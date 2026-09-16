# Where the port stands

**Last checkpoint: Phase 7 (App environment + view models) complete.**
475 tests green on both JVM and the iOS simulator;
`:app:linkDebugFrameworkIosSimulatorArm64` green. Every commit is on this
branch; nothing is stashed.

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

## Next: Phase 8 — UI (~18 h)

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

## Phase 8 — UI (~18 h)

Per `PLAN.md §8`: `Theme.kt` + `Navigation.kt` → `Components/*` → `Settings` →
`Watchlist` → `Dashboard` + `BenchmarkDetail` → `Research` → `Screener`.
Phase 8 also owns the `compose.runtime` / `foundation` / `material3` accessor
deprecations in `app/build.gradle.kts`.

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
