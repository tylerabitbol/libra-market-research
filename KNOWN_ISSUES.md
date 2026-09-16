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
