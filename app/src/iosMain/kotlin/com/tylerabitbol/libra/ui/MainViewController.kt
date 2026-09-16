package com.tylerabitbol.libra.ui

import androidx.compose.ui.window.ComposeUIViewController
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.app.AppLaunch
import com.tylerabitbol.libra.app.LaunchEnvironment
import com.tylerabitbol.libra.persistence.openLibraDatabase
import com.tylerabitbol.libra.services.secrets.KeychainSecretsStore
import com.tylerabitbol.libra.support.UserDefaultsPreferenceStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * The iOS entry point: one Compose view controller, and the wiring Swift's
 * `LibraApp` did in its initialiser.
 *
 * Built once for the process. `LibraApp.swift` makes one `ComposeRoot`, but a
 * `UIViewControllerRepresentable` may be asked for its controller again after a
 * scene change, and a second environment would mean a second database
 * connection and a second registry.
 */
private val shell by lazy { IosShell() }

fun MainViewController(): UIViewController = ComposeUIViewController {
    App(
        environment = shell.environment,
        openSymbol = shell.openSymbol,
        openUrl = shell::openUrl,
    )
}

@OptIn(ExperimentalNativeApi::class)
private class IosShell {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val launch = AppLaunch(
        LaunchEnvironment(
            arguments = NSProcessInfo.processInfo.arguments.map { it.toString() },
            environment = NSProcessInfo.processInfo.environment
                .entries
                .associate { it.key.toString() to it.value.toString() },
            // `Platform.isDebugBinary` rather than a build-config constant:
            // Kotlin/Native already knows, and a constant would have to be
            // threaded through the Xcode configuration to say the same thing.
            isDebugBuild = Platform.isDebugBinary,
        ),
    )

    val openSymbol: String? = launch.openSymbol

    val environment: AppEnvironment = launch.start(
        secrets = KeychainSecretsStore(),
        preferences = UserDefaultsPreferenceStore(),
    )

    init {
        // Off the critical path: the window is up before the database is, and
        // every screen already renders an empty state while the store is null.
        scope.launch { launch.attach(environment, openLibraDatabase()) }
    }

    /** SwiftUI's `Link`, which Compose has no multiplatform equivalent for. */
    fun openUrl(url: String) {
        val target = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(target, emptyMap<Any?, Any?>(), null)
    }
}
