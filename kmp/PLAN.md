# Libra: UI/UX and general improvements

## Context

The port matches the Swift app, and the look plan (`docs/LOOK_PLAN.md`) made it
look like it too. The owner's verdict is that the result **feels old**. The charts
draw a line and say little about it. The key-stats block on a security's page is
oversized. Screens slide in where they used to fade. And nobody has checked the
arithmetic against hand-worked answers: the 477 tests mostly prove the port agrees
with Swift, not that either one is right.

This plan covers five things: the math, the charts, a UI/UX refresh, workspace
cleanup, and general optimisation.

### Decisions already made — do not re-open

| | |
|---|---|
| Scope | The Kotlin port only. The Swift app on `main` is untouched |
| Parity | Swift parity is now a baseline, not a ceiling. The refresh may add motion, colour-by-direction, gestures and elevation that Swift does not have |
| Transitions | Fade, not slide |
| Math first | No chart work lands until Stage 1 is done: a prettier chart of a wrong number is worse than today |

---

## Rules for the implementer

1. `docs/PORT_PLAN.md`, `docs/MAINTENANCE_PLAN.md` and `docs/LOOK_PLAN.md` are
   history. **This file is the plan.**
2. The Swift app on `main` is read-only reference. Never edit it.
3. Colours still come from `Theme.kt` only. New tokens are fine; literals
   elsewhere are not. `DirectionalChangeText`'s rule (`> 0` green, `< 0` red,
   `== 0` and `null` secondary) and `growthColor`'s `>= 0` both survive.
4. Where Swift has the same math bug, fix it here, and record it in
   `KNOWN_ISSUES.md` as a divergence from Swift, with the Swift file and line.
5. Commit per stage. No co-author trailers.
6. Log deviations in `KNOWN_ISSUES.md` as you go, not at the end.
7. `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`

Work order: **1 → 4 → 3 → 2 → 5 → 7 → 6.**

---

## Stage 1 — Math audit (6h)

**Scope:** every file in `core/src/commonMain/.../calculations/`, plus
`support/Format.kt`.

### 1.1 Method

For each file:

- Read it side by side with its Swift original.
- Add at least one **worked-example test** per formula. The expected value is
  computed by hand in the test's comment, not by calling the code under test.
- Put them in `core/src/commonTest/.../calculations/WorkedExampleTests.kt`, so
  they are easy to find apart from the parity tests.

### 1.2 Suspects found while planning, each to confirm or rule out

| # | Where | Suspicion | Check |
|---|---|---|---|
| 1 | `FundamentalDetector.marginSeries` | The comment at `FundamentalDetector.kt:140` cites GOOGL's quarterly gross margin as **93.7%**. Alphabet's real figure is about 58%. Likely cause: a wrong denominator, or a Q4 matched against year-to-date revenue (derived by subtracting from the 10-K) | Run the series against `sec_companyfacts_AAPL.json` and a live GOOGL pull; compare each quarter with the 10-Q |
| 2 | `Format.fixed` | Rounds `magnitude * 10^n` as a binary double. 2.675 at 2 dp scales to `267.4999…` and prints **2.67**; Foundation/ICU rounds the decimal value and prints **2.68**. So the half-to-even branch almost never fires on real ties | Test 2.675, 1.005 and 0.125. Fix by rounding from the shortest decimal string |
| 3 | `ReturnCalculator.relativePerformance` | Checks only that the start dates are within 3 days of each other. A stale benchmark (end days old) is compared against a fresh security | Add an end-date mismatch guard. Swift shares the gap |
| 4 | `ReturnCalculator.trailingReturn` | `isFullWindow = gap <= tolerance`, and the gap is signed. A start bar far **before** the requested start (a hole in the data) still counts as full | Use `gap.absoluteValue` |
| 5 | `ValuationCalculator` margin scaling | `historyScale = 100`, `currentScale = 1` is verified for gross margin only. It is assumed for operating margin, net margin and ROE | Check each key against a live `/stock/metric` response |
| 6 | `AnomalyMeasure.measure` | `usable.size < minimumSample - 1` is an off-by-one against its own name. Ties count toward `exceededCount`'s complement | Decide the intended rule, write it in the KDoc, and test the boundary |
| 7 | `ValuationCalculator` percentile | Counts `<=`, so ties raise the percentile | Keep it or change it, but state the rule in the UI text ("at or below") |

Also re-derive: `ChartSeriesBuilder` (positions, overnight gaps), `ChangeWindow`,
`InsiderActivity` net-value sums, `FilingAnalysis.describe` (percent vs percentage
points), `Screener` filters, and `yearOverYear`'s 365-day, ±45-day pairing.

**Done when:** every suspect has a test and a verdict in `KNOWN_ISSUES.md`, and
`:core:jvmTest` is green.

**Commit:** *Check the arithmetic against worked answers*

---

## Stage 2 — Charts that summarise the data (6h)

**Files:** `ui/components/PriceChart.kt`, `dashboard/BenchmarkDetailScreen.kt`,
new `ui/components/ChartSummary.kt`

### 2.1 A summary strip above the chart

The strip shows:

- The change over the window, in dollars and percent.
- The window's high and low.
- The last price's position between them.

It follows the selected range. The numbers come from the same `analysisClose`
values the line draws, so the strip and the line cannot disagree. Put the
calculation in `:core` (`ChartSummary` next to `ChartSeriesBuilder`), and test it.

### 2.2 The line

- **Colour by direction**: `positive` or `negative` against the window's first
  close. A flat window uses `secondaryText`, never a green zero.
- A gradient fill in the line's colour, from 18% down to 0%.
- A **dotted baseline at the starting price**, in `separator`.
- The overnight segments keep their dashed stroke, in the line's colour at 40%.

### 2.3 Scrubbing

- Press and drag shows a vertical crosshair and a dot on the line.
- While scrubbing, the strip shows that point's date, price, and change from
  the window's start. On release it returns to the window summary.
- Vico has a `CartesianMarker` for this. Use it rather than hand-rolled pointer
  input, and check that it does not re-enable scrolling (see "Every chart showed
  only its first eight bars" in `KNOWN_ISSUES.md`).

### 2.4 Axes

- At most 3 horizontal guidelines, in `separator` at 50%.
- End-axis labels in compact currency ($340, not $340.00).
- The x axis keeps `TickItemPlacer`.

`windowDescription` keeps its wording. `PriceChartTest` must still pass unchanged.
Add tests for the summary strip's semantics.

**Commit:** *Charts that say what the line did*

---

## Stage 3 — A smaller stats block on the security page (2h)

**File:** `security/SecurityDetailScreen.kt` → `MetricGrid`

Today it is a 180dp fixed-height adaptive grid. Its values are in `figureEmphasis`,
so "$236.65 – $344.57" wraps.

- **Day range** and **52-week range** become one `RangeBar` each: a thin 4dp track,
  a marker at the current price, and the low and high at the ends in `figureSmall`.
  It is a new component in `ui/components/`.
- **Market cap, Open, Beta, Avg volume** become one row of four, or a 2×2 grid
  below 360dp wide. Values use `figure`, not `figureEmphasis`; labels use `caption2`.
- Drop the fixed height. The block sizes to its content.
- Keep each cell's `contentDescription = "$label: $value"`. A range bar also
  reads its position, e.g. "Day range: $336.10 to $341.20, last $338.98".

**Commit:** *Tighten the stats under the price*

---

## Stage 4 — Fade transitions, as before (30min)

**File:** `ui/Navigation.kt`

`NavHost` sets no transitions, so navigation 2.9.2 uses its platform default,
which slides. Set all four explicitly on the `NavHost`:

```kotlin
enterTransition = { fadeIn(tween(220)) },
exitTransition = { fadeOut(tween(220)) },
popEnterTransition = { fadeIn(tween(220)) },
popExitTransition = { fadeOut(tween(220)) },
```

Tab switches cross graphs and therefore use the same transitions. Verify on the
iPhone that the edge back-swipe still pops, and that it fades rather than slides.
`NavigationTest` stays green.

**Commit:** *Fade between screens again*

---

## Stage 5 — UI/UX refresh (10h)

### 5.1 Look

- **Hero price** on the security and benchmark pages: `figureHero` price, with
  the change and percent below in direction colour, and the freshness stamp
  inline rather than in the floating pill where there is room.
- **Cards**: one radius (`LibraShapes.card`, 14dp). Separate them with a hairline
  `separator` border in light mode and fill contrast in dark. More space between
  sections (`LibraSpacing.wide`).
- **Range picker**: a segmented control with a sliding selected indicator,
  replacing the chip row.
- **Tab bar**: no Material pill. The selected tab shows as an accent-coloured
  icon and label, over a `cardFill` bar with a top hairline.

### 5.2 Feel

- **Watchlist sparklines**: a 1-month closes line, 56×24dp, in each row, coloured
  by direction. It uses the daily bars the store already holds, with no new
  request per row. If no bars are stored, show nothing rather than a placeholder
  line.
- **Swipe to remove** replaces the "Remove" text button. A long press opens a
  context menu with Remove, as the discoverable path. Reuse `SwipeToDelete`.
- **Pull to refresh** on the Dashboard, Watchlist and Security Detail pages,
  wired to the existing `refresh(...)` suspends.
- **Placeholder loading shapes** on first load, instead of spinners and "—".
  "—" stays for data that is genuinely missing: a missing value is never drawn
  as loading.
- **Motion**: prices use `AnimatedContent` with a vertical roll on change. Range
  changes animate the chart. Everything respects reduced motion.
- **Haptics**: light, on a range change and on each scrub step.
- **Empty states**: an empty watchlist offers one-tap "Add AAPL, MSFT, NVDA",
  and an empty Screener explains what it needs.

### 5.3 Verification

Take screenshots in light and dark on an iPhone 18 Pro (iOS 27) and the
`medium_phone` emulator: all five tabs plus the three pushed screens. Save them
to `docs/refresh/`.

**Commit:** one per sub-area — *Hero price and cards*, *Watchlist gestures and
sparklines*, *Loading, motion and empty states*.

---

## Stage 6 — Workspace cleanup (2h)

**Ask the owner before deleting any branch or worktree.**

- **Build output**: about 1.4 GB (`app/build` 1.2 GB, `core/build` 137 MB,
  `androidApp/build` 78 MB). Run `./gradlew clean` and record the before and after.
- **Branches and worktrees**: `backup-before-reorder`,
  `phase-a-store-reads-and-fundamentals`, `claude/mock-libra-website-63d82d`.
  List them, with their last commit, as deletion candidates.
  `claude/master-plan-known-issues-ed4a90` has one unmerged commit ("ignore the
  generated directories"): merge it or drop it.
- **Repo root on `main`**: `Claude outputs/` and `brag-output/` are untracked
  clutter. Either ignore them in git or move them out of the repository.
- **Docs**:
  - Fold the three finished plans into `docs/ARCHIVE.md`, with section anchors
    kept so `KNOWN_ISSUES.md` citations still resolve.
  - Split `KNOWN_ISSUES.md` (1,159 lines) into open issues first, then a
    one-line index of closed ones, each linking to its detail.
  - Update `RESUME.md`.

**Commit:** *Clear out the workspace and fold the finished plans*

---

## Stage 7 — General optimisation (4h)

- **One host.** The five `*Host.kt` files repeat the same wiring: `remember` the
  model, collect `registry` and `snapshots`, then `LaunchedEffect(load)`. Make one
  generic host, and audit each for the store-attaches-after-launch defect.
- **Split the big files** by section: `SecurityDetailScreen.kt` (809 lines) and
  `SecurityDetailSections.kt` (468).
- **Recomposition**:
  - Check with the Compose compiler reports that the detail screens skip when an
    unrelated snapshot emits.
  - Mark UI state classes `@Immutable` where they are.
  - Stop `DailyChart` rebuilding `dates` and `closes` on every recomposition.
- **Build**: record clean and incremental times and the `LibraKit` framework size,
  in `KNOWN_ISSUES.md` under "Build and toolchain".
- **Dead code**: a coverage-guided sweep of `:core` for unreferenced helpers.

**Commit:** *One host, smaller files, fewer recompositions*

---

## Things that will go wrong

| | |
|---|---|
| A math fix changes a figure a Swift-parity test pins | Update the test and log the divergence with the Swift line. Do not bend the fix to keep parity |
| Vico's marker re-enables scroll or zoom | Keep `scrollEnabled = false` and `Zoom.Content`, and check a 1Y chart still fills the width |
| Colour-by-direction on a flat window | Secondary colour. Test it |
| Sparklines cost a request per row | They read stored bars only. Rows without bars show none |
| Swipe-to-remove fights the back-swipe on iOS | Start the dismiss only past the leading 20dp edge |
| Fade transitions hide the back-swipe preview | Check on the iPhone. If the swipe loses its preview, accept that and log it |

## Definition of done

- Every Stage 1 suspect has a verdict, a test and a `KNOWN_ISSUES.md` entry.
- Charts show a summary strip, colour by direction, and support scrubbing, on
  both platforms.
- The stats block fits without wrapping at 375dp wide.
- Screens fade; none slide.
- Light and dark screenshots on both platforms, in `docs/refresh/`.
- Tests pass: 477 `:core` JVM, 477 `:core` iOS, 20+ `:app` UI and 5 Android
  instrumentation, plus the new worked-example and summary tests.
- The workspace is cleaned, the finished plans archived, and `RESUME.md` current.

## Out of scope

- Any edit to the Swift app.
- An appearance setting.
- Blur or Liquid Glass.
- New data providers or new API requests.

| Stage | Hours |
|---|---|
| 1 Math audit | 6 |
| 2 Charts | 6 |
| 3 Stats block | 2 |
| 4 Fade transitions | 0.5 |
| 5 UI/UX refresh | 10 |
| 6 Workspace cleanup | 2 |
| 7 Optimisation | 4 |
| **Total** | **30.5** |
