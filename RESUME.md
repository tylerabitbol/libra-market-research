# Where the port stands

**Last checkpoint: Phase 7 (App environment + view models) about 85% done.**
455 tests green on both JVM and the iOS simulator;
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

## Next: finish Phase 7, then Phase 8

Ported and committed so far: `AppEnvironment`, and the view models
`Dashboard`, `BenchmarkDetail`, `Watchlist` (+ `SymbolSearch`), `Research`,
`Screener` and `SecurityDetail` (1,214 Swift lines, ported 1:1 per `PLAN.md
§7`), plus `DeveloperOptions` and `SelfTest`. Test suites ported:
`BenchmarkDetailViewModelTests`, `DetailAndWatchlistTests`, `SortOptionTests`,
`AnnualPeriodKeyingTests` and `HydrationTests` (both of its suites).

**What is left in Phase 7 — three test suites, no production code:**

1. `WatchlistIntelligenceTests` (`LibraTests/WatchlistIntelligenceTests.swift`,
   162 lines).
2. The `Dashboard request budget` suite
   (`LibraTests/RequestBudgetTests.swift:43`).
3. The two view-model suites in `LibraTests/AlpacaProviderTests.swift` —
   `Intraday stays out of the calculations` (line 225) and `The chart does not
   disappear` (line 354). The provider half of that file is already ported.

Then append anything further to `KNOWN_ISSUES.md` (the Phase 7 section is
already written) and move to Phase 8.

### Notes for whoever picks these up

- `SecurityDetailViewModel.awaitLoad()` joins the spawned load, replacing
  Swift's `Task.sleep(600ms)`. `WatchlistViewModel.refresh(...)` already joins.
- `HydrationTest.kt` has the stub providers (`StubMarketProvider`,
  `StubFundamentalsProvider`, `StubSECProvider`) and a `CallLog` the request-
  budget suite will want; they are `private` to that file and should be lifted
  into a shared `ProviderTestSupport`-style file rather than copied.
- Derived figures are extension vals on the UiState, so assertions read
  `model.state.value.annualRevenue`, not `model.annualRevenue`.

## Phase 8 — UI (~18 h)

Per `PLAN.md §8`: `Theme.kt` + `Navigation.kt` → `Components/*` → `Settings` →
`Watchlist` → `Dashboard` + `BenchmarkDetail` → `Research` → `Screener`.
Phase 8 also owns the `compose.runtime` / `foundation` / `material3` accessor
deprecations in `app/build.gradle.kts`.

## Deferred work, by the phase that owns it

(`SortOptionTests`, `AnnualPeriodKeyingTests`, `DetailAndWatchlistTests` and
`HydrationTests` are done; the rows below are what remains.)

| Owed in | What |
|---|---|
| Phase 7 | `WatchlistIntelligenceTests` and the `Dashboard request budget` suite (both need view models) |
| Phase 7 | The two view-model suites in `AlpacaProviderTests.swift` — `Intraday stays out of the calculations` and `The chart does not disappear` |
| Phase 8 | The `compose.runtime` / `foundation` / `material3` accessor deprecations in `app/build.gradle.kts` |

## Standing rules for whoever picks this up

- Query an artifact's `maven-metadata.xml` before writing a version into
  `gradle/libs.versions.toml`. Do not guess.
- The Swift app on `main` is a read-only reference.
- Port the test alongside the file. Log any deviation in `KNOWN_ISSUES.md`.
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  before invoking Gradle.
