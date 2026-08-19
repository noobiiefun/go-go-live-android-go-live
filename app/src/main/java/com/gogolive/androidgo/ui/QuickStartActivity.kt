package com.gogolive.androidgo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.gogolive.androidgo.service.ScreenRecordService

/**
 * Activity ini TIDAK PUNYA UI SAMA SEKALI (tema Translucent.NoTitleBar, lihat themes.xml).
 * Satu-satunya tujuannya: jembatan supaya LiveQuickTileService (tile di Quick Settings)
 * bisa memicu dialog izin RECORD_AUDIO + MediaProjection tanpa membuka MainActivity
 * yang punya layout penuh.
 *
 * KENAPA INI PENTING (bukan cuma soal tampilan):
 * Kalau pakai MainActivity biasa buat ini, membuka MainActivity akan mengambil alih
 * layar penuh - termasuk kemungkinan memaksa orientasi balik ke potrait tergantung
 * state activity sebelumnya. Untuk kasus spacedesk (live harus mulai SAAT layar masih
 * landscape ikut spacedesk), itu bisa merusak resolusi yang mau di-capture. Activity
 * translucent ini tidak menggambar apa-apa selain dialog sistem kecil (izin microphone /
 * izin capture layar), jadi apapun yang ada di baliknya (spacedesk) tetap terlihat &
 * orientasinya tidak terganggu.
 *
 * RTMP URL & Stream Key TIDAK diminta ulang di sini - diambil dari SharedPreferences
 * yang sama dipakai MainActivity (KEY_RTMP_URL/KEY_STREAM_KEY). Kalau belum pernah
 * diisi sama sekali, activity ini akan membuka MainActivity biasa supaya user mengisi
 * dulu (hanya terjadi sekali, pemakaian berikutnya lewat tile tidak perlu itu lagi).
 */
class QuickStartActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rtmpUrl = prefs.getString(KEY_RTMP_URL, null).orEmpty()
            val streamKey = prefs.getString(KEY_STREAM_KEY, null).orEmpty()
            val audioSource = prefs.getString(KEY_AUDIO_SOURCE, ScreenRecordService.AUDIO_SOURCE_INTERNAL)
            val fps = prefs.getInt(KEY_FPS, 30)
            val bitrate = prefs.getInt(KEY_BITRATE, 3000)
            val resolution = prefs.getInt(KEY_RESOLUTION, 480)

            val cleanUrl = rtmpUrl.removeSuffix("/")

            val intent = Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_START
                putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
                putExtra(ScreenRecordService.EXTRA_RTMP_URL, "$cleanUrl/$streamKey")
                putExtra(ScreenRecordService.EXTRA_AUDIO_SOURCE, audioSource)
                putExtra(ScreenRecordService.EXTRA_FPS, fps)
                putExtra(ScreenRecordService.EXTRA_BITRATE, bitrate)
                putExtra(ScreenRecordService.EXTRA_RESOLUTION, resolution)
            }
            ContextCompat.startForegroundService(this, intent)
        } else {
            Toast.makeText(this, "Izin capture layar ditolak", Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Lanjut ke pengecekan audio permission terlepas dari notifikasi diizinkan atau tidak,
        // karena notifikasi bukan permission "dangerous" yang mematikan fungsi utama (streaming),
        // hanya mempengaruhi visibilitas tombol Stop.
        checkAudioPermission()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            Toast.makeText(this, "Izin microphone ditolak", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rtmpUrl = prefs.getString(KEY_RTMP_URL, null)
        val streamKey = prefs.getString(KEY_STREAM_KEY, null)

        if (rtmpUrl.isNullOrBlank() || streamKey.isNullOrBlank()) {
            // Belum pernah diisi sama sekali - arahkan ke MainActivity dulu supaya user
            // mengisi RTMP URL & Stream Key. Kali berikutnya tile bisa dipakai langsung.
            Toast.makeText(
                this,
                "Isi RTMP URL & Stream Key dulu di aplikasi",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            finish()
            return
        }

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            
            if (!hasNotifPermission) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        checkAudioPermission()
    }

    private fun checkAudioPermission() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    companion object {
        // HARUS SAMA PERSIS dengan yang dipakai MainActivity, supaya baca data yang sama
        private const val PREFS_NAME = "go_go_live_prefs"
        private const val KEY_RTMP_URL = "rtmp_url"
        private const val KEY_STREAM_KEY = "stream_key"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_RESOLUTION = "resolution"
    }
}
