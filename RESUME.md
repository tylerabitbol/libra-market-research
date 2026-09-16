# Where the port stands

**Last checkpoint: Phase 6 (Providers) complete.** 421 tests green on both
JVM and the iOS simulator; `:androidApp:assembleDebug` and
`:app:linkDebugFrameworkIosSimulatorArm64` green. Every commit is on this branch; nothing is stashed.

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

## Next: Phase 7 — App environment + ViewModels (~12 h)

Per `PLAN.md §7`. `AppEnvironment`, `DeveloperOptions`, `SelfTest`, and the six
view models: `Dashboard`, `BenchmarkDetail`, `Watchlist`, `SecurityDetail`
(1214 LOC — the largest file in the app), `Research`, `Screener`.

- `ProviderRegistry` is the seam the view models depend on. None of them names
  a concrete provider, so the mocks substitute without ceremony.
- `SnapshotStore` (Phase 4) supplies the hydration path: read the held copy
  first, fetch only what is stale, and fall back to the stored copy rather
  than to an empty page when a request fails.
- This phase clears the largest deferred block — five suites listed below.

## Deferred work, by the phase that owns it

| Owed in | What |
|---|---|
| Phase 7 | `SortOptionTests`, `AnnualPeriodKeyingTests`, `WatchlistIntelligenceTests`, `DetailAndWatchlistTests`, `HydrationTests`, and the `Dashboard request budget` suite (all need view models) |
| Phase 7 | The 11 view-model suites in `AlpacaProviderTests.swift` — `Intraday stays out of the calculations` and `The chart does not disappear` |
| Phase 8 | The `compose.runtime` / `foundation` / `material3` accessor deprecations in `app/build.gradle.kts` |

## Standing rules for whoever picks this up

- Query an artifact's `maven-metadata.xml` before writing a version into
  `gradle/libs.versions.toml`. Do not guess.
- The Swift app on `main` is a read-only reference.
- Port the test alongside the file. Log any deviation in `KNOWN_ISSUES.md`.
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  before invoking Gradle.
