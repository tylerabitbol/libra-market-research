package com.tylerabitbol.libra.app

import com.tylerabitbol.libra.networking.HTTPClient
import com.tylerabitbol.libra.persistence.LibraDatabase
import com.tylerabitbol.libra.services.secrets.SecretsStore
import com.tylerabitbol.libra.support.PreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Everything both shells do at launch, written once.
 *
 * Swift spreads this across `LibraApp.init` and `RootView.task`; the two
 * Compose shells would otherwise each carry their own copy, and the copies
 * would drift. What stays in a shell is only what is genuinely per-platform:
 * where the arguments come from, which secrets store to build, and which
 * database file to open.
 *
 * Nothing here decides *whether* a developer option applies — [DeveloperOptions]
 * and [SelfTest] already gate themselves on the launch arguments and the build
 * configuration, so a release build passing its real [LaunchEnvironment] runs
 * none of it.
 */
class AppLaunch(
    private val launch: LaunchEnvironment = LaunchEnvironment.release,
    /** Where the detached work — connection tests, fixture capture — runs. */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    val developerOptions = DeveloperOptions(launch)
    private val selfTest = SelfTest(launch)

    /**
     * The symbol to open on, when a debug build was launched with
     * `-LibraOpenSymbol AAPL`. Null in every other case.
     */
    val openSymbol: String? get() = developerOptions.debugSymbol

    /**
     * Builds the environment and runs the checks that must happen before any
     * screen appears.
     *
     * The self-test runs against the *real* secrets store deliberately: it
     * exists to catch the entitlement failure that makes the Keychain return
     * nothing, and an in-memory stand-in cannot fail that way.
     */
    fun start(
        secrets: SecretsStore,
        preferences: PreferenceStore,
        httpClient: HTTPClient = HTTPClient(),
    ): AppEnvironment {
        if (selfTest.isRequested) {
            selfTest.run(secrets)
            scope.launch { selfTest.runConnectionTests(secrets) }
        }
        if (selfTest.isCaptureRequested) {
            scope.launch { selfTest.captureFixtures(secrets) }
        }
        // Debug-only, and only on an explicit launch argument. Before the
        // environment is built, so a seeded key is already in place when the
        // registry is first assembled and the app does not start on sample
        // data it would immediately replace.
        developerOptions.seedSecretsFromEnvironment(secrets)

        return AppEnvironment(
            secrets = secrets,
            httpClient = httpClient,
            preferences = preferences,
        )
    }

    /**
     * Attaches the database and applies the options that need it.
     *
     * Suspending, and called from the shell's own scope rather than from
     * [start]: the seeds write rows, and a write on the main thread at launch
     * is exactly the kind of thing that makes a cold start stutter.
     */
    suspend fun attach(environment: AppEnvironment, database: LibraDatabase) {
        developerOptions.seedWatchlist(database)
        developerOptions.backdateVisits(database)
        environment.attach(database)
    }
}
