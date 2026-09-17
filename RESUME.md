# Where the port stands

**Last checkpoint: Phase 9's code is complete on both shells; only the owner's
manual pass is left.**
Phases 0–7 complete: 475 `:core` tests green on both JVM and the iOS
simulator. Phase 8: theme, navigation, the component set, and the Settings,
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

Phase 9 is wired on both platforms. `core/.../app/AppLaunch.kt` holds the launch
sequence both shells share (self-test, key seeding, watchlist seeding, visit
backdating, attaching the database); `app/src/iosMain/.../MainViewController.kt`
and `androidApp/.../LibraApplication.kt` supply only what is per-platform —
where the launch arguments come from, which secrets store to build, which
database file to open, and how a link is opened. `App()` takes `openSymbol` and
`openUrl`, and the `Scaffold` takes `WindowInsets.safeDrawing`. Both shells now
carry the launcher icon, and `README.md` covers building, testing and running
both. Every deviation is in `KNOWN_ISSUES.md` under "Phase 9".

Verified on the iPhone 17 Pro simulator: the app launches, and
`-LibraSelfTest` logs **`keychain=PASS round-trip succeeded`** — secure storage
works, so credentials can be entered on iOS.

Remaining:

1. **The manual pass on both platforms**, per `PLAN.md §7`. This is the last
   item in the phase and it is the owner's: it needs real API keys, which are
   entered in Settings → Data Sources and never handled here.
   - iOS is ready to run now. `README.md` has the exact commands; the short
     version is `cd iosApp && xcodegen generate`, then open `Libra.xcodeproj`
     and press Run.
   - Android **has never been run.** It builds and installs, but there is no
     emulator on this machine — no `emulator` binary, no system images, no AVDs
     under `~/Library/Android/sdk` or `~/.android/avd`; only `build-tools`,
     `platform-tools`, `platforms` and `cmdline-tools`. Running it means either
     a physical device over `adb`, or `sdkmanager` downloading a system image
     into the owner's SDK — **ask before downloading one.**

### What the iOS run established

- **Compose on iOS aborts at launch without a plist key**, and this is now
  fixed in `project.yml`. Worth knowing if it ever recurs: Compose's
  `PlistSanityCheck` throws unless `Info.plist` carries
  `CADisableMinimumFrameDurationOnPhone`, the process dies with `SIGABRT` on a
  blank white screen, and *nothing* is written to the device log — the only
  evidence is the `.ips` crash report in `~/Library/Logs/DiagnosticReports`.
- **The Keychain -50 is fixed**, and the cause was not where it looked. The
  `kSec…` names are `CFStringRef` *pointers*, so bridging a Kotlin `Map`
  containing them produced a dictionary whose keys the Security framework did
  not recognise, and *every* call failed with `errSecParam`; only `SecItemAdd`
  reported it, because the read and the delete treat any non-success as "not
  there". `withCFDictionary` now builds a real `CFMutableDictionary` and no
  bridging happens at all. A `core/src/iosTest` suite still cannot verify this —
  the test bundle has no host app and so no entitlements, and every Keychain
  call in one fails with `-25291` first — which is exactly why `SelfTest` exists:
  run the signed app with `-LibraSelfTest` and read the log.
- **Phase 8's screens are real.** All five tabs render, both mandatory banners
  appear, symbol search finds AAPL (after a debounce — do not judge it on the
  first frame), adding it persists through Room and survives navigation, and the
  1D intraday chart draws, which is the exact path `TickItemPlacer` was written
  for.
- **Run the simulator with its window open.** A device booted headlessly by
  `simctl` still runs and still screenshots, but it does not drive the display
  link, so Compose produces frames only when poked and a screenshot can show a
  stale frame. Note that `open -a Simulator` fails, and on this machine's Xcode
  27 there is no `Simulator.app` under `Contents/Developer/Applications` either —
  launching the simulator UI means opening the project in Xcode and pressing Run.

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
