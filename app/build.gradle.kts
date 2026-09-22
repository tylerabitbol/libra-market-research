plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Navigation routes are serializable types rather than format strings, so
    // a destination's arguments are checked by the compiler.
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())

    android {
        namespace = "com.tylerabitbol.libra.ui"
        compileSdk = libs.versions.androidCompileSdk.get().toInt()
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }

    // One framework carries Compose UI and :core. The iOS shell links this.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "LibraKit"
            isStatic = true
            // `dependencies.project(...)`, not `project(...)`: the latter is
            // `Project.project(String)`, and passing a Project as a dependency
            // notation is deprecated and fails in Gradle 10.
            export(dependencies.project(":core"))
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Compose's own test harness. These run on the iOS simulator
            // target rather than a desktop one: the app ships on iOS and
            // Android, and adding a JVM target to :app only to host tests
            // would mean testing a third platform nobody uses.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            api(project(":core"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.navigation.compose)
            // Charts. Vico draws both: one series per `ChartSegment` gives the
            // intraday line its overnight break, so the `Canvas` fallback
            // `PLAN.md §8` allowed for was never needed.
            implementation(libs.vico.multiplatform)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
