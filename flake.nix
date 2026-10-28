{
  description = "Generic Launcher — Android build environment (JDK 17 + Android SDK + Gradle)";

  # Tarball URL (codeload) instead of github: ref to avoid the GitHub API rate limit.
  inputs.nixpkgs.url = "https://github.com/NixOS/nixpkgs/archive/refs/heads/nixos-25.05.tar.gz";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-darwin" "aarch64-darwin" "x86_64-linux" "aarch64-linux" ];
      forAll = f: nixpkgs.lib.genAttrs systems (system: f system);
    in
    {
      devShells = forAll (system:
        let
          pkgs = import nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };

          # Versions pinned to app/build.gradle (compileSdk 36, build-tools 35.0.0)
          # and the Gradle wrapper (gradle 8.11.1 / AGP 8.10.1). SDK 36 is Play's
          # target-API floor from 31 Aug 2026 — a policy floor, not a build error, so
          # nothing in the Gradle output warns you about it. Kept identical to
          # the flake of the launcher this was extracted from, so the two share one SDK
          # in the nix store.
          android = pkgs.androidenv.composeAndroidPackages {
            platformVersions = [ "35" "36" ];
            buildToolsVersions = [ "35.0.0" "36.0.0" ];
            cmdLineToolsVersion = "11.0";
            includeEmulator = false;
            includeSystemImages = false;
            includeSources = false;
            includeNDK = false;
          };

          androidSdk = android.androidsdk;
          sdkRoot = "${androidSdk}/libexec/android-sdk";
          jdk = pkgs.jdk17;

          # AGP's JdkImageTransform runs `jlink` from the JVM that Gradle
          # itself runs on. nixpkgs' gradle defaults to JDK 21, whose jlink fails
          # on android-34's core-for-system-modules.jar. Pin Gradle to JDK 17.
          gradle = pkgs.gradle.override { java = jdk; };

          # On Linux the AGP-bundled aapt2 is a prebuilt ELF that won't run under
          # Nix's loader, so point Gradle at the SDK's patched aapt2. On Darwin the
          # downloaded mach-o binary runs natively, so the override is skipped.
          aapt2Override = pkgs.lib.optionalString pkgs.stdenv.isLinux
            "-Pandroid.aapt2FromMavenOverride=${sdkRoot}/build-tools/35.0.0/aapt2";
        in
        {
          default = pkgs.mkShell {
            packages = [ jdk gradle androidSdk ];

            JAVA_HOME = "${jdk}";
            ANDROID_HOME = sdkRoot;
            ANDROID_SDK_ROOT = sdkRoot;

            # Read by tools/nix-env.sh and spliced into every ./gradlew invocation the
            # scripts make. Empty on Darwin. Without this the override existed only in the
            # banner text below, where nothing could consume it.
            LAUNCHER_GRADLE_ARGS = aapt2Override;

            # Note the commands: ./gradlew, not gradle. The wrapper pins 8.11.1; the gradle
            # on this shell's PATH is whatever nixpkgs ships (8.14.3). Both clear AGP's
            # ">= 8.9" floor, so mixing them produces no error — it just means the test
            # suite and the perf gate are not built by the same Gradle.
            shellHook = ''
              echo "Generic Launcher dev shell"
              echo "  JDK:          ${jdk}"
              echo "  ANDROID_HOME: ${sdkRoot}"
              echo "  gradle (PATH): $(gradle --version 2>/dev/null | awk '/^Gradle/{print $2}')  — prefer ./gradlew (8.11.1, the committed pin)"
              echo
              echo "Build:  ./gradlew assembleDebug ${aapt2Override}"
              echo "Test:   ./gradlew testDebugUnitTest ${aapt2Override}"
              echo "        (./test.sh, ./build.sh and ./perf.sh re-enter this shell themselves)"
            '';
          };
        });
    };
}
