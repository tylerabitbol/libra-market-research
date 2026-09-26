package com.tylerabitbol.libra

import android.app.Application
import android.content.Intent
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.app.AppLaunch
import com.tylerabitbol.libra.app.LaunchEnvironment
import com.tylerabitbol.libra.persistence.openLibraDatabase
import com.tylerabitbol.libra.services.secrets.KeystoreSecretsStore
import com.tylerabitbol.libra.support.SharedPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Android counterpart of `MainViewController.kt`'s `IosShell`: the launch
 * sequence, run once for the process.
 *
 * It lives on the [Application] rather than the activity because an activity is
 * recreated on every rotation and every configuration change, and a second
 * [AppEnvironment] would mean a second database connection and a second
 * provider registry.
 *
 * It is not built in [onCreate], though. iOS reads its launch arguments from
 * argv, which exists before any UI does; an Android process has no argv, and
 * the nearest equivalent — the extras on the intent that started the app —
 * only arrives with the first activity. So [start] is called by [MainActivity]
 * and is idempotent: the first launch wins, exactly as argv does, and a later
 * activity with different extras changes nothing.
 */
class LibraApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var launch: AppLaunch? = null

    /** Valid only after [start]; [MainActivity] calls that before reading it. */
    lateinit var environment: AppEnvironment
        private set

    val openSymbol: String? get() = launch?.openSymbol

    fun start(intent: Intent?) {
        if (launch != null) return

        val launchEnvironment = LaunchEnvironment(
            arguments = launchArguments(intent),
            environment = launchVariables(intent),
            // `BuildConfig.DEBUG` is the Android equivalent of Swift's `#if
            // DEBUG` and of the iOS shell's `Platform.isDebugBinary`: every
            // developer option is gated on it, so nothing below can be reached
            // from a release build however it is launched.
            isDebugBuild = BuildConfig.DEBUG,
        )
        val appLaunch = AppLaunch(launchEnvironment)
        launch = appLaunch

        environment = appLaunch.start(
            secrets = KeystoreSecretsStore(this),
            preferences = SharedPreferencesStore(this),
        )

        // Off the critical path, as on iOS: the window is up before the
        // database is, and every screen renders an empty state until the
        // store arrives.
        scope.launch { appLaunch.attach(environment, openLibraDatabase(this@LibraApplication)) }
    }

    /**
     * Intent extras named `LibraSomething` become the launch arguments
     * `-LibraSomething <value>`, which is the shape `DeveloperOptions` reads
     * on iOS:
     *
     * ```
     * adb shell am start -n com.tylerabitbol.libra/.MainActivity \
     *   -e LibraOpenSymbol AAPL -e LibraSeedWatchlist 1
     * ```
     *
     * The leading dash is added here rather than typed into the key because
     * `am`'s own argument parser takes a token beginning with `-` for a flag of
     * its own. A flag that takes no value — `-LibraSeedKeys` — is given one
     * anyway and ignores it; presence is all `DeveloperOptions` tests.
     */
    private fun launchArguments(intent: Intent?): List<String> {
        val extras = intent?.extras ?: return emptyList()
        return extras.keySet()
            .filter { it.startsWith(ARGUMENT_PREFIX) }
            .sorted()
            .flatMap { listOf("-$it", extras.getString(it).orEmpty()) }
    }

    /**
     * Extras named `LIBRA_…` stand in for the process environment.
     *
     * `System.getenv()` is the honest translation but a useless one: nothing
     * can set a variable on an app process the way `SIMCTL_CHILD_` sets one on
     * a simulator launch. The extras are the only channel there is, and
     * seeding still requires `LibraSeedKeys` as well, so a leftover extra
     * cannot quietly overwrite a key entered in Settings.
     */
    private fun launchVariables(intent: Intent?): Map<String, String> {
        val extras = intent?.extras ?: return emptyMap()
        return extras.keySet()
            .filter { it.startsWith(VARIABLE_PREFIX) }
            .associateWith { extras.getString(it).orEmpty() }
    }

    private companion object {
        const val ARGUMENT_PREFIX = "Libra"
        const val VARIABLE_PREFIX = "LIBRA_"
    }
}
