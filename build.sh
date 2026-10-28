#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# build.sh - Build the Generic Launcher Android app
# ============================================================
#
# Runs inside the flake's dev shell (JDK 17, Android SDK 35) — it re-execs itself under
# `nix develop` so the toolchain can't drift. See tools/nix-env.sh.
# Set LAUNCHER_NO_NIX=1 to use whatever is on PATH instead.
#
# Usage:
#   ./build.sh              # Debug build
#   ./build.sh release      # Release build
#   ./build.sh clean        # Clean build artifacts
#   ./build.sh install [FLAVOUR]  # Build + install one flavour's debug APK (default: plain)
# ============================================================

HERE="$(cd "$(dirname "$0")" && pwd)"
SELF="$HERE/$(basename "$0")"
cd "$HERE"

# Re-exec inside the pinned toolchain before touching java/gradle. No-op on the inner run.
# shellcheck source=tools/nix-env.sh
source "$HERE/tools/nix-env.sh"
launcher_nix_reexec "$SELF" "$@"

# --- Check prerequisites ---
# Inside the dev shell these always hold; they are the diagnostic for LAUNCHER_NO_NIX=1.
check_prereqs() {
    if ! command -v java &>/dev/null; then
        echo "ERROR: java not found. Install JDK 17+ and set JAVA_HOME,"
        echo "  or unset LAUNCHER_NO_NIX and let the script enter the nix shell."
        exit 1
    fi

    # `java -version` prints e.g.  openjdk version "17.0.12" 2024-07-16 LTS
    # This used to be `sed 's/.*"\([0-9]*\).*/\1/'`, whose greedy .* ran past the *closing*
    # quote and captured the empty string — so the comparison below was "" -lt 17, which
    # bash reads as 0, and the script rejected every JDK including the pinned one. It went
    # unnoticed because nobody ran build.sh; the documented command was gradle directly.
    java_version_full=$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
    java_version=${java_version_full%%.*}
    # Pre-9 JDKs report 1.8.0_xxx; the feature number is the second field.
    if [[ "$java_version" == "1" ]]; then
        java_version=${java_version_full#1.}
        java_version=${java_version%%.*}
    fi
    if [[ -z "$java_version" ]]; then
        echo "ERROR: could not parse a version from: $(java -version 2>&1 | head -1)"
        exit 1
    fi
    if [[ "$java_version" -lt 17 ]]; then
        echo "ERROR: Java 17+ required (found version $java_version)."
        exit 1
    fi
    # Not just a floor: the flake pins 17 because AGP's JdkImageTransform runs `jlink` from
    # the JVM Gradle itself runs on, and JDK 21's jlink fails on android's
    # core-for-system-modules.jar. A newer JDK is a different toolchain, not a better one.
    if [[ "$java_version" -ne 17 && -z "${LAUNCHER_IN_NIX_SHELL:-}" ]]; then
        echo "WARNING: Java $java_version, but the pinned toolchain is 17 (see flake.nix)."
        echo "         A build that passes here has not exercised the pin."
    fi

    if [[ -z "${ANDROID_HOME:-}" && -z "${ANDROID_SDK_ROOT:-}" ]]; then
        echo "ERROR: ANDROID_HOME or ANDROID_SDK_ROOT not set."
        echo "  Install Android SDK and set the environment variable."
        echo "  Common paths:"
        echo "    macOS:   ~/Library/Android/sdk"
        echo "    Linux:   ~/Android/Sdk"
        echo "    Windows: %LOCALAPPDATA%/Android/Sdk"
        exit 1
    fi

    echo "OK: Java $(java -version 2>&1 | head -1)"
    echo "OK: ANDROID_HOME=${ANDROID_HOME:-${ANDROID_SDK_ROOT}}"
}

# --- Ensure Gradle wrapper exists ---
ensure_gradlew() {
    # Fails rather than regenerating — see the note in test.sh for what regenerating cost.
    if [[ ! -x ./gradlew ]]; then
        echo "ERROR: ./gradlew is missing or not executable."
        echo "  It is committed — restore it with:  git checkout -- gradlew && chmod +x gradlew"
        echo "  Do NOT run 'gradle wrapper': it would overwrite the 8.11.1 pin."
        exit 1
    fi
}

# --- Main ---
check_prereqs
ensure_gradlew
launcher_gradle_args

# ./gradlew, never the shell's `gradle`: the wrapper is the 8.11.1 pin, and test.sh and
# perf.sh use the same one so everything is built by one Gradle.
gradlew() { ./gradlew ${LAUNCHER_GRADLE_EXTRA[@]+"${LAUNCHER_GRADLE_EXTRA[@]}"} "$@"; }

case "${1:-debug}" in
    clean)
        echo ""
        echo "=== Cleaning ==="
        gradlew clean
        echo "Done."
        ;;
    release)
        echo ""
        echo "=== Building Release APKs ==="
        gradlew assembleRelease
        echo ""
        echo "APKs: app/build/outputs/apk/*/release/"
        ;;
    install)
        # A flavour has to be named: with product flavours `installDebug` is ambiguous and Gradle
        # refuses it, which is how this subcommand stayed broken from the day `color` was added.
        flavour="${2:-plain}"
        echo ""
        echo "=== Building & Installing the $flavour debug APK ==="
        gradlew "install${flavour^}Debug"
        echo ""
        echo "Installed. A launcher has no icon to open: press Home and pick it as the home app"
        echo "(or Settings > Apps > Default apps > Home app)."
        ;;
    debug|*)
        echo ""
        echo "=== Building Debug APKs ==="
        gradlew assembleDebug
        echo ""
        echo "APKs: app/build/outputs/apk/*/debug/"
        echo ""
        echo "To install on a device:  ./build.sh install [plain|color]"
        echo "To build release:        ./build.sh release"
        ;;
esac
