# Libra → Kotlin Multiplatform + Compose

Planning only. Nothing here is implemented.

## What exists today

| Layer | Files | LOC | Portability |
|---|---:|---:|---|
| Calculations | 11 | 3,371 | ~95% — pure value types, no Apple APIs |
| Services (providers, parsers) | 13 | 2,613 | ~85% — HTTP + JSON/XBRL parsing |
| Models | 9 | 1,583 | ~50% — `@Model` classes are SwiftData |
| Persistence (SnapshotStore) | 2 | 841 | ~10% — `@ModelActor`, rewrite |
| Networking | 4 | 405 | ~80% — one `URLSession` seam |
| ViewModels | 6 | 2,293 | ~70% — `@Observable` → `StateFlow` |
| Views | 16 | 3,227 | ~60% — SwiftUI → Compose, charts excepted |
| App + Support | 8 | 887 | ~40% — entry point, Keychain, formatting |
| Tests | 33 | 6,950 | ~90% — mechanical |

**Verdict: viable.** Roughly 55% of the app (calculations, services, networking,
models-as-data) is arithmetic and parsing that moves across almost verbatim.
That is also the part worth the most — it is where the design decisions live.

## Two options

**A. Shared core, native UI (recommended.)** Move Calculations + Models +
Services + Networking into `commonMain`. Keep SwiftUI on iOS, write Compose only
for Android. Nothing about the iOS app degrades; Android gets the same numbers.

**B. Full Compose Multiplatform.** One UI for both. Cheaper to maintain, but
iOS stops feeling like an iOS app — see below.

## What is lost in translation

1. **Swift Charts.** No equivalent. `PriceChartView` (189 LOC) becomes ~500 LOC
   of Compose `Canvas`, or a third-party lib. The intraday/daily segmented axis
   and its accessibility labels are hand-rolled either way. Biggest single cost.
2. **SwiftData.** `@Model` + `@ModelActor` → Room KMP (or SQLDelight). The
   value-type/`Sendable` discipline already in `SnapshotStore` maps well to DAOs
   returning `Flow`, but it is a rewrite, not a translation.
3. **Keychain.** `SecretsStore` (285 LOC) splits into `expect`/`actual`:
   Keychain on iOS, Keystore/EncryptedSharedPreferences on Android. The
   entitlement comment in `project.yml` stays true on the iOS side.
4. **Decimal arithmetic.** Swift `Decimal` → `BigDecimal`. Rounding and
   percentage formatting must be pinned with golden tests, or figures drift in
   the last digit — unacceptable for an app that shows its arithmetic.
5. **Formatting + dates.** `NumberFormatter`/`DateFormatter` have no Kotlin
   equal on iOS. `kotlinx-datetime` plus hand-written formatters, per locale.
6. **Accessibility.** 15 files carry accessibility work. Compose `semantics`
   covers labels and values; VoiceOver rotors, custom actions and Dynamic Type
   do not map one-to-one and need re-auditing on both platforms.
7. **Concurrency model.** Swift 6 strict concurrency, actors and `Sendable`
   checking → coroutines, `Mutex`, `Dispatchers`. Kotlin will not catch at
   compile time what Swift catches now; some invariants become conventions.
8. **Option B only:** scroll physics, back-swipe, system fonts, share sheet,
   previews and `SelfTest`/`DeveloperOptions` tooling. Compose on iOS is good
   and is not indistinguishable.

## Estimate (my working time, not calendar)

Option A — shared core, SwiftUI kept:

| Phase | Est. |
|---|---|
| Gradle/KMP scaffold, module layout | 2–3 h |
| Calculations → commonMain + tests | 8–10 h |
| Models + Room KMP persistence | 6–8 h |
| Networking (Ktor) + providers | 6–8 h |
| Keychain/Keystore expect-actual | 2 h |
| Rewire iOS app onto shared core | 4–6 h |
| Android Compose UI (parity with 16 views) | 12–16 h |
| **Total** | **40–53 h** |

Option B adds the iOS Compose UI, charts, and accessibility re-audit:
**+25–35 h**, call it **65–90 h** total.

Recommendation: do A. Ship the core shared and the Android UI in Compose; revisit
B only if maintaining two UIs actually hurts.
