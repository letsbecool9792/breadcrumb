package com.lbc.breadcrumb

import android.app.Application
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.net.ServerClient
import com.lbc.breadcrumb.ocr.MlKitOcrReader
import com.lbc.breadcrumb.ocr.OcrQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BreadcrumbApp : Application() {

    /**
     * Work that must outlive the Activity that started it.
     *
     * Capture surfaces finish within milliseconds of launching -- that is the
     * point of them -- so a `lifecycleScope` coroutine would be cancelled
     * before the insert ever reached Room. SupervisorJob so one failed save
     * cannot take the scope down with it.
     */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Lazy: a capture process never talks to the server, so never builds a client. */
    val server: ServerClient by lazy { ServerClient(BuildConfig.SERVER_URL) }

    override fun onCreate() {
        super.onCreate()

        // Started for every process, capture included: a share is usually a
        // cold start, and its image should be read in that same process rather
        // than whenever the app is next opened. Built inside the coroutine so
        // none of it -- not even the database builder -- runs on the main
        // thread during that cold start.
        applicationScope.launch {
            OcrQueue(
                dao = BreadcrumbDatabase.get(this@BreadcrumbApp).memoryDao(),
                store = OriginalStore(this@BreadcrumbApp),
                reader = MlKitOcrReader(),
            ).run()
        }
    }
}
