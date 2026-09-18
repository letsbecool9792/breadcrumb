package com.lbc.breadcrumb

import android.app.Application
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.net.ServerClient
import com.lbc.breadcrumb.ocr.MlKitOcrReader
import com.lbc.breadcrumb.ocr.OcrQueue
import com.lbc.breadcrumb.ocr.PdfReader
import com.lbc.breadcrumb.sync.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * Memories the upload queue must leave for now: saved, but with their
     * capture sheet still open, where a note may yet be written. Sending them
     * first would mean sending them twice. In memory only -- if the process
     * dies, so did the sheet, and there is nothing left to wait for.
     */
    val uploadHolds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override fun onCreate() {
        super.onCreate()

        // Started for every process, capture included: a share is usually a
        // cold start, and its image should be read in that same process rather
        // than whenever the app is next opened. Built inside the coroutine so
        // none of it -- not even the database builder -- runs on the main
        // thread during that cold start.
        applicationScope.launch {
            // one recognizer, for pictures and for PDF pages with no text layer
            val ocr = MlKitOcrReader()
            OcrQueue(
                dao = BreadcrumbDatabase.get(this@BreadcrumbApp).memoryDao(),
                store = OriginalStore(this@BreadcrumbApp),
                reader = ocr,
                onRead = { UploadWorker.schedule(this@BreadcrumbApp) },
                documents = PdfReader(ocr),
            ).run()
        }
    }
}
