package com.tylerabitbol.libra.ui

import androidx.compose.ui.window.ComposeUIViewController
import com.tylerabitbol.libra.app.AppEnvironment
import platform.UIKit.UIViewController

/**
 * The iOS entry point.
 *
 * Phase 9 owns the rest of the shell — the real secrets store, the database
 * path, the developer-option launch arguments. For now it builds an
 * environment the way Swift's `LibraApp` does, one instance for the process.
 */
private val environment = AppEnvironment()

fun MainViewController(): UIViewController = ComposeUIViewController { App(environment) }
