# Where the port stands

**Last checkpoint: the port is done. Phases 0-9 complete, and the manual pass
of `PORT_PLAN.md §7` has been run on both platforms against live API keys.**
Phases 0–7 complete: 475 `:core` tests green on both JVM and the iOS
simulator. Phase 8: theme, navigation, the component set, and the Settings,
SecretEntry, Watchlist, Dashboard, BenchmarkDetail, Research, Screener and
SecurityDetail screens, all compiling on both `iosSimulatorArm64` and `android`, with
17 `:app:iosSimulatorArm64Test` tests green — `BannerTest`, `ProvenanceTest` and
`PriceChartTest` in `app/src/commonTest/.../ui/` (the first run costs ~21 min,
later ones ~15-40 s).
Every commit is on this branch; nothing is stashed.

**`MAINTENANCE_PLAN.md`'s five stages have been worked, and four of them are finished.**
Stage 1 (workspace and build), Stage 2 (the six open issues), Stage 3 (UI
fidelity) and Stage 4 (per-tab navigation graphs) are done and committed.
Stage 5 (verification) is not: iOS 27.0 was walked, but the API 26 emulator,
the physical Android device and the self test's six PASS lines were not — the
reasons are under [Open issues](KNOWN_ISSUES.md#open-issues), and two of them
need the owner rather than more work.

Test counts at that point: **477** `:core` on JVM, **477** on
`iosSimulatorArm64`, **20** `:app` UI tests (17 plus three navigation tests),
and **5** Android instrumentation tests against a real AndroidKeyStore, which
`:core` had no compilation for before. No failures.

Two things found and deliberately not fixed, both written up in
`KNOWN_ISSUES.md`: the `-LibraOpenSymbol` page still disagrees with the
Watchlist path on live keys even after both of Stage 2.4's fixes. The base
colour scheme was Material's baseline purple; `docs/LOOK_PLAN.md` Stage 1 replaced it.

The Android emulator's six credentials were lost during that pass's Stage 5 —
`pm clear` removes the SharedPreferences the secrets store encrypts into. As of
2026-09-23 all six are stored again. iOS kept its own throughout.

The port plan is finished and archived at `docs/PORT_PLAN.md` — that is what
`PORT_PLAN.md §N` means throughout this file and `KNOWN_ISSUES.md`. The current
plan, covering workspace cleanup, the remaining open issues and the UI parity
work, is `docs/MAINTENANCE_PLAN.md`.

A cleanup pass has since removed the declarations that had no references
anywhere — four unwired vendor serializers, the `UnportedScreen` placeholder,
an unused DAO query and test stub, and 39 unused imports. Both platforms still
compile and the 475 JVM tests are green. The list, and the three dead-looking
things deliberately kept, are under "Deliberately absent" in `KNOWN_ISSUES.md`.

Read `PORT_PLAN.md` for the phase outline. `KNOWN_ISSUES.md` holds every deviation,
now organised by the part of the codebase it constrains rather than by phase —
its table at the top maps a directory to the section to read before touching it.

## Done

- **Phase 0 — Scaffold.** Three Gradle modules (`core`, `app`, `androidApp`)
  plus an XcodeGen iOS shell. Builds green on Android, iOS and JVM.
- **Phase 1 — Support, provenance, value types.** `Format` (half-to-even),
  `Freshness`, `Claim`/`Derivation`/`SourceReference`, `APIError`, the market,
  fundamentals, filings, macro, benchmark and event models, and the provider
  protocols.
- **Phase 3 — Networking.** `Endpoint`, `RateLimiter` and `HTTPClient` on
  Ktor 3.5.2 (OkHttp on Android/JVM, Darwin on iOS), plus the `MockHttp`
  harness in `commonTest` that Phase 6's provider tests will reuse.
- **Phase 2 — Calculations.** `ChangeWindow`, `ReturnCalculator`,
  `RelativeAnalysis`, `ValuationCalculator`, `InsiderActivity`, `Statistics`,
  `AnomalyMeasure`, `FilingSignificance`, `EventDetector`,
  `FundamentalDetector`, `FilingAnalysis`, `ResearchProfile` +
  `ResearchProfileBuilder`, `Screener`, `ChartSeriesBuilder`.
- **Phase 4 — Persistence.** Room KMP 2.8.5 on `BundledSQLiteDriver`: nine
  entities, nine DAOs, per-platform builders, `SnapshotStore`,
  `evictSyntheticRows`, `FactPeriods`, and a `PreferenceStore` backing
  `SavedScreens`.
- **Phase 5 — Secrets.** `SecretKey`, `SecretsHealth`, `SecretsError` and the
  `SecretsStore` interface in `commonMain`, with a Keychain implementation on
  iOS, an AndroidKeyStore + SharedPreferences one on Android, and the
  in-memory store the tests and previews use.
- **Phase 6 — Providers.** `Finnhub`, `Tiingo`, `Alpaca`, `FRED`, `SEC`,
  `SECFundamentals`, `Form4Parser` + `XMLTree`, `CompositeMarketDataProvider`
  and the three Finnhub adapters, `ProviderRegistry`, `ConnectionTest` and the
  `Mock*Provider` family. Plus the vendor serializers and the fixture harness:
  all 19 fixtures are in, reached through a generated Kotlin source file.

## The manual pass: done, on both platforms

Run 2026-09-21 with all six credentials in place — Finnhub, Tiingo, FRED, the
SEC contact email, and both halves of the Alpaca pair. `-LibraSelfTest` on each
platform:

```
keychain=PASS round-trip succeeded
finnhub=PASS Live quote received (AAPL $338.98).
tiingo=PASS 7 daily bars received.
fred=PASS S&P 500 at 7,650.50.
sec=PASS CIK 0000320193 resolved, 5 recent filings.
fundamentals=PASS revenue quarters=67 annual=19 latest=$109B
```

**Identical on both**, value for value. There is no Alpaca line — `SelfTest`
never probed it, in Swift either — so Alpaca is verified through the 1D and 5D
charts, which are the only thing that uses it.

Screens walked with live data: Dashboard (S&P 7,650.50, Nasdaq 26,522.55, Dow
52,048.83, Russell $285.58, VIX 14.81 — the same figures and the same
percentages on both platforms), Watchlist including symbol search and adding
AAPL, Security Detail ($4.94T market cap, beta 1.09, 52-week $236.65-$344.57)
with 1D and 5D intraday off Alpaca and 1Y off Tiingo, Research (real SEC
fundamentals: gross margin 46.5% → 50.1%, +3.6 pp YoY, with "Show the
arithmetic" and the interpretation card), Screener, Benchmark Detail (a real
FRED chart where the keyless pass only ever saw its empty state), and Settings
with all seven rows green.

The sample-data banner correctly disappears once keys are present; the
disclaimer banner stays. The chart fixes hold: a year of sessions fills the
width with its axis labels, and 5D renders five separate sessions rather than
one line across the overnight gaps.

Test suites at the same checkpoint: **475** `:core` on JVM, **475** `:core` on
`iosSimulatorArm64`, **17** `:app` UI tests on `iosSimulatorArm64`. No failures.

### Where it was run, and why not where PLAN says

- **iOS: iPhone 18 Pro, iOS 27.0.** Not the iPhone 17 Pro on 26.5 this file
  used to name. That 26.5 runtime is broken on this machine — one boot failed
  with `EINVAL` outright, and launching on it crashed the system shell. The
  27.0 devices are clean. `KNOWN_ISSUES.md`, "Platform shells".
- **Android: `medium_phone`, API 36.** `PORT_PLAN.md §7` asks for an API 26
  emulator and a current device. API 26 is the manifest floor and the build
  asserts it; no API 26 emulator and no physical device were exercised. That is
  the one line of the definition of done not literally met.

### Two retracted findings

An earlier run of this pass concluded that this Xcode 27 install shipped no
`Simulator.app` and that the simulator's capture path was frozen. **Both were
wrong**, and both are corrected in `KNOWN_ISSUES.md` under "Platform shells".
Briefly: Xcode 27 moved the developer apps to `Contents/Applications/` and
replaced Simulator with `DeviceHub.app`, and two identical screenshots of an
idle Compose screen prove nothing about the display link. The method for
telling the two cases apart is written down there, because the wrong conclusion
cost an hour and put a false entry in that file.

### What running it established

Four traps, all fixed, all written up in full in `KNOWN_ISSUES.md` — so only
the one-line version is here:

- **The pinned "Updated just now" pill was invisible against the cards it
  floats over**, so it read as a line of text lying across a filing card and
  across the range selector. Swift gets separation from `.regularMaterial`;
  Compose has no material, so the pill now takes an opaque surface and a
  shadow ("UI"). Like the charts, this is a defect no test can see.
- **Compose on iOS aborts at launch without `CADisableMinimumFrameDurationOnPhone`
  in `Info.plist`**, with a `SIGABRT` on a blank screen and nothing in the
  device log ("Platform shells").
- **The Keychain `-50` was the `kSec…` constants being bridged through a Kotlin
  `Map`, not anything about the write** ("Secrets"). A `core/src/iosTest` suite
  cannot verify secure storage at all, which is why `SelfTest` exists.
- **Every chart showed only its first eight bars.** A Vico host scrolls unless
  it is told to fit, so a year of sessions was several screens wide, and the
  x-axis labels were missing with it ("UI"). The line still looked plausible,
  which is the lesson: the UI tests read semantics computed from the data, so a
  chart drawing the wrong window passes them all. Looking at it is the only
  test there is.

Phase 8's screens are otherwise real, on sample data with no keys: five tabs,
both banners, search (after a debounce — do not judge it on the first frame),
Room persistence across navigation, the security page with all seven ranges,
provenance badges and their arithmetic.

Phases 7 and 8 are done: `AppEnvironment`, all six view models (`Dashboard`,
`BenchmarkDetail`, `Watchlist` + `SymbolSearch`, `SecurityDetail`, `Research`,
`Screener`), `DeveloperOptions` and `SelfTest`, with every Swift test suite
ported. Nothing from Phases 1–7 is outstanding.

### Notes from Phase 9

- Derived figures are extension vals on the UiState, so a composable reads
  `state.chartBars`, not `viewModel.chartBars`.
- `SecurityDetailViewModel.awaitLoad()` joins both the load and the job a range
  change spawns. `WatchlistViewModel.refresh(...)` and
  `DashboardViewModel.refresh(...)` already suspend until done.
- View-model test stubs are shared in
  `core/src/commonTest/.../viewmodels/ViewModelTestSupport.kt`.
- `DeveloperOptions` and `SelfTest` take a `LaunchEnvironment`; Phase 9 wires
  the two shells to it.

## Phase 8 — UI: done

In `PORT_PLAN.md §8`'s order: `Theme.kt`, `Icons.kt`, `Navigation.kt`, `App.kt`,
`Screens.kt`; `Components/*` (banners, claim badge, data cells, attribution
card, event card, research profile card, filing analysis card, `PriceChart`);
`Settings` + `SecretEntry`; `Watchlist` + `AddSymbolSheet`; `Dashboard`;
`BenchmarkDetail`; `Research`; `Screener`; `SecurityDetail`. Every route is
ported, and the `UnportedScreen` placeholder has been deleted.

The UI tests `PORT_PLAN.md §8` asked for are in: banners (`BannerTest`), a
provenance label on every figure (`ProvenanceTest`) and the chart's description
(`PriceChartTest`). They earned their keep immediately — between them they found
two live bugs, both written up in `KNOWN_ISSUES.md`: the chart announced a ten
percent move as "+0.10%" (a bug inherited from the Swift app), and both charts
crashed Vico by handing it an empty axis label, which is what the 1D and 5D
ranges did on the security page. `ui/components/TickItemPlacer.kt` is the fix
for the second.

Still owed by this phase: the `compose.runtime` / `foundation` / `material3`
accessor deprecations in `app/build.gradle.kts`, which stay until material3
publishes a stable 1.12.0 — see `KNOWN_ISSUES.md` under "Build and toolchain".

Then **Phase 9** (4 h) — see `PORT_PLAN.md §4`. Its wiring list is the payoff for
everything Phases 7–8 left injectable with safe defaults.

### Notes on the UI, for Phase 9 and after

- Charts are Vico 2.5.2 (`com.patrykandpatrick.vico:multiplatform`). The
  intraday overnight break is `LineCartesianLayer.LineStroke.Dashed`, so the
  Canvas fallback was never needed. `PriceChart(bars, points, segments, ticks,
  isIntraday, modifier, valueFormat)` is the one entry point.
- SwiftUI's `.accessibilityElement(children: .combine)` is
  `semantics(mergeDescendants = true)`, **not** `clearAndSetSemantics`, which
  discards child text and makes `onNodeWithText` fail against the merged tree.
  `clearAndSetSemantics` is only for Swift's `.ignore`.
- `:app` must never see Room: `LibraDatabase` stays behind `:core` types like
  `WatchlistStore`. Room is `implementation` in `:core`.
- Routes are `@Serializable` objects/data classes read back with
  `entry.toRoute<T>()`. A route carries an id, never a value; an id the
  catalog no longer knows pops the back stack.

## Deferred work, by the phase that owns it

(Every suite Phase 7 owed is ported. One row remains.)

| Owed in | What |
|---|---|
| Phase 8 | The `compose.runtime` / `foundation` / `material3` accessor deprecations in `app/build.gradle.kts` |

## Standing rules for whoever picks this up

- Query an artifact's `maven-metadata.xml` before writing a version into
  `gradle/libs.versions.toml`. Do not guess.
- The Swift app on `main` is a read-only reference.
- Port the test alongside the file. Log any deviation in `KNOWN_ISSUES.md`.
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  before invoking Gradle.
