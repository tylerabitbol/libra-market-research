package com.tylerabitbol.libra

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import co.touchlab.kermit.Logger
import com.tylerabitbol.libra.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The shell is the application's, not the activity's, so a rotation
        // re-enters this method against the same environment. `start` is
        // idempotent and takes its launch arguments from the first intent.
        val shell = application as LibraApplication
        shell.start(intent)

        setContent {
            App(
                environment = shell.environment,
                openSymbol = shell.openSymbol,
                openUrl = ::openUrl,
            )
        }
    }

    /** SwiftUI's `Link`; `UIApplication.openURL` on the other shell. */
    private fun openUrl(url: String) {
        val target: Uri = Uri.parse(url)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, target))
        } catch (error: ActivityNotFoundException) {
            // A device with no browser. Swift's `Link` fails the same way, and
            // a source link failing to open is not worth a crash.
            Logger.w(error) { "Nothing on this device opens $url" }
        }
    }
}
