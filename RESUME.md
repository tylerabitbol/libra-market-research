# Where the port stands

**Last checkpoint: Phase 8 complete; Phase 9 started — the iOS shell is wired.**
Phases 0–7 complete: 475 `:core` tests green on both JVM and the iOS
simulator. Phase 8 so far: theme, navigation, the component set, and the Settings,
SecretEntry, Watchlist, Dashboard, BenchmarkDetail, Research, Screener and
SecurityDetail screens, all compiling on both `iosSimulatorArm64` and `android`, with
16 `:app:iosSimulatorArm64Test` tests green — `BannerTest`, `ProvenanceTest` and
`PriceChartTest` in `app/src/commonTest/.../ui/` (the first run costs ~21 min,
later ones ~15-40 s).
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

## Next: finish Phase 9

Phase 9 so far: `core/.../app/AppLaunch.kt` holds the launch sequence both
shells share (self-test, key seeding, watchlist seeding, visit backdating,
attaching the database), and `app/src/iosMain/.../MainViewController.kt` uses it
— `KeychainSecretsStore`, `UserDefaultsPreferenceStore`, `openLibraDatabase()`,
`UIApplication.openURL` for `LocalUrlOpener`, argv and the process environment
for `LaunchEnvironment`, and `-LibraOpenSymbol` opening a security page. `App()`
now takes `openSymbol` and `openUrl`, and the `Scaffold` takes
`WindowInsets.safeDrawing` so the first row of a screen clears the status bar
and the notch.

Remaining, in order:

1. **The Android shell.** An `Application` subclass building `AppLaunch` and
   `AppEnvironment` once for the process, `MainActivity` reading them, the real
   `KeystoreSecretsStore` and `SharedPreferencesStore`, `openLibraDatabase(context)`,
   an `Intent(ACTION_VIEW)` for `LocalUrlOpener`, and `BuildConfig.DEBUG` for
   `LaunchEnvironment.isDebugBuild` (which needs `buildFeatures { buildConfig = true }`).
   Decide where Android's launch *arguments* come from — iOS has argv and
   Android has none, so the intent's extras are the obvious stand-in; log
   whatever you choose in `KNOWN_ISSUES.md`.
2. **The app icon.** One 1024×1024 PNG at
   `../Libra/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png`. It
   needs an `iosApp` asset catalog (`project.yml` has none yet) and Android
   mipmaps; `sips -z` resamples it without another tool.
3. **`README.md` for this branch** — how to build both, how to run the tests,
   the `JAVA_HOME` export, and that `:app` UI tests run on `iosSimulatorArm64`.
4. **A manual pass on both platforms**, per `PLAN.md §7`.

`PLAN.md §9` also lists "Compose resources for strings" and an
`expect fun appPaths()`. Neither looks worth doing as written: the per-platform
`openLibraDatabase` already puts the file where each platform wants it, so
`appPaths()` would be an abstraction over one call site, and extracting every
English string into a resource bundle is churn that `PLAN.md §8`'s "English
only, don't localise during the port" rules out the benefit of. Make the call
and write it down either way. **Decided: drop both.** The reasoning above
stands and the owner agreed; `KNOWN_ISSUES.md` should carry the entry when the
rest of the phase lands.

### What a first attempt at items 1, 2 and 4 established

An attempt at the Android shell, the icon and the manual pass was made and its
code was **thrown away** — it drifted into debugging the environment rather
than the port, and the tree was reset to this commit. The findings are kept
because they are the expensive part; none of the code below exists.

**Compose on iOS aborts at launch without a plist key.** This is the first
thing to fix on the next attempt, before anything else is judged. Compose's
`PlistSanityCheck` throws unless `Info.plist` carries
`CADisableMinimumFrameDurationOnPhone = true`; the process dies with `SIGABRT`
on a blank white screen, and *nothing* is written to the device log — the only
evidence is the `.ips` crash report in `~/Library/Logs/DiagnosticReports`, whose
top Kotlin frame names `PlistSanityCheck`. The key exists for ProMotion: a
Compose app without it is capped to 60Hz on a 120Hz phone. `project.yml` cannot
express it through `GENERATE_INFOPLIST_FILE` + `INFOPLIST_KEY_*`, which only
understand a fixed set of keys and silently drop the rest; XcodeGen's `info:`
block writes a real plist and does. Moving to `info:` means restating the four
`INFOPLIST_KEY_*` settings the target has today, or they are lost.

**The app runs, and Phase 8's screens are real.** Once past that abort, and
against sample data with no keys: all five tabs render, both mandatory banners
appear, symbol search finds AAPL (after a debounce — do not judge it on the
first frame), adding it persists through Room and survives navigation, the
security page renders, and **the 1D intraday chart draws** — the exact path
`TickItemPlacer` was written for. That is the strongest evidence so far that
Phase 8 is sound.

**Unresolved: the iOS Keychain returns -50.** `KeychainSecretsStore.diagnose()`
reports "Secure storage unavailable — Keychain error -50" in the real signed
app, so Settings shows the warning banner and **no credential can be entered on
iOS at all**. This blocks the owner's half of `PLAN.md §7`, and it is the
highest-value thing left in the phase. What is already ruled out:

- It is not the CF-constant bridging, or not only that. The `kSec…` constants
  are `CFStringRef` pointers rather than Kotlin objects, and bridging them with
  `CFBridgingRelease(CFRetain(x))` does yield the right `NSString` —
  `kSecClass` becomes `"class"` — but making that change did not clear the -50.
- It is not the choice between a Kotlin `Map` and an `NSMutableDictionary`.
  Both are accepted; neither returns a parameter error where the comparison can
  be made.
- `diagnose()` fails at the **write**, not the read: it returns on the first
  failed step, and that step is `SecItemAdd`. The next thing to look at is
  therefore what that call carries and the delete/read do not — the `NSData`
  value built by `String.toNSData()`, and the
  `kSecAttrAccessible`/`kSecAttrAccessibleAfterFirstUnlock` pair.

**A `core/src/iosTest` suite cannot verify the Keychain.** This is worth knowing
before writing one, which the attempt did before discovering it. The test bundle
has no host app and so no entitlements, and **every** Keychain call in it fails
with `-25291` (`errSecNotAvailable`) before anything else is evaluated. A test
there cannot reach the app's `-50`, cannot distinguish a malformed query from an
unavailable keychain, and will pass whether the bug is present or not. This is
the same limit `SelfTest`'s own comment describes, and it is why `SelfTest`
exists: verifying secure storage means running the signed app, via
`-LibraSelfTest`, and reading its log.

**Run the simulator with its window open.** `open -a Simulator` fails — the app
lives inside Xcode, at `"$(xcode-select -p)/Applications/Simulator.app"`. A
device booted headlessly by `simctl` still runs and still screenshots, but it
does not drive the display link, so Compose produces frames only when poked:
screens come back blank, transitions freeze half-drawn, and a screenshot can
show the previous frame. The attempt lost most of its time reading those
artefacts as app bugs. Boot the device, open that Simulator.app, confirm a
window is on screen, and only then believe a screenshot.

**There is no Android emulator on this machine.** No `emulator` binary, no
system images and no AVDs under `~/Library/Android/sdk` or `~/.android/avd`;
only `build-tools`, `platform-tools`, `platforms` and `cmdline-tools` are
installed. `:androidApp:assembleDebug` builds clean, but the Android shell has
never been run. Running it means `sdkmanager` downloading a system image into
the owner's SDK — ask first.

**The icon needs an adaptive icon on Android.** The 1024 source is line art that
runs to all four edges of its square, so a round or squircle launcher mask clips
the top-right loop and the base stroke. A plain square mipmap is not enough: the
foreground has to be inset into the 66% safe zone over a background layer.
`sips -z` to resample and `sips -p … --padColor FFFFFF` to pad produces a
correct foreground with no other tooling, and the artwork sits on white anyway.
`minSdk` is 26, so `mipmap-anydpi-v26` covers every supported version and the
legacy square PNGs are only a fallback.

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
`BenchmarkDetail`; `Research`; `Screener`; `SecurityDetail`. No
`UnportedScreen` placeholders remain.

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
publishes a stable 1.12.0 — see `KNOWN_ISSUES.md`.

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
