#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# test.sh - Run tests for the Generic Launcher
# ============================================================
#
# Runs inside the flake's dev shell (JDK 17, Android SDK 35) — it re-execs itself under
# `nix develop` so the toolchain can't drift. See tools/nix-env.sh.
# Set LAUNCHER_NO_NIX=1 to use whatever is on PATH instead.
#
# Usage:
#   ./test.sh                # Run all unit tests
#   ./test.sh --verbose      # Run with verbose output
#   ./test.sh --class NAME   # Run a specific test class
# ============================================================

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF="$HERE/$(basename "$0")"
cd "$HERE"

# Re-exec inside the pinned toolchain before touching java/gradle. No-op on the inner run.
# shellcheck source=tools/nix-env.sh
source "$HERE/tools/nix-env.sh"
launcher_nix_reexec "$SELF" "$@"

VERBOSE=""
CLASS=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --verbose|-v)
            VERBOSE="--info"
            shift
            ;;
        --class|-c)
            CLASS="--tests $2"
            shift 2
            ;;
        *)
            echo "Usage: $0 [--verbose] [--class TestClassName]"
            exit 1
            ;;
    esac
done

# --- Check prerequisites ---
# Inside the dev shell these always hold; they are the diagnostic for LAUNCHER_NO_NIX=1.
if ! command -v java &>/dev/null; then
    echo "ERROR: java not found. Install JDK 17+ and set JAVA_HOME,"
    echo "  or unset LAUNCHER_NO_NIX and let the script enter the nix shell."
    exit 1
fi

if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
    echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT not set."
    exit 1
fi

# --- Require the committed Gradle wrapper ---
# Deliberately fails instead of regenerating. `gradle wrapper` rewrites
# gradle-wrapper.properties to whatever gradle happens to be on PATH: this ran at 8.2, which
# AGP 8.7.3 rejects (it needs >= 8.9), so the next build failed with an error that reads like a
# broken environment while the rewritten pin looked like a deliberate change in the diff. That
# cost a reviewer an entire "the test suite is unverified" conclusion. The wrapper is committed;
# a missing one is a broken checkout, not something to paper over.
if [[ ! -x ./gradlew ]]; then
    echo "ERROR: ./gradlew is missing or not executable."
    echo "  It is committed — restore it with:  git checkout -- gradlew && chmod +x gradlew"
    echo "  Do NOT run 'gradle wrapper': it would overwrite the 8.11.1 pin."
    exit 1
fi

echo "=== Running Unit Tests ==="
echo "    java:   $(java -version 2>&1 | head -1)"
echo "    gradle: ./gradlew (the committed pin)"
echo ""

# Run local unit tests (JVM-based, no device needed).
# Robolectric provides Android framework stubs.
# ./gradlew, not the shell's `gradle`: the wrapper is the version the pin actually names,
# and perf.sh uses the same one so the suite and the perf gate agree.
launcher_gradle_args
./gradlew ${LAUNCHER_GRADLE_EXTRA[@]+"${LAUNCHER_GRADLE_EXTRA[@]}"} testDebugUnitTest $VERBOSE $CLASS

echo ""
echo "=== Test Results ==="
REPORT="app/build/reports/tests/testDebugUnitTest/index.html"
if [[ -f "$REPORT" ]]; then
    echo "HTML report: $REPORT"
    # Try to open in browser
    if command -v open &>/dev/null; then
        open "$REPORT" 2>/dev/null || true
    elif command -v xdg-open &>/dev/null; then
        xdg-open "$REPORT" 2>/dev/null || true
    fi
fi

# --- Summary from the per-class JUnit XML ---
# awk, not `grep -oP`: BSD grep (stock macOS) has no -P, so the old version worked here only
# because `grep` happened to be ugrep. Silently printing "Total: 0 | Failed: 0" and then
# "ALL TESTS PASSED" is exactly the failure mode this harness keeps hitting — a check that
# succeeds while measuring nothing.
#
# nullglob rather than `compgen -G` to test for results: the bash inside `nix develop` is
# built without programmable completion, so compgen is "command not found" there — which
# is the whole point of running the scripts in the shell they will actually run in.
XML_DIR="app/build/test-results/testDebugUnitTest"
shopt -s nullglob
XMLS=("$XML_DIR"/*.xml)
shopt -u nullglob

if [[ ${#XMLS[@]} -gt 0 ]]; then
    read -r TOTAL FAILED SKIPPED FILES < <(awk -f tools/junit-summary.awk "${XMLS[@]}")

    echo ""
    echo "Total: $TOTAL | Passed: $((TOTAL - FAILED - SKIPPED)) | Failed: $FAILED | Skipped: $SKIPPED  ($FILES classes)"
    if [[ $TOTAL -eq 0 ]]; then
        echo ""
        echo "NO TESTS RAN — $FILES result files, zero tests. Treat this as a failure:"
        echo "  a green summary over an empty run is worse than a red one."
        exit 1
    fi
    if [[ $FAILED -gt 0 ]]; then
        echo ""
        echo "TESTS FAILED"
        exit 1
    fi
    echo ""
    echo "ALL TESTS PASSED"
else
    echo ""
    echo "ERROR: no test XML under $XML_DIR — the run produced no results."
    exit 1
fi
