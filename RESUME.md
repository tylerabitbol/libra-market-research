# Where the port stands

**Last checkpoint: Phase 8 (UI) — five of nine screens ported.**
Phases 0–7 complete: 475 `:core` tests green on both JVM and the iOS
simulator. Phase 8 so far: theme, navigation, the component set, and the
Settings, SecretEntry, Watchlist, Dashboard and BenchmarkDetail screens,
all compiling on both `iosSimulatorArm64` and `android`, plus two Compose
UI tests green (`app/src/commonTest/.../ui/BannerTest.kt`, run on
`iosSimulatorArm64` — the first run costs ~21 min, later ones ~40 s).
Every commit is on this branch; nothing is stashed.

Read `PLAN.md` for the phase outline and `KNOWN_ISSUES.md` for every deviation
taken so far — including work deliberately deferred to a later phase, which is
listed again below so it is not lost.

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

## Next: finish Phase 8 — UI

Phase 7 is done: `AppEnvironment`, all six view models (`Dashboard`,
`BenchmarkDetail`, `Watchlist` + `SymbolSearch`, `SecurityDetail`, `Research`,
`Screener`), `DeveloperOptions` and `SelfTest`, with every Swift test suite
ported. Nothing from Phases 1–7 is outstanding.

### Notes for whoever picks up Phase 8

- Derived figures are extension vals on the UiState, so a composable reads
  `state.chartBars`, not `viewModel.chartBars`.
- `SecurityDetailViewModel.awaitLoad()` joins both the load and the job a range
  change spawns. `WatchlistViewModel.refresh(...)` and
  `DashboardViewModel.refresh(...)` already suspend until done.
- View-model test stubs are shared in
  `core/src/commonTest/.../viewmodels/ViewModelTestSupport.kt`.
- `DeveloperOptions` and `SelfTest` take a `LaunchEnvironment`; Phase 9 wires
  the two shells to it.

## Phase 8 — UI: what is left

Done, in `PLAN.md §8`'s order: `Theme.kt`, `Icons.kt`, `Navigation.kt`,
`App.kt`, `Screens.kt`; `Components/*` (banners, claim badge, data cells,
attribution card, event card, research profile card, filing analysis card,
`PriceChart`); `Settings` + `SecretEntry`; `Watchlist` + `AddSymbolSheet`;
`Dashboard`; `BenchmarkDetail`.

Remaining, in order — each is a `Screen` + a `Host`, then its
`UnportedScreen` placeholder in `Screens.kt` is replaced:

1. **Research** — `Libra/Views/Research/ResearchView.swift`, 125 Swift lines.
2. **Screener** — `Libra/Views/Screener/ScreenerView.swift`, 193 lines.
3. **SecurityDetail** — 841 lines, last, because it uses every component.

Then: more Compose UI tests for the risky bits (a provenance label on every
figure, the chart's content description), a Phase 8 pass over
`KNOWN_ISSUES.md` (the section still needs the
`clearAndSetSemantics`-versus-`mergeDescendants` finding and the
`WatchlistStore` decision written up), and this file updated again.

Phase 8 also still owns the `compose.runtime` / `foundation` / `material3`
accessor deprecations in `app/build.gradle.kts`.

### Notes for the remaining screens

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
