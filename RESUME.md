# Where the port stands

**Last checkpoint: Phase 9's code is complete on both shells; only the owner's
manual pass is left.**
Phases 0–7 complete: 475 `:core` tests green on both JVM and the iOS
simulator. Phase 8: theme, navigation, the component set, and the Settings,
SecretEntry, Watchlist, Dashboard, BenchmarkDetail, Research, Screener and
SecurityDetail screens, all compiling on both `iosSimulatorArm64` and `android`, with
16 `:app:iosSimulatorArm64Test` tests green — `BannerTest`, `ProvenanceTest` and
`PriceChartTest` in `app/src/commonTest/.../ui/` (the first run costs ~21 min,
later ones ~15-40 s).
Every commit is on this branch; nothing is stashed.

A cleanup pass has since removed the declarations that had no references
anywhere — four unwired vendor serializers, the `UnportedScreen` placeholder,
an unused DAO query and test stub, and 39 unused imports. Both platforms still
compile and the 475 JVM tests are green. The list, and the three dead-looking
things deliberately kept, are under "Deliberately absent" in `KNOWN_ISSUES.md`.

Read `PLAN.md` for the phase outline. `KNOWN_ISSUES.md` holds every deviation,
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

## Next: finish Phase 9

Phase 9 is wired on both platforms. `core/.../app/AppLaunch.kt` holds the launch
sequence both shells share (self-test, key seeding, watchlist seeding, visit
backdating, attaching the database); `app/src/iosMain/.../MainViewController.kt`
and `androidApp/.../LibraApplication.kt` supply only what is per-platform —
where the launch arguments come from, which secrets store to build, which
database file to open, and how a link is opened. `App()` takes `openSymbol` and
`openUrl`, and the `Scaffold` takes `WindowInsets.safeDrawing`. Both shells now
carry the launcher icon, and `README.md` covers building, testing and running
both. The launch, icon and plist deviations are in `KNOWN_ISSUES.md` under
"Platform shells".

Verified on the iPhone 17 Pro simulator: the app launches, and
`-LibraSelfTest` logs **`keychain=PASS round-trip succeeded`** — secure storage
works, so credentials can be entered on iOS.

Remaining: **the owner's half of the manual pass** (`PLAN.md §7`) — entering
real API keys in Settings → Data Sources, testing each connection, and
watching a screen carry live figures. Keys are never handled here.

Everything that can be checked without a key has been, on Android: five tabs,
both mandatory banners, symbol search finding AAPL, adding it, the row
persisting through Room, the security page, all seven chart ranges including
1D and 5D, the provenance badges and "Show the arithmetic", insider activity,
filings, and Settings with no secure-storage warning. `-LibraSelfTest` logs
`keychain=PASS` on both platforms.

- **Android** now runs on an emulator: `medium_phone` (API 36, Google APIs,
  arm64) created with `android emulator create medium_phone` and started with
  `android emulator start medium_phone`. Note that `avdmanager`/`sdkmanager`
  cannot see an API "37.0" image — the newer `android` CLI can, and it
  downloads its own image.
- **iOS** builds, installs, launches and self-tests, but **cannot be looked at
  from here**: this Xcode 27 install ships no `Simulator.app` (not under
  `Contents/Developer/Applications`, not anywhere), and a headless device does
  not drive the display link, so every screenshot is a stale frame. Judge iOS
  from Xcode — open `iosApp/Libra.xcodeproj` and press Run.

### What running it established

Three traps, all fixed, all written up in full in `KNOWN_ISSUES.md` — so only
the one-line version is here:

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

### Notes for whoever picks up Phase 9

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

In `PLAN.md §8`'s order: `Theme.kt`, `Icons.kt`, `Navigation.kt`, `App.kt`,
`Screens.kt`; `Components/*` (banners, claim badge, data cells, attribution
card, event card, research profile card, filing analysis card, `PriceChart`);
`Settings` + `SecretEntry`; `Watchlist` + `AddSymbolSheet`; `Dashboard`;
`BenchmarkDetail`; `Research`; `Screener`; `SecurityDetail`. Every route is
ported, and the `UnportedScreen` placeholder has been deleted.

The UI tests `PLAN.md §8` asked for are in: banners (`BannerTest`), a
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

Then **Phase 9** (4 h) — see `PLAN.md §4`. Its wiring list is the payoff for
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
