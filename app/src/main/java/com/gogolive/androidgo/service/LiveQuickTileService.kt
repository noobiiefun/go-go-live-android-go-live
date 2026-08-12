package com.gogolive.androidgo.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.gogolive.androidgo.ui.QuickStartActivity

/**
 * Tile di Quick Settings (notification shade) untuk mulai/stop live tanpa perlu
 * membuka aplikasi penuh - supaya bisa dipakai sambil aplikasi lain (misal spacedesk)
 * tetap di depan, dan orientasi layarnya tidak ikut berubah gara-gara kita.
 *
 * Cara user menambahkan tile ini ke Quick Settings: buka notification shade,
 * tekan ikon pensil/edit, cari "Live" (nama tile ini), drag ke area tile aktif.
 */
class LiveQuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTileState()
    }

    override fun onClick() {
        super.onClick()
        if (ScreenRecordService.isRunning) {
            stopLive()
        } else {
            startLiveViaTrampoline()
        }
    }

    private fun startLiveViaTrampoline() {
        val intent = Intent(this, QuickStartActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ mewajibkan PendingIntent, tidak bisa langsung Intent lagi
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun stopLive() {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(intent)
        // Update tile ke kondisi idle segera - tidak perlu menunggu onStartListening berikutnya
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    private fun refreshTileState() {
        qsTile?.apply {
            state = if (ScreenRecordService.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
