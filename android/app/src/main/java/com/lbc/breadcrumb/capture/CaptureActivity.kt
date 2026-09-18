package com.lbc.breadcrumb.capture

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableStateOf
import com.lbc.breadcrumb.BreadcrumbApp
import com.lbc.breadcrumb.data.BreadcrumbDatabase
import com.lbc.breadcrumb.data.Memory
import com.lbc.breadcrumb.data.MemoryDao
import com.lbc.breadcrumb.data.OriginalStore
import com.lbc.breadcrumb.sync.UploadWorker
import com.lbc.breadcrumb.ui.capture.CaptureSheet
import com.lbc.breadcrumb.ui.theme.BreadcrumbTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Base for every capture entry point: hosts the capture sheet, and owns its
 * state, dismissal and undo. Subclasses only do the capturing.
 *
 * ComponentActivity rather than a plain Activity -- a reversal of 1.3's choice,
 * made because these activities now draw UI, and Compose needs one. With
 * something on screen, the difference in startup cost is negligible.
 *
 * Saving still never waits on the sheet (architecture rule 2): the write is
 * launched first, and the sheet merely reports it.
 */
abstract class CaptureActivity : ComponentActivity() {

    private val state = mutableStateOf<CaptureUiState>(CaptureUiState.Working)
    private var saved: List<Memory> = emptyList()
    private var pendingWrite: Job? = null

    protected val app: BreadcrumbApp get() = application as BreadcrumbApp
    protected val dao: MemoryDao by lazy { BreadcrumbDatabase.get(this).memoryDao() }
    protected val store: OriginalStore by lazy { OriginalStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Transparent bars: the scrim and sheet run edge to edge over the host app.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false

        // Recreated after process death: the original capture has either
        // finished or died with the process. Capturing again would duplicate it.
        if (savedInstanceState != null) {
            finish()
            return
        }

        setContent {
            BreadcrumbTheme {
                CaptureSheet(
                    state = state.value,
                    onUndo = ::undo,
                    onFinished = ::finishInvisibly,
                )
            }
        }

        onCapture()
    }

    /** Start capturing, then report through [showSaved] or [showFailed]. */
    protected abstract fun onCapture()

    /**
     * Closes without leaving the system anything visible to animate.
     *
     * Capture activities always run in their own task -- sharing apps launch
     * targets with NEW_TASK / NEW_DOCUMENT, and the tile must use NEW_TASK -- so
     * finishing one is a cross-task transition. Apps cannot customise those
     * (overrideActivityTransition and overridePendingTransition are ignored),
     * and the system slides the closing window down. Whatever that window still
     * held -- the dim, observed on-device -- slid away a beat after the sheet.
     *
     * So the window is hidden first. Once the window manager has taken the
     * surface off screen, the close transition moves nothing anyone can see.
     */
    private fun finishInvisibly() {
        if (isFinishing) return
        val decor = window.decorView

        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        decor.visibility = View.INVISIBLE

        val close = Runnable { if (!isFinishing) finish() }
        // Two frames: one for the traversal that applies INVISIBLE, one for the
        // window manager to have hidden the surface. The delayed post is a
        // fallback in case frames stop being delivered to a hidden window.
        decor.postOnAnimation { decor.postOnAnimation(close) }
        decor.postDelayed(close, 150)
    }

    /**
     * @param write the job writing [memories], when it may still be running.
     *   Undo waits for it, so a fast tap can never delete before the insert.
     */
    protected fun showSaved(memories: List<Memory>, attempted: Int, write: Job? = null) {
        saved = memories
        pendingWrite = write
        state.value = CaptureUiState.Saved(memories, attempted)

        // Asks only; WorkManager decides when. Undo can still beat it, and the
        // worker skips rows that are gone by the time it looks.
        if (memories.isNotEmpty()) UploadWorker.schedule(applicationContext)
    }

    protected fun showFailed(@StringRes message: Int) {
        state.value = CaptureUiState.Failed(message)
    }

    private fun undo() {
        val toRemove = saved
        val write = pendingWrite
        saved = emptyList()
        state.value = CaptureUiState.Undone

        app.applicationScope.launch {
            write?.join()
            toRemove.forEach {
                // The sheet stays up until dismissed, and the upload queue may
                // have sent the memory meanwhile: the delete goes to the server too.
                dao.deleteEverywhere(it, System.currentTimeMillis())
                store.delete(it)
            }
            if (toRemove.isNotEmpty()) UploadWorker.schedule(applicationContext)
        }
    }
}
