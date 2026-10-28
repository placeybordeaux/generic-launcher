#!/usr/bin/env bash
# ============================================================
# perf.sh — performance regression gate for the launcher
# ============================================================
# Speed to open and memory footprint are the product here, so they get the same
# treatment as correctness: a recorded baseline, and a check that fails on drift.
#
#   ./perf.sh run                 run the benchmarks, print results (no gating)
#   ./perf.sh baseline            run, then record the numbers as the new baseline
#   ./perf.sh check               run, diff against the baseline, exit 1 on regression
#   ./perf.sh show                print the recorded baseline without running anything
#
# Options (before the command):
#   --startup-only  |  --jank-only     run one suite instead of both
#
# Requires: a physical device on adb, and nix (the toolchain lives in flake.nix). The script
# re-execs itself under `nix develop` so the benchmarks are built by the pinned toolchain;
# LAUNCHER_NO_NIX=1 opts out. See tools/nix-env.sh.
#
# WHY A PHYSICAL DEVICE. Macrobenchmark numbers from an emulator are not comparable
# between runs on a laptop — they track host CPU contention more than app behaviour.
# The flake deliberately sets includeEmulator = false. Plug the phone in.
#
# WHY THE BASELINE IS COMMITTED. perf/baseline.json is checked in on purpose: the
# point is to notice the commit that made things slower, which requires knowing what
# "before" was. Re-baseline deliberately (./perf.sh baseline) when a change is a real,
# accepted trade-off — never to make a red check go green.
# ============================================================
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF="$HERE/$(basename "$0")"
cd "$HERE"

# Re-exec inside the pinned toolchain before anything else. This also supplies adb, since
# the dev shell puts the SDK's platform-tools on PATH. No-op on the inner run.
# shellcheck source=tools/nix-env.sh
source "$HERE/tools/nix-env.sh"
launcher_nix_reexec "$SELF" "$@"

BASELINE="perf/baseline.json"
LATEST="perf/latest.json"
# Benchmark output lands here; the module writes one JSON per test class.
RESULTS_DIR="macrobenchmark/build/outputs/connected_android_test_additional_output"

# How much worse a metric may get before `check` fails. Chosen from observed run-to-run
# noise on a physical device: startup timings are stable to a few percent, memory to
# about one, and frame timings are the noisiest because they are quantised to the
# display's refresh interval. A threshold below the noise floor produces flaky failures,
# which is worse than no gate at all — people learn to ignore it.
THRESH_STARTUP=15   # percent
THRESH_MEMORY=10    # percent
THRESH_FRAME=20     # percent

# --- Locate adb (mirrors dev-debug.sh) ---
if ! command -v adb >/dev/null 2>&1; then
    for p in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        [[ -x "$p/platform-tools/adb" ]] && export PATH="$p/platform-tools:$PATH" && break
    done
fi

# ./gradlew (8.11.1, the committed pin), not the shell's `gradle` (8.14.3, whatever nixpkgs
# ships). Both clear AGP's ">= 8.9" floor so the split never errored — it just meant the perf
# gate and the test suite were built by different Gradles, which is not a difference you want
# turning up inside a performance number.
if [[ ! -x ./gradlew ]]; then
    echo "ERROR: ./gradlew is missing or not executable."
    echo "  It is committed — restore it with:  git checkout -- gradlew && chmod +x gradlew"
    echo "  Do NOT run 'gradle wrapper': it would overwrite the 8.11.1 pin."
    exit 1
fi
launcher_gradle_args
GRADLE=(./gradlew ${LAUNCHER_GRADLE_EXTRA[@]+"${LAUNCHER_GRADLE_EXTRA[@]}"})

FILTER=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --startup-only) FILTER="-Pandroid.testInstrumentationRunnerArguments.class=com.genericlauncher.launcher.macrobenchmark.StartupBenchmark"; shift ;;
        --jank-only)    FILTER="-Pandroid.testInstrumentationRunnerArguments.class=com.genericlauncher.launcher.macrobenchmark.JankBenchmark"; shift ;;
        *) break ;;
    esac
done

CMD="${1:-run}"

require_device() {
    command -v adb >/dev/null 2>&1 || { echo "ERROR: adb not found (set ANDROID_HOME)."; exit 1; }
    local n
    n=$(adb devices | grep -cw "device" || true)
    if [[ "$n" -eq 0 ]]; then
        echo "ERROR: no device on adb. Plug in the phone, unlock it, and accept USB debugging."
        echo "       Benchmarks need real hardware — see the note at the top of this script."
        exit 1
    fi
    if [[ "$n" -gt 1 ]]; then
        echo "ERROR: $n devices attached. Macrobenchmark will not pick one for you."
        echo "       Detach the others or set ANDROID_SERIAL."
        exit 1
    fi
}

# Reduce the two largest sources of run-to-run variance before measuring. Neither is
# permanent; both revert on reboot.
stabilise_device() {
    echo "--- preparing device ---"
    # A screen that blanks mid-run parks the CPU in a low-power state and inflates every
    # subsequent measurement.
    adb shell svc power stayon usb >/dev/null 2>&1 || true
    # Charging state changes the governor's willingness to boost. Nothing to do about it
    # beyond reporting it, so the number can be interpreted later.
    local lvl temp batt
    batt=$(adb shell dumpsys battery | tr -d '\r')
    # Match the key exactly. A bare /level:/ also matches OnePlus's "Capacity level: 3",
    # yielding a two-line value that silently breaks the numeric comparison below.
    lvl=$(awk '$1=="level:"{print $2}' <<<"$batt")
    temp=$(awk '$1=="temperature:"{print $2}' <<<"$batt")
    echo "battery ${lvl:-?}%, temperature $(( ${temp:-0} / 10 ))C"
    if [[ -n "$temp" && "$temp" -gt 380 ]]; then
        echo "WARNING: device is above 38C. Thermal throttling will inflate these numbers."
        echo "         Let it cool before trusting a 'check' result."
    fi
    # Macrobenchmark hard-fails below 25% (its LOW-BATTERY check, and rightly: a device that
    # low is throttling). We already have the number, so say so now rather than after a full
    # build and five benchmark iterations — the failure that costs is the one you wait for.
    # Only gate on a level we actually parsed; an unreadable dumpsys is not a low battery.
    if [[ -n "$lvl" && "$lvl" =~ ^[0-9]+$ && "$lvl" -lt 25 ]]; then
        echo ""
        echo "ERROR: battery is ${lvl}%, below the 25% floor macrobenchmark enforces."
        echo "  It will fail every test with LOW-BATTERY. Charge past 25% and re-run."
        echo "  (Plugged in is not enough — the check reads the level, not the charging state.)"
        exit 1
    fi
}

run_benchmarks() {
    require_device
    stabilise_device
    echo ""
    echo "--- running benchmarks (this takes several minutes) ---"
    rm -rf "$RESULTS_DIR"
    # No suppressErrors override on purpose. Macrobenchmark hard-fails by default when the
    # device is in a state known to produce meaningless numbers — emulator, debuggable target,
    # low battery. Those errors are the harness working; suppressing them to get a green run
    # would produce numbers that mean nothing, which is the one outcome worse than no numbers.
    "${GRADLE[@]}" :macrobenchmark:connectedBenchmarkAndroidTest $FILTER 2>&1 | tail -40
    local rc=${PIPESTATUS[0]}
    if [[ $rc -ne 0 ]]; then
        echo ""
        echo "Benchmark run FAILED. Common causes:"
        echo "  - device locked, or asleep (benchmarks drive the UI and need the screen on)"
        echo "  - a macrobenchmark environment check tripped — read the error above rather"
        echo "    than reaching for androidx.benchmark.suppressErrors"
        exit $rc
    fi
    mkdir -p perf
    python3 tools/perf_report.py collect "$RESULTS_DIR" "$LATEST"
}

case "$CMD" in
    run)
        run_benchmarks
        python3 tools/perf_report.py show "$LATEST"
        ;;
    baseline)
        run_benchmarks
        cp "$LATEST" "$BASELINE"
        echo ""
        echo "Baseline recorded in $BASELINE — commit it."
        python3 tools/perf_report.py show "$BASELINE"
        ;;
    check)
        [[ -f "$BASELINE" ]] || { echo "ERROR: no $BASELINE. Run './perf.sh baseline' first."; exit 1; }
        run_benchmarks
        python3 tools/perf_report.py diff "$BASELINE" "$LATEST" \
            "$THRESH_STARTUP" "$THRESH_MEMORY" "$THRESH_FRAME"
        ;;
    show)
        [[ -f "$BASELINE" ]] || { echo "ERROR: no $BASELINE yet."; exit 1; }
        python3 tools/perf_report.py show "$BASELINE"
        ;;
    *)
        echo "Usage: $0 [--startup-only|--jank-only] {run|baseline|check|show}"
        exit 1
        ;;
esac
