package com.akira.island.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.akira.island.core.Controls
import com.akira.island.core.IslandBus
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import com.akira.island.data.LauncherVisibility

/**
 * "Island" tile in the shade. Banks (K PLUS, GCash and others) refuse to work while an accessibility service
 * not from Play Store is on. Tap the tile: the island turns off, and you can go to the bank; tap again: accessibility
 * settings open to turn it back on, because Android does not let an app enable the service itself.
 */
class IslandTile : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        LauncherVisibility.onTileAdded(this)
    }

    override fun onTileRemoved() {
        LauncherVisibility.onTileRemoved(this)
        super.onTileRemoved()
    }

    override fun onStartListening() {
        SettingsRepo.init(this)
        val tile = qsTile ?: return
        val on = IslandBus.serviceRunning.value
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = if (on) tr("Включён", "On") else tr("Выключен", "Off")
        tile.updateTile()
    }

    override fun onClick() {
        SettingsRepo.init(this)
        if (IslandBus.serviceRunning.value) {
            Controls.turnOffIsland()
            // The service will turn off in a moment: dim the tile right away
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) it.subtitle = tr("Выключен", "Off")
                it.updateTile()
            }
        } else {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        /** The island was turned on or off: the tile is redrawn when the shade is opened */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, IslandTile::class.java)) }
        }
    }
}
