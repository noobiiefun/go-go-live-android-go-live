package com.gogolive.androidgo.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.gogolive.androidgo.R
import com.gogolive.androidgo.databinding.ActivitySettingsBinding
import com.gogolive.androidgo.service.ScreenRecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.system.measureTimeMillis

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()

        binding.btnBack.setOnClickListener {
            saveSettings()
            finish()
        }

        binding.btnSpeedTest.setOnClickListener {
            runSpeedTest()
        }
    }

    private fun runSpeedTest() {
        binding.btnSpeedTest.isEnabled = false
        binding.tvSpeedResult.text = getString(R.string.speedtest_running)

        lifecycleScope.launch(Dispatchers.IO) {
            val speedMbps = performUploadTest()
            withContext(Dispatchers.Main) {
                val recommendation = when {
                    speedMbps < 0.5 -> getString(R.string.rec_low)
                    speedMbps < 1.5 -> getString(R.string.rec_360)
                    speedMbps < 4.0 -> getString(R.string.rec_480)
                    else -> getString(R.string.rec_720)
                }
                binding.tvSpeedResult.text = getString(R.string.speedtest_result, speedMbps, recommendation)
                binding.btnSpeedTest.isEnabled = true
            }
        }
    }

    private fun performUploadTest(): Double {
        // Upload 1MB of dummy data to a common endpoint
        val dataSize = 1 * 1024 * 1024 
        val dummyData = ByteArray(dataSize) { 0 }
        
        return try {
            val url = URL("https://httpbin.org/post")
            var bytesUploaded = 0L
            val timeTaken = measureTimeMillis {
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.doOutput = true
                    conn.requestMethod = "POST"
                    conn.setFixedLengthStreamingMode(dataSize)
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    
                    conn.outputStream.use { it.write(dummyData) }
                    if (conn.responseCode == 200) {
                        bytesUploaded = dataSize.toLong()
                    }
                } finally {
                    conn.disconnect()
                }
            }
            
            if (timeTaken > 0 && bytesUploaded > 0) {
                (bytesUploaded * 8.0 / 1_000_000.0) / (timeTaken / 1000.0)
            } else 0.0
        } catch (e: Exception) {
            0.0
        }
    }

    private fun loadSettings() {
        // Load Resolution
        when (prefs.getInt(KEY_RESOLUTION, 480)) {
            720 -> binding.rgResolution.check(R.id.rbRes720p)
            480 -> binding.rgResolution.check(R.id.rbRes480p)
            360 -> binding.rgResolution.check(R.id.rbRes360p)
            else -> binding.rgResolution.check(R.id.rbRes480p)
        }

        // Load FPS
        if (prefs.getInt(KEY_FPS, 30) == 60) binding.rgFps.check(R.id.rbFps60)
        else binding.rgFps.check(R.id.rbFps30)

        // Load Bitrate
        when (prefs.getInt(KEY_BITRATE, 1000)) {
            500 -> binding.rgBitrate.check(R.id.rbBitrate500)
            800 -> binding.rgBitrate.check(R.id.rbBitrate800)
            1000 -> binding.rgBitrate.check(R.id.rbBitrate1)
            2000 -> binding.rgBitrate.check(R.id.rbBitrate2)
            4000 -> binding.rgBitrate.check(R.id.rbBitrate4)
            else -> binding.rgBitrate.check(R.id.rbBitrate1)
        }

        // Load Audio Source
        when (prefs.getString(KEY_AUDIO_SOURCE, ScreenRecordService.AUDIO_SOURCE_INTERNAL)) {
            ScreenRecordService.AUDIO_SOURCE_MIC -> binding.rgAudioSource.check(R.id.rbAudioMic)
            ScreenRecordService.AUDIO_SOURCE_MIX -> binding.rgAudioSource.check(R.id.rbAudioMix)
            else -> binding.rgAudioSource.check(R.id.rbAudioInternal)
        }
    }

    private fun saveSettings() {
        val resolution = when (binding.rgResolution.checkedRadioButtonId) {
            R.id.rbRes720p -> 720
            R.id.rbRes360p -> 360
            else -> 480
        }
        val fps = if (binding.rgFps.checkedRadioButtonId == R.id.rbFps60) 60 else 30
        
        val bitrate = when (binding.rgBitrate.checkedRadioButtonId) {
            R.id.rbBitrate500 -> 500
            R.id.rbBitrate800 -> 800
            R.id.rbBitrate1 -> 1000
            R.id.rbBitrate2 -> 2000
            R.id.rbBitrate4 -> 4000
            else -> 3000
        }

        val audioSource = when (binding.rgAudioSource.checkedRadioButtonId) {
            R.id.rbAudioMic -> ScreenRecordService.AUDIO_SOURCE_MIC
            R.id.rbAudioMix -> ScreenRecordService.AUDIO_SOURCE_MIX
            else -> ScreenRecordService.AUDIO_SOURCE_INTERNAL
        }

        prefs.edit {
            putInt(KEY_RESOLUTION, resolution)
            putInt(KEY_FPS, fps)
            putInt(KEY_BITRATE, bitrate)
            putString(KEY_AUDIO_SOURCE, audioSource)
        }
    }

    companion object {
        private const val PREFS_NAME = "go_go_live_prefs"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_FPS = "fps"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_RESOLUTION = "resolution"
    }
}
