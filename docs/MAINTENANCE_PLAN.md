# Libra KMP — cleanup, open issues, UI parity

> **This is the completed cleanup and UI-parity plan, kept for reference. It is
> not the current plan.** Its five stages were worked and committed; what they
> left open is recorded in [`../KNOWN_ISSUES.md`](../KNOWN_ISSUES.md). The plan
> being worked now is [`../PLAN.md`](../PLAN.md).

The plan being worked now. Written to be executed by another agent without
re-deriving decisions, the same way `docs/PORT_PLAN.md` was.

The port itself is finished: Phases 0–9 complete, 475 `:core` tests green on
JVM and the iOS simulator, 17 `:app` UI tests green, and the manual pass run on
both platforms against live keys. `RESUME.md` records that. What is left is the
residue — a workspace that accumulated generated files, six genuinely open
issues in `KNOWN_ISSUES.md`, and a UI that works but navigates worse than the
SwiftUI app it came from.

Decisions already made by the owner (do not re-litigate):

- **Fidelity first, flow second.** Close the places the Compose port visibly
  diverged from SwiftUI, then improve navigation inside that restored shape.
  No redesign; it still reads as Libra — dense, text-first, no hero graphics.
- **Open issues only.** `KNOWN_ISSUES.md` is mostly recorded *decisions*.
  Those stay. Stage 2 lists the six entries that are actually work.
- **Cleanup reaches the repo root**, not just `Translation/`. The Swift app on
  `main` is not retired; it stays the read-only reference.
- **No new features.** `docs/PORT_PLAN.md §0.5` still holds.

---

## 0. Rules for the implementer

1. **`docs/PORT_PLAN.md` is history.** It is the completed port plan, kept
   because `KNOWN_ISSUES.md` cites its section numbers as `PORT_PLAN.md §N`.
   This file is the plan. Never work from that one.
2. **A decision is not a defect.** Entries in `KNOWN_ISSUES.md` that explain
   why something differs from Swift stay as they are. Only [Open
   issues](KNOWN_ISSUES.md#open-issues) and the items named in Stage 2 are work.
3. The porting rules still apply: never format a user-visible number with
   `toString()`, never `!!`, never `runBlocking` in production code, and port
   the test alongside the change.
4. Query an artifact's `maven-metadata.xml` before writing a version into
   `gradle/libs.versions.toml`. Do not guess.
5. `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
   before invoking Gradle.
6. Commit per stage. No co-author trailers.
7. Log every new deviation in `KNOWN_ISSUES.md`, in the section for the area it
   constrains — the table at the top of that file maps directory to section.

---

## Stage 1 — Workspace and build (3 h)

**The Room schema is committed twice, one copy under a directory named
`$projectDir`.** `core/build.gradle.kts:118` reads
`schemaDirectory("${'$'}projectDir/schemas")`. The `${'$'}` escape means Room
receives the literal string `$projectDir/schemas`, so it created
`core/$projectDir/schemas/…/1.json` — and both that and `core/schemas/…/1.json`
are tracked. Fix the expression
(`schemaDirectory(layout.projectDirectory.dir("schemas").asFile.path)`),
`git rm -r 'core/$projectDir'`, rebuild, and confirm the export now lands in
`core/schemas`.

**Try the configuration cache.** `gradle.properties` carries
`org.gradle.configuration-cache=false` with nothing saying why. AGP 9,
KSP 2.3.12 and the Compose plugin all claim support. Turn it on; if a task fails
to serialise, put it back with the failing task named in a comment. A bare
`false` teaches the next person nothing.

**Two stale commands in `README.md`**, both contradicted by
`KNOWN_ISSUES.md` → Platform shells: `open "$(xcode-select -p)/Applications/Simulator.app"`
cannot work on Xcode 27, which moved the developer apps to
`Xcode.app/Contents/Applications/` and replaced Simulator with `DeviceHub.app`;
and the `xcodebuild` example names `iPhone 17 Pro`, whose 26.5 runtime is the
broken one. Point both at what the manual pass actually used.

**Delete the four `.DS_Store` files** — root, `Libra/`, `Translation/`,
`Translation/iosApp/`. All ignored, none tracked.

**At the repo root**, where `git status` currently shows three untracked
directories:

- `Claude outputs/` (9.3 MB) and `brag-output/` (7.0 MB) are untracked *and*
  unignored, so a `git add -A` on `main` commits 16 MB of generated images.
  Decide per directory — ignore or delete — and write the choice into
  `.gitignore` beside the `.claude/` entry, which already explains that
  reasoning. `docs/` (6.1 MB) is the README's screenshots; keep it.
- `Translation/` shows as untracked because it is the `kmp-translation`
  worktree. Ignore it for the same reason `.claude/` is ignored.
- Two stale Claude worktrees sit at `main`'s tip:
  `.claude/worktrees/master-plan-known-issues-ed4a90` and
  `.claude/worktrees/mock-libra-website-63d82d`. `git worktree remove` each once
  its branch has landed or been abandoned, then `git worktree prune`.
- Root `README.md` and `ROADMAP.md` describe the Swift app as the product. One
  line in each: the Kotlin port lives on `kmp-translation` under `Translation/`,
  and `main` is the reference.

**Accept:** `./gradlew :core:jvmTest` green; one schema directory, tracked once;
`git status --short` clean at both roots; a fresh clone of `main` does not pull
down the generated images.

---

## Stage 2 — The open issues (4 h)

Six entries. Everything else in `KNOWN_ISSUES.md` is a decision.

**2.1 `SecretsError.explain` has no case for `DUPLICATE_ITEM` (-25299)**, which
is exactly what `SecItemAdd` returns. Add the case and its test; the constant
table and `explain` then agree.

**2.2 `KeystoreSecretsStore` has no automated test.** It needs a real
`AndroidKeyStore`, and `:core` has no Android instrumentation source set — it is
compile-verified by `:androidApp:assembleDebug` and nothing more. Add an
`androidInstrumentedTest` covering set → get → delete, that two writes of the
same key produce different ciphertext, and the drop-the-orphaned-record path on
`GeneralSecurityException`. If AGP 9 will not give a KMP library an
instrumentation source set, put the test in `:androidApp` and say so in
`KNOWN_ISSUES.md`.

**2.3 `Format.currency` renders non-USD as `EUR 1,234.50`** and nothing covers
it. Add the test that pins today's behaviour. Do not change the rendering — no
caller passes a non-USD code, and changing it quietly is how a figure moves.

**2.4 `-LibraOpenSymbol` arrives before hydration.** The deep link goes straight
to the security page, so "What changed" renders from whatever the database holds
at that instant: on a cold start, nothing, and the section reads "Nothing
unusual in this window" while the same page reached through the Watchlist says
"2 changes since …". Make the deep link await the hydration the Watchlist path
does, or show the section's loading state rather than its empty state.
Debug-only, so keep the fix within `DeveloperOptions`' reach.

**2.5 and 2.6 — the two Gradle deprecation warnings.** Re-query
`maven-metadata.xml` for `org.jetbrains.compose.material3:material3`: the 1.12.0
line ended at `alpha03` when it was last checked on 2026-09-21. If a stable
1.12.0 now exists, name the three coordinates explicitly in
`app/build.gradle.kts` and drop the `compose.*` accessors; if not, re-date the
note and leave it. The "incompatible with Gradle 10" warning comes from the
plugins rather than our scripts — re-check it and move on.

**Accept:** 475+ `:core` tests green on JVM and `iosSimulatorArm64`; the new
secrets test green on a device or emulator; `KNOWN_ISSUES.md` → Open issues
reduced to whatever genuinely remains.

---

## Stage 3 — UI fidelity (8 h)

The Swift app on `main` is the reference and `docs/PORT_PLAN.md §3`'s mapping
table still governs. The screenshots in `../docs/*.png` show the target: large
titles, inset-grouped cards, chevron disclosure, signed figures in green and
red, gray source labels under each name.

**3.1 A top app bar on the three pushed routes only** — `SecurityDetailRoute`,
`BenchmarkDetailRoute`, `SecretEntryRoute`. None of them has any back control
today: the shell has no top bar, and Compose on iOS has no edge-swipe, so a user
who opens a security can only tap a tab — which resets that tab. Give each a
Material `TopAppBar` with a back chevron and the title Swift's
`.navigationTitle` sets (the symbol, the benchmark's display name, the
provider's display name). Two things to watch: put the bar in the *screen*, not
in the shell's `Scaffold`, or all five tab roots grow a second title above the
header rows they already draw; and draw the chevron as an `ImageVector` in
`ui/Icons.kt` beside the five tab glyphs, because material-icons is not
multiplatform past Compose 1.7.3.

**3.2 The toolbar menus regain their structure.** `DropdownMenu` has no
`Section` and no `Label(systemImage: "checkmark")`, which is why the kind filter
draws plain-text category headers and marks the selection with a leading `✓ `.
Material 3 does have `HorizontalDivider` inside a menu and
`DropdownMenuItem(leadingIcon = …)`. Use both, so the sort menus on Research,
Screener and Security Detail read as Swift's do. Keep the text fallback only
where an icon would throw the labels out of alignment.

**3.3 Swipe-to-delete returns** to the rows Swift gives it — the screener's
rules and the saved screens. `SwipeToDismissBox` with per-row dismiss state,
*and* keep the visible Remove button: the reason it was added stands, since a
swipe that nothing announces is not discoverable. Swift has both.

**3.4 Check the sheets.** The date picker stays a `DatePickerDialog` —
`SelectableDates` enforces the same `...Date.now` bound Swift does. Confirm
`AddSymbolSheet` presents as a `ModalBottomSheet` and carries the cancel
affordance Swift gets from its `.cancellationAction` toolbar item.

**3.5 Two things stay as they are.** The decorative card glyphs stay dropped,
and dynamic colour stays off. Both are recorded decisions under
`KNOWN_ISSUES.md` → UI.

**Accept:** `./gradlew :app:iosSimulatorArm64Test` green — budget the
~21-minute first run — and, the part no test covers, the app run on both
platforms: open a security from the Watchlist and come back using the bar.

---

## Stage 4 — Flow between sections (4 h)

**4.1 Per-tab back stacks.** `Navigation.kt` declares one flat `NavHost`: five
tab routes and three pushed routes as siblings, with `switchTo` doing
`popUpTo(startDestination){saveState}` + `restoreState`. The file's own comment
promises Swift's behaviour — "opening a security from the Watchlist, glancing at
the Dashboard and coming back returns to the security" — which a flat graph only
approximates. Nest each section in its own `navigation<T>` graph holding the
routes reachable from it, and have `switchTo` pop to the graph rather than to
the app's start destination. `SecurityDetailRoute` is reachable from Watchlist,
Research and Screener, so it is declared in all three: the route type is the
same, the graph it sits in is what differs.

**4.2 Prove it.** The `:app` UI tests read semantics, and this is a navigation
property, so it needs its own test: open a security from the Watchlist, switch
to Dashboard, switch back, assert the security is still on screen. Same for the
Screener.

**4.3 Re-read the deep link afterwards.** `LaunchedEffect(openSymbol)` pushes
onto whatever is current, and its comment says it is pushed "rather than made
the start destination, so the back gesture still lands on the Dashboard".
Nesting changes what current means.

Deliberately not in this stage: cross-screen links between sections, and a
global symbol search. Both are new surfaces.

**Accept:** the two navigation tests green, and the same trip made by hand on
iOS, where there is no system back gesture to paper over a mistake.

---

## Stage 5 — Verification (3 h)

The one line of `docs/PORT_PLAN.md §7` never met. Android has run only on
`medium_phone`, API 36. API 26 is the manifest floor and the build asserts it,
but nobody has watched the app run there, and no physical device has been used
at all.

- Create an API 26 emulator, install `:androidApp:assembleDebug`, and walk it:
  Dashboard, Watchlist add and open, Security Detail across all seven ranges
  including 1D and 5D, Research, Screener, Benchmark Detail, Settings. API 26 is
  where an adaptive-icon or Keystore assumption breaks if one is going to.
- Run the same walk on a physical Android device.
- iOS on **iPhone 18 Pro, iOS 27.0**. The 26.5 runtime on this machine is
  broken — `KNOWN_ISSUES.md` → Platform shells. Do not spend time on it.
- `-LibraSelfTest` on both: six lines, all PASS.
- Look at the charts rather than testing them. A year of sessions must fill the
  width and carry four axis labels; 5D must draw five separate sessions. Both
  failure modes drew a plausible line and passed every test.

**Accept:** the walk completed on API 26, on a physical device, and on iOS 27.0;
`KNOWN_ISSUES.md` → Open issues updated to say what is actually left.

---

## Things that will go wrong

| Symptom | Cause | Fix |
|---|---|---|
| `core/$projectDir` survives the Gradle fix | it is tracked, not generated-and-ignored | `git rm -r` it; a rebuild will not remove a committed file |
| Configuration cache fails on a KSP or Compose task | a plugin reading project state at execution time | revert to `false`, name the task in a comment |
| Tab roots grow a second title | the `TopAppBar` went into the shell's `Scaffold` | per-screen only; the shell keeps `bottomBar` alone |
| `Unresolved reference 'icons'` for the back chevron | material-icons is not multiplatform past Compose 1.7.3 | draw it in `ui/Icons.kt` beside the tab glyphs |
| Content slides under the status bar | `Scaffold(contentWindowInsets = safeDrawing)` already pads; a screen-level bar double-counts it | let the screen's bar consume `WindowInsets.statusBars` |
| Nested graphs break tab selection | selection reads `hierarchy`, which now includes the graph | match on the graph's route, not the start destination's |
| `:app` UI tests take 21 minutes again | first build of the test binary after a clean | expected; budget it once per stage, not per run |
| API 26 emulator refuses the install | an adaptive-icon or `minSdk` assumption | this is the point of Stage 5 — fix it and record it |
| A chart looks right and is wrong | the UI tests read semantics computed from the data, not the drawing | look at it; this has happened twice |

---

## Definition of done

- `./gradlew :core:jvmTest :core:iosSimulatorArm64Test :app:iosSimulatorArm64Test`
  all green.
- One Room schema directory, tracked once. `git status --short` clean at both
  roots, and `git add -A` on `main` cannot sweep up the generated images.
- Every pushed screen has a back control that works on iOS without a gesture.
- A security opened from the Watchlist survives a trip to the Dashboard, with a
  test that says so.
- The `§7` walk completed on API 26, on a physical Android device, and on
  iOS 27.0.
- `KNOWN_ISSUES.md` → Open issues names only what is genuinely still open.

## Out of scope

Everything `docs/PORT_PLAN.md §8` ruled out, and these, each a recorded decision
rather than an omission:

- The three dead-looking-but-kept declarations, and the schema migration that
  would drop the unread Room columns.
- `appPaths()` and Compose string resources. Dropped by the owner; cheap to add
  if a second language is ever wanted.
- The Swift→JSON parity fixture step (`docs/PORT_PLAN.md §5`). Still valid if a
  ported figure is ever doubted.
- Retiring `Libra/` and `LibraTests/` on `main`.
- The VoiceOver percentage bug in Swift's `PriceChartView` — fixed in Kotlin on
  purpose, left in Swift, which is read-only.
- Cross-screen links and global symbol search (Stage 4).

## Estimate

| Stage | h |
|---|---:|
| 1 Workspace and build | 3 |
| 2 Open issues | 4 |
| 3 UI fidelity | 8 |
| 4 Flow between sections | 4 |
| 5 Verification | 3 |
| **Total** | **≈ 22** |

Working time for the implementing agent, not calendar time. The uncertainties
are the Android instrumentation source set in Stage 2 and the nested navigation
graphs in Stage 4; both have a stated fallback above.
