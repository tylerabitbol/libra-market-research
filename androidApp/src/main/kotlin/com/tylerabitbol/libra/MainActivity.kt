package com.tylerabitbol.libra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.tylerabitbol.libra.app.AppEnvironment
import com.tylerabitbol.libra.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Phase 9 replaces this with the AndroidKeyStore-backed store, the
        // on-disk database and the launch-argument wiring; one instance for
        // the process either way.
        setContent { App(AppEnvironment()) }
    }
}
