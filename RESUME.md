# Where the port stands

**Last checkpoint: Phase 4 (Persistence) complete.** 298 tests green on both
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

## Next: Phase 5 — Secrets (~3h)

Per `PLAN.md §5`. `expect class SecretsStore` with `get` / `set` / `delete` /
`healthCheck`.

- **iOS actual** via `platform.Security` cinterop: `SecItemAdd`,
  `SecItemCopyMatching`, `SecItemUpdate`, `SecItemDelete` against
  `kSecClassGenericPassword`. Keep the `SecretsHealth` write-then-read probe
  and the `errSecMissingEntitlement (-34018)` explanation verbatim.
- **Android actual** via an AES key in the AndroidKeyStore wrapping an
  encrypted `SharedPreferences` file. Do *not* use `security-crypto`; it is
  deprecated.
- Port the three deferred suites from `NetworkingTests.swift` (keychain
  errors, key fingerprints, secrets store — 14 cases).

Section 19 of the spec still governs: a key never appears in the UI or in
source, is never written to the snapshot store, never logged, and never part
of a cache key. Tyler enters the keys himself — implement the integration and
stop at the credential.

## Deferred work, by the phase that owns it

| Owed in | What |
|---|---|
| Phase 6 | 2 fixture-backed `FilingAnalysisTests`; `CIKTests`; `HydrationTests`; `SampleDataTests` (all need the `Mock*Provider` family) |
| Phase 5 | The keychain, fingerprint and secrets-store suites from `NetworkingTests.swift` (14 cases) |
| Phase 7 | `SortOptionTests`, `AnnualPeriodKeyingTests`, `WatchlistIntelligenceTests`, `DetailAndWatchlistTests`, and the `Dashboard request budget` suite (all need view models) |
| Phase 8 | The `compose.runtime` / `foundation` / `material3` accessor deprecations in `app/build.gradle.kts` |

## Standing rules for whoever picks this up

- Query an artifact's `maven-metadata.xml` before writing a version into
  `gradle/libs.versions.toml`. Do not guess.
- The Swift app on `main` is a read-only reference.
- Port the test alongside the file. Log any deviation in `KNOWN_ISSUES.md`.
- `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
  before invoking Gradle.
