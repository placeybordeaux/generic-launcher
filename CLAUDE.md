# Generic Launcher — working notes

A taste-free Android launcher, built to be forked and personalized — mostly by editing one file.
Extracted from a colour-sorting launcher; the colour identity is removed from the default and
re-added as the reference example behind the personality seam (the `color` flavour).

## The one thing to understand: the personality seam

Almost everything a fork changes lives behind five seams — `DrawerStrategy`, `Background`,
`DrawerItemDecorator`, `BadgeAppearance`, `AppDataSink`. Edit a seam; leave the launcher spine
alone. The full manifest and the intent→file recipe table are in **`docs/SEAM.md` — start there.**

The intended interaction is **edit code, not add settings.** Taste is expressed as a small diff,
not an options screen. Point `Personality.DRAWER` at a different strategy and you've re-skinned the
whole drawer.

A personality is **one file**: `app/src/<flavour>/java/.../personality/Personality.java`. That is
not a convention — two product flavours cannot contribute the same class, so a personality that
needed to override a spine class would fail to compile rather than quietly succeed.

```
./gradlew assemblePlainDebug     # the taste-free default
./gradlew assembleColorDebug     # the worked example, installs alongside it
```

## How to check your work

Three gates, cheapest first:

```
javac -d out $(find src example test -name '*.java') && java -cp out SeamCheck
nix develop -c ./gradlew assembleDebug testDebugUnitTest
./perf.sh check        # device required; see "On device" below
```

`SeamCheck` is the fast loop for anyone writing a strategy: a bare JVM, no Android SDK, no device,
about a second. It compiles the two pure-Java trees — `src/` (the seam and its default strategies)
and `example/` (the colour example) — and asserts the `DrawerStrategy` contract against each. It
cannot see `Personality`, which lives in a flavour source set; `DrawerStrategyContractTest` runs
the same assertions inside the app module, where the active personality is resolved, and covers it
there. **Both, or neither is enough.**

Layout, and why it is split this way:

| Tree | Compiled into | Why it lives there |
|---|---|---|
| `src/` | every flavour | pure Java; an `android.*` import here breaks `SeamCheck`, which is the point |
| `example/` | the `color` flavour only | pure Java, but it is the example's taste, not the base's |
| `app/src/main/` | every flavour | the launcher spine, including the `android.*`-bound seams |
| `app/src/plain/`, `app/src/color/` | one flavour each | `Personality` and whatever it points at |
| `app/src/test/` | every flavour | must pass for *any* personality |
| `app/src/testPlain/` | the `plain` flavour only | asserts the default has no taste; a colour build is meant to fail these |

## Toolchain (inherited — do not relearn these the hard way)

The build runs through a nix shell (JDK 17, Gradle, Android SDK 36, with 35 kept alongside). A bare `./gradlew` will not
find a JDK. Use `nix develop -c ./gradlew ...` or the wrapper scripts.

- **`./gradlew` is the pinned Gradle (8.11.1); the shell's `gradle` is a different version.** Always
  use `./gradlew`. **Never run `gradle wrapper`** — it rewrites the pin to whatever's on PATH and
  silently breaks the build with an error that reads like a broken environment.
- **JDK 17 is the pin. Android Studio's bundled JDK 21 is not a substitute** (its `jlink` breaks
  AGP's `JdkImageTransform`). "A build passed but I never entered the shell" does not count.
- **`AppWidgetProviderInfo.min*` sizes are in PIXELS, not dp** — the framework pre-scales the dp from
  the provider XML by display density. Go through the one blessed reader (`HomescreenView.spanForProvider`),
  never multiply by density again.
- **A green test/benchmark run can measure nothing.** Assert the interaction actually happened;
  confirm a new metric appears before trusting it. This bit the source launcher repeatedly.

## On device

Both flavours have been installed and driven on a physical phone (OnePlus CPH2749, Android 16 /
SDK 36 — at the time one API level above the app's `targetSdk` 35; the app has targeted 36
since the 2026-10-11 port, and that has not yet been driven on the phone). What that confirmed,
and what it cost:

- **The device-only classes of change behave.** Widget spans come out of the picker at their
  declared size (2×1, 4×1 — not multiplied by the 3.5x display density); a 4×1 widget resized to
  4×2 and persisted; and on a full-width widget the left/right resize handles are visibly clamped
  inward off the display edge, clear of the back-gesture strip. These are the two bugs that stayed
  invisible under Robolectric for months in the source launcher.
- **The colour flavour is the proof it was meant to be.** Rainbow background, colour-group section
  headers and per-cell accents, from `src/color/**` alone. Verified structurally too: no file in
  `src/main` or `src/` names anything in the flavour, in code — the single mention is one javadoc
  line in `DrawerStrategy`.
- **It holds the HOME role correctly.** Driven as the actual default home app: a Home press from
  inside the drawer returns to the home screen through `onNewIntent` on the *same* `ActivityRecord`
  (so `singleTask` is doing its job and the activity is not being rebuilt), and a `force-stop`
  followed by a Home press cold-starts back to a drawn home screen with an empty crash buffer.

**Cold start does ~60–80ms of main-thread disk I/O, and always did.** `dev-debug.sh strict` reports
`DiskReadViolation`s from five of our call sites — `Settings.prefs`, the first `Settings` mode read,
`HomeScreenStore.<init>` (documented as deliberately synchronous; see `Perf.STORE_LOAD`),
`HomescreenView.<init>` and `HomescreenView.populatePage`. This was checked against the source launcher's
debug build on the same device and is **inherited, not introduced**: the same five sites, the same
magnitudes, the generic build marginally faster. Worth knowing before optimising cold start, and
worth not mistaking for extraction damage. The largest violation in the log (42ms) is ColorOS's
`OplusThemeZipFile` inside `ResourcesImpl`, which is not ours and cannot be fixed from here.

### The recorded baseline, and how to read it

`perf/baseline.json` (71 metrics) is recorded on the `plain` benchmark build. The headline numbers:

| Metric | Value |
|---|---|
| `startupCold.timeToFullDisplayMs` | **200.6 ms** |
| `startupCold.Launcher.loadHomeAppsSumMs` | 71.0 ms |
| `startupCold.Launcher.loadIconSumMs` | 283.7 ms (≈60 icons) |
| `startupWarm.timeToFullDisplayMs` | 56.3 ms |
| `startupHot.Launcher.newIntentSumMs` | 0.0 ms |

Cold time-to-*fully-drawn* of ~200ms against the 1501ms the source launcher recorded for the same measurement
is the extraction's one genuine performance win, and it is mostly the ~780ms dominant-colour pass
that no longer exists. It is `reportFullyDrawn()`-backed, so it is time-to-usable, not time-to-first-frame.

**This baseline is not yet a usable gate.** Running `./perf.sh check` immediately afterwards, on the
*same unchanged build*, failed 11 of the 71 metrics — including startup timings the harness assumes
are "stable to a few percent" (`onCreateSumMs` +68%, `queryActivitiesSumMs` +25%). Some of that is
real state drift rather than noise: `loadIconCount` moved 59.5 → 72.0, so the second run simply
caught more of the icon load inside the measured window, and `loadIconSumMs` tracks it almost
exactly. Both runs happened on a phone that was being actively driven for other testing. **Re-record
on a genuinely quiet, thermally settled device before treating a red `check` as evidence of
anything.** Until then the numbers below are indicative, not a contract.

**`startupCold.Launcher.loadAppsSumMs` reads 0.0, and that is not a measurement of zero work.** The
second load phase finishes after the measured window closes, so the section never lands in the
trace. This exact reading has fooled people before; do not celebrate it, and do not treat a
later non-zero value as a regression. `loadHomeApps` is the honest cold-start number.

Two traps this harness hit on its first real run, both of which cost a full build-and-run:

- **`./perf.sh` needs the battery above 25%.** Macrobenchmark hard-fails every test with
  `LOW-BATTERY` below that, and *being plugged in does not count* — the check reads the level, not
  the charging state. `stabilise_device` now refuses up front instead of finding out five
  benchmark iterations later.
- **The benchmarks need the screen on _and_ the device unlocked.** Letting the phone sleep to
  charge faster puts it behind the keyguard, and nothing in adb can dismiss a secure one — the run
  then fails on a UI it cannot see. `perf.sh` sets `svc power stayon usb`, which keeps a *woken*
  device awake; it cannot wake a locked one.

## The seam was wrong once already — this is what that looks like

Building the colour flavour is the load-bearing proof: the identity this base was
extracted from, re-added with no edits to the launcher core. It did not work first time, and the
failure is worth keeping because it is the failure mode the rule exists to catch.

A colour personality has to know each app's dominant colour. That comes from the icon's pixels —
and `DrawerApp` deliberately has no icon, because keeping it free of `android.*` is what makes
strategies unit-testable on a bare JVM. There was no hook anywhere for a personality to derive its
own per-app data, so the colour build could only have got at the icons by reaching into
`AppLoader`. **That is a core edit, so by the rule the seam was wrong, not the flavour.**

The fix was `AppDataSink`: one worker-thread callback handing the personality every `AppInfo` when
the app list loads, so it can build a side table keyed by app. Small, general (a "group by install
date" or "group by category" personality needs exactly the same thing), and it keeps `DrawerApp`
framework-free. With it, `src/color/**` is purely additive — no `src/main` file names anything in
the flavour.

Two things the seam still cannot express, recorded so the next person does not rediscover them:

- **A `Background` cannot react to drawer scroll.** The source launcher's rainbow backdrop repainted from the
  grid's scroll listener; the seam hands over a `View` and nothing else, so the flavour ships a
  static full-spectrum gradient instead. Deliberate: a background with a per-frame callback is a
  background that can make the drawer janky.
- **`BadgeAppearance` returns a flat colour**, so a personality can pick any colour per app but
  cannot paint a moving spectrum. `BadgeStyle.RAINBOW` therefore stayed a painter mechanic in the
  spine rather than moving into the flavour.

## targetSdk 36 changes behaviour, not just a number

Adopted on 2026-10-11 along with the fixes that make it safe. The one that bites:
**`onBackPressed()` is never called under targetSdk 36.** Predictive back is on by default, the
framework delivers Back to an `OnBackInvokedCallback`, and with none registered its default
*finishes* the activity — for the HOME app, a kill-and-relaunch on every Back that looks exactly like
"the drawer closed". Back goes through `MainActivity.registerBackHandler` → `handleBack`; anything new
that wants Back joins `handleBack`. `BackHandlingTest` drives the dispatcher, the same entry the
framework uses. Robolectric tops out at SDK 35, so `app/src/test/resources/robolectric.properties`
pins unannotated test classes to 33 rather than following the targetSdk.

## Invariants to preserve (these have teeth)

- **`MainActivity` is the default HOME app.** An uncaught exception = no home screen, not a dialog.
- **`AppLoader` runs on a worker where an escaping exception is process death.** One bad package must
  cost only that package — keep the per-package try/catch.
- **The priority load phase must never prune.** `removeUnknownApps` deletes home items missing from
  the list it's given, and the priority list is a subset by construction.
- **`Mode` ordinals and the `Settings` bitmask are persisted** once you ship — append only.
- **`allowBackup=false`** — `homescreen.json` holds device-local widget IDs; the export/import flow
  is the supported backup path.
