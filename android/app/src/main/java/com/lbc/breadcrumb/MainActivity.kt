package com.lbc.breadcrumb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.lbc.breadcrumb.ui.debug.MemoryListScreen
import com.lbc.breadcrumb.ui.theme.BreadcrumbTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // must run before super.onCreate() so the splash theme is swapped out
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BreadcrumbTheme {
                // Temporary debug surface; the search UI replaces it at step 4.2.
                MemoryListScreen()
            }
        }
    }
}
