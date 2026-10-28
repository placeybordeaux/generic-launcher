#!/usr/bin/env bash
# ============================================================
# dev-debug.sh — on-device debug harness for the launcher
# ============================================================
# The launcher is the default HOME app, so it can't be "opened" like a normal
# app and its crashes vanish behind the wallpaper. This wraps the adb incantations
# that actually matter when debugging on a real device.
#
#   ./dev-debug.sh build [FLAVOUR]  nix build + install a debug APK (default: plain)
#   ./dev-debug.sh crash         dump the crash buffer for our package (the important one)
#   ./dev-debug.sh watch         live-stream FATAL crashes + our logs (Ctrl-C to stop)
#   ./dev-debug.sh errors        recent AndroidRuntime/our-package errors from main buffer
#   ./dev-debug.sh shot [name]   screenshot to ./debug-shots/<name>.png (default: timestamped)
#   ./dev-debug.sh store         print the persisted homescreen.json (pretty)
#   ./dev-debug.sh strict        dump StrictMode violations (main-thread I/O, leaks)
#   ./dev-debug.sh restart       force-stop then relaunch, clearing the log buffers first
#   ./dev-debug.sh awake on|off  keep the screen on while plugged in (stops dozing mid-test)
#   ./dev-debug.sh version       show installed vs. local HEAD (are they the same build?)
#
# Every subcommand but build talks to the plain debug install; LAUNCHER_PKG aims them elsewhere,
# e.g. LAUNCHER_PKG=com.genericlauncher.livedin.color.debug ./dev-debug.sh crash
#
# Requires: adb on PATH (or ANDROID_HOME/ANDROID_SDK_ROOT set).
# ============================================================
set -uo pipefail

# The debug install, not the bare applicationId: debug builds carry a ".debug" suffix (see
# app/build.gradle), so the bare id is a package nothing here ever installs.
PKG="${LAUNCHER_PKG:-com.genericlauncher.livedin.debug}"
# applicationId != Java namespace, so the component needs the full class name.
ACT="$PKG/com.genericlauncher.launcher.MainActivity"
HERE="$(cd "$(dirname "$0")" && pwd)"

# Before the adb lookup: build only needs the toolchain, and build.sh enters the nix shell
# itself, so it must work on a machine with no adb outside the shell. The launcher this was
# extracted from shipped exactly that bug ("adb not found" from ./dev-debug.sh build) until 2026-09-06.
if [[ "${1:-}" == build ]]; then
    shift
    # Delegated to build.sh rather than duplicating the incantation: build.sh already enters
    # the nix shell and uses ./gradlew (8.11.1, the committed pin) rather than the shell's gradle.
    exec "$HERE/build.sh" install "$@"
fi

# Locate adb.
if ! command -v adb >/dev/null 2>&1; then
    for p in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk"; do
        [[ -x "$p/platform-tools/adb" ]] && export PATH="$p/platform-tools:$PATH" && break
    done
fi
command -v adb >/dev/null 2>&1 || { echo "adb not found (set ANDROID_HOME)"; exit 1; }

# Only the crash-relevant lines: our package, fatal exceptions, and stack frames.
CRASH_FILTER='genericlauncher|FATAL|AndroidRuntime|Caused by|^[[:space:]]*at |RemoteViews|Error inflating'

cmd="${1:-}"; shift || true
case "$cmd" in
  crash)
    echo "=== crash buffer (most recent FATAL for $PKG) ==="
    # -b crash survives the app dying and the screen locking; this is the workhorse.
    adb logcat -b crash -d 2>/dev/null | grep -iE "$CRASH_FILTER" | tail -80 ;;

  watch)
    echo "=== live: FATAL crashes + $PKG logs (Ctrl-C to stop) ==="
    adb logcat -c
    adb logcat AndroidRuntime:E "$PKG":V WidgetDbg:V "*:S" 2>/dev/null ;;

  errors)
    echo "=== recent errors (main buffer) ==="
    adb logcat -d 2>/dev/null | grep -iE "$CRASH_FILTER" | tail -60 ;;

  shot)
    mkdir -p "$HERE/debug-shots"
    # No Date.now in scripts here — use adb's clock via the device.
    name="${1:-shot-$(adb shell date +%H%M%S | tr -d '\r')}"
    out="$HERE/debug-shots/$name.png"
    adb exec-out screencap -p > "$out" && echo "saved $out" ;;

  store)
    adb exec-out run-as "$PKG" cat files/homescreen.json 2>/dev/null \
      | python3 -m json.tool 2>/dev/null \
      || echo "(no store yet, or run-as unavailable on a release build)" ;;

  strict)
    # StrictMode violations from the debug build (LauncherApp.enableStrictModeIfDebug).
    # Main-thread disk I/O is the one that matters: it is invisible in normal use and shows up
    # in benchmarks only as unexplained drift. Framework/OEM frames in the trace are expected —
    # look for com.genericlauncher frames.
    echo "=== StrictMode violations (debug build only) ==="
    adb logcat -d -s StrictMode:* 2>/dev/null | tail -60
    echo
    echo "(none above? the debug build may not be running, or nothing has violated yet)" ;;

  restart)
    adb logcat -c
    adb shell am force-stop "$PKG"
    adb shell am start -n "$ACT" >/dev/null 2>&1
    echo "restarted; use './dev-debug.sh crash' if it dies" ;;

  awake)
    case "${1:-on}" in
      on)  adb shell svc power stayon true;  echo "screen stays on while charging" ;;
      off) adb shell svc power stayon false; echo "restored normal screen timeout" ;;
    esac ;;

  version)
    dev="$(adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d '\r' | xargs)"
    head="$(cd "$HERE" && git rev-parse --short HEAD 2>/dev/null)"
    echo "installed:  $dev"
    echo "local HEAD: $head  (build embeds this as BuildConfig.GIT_HASH)" ;;

  *)
    grep -E '^#( |=)' "$0" | sed 's/^# \{0,1\}//' ;;
esac
