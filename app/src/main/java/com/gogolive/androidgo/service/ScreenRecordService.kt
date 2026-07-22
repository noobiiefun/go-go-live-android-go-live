package com.gogolive.androidgo.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gogolive.androidgo.R
import com.gogolive.androidgo.ui.MainActivity
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream

/**
 * Service ini adalah "otak" dari aplikasi.
 *
 * PENTING soal kenapa ini BUKAN overlay:
 * - Service biasa (foreground service) TIDAK menggambar apapun di layar.
 * - Satu-satunya yang terlihat ke user adalah notifikasi wajib di status bar
 *   (itu aturan Android supaya user selalu tahu layarnya sedang direkam/disiarkan).
 * - Tidak ada izin "Draw over other apps" / SYSTEM_ALERT_WINDOW yang dipakai di sini.
 *
 * Pakai library RootEncoder versi 2.7.3 (GenericStream + ScreenSource), versi yang
 * aktif dikembangkan - versi lama (2.2.6/RtmpDisplay) sering gagal di-resolve Jitpack
 * pada Android Studio versi baru.
 *
 * DUKUNGAN ROTASI (vertikal/horizontal, untuk kasus spacedesk PC->HP):
 * MediaProjection membuat "kanvas" capture dengan ukuran TETAP saat pertama kali
 * disiapkan. Kalau device/tampilan berputar setelah itu (misal spacedesk pindah
 * dari mode potrait ke landscape), video akan terlihat gepeng/terpotong kalau
 * kanvas lama dipertahankan. Solusinya di sini: begitu sistem melaporkan
 * perubahan orientasi lewat onConfigurationChanged(), stream RTMP lama
 * dihentikan sebentar lalu dibuat ulang dengan ukuran layar yang baru.
 * MediaProjection token yang sama dipakai ulang - user TIDAK akan diminta
 * konfirmasi izin lagi. Ada jeda beberapa ratus milidetik saat proses ini.
 */
class ScreenRecordService : Service(), ConnectChecker {

    private lateinit var genericStream: GenericStream
    private val mediaProjectionManager: MediaProjectionManager by lazy {
        getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    private var mediaProjection: MediaProjection? = null

    // Disimpan supaya bisa dipakai ulang saat restart akibat rotasi,
    // tanpa perlu minta izin MediaProjection lagi ke user.
    private var savedResultCode: Int = -1
    private var savedResultData: Intent? = null
    private var savedRtmpUrl: String = ""

    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        // NoVideoSource() = placeholder sebelum ScreenSource asli dipasang setelah izin didapat.
        genericStream = GenericStream(baseContext, this, NoVideoSource(), MicrophoneSource())
    }

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

        savedResultCode = resultCode
        savedResultData = data
        savedRtmpUrl = rtmpUrl
        lastOrientation = resources.configuration.orientation

        startForegroundWithNotification()
        startEncoding(isRestart = false)
    }

    /** Menyiapkan encoder + ScreenSource baru dan mulai streaming, memakai ukuran layar TERKINI. */
    private fun startEncoding(isRestart: Boolean) {
        val data = savedResultData ?: return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        if (isRestart) {
            mediaProjection?.stop()
        }
        val projection = mediaProjectionManager.getMediaProjection(savedResultCode, data)
        if (projection == null) {
            Log.e(TAG, "Gagal mendapatkan MediaProjection")
            stopSelf()
            return
        }
        mediaProjection = projection

        // WAJIB sejak Android 14: MediaProjection harus didaftarkan callback-nya SEBELUM
        // dipakai untuk capture (createVirtualDisplay). Kalau tidak didaftarkan, sistem akan
        // langsung throw IllegalStateException begitu capture dimulai - inilah penyebab
        // force close yang terjadi persis setelah user memilih "seluruh layar" di dialog izin.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection dihentikan oleh sistem (misal user cabut izin lewat status bar)")
            }
        }, mainHandler)

        val prepared = try {
            genericStream.prepareVideo(metrics.widthPixels, metrics.heightPixels, VIDEO_BITRATE, FPS) &&
                genericStream.prepareAudio(AUDIO_SAMPLE_RATE, true, AUDIO_BITRATE)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Gagal prepare video/audio: ${e.message}")
            false
        }

        if (!prepared) {
            Log.e(TAG, "Encoder video/audio tidak siap, berhenti")
            stopSelf()
            return
        }

        try {
            genericStream.changeVideoSource(ScreenSource(baseContext, projection))
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Gagal memasang ScreenSource: ${e.message}")
            stopSelf()
            return
        }

        genericStream.startStream(savedRtmpUrl)
        Log.d(TAG, "Encoding dimulai pada ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    /**
     * Dipanggil otomatis oleh Android setiap kali konfigurasi berubah,
     * termasuk saat rotasi layar potrait <-> landscape.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (!this::genericStream.isInitialized) return
        if (savedResultData == null) return // belum mulai streaming, abaikan
        if (newConfig.orientation == lastOrientation) return // bukan perubahan orientasi

        lastOrientation = newConfig.orientation
        Log.d(TAG, "Orientasi berubah, menyesuaikan ulang resolusi stream...")

        // Debounce: rotasi bisa memicu beberapa callback beruntun selama animasi,
        // jadi tunggu sebentar sampai rotasi selesai sebelum restart encoder.
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        val restartTask = Runnable { restartEncodingForNewOrientation() }
        pendingRestart = restartTask
        mainHandler.postDelayed(restartTask, ORIENTATION_DEBOUNCE_MS)
    }

    private fun restartEncodingForNewOrientation() {
        if (genericStream.isStreaming) {
            genericStream.stopStream()
        }
        startEncoding(isRestart = true)
    }

    private fun handleStop() {
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        if (this::genericStream.isInitialized && genericStream.isStreaming) {
            genericStream.stopStream()
        }
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private val windowManager
        get() = getSystemService(WINDOW_SERVICE) as android.view.WindowManager

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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- Callback status koneksi dari RootEncoder (interface ConnectChecker terpadu) ----

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Mulai konek ke $url")
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "RTMP terhubung, live dimulai")
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP gagal konek: $reason")
        handleStop()
    }

    override fun onNewBitrate(bitrate: Long) {
        // opsional: bisa dipakai untuk menampilkan bitrate real-time di notifikasi
    }

    override fun onDisconnect() {
        Log.d(TAG, "RTMP terputus")
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error - cek stream key")
        handleStop()
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth sukses")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        if (this::genericStream.isInitialized && genericStream.isStreaming) {
            genericStream.stopStream()
        }
        mediaProjection?.stop()
        mediaProjection = null
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val NOTIFICATION_ID = 1001
        private const val VIDEO_BITRATE = 3_000 * 1000 // 3 Mbps, cukup untuk 30fps di Android Go
        private const val FPS = 30
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128 * 1000
        private const val ORIENTATION_DEBOUNCE_MS = 500L

        const val ACTION_START = "com.gogolive.androidgo.action.START"
        const val ACTION_STOP = "com.gogolive.androidgo.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RTMP_URL = "extra_rtmp_url"
    }
}
