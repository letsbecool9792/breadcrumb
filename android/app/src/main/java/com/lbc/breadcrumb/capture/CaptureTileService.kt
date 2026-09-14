package com.lbc.breadcrumb.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.lbc.breadcrumb.R

/**
 * Quick Settings tile: one tap saves the clipboard.
 *
 * The tile cannot read the clipboard itself -- a TileService never has window
 * focus -- so it hands off to [ClipboardCaptureActivity], which does.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            // an action, not a toggle: there is no on/off state to show
            state = Tile.STATE_INACTIVE
            subtitle = getString(R.string.tile_subtitle)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        // On the lock screen, unlock first: a capture activity launched over
        // the keyguard would not get the focus it needs to read the clipboard.
        if (isLocked) unlockAndRun(::launchCapture) else launchCapture()
    }

    private fun launchCapture() {
        val intent = Intent(this, ClipboardCaptureActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // The Intent overload throws UnsupportedOperationException for apps
            // targeting 34+; only the PendingIntent form is accepted.
            val pending = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
