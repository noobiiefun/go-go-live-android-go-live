package com.gogolive.androidgo.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.gogolive.androidgo.R
import com.gogolive.androidgo.databinding.ActivitySettingsBinding
import com.gogolive.androidgo.service.ScreenRecordService

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
    }

    private fun loadSettings() {
        // Load Resolution
        val res = prefs.getInt(KEY_RESOLUTION, 480)
        if (res == 720) binding.rgResolution.check(R.id.rbRes720p)
        else binding.rgResolution.check(R.id.rbRes480p)

        // Load FPS
        if (prefs.getInt(KEY_FPS, 30) == 60) binding.rgFps.check(R.id.rbFps60)
        else binding.rgFps.check(R.id.rbFps30)

        // Load Bitrate
        when (prefs.getInt(KEY_BITRATE, 2000)) {
            500 -> binding.rgBitrate.check(R.id.rbBitrate500)
            1000 -> binding.rgBitrate.check(R.id.rbBitrate1)
            2000 -> binding.rgBitrate.check(R.id.rbBitrate2)
            4000 -> binding.rgBitrate.check(R.id.rbBitrate4)
            else -> binding.rgBitrate.check(R.id.rbBitrate2)
        }

        // Load Audio Source
        when (prefs.getString(KEY_AUDIO_SOURCE, ScreenRecordService.AUDIO_SOURCE_INTERNAL)) {
            ScreenRecordService.AUDIO_SOURCE_MIC -> binding.rgAudioSource.check(R.id.rbAudioMic)
            ScreenRecordService.AUDIO_SOURCE_MIX -> binding.rgAudioSource.check(R.id.rbAudioMix)
            else -> binding.rgAudioSource.check(R.id.rbAudioInternal)
        }
    }

    private fun saveSettings() {
        val resolution = if (binding.rgResolution.checkedRadioButtonId == R.id.rbRes720p) 720 else 480
        val fps = if (binding.rgFps.checkedRadioButtonId == R.id.rbFps60) 60 else 30
        
        val bitrate = when (binding.rgBitrate.checkedRadioButtonId) {
            R.id.rbBitrate500 -> 500
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
