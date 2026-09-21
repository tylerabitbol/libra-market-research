# Known issues and deviations from PLAN.md

Every place this port diverges from `PLAN.md` or from the Swift app, and why.
Organised by the part of the codebase it constrains, so touching one area means
reading one section. `RESUME.md` says where the work stands; this file says what
the code does that you would not predict.

Resolved items are not kept. Entries that record a *decision* stay even when the
work is done, because reversing the decision is the thing that would go wrong.

| If you are touching | Read |
|---|---|
| Gradle, versions, module layout, Xcode project | [Build and toolchain](#build-and-toolchain) |
| `support/`, `models/` | [Support and value types](#support-and-value-types) |
| `networking/` | [Networking](#networking) |
| `persistence/` | [Persistence](#persistence) |
| `services/secrets/` | [Secrets](#secrets) |
| `services/providers/` | [Providers](#providers) |
| `viewmodels/`, `app/` | [View models and app environment](#view-models-and-app-environment) |
| `:app` (Compose UI) | [UI](#ui) |
| `:androidApp`, `iosApp/`, launch | [Platform shells](#platform-shells) |
| tests, fixtures, harnesses | [Testing](#testing) |
| anything — check first | [Open issues](#open-issues) · [Deliberately absent](#deliberately-absent) |

## Build and toolchain

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

**Persistence versions, verified against maven-metadata.xml on 2026-09-16:**
Room 2.8.5, androidx.sqlite 2.7.1, KSP 2.3.12. KSP is wired per target
(`kspAndroid`, `kspJvm`, `kspIosArm64`, `kspIosSimulatorArm64`); there is no
single `ksp(...)` configuration that covers a multiplatform module. The
`room { }` and `dependencies { }` blocks sit at the *end* of
`core/build.gradle.kts`: a top-level `add("kspJvm", ...)` evaluated before
`kotlin { }` declares its targets fails with
`Configuration with name 'kspJvm' not found`.

**Networking versions, verified against maven-metadata.xml on 2026-09-16:**
Ktor 3.5.2, kotlinx-serialization-json 1.11.0 (the stable release, not the 1.12.0-RC
that `<latest>` reports). Engines are per target: OkHttp on Android and JVM,
Darwin on iOS, and Ktor resolves the engine from the classpath so `commonMain`
names none of them.

**The `project.yml` signing note needed no work.** Phase 0 already carried the
`CODE_SIGN_STYLE: Automatic` / `DEVELOPMENT_TEAM` block and the -34018
explanation into `iosApp/project.yml` verbatim.

**Two Gradle deprecation warnings are open and both are out of our hands.**
Gradle reports the build "incompatible with Gradle 10" from the plugins, not
from our scripts. Separately, the `compose.runtime` / `compose.foundation` /
`compose.material3` accessors in `app/build.gradle.kts` are deprecated in favour
of explicit coordinates — but `org.jetbrains.compose.material3:material3` has
published no stable 1.12.0, only `1.12.0-alpha03`, so naming them explicitly
would mean choosing an alpha over what the plugin resolves. The plugin knows the
right mapping. Both warnings are cosmetic and stay until the plugins update.
(The `androidLibrary { }` → `android { }` rename that accompanied them is
done.)


## Support and value types

Foundation types with no multiplatform counterpart, and what replaced them:

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

**`PriceBar` exists now, in `models/core`.** The plan put it in Phase 4 as a
Room entity. Calculations take it, so the value type is written here and Phase
4 annotates the same class rather than introducing a second one — the
alternative was porting every calculator against a placeholder and rewriting
the signatures later.

**`PriceBar.closeOnly` was missed in Phase 1.** Added as
`fun PriceBar.Companion.closeOnly(observations: List<MacroObservationDTO>)`
next to the macro models, with an empty `companion object` on `PriceBar` for it
to hang off.


## Networking

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


## Persistence

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

**`FactPeriods` lives in `services/providers/`, and `SnapshotStore` uses it.**
`periodKey`, `groupKey` and `deduplicated` fold fact revisions, which
`SnapshotStore.facts()` needs, so they are not private to
`SECFundamentalsProvider` the way Swift had them. The Swift tests that call
`SECFundamentalsProvider.deduplicated` call `FactPeriods` here; the behaviour is
identical. `Month.number` is not available in kotlinx-datetime 0.8.0, so the key
uses `month.ordinal + 1`.

**`SavedScreens` takes an injected `PreferenceStore`** rather than an
expect/actual class. The interface is two methods (`getString`, `setString`);
`UserDefaultsPreferenceStore`, `SharedPreferencesStore` and
`InMemoryPreferenceStore` implement it, and the last is what the tests use,
which an expect/actual would not have allowed.

**`:app` reaches storage through `WatchlistStore`, never `LibraDatabase`.**
See [UI](#ui) — the module boundary is enforced by `:core`'s dependency
configuration, so widening it fails the `:app` build rather than degrading
quietly.

**`Instant` overflowed the Room converter, and every bounded range query
returned nothing.** Instants are stored as nanoseconds in a `Long`.
`Instant.DISTANT_FUTURE` is about 3×10²¹ nanoseconds, which wraps to a negative
number, so `SnapshotStore.bars(symbol, from = …)` — which passes
`DISTANT_FUTURE` as its open upper bound — matched no rows at all. The store
looked permanently empty: nothing hydrated, and every page re-fetched five
years of history it already held, against the scarcest budget in the app. The
converter now saturates at `Long.MAX_VALUE`/`MIN_VALUE` and maps those back to
the distant sentinels. Nanosecond precision caps real dates at 2262, which is
unchanged from before and not a limit this app can reach.

This was invisible until `HydrationTests` ran, which is the argument for
porting the tests alongside the code rather than after it.


## Secrets

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

**Keychain dictionaries are built as real `CFMutableDictionary`s, never bridged
from a Kotlin `Map`.** This is the one that cost the most to find. The `kSec…`
names are `CFStringRef` *pointers*, not objects, so bridging a map containing
them yields a dictionary whose keys are opaque Kotlin wrappers that the Security
framework does not recognise — and **every** call fails with `errSecParam`
(-50). Only `SecItemAdd` surfaced it, because the read and the delete treat any
non-success as "not there", which made it look like a bug in the write for a
long time. `withCFDictionary` now creates the dictionary, fills it and releases
it in a `finally`; Swift got the retain/release from ARC on `query as
CFDictionary`, and writing `CFBridgingRetain(...)` inline would leak one
dictionary per keychain access.

Two corollaries. `CFBridgingRelease(CFRetain(x))` *does* yield the right
`NSString` for a `kSec…` constant — `kSecClass` becomes `"class"` — so verifying
the bridging in isolation proves nothing and sent one debugging session down the
wrong path. And a `core/src/iosTest` suite cannot catch any of this: the test
bundle has no host app and so no entitlements, so every Keychain call in one
fails with `-25291` (`errSecNotAvailable`) before anything else is evaluated,
and the test passes whether the bug is present or not. That is the limit
`SelfTest`'s own comment describes and the reason `SelfTest` exists — verifying
secure storage means running the signed app with `-LibraSelfTest` and reading
its log.

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

**`SecretKey.displayName` and `helpText` are plain constants.** Phase 5 parked
them against a move to `composeResources`; that move was later dropped outright
(see [Deliberately absent](#deliberately-absent)), so constants are where they
stay.


**The seed path carries Alpaca's key pair, which the Swift original did not.**
`DeveloperOptions.mapping` in Swift covers four secrets — Finnhub, Tiingo, FRED
and the SEC contact email — and leaves Alpaca's `keyID`/`secret` pair to be
typed into Settings. `LIBRA_ALPACA_KEY_ID` and `LIBRA_ALPACA_SECRET` were added
during the manual pass so the pair could be seeded like the rest.

The justification recorded here at the time was that Settings was unreachable on
this machine. **That was wrong** — Settings was reachable throughout; see the
retraction under [Platform shells](#platform-shells). The rows are kept because
seeding all six from one place is genuinely easier than typing two 40-character
halves into a simulator, not because anything forced it.

So: a deviation from `PLAN.md §0.5` ("no new features"), on the owner's
instruction and for convenience. It is debug-only — `DeveloperOptions` gates
every argument on `isDebugBuild`, so a release build ignores the variables — and
it adds no capability the other four secrets did not already have. If the Swift
app is ever the reference again, these are the two rows of `mapping` with no
counterpart there.


## Providers

**One lenient serializer per shape, in `networking/serializers/`.** Swift's
`Decodable` absorbed vendor sloppiness quietly; kotlinx.serialization does not.
`LenientDoubleSerializer` reads a number a vendor may have sent as a string, and
`libraJson` gained `coerceInputValues`. `VendorDate` collects the date shapes —
ISO-8601 with and without fractional seconds, bare `yyyy-MM-dd`, and Finnhub's
epoch seconds — so a formatter configured slightly differently in one provider
can no longer shift a chart by a day.

`PLAN.md §3` asked for one serializer per shape, and Long, String, ISO-instant
and day-instant siblings were written to match. Every provider turned out to
reach `VendorDate` directly and to need leniency only on doubles, so all four
sat unreferenced from the day they were written through the end of Phase 9 and
have been deleted. Write the next one when a payload asks for it.

**Finnhub's metric object is read more strictly than everything else.** It
mixes ratios with date strings under one schema, so values are decoded as raw
`JsonElement` and only entries that *arrived as JSON numbers* are kept. Using
the lenient parser here would read `52WeekHighDate` as a year and put it where
a multiple belongs. Swift's `JSONValue.doubleValue` had the same rule.

**The XML parser is hand-rolled, not `xmlutil`.** `PLAN.md §6` allowed this as
a fallback; it is the better default here. The only XML this app reads is an
SEC ownership form — a few kilobytes of plain elements, no namespaces to
resolve, no DTD to honour, no schema to validate — so a parser dependency would
be carried for one file. `XMLTree` in `support/` is a direct port of Swift's,
with the `XMLParser` delegate replaced by a small scanner. It drops namespace
prefixes, so a filing agent that adds one cannot change which elements are
found, and it returns null on an unclosed element rather than reading half a
truncated filing as a complete one. That last case has no Swift counterpart —
`XMLParser` reported it itself — and has its own test.

**`Form4Parser` takes a `String`, not bytes.** Swift took `Data` and handed it
to `XMLParser`. Nothing in the parse needs the bytes, and `SECProvider` already
has to decode them, so the seam moved one step up.

**`SECProvider.ownershipXMLURL` rebuilds the path by hand.** Swift used
`URLComponents`. The rule is unchanged: drop the first path segment beginning
`xsl`, because that directory serves the XSL-rendered HTML and the
machine-readable document is the same filename one level up.

**`TickerMapCache` is a class with a `Mutex`, not an actor** — the same
substitution `RateLimiter` made in Phase 3.

**`putIfAbsent` is JVM-only.** The ticker map's first-wins rule is spelled out
with an explicit `containsKey` check. Caught by the iOS compilation, not the
JVM one, which is the argument for running both.

**`CompanyMetricsProvider` was missing.** Phase 1 ported the other provider
protocols and skipped this one; it is added here, where
`FinnhubMetricsProvider` needed something to conform to.

**`SeededGenerator` is ported exactly; the uniform draw is not.** The FNV-1a
seed and the xorshift64\* step are reproduced bit for bit, so a symbol produces
the same series across launches and platforms — the property the tests depend
on. Swift's `Double.random(in:using:)` is not specified precisely enough to
reproduce, so `nextDouble` maps the top 53 bits itself. Sample *values*
therefore differ from the Swift app's; nothing asserts a specific one.


## View models and app environment

**View models are plain Kotlin in `:core`, not `androidx.lifecycle.ViewModel`
in `:app`.** Swift's `@Observable @MainActor final class` has no direct
equivalent that is also testable off-device. Each one takes a `CoroutineScope`,
holds a `private val _state = MutableStateFlow(…UiState())`, and exposes
`state: StateFlow<…>`. Every Swift computed property becomes an extension val
on the UiState, so the derivations are pure functions of a value and can be
asserted without constructing a view model at all. The cost is that `:app` must
supply the scope and cancel it; the gain is that the whole phase's tests run in
`commonTest` on the JVM in seconds.

**`WatchlistEntry.security` becomes an explicit join.** SwiftData handed the
view model an object graph; Room entities carry plain foreign-key columns. A
`WatchlistDao.members()` query returns a `WatchlistMember(symbol, name, sector,
priority)` row instead, and the view model builds its rows from that.

**`ChartValueFormat` moves out of the SwiftUI chart view** into
`BenchmarkDetailViewModel.kt`, where the view model that decides the format
lives. `SecurityDetailViewModel` reuses it rather than declaring a second copy.

**`DeveloperOptions` and `SelfTest` take a `LaunchEnvironment` rather than
reading the process.** Swift reads `CommandLine.arguments` and
`ProcessInfo.environment` directly and compiles the whole file out of release
builds with `#if DEBUG`. None of the three has a Kotlin equivalent that works
on both targets: an Android process has no argv, and common source has no
build-configuration flag. So arguments, environment and `isDebugBuild` are
passed in, with release defaults — a shell that supplies nothing gets a
`DeveloperOptions` that does nothing, which is what the Swift compiler gave.
Both shells supply one; see [Platform shells](#platform-shells).

**Fixture capture returns JSON instead of writing a file.** Swift's
`SelfTest.captureFixtures` wrote into the app container for `simctl
get_app_container` to lift out. There is no common filesystem API in this
stack and the two platforms put private storage in different places, so the
trimmed payload is returned and the platform shell writes it.

**`SecurityDetailViewModel` gains `awaitLoad()`.** `load` spawns and returns,
and Swift's tests slept 600 ms and hoped. Joining the job is exact, faster, and
the only test affordance the file needed beyond the
`applyFundamentalsForTesting` Swift already had.

**The detection diagnostic is logged at debug level rather than compiled out.**
Swift wraps the `DETECT …` line in `#if DEBUG`; Kotlin has no equivalent, so
the sink drops it in release instead of the compiler.


## UI

**Vico draws both charts; the Canvas fallback was not needed.** `PLAN.md §8`
allowed hand-drawing the chart if Vico could not express the gap between
sessions. It can: `LineCartesianLayer.LineStroke.Dashed` and one series per
`ChartSegment` give a full-weight traded run and a thinner dashed overnight
link, with the same area fill under both so no notch of bare background reads
as missing data. Vico's multiplatform artifact (2.5.2) publishes android,
iosArm64 and iosSimulatorArm64.

**The daily chart plots against the session index, not the date.** Swift plots
against `Date` and lets Charts pick four labels; Vico's x-axis is numeric, so
the dates travel in a lookup the value formatter reads. This incidentally
removes the workaround Swift needed — it anchors x labels trailing so the last
one does not overflow the y-axis gutter — because a positional axis cannot
overflow it.

**The pinned freshness pill needs a shadow, because Compose has no material.**
The Dashboard and the security page both float "Updated just now" over the
scrolling content, the way Swift does with `.background(.regularMaterial, in:
.capsule)`. Ported as a `surfaceVariant` fill, the pill came out the same tone
as the cards passing under it, so on a real screen it read as a stray line of
text laid across a filing card and then across the range selector — a bug you
cannot see in a test, only by looking. `PinnedFreshnessLabel` in
`ui/components/DataCells.kt` is the fix: an opaque `surface` capsule with a
3 dp shadow, which is the nearest Compose gets to a material.

**SF Symbols has no multiplatform counterpart.** Three consequences. The five
tab glyphs are drawn as `ImageVector`s in `ui/Icons.kt`, because a bottom bar
without icons is not a tab bar. The decorative glyphs that lead a card header —
`arrow.triangle.branch` on the attribution card, `doc.text.magnifyingglass` on
the filing card, and `event.kind.systemImage` on every event card — are
dropped: each sits beside a text label saying the same thing, so nothing is
lost but decoration. Eighteen event-kind glyphs redrawn by hand would be
decoration with a maintenance cost. The expand/collapse chevrons are `▾`/`▴`
characters.

**`Link` becomes `LocalUrlOpener`.** SwiftUI opens a URL with no ceremony;
Compose has no multiplatform equivalent, and the two shells differ — an
`Intent` on Android, `UIApplication.openURL` on iOS. The capability is provided
at the root and defaults to doing nothing, which is correct in a test and in a
preview. Each shell provides the real opener.

**Semantic colours are named rather than inherited.** SwiftUI's `.secondary`,
`.tertiary` and `.orange` account for nearly every colour in the Swift views.
Material 3 has the first two and nothing for the third, so `LibraColors` names
`secondaryText`, `tertiaryText`, `caution`, `positive` and `negative`. Dynamic
colour is off on purpose: a caution is orange because it is a caution, and
letting the wallpaper choose would make "is this a warning?" a question about
the device.

**Type-safe navigation routes.** `@Serializable` route types rather than
`"security/{symbol}"` format strings, so a destination's arguments are checked
by the compiler and the `SavedState` argument API never appears. Switching tabs
saves and restores each section's stack, which is what Swift gets from one
`NavigationStack` per tab.

**`.combine` is `mergeDescendants`, not `clearAndSetSemantics`.** The first two
Compose UI tests failed with "Expected exactly '1' node but could not find any
node… the unmerged tree contains '1' node that matches", because every
`.accessibilityElement(children: .combine)` had been translated as
`clearAndSetSemantics`. The two are not the same: `.combine` and
`mergeDescendants` gather the children's text into one node, while
`clearAndSetSemantics` *discards* it and keeps only what the block sets. Every
combined element had therefore become an unlabelled box to a screen reader.
`clearAndSetSemantics` now appears only where Swift wrote
`.accessibilityElement(children: .ignore)` — the attribution bar and the
unusualness meter, both of which supply their own sentence. This was worth the
whole UI-test decision on its own.

**`:app` never sees Room.** Exposing `LibraDatabase` from `AppEnvironment` put
`androidx.room.RoomDatabase` on `:app`'s compile path, where Room is not a
dependency (it is `implementation` in `:core`). Rather than widen the
dependency, the watchlist's three operations moved behind
`core/.../persistence/WatchlistStore.kt` and `AppEnvironment` exposes that. The
module boundary is the point: the UI layer works in terms of the app's own
types and cannot accidentally take a dependency on the storage engine.

**The toolbar becomes a screen header.** Swift hangs sort menus and the
screener's action menu off `.toolbar`, which needs a `NavigationStack` title
bar. The Compose shell is a bottom `NavigationBar` with no top bar, so each
screen that had toolbar items draws its own header row — title on the left,
controls on the right. Watchlist established the shape; Research and Screener
follow it. The alternative, a `TopAppBar` in the shell, would have put a second
title above five screens that mostly do not want one.

**Swipe-to-delete becomes a visible control.** `onDelete` on a `ForEach` and
`.swipeActions` give SwiftUI list rows a delete gesture for free. The screener's
rules and saved screens now carry an explicit "Remove"/"Delete" button instead.
A swipe that nothing announces is not discoverable, and Compose's
`SwipeToDismissBox` would have needed per-row dismiss state for an action that
is used rarely.

**The screener's threshold is edited as text.** SwiftUI's
`TextField(value:format:.number)` binds a `Double` directly. Doing the same in
Compose — reformatting the field from the parsed number on every keystroke —
makes "-", "1." and "0.0" impossible to type. The field holds the draft string
and commits only when it parses, so the rule keeps its last valid threshold
while a partial number is being typed.

**`AppEnvironment` gained a `preferences` parameter.** Swift's
`ScreenerViewModel` reached `UserDefaults.standard` directly. `PreferenceStore`
has a `NSUserDefaults` implementation on iOS and a `SharedPreferences` one on
Android, but neither is reachable from common code, so the composition root now
injects it exactly as it injects `secrets` — defaulting to the in-memory store,
which is what a test and a preview should get. Each shell passes the real one.

**`PriceChartView`'s value format is the security page's default.** The
benchmark page passes `state.valueFormat` because an index level is not a
price; the security page passes nothing, which is `PriceChart`'s currency
default. Same component, two callers, no branch inside it.

**The custom-date sheet is a `DatePickerDialog`.** Swift presented a graphical
`DatePicker` in a half-height sheet. Material 3's dialog is the nearest
equivalent, and `SelectableDates` enforces the same `...Date.now` bound so a
window cannot open in the future. `ChangeWindow.Custom` would otherwise have
been a model state the UI could never reach.

**`Divider()` inside a menu, plus `✓ ` prefixes.** SwiftUI's `Menu` takes
`Section` headers and `Label(…, systemImage: "checkmark")` for the selected
item. `DropdownMenu` has neither, so the kind filter draws its category
headers as plain text rows and marks the current selection with a leading
`✓ ` (and three spaces when unselected, so the labels stay aligned).

**The chart's spoken move was a hundredth of the real one (fixed; a bug in the
Swift app, not a port artifact).** `PriceChartView.dailyChartValue` and
`intradayChartValue` compute `(last - first) / first` — a fraction — and pass it
to `Format.signedPercent`, which only appends a `%` to whatever number it is
given. Everywhere else in both apps a percentage travels in percent units
(`ReturnCalculator.simpleReturn` ends in `* 100`), so VoiceOver announces a ten
percent move as "+0.10% across the window" while the figure beside the chart
says "+10.00%". The Kotlin `windowDescription` multiplies by 100 and the UI test
pins the sentence. This is a divergence from Swift on purpose: reproducing it
would mean the only reader who cannot see the line is also the only one told the
wrong number.

**An axis label Vico will not draw: `TickItemPlacer`.** Both charts' x-axis
formatters returned `""` for a position with no tick — the daily chart for an
out-of-range index, the intraday chart for every bar that is not the start of a
session. Vico raises `CartesianValueFormatter.format returned an empty string`
and the chart fails to draw, so the 1D and 5D ranges crashed the security page
as soon as a UI test rendered one. Vico's own item placers space labels evenly
(`aligned`, `segmented`) and neither chart's labels are evenly spaced, so
`ui/components/TickItemPlacer.kt` implements `HorizontalAxis.ItemPlacer` over the
exact positions the model chose: the `ChartAxisTick` positions on the intraday
chart, and four dates spread across the range on the daily one, which is what
Swift got from `AxisMarks(values: .automatic(desiredCount: 4))`. The formatters
are total now as well, so a position the placer did not choose can never yield an
empty string. Nothing caught this before the UI test because the crash needs the
chart to actually measure itself.

**A Vico chart scrolls unless told to fit, and three things followed from
that.** A `CartesianChartHost` lays its layer out at a fixed spacing per point
and lets the user scroll, so a year of sessions was many screens wide and only
the first eight days were ever visible — the line looked plausible, which is
why neither the UI tests nor a glance caught it. Swift's chart fits its range,
so both charts now pass `rememberVicoScrollState(scrollEnabled = false)` and
`rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content)`; this
is a figure inside a page that itself scrolls, and a gesture that fights the
page is worse than no gesture. Two consequences fell out of fixing it:

- `TickItemPlacer` must override `getFirstLabelValue` and `getLastLabelValue`.
  The interface's defaults return null, which Vico reads as "no label at the
  extreme" and drops — the first and last of four dates simply never drew.
- The axis label narrows to fit: `axisDateLabels` gives "Sep 18" within a year
  and "Sep 2025" beyond one, because four "Sep 18, 2025" labels side by side
  are each ellipsised to "Sep 18, …". Swift gets the same narrowing for nothing
  from Charts' automatic axis. `Format.monthAndYear` is new for it.

All of this was found by running the app, not by a test: a chart that draws the
wrong window still draws, and the semantics the UI tests read are computed from
the data rather than from the picture.


## Platform shells

**Launch wiring lives in `:core`, not in each shell.** Swift splits it between
`LibraApp.init` (self-test, key seeding) and `RootView.task` (watchlist seed,
visit backdating, attaching the container). Two Compose shells would each carry
a copy of that sequence and the copies would drift, so `app/AppLaunch.kt` holds
it once and the shells pass in only what is genuinely per-platform: where the
launch arguments come from, which secrets store to build, and which database
file to open. `AppLaunch.start` returns the environment; `AppLaunch.attach` is
suspending and runs off the critical path, because the seeds write rows and a
write on the main thread at launch is what makes a cold start stutter.

**`Platform.isDebugBinary` rather than an Xcode build setting.** `PLAN.md §9`
says argv on iOS and `BuildConfig` on Android. Kotlin/Native already knows
whether it is a debug binary, so the iOS shell reads that instead of threading a
constant through the Xcode configuration to say the same thing. Android still
uses `BuildConfig.DEBUG`, which is the same fact by the same name.

**Android's launch arguments come from the intent's extras.** iOS reads argv,
which exists before any UI does; an Android process has no argv at all. The
nearest equivalent is the extras on the intent that started the app, so
`LibraApplication.start(intent)` turns an extra named `LibraSomething` into the
argument pair `-LibraSomething <value>` and an extra named `LIBRA_…` into an
entry of the stand-in environment. The dash is added in code rather than typed
into the key because `am`'s own parser takes a leading `-` for a flag of its
own. Two consequences worth knowing: the shell is built by the *first*
activity rather than in `Application.onCreate`, because the intent is not
available before then — `start` is idempotent, so the first launch wins exactly
as argv does — and `System.getenv()` is not used, since nothing can set a
variable on an app process the way `SIMCTL_CHILD_` can on a simulator launch.
Seeding still requires `LibraSeedKeys` as well as the values, so a leftover
extra cannot quietly overwrite a key entered in Settings.

**Compose on iOS aborts at launch unless `Info.plist` carries
`CADisableMinimumFrameDurationOnPhone`.** Compose's `PlistSanityCheck` throws
without it: the process dies with `SIGABRT` on a blank white screen, *nothing*
is written to the device log, and the only evidence is the `.ips` crash report
in `~/Library/Logs/DiagnosticReports`, whose top Kotlin frame names the check.
The key is a ProMotion one — a Compose app without it is capped to 60Hz on a
120Hz phone. `GENERATE_INFOPLIST_FILE` cannot express it: the synthesised plist
understands a fixed set of `INFOPLIST_KEY_*` settings and silently drops the
rest. So `iosApp/project.yml` now uses XcodeGen's `info:` block, which writes a
real plist, and restates there the four settings the target used to carry —
they are lost otherwise. This is a divergence from `../project.yml`, which
`PLAN.md §0` said to mirror; the Swift app is SwiftUI and never needed the key.
The generated `iosApp/Info.plist` is gitignored, like the generated project.

**The Android launcher icon is adaptive; the iOS one is the Swift app's
unchanged.** `../Libra/Resources/Assets.xcassets` is copied verbatim into
`iosApp/Resources`, so iOS uses the same single 1024×1024 image the Swift app
does. Android cannot: the artwork is line art that runs to all four edges of
its square, and a round or squircle launcher mask clips the top-right loop and
the base stroke. `mipmap-anydpi-v26/ic_launcher.xml` therefore composes a
foreground inset to 60% of the adaptive canvas — inside the 66% safe zone —
over a white background layer, which is the colour the artwork already sits on.
`sips -z` resamples and `sips -p … --padColor FFFFFF` insets, so no tooling
beyond the OS is needed. `minSdk` is 26, so the adaptive icon covers every
supported version and the square `ic_launcher.png` fallbacks are only for a
launcher that ignores `anydpi-v26`.


**Xcode 27 replaced `Simulator.app` with `DeviceHub.app`, and moved the
developer apps.** They are no longer under
`Xcode.app/Contents/Developer/Applications/` — that directory does not exist in
Xcode 27 — but under `Xcode.app/Contents/Applications/`, alongside Instruments,
Accessibility Inspector, Create ML, FileMerge and Icon Composer.
`SimulatorKit.framework` moved to `Contents/SharedFrameworks/`. `open -a
Simulator` fails and always will; the window you want is:

```sh
open "/Applications/Xcode.app/Contents/Applications/DeviceHub.app"
```

The Xcode 26 path is still what most tooling and most instructions reach for, so
expect anything that looks for `Contents/Developer/Applications/Simulator.app`
to report the install as broken. It is not.

**Do not infer a frozen display from two identical screenshots.** A headless
booted device renders perfectly well — `simctl io <udid> enumerate` shows the
framebuffer port On with a live 1206×2622 BGRA IOSurface, and `recordVideo`
produces valid H.264. What misleads is that Compose does not redraw a static
screen and the simulator's status-bar clock is fixed, so an idle screen really
does hash identically twice, and it looks exactly like a dead display link.
Prove the path is live by changing something first:

```sh
xcrun simctl io <udid> screenshot /tmp/a.png
xcrun simctl ui <udid> appearance dark && sleep 1
xcrun simctl io <udid> screenshot /tmp/b.png
md5 -q /tmp/a.png /tmp/b.png     # differing hashes = live; set appearance back
```

This cost one session an hour and produced a wrong entry in this file, which is
why the method is written down rather than the conclusion.

**`simctl pbcopy` / `pbsync` time out while the device is still coming up.**
`NSPOSIXErrorDomain code=60` from either one means the pasteboard service is not
up yet, not that the bridge is unavailable. It starts working on its own; once
up, `pbsync host <udid>` returns in well under a second.

**The iOS 26.5 runtime on this machine is broken; iOS 27.0 is clean.** Booting
`iPhone 17 Pro` (26.5) — the device `RESUME.md` recorded as verified — failed
once outright with `EINVAL`, and once booted, launching the app crashed the
system shell (`FBSOpenApplicationServiceErrorDomain code=5`, "The system shell
probably crashed"), taking SpringBoard and MobileCal with it. Nothing in the app
is implicated: the same binary launches, self-tests and runs on
**iPhone 18 Pro, iOS 27.0**, which is where the manual pass was done. Use a
27.0 device. If 26.5 has to be used, expect to reinstall the runtime first.


## Testing

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

**Fixtures reach the test binary as generated Kotlin, not as resources.**
Every file in `LibraTests/Fixtures` is copied to
`core/src/commonTest/resources/fixtures` unchanged, and a `generateFixtures`
Gradle task turns the directory into a Kotlin source file on the `commonTest`
source set. Kotlin Multiplatform has no common way to read a resource from a
test binary: the JVM wants the classpath, and a Kotlin/Native test binary has
no bundle the test runner builds. Generating the payloads as source sidesteps
it, and the fixtures stay on disk as ordinary readable JSON rather than being
pasted into a test. Literals are chunked at 20 KB because a JVM string constant
cannot exceed 64 KB in the class file and `sec_companyfacts_AAPL.json` is
140 KB.

**`MockHttp` gained a path router.** Swift's `StubURLProtocol.stub(path:)`
matched by path, and a provider test usually drives several endpoints in one
call — Finnhub's metrics page needs `/stock/metric` *and* `/stock/profile2`.
Matching is by suffix on the path, since the base URL differs per vendor and
the query string carries credentials that must not be part of the match.

**Provider tests run with the rate limiters removed.** A suite driving eight
endpoints would otherwise wait out Tiingo's one-hour refill. Back-pressure has
its own tests against virtual time in `RateLimiterTest`; here it would only be
a delay. The helper builds a generous limiter rather than adding an
`unlimited()` preset to production code.

**`inMemoryLibraDatabase()` is expect/actual in `commonTest`.** Room's
in-memory builder exists only per platform — the native one is in `nativeMain`
and Android's requires a `Context`; there is no common overload. Actuals live
in `jvmTest` and `iosTest`. `:core` has no Android test compilation, so no
third actual is required.

**The view-model stubs are shared, not copied.** `CallLog`,
`StubMarketProvider`, `StubFundamentalsProvider` and `StubSECProvider` live in
`viewmodels/ViewModelTestSupport.kt`. (A `RecordingMacroProvider` was written
here too and never used — `BenchmarkDetailViewModelTest` needs a stub that
records the *window* it was asked for and generates a series, which is a
different job, so it declares its own `CountingMacroProvider`. The unused one
has been deleted.) Swift
redeclared a near-identical `CallLog` actor and counting provider in each of
`HydrationTests`, `WatchlistIntelligenceTests` and `RequestBudgetTests`, which
is how they drifted: one counted symbols, one counted calls, one did both.
Kotlin's package-level visibility would make three copies a redeclaration
error, so the merge was forced and the drift is gone.

**`CallLog` is a CAS loop, not an actor.** Swift's counters are `actor`s read
with `await`. The assertions are made from a non-suspending accessor after the
work is done, so `AtomicReference<List<String>>` with a compare-and-set append
is the equivalent — the same choice `InMemorySecretsStore` made in Phase 5.

**`SecurityDetailViewModel.select` now records its job.** The two Alpaca view-
model suites switch ranges and immediately assert what the chart holds; Swift
slept 400–600 ms between each. `select` assigns its spawned fetch to a
`selectJob` that `awaitLoad()` joins alongside `loadJob`, so the suites are
exact rather than timing-dependent. Eleven tests that took about six seconds of
sleeping in Swift now run in well under one.

**Swift's `lastRegularClose` helper hung off a test stub; here it is a
top-level function** in `IntradayBoundaryTest.kt`, shared by the three
intraday stubs that need it. It answers "the most recent 15:55 in New York",
which is what lets the chart suites run outside market hours — most of the
time.

**475 tests across both targets against Swift's 416.** The surplus is the nine
provider-level tests logged in Phase 6 plus the split of Swift's larger suites
into separate Kotlin classes; no Swift case was dropped. `ProviderDecodingTests`
went over as the per-provider decoding tests, `PersistenceTests` as
`SnapshotStoreTest` + `SchemaTest`, and `SyntheticDataTests` as
`SyntheticRowEvictionTest`.

**Eleven tests beyond the Swift suites.** Swift had 13 cases across
`Keychain error reporting`, `Key fingerprints` and `Secrets store`; all 13 are
ported. The additions pin things the Kotlin port newly made possible to get
wrong: the storage account names (renaming a `SecretKey` case would orphan a
credential the user already entered), the round trip through `fromRaw`, that
every key has help text, that a trailing newline in a paste does not inflate
the fingerprint length, and that `SecretsError.message` carries the
explanation rather than a code.

**Nine tests beyond the Swift suites**, each pinning something the Kotlin port
newly made possible to get wrong: that Alpaca's `adjustment=split` is actually
requested (the "no adjusted series is claimed" assertion is false without it),
that the next-page token is followed, that both Alpaca halves stay out of the
URL, that FRED omits the date window when not asked for one, that Finnhub's
date strings are dropped from the metric map, that the SEC User-Agent falls
back to the app name, that a Finnhub failure which is not a miss does not
quietly switch vendors, that sample filings carry the evictable accession
prefix, and that no mock answers to a real vendor's identity.


## Open issues

**Two Gradle deprecation warnings**, both waiting on upstream plugin releases —
see [Build and toolchain](#build-and-toolchain). Nothing else is open: the iOS
Keychain `-50` that blocked credential entry is fixed, and the cause is written
up under [Secrets](#secrets) because it is a trap worth not re-entering.


## Deliberately absent

**`expect fun appPaths()` and Compose string resources: both dropped.**
`PLAN.md §9` lists them; neither is worth doing as written, and the owner
agreed. The per-platform `openLibraDatabase` already puts the file where each
platform wants it — `NSDocumentDirectory` on iOS, `getDatabasePath` on Android
— so `appPaths()` would be an abstraction over one call site, and the secrets
stores have no path at all (Keychain and AndroidKeyStore are not files).
Extracting every English string into a resource bundle is churn whose only
payoff is localisation, which `PLAN.md §8` rules out for the port ("English
only, don't localise during the port"). Both are cheap to add later if a second
language is ever wanted.

The cleanup pass after Phase 9's first attempt deleted every declaration that
had **zero** references outside itself, in every source set including tests:
`LenientLongSerializer`, `LenientStringSerializer`, `IsoInstantSerializer` and
`DayInstantSerializer` plus the two `asDoubleOrNull` extensions (see
[Providers](#providers)); `UnportedScreen`, which Phase 8 left nothing to stand
in for; `periodicReportForms`, since `FilingSignificance` makes the same
distinction from the form type directly; `FinancialFactDao.forConcept`, never
wired because callers read through `all` and filter in Kotlin;
`RecordingMacroProvider` and `CallLog.barSymbolsRequested`;
`MockHttp.Router.stubAll` and the `fallback` reply it set, since no test ever
registered a catch-all and the 404 naming the missing stub is the more useful
failure; and 39 unused imports.

Three things look dead and are **kept** — do not re-flag them:

- `WatchlistRecord.addedAt` and `SecurityRecord.profileUpdatedAt` are unread,
  but they are Room columns. Dropping them is a schema migration, not a cleanup.
- `AnalystEstimateDTO.analystCount` is never set or read. It is part of a DTO
  shape mirroring the Swift model, and Finnhub's estimate endpoints are a tier
  gap the app reports rather than calls.
- `OSStatusCode.DUPLICATE_ITEM` (`-25299`) is in the constant table but
  `SecretsError.explain` has no case for it. That is a gap in `explain` rather
  than redundancy — `SecItemAdd` is precisely the call that returns it — and it
  is left in place for the open Keychain work above.
