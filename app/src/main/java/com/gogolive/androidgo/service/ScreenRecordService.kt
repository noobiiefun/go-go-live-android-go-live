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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import com.gogolive.androidgo.R
import com.gogolive.androidgo.ui.MainActivity
import com.pedro.common.ConnectChecker
import com.pedro.library.util.BitrateAdapter
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
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
    private var savedAudioSource: String = AUDIO_SOURCE_INTERNAL
    private var savedFps: Int = FPS_FLOOR
    private var savedBitrate: Int = 3000
    private var savedResolution: Int = 480

    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED
    // Dicek terpisah dari orientasi. Alasan: skenario spacedesk lewat kabel data biasanya
    // TIDAK mengubah enum orientasi (tetap portrait/landscape yang sama), tapi RESOLUSI ASLI
    // capture bisa berubah (density/ukuran tampilan menyesuaikan sinyal dari PC). Kalau cuma
    // mengandalkan cek orientasi, perubahan ini tidak akan memicu restart sama sekali - kanvas
    // MediaProjection lama (ukuran tetap dari awal) jadi tidak cocok lagi dengan kondisi layar,
    // hasilnya video freeze/rusak padahal koneksi RTMP masih terbuka (live "kelihatan" jalan
    // tapi videonya sudah tidak ter-update).
    private var lastCaptureWidth: Int = 0
    private var lastCaptureHeight: Int = 0
    // FPS ini TIDAK dipilih otomatis oleh service - nilainya datang dari savedFps
    // (extra EXTRA_FPS), yaitu pilihan manual user di UI. Dikunci selama sesi live,
    // tidak berubah real-time (mengubah fps di tengah live butuh setup ulang encoder,
    // jalur yang sama rapuhnya dengan restart resolusi).
    private var selectedFps: Int = FPS_FLOOR
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRestart: Runnable? = null
    private var bitrateAdapter: BitrateAdapter? = null

    // RECONNECT LOGIC
    private var reconnectCount = 0
    private val maxReconnectRetries = 5
    private val reconnectDelayMs = 3000L

    // RESOURCE LOCKS (Mencegah Android Go membunuh koneksi)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        // NoVideoSource() = placeholder sebelum ScreenSource asli dipasang setelah izin didapat.
        genericStream = GenericStream(baseContext, this, NoVideoSource(), MicrophoneSource())
        
        bitrateAdapter = BitrateAdapter { bitrate: Int ->
            if (isRunning) {
                genericStream.setVideoBitrateOnFly(bitrate)
            }
        }
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
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_RESULT_DATA, Intent::class.java)
        val rtmpUrl = intent.getStringExtra(EXTRA_RTMP_URL).orEmpty()

        if (data == null || rtmpUrl.isEmpty()) {
            Log.e(TAG, "Data izin MediaProjection atau RTMP URL kosong, service dihentikan")
            stopSelf()
            return
        }

        savedResultCode = resultCode
        savedResultData = data
        savedRtmpUrl = rtmpUrl
        savedAudioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: AUDIO_SOURCE_INTERNAL
        // Dipilih MANUAL oleh user di UI (bukan ditebak otomatis lagi - lihat riwayat
        // di pickFpsForDevice() kenapa deteksi otomatis dibatalkan). Default 30 kalau
        // extra ini entah kenapa tidak dikirim (misal dari versi lama yang belum update).
        savedFps = intent.getIntExtra(EXTRA_FPS, FPS_FLOOR).coerceIn(FPS_FLOOR, FPS_CEILING)
        savedBitrate = intent.getIntExtra(EXTRA_BITRATE, 3000)
        savedResolution = intent.getIntExtra(EXTRA_RESOLUTION, 480)

        lastOrientation = resources.configuration.orientation
        isRunning = true
        reconnectCount = 0 // reset counter saat mulai baru
        
        acquireLocks()
        startForegroundWithNotification()
        startEncoding(isRestart = false)
        startPeriodicResolutionWatcher()
    }

    /**
     * Jaring pengaman tambahan di luar onConfigurationChanged(). Beberapa vendor Android
     * (termasuk MIUI/Android Go) tidak selalu memicu callback config-change untuk perubahan
     * resolusi yang datang dari display eksternal seperti spacedesk. Jadi selain reaktif
     * lewat callback, kita juga cek manual setiap beberapa detik selama live berjalan.
     * Biaya cek ini murah (cuma baca metrics), jauh lebih murah daripada video diam-diam
     * freeze tanpa ketahuan.
     */
    private fun startPeriodicResolutionWatcher() {
        mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!this@ScreenRecordService::genericStream.isInitialized || savedResultData == null) {
                    return // live sudah berhenti, hentikan watcher
                }
                checkResolutionAndRestartIfNeeded()
                mainHandler.postDelayed(this, RESOLUTION_WATCH_INTERVAL_MS)
            }
        }, RESOLUTION_WATCH_INTERVAL_MS)
    }

    /** Menyiapkan encoder + ScreenSource baru dan mulai streaming, memakai ukuran layar TERKINI. */
    private fun startEncoding(isRestart: Boolean) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        // LOGIKA DOWNSCALING:
        // Jika user pilih 480p, kita hitung target resolusi yang proporsional.
        // Misal layar asli 720x1600 (Portrait) -> 480 x (1600 * 480 / 720) = 480x1066.
        val targetWidth: Int
        val targetHeight: Int

        if (savedResolution == 480) {
            if (metrics.widthPixels < metrics.heightPixels) {
                // Portrait
                targetWidth = 480
                targetHeight = (metrics.heightPixels * 480) / metrics.widthPixels
            } else {
                // Landscape
                targetHeight = 480
                targetWidth = (metrics.widthPixels * 480) / metrics.heightPixels
            }
            Log.d(TAG, "Downscaling aktif: 480p mode")
        } else {
            // Asli (720p atau lebih)
            targetWidth = metrics.widthPixels
            targetHeight = metrics.heightPixels
            Log.d(TAG, "Resolusi asli aktif: 720p+ mode")
        }

        // WAJIB: lebar & tinggi yang dikirim ke encoder H.264 HARUS kelipatan 16
        val alignedWidth = (targetWidth / 16) * 16
        val alignedHeight = (targetHeight / 16) * 16
        
        Log.d(
            TAG,
            "Resolusi layar ${metrics.widthPixels}x${metrics.heightPixels} -> " +
                "Output encoder ${alignedWidth}x${alignedHeight}"
        )
        // PENTING: Simpan resolusi ASLI layar, bukan hasil aligned/downscaled.
        // Supaya pengecekan di watcher berkala konsisten dengan ukuran fisik HP.
        lastCaptureWidth = metrics.widthPixels
        lastCaptureHeight = metrics.heightPixels

        // FPS ditentukan HANYA SEKALI di awal sesi live (bukan real-time selama live
        // jalan) - dari savedFps, yaitu pilihan MANUAL user di UI (lihat MainActivity,
        // RadioGroup rgFps). Alasan tidak real-time/otomatis: mengubah FPS di tengah
        // sesi butuh menyiapkan ulang encoder video, jalur yang sama dengan restart
        // resolusi yang ternyata rapuh (MediaProjection tidak boleh dipakai berulang).
        // RIWAYAT: sempat dicoba deteksi otomatis lewat ActivityManager.isLowRamDevice()
        // - TERBUKTI TIDAK CUKUP, device RAM cukup pun bisa chip video encoder-nya
        // (Codec2) crash total di 60fps ("Codec2 component died", DEAD_OBJECT), bikin
        // live macet + app hang. Makanya sekarang diserahkan ke user via toggle manual.
        if (!isRestart) {
            selectedFps = savedFps
        }

        // Bitrate dalam bps (bit per second). User menginput dalam Kbps.
        val videoBitrateBps = savedBitrate * 1000

        // PENTING: token izin MediaProjection (savedResultCode/savedResultData) HANYA BOLEH
        // dipakai SEKALI untuk memanggil getMediaProjection(). Kalau dipanggil lagi saat restart
        // (misal karena rotasi ke landscape waktu buka spacedesk), Android akan menolak/crash.
        // Jadi saat restart, kita HARUS pakai ulang objek MediaProjection yang sudah didapat di
        // awal (field "mediaProjection"), bukan minta yang baru.
        val projection: MediaProjection
        if (isRestart) {
            val existing = mediaProjection
            if (existing == null) {
                Log.e(TAG, "Restart dibatalkan: tidak ada MediaProjection aktif")
                stopSelf()
                return
            }
            projection = existing
        } else {
            val data = savedResultData ?: return
            val fresh = mediaProjectionManager.getMediaProjection(savedResultCode, data)
            if (fresh == null) {
                Log.e(TAG, "Gagal mendapatkan MediaProjection")
                stopSelf()
                return
            }
            mediaProjection = fresh

            // WAJIB sejak Android 14: MediaProjection harus didaftarkan callback-nya SEBELUM
            // dipakai untuk capture (createVirtualDisplay). Kalau tidak didaftarkan, sistem akan
            // langsung throw IllegalStateException begitu capture dimulai.
            fresh.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection dihentikan oleh sistem (misal user cabut izin lewat status bar)")
                }
            }, mainHandler)

            projection = fresh
        }

        // 3.0.0+ untuk 30fps, 5.0.0+ untuk 60fps. Bitrate yang terlalu rendah pada 60fps
        // sering bikin YouTube bingung (frame drop/pixelated), tapi terlalu tinggi
        // bikin encoder Xiaomi A3 meledak.
        // SEKARANG: Menggunakan pilihan manual user (videoBitrateBps).
        val dynamicBitrate = videoBitrateBps

        val prepared = try {
            // PENTING: Gunakan default profile/level library agar hardware bisa memilih 
            // jalur paling stabil secara otomatis. Menghapus hardcode 66/128.
            // GOP (Keyframe Interval) tetap 2 detik untuk YouTube.
            var success = genericStream.prepareVideo(
                alignedWidth, alignedHeight, dynamicBitrate, selectedFps, 2
            )
            
            // FALLBACK LOGIC: Kalau 60fps gagal di-prepare (biasanya karena encoder menolak),
            // coba turunkan ke 30fps secara otomatis.
            if (!success && selectedFps > FPS_FLOOR) {
                Log.w(TAG, "Encoder menolak $selectedFps FPS, mencoba fallback ke $FPS_FLOOR FPS...")
                mainHandler.post {
                    Toast.makeText(baseContext, "HP tidak kuat 60fps, otomatis turun ke 30fps", Toast.LENGTH_LONG).show()
                }
                selectedFps = FPS_FLOOR
                success = genericStream.prepareVideo(
                    alignedWidth, alignedHeight, dynamicBitrate, FPS_FLOOR, 2
                )
            }
            
            // PENTING UNTUK DELAY SUARA: Gunakan MONO (false) dan 44100Hz.
            // Mono mengurangi beban CPU 50% dibandingkan Stereo, sangat krusial di Android Go.
            success && genericStream.prepareAudio(44100, false, 64 * 1000)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Gagal prepare video/audio: ${e.message}")
            false
        }

        if (!prepared) {
            Log.e(TAG, "Encoder video/audio tidak siap, berhenti")
            mainHandler.post {
                Toast.makeText(baseContext, "Error: HP tidak support resolusi/FPS ini", Toast.LENGTH_LONG).show()
            }
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

        applyAudioSource(projection)

        // Mengaktifkan BitrateAdapter (Adaptive Bitrate). Ini sangat penting untuk mencegah
        // "Broken Pipe". Jika internet melambat, bitrate akan turun otomatis alih-alih putus.
        bitrateAdapter?.setMaxBitrate(videoBitrateBps)
        genericStream.setVideoBitrateOnFly(videoBitrateBps)

        Log.d(TAG, "Mulai streaming ke: ${savedRtmpUrl.take(20)}...")
        genericStream.startStream(savedRtmpUrl)
        Log.d(TAG, "Encoding dimulai pada ${alignedWidth}x${alignedHeight}, audio=$savedAudioSource")
    }

    /**
     * Menentukan dari mana audio diambil:
     * - INTERNAL: audio digital dari sistem HP (misal audio PC yang diputar lewat spacedesk).
     *   Ini yang disarankan untuk kasus spacedesk supaya tidak ada gema/kualitas jelek akibat
     *   microphone "mendengar" suara dari speaker HP.
     * - MIC: microphone fisik HP (suara ruangan/komentar suara kamu).
     * - MIX: gabungan keduanya.
     * INTERNAL dan MIX butuh Android 10+ (API 29); di bawah itu otomatis fallback ke microphone.
     */
    private fun applyAudioSource(projection: MediaProjection) {
        val useInternal = savedAudioSource == AUDIO_SOURCE_INTERNAL || savedAudioSource == AUDIO_SOURCE_MIX
        val supportsInternal = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        if (useInternal && !supportsInternal) {
            Log.e(TAG, "Audio internal butuh Android 10+, fallback ke microphone")
        }

        try {
            when {
                savedAudioSource == AUDIO_SOURCE_MIX && supportsInternal ->
                    genericStream.changeAudioSource(MixAudioSource(projection))
                savedAudioSource == AUDIO_SOURCE_INTERNAL && supportsInternal ->
                    genericStream.changeAudioSource(InternalAudioSource(projection))
                else ->
                    genericStream.changeAudioSource(MicrophoneSource())
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Gagal set audio source ($savedAudioSource): ${e.message}, fallback ke microphone")
            try {
                genericStream.changeAudioSource(MicrophoneSource())
            } catch (e2: IllegalArgumentException) {
                Log.e(TAG, "Fallback microphone juga gagal: ${e2.message}")
            }
        }
    }

    /**
     * Dipanggil otomatis oleh Android setiap kali konfigurasi berubah,
     * termasuk saat rotasi layar potrait <-> landscape.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        if (!this::genericStream.isInitialized) return
        if (savedResultData == null) return // belum mulai streaming, abaikan

        lastOrientation = newConfig.orientation
        Log.d(TAG, "onConfigurationChanged terpicu, cek ulang resolusi layar...")

        // Debounce: rotasi/perubahan tampilan bisa memicu beberapa callback beruntun,
        // jadi tunggu sebentar sampai kondisi layar stabil sebelum cek & restart encoder.
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        val restartTask = Runnable { checkResolutionAndRestartIfNeeded() }
        pendingRestart = restartTask
        mainHandler.postDelayed(restartTask, ORIENTATION_DEBOUNCE_MS)
    }

    /**
     * Dipanggil setelah debounce. Restart HANYA dilakukan kalau resolusi asli layar
     * benar-benar berbeda dari yang dipakai encoder saat ini - bukan cuma karena
     * onConfigurationChanged terpicu (callback ini bisa terpicu oleh hal lain yang
     * tidak mengubah ukuran capture sama sekali, misal density/keyboard).
     * Ini juga menutup celah kasus spacedesk: resolusi bisa berubah tanpa enum
     * orientasi (potrait/landscape) ikut berubah.
     */
    private fun checkResolutionAndRestartIfNeeded() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        
        // Bandingkan langsung dengan resolusi fisik layar terakhir.
        if (metrics.widthPixels == lastCaptureWidth && metrics.heightPixels == lastCaptureHeight) {
            return
        }

        Log.d(
            TAG,
            "Resolusi FISIK berubah dari ${lastCaptureWidth}x${lastCaptureHeight} ke " +
                "${metrics.widthPixels}x${metrics.heightPixels}, restart encoder..."
        )
        restartEncodingForNewOrientation()
    }

    private fun restartEncodingForNewOrientation() {
        try {
            if (genericStream.isStreaming) {
                genericStream.stopStream()
            }
            startEncoding(isRestart = true)
        } catch (e: Throwable) {
            // Tangkap SEMUA jenis exception (bukan cuma IllegalArgumentException) di sini.
            // Ini titik paling rawan crash: dipicu tiap kali resolusi/orientasi layar
            // berubah saat live jalan (misal buka app lain yang mengubah tampilan,
            // termasuk kasus spacedesk). Kalau restart gagal, hentikan service dengan
            // rapi (log jelas) daripada membiarkan uncaught exception menjatuhkan
            // seluruh proses aplikasi (force close).
            Log.e(TAG, "Restart encoder gagal saat perubahan layar, menghentikan live: ${e.message}", e)
            handleStop()
        }
    }

    private fun handleStop() {
        // URUTAN PENTING: bagian "wajib cepat" (matikan notifikasi, tandai service
        // berhenti) dijalankan LANGSUNG di sini secara síncron, SEBELUM mencoba
        // membersihkan genericStream/mediaProjection. Alasan: kalau genericStream.
        // stopStream() sampai macet/nunggu lama (misal karena hardware encoder chip
        // sudah mati duluan - pernah kejadian: "Codec2 component died"), operasi itu
        // jalan di MAIN THREAD dan akan MEMBEKUKAN SELURUH APLIKASI kalau ditunggu -
        // termasuk bikin tombol Stop (baik di UI maupun di notifikasi) tidak merespon
        // sama sekali, karena keduanya lewat jalur main thread yang sama.
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        savedResultData = null // penting: jadi sinyal berhenti untuk watcher resolusi berkala
        isRunning = false
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()

        // Baru sekarang coba bersihkan encoder/projection - di background thread,
        // "fire and forget". Kalau ini macet/gagal, TIDAK akan membekukan UI lagi,
        // karena notifikasi & status "live" sudah dianggap berhenti sejak baris di atas.
        Thread {
            try {
                if (this::genericStream.isInitialized && genericStream.isStreaming) {
                    genericStream.stopStream()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error saat stopStream() (background): ${e.message}", e)
            }
            try {
                mediaProjection?.stop()
            } catch (e: Throwable) {
                Log.e(TAG, "Error saat mediaProjection.stop() (background): ${e.message}", e)
            }
            mediaProjection = null
        }.start()
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Gagal melepas locks: ${e.message}")
        }
    }

    private fun acquireLocks() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GoGoLive::StreamingLock").apply {
            acquire(10 * 60 * 1000L /* 10 minutes max safe fallback */)
        }
        
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GoGoLive::WifiLock").apply {
            acquire()
        }
        Log.d(TAG, "WakeLock & WifiLock didapatkan")
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
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_content))
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    // ---- Callback status koneksi dari RootEncoder (interface ConnectChecker terpadu) ----

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Mulai konek ke $url")
        mainHandler.post {
            Toast.makeText(baseContext, "Menghubungkan ke YouTube...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConnectionSuccess() {
        Log.d(TAG, "RTMP terhubung, live dimulai")
        reconnectCount = 0 // Reset counter setelah sukses tersambung
        mainHandler.post {
            Toast.makeText(baseContext, "BERHASIL! Live sudah masuk ke YouTube", Toast.LENGTH_LONG).show()
        }
    }

    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "RTMP gagal konek: $reason")
        attemptReconnect(reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        bitrateAdapter?.adaptBitrate(bitrate)
    }

    override fun onDisconnect() {
        Log.d(TAG, "RTMP terputus")
        if (isRunning) {
            attemptReconnect("Terputus")
        }
    }

    private fun attemptReconnect(reason: String) {
        if (reconnectCount < maxReconnectRetries) {
            reconnectCount++
            mainHandler.post {
                Toast.makeText(baseContext, "Koneksi drop ($reason), menyambung kembali ($reconnectCount/$maxReconnectRetries)...", Toast.LENGTH_SHORT).show()
            }
            mainHandler.postDelayed({
                if (isRunning && savedRtmpUrl.isNotEmpty()) {
                    Log.d(TAG, "Mencoba reconnect dengan reset full...")
                    try {
                        if (genericStream.isStreaming) genericStream.stopStream()
                        // Re-prepare dan start stream ulang
                        startEncoding(isRestart = true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Gagal restart encoding: ${e.message}")
                        genericStream.startStream(savedRtmpUrl)
                    }
                }
            }, reconnectDelayMs)
        } else {
            mainHandler.post {
                Toast.makeText(baseContext, "Gagal konek setelah $maxReconnectRetries percobaan: $reason", Toast.LENGTH_LONG).show()
            }
            handleStop()
        }
    }

    override fun onAuthError() {
        Log.e(TAG, "RTMP auth error - cek stream key")
        mainHandler.post {
            Toast.makeText(baseContext, "Auth Error: Cek Stream Key!", Toast.LENGTH_LONG).show()
        }
        handleStop()
    }

    override fun onAuthSuccess() {
        Log.d(TAG, "RTMP auth sukses")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Log level tekanan memori dari sistem. Kalau force close terjadi TANPA log
        // exception apapun tapi ada log onTrimMemory dengan level tinggi (misal
        // TRIM_MEMORY_RUNNING_CRITICAL/COMPLETE) tepat sebelumnya, itu tanda kuat
        // penyebabnya RAM kurang (device Android Go + aplikasi berat seperti
        // spacedesk berjalan bersamaan), bukan bug logika di aplikasi ini.
        Log.w(TAG, "onTrimMemory dipanggil sistem, level=$level")
    }

    override fun onDestroy() {
        super.onDestroy()
        pendingRestart?.let { mainHandler.removeCallbacks(it) }
        if (this::genericStream.isInitialized && genericStream.isStreaming) {
            genericStream.stopStream()
        }
        mediaProjection?.stop()
        mediaProjection = null
        isRunning = false // jaga-jaga kalau service dimatikan sistem (bukan lewat handleStop())
    }

    companion object {
        private const val TAG = "ScreenRecordService"
        private const val NOTIFICATION_ID = 1001

        // FPS_FLOOR/CEILING: batas bawah & atas untuk toggle manual di UI (RadioGroup rgFps).
        // 30 = default aman; 60 = opsi coba-coba user (lihat riwayat kenapa deteksi
        // otomatis dari RAM dibatalkan, di komentar startEncoding()).
        private const val FPS_FLOOR = 30
        private const val FPS_CEILING = 60
        
        private const val ORIENTATION_DEBOUNCE_MS = 500L
        private const val RESOLUTION_WATCH_INTERVAL_MS = 5_000L

        const val ACTION_START = "com.gogolive.androidgo.action.START"
        const val ACTION_STOP = "com.gogolive.androidgo.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RTMP_URL = "extra_rtmp_url"
        const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        const val EXTRA_FPS = "extra_fps"
        const val EXTRA_BITRATE = "extra_bitrate"
        const val EXTRA_RESOLUTION = "extra_resolution"

        const val AUDIO_SOURCE_INTERNAL = "internal"
        const val AUDIO_SOURCE_MIC = "mic"
        const val AUDIO_SOURCE_MIX = "mix"

        // Dibaca oleh LiveQuickTileService untuk tahu harus menampilkan tile
        // dalam kondisi "aktif" (klik = stop) atau "tidak aktif" (klik = mulai).
        // Cukup var biasa (bukan lewat broadcast) karena TileService selalu
        // membaca ulang nilainya tiap kali panel Quick Settings dibuka
        // (onStartListening), jadi tidak butuh notifikasi real-time.
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
