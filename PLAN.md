# Libra look: dark mode, the real palette, and the right fonts

## Context

The Compose port renders in Material 3's baseline purple. `Theme.kt:131` calls bare
`darkColorScheme()` / `lightColorScheme()`, so the tab pills are lilac, the cards are
tinted, and the app does not read as Libra in a side-by-side. Dark mode is not broken
so much as unwritten: `LibraTheme(useDarkTheme = isSystemInDarkTheme())` already exists
and already has the right default, but the scheme it hands to `MaterialTheme` is a
stock palette nobody chose. Five semantic colours were ported carefully; the other
forty came from Google.

The Swift app has no palette file and no colour assets — **every** colour is a UIKit
semantic colour. That is why its dark mode needed no code, and it is why the port has
nothing to copy from. Both schemes have to be written out by hand.

Typography has the same shape of problem. `LibraType.figure` is monospaced. Libra's
figures are **SF Pro Rounded, `.weight(.medium)`, `.monospacedDigit()`** — rounded with
tabular numerals, not a typewriter face. Monospace is reserved for tickers, formulas
and raw data. The port collapsed three families into one, and the one it kept is the
wrong one for the most common case.

Outcome: the app looks like Libra in both appearances on both platforms, with the same
three typefaces doing the same three jobs.

### Decisions already made — do not re-open

| | |
|---|---|
| Fonts | Native SF on iOS, bundled lookalikes on Android |
| Theme control | Follow the system only. No setting, no `PreferenceStore` work |
| Depth | Colours, fonts, surfaces, **and** the inset-grouped list chrome |
| Freshness pill | Opaque capsule, high-contrast fill. No blur, no UIKit interop |

---

## Rules for the implementer

1. `docs/PORT_PLAN.md` and `docs/MAINTENANCE_PLAN.md` are history — the port, and
   the cleanup pass after it. Both are finished. **This file is the plan.** Citations
   of the form "PLAN.md §4" in `KNOWN_ISSUES.md` point at those archives, not here.
2. The Swift app on `main` is read-only reference. Never edit it.
3. Every colour value below is transcribed from UIKit semantics. If a screen needs a
   colour not in the table, that is a finding — log it, do not invent a hex.
4. Nothing outside `Theme.kt` and `components/` may hold a colour literal. There is
   exactly one today (`ClaimBadge.kt:62`); at the end there must be zero.
5. Commit per stage. No co-author trailers.
6. Log deviations in `KNOWN_ISSUES.md` as you go, not at the end.

---

## Stage 1 — The palette (3h)

**File:** `app/src/commonMain/kotlin/com/tylerabitbol/libra/ui/Theme.kt`

### 1.1 The iOS semantic values

Write these as `private val` constants, named after the UIKit token, so a reader can
check them against Apple's documentation rather than against taste.

| Token | Light | Dark |
|---|---|---|
| `systemGroupedBackground` | `#F2F2F7` | `#000000` |
| `secondarySystemGroupedBackground` | `#FFFFFF` | `#1C1C1E` |
| `tertiarySystemGroupedBackground` | `#F2F2F7` | `#2C2C2E` |
| `label` | `#000000` | `#FFFFFF` |
| `secondaryLabel` | `#3C3C43` @ 60% | `#EBEBF5` @ 60% |
| `tertiaryLabel` | `#3C3C43` @ 30% | `#EBEBF5` @ 30% |
| `quaternaryLabel` | `#3C3C43` @ 18% | `#EBEBF5` @ 16% |
| `separator` | `#3C3C43` @ 29% | `#545458` @ 65% |
| `systemBlue` | `#007AFF` | `#0A84FF` |
| `systemGreen` | `#34C759` | `#30D158` |
| `systemRed` | `#FF3B30` | `#FF453A` |
| `systemOrange` | `#FF9500` | `#FF9F0A` |
| `systemPurple` | `#AF52DE` | `#BF5AF2` |

Resolve the alpha-on-label tokens to opaque `Color` values against their own
background rather than leaving them translucent. Translucent text over a card over a
grouped background composites differently in Compose than in UIKit, and the drift is
visible in the tertiary tier.

### 1.2 The Material slots that actually matter

Seventeen files read `MaterialTheme.colorScheme.*`, and Material's own components read
more slots than those files do. Build **explicit** `lightColorScheme(...)` /
`darkColorScheme(...)` — every slot below named. The unset remainder stays baseline
purple and will leak through a menu or a sheet the first time someone opens one.

| Slot | Gets | Why it matters |
|---|---|---|
| `background` / `onBackground` | grouped bg / label | Every screen |
| `surface` / `onSurface` | card fill / label | 19 card sites |
| `surfaceVariant` / `onSurfaceVariant` | tertiary grouped / secondaryLabel | The `.secondary` tier, 75 Swift uses |
| `surfaceContainer`, `-Low`, `-High`, `-Highest` | card fill / grouped bg ladder | `DropdownMenu`, `ModalBottomSheet`, `NavigationBar` |
| `primary` / `onPrimary` | systemBlue / white | Accent, chips, chart |
| **`secondaryContainer` / `onSecondaryContainer`** | systemBlue @ 15% / systemBlue | **This is the purple tab pill** |
| `error` / `onError` | systemRed / white | Failing connection tests |
| `outline` / `outlineVariant` | separator / separator @ 50% | Dividers, hairline borders |
| `scrim` | black @ 32% | Sheet backdrop |

`secondaryContainer` is the single highest-value line in this stage. It is what paints
the selected navigation-bar indicator.

### 1.3 Expand `LibraColors`

Keep the five that exist — their semantics were reasoned about and are right — but
repoint them at the iOS values, and add what the surfaces and badges need:

```kotlin
@Immutable
data class LibraColors(
    // existing five, now iOS-valued
    val secondaryText: Color,     // secondaryLabel
    val tertiaryText: Color,      // tertiaryLabel
    val caution: Color,           // systemOrange
    val positive: Color,          // systemGreen
    val negative: Color,          // systemRed
    // surfaces
    val groupedBackground: Color,
    val cardFill: Color,
    val separator: Color,
    val quaternaryFill: Color,
    // claim tints — ClaimBadge.tint, ported verbatim
    val claimFact: Color,         // label
    val claimCalculation: Color,  // systemBlue
    val claimInterpretation: Color, // systemPurple
    val claimHypothesis: Color,   // systemOrange
)
```

`claimInterpretation` retires `ClaimBadge.kt:62`'s `Color(0xFF7A5AF8)` — a purple with
no dark variant, the port's only stray literal.

Chip alphas are constants, not colours: `chipFill = 0.15f`, `bannerFill = 0.12f`,
`chipSelected = 0.18f`. The Swift pattern is
`.background(tint.opacity(0.15), in: .rect(cornerRadius: 4)).foregroundStyle(tint)` and
it appears on every screen; give it one helper rather than fifteen call sites.

### 1.4 Preserve two rules exactly

These are not stylistic and must survive the refactor:

- `DirectionalChangeText` (`Libra/Views/Components/DataCells.swift:44-51`): `> 0` green,
  `< 0` red, **`== 0` and `null` both secondary**. A missing value is never drawn as a
  neutral zero.
- `growthColor` (`SecurityDetail/SecurityDetailView.swift:144-146`) uses `>= 0` — a
  *different* boundary. Do not unify them.

**Commit:** *The iOS semantic palette, in both appearances*

---

## Stage 2 — Three typefaces doing three jobs (5h)

### 2.1 The mechanism

```kotlin
// ui/LibraFonts.kt (commonMain)
@Immutable
data class LibraFontFamilies(val text: FontFamily, val rounded: FontFamily, val mono: FontFamily)

@Composable expect fun libraFontFamilies(): LibraFontFamilies
```

**iOS actual** (`app/src/iosMain/.../LibraFonts.ios.kt`): `text = FontFamily.Default`
and `mono = FontFamily.Monospace` already resolve to SF Pro and SF Mono through Skia —
free, exact, nothing to ship. **`rounded` is the risk.** Compose MP has no public API
for `UIFontDescriptor.withDesign(.rounded)`, so this needs a timeboxed spike (≤1h):
try resolving the system font data through CoreText and wrapping it as a `Font`. If the
spike does not land inside the box, take the fallback — bundle the rounded face on
*both* platforms and keep text and mono native on iOS. Say which happened in
`KNOWN_ISSUES.md`; do not spend a second hour on it.

**Android actual**: bundle Inter, Nunito Sans, JetBrains Mono.

```kotlin
// app/build.gradle.kts, commonMain.dependencies
implementation(compose.components.resources)
```

Files go in `app/src/commonMain/composeResources/font/` (Compose resources has no
Android-only source set; guard the *use*, not the location — and note in
`KNOWN_ISSUES.md` that the ~1.4 MB rides along in the iOS framework unless the spike
succeeds and lets the fallback be dropped). All three are SIL OFL — ship `OFL.txt`
alongside them and add an attribution line to Settings → About.

### 2.2 The named styles

Extend `LibraType` so every Swift `.font(` call has one Kotlin counterpart. Figures are
**rounded + medium + tnum**, not monospace:

```kotlin
object LibraType {
    // SF Pro Text — labels and prose
    val caption: TextStyle        // 12/16   (26+ Swift uses)
    val caption2: TextStyle       // 11/14   (13+)
    val footnoteEmphasis: TextStyle
    val subheadline: TextStyle
    val callout: TextStyle
    val headline: TextStyle       // (9)
    val title3Emphasis: TextStyle

    // SF Pro Rounded, Medium, tabular — every displayed number
    val figure: TextStyle         // was Monospace. It is not.
    val figureEmphasis: TextStyle
    val figureHero: TextStyle     // largeTitle rounded medium

    // SF Mono — tickers, formulas, raw data
    val ticker: TextStyle         // subheadline mono semibold
    val code: TextStyle           // caption mono   (11 uses)
    val codeSmall: TextStyle      // caption2 mono  (10)
}
```

Tabular figures in Compose:

```kotlin
fontFeatureSettings = "tnum"
```

on every rounded figure style. Swift applies `.monospacedDigit()` at eight sites
(`DataCells.swift:20,43`, `DashboardView.swift:157,250`, `WatchlistView.swift:181`,
`ScreenerView.swift:156`, `BenchmarkDetailView.swift:44`,
`ResearchProfileCard.swift:115`, `EventCard.swift:77`) — baking it into the style is
both closer to intent and harder to forget.

### 2.3 Retire the inlined monospace

`fontFamily = FontFamily.Monospace` appears in ~14 files outside `LibraType` —
`SecurityDetailSections.kt` alone has twelve. Each one is a decision about whether that
text is a *figure* (→ `figure`, rounded) or *raw data* (→ `code`, mono). It is not a
find-and-replace; read each site against its Swift counterpart. The densest are
`SecurityDetailSections.kt` (12), `ScreenerScreen.kt` (3), `SecurityDetailScreen.kt` (3),
`WatchlistScreen.kt` (3).

Finish with `grep -rn 'FontFamily.Monospace' app/src` returning only `LibraFonts`.

Typography is wired by threading `libraFontFamilies()` into `libraTypography` inside
`LibraTheme` — it must become a `@Composable` builder, since resource fonts resolve in
composition.

**Commit:** *SF Text, SF Rounded and SF Mono, each where Libra puts them*

---

## Stage 3 — Surfaces (4h)

### 3.1 Spacing

The port has four steps; Swift uses eight. Keep the four existing names at their
current values so no call site breaks, and add the rest:

```kotlin
object LibraSpacing {
    val hair = 2.dp; val tight = 4.dp; val snug = 6.dp; val small = 8.dp
    val base = 10.dp          // Swift's dominant step
    val medium = 12.dp; val large = 16.dp; val wide = 20.dp
    val screen = 16.dp; val card = 14.dp
    val pillClearance = 44.dp // bottom inset clearing the floating pill
}
```

### 3.2 Shapes

```kotlin
object LibraShapes {
    val badge = RoundedCornerShape(3.dp)
    val chip = RoundedCornerShape(4.dp)
    val panel = RoundedCornerShape(6.dp)   // derivation panel
    val smallCard = RoundedCornerShape(8.dp)
    val group = RoundedCornerShape(10.dp)  // grouped row stacks, macro cards
    val card = RoundedCornerShape(12.dp)
}
```

### 3.3 The card

One Swift pattern repeats nineteen times: `.padding(14)` +
`.background(secondarySystemGroupedBackground, in: .rect(cornerRadius: 12))`. There are
**zero `.shadow(` calls in the entire Swift repo** — separation is by fill contrast
alone. Any elevation in the Compose port is an invention and should go.

Before writing a new helper, grep `app/src/commonMain` for
`background(` + `RoundedCornerShape` to find what the port already duplicates, then add
one `LibraCard` (or `Modifier.libraCard()`) in a new `ui/components/Surfaces.kt`
alongside `libraChip(tint)` for the tinted-chip pattern.

### 3.4 The two specific surfaces

- **Freshness pill** (`DashboardScreen`, `SecurityDetailScreen`): opaque `cardFill`
  capsule, 1dp `separator` border, bottom overlay, content inset by `pillClearance`.
  Swift's `.regularMaterial` has no Compose MP equivalent; this is the chosen
  substitute, not an approximation being passed off as one.
- **`PriceChart.kt`** (Vico): area gradient `primary` 25% → 2%, line 2dp, overnight
  gaps 1dp dashed `[2,2]` at `primary` 40%, fixed height 190dp.

**Commit:** *Cards, chips and the surfaces underneath them*

---

## Stage 4 — Inset-grouped list chrome (5h)

Swift never sets `.listStyle(` — `Form` and `List` default to inset-grouped, and the
scroll-based screens hand-roll the same look (`DashboardView.swift:53-64`): rows in a
zero-spacing stack, `Divider().padding(.leading, 12)` between them, a rounded-10 fill
around the whole stack. Compose gives none of this.

Add to `ui/components/GroupedList.kt`:

```kotlin
@Composable fun GroupedSection(header: String? = null, footer: String? = null,
                               content: @Composable ColumnScope.() -> Unit)
@Composable fun GroupedRow(onClick: (() -> Unit)? = null,
                           leading: ...  , trailing: ... , content: ...)
```

`GroupedSection` draws the `LibraShapes.group` fill and inserts dividers inset 12dp
from the start between children — not after the last one. Reuse `SectionHeader` from
`SettingsScreen.kt:145` (promote it out of that file), and keep `SwipeToDelete` working
*inside* a `GroupedRow`, which is the fiddly part.

Adopt in, in this order: `SettingsScreen`, `SecretEntryScreen`, `WatchlistScreen`,
`ScreenerScreen`, then the Dashboard macro stacks. `PushedScreen` and `MenuSelection`
stay as they are.

**Commit:** *Grouped sections, the way Form draws them*

---

## Stage 5 — Platform chrome and verification (3h)

### 5.1 Android

`androidApp/src/main/res/values/themes.xml` hardcodes
`Theme.Material.Light.NoActionBar` with `windowBackground` = white — a white flash in
front of a dark app. Move to a `DayNight` parent, extract `windowBackground` to a colour
resource, and add `values-night/` with `#000000`. Then check the `enableEdgeToEdge()`
call in `MainActivity` still gives readable status-bar icons in dark.

### 5.2 iOS

`iosApp/Info.plist` has no `UIUserInterfaceStyle`, which is correct — it follows the
system. Verify nothing in `LibraApp.swift` or `MainViewController.kt` forces a style.

### 5.3 What can be tested, honestly

Compose UI test cannot assert a rendered colour. So:

- **Unit tests** (`commonTest`, no composition): assert the palette itself — that
  `lightColors.positive` is `#34C759`, that every `ColorScheme` slot listed in §1.2 is
  non-null and non-baseline, that light and dark differ in every slot. Cheap, real,
  catches the exact class of bug this plan exists to fix.
- **`runComposeUiTest` on `iosSimulatorArm64`**: structure only — a `GroupedSection`
  with three children renders two dividers; a `GroupedRow` with `onClick` is clickable.
- **Screenshots**, the only real check: iPhone simulator light **and** dark, Android
  emulator light **and** dark, across all five tabs plus the three pushed screens.
  Compare against `docs/LI-*.png` / `docs/RM-*.png` on `main`.

Baseline to hold: 477 `:core` jvmTest, 477 `:core` iOS, 20 `:app` iOS UI, 5 Android
instrumentation, all passing.

### 5.4 Android credentials

The emulator's six API credentials were wiped during the previous plan. As of
2026-09-23 they are stored again. If they are ever missing, the owner re-enters
them. Do not ask for the keys themselves.

**Commit:** *Dark windows, and what the screenshots showed*

---

## Things that will go wrong

| | |
|---|---|
| iOS SF Pro Rounded has no Compose MP API | Timebox the CoreText spike to 1h; fall back to a bundled rounded face on both platforms |
| An unset `ColorScheme` slot leaks purple | Name every slot in §1.2 explicitly; open a menu and a sheet in dark before committing |
| `compose.components.resources` changes the iOS framework size | Measure `LibraKit` before and after; record it |
| Resource fonts resolve in composition | `libraTypography` must become a `@Composable` builder, not a `val` |
| The 14 monospace sites are not a sed | Each is a figure-vs-raw-data judgement against the Swift source |
| `SwipeToDelete` inside `GroupedRow` | The dismiss background must be clipped by the section's rounded fill |
| Resolved alpha labels drift from UIKit | Composite against the card, not the grouped background, for text sitting on cards |

## Definition of done

- No colour literal outside `Theme.kt`; `grep FontFamily.Monospace app/src` hits only `LibraFonts`.
- Light and dark screenshots on both platforms, all five tabs, no purple anywhere.
- Figures render rounded with tabular numerals; tickers render monospaced.
- Test baseline unchanged and green; new palette unit tests passing.
- `KNOWN_ISSUES.md` records the font-spike outcome and the framework size delta.

## Out of scope

Appearance setting (the owner chose system-only). Blur / Liquid Glass. Dynamic colour —
still deliberately off; a caution is orange because it is a caution. Any change to
`:core`. Any edit to the Swift app.

| Stage | Hours |
|---|---|
| 1 Palette | 3 |
| 2 Fonts and type | 5 |
| 3 Surfaces | 4 |
| 4 Grouped lists | 5 |
| 5 Chrome and verification | 3 |
| **Total** | **20** |

---

## Future stage — cleanup and optimization (sketch, not scheduled)

To be planned properly after the look lands, because several of these are only
measurable once the theme stops changing.

**Shape.** `SecurityDetailSections.kt` is the port's largest UI file and the counterpart
of an 841-line Swift view; it is where twelve of the fourteen monospace sites live and
is the obvious split candidate — by section, not by line count. The five `*Host.kt`
files are near-identical `LaunchedEffect` + delegate shells and probably want one
generic host.

**Recomposition.** The `snapshots: StateFlow<SnapshotStore?>` attach-after-launch pattern
caused two real bugs already (`SecurityDetailHost`, `BenchmarkDetailHost`). Audit the
remaining hosts for the same defect, and check whether the detail screens recompose on
every snapshot emission rather than on the slice they read.

**Still-open correctness.** Stage 2.4 of `docs/MAINTENANCE_PLAN.md` is not closed: the deep-linked
security page and the Watchlist path disagreed on live keys. That is a bug, not a style
issue, and should lead this stage rather than trail it.

**Build.** Configuration cache is on; measure clean and incremental build times and the
`LibraKit` framework size before and after the font bundling, so the number has a
baseline. Check whether `:app`'s iOS-only test target is costing more than it returns.

**Dead weight.** `docs/PORT_PLAN.md` and `docs/MAINTENANCE_PLAN.md` are both history
already; fold them, and this file once it is done, into one archive. Sweep for unused composables and unreferenced `:core`
helpers — the test count (477 × 2) suggests good coverage, so a coverage-guided sweep is
low-risk.
