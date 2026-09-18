package com.lbc.breadcrumb

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lbc.breadcrumb.ui.debug.MemoryListScreen
import com.lbc.breadcrumb.ui.home.HomeScreen
import com.lbc.breadcrumb.ui.theme.BreadcrumbTheme

class MainActivity : ComponentActivity() {

    /**
     * Counts requests to open the "+" sheet -- the launcher shortcut's. A
     * count rather than a flag, so the same request arriving twice opens it
     * twice; the screen remembers the last count it answered. Kept across a
     * rotation, so that memory stays right.
     */
    private var keepRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // must run before super.onCreate() so the splash theme is swapped out
        installSplashScreen()
        super.onCreate(savedInstanceState)
        keepRequests = savedInstanceState?.getInt(KEEP_REQUESTS) ?: 0
        // Light system-bar icons whatever the phone's theme, since the ground is always ink.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // restored after a rotation: the intent was already answered
        if (savedInstanceState == null) take(intent)
        setContent {
            // Dark always, as the design canvas decides: the app is mostly a
            // frame around other people's screenshots, and a loud ground
            // competes with its own content.
            BreadcrumbTheme(darkTheme = true) {
                var debug by rememberSaveable { mutableStateOf(false) }
                // the shortcut lands on the sheet, never behind the debug list
                LaunchedEffect(keepRequests) { if (keepRequests > 0) debug = false }
                if (debug) {
                    BackHandler { debug = false }
                    MemoryListScreen()
                } else {
                    HomeScreen(
                        onOpenDebug = if (BuildConfig.DEBUG) ({ debug = true }) else null,
                        keepRequests = keepRequests,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        take(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEEP_REQUESTS, keepRequests)
    }

    private fun take(intent: Intent?) {
        if (intent?.action == ACTION_KEEP) keepRequests += 1
    }

    companion object {
        /** The launcher shortcut's action (res/xml/shortcuts.xml): open the "+" sheet. */
        const val ACTION_KEEP = "com.lbc.breadcrumb.action.KEEP"

        private const val KEEP_REQUESTS = "keep-requests"
    }
}
