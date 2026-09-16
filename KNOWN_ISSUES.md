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
