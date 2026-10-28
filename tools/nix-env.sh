# shellcheck shell=bash
# ============================================================
# tools/nix-env.sh — put every script in the pinned toolchain
# ============================================================
# Sourced by test.sh, build.sh, perf.sh and dev-debug.sh. Not executable on its own.
#
# WHY. flake.nix is the build environment: JDK 17, Gradle pinned to that JDK, Android SDK
# 35 / build-tools 35.0.0, and the Linux aapt2 override. There is no usable system Java on
# this machine (/usr/bin/java is the macOS stub), so a script that shells out to whatever
# `java` it finds either fails or — worse — silently finds Android Studio's bundled JBR,
# which is JDK 21. That is not the pinned toolchain: the flake warns that JDK 21's `jlink`
# breaks AGP's JdkImageTransform. An entire round of fix verification once ran that way and
# passed, which proved nothing. So the scripts re-exec themselves inside `nix develop`
# rather than trusting the ambient environment.
#
# ESCAPE HATCH. LAUNCHER_NO_NIX=1 skips the re-exec and uses whatever is on PATH — for CI
# runners and for people who provision JDK 17 + the SDK some other way. The scripts still
# check for java/ANDROID_HOME afterwards, so that path fails with a real message.
#
# LOOP GUARD. LAUNCHER_IN_NIX_SHELL is the sentinel that says "this is the inner
# invocation". It is exported *and* re-stated through `env` on the inner command line, so a
# nix that declined to forward the environment still cannot produce an exec loop.

# The sentinel. Deliberately ours and not IN_NIX_SHELL: being inside *some* nix shell says
# nothing about being inside *this* flake's shell.
LAUNCHER_NIX_SENTINEL="LAUNCHER_IN_NIX_SHELL"

# True when the re-exec should be skipped: already inside, or opted out.
_launcher_nix_skip() {
    [[ -n "${LAUNCHER_IN_NIX_SHELL:-}" || -n "${LAUNCHER_NO_NIX:-}" ]]
}

_launcher_need_nix() {
    if ! command -v nix >/dev/null 2>&1; then
        echo "ERROR: nix not found, and LAUNCHER_NO_NIX is not set." >&2
        echo "  The build environment (JDK 17, Android SDK 35, Gradle) lives in flake.nix." >&2
        echo "  Install nix with flakes enabled, or — if you have provisioned JDK 17 and the" >&2
        echo "  Android SDK yourself — re-run with:  LAUNCHER_NO_NIX=1 ./$1" >&2
        echo "  Note that LAUNCHER_NO_NIX=1 means the toolchain is unverified: a build that" >&2
        echo "  passes on Android Studio's bundled JDK 21 has not exercised the pin." >&2
        exit 1
    fi
}

# launcher_nix_reexec <abs-path-to-self> "$@"
# Replaces the current process with the same script running inside the flake's dev shell.
# Returns (does nothing) when already inside or when LAUNCHER_NO_NIX is set.
launcher_nix_reexec() {
    local self="$1"; shift
    _launcher_nix_skip && return 0
    _launcher_need_nix "$(basename "$self")"
    local root
    root="$(cd "$(dirname "$self")" && pwd)"
    export "$LAUNCHER_NIX_SENTINEL=1"
    exec nix develop "$root" -c env "$LAUNCHER_NIX_SENTINEL=1" "$self" "$@"
}

# Extra Gradle arguments the dev shell asks for, as an array. Expand it as
#   ${LAUNCHER_GRADLE_EXTRA[@]+"${LAUNCHER_GRADLE_EXTRA[@]}"}
# — the bare form trips `set -u` on an empty array under bash 3.2, which is what
# /bin/bash still is on macOS.
# flake.nix exports LAUNCHER_GRADLE_ARGS: on Linux it carries the aapt2 override (AGP's
# bundled aapt2 is a prebuilt ELF that will not run under Nix's loader); on Darwin it is
# empty, because the downloaded mach-o binary runs natively.
launcher_gradle_args() {
    LAUNCHER_GRADLE_EXTRA=()
    if [[ -n "${LAUNCHER_GRADLE_ARGS:-}" ]]; then
        # Deliberate word splitting: the variable is a flat argument string from the flake.
        read -r -a LAUNCHER_GRADLE_EXTRA <<<"$LAUNCHER_GRADLE_ARGS"
    fi
}
