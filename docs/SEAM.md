# The personality seam — manifest & recipe table

If you are personalizing this launcher (human or agent), start here. Almost everything you'd
want to change lives behind one of five seams. Edit a seam; leave the launcher spine alone.

The intended interaction is **edit code, not add settings.** A fork expresses taste as a small
diff, not as an options screen. If you find yourself adding a toggle to the base, you've
misread the product — put the taste in a seam and point `Personality` at it.

## The five seams

All five are wired through one file — `app/src/<flavour>/java/.../personality/Personality.java`.

| Seam | Interface | Where | Default | Contract |
|---|---|---|---|---|
| Drawer order/grouping | `DrawerStrategy` | `src/` (pure Java) | `AlphabeticalStrategy(true)` | every input app in exactly one section; no invention; no nulls; pure function. Enforced by `SeamCheck` **and** `DrawerStrategyContractTest`. |
| Drawer cell look | `DrawerItemDecorator` | app module | `NONE` (platform icon + label) | cosmetic only — must not change what a cell launches; cells are recycled, so reset what you set; touch only the icon and label you were handed |
| Background | `Background` | app module | `NONE` (wallpaper shows through) | must not intercept touches meant for the grid; no scroll or per-frame hook exists, by design |
| Badge appearance | `BadgeAppearance` | app module | `RED` (`BadgePalette.RED_FILL`) | flat opaque colour only; count maths stay in `BadgePalette` |
| Per-app data | `AppDataSink` | app module | `NONE` | **worker thread**; must not throw (an escape is process death, and this is the HOME app); called repeatedly and with partial lists — merge, never replace |
| Icon look everywhere, and launches | `IconTreatment` | app module | `NONE` (icon unchanged) | main thread, on every bind — cache expensive work; never mutate the shared icon, wrap it; the badge is drawn over the result. Added on the Lived-in branch — see `LIVEDIN.md` |

`DrawerStrategy` is pure Java on purpose: it is the seam most forks change and the one worth
unit-testing with no device. The others touch `android.*` types (`Canvas`, `Drawable`, `Color`,
`Context`) and are declared next to the view code.

**`AppDataSink` is the one to reach for when your ordering depends on something the launcher does
not already know.** `DrawerApp` shows a strategy only label, key, usage and work flag — no icon, no
`Context`. Anything else (the icon's colour, an app's category, its install date) you derive here,
once, on the loader's thread, into a side table keyed by `DrawerApp.key()`; the strategy then reads
that table and stays pure. Never add a field to `AppInfo`: the launcher's model is not the fork's.

## Recipe table — "to change ___, edit ___"

Built from real anchors so you land on the first try instead of grepping.

| Intent | Edit | Note |
|---|---|---|
| Reorder / regroup the drawer | new `DrawerStrategy`, point `Personality.DRAWER` at it | run `SeamCheck` |
| Restyle a drawer cell (tint, monogram, label) | `DrawerItemDecorator` | default no-op |
| Change the home/drawer background | `Background` | see the colour build's rainbow bg for a live one |
| Change notification-dot colour/shape | `BadgeAppearance` | default = `BadgePalette.RED_FILL` |
| Tint/restyle icons **on the home screen** | `IconTreatment` (every surface) | the drawer decorator does **not** reach the home screen; `IconTreatment` does |
| Change grid size | `Settings` `home_grid_cols/rows` | already a setting; don't hardcode |
| Widget min-size behaviour | `HomescreenView.spanForProvider` | the only blessed reader of provider minimums (they're in PIXELS, not dp) |
| React to a home long-press | add to `cancelPendingWidgetLongPress()` **and** the `downOnWidget` guard | both must agree — a non-interactive widget routes its whole touch stream to `HomescreenView` |
| Add a drawer mode | append to `MainActivity.Mode` | ordinals persist once shipped — append only from then on |
| Group by something the launcher doesn't know (colour, category, install date) | `AppDataSink` → side table → your `DrawerStrategy` | see `ColorTable` in the `color` flavour |
| Ship a second personality from one checkout | add a product flavour + `src/<name>/…/Personality.java` | see the `color` flavour; nothing in `src/main` changes |

## Writing a DrawerStrategy (the common case)

1. Implement `organize(List<DrawerApp>)` — the input is already filtered (search box, hidden
   apps); you only order and group. Return `DrawerSection`s top to bottom; `""` title =
   headerless.
2. Give it a short stable `id()` (persisted in settings, shown in the recipe table).
3. Point `Personality.DRAWER` at it.
4. Add it to the array in `SeamCheck` and run the check. If it's red, it tells you which
   contract clause you broke.

The colour launcher this base came from is a `ColorGroupStrategy` plus a side table — the whole
"apps sorted into rainbow sections" identity lives behind these seams, with no edits to the
launcher core. Build it with `./gradlew assembleColorDebug` and read `app/src/color/**`; it is the
template to pattern-match against, and it is deliberately small.

One caveat worth reading before you copy it: that flavour did **not** work behind the original
four seams — a colour personality cannot see an icon through `DrawerApp`. `AppDataSink` exists
because of it. If your personality hits the same wall, widen the seam rather than reaching into the
launcher; the rule is "fix the seam, not the flavour", and it has been exercised.
