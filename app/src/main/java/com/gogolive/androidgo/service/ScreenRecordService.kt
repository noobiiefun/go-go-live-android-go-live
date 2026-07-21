package com.gogolive.androidgo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gogolive.androidgo.R
import com.gogolive.androidgo.ui.MainActivity
import com.pedro.rtplibrary.rtmp.RtmpDisplay
import net.ossrs.rtmp.ConnectCheckerRtmp

/**
 * Service ini adalah "otak" dari aplikasi.
 *
 * PENTING soal kenapa ini BUKAN overlay:
 * - Service biasa (foreground service) TIDAK menggambar apapun di layar.
 * - Satu-satunya yang terlihat ke user adalah notifikasi wajib di status bar
 *   (itu aturan Android supaya user selalu tahu layarnya sedang direkam/disiarkan).
 * - Tidak ada izin "Draw over other apps" / SYSTEM_ALERT_WINDOW yang dipakai di sini.
 * - Karena berjalan sebagai Service (bukan Activity), aplikasi lain tetap bisa
 *   dipakai secara normal selama live berjalan - cocok untuk Android Go yang
 *   RAM-nya terbatas karena kita hindari overhead window overlay.
 */
class ScreenRecordService : Service(), ConnectCheckerRtmp {

    private var rtmpDisplay: RtmpDisplay? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
        val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        val rtmpUrl = intent.getStringExtra(EXTRA_RTMP_URL).orEmpty()

        if (data == null || rtmpUrl.isEmpty()) {
            Log.e(TAG, "Data izin MediaProjection atau RTMP URL kosong, service dihentikan")
            stopSelf()
            return
        }

        startForegroundWithNotification()

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        rtmpDisplay = RtmpDisplay(baseContext, true, this).apply {
            setIntentResult(resultCode, data)
        }

        val prepared = rtmpDisplay?.prepareVideo(
            metrics.widthPixels,
            metrics.heightPixels,
            FPS,
            VIDEO_BITRATE,
            0,
            metrics.densityDpi
        ) ?: false

        val audioPrepared = rtmpDisplay?.prepareAudio() ?: false

        if (prepared && audioPrepared) {
            rtmpDisplay?.startStream(rtmpUrl)
        } else {
            Log.e(TAG, "Gagal menyiapkan encoder video/audio")
            stopSelf()
        }
    }

    private fun handleStop() {
        if (rtmpDisplay?.isStreaming == true) {
            rtmpDisplay?.stopStream()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val windowManager
        get() = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager

    private fun startForegroundWithNotification() {
        val channelId = "go_go_live_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_content))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- Callback status koneksi RTMP dari RootEncoder ----

    override fun onConnectionSuccessRtmp() {
        Log.d(TAG, "RTMP terhubung, live dimulai")
    }

    override fun onConnectionFailedRtmp(reason: String) {
        Log.e(TAG, "RTMP gagal konek: $reason")
        handleStop()
    }

    override fun onNewBitrateRtmp(bitrate: Long) {
        // opsional: bisa dipakai untuk menampilkan bitrate real-time di notifikasi
    }

    override fun onDisconnectRtmp() {
        Log.d(TAG, "RTMP terputus")
    }

    override fun onAuthErrorRtmp() {
        Log.e(TAG, "RTMP auth error - cek stream key")
        handleStop()
    }

    override fun onAuthSuccessRtmp() {
        Log.d(TAG, "RTMP auth sukses")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (rtmpDisplay?.isStreaming == true) {
            rtmpDisplay?.stopStream()
        }
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val NOTIFICATION_ID = 1001
        private const val VIDEO_BITRATE = 2_500 * 1000 // 2.5 Mbps, aman untuk Android Go
        private const val FPS = 24

        const val ACTION_START = "com.gogolive.androidgo.action.START"
        const val ACTION_STOP = "com.gogolive.androidgo.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RTMP_URL = "extra_rtmp_url"
    }
}
