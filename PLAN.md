# Libra → Kotlin Multiplatform + Compose Multiplatform

Implementation plan. Written to be executed by another agent (Opus) without
re-deriving decisions. Nothing in this branch is implemented yet.

Decisions already made by the owner (do not re-litigate):

- **Scope B**: one Kotlin codebase, Compose UI on iOS *and* Android. SwiftUI retired.
- **Location**: this orphan branch `kmp-translation`, worktree `Translation/`.
  The Swift app stays on `main` as the reference; read it, never edit it.
- **Providers**: port the current five (Finnhub, Tiingo, Alpaca, FRED, SEC) 1:1,
  BYOK unchanged. No vendor changes, no licensing work, no new adapters.
- **Android floor**: API 26. **iOS floor**: 26 (matches the Swift app; no back-deployment work).
- **Charts**: a library is fine. Use Vico (Compose Multiplatform build).
- **Parity**: reasonable, not pixel-perfect. Numbers must match; layout may differ.

Source of truth for every port: `../Libra/**` and `../LibraTests/**` on `main`.
Sizes: 69 Swift files, ~15.2k LOC app; 33 test files, ~7k LOC, all Swift Testing.

---

## 0. Rules for the implementer

1. **Translate, don't redesign.** Port file-for-file, keep names, keep comments
   (they record decisions — e.g. why cumulative XBRL periods are classified,
   why intraday never falls back to daily). Refactor only after the tests pass.
2. **Port the test alongside the file.** A phase is not done until its Swift
   tests exist in `commonTest` and pass on JVM *and* iOS simulator.
3. **Never format a number with `toString()` or string templates** in code that
   the user sees or a test compares. Everything goes through `Format.kt`.
4. **Never `!!`, never `runBlocking` in production code.** Swift's `guard let`
   becomes `?: return`/`?: throw`.
5. **No new features, no API fixes.** If a provider endpoint is broken today,
   it stays broken; note it in `KNOWN_ISSUES.md` and move on.
6. **Commit per phase**, message `Port <area>`. No co-author trailers.
7. Tool budget: read a Swift file once, fully, then write the Kotlin file.
   Don't grep the same file repeatedly.

---

## 1. Project layout

```
Translation/
  settings.gradle.kts            includes :core, :app
  gradle/libs.versions.toml      single version catalog
  core/                          KMP library, no Compose
    src/commonMain/kotlin/com/tylerabitbol/libra/
      calculations/  models/  networking/  persistence/  services/
      secrets/  support/  viewmodels/
    src/commonTest/kotlin/...    ported tests
    src/commonTest/resources/    fixtures (JSON/XML) copied from LibraTests
    src/androidMain/  src/iosMain/   expect/actual only (Keychain, sqlite driver, logging)
  app/                           Compose Multiplatform
    src/commonMain/kotlin/com/tylerabitbol/libra/ui/
      components/ dashboard/ research/ screener/ securitydetail/ settings/ watchlist/
      App.kt  Navigation.kt  Theme.kt
    src/commonMain/composeResources/   strings, app icon, disclaimer text
    src/androidMain/   MainActivity, Application, Android preview annotations
    src/iosMain/       MainViewController.kt (ComposeUIViewController)
  iosApp/                        thin Xcode project (XcodeGen project.yml), links app framework
  KNOWN_ISSUES.md
```

Package root: `com.tylerabitbol.libra`. Swift directory → Kotlin package, 1:1.

`:core` must not depend on Compose. It compiles for `androidTarget`,
`iosArm64`, `iosSimulatorArm64`, and `jvm()` — the JVM target exists only so
`commonTest` runs fast (`./gradlew :core:jvmTest`).

---

## 2. Dependencies (pin latest stable at implementation time)

| Need | Library | Note |
|---|---|---|
| Language/build | Kotlin 2.x, Gradle 8.x, AGP 8.x, JDK 17 | K2 compiler |
| UI | Compose Multiplatform (org.jetbrains.compose), Material 3 | |
| Navigation | `org.jetbrains.androidx.navigation:navigation-compose` | multiplatform since 2.8 |
| ViewModel | `org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose` | `viewModelScope` in common |
| HTTP | Ktor 3 client: `core`, `content-negotiation`, `serialization-kotlinx-json`, `encoding` (gzip), `logging`; engines `okhttp` (Android), `darwin` (iOS), `cio` or `java` (jvm tests) | |
| JSON | kotlinx-serialization-json | |
| XML | `io.github.pdvrieze.xmlutil:serialization` | Form 4 parser; no XML in stdlib |
| Dates | kotlinx-datetime | `Instant`, `LocalDate`, `TimeZone.of("America/New_York")` |
| Coroutines | kotlinx-coroutines-core (+ `-test`) | |
| DB | Room KMP (`androidx.room:room-runtime`, `room-compiler` via KSP), `androidx.sqlite:sqlite-bundled` | replaces SwiftData |
| Charts | Vico (`com.patrykandpatrick.vico:multiplatform`) | line, area, axis marks |
| Logging | Kermit | replaces OSLog |
| DI | none — manual, mirrors `AppEnvironment` | app is small |
| Tests | kotlin-test, kotlinx-coroutines-test, Ktor `MockEngine`, Room in-memory | |

Don't add: Koin, Voyager, Decompose, Realm, SQLDelight, Okio (unless xmlutil
needs it), Arrow.

---

## 3. Concept mapping (apply mechanically)

| Swift | Kotlin |
|---|---|
| `struct X: Sendable, Hashable` | `data class X` |
| `enum` with associated values | `sealed interface` + data classes / objects |
| `enum: String, CaseIterable` | `enum class` with `val raw`; `entries` |
| `protocol P: Sendable` | `interface P` |
| `actor` | `class` + `Mutex` (`withLock`) |
| `@Observable @MainActor final class VM` | `class VM : ViewModel()`; one `StateFlow<XUiState>` data class |
| `@Model final class` | `@Entity data class` + DAO; relationships → foreign-key columns, not object refs |
| `@ModelActor SnapshotStore` | `class SnapshotStore(db)`; all DAO calls are `suspend`, Room dispatches |
| `throws` / `APIError` | `sealed class APIError : Exception()`; `throw` |
| `Result` | keep `Result<T>` or exceptions; match the Swift file |
| `Optional` / `guard let` | nullable / `?: return` |
| `Date` | `Instant` |
| `Calendar` date math | `LocalDate` + `TimeZone` (NY for market days, UTC for filings) |
| `Codable` | `@Serializable` + custom serializers for dates/`"."` |
| `URLSession` / `HTTPClient` | `HttpClient` wrapper with the same method signatures |
| `TaskGroup` / `async let` | `coroutineScope { async {} }` / `awaitAll` |
| `Task { }` in VM | `viewModelScope.launch` |
| `os.Logger` | `Kermit Logger.withTag` |
| `some View` | `@Composable fun` |
| `NavigationStack` + `.navigationDestination` | `NavHost` with typed routes (`@Serializable` route classes) |
| `List` / `Section` | `LazyColumn` / `stickyHeader` |
| `.sheet` / `.alert` | `ModalBottomSheet` / `AlertDialog` |
| `.task {}` / `.refreshable` | `LaunchedEffect(key)` / `PullToRefreshBox` |
| `SecureField` | `OutlinedTextField` + `PasswordVisualTransformation` |
| `.accessibilityLabel/Value` | `Modifier.semantics { contentDescription; stateDescription }` |
| `Chart { LineMark... }` | Vico `CartesianChartHost` |
| `@Environment(\.dismiss)` | `navController.popBackStack()` |
| Swift Testing `@Test` / `#expect` | `@Test fun` / `assertEquals` (tolerance for doubles) |

---

## 4. Phases

Order is dependency order. Each phase lists files, the Swift tests to port,
acceptance, and an hour estimate for Opus. Total ≈ **75 h**.

### Phase 0 — Scaffold (3 h)
- Gradle wrapper, version catalog, `:core` + `:app`, iOS framework export from `:app`
  (static framework `LibraKit`, built from `:app`, exporting `:core`), `iosApp/project.yml` (XcodeGen, team
  `3RSPVBC57V`, bundle `com.tylerabitbol.libra`, iOS 26, portrait+landscape,
  iPhone+iPad — mirror `../project.yml`).
- Kermit, coroutines, datetime wired. One `HelloTest` in `commonTest` that
  passes on `jvmTest` and `iosSimulatorArm64Test`. Android app launches to an
  empty screen; iOS app launches to an empty `ComposeUIViewController`.
- **Accept:** `./gradlew :core:allTests :app:assembleDebug` green;
  `xcodebuild -scheme Libra -destination 'platform=iOS Simulator,...' build` green.

### Phase 1 — Support + Models (value types) (6 h)
Files: `Support/Format.swift`, `Freshness.swift`, `RelativeTimeText.swift`
(logic half only), `Models/Provenance/Claim.swift`, `Models/Core/*.swift`
(**value types and enums only** — skip `@Model` classes, they're Phase 4),
`Services/Providers/ProviderProtocols.swift` (DTOs + interfaces),
`Networking/APIError.swift`.

Tests: `FormatTests`, `FreshnessTests`, `ProvenanceTests`.

Pitfalls:
- `Format.swift` uses Foundation formatters. Reimplement by hand, fixed
  `en_US` behaviour: grouping commas, fixed decimals, percent with sign,
  compact (`1.2B`), signed change. Write `formatFixed(Double, decimals)` with
  half-even? **No — check what Swift does** (Foundation rounds half-away for
  `.number.precision`? verify against `FormatTests` expected strings; the tests
  are the spec).
- `Clock` must be injectable everywhere `Date()` appears (`Freshness`,
  `RelativeTimeText`); use `kotlin.time.Clock`/`kotlinx.datetime.Clock` and a
  `TestClock` in tests.

### Phase 2 — Calculations (12 h)
Files (all pure): `ChangeWindow`, `ReturnCalculator`, `RelativeAnalysis`,
`ValuationCalculator`, `EventDetector`, `FundamentalDetector`, `FilingAnalysis`,
`InsiderActivity`, `ResearchProfile`, `ResearchProfileBuilder`, `Screener`.
Inputs that were `[PriceBar]` (`@Model`) become `List<PriceBarDTO>`-shaped
data classes; define the plain types here if Phase 1 didn't.

Tests: `ChangeWindowTests`, `ReturnCalculatorTests`, `RelativeAnalysisTests`,
`ValuationTests`, `EventDetectionTests`, `FundamentalDetectionTests`,
`FilingAnalysisTests`, `InsiderActivityTests`, `ResearchProfileTests`,
`ScreenerTests`, `SectorAttributionTests`, `CorrectnessRegressionTests`,
`SyntheticDataTests`, `WatchlistIntelligenceTests`.

Pitfalls:
- The app uses `Double` everywhere, never `Decimal` (verified). IEEE-754 is
  identical on both sides; **preserve operation order** and results match.
  `pow/log/sqrt/exp` may differ by 1 ulp across libm — compare doubles with
  `assertEquals(expected, actual, 1e-9)` relative where Swift used exact
  `==`, and record any test that needed loosening in `KNOWN_ISSUES.md`.
- Swift `sorted` isn't documented stable; Kotlin `sortedBy` is. Where a
  sort key can tie (same date, same magnitude), add the Swift tiebreaker
  explicitly.
- Percentile/rank code: check inclusive/exclusive bounds line by line —
  this is where "looks fine, off by one bucket" bugs come from.
- Trading-day arithmetic must use `TimeZone.of("America/New_York")`, and
  fiscal-period classification uses day counts (already explicit in Swift).

### Phase 3 — Networking (4 h)
Files: `Endpoint`, `HTTPClient`, `RateLimiter`.
Tests: `NetworkingTests`, `RateLimiterTests`, `RequestBudgetTests`;
`StubURLProtocol` → Ktor `MockEngine` helper in `commonTest`.

Pitfalls:
- `RateLimiter` is a token bucket actor. Port as `class RateLimiter(
  requests, per, burst, clock, delay)` with `Mutex`; inject `TimeSource` so
  tests use `runTest` virtual time, not sleeps. Keep the five factory
  presets exactly (sec 8/s, finnhub 50/min burst 10, tiingo 45/h burst 6,
  alpaca 200/min burst 30, fred 100/min burst 20).
- SEC requires a `User-Agent` and serves gzip: enable Ktor `ContentEncoding`.
- Ktor throws on non-2xx only if `expectSuccess = true`; the Swift
  `HTTPClient` maps status codes to `APIError` cases (401/403 → notEntitled,
  404 → notFound, 429 → rateLimited…). Do that mapping explicitly; don't
  rely on Ktor defaults.
- Keep cache keys free of secrets (Section 19); the Swift comment says so.

### Phase 4 — Persistence (8 h)
Files: `AppModelContainer`, `SnapshotStore`, and the nine `@Model` classes:
`Security`, `WatchlistEntry`, `PriceBar`, `QuoteObservation`,
`FinancialFactRecord`, `FilingRecord`, `InsiderTransaction`, `DetectedEvent`,
`MacroObservation`.
Tests: `PersistenceTests`, `HydrationTests`, `SampleDataTests`,
`DetailAndWatchlistTests` (store half).

Design:
- One Room `@Database(version = 1)` `LibraDatabase`; one DAO per entity;
  entities are plain data classes with `symbol`/`cik` foreign-key columns.
  Relationships that SwiftData modelled as object graphs become queries.
- `SnapshotStore` keeps its public API (method names, value-type returns).
  Everything is `suspend`; no manual dispatcher — Room handles it.
- `AppModelContainer.shared/preview` → `LibraDatabase.open(path)` and
  `LibraDatabase.inMemory()`; the "sample data must be distinguishable from
  real sessions" rule from `MarketData.swift:91` → keep the `isSample` column.
- iOS: `BundledSQLiteDriver`; database file under `NSDocumentDirectory`.
  Add `-lsqlite3` linker opt to the iOS framework. Android: default driver,
  `context.getDatabasePath`.
- Room KMP needs KSP configured **per target** (`kspAndroid`, `kspIosArm64`,
  `kspIosSimulatorArm64`, `kspJvm`) and `room { schemaDirectory(...) }`.
  Budget an hour for getting this to build; it is the fiddliest Gradle step.

### Phase 5 — Secrets (3 h)
Files: `SecretsStore.swift` (`SecretKey`, `SecretsHealth`, store).
Tests: none in Swift; write `SecretsStoreTest` against an in-memory `actual`.

- `expect class SecretsStore` with `get/set/delete/healthCheck`.
- iOS `actual`: Security framework via `platform.Security` cinterop
  (`SecItemAdd/CopyMatching/Update/Delete`, `kSecClassGenericPassword`).
  The `SecretsHealth` write-then-read probe from Swift stays — the
  `errSecMissingEntitlement (-34018)` failure mode is identical here, and the
  `project.yml` signing note carries over verbatim.
- Android `actual`: AES key in AndroidKeyStore, values encrypted into
  SharedPreferences (don't use `security-crypto`; it's deprecated).
- `helpText`/`displayName` strings move to `composeResources` later; keep
  them as constants for now.

### Phase 6 — Services / providers (10 h)
Files: `FinnhubProvider`, `TiingoProvider`, `AlpacaProvider`, `FREDProvider`,
`SECProvider`, `SECFundamentalsProvider`, `Form4Parser`,
`CompositeMarketDataProvider` (+ the three Finnhub adapter structs),
`ProviderRegistry`, `ConnectionTest`, `Mock/MockProviders`.
Tests: `AlpacaProviderTests` (654 LOC — largest), `ProviderDecodingTests`,
`FREDDecodingTests`, `SECDecodingTests`, `Form4ParsingTests`,
`FundamentalsTests`. Copy every fixture file in `LibraTests` into
`commonTest/resources` unchanged.

Pitfalls:
- FRED encodes missing observations as the string `"."`; Tiingo and Alpaca
  mix numeric strings and numbers. Write one `LenientDoubleSerializer`
  (`String?`→`Double?`) and use `Json { ignoreUnknownKeys = true; isLenient
  = true; coerceInputValues = true }`.
- Date formats differ per vendor (ISO-8601 with/without zone, `yyyy-MM-dd`,
  epoch seconds from Finnhub). One serializer per shape, in
  `networking/serializers/`.
- `Form4Parser` is XML. `xmlutil` handles it; if its annotations fight the
  document, fall back to xmlutil's streaming `XmlReader` and port the parser
  as a hand-walk — the Swift code is 186 lines, the walk is small.
- SEC XBRL companyfacts is large (tens of MB for big filers). Parse
  streaming or at least off-main; the Swift `@ModelActor` note about "a large
  XBRL" exists for this reason.
- `FiscalPeriodKind.classify` and the cumulative-period rule are the most
  consequential logic in this phase — port exactly, test exactly.
- Every `notEntitled` (403) path Finnhub returns on the free tier must still
  surface as `APIError.NotEntitled` so the UI can say "not in your plan".

### Phase 7 — App environment + ViewModels (12 h)
Files: `AppEnvironment`, `DeveloperOptions`, `SelfTest`, and the six view
models (`Dashboard`, `BenchmarkDetail`, `Watchlist`, `SecurityDetail` — 1214
LOC — `Research`, `Screener`).
Tests: `BenchmarkDetailViewModelTests`, `DetailAndWatchlistTests` (VM half).

Design:
- Each VM: `class XViewModel(env: AppEnvironment) : ViewModel()`, private
  `MutableStateFlow<XUiState>`, public `val state: StateFlow<XUiState>`.
  Every `@Observable` stored property becomes a field of the `UiState` data
  class; computed properties become extension vals on `UiState`.
- `AppEnvironment` stays the composition root: builds `HttpClient`,
  limiters, providers, registry, store, secrets, clock. Provide it through a
  `CompositionLocal` in the UI; construct VMs with `viewModel { }` factories.
- `SecurityDetailViewModel`: port 1:1 even though it's long. Splitting it is
  a post-parity task.
- `SelfTest` and `DeveloperOptions` are small and useful on device; port them.

### Phase 8 — UI (18 h)
Order: `Theme.kt` + `Navigation.kt` → `Components/*` → `Settings` →
`Watchlist` → `Dashboard` + `BenchmarkDetail` → `Research` → `Screener` →
`SecurityDetail` (last; 841 LOC, uses everything).

- Material 3, dynamic color off, one `LibraTheme` with light/dark. Match the
  Swift app's tone: dense, text-first, no hero graphics.
- `DisclaimerBanner` and `SampleDataBanner` are **mandatory** on the screens
  where Swift shows them; the README treats them as product rules.
- `ClaimBadge`/`DataCells`/`AttributionCard`: these render provenance. Every
  figure keeps its source label.
- `PriceChartView` → `PriceChart.kt` with Vico: line for daily, segmented
  line for intraday sessions, trailing y-axis, custom x ticks from
  `ChartAxisTick`. Reproduce `intradayChartLabel/Value` as
  `contentDescription`/`stateDescription`. If Vico can't do the segment gap,
  draw with `Canvas` — ~300 LOC, acceptable.
- Settings → Data Sources: masked fields, per-key help text, "Test
  connection" using `ConnectionTest`, `SecretsHealth` warning when the
  Keychain probe fails.
- Accessibility: every tappable row gets `Role`, every chart gets a text
  description, every figure cell merges descendants. Use `sp` only.
- Landscape and iPad/tablet: don't design for them; just don't break
  (no fixed widths).
- No custom fonts. No animations beyond defaults.

### Phase 9 — Platform shells, assets, release hygiene (4 h)
- App icon from `../Libra/Resources/Assets.xcassets` into Android mipmaps
  and iOS asset catalog; Compose resources for strings.
- iOS: `MainViewController.kt` returns `ComposeUIViewController { App() }`;
  status-bar/safe-area via `WindowInsets.safeDrawing`.
- Android: `MainActivity`, `enableEdgeToEdge()`, `Application` creating
  `AppEnvironment` once.
- Store the DB and secrets paths per platform (`expect fun appPaths()`).
- `README.md` for this branch: how to build both, how to run tests.
- `KNOWN_ISSUES.md`: every loosened test, every parity gap.

---

## 5. Parity fixture step (do once, before Phase 2) (2 h)

The Swift tests encode expected numbers, but a second net is cheap: add one
Swift test on `main` (in a throwaway local branch, not committed) that feeds
the existing calculator fixtures through `ReturnCalculator`,
`RelativeAnalysis`, `ValuationCalculator`, `EventDetector`,
`FundamentalDetector` and dumps outputs to JSON. Copy those JSONs into
`core/src/commonTest/resources/parity/` and write one Kotlin test per
calculator that replays them with 1e-9 tolerance. This catches operation-order
drift the hand-written tests don't cover. Needs Xcode on the machine; if it
isn't available, skip and say so.

---

## 6. Things that will go wrong (and what to do)

| Symptom | Cause | Fix |
|---|---|---|
| Room KSP "no processor for iosSimulatorArm64" | KSP not declared per target | add every `ksp<Target>` config |
| iOS link error `_sqlite3_*` | bundled driver not linked | `linkerOpts("-lsqlite3")` in framework block |
| Keychain reads return null after write on iOS | unsigned/un-entitled build | signing on in `project.yml`, same team ID; `SecretsHealth` should report it |
| Doubles differ in 15th digit | libm / op order | tolerance 1e-9; if larger, you changed operation order — diff the Swift |
| Numbers show `1.0E7` | someone used `toString()` | route through `Format.kt` |
| Fiscal quarter looks 3× too big | cumulative period read as discrete | `periodKind.isDiscrete` filter missing |
| FRED decode crash | `"."` value | `LenientDoubleSerializer` |
| Finnhub 403 shows empty chart | 403 mapped to generic error | map to `NotEntitled` in `HTTPClient` |
| Compose iOS text tiny/huge | using `dp` for text | `sp` |
| Test passes on JVM, hangs on iOS | `runBlocking` on main / Dispatchers.Main missing | `runTest`; never block main |
| Intraday chart draws a line across the overnight gap | one series, not segments | split per `ChartSegment` |
| Rate limiter tests take minutes | real delays | inject `TimeSource`, use `runTest` |
| xmlutil can't map Form 4 | schema quirks | hand-walk with `XmlReader` |

Also:
- **Concurrency guarantees weaken.** Swift 6 checked `Sendable` at compile
  time. Kotlin won't. Keep all mutable state inside VMs (`StateFlow`) or
  behind `Mutex`; no shared mutable singletons besides `AppEnvironment`.
- **Structured cancellation:** Swift `Task` cancellation on view disappear
  is automatic; in Compose it's `viewModelScope` + `LaunchedEffect` keys.
  Any long fetch must check `ensureActive()` between steps.
- **Sample-data flag** (`isUsingSampleData`) must reach every screen; the
  banner is not optional.
- **English only.** Don't localise during the port.

---

## 7. Definition of done

- `./gradlew :core:jvmTest :core:iosSimulatorArm64Test` — all ported tests
  green (target: 33 test files → 33 Kotlin test files, same test names).
- `./gradlew :app:assembleDebug` installs and runs on an API 26 emulator and
  on a current device.
- `iosApp` builds and runs on an iOS 26 simulator; Keychain probe passes on
  a signed build.
- Manual pass on both platforms: enter keys → Test connection → add a
  symbol → Dashboard, Security Detail (all ranges incl. 1D/5D), Research,
  Screener, Benchmark Detail, Settings. Disclaimer and sample-data banners
  appear where they do in Swift.
- `KNOWN_ISSUES.md` lists every deviation.

## 8. Out of scope

Provider consolidation / commercial licensing (see earlier research), backend,
billing, localisation, widgets, watchOS/macOS/desktop targets, refactoring
`SecurityDetailViewModel`, new features of any kind.

## 9. Estimate

| Phase | h |
|---|---:|
| 0 Scaffold | 3 |
| 1 Support + models | 6 |
| Parity fixtures | 2 |
| 2 Calculations | 12 |
| 3 Networking | 4 |
| 4 Persistence | 8 |
| 5 Secrets | 3 |
| 6 Providers | 10 |
| 7 Env + ViewModels | 12 |
| 8 UI | 18 |
| 9 Shells + hygiene | 4 |
| **Total** | **≈ 82** |

Working time for the implementing agent, not calendar time. The biggest
uncertainties are Room-KMP build setup (Phase 4) and the intraday chart
(Phase 8); both have documented fallbacks above.
