# Generic Launcher

> *This README was written by Claude (Anthropic's AI model).*

An Android home-screen launcher that does the expected things — home screen with widgets and
folders, an app drawer with search, notification badges — and is built to be **forked and
personalized**, mostly by editing one file.

The idea is that taste lives in code, not in a settings screen. A personality is a small diff:
point `Personality.DRAWER` at a different strategy and the whole drawer re-sorts; give it a
`Background`, a `DrawerItemDecorator` or a `BadgeAppearance` and it looks different. Everything a
fork is likely to want to change sits behind five seams, listed with an intent→file recipe table
in **[`docs/SEAM.md`](docs/SEAM.md) — start there.**

It was extracted from [Hue-istic](https://hueistic.com), a launcher that sorts your apps by the
colour of their icons. The colour identity was taken out of the default and put back as the
worked example behind the seam — the `color` flavour below.

## Branches

`main` is the taste-free base. The other branches are personalities, each one a single commit on
top of `main`, so `git diff main <branch>` is the whole of what it took:

| Branch | What it is |
|---|---|
| `main` | The base: alphabetical drawer, no opinions. Also carries a `color` flavour as the worked example of the seam — apps grouped by icon colour, a rainbow background — built with no edits to the launcher core. |
| `wobble` | Every app icon hangs from a push-pin and swings when you tilt the phone. See `WOBBLE.md`. |
| `lived-in` | Icons age with use: the ones you reach for get a worn patina, the ones you never open gather dust and, eventually, cobwebs. See `LIVEDIN.md`. |

Each personality has its own `applicationId`, so they install side by side.

## Building

The toolchain comes from a nix flake (JDK 17, pinned Gradle, Android SDK 36). A bare `./gradlew`
will not find a JDK.

```
nix develop -c ./gradlew assemblePlainDebug   # the taste-free default
nix develop -c ./gradlew assembleColorDebug   # the worked example, installs alongside it
./build.sh install plain                      # build and install on a connected device
```

The two flavours carry different `applicationId`s and labels ("Launcher Debug" / "Launcher Color
Debug"), so they coexist on one device, and a test build never inherits your daily driver's home
screen and widget IDs.

## Check your work — three gates, cheapest first

```
# 1. Pure JVM, ~1s, no SDK and no device. The loop for anyone writing a strategy.
javac -d out $(find src example test -name '*.java') && java -cp out SeamCheck

# 2. The app module: both flavours compile, every personality's suite passes.
nix develop -c ./gradlew assembleDebug testDebugUnitTest

# 3. Device required. See CLAUDE.md before trusting a green run.
./perf.sh check
```

Gate 1 compiles **`src/` and `example/` both** — `SeamCheck` asserts the `DrawerStrategy`
contract against the colour example as well as the defaults, so leaving `example` out of the
`find` fails to compile rather than quietly checking less.

`SeamCheck` cannot see `Personality`, which lives in a flavour source set;
`DrawerStrategyContractTest` runs the same assertions inside the app module, where the active
personality is resolved. **Both, or neither is enough.**

## Status

Both flavours build, the Robolectric suites are green (~800 tests each), lint gates the build,
and both have been installed and run as the default home app on a physical phone (Android 16).

Driven by hand so far: widget placement and resize, the colour flavour, holding the HOME role
(Home presses, cold start after force-stop). **Not yet driven by hand**, though each has test
coverage: dragging from the drawer to the home screen, folders, home-item long-press actions,
the usage-sorted drawer, notification badges, the settings screen, and export/import of the home
screen. Treat a green suite as necessary, not sufficient.

The perf harness runs and `perf/baseline.json` is recorded, but it is not yet a trustworthy gate —
see "The recorded baseline" in [`CLAUDE.md`](CLAUDE.md).

## Layout

```
src/com/genericlauncher/launcher/personality/   pure Java, JVM-verifiable, compiled into every flavour
  DrawerApp.java        framework-free view of one app (real AppInfo implements this)
  DrawerSection.java    a titled run of apps ("" title = headerless)
  DrawerStrategy.java   THE seam: order + group the drawer; contract in the javadoc
  DrawerQuery.java      the search filter the caller runs before organize()
  strategy/
    AlphabeticalStrategy.java   default: A–Z, "#" bucket last
    UsageStrategy.java          most-used first, flat
example/                        pure Java, compiled into the `color` flavour only
  .../personality/example/ColorGroups.java          icon-colour classifier, ported + tested
  .../personality/example/ColorGroupStrategy.java   groups apps into rainbow sections
app/src/main/java/com/genericlauncher/launcher/
  personality/          the android.*-bound seams: Background, DrawerItemDecorator,
                        BadgeAppearance, AppDataSink
  ...                   the launcher spine (MainActivity, HomescreenView, WidgetPicker, …)
app/src/plain/ · app/src/color/   one Personality.java each — the file a fork edits
app/src/test/                     must pass for ANY personality
app/src/testPlain/                asserts the default has no taste; a colour build fails these
test/SeamCheck.java               dependency-free executable contract check
macrobenchmark/                   device-only perf suite; runs solely via ./perf.sh
docs/SEAM.md                      the seam manifest + recipe table (start here to personalize)
CLAUDE.md                         toolchain traps, on-device findings, invariants
```

A personality is **one file** — that is enforced, not conventional: two product flavours cannot
contribute the same class, so a personality that tried to override a spine class would fail to
compile rather than quietly succeed.

## License

[MIT](LICENSE).
