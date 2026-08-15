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
        savedAudioSource = intent.getStringExtra(EXTRA_AUDIO_SOURCE) ?: AUDIO_SOURCE_INTERNAL
        // Dipilih MANUAL oleh user di UI (bukan ditebak otomatis lagi - lihat riwayat
        // di pickFpsForDevice() kenapa deteksi otomatis dibatalkan). Default 30 kalau
        // extra ini entah kenapa tidak dikirim (misal dari versi lama yang belum update).
        savedFps = intent.getIntExtra(EXTRA_FPS, FPS_FLOOR).coerceIn(FPS_FLOOR, FPS_CEILING)
        lastOrientation = resources.configuration.orientation
        isRunning = true
        // lastCaptureWidth/Height diisi di dalam startEncoding() setelah metrics dibaca,
        // supaya nilainya konsisten sama persis dengan yang dipakai encoder.

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

        // WAJIB: lebar & tinggi yang dikirim ke encoder H.264 HARUS kelipatan 16 (ukuran
        // blok internal encoder/macroblock). Resolusi layar asli (mis. 720x1650 dari kasus
        // spacedesk) SERING TIDAK kelipatan 16 (1650 / 16 = 103,125 - tidak bulat). Sebagian
        // chip hardware encoder (terbukti: MediaTek "c2.mtk.avc.encoder" di HP ini) tidak
        // cuma menolak halus, tapi CRASH TOTAL ("Codec2 component died", lalu RTMP kena
        // "Broken pipe" karena encoder-nya sudah tidak ada) kalau dikasih ukuran ganjil
        // begini - ini kejadian nyata di kasus kita, BUKAN cuma soal FPS seperti dugaan
        // sebelumnya (terbukti tetap crash walau FPS sudah diturunkan ke 30).
        // Dibulatkan KE BAWAH supaya tidak melebihi batas layar asli.
        val alignedWidth = (metrics.widthPixels / 16) * 16
        val alignedHeight = (metrics.heightPixels / 16) * 16
        Log.d(
            TAG,
            "Resolusi asli ${metrics.widthPixels}x${metrics.heightPixels} -> " +
                "dibulatkan ke kelipatan 16 jadi ${alignedWidth}x${alignedHeight}"
        )
        lastCaptureWidth = alignedWidth
        lastCaptureHeight = alignedHeight

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

        val prepared = try {
            genericStream.prepareVideo(alignedWidth, alignedHeight, VIDEO_BITRATE, selectedFps) &&
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

        applyAudioSource(projection)

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
        // Dibulatkan ke kelipatan 16 juga di sini, SAMA PERSIS seperti di startEncoding() -
        // supaya perbandingannya konsisten (apple-to-apple). Kalau tidak, lastCaptureWidth/
        // Height (yang sudah dibulatkan) tidak akan pernah cocok dengan metrics mentah,
        // dan restart akan terus dianggap perlu tiap kali watcher berkala ini jalan.
        val alignedWidth = (metrics.widthPixels / 16) * 16
        val alignedHeight = (metrics.heightPixels / 16) * 16

        if (alignedWidth == lastCaptureWidth && alignedHeight == lastCaptureHeight) {
            Log.d(TAG, "Resolusi tidak berubah (${alignedWidth}x${alignedHeight}), restart dilewati")
            return
        }

        Log.d(
            TAG,
            "Resolusi berubah dari ${lastCaptureWidth}x${lastCaptureHeight} ke " +
                "${alignedWidth}x${alignedHeight}, restart encoder..."
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
        private const val VIDEO_BITRATE = 3_000 * 1000 // 3 Mbps, cukup untuk 30fps di Android Go
        // FPS_FLOOR/CEILING: batas bawah & atas untuk toggle manual di UI (RadioGroup rgFps).
        // 30 = default aman; 60 = opsi coba-coba user (lihat riwayat kenapa deteksi
        // otomatis dari RAM dibatalkan, di komentar startEncoding()).
        // https://github.com/pedroSG94/RootEncoder/issues/232 (referensi soal FPS & sync)
        private const val FPS_FLOOR = 30
        private const val FPS_CEILING = 60
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val AUDIO_BITRATE = 128 * 1000
        private const val ORIENTATION_DEBOUNCE_MS = 500L
        private const val RESOLUTION_WATCH_INTERVAL_MS = 5_000L

        const val ACTION_START = "com.gogolive.androidgo.action.START"
        const val ACTION_STOP = "com.gogolive.androidgo.action.STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_RTMP_URL = "extra_rtmp_url"
        const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        const val EXTRA_FPS = "extra_fps"

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
