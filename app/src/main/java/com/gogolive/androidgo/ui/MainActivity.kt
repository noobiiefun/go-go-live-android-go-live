package com.gogolive.androidgo.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.gogolive.androidgo.R
import com.gogolive.androidgo.databinding.ActivityMainBinding
import com.gogolive.androidgo.service.ScreenRecordService
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var prefs: SharedPreferences

    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateStatusText()
        }
    }

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startStreamingService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Izin capture layar ditolak", Toast.LENGTH_SHORT).show()
            resetStatusToIdle()
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            proceedToScreenCapture()
        } else {
            Toast.makeText(this, "Izin microphone ditolak", Toast.LENGTH_LONG).show()
            resetStatusToIdle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        maybeRequestNotificationPermission()
        restoreSavedRtmpFields()

        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener { onStopClicked() }
        binding.btnSave.setOnClickListener { onSaveClicked() }
        binding.btnOpenSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(ScreenRecordService.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
        updateStatusText()
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }

    private fun updateStatusText() {
        when {
            ScreenRecordService.isStreamingSuccessfully -> {
                binding.tvStatus.text = getString(R.string.status_live)
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = true
            }
            ScreenRecordService.isAttemptingReconnect -> {
                binding.tvStatus.text = getString(R.string.status_reconnecting)
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = true
            }
            ScreenRecordService.isRunning -> {
                binding.tvStatus.text = getString(R.string.status_connecting)
                binding.btnStart.isEnabled = false
                binding.btnStop.isEnabled = true
            }
            else -> {
                resetStatusToIdle()
            }
        }
    }

    private fun onSaveClicked() {
        val rtmpUrl = binding.etRtmpUrl.text?.toString()?.trim().orEmpty()
        val streamKey = binding.etStreamKey.text?.toString()?.trim().orEmpty()

        if (rtmpUrl.isEmpty() || streamKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_isi_dulu), Toast.LENGTH_SHORT).show()
            return
        }

        saveRtmpFields(rtmpUrl, streamKey)
        Toast.makeText(this, getString(R.string.toast_saved), Toast.LENGTH_SHORT).show()
    }

    private fun restoreSavedRtmpFields() {
        val savedUrl = prefs.getString(KEY_RTMP_URL, null)
        val savedKey = prefs.getString(KEY_STREAM_KEY, null)
        if (!savedUrl.isNullOrBlank()) binding.etRtmpUrl.setText(savedUrl)
        if (!savedKey.isNullOrBlank()) binding.etStreamKey.setText(savedKey)
    }

    private fun saveRtmpFields(rtmpUrl: String, streamKey: String) {
        prefs.edit {
            putString(KEY_RTMP_URL, rtmpUrl)
            putString(KEY_STREAM_KEY, streamKey)
        }
    }

    private fun onStartClicked() {
        val rtmpUrl = binding.etRtmpUrl.text?.toString()?.trim().orEmpty()
        val streamKey = binding.etStreamKey.text?.toString()?.trim().orEmpty()

        if (rtmpUrl.isEmpty() || streamKey.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_isi_dulu), Toast.LENGTH_SHORT).show()
            return
        }

        saveRtmpFields(rtmpUrl, streamKey)

        val cleanUrl = rtmpUrl.removeSuffix("/")
        pendingFullRtmpUrl = "$cleanUrl/$streamKey"
        
        binding.tvStatus.text = getString(R.string.status_connecting)
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true // Izinkan Stop saat sedang connecting
        
        maybeStartLive()
    }

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
        startService(
            Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            }
        )
        resetStatusToIdle()
    }

    private fun resetStatusToIdle() {
        if (!ScreenRecordService.isRunning) {
            binding.tvStatus.text = getString(R.string.status_idle)
            binding.btnStart.isEnabled = true
            binding.btnStop.isEnabled = false
        }
    }

    private fun startStreamingService(resultCode: Int, data: Intent) {
        // Ambil pengaturan terbaru dari SharedPreferences
        val audioSource = prefs.getString(KEY_AUDIO_SOURCE, ScreenRecordService.AUDIO_SOURCE_INTERNAL)
        val fps = prefs.getInt(KEY_FPS, 30)
        val bitrate = prefs.getInt(KEY_BITRATE, 3000)
        val resolution = prefs.getInt(KEY_RESOLUTION, 480)

        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_RTMP_URL, pendingFullRtmpUrl)
            putExtra(ScreenRecordService.EXTRA_AUDIO_SOURCE, audioSource)
            putExtra(ScreenRecordService.EXTRA_FPS, fps)
            putExtra(ScreenRecordService.EXTRA_BITRATE, bitrate)
            putExtra(ScreenRecordService.EXTRA_RESOLUTION, resolution)
        }
        ContextCompat.startForegroundService(this, intent)

        binding.tvStatus.text = getString(R.string.status_live)
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private var pendingFullRtmpUrl: String = ""

        private const val PREFS_NAME = "go_go_live_prefs"
        private const val KEY_RTMP_URL = "rtmp_url"
        private const val KEY_STREAM_KEY = "stream_key"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_RESOLUTION = "resolution"
    }
}
