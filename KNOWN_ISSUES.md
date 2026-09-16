# Known issues and deviations from PLAN.md

## Phase 0 — toolchain

The plan's assumed versions were stale by four months. Corrected against
`maven-metadata.xml` on 2026-09-15 and verified by a real build:

| Plan said | Actual | Why |
|---|---|---|
| Kotlin 2.x | 2.4.20 | 2.2.20 does not resolve |
| AGP 8.x | 9.4.0 | Compose artifacts require AGP 9.1+ |
| Gradle 8.x | 9.7.1 | required by AGP 9 |
| JDK 17 | 21 | toolchain and daemon |
| compileSdk 36 | 37 (`platforms;android-37.0`) | dependency graph requires it |

**Module structure changed.** AGP 9 forbids `com.android.library` and
`com.android.application` on a module that also applies the Kotlin
Multiplatform plugin. So:

- `:core` and `:app` are KMP libraries using `com.android.kotlin.multiplatform.library`.
- `:androidApp` is a new, pure-Android module holding `MainActivity`, the
  manifest and the theme — the Android counterpart of `iosApp/`.
- `org.jetbrains.kotlin.android` is **not** applied anywhere: AGP 9 compiles
  Kotlin itself. The Compose *compiler* plugin is still required.

**Simulator arch.** `EXCLUDED_ARCHS[sdk=iphonesimulator*] = x86_64` in
`iosApp/project.yml`. Only `iosSimulatorArm64` is a declared Kotlin target, and
an x86_64 slice makes `:app:syncComposeResourcesForIos` fail. Add `iosX64()` if
an Intel Mac ever needs to build this.

**Xcode does not inherit `JAVA_HOME`.** The `Build Kotlin framework` phase in
`iosApp/project.yml` resolves a JDK itself, falling back to the Homebrew path.
On a machine without `openjdk@21` there, edit that script.

## Deferred

- Gradle emits "incompatible with Gradle 10" deprecation warnings from the
  plugins, not from our scripts. Nothing to do until the plugins update.
- `iosApp` has no app icon or launch screen yet (Phase 9).

## Phase 1 — support and models

Ported: `APIError`, `Claim`/provenance, `Format`, `Freshness`,
`RelativeTimeText`, and the value types and enums from `Models/Core` plus the
provider DTOs and interfaces. 38 tests green on JVM and iOS.

**Deferred out of Phase 1, by design:**

- `ChartSeriesBuilder` (`Models/Core/ChartSeries.swift`) takes `PriceBar`, a
  stored row, so it moves to Phase 2 with the other pure calculations.
- Every `@Model` class is Phase 4. `FilingRecord`'s `isPeriodicReport` and
  friends go with the entity; only the form-type set is here so far.

**Deviations:**

- `Format` is hand-rolled. Foundation's `FormatStyle` has no multiplatform
  equal, so fixed-point rendering, grouping separators and **half-to-even**
  rounding are written out. Half-to-even because that is ICU's default, which
  the Swift expectations were written against — `FormatTests` avoids an exact
  .5 tie on purpose. A `grouped_thousands_never_render_in_scientific_notation`
  test was added; it has no Swift counterpart and guards the failure mode
  `toString()` would introduce.
- `Format.currency` renders non-USD as `"EUR 1,234.50"` rather than matching
  Foundation's per-locale currency symbols. Nothing calls it with a non-USD
  code, and no test covers it.
- `RelativeTimeText` replaces `RelativeDateTimeFormatter`. Unit selection is
  written out and approximates ICU at the month and year boundaries; nothing
  in the app reads above weeks.
- `URL` becomes `String` throughout. Kotlin common has no URL type, and every
  use is either display or a Ktor request.
- `Claim.id` uses a hand-rolled v4-shaped `randomId()` rather than
  `kotlin.uuid.Uuid`, which is still opt-in. Ids are UI identity only.
- `DetectedEventDTO` clamps `unusualness` in a `create` factory, because a
  Kotlin `data class` cannot transform a constructor parameter. Construct
  through `create`; the raw constructor and `copy` do not clamp.
- `EventKind.systemImage` keeps the SF Symbol names verbatim. Phase 8 maps
  them onto Material icons in one place.

## Phase 2 — calculations

**Deviation from PLAN.md §5: the Swift-side parity fixture step was skipped.**
Xcode is available, so the plan's own escape clause does not apply — this was
a judgement call, not a blocker. The step would have dumped calculator outputs
from Swift to JSON and replayed them in Kotlin at 1e-9. It was skipped because
its value overlaps almost entirely with the 14 ported Swift test files (~2,700
lines of expectations), while costing a dump harness on the Swift side plus
JSON plumbing on the Kotlin side — building every calculator's inputs twice.

If any ported figure is ever in doubt, run it then: the step is still valid and
nothing about the port prevents it.

Mitigation in its place: every Swift test file is ported, and targeted Kotlin
tests are added wherever the Swift coverage of a calculator is thin. Those
additions are marked in the test files as having no Swift counterpart.

**`PriceBar` exists now, in `models/core`.** The plan put it in Phase 4 as a
Room entity. Calculations take it, so the value type is written here and Phase
4 annotates the same class rather than introducing a second one — the
alternative was porting every calculator against a placeholder and rewriting
the signatures later.

**`FundamentalDetector.restatements` is deferred to Phase 6.** It keys revision
groups on `SECFundamentalsProvider.periodKey(_:)`, which is part of the SEC
provider and does not exist yet. Everything else in the file is ported. The
four Swift tests that cover it (`restatementIsDetected`,
`annualAndQuarterlyPeriodsAreNotConflated`, `singleFilingIsNotRestatement`,
`trivialRevisionIsIgnored`) are deferred with it and are noted at the top of
`FundamentalDetectionTest.kt`. Porting the key function early would have
duplicated provider logic that Phase 6 then has to reconcile.

**`EventPersistenceTests` travels with Phase 4, not Phase 2.** The Swift file
`EventDetectionTests.swift` holds two suites; the second exercises
`SnapshotStore`, `ModelContainer` and `Security`, none of which exist before
persistence. The detection suite is ported in full here.

**Two `FilingAnalysisTests` cases are deferred to Phase 6.**
`realAccessionJoins` and `annualFormsUseAnnualFigures` decode the
`sec_companyfacts_AAPL` fixture through `SECFundamentalsProvider.extract`.
Both arrive with the SEC provider. The other nine cases are ported and green;
the deferral is noted at the top of `FilingAnalysisTest.kt`.

**`SavedScreens` and three `ScreenerTests` cases are deferred.** `SavedScreens`
is `UserDefaults` plus `Codable`; the multiplatform equivalent needs both a
settings store (Phase 4) and kotlinx-serialization (added in Phase 3 with
Ktor). `Screen` and its rules are ported now as plain data classes and will be
annotated `@Serializable` when the dependency lands, rather than pulling a
serialization plugin into Phase 2 for one type. The deferred tests are
`savedScreensRoundTrip`, `benchmarksAreNotScreened` and the stored-figures
case; the last two need the store regardless.

*Resolved in part, Phase 3:* kotlinx-serialization landed with Ktor, so
`Screen`, `ScreenRule` and the three screener enums are now `@Serializable`
and a round-trip test replaces the serialisation half of
`savedScreensRoundTrip`. `SavedScreens` itself still waits on a settings store
in Phase 4.

**Three cross-cutting Swift test suites are split across later phases.** They
were listed under Phase 2, but each is written against a layer that does not
exist yet, so the parts that are pure calculation are ported now and the rest
travels with the layer it tests:

- `CorrectnessRegressionTests.swift` — the four valuation suites (metric key
  mapping, unrankable multiples, percentile self-exclusion, scale validation,
  13 cases) are ported. `CIKTests` needs `SECProvider` (Phase 6);
  `SortOptionTests` needs `WatchlistViewModel` and `AnnualPeriodKeyingTests`
  needs `SecurityDetailViewModel` (both Phase 7).
- `SyntheticDataTests.swift` — entirely `SnapshotStore`, `AppModelContainer`
  and the `Mock*Provider` family. Phase 4 for the store, Phase 6 for the mocks.
- `WatchlistIntelligenceTests.swift` — `WatchlistRow` ordering and the store.
  Phase 7.

Nothing is dropped; each is named against the phase that will pick it up.


## Phase 3 — Networking

**Versions added, verified against maven-metadata.xml on 2026-09-16:** Ktor
3.5.2, kotlinx-serialization-json 1.11.0 (the stable release, not the 1.12.0-RC
that `<latest>` reports). Engines are per target: OkHttp on Android and JVM,
Darwin on iOS, and Ktor resolves the engine from the classpath so `commonMain`
names none of them.

**`Endpoint` returns a URL string, not a request object.** Swift's
`makeRequest()` built a `URLRequest`; in Ktor the client owns the request, so
the endpoint's job stops at `requestURL()` plus the header map beside it. The
cache-key rules are unchanged and still strip `token` / `api_key` / `apikey`.

**Percent-encoding is hand-rolled.** `URLComponents` did this in Swift. Ktor's
`URLBuilder` re-encodes values it believes are already escaped, which turns a
`%` inside a FRED series id into `%25`, so `Endpoint` encodes query components
itself against an explicit unreserved set.

**`RateLimiter` is a class with a `Mutex`, not an actor, and the wait happens
outside the lock.** An actor serialised callers for free; a Mutex held across
the `delay` would make every caller wait for the one in front of it *and then*
for its own refill. There is a test for exactly this (`theWaitDoesNotHoldTheLock`),
which has no Swift counterpart because the bug it guards against could not
exist there.

**The limiter takes an injected `TimeSource`.** Swift's tests slept against
`ContinuousClock`. Here `runTest` drives the delay and `testTimeSource` drives
the bucket, so a one-hour Tiingo refill is asserted in virtual time. The five
provider presets are unchanged.

**`penalize` does not raise `estimatedWait`.** Faithful to Swift: the penalty
empties the bucket and pushes the refill clock forward, but `estimatedWait`
reads only the token deficit, so it under-reports during a penalty. The
behaviour a caller experiences — waiting out the full penalty — is what the
Kotlin test asserts, rather than the number the Swift code would have returned.

**`HTTPClient` has a direct test suite; Swift had none.** It was exercised only
through the provider decoding tests. Ktor raises on non-2xx only when asked, so
every status mapping is ours rather than inherited, and 14 tests pin the
mapping, the retry bound, the 403 disambiguation and the header pass-through.
`MockHttp` in `commonTest` is the `StubURLProtocol` replacement the plan asked
for, written so Phase 6's provider tests reuse it.

**Deferred from `NetworkingTests.swift`:** the keychain, fingerprint and
secrets-store suites (three suites, 14 cases) test `SecretsStore`, which is
Phase 5. The API-error and endpoint suites are ported.

**Deferred from `RequestBudgetTests.swift`:** the `Dashboard request budget`
suite needs `DashboardViewModel` and `ProviderRegistry` — Phase 7. The
`Rate limiter back-pressure` suite is ported.


## Phase 4 — Persistence

**Versions added, verified against maven-metadata.xml on 2026-09-16:** Room
2.8.5, androidx.sqlite 2.7.1, KSP 2.3.12. KSP is wired per target
(`kspAndroid`, `kspJvm`, `kspIosArm64`, `kspIosSimulatorArm64`); there is no
single `ksp(...)` configuration that covers a multiplatform module. The
`room { }` and `dependencies { }` blocks sit at the *end* of
`core/build.gradle.kts`: a top-level `add("kspJvm", ...)` evaluated before
`kotlin { }` declares its targets fails with
`Configuration with name 'kspJvm' not found`.

**`-lsqlite3` is not needed.** `PLAN.md §4` predicted a linker option on the
iOS framework. `BundledSQLiteDriver` ships its own SQLite, and
`:app:linkDebugFrameworkIosSimulatorArm64` links clean without it.

**Instants are stored as epoch nanoseconds, not ISO-8601 text.** Text sorts
wrongly: `…:20.5Z` compares *below* `…:20Z` because `.` precedes `Z`, which
would make every `ORDER BY` on a timestamp subtly incorrect. Milliseconds
would truncate a `Clock.System.now()` round-trip. Both hazards are pinned by
tests in `SchemaTest.kt`. The converter uses Kotlin's `Long.floorDiv` /
`Long.mod` rather than `Math.floorDiv`, which is JVM-only.

**Enums are converted by raw value, never by ordinal.** `BarResolution` and
`DataProviderID` round-trip through their `raw` strings, so reordering a
declaration cannot silently reinterpret stored rows. `DataProviderID` gained a
`companion object { fun fromRaw(...) }` for the reverse lookup.

**`PriceBar` is annotated in place** as the ninth entity, per the plan. Its
`@PrimaryKey(autoGenerate = true) val id: Long = 0` is declared *last* so the
positional constructor calls written in Phase 1 still compile.

**Six `record` overloads were renamed.** Swift distinguished
`record(_:for:from:)` by argument label; on the JVM the four
`record(List<X>, String, DataProviderID)` forms erase to one signature
("platform declaration clash"). They are now `recordQuote`, `recordBars`,
`recordFacts`, `recordFilings`, `recordInsiders` and `recordEvents`, and the
mapping is documented in the `SnapshotStore` KDoc.

**`EventDao.insertAll` uses `OnConflictStrategy.IGNORE`.** REPLACE would
delete and reinsert the row, losing the user's acknowledgement of an event
that is merely re-detected. The unique index on `(symbol, naturalKey)` makes
IGNORE the correct upsert here.

**`inMemoryLibraDatabase()` is expect/actual in `commonTest`.** Room's
in-memory builder exists only per platform — the native one is in `nativeMain`
and Android's requires a `Context`; there is no common overload. Actuals live
in `jvmTest` and `iosTest`. `:core` has no Android test compilation, so no
third actual is required.

**iOS uses `Dispatchers.Default` for the query context**, not
`Dispatchers.IO`, which is `internal` on Kotlin/Native in coroutines 1.11.
Both are multi-threaded pools there, so the behaviour matches.

**`-Xexpect-actual-classes` is on for `:core`.** Room generates
`LibraDatabaseConstructor` as an `actual object`. The pattern is Room's and
has no alternative spelling, so the flag replaces a file-level `@Suppress`.

**The plan's `isSample` column does not exist.** Swift marks synthetic data
two ways: a nullable `providerRaw` on `PriceBar`, and the impossible-CIK
accession prefix `0000000000-` on filings. `evictSyntheticRows()` preserves
that actual mechanism rather than inventing a flag.

**`FactPeriods` was pulled forward from Phase 6.** `SnapshotStore.facts()`
needs `periodKey`/`groupKey`/`deduplicated` to fold fact revisions, so the
helpers could not wait for `SECFundamentalsProvider`. `Month.number` is not
available in kotlinx-datetime 0.8.0; the key uses `month.ordinal + 1`.

**That un-defers `FundamentalDetector.restatements`** and its four tests,
which Phase 2 had parked against Phase 6. Both are now in.

**`SavedScreens` takes an injected `PreferenceStore`** rather than an
expect/actual class. The interface is two methods (`getString`, `setString`);
`UserDefaultsPreferenceStore`, `SharedPreferencesStore` and
`InMemoryPreferenceStore` implement it, and the last is what the tests use,
which an expect/actual would not have allowed.

**`DetailAndWatchlistTests.swift` moves from Phase 4 to Phase 7.** The plan
tagged it "(store half)", but the file has no `SnapshotStore` usage at all —
it is entirely view models. `HydrationTests` and `SampleDataTests` stay in
Phase 6 as planned, since both need the `Mock*Provider` family.

**Two Gradle deprecations remain, both from Phase 0 and both owned by Phase 8.**
The `androidLibrary { }` block is renamed to `android { }` in `core` and `app`,
which clears that one. Still open: `compose.runtime` / `compose.foundation` /
`compose.material3` accessors in `app/build.gradle.kts` are deprecated in
favour of naming the artifacts directly. Phase 8 rewrites those dependencies
anyway, so they move with the UI work rather than churning the catalog twice.


## Phase 5 — Secrets

**`SecretsStore` is an interface, not an `expect class`.** `PLAN.md §5` asked
for `expect class SecretsStore`. Swift's shape is a `protocol SecretsStoring`
with two conformers — `KeychainSecretsStore` and `InMemorySecretsStore` — and
the in-memory one is what every test and preview uses. An `expect class` admits
exactly one implementation per target, so it would have made the store
untestable and previews dependent on a real keychain. The interface carries
`hasValue`, `fingerprint`, `require` and `diagnose` as defaults, exactly as
Swift's extension did. `KeychainSecretsStore` lives in `iosMain`,
`KeystoreSecretsStore` in `androidMain`, and neither is named from
`commonMain` — the app wires the right one at startup, the same way the
`PreferenceStore` and HTTP engine choices already work.

**`OSStatus` constants are restated in `commonMain`.** `SecretsError.explain`
holds the user-facing judgement — that `-34018` is a build problem and not the
user's fault — and that is the part worth testing on every target, not just
iOS. The codes are stable ABI values, so `OSStatusCode` restates the seven the
app reacts to rather than pulling `platform.Security` into common code.
`explain` takes an optional `systemMessage`, which is what
`SecCopyErrorMessageString` returns; only the iOS store can supply it, and the
fallback still prints the raw code so an unmapped status is never swallowed.

**Android encrypts into SharedPreferences under an AndroidKeyStore AES key.**
Per the plan, and explicitly not `androidx.security:security-crypto`, which is
deprecated. Each record is `Base64(iv ‖ ciphertext)` under AES-256-GCM with a
per-write 12-byte IV, so re-saving the same key produces different ciphertext
and tampering surfaces as a decrypt failure. A `GeneralSecurityException` on
read drops the orphaned record and reports "not set": the keystore entry can
vanish on a restored backup, and throwing from a read the whole settings screen
depends on would be worse than losing a value the user must re-enter anyway.

**The Android key is not user-authentication-bound.** `setUserAuthenticationRequired(false)`
matches iOS's `kSecAttrAccessibleAfterFirstUnlock`: background refreshes need
the credentials while the screen is off. Keeping the two platforms aligned
matters more here than a stricter Android-only rule.

**`NSString` does not bridge to `kotlin.String`.** `CFBridgingRelease(...) as?
String` works for a `CFStringRef`, but `NSString.create(data:encoding:) as
String?` is a cast the compiler proves can never succeed. The keychain store
converts through raw bytes (`usePinned` + `memcpy`) in both directions instead.

**Keychain dictionaries are retained and released explicitly.** Swift's
`query as CFDictionary` was an ARC-managed bridge. Kotlin/Native has none, so
every query goes through a `withCFDictionary` helper that releases in a
`finally`; writing `CFBridgingRetain(...)` inline would leak one dictionary per
keychain access.

**`InMemorySecretsStore` uses an atomic reference, not a mutable map.** Swift
guarded its dictionary with an `NSLock`. `SecretsStore` is not a suspending
interface, so a `Mutex` is unavailable; the store holds an immutable map behind
`AtomicReference` and updates it by compare-and-set. A bare `MutableMap` shared
across threads is a data race under Kotlin/Native's memory model.

**`KeystoreSecretsStore` has no automated test.** It needs a real
`AndroidKeyStore`, which exists only on a device or emulator, and `:core` has
no Android instrumentation source set. It is compile-verified by
`:androidApp:assembleDebug`. The logic that *can* be tested off-device — the
key metadata, the fingerprint masking, the `require` contract and the status
explanations — is covered by the 24 common tests, which run on both JVM and
the iOS simulator.

**Eleven tests beyond the Swift suites.** Swift had 13 cases across
`Keychain error reporting`, `Key fingerprints` and `Secrets store`; all 13 are
ported. The additions pin things the Kotlin port newly made possible to get
wrong: the storage account names (renaming a `SecretKey` case would orphan a
credential the user already entered), the round trip through `fromRaw`, that
every key has help text, that a trailing newline in a paste does not inflate
the fingerprint length, and that `SecretsError.message` carries the
explanation rather than a code.

**`displayName` and `helpText` stay as constants.** Per the plan — they move to
`composeResources` in Phase 8.

**The `project.yml` signing note needed no work.** Phase 0 already carried the
`CODE_SIGN_STYLE: Automatic` / `DEVELOPMENT_TEAM` block and the -34018
explanation into `iosApp/project.yml` verbatim.
