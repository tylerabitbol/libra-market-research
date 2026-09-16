# Where the port stands

**Last checkpoint: Phase 3 (Networking) complete.** 239 tests green on both
JVM and the iOS simulator; `:androidApp:assembleDebug` green. Every commit is on this branch; nothing is stashed.

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

## Next: Phase 4 — Persistence (Room KMP)

Per `PLAN.md §4`. Budget an hour for the Gradle work alone: Room KMP needs KSP
configured per target (`kspAndroid`, `kspIosArm64`, `kspIosSimulatorArm64`,
`kspJvm`) plus `room { schemaDirectory(...) }`, and iOS needs
`BundledSQLiteDriver` and a `-lsqlite3` linker option on the framework.

## Deferred work, by the phase that owns it

| Owed in | What |
|---|---|
| Phase 4 | `SavedScreens` (settings store); `EventPersistenceTests`; `SyntheticDataTests`; `ScreenerTests` store cases |
| Phase 6 | `FundamentalDetector.restatements` + its 4 tests (needs `SECFundamentalsProvider.periodKey`); 2 fixture-backed `FilingAnalysisTests`; `CIKTests` |
| Phase 5 | The keychain, fingerprint and secrets-store suites from `NetworkingTests.swift` (14 cases) |
| Phase 7 | `SortOptionTests`, `AnnualPeriodKeyingTests`, `WatchlistIntelligenceTests`, and the `Dashboard request budget` suite (all need view models) |

## Standing rules for whoever picks this up

- Query an artifact's `maven-metadata.xml` before writing a version into
  `gradle/libs.versions.toml`. Do not guess.
- The Swift app on `main` is a read-only reference.
- Port the test alongside the file. Log any deviation in `KNOWN_ISSUES.md`.
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  before invoking Gradle.
