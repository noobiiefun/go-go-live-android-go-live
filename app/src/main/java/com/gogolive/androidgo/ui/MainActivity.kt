package com.gogolive.androidgo.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.gogolive.androidgo.R
import com.gogolive.androidgo.databinding.ActivityMainBinding
import com.gogolive.androidgo.service.ScreenRecordService

/**
 * Activity ini HANYA dipakai untuk:
 *  1. Mengambil input RTMP URL + Stream Key dari user.
 *  2. Meminta izin capture layar (MediaProjection) - dialog sistem, wajib, tidak bisa dilewati.
 *  3. Meminta izin notifikasi (Android 13+).
 *  4. Menyerahkan hasil izin tsb ke ScreenRecordService yang jalan di background.
 *
 * Setelah live dimulai, Activity ini BOLEH ditutup / user pindah ke app lain -
 * proses capture + streaming tetap berjalan di ScreenRecordService (foreground service),
 * BUKAN sebagai overlay/bubble yang mengambang di atas aplikasi lain.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startStreamingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Izin capture layar ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, kita tetap lanjut - notifikasi cuma informatif */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        maybeRequestNotificationPermission()

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { onStopClicked() }
    }

    private fun onStartClicked() {
        val rtmpUrl = binding.etRtmpUrl.text?.toString()?.trim().orEmpty()
        val streamKey = binding.etStreamKey.text?.toString()?.trim().orEmpty()

        if (rtmpUrl.isEmpty() || streamKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_isi_dulu), Toast.LENGTH_SHORT).show()
            return
        }

        // simpan sementara supaya bisa dipakai saat callback izin sukses
        pendingFullRtmpUrl = "$rtmpUrl/$streamKey"

        binding.tvStatus.text = getString(R.string.status_connecting)
        screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun onStopClicked() {
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_STOP
        }
        startService(intent)
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
    }
}
