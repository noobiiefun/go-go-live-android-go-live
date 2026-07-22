package com.gogolive.androidgo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.gogolive.androidgo.R
import com.gogolive.androidgo.databinding.ActivityMainBinding
import com.gogolive.androidgo.service.ScreenRecordService

/**
 * Activity ini HANYA dipakai untuk:
 *  1. Mengambil input RTMP URL + Stream Key dari user (disimpan otomatis di SharedPreferences
 *     supaya tidak perlu diketik ulang tiap mau live lagi).
 *  2. Meminta izin RECORD_AUDIO (wajib runtime permission - lihat catatan di maybeStartLive()).
 *  3. Meminta izin capture layar (MediaProjection) - dialog sistem, wajib, tidak bisa dilewati.
 *  4. Meminta izin notifikasi (Android 13+).
 *  5. Menyerahkan hasil izin tsb ke ScreenRecordService yang jalan di background.
 *
 * Setelah live dimulai, Activity ini BOLEH ditutup / user pindah ke app lain -
 * proses capture + streaming tetap berjalan di ScreenRecordService (foreground service),
 * BUKAN sebagai overlay/bubble yang mengambang di atas aplikasi lain.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startStreamingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Izin capture layar ditolak", Toast.LENGTH_SHORT).show()
            resetStatusToIdle()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, kita tetap lanjut - notifikasi cuma informatif */ }

    // WAJIB: RECORD_AUDIO adalah dangerous permission - meski sudah dideklarasikan di
    // AndroidManifest, tetap harus di-approve user lewat dialog runtime ini. Kalau di-skip,
    // ScreenRecordService akan force close saat startForeground() dengan tipe "microphone"
    // (SecurityException: requires permissions RECORD_AUDIO / FOREGROUND_SERVICE_MICROPHONE).
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            proceedToScreenCapture()
        } else {
            Toast.makeText(
                this,
                "Izin microphone ditolak - live butuh izin ini untuk audio",
                Toast.LENGTH_LONG
            ).show()
            resetStatusToIdle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        maybeRequestNotificationPermission()
        restoreSavedRtmpFields()

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { onStopClicked() }
    }

    /** Isi ulang kolom RTMP URL & Stream Key dari yang terakhir kali disimpan, kalau ada. */
    private fun restoreSavedRtmpFields() {
        val savedUrl = prefs.getString(KEY_RTMP_URL, null)
        val savedKey = prefs.getString(KEY_STREAM_KEY, null)
        if (!savedUrl.isNullOrBlank()) {
            binding.etRtmpUrl.setText(savedUrl)
        }
        if (!savedKey.isNullOrBlank()) {
            binding.etStreamKey.setText(savedKey)
        }

        when (prefs.getString(KEY_AUDIO_SOURCE, ScreenRecordService.AUDIO_SOURCE_INTERNAL)) {
            ScreenRecordService.AUDIO_SOURCE_MIC -> binding.rgAudioSource.check(R.id.rbAudioMic)
            ScreenRecordService.AUDIO_SOURCE_MIX -> binding.rgAudioSource.check(R.id.rbAudioMix)
            else -> binding.rgAudioSource.check(R.id.rbAudioInternal)
        }
    }

    private fun saveRtmpFields(rtmpUrl: String, streamKey: String) {
        prefs.edit()
            .putString(KEY_RTMP_URL, rtmpUrl)
            .putString(KEY_STREAM_KEY, streamKey)
            .apply()
    }

    private fun onStartClicked() {
        val rtmpUrl = binding.etRtmpUrl.text?.toString()?.trim().orEmpty()
        val streamKey = binding.etStreamKey.text?.toString()?.trim().orEmpty()

        if (rtmpUrl.isEmpty() || streamKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_isi_dulu), Toast.LENGTH_SHORT).show()
            return
        }

        // Simpan supaya lain kali buka aplikasi, kolom ini sudah terisi otomatis.
        saveRtmpFields(rtmpUrl, streamKey)

        // simpan sementara supaya bisa dipakai saat callback izin sukses
        pendingFullRtmpUrl = "$rtmpUrl/$streamKey"
        pendingAudioSource = selectedAudioSourceValue()
        prefs.edit().putString(KEY_AUDIO_SOURCE, pendingAudioSource).apply()

        binding.tvStatus.text = getString(R.string.status_connecting)
        maybeStartLive()
    }

    /** Membaca RadioButton yang dipilih user dan mengubahnya jadi konstanta untuk Service. */
    private fun selectedAudioSourceValue(): String = when (binding.rgAudioSource.checkedRadioButtonId) {
        R.id.rbAudioMic -> ScreenRecordService.AUDIO_SOURCE_MIC
        R.id.rbAudioMix -> ScreenRecordService.AUDIO_SOURCE_MIX
        else -> ScreenRecordService.AUDIO_SOURCE_INTERNAL
    }

    /** Urutan wajib: pastikan izin microphone didapat dulu, baru minta izin capture layar. */
    private fun maybeStartLive() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            proceedToScreenCapture()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun proceedToScreenCapture() {
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun onStopClicked() {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(intent)
        resetStatusToIdle()
    }

    private fun resetStatusToIdle() {
        binding.tvStatus.text = getString(R.string.status_idle)
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
    }

    private fun startStreamingService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_RTMP_URL, pendingFullRtmpUrl)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SOURCE, pendingAudioSource)
        }
        ContextCompat.startForegroundService(this, intent)

        binding.tvStatus.text = getString(R.string.status_live)
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private var pendingFullRtmpUrl: String = ""
        private var pendingAudioSource: String = ScreenRecordService.AUDIO_SOURCE_INTERNAL

        private const val PREFS_NAME = "go_go_live_prefs"
        private const val KEY_RTMP_URL = "rtmp_url"
        private const val KEY_STREAM_KEY = "stream_key"
        private const val KEY_AUDIO_SOURCE = "audio_source"
    }
}
