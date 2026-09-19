# API Reference - Go Go Live

Dokumentasi teknis untuk komponen internal dan interface aplikasi Go Go Live.

---

## SharedPreferences Keys

Aplikasi menyimpan konfigurasi user menggunakan `SharedPreferences` dengan nama file `go_go_live_prefs`.

### Keys yang Digunakan

| Key | Tipe | Default | Deskripsi |
|-----|------|---------|-----------|
| `rtmp_url` | String | null | RTMP server URL (tanpa stream key) |
| `stream_key` | String | null | Stream key YouTube |
| `audio_source` | String | `internal` | Sumber audio: `internal`, `mic`, atau `mix` |
| `fps` | Int | 30 | Frame rate: 30 atau 60 |
| `bitrate` | Int | 3000 | Video bitrate dalam Kbps (500-4000) |
| `resolution` | Int | 480 | Target resolusi: 360, 480, atau 720 |

### Contoh Penggunaan

```kotlin
val prefs = getSharedPreferences("go_go_live_prefs", Context.MODE_PRIVATE)

// Membaca nilai
val rtmpUrl = prefs.getString("rtmp_url", null)
val fps = prefs.getInt("fps", 30)

// Menyimpan nilai
prefs.edit {
    putString("rtmp_url", "rtmp://a.rtmp.youtube.com/live2")
    putInt("fps", 30)
}
```

---

## ScreenRecordService Constants

Konstanta yang digunakan di `ScreenRecordService.kt`.

### Action Strings

```kotlin
companion object {
    const val ACTION_START = "com.gogolive.androidgo.action.START"
    const val ACTION_STOP = "com.gogolive.androidgo.action.STOP"
    const val ACTION_STATE_CHANGED = "com.gogolive.androidgo.action.STATE_CHANGED"
}
```

### Extra Keys

```kotlin
const val EXTRA_RESULT_CODE = "result_code"
const val EXTRA_RESULT_DATA = "result_data"
const val EXTRA_RTMP_URL = "rtmp_url"
const val EXTRA_AUDIO_SOURCE = "audio_source"
const val EXTRA_FPS = "fps"
const val EXTRA_BITRATE = "bitrate"
const val EXTRA_RESOLUTION = "resolution"
```

### Audio Source Values

```kotlin
const val AUDIO_SOURCE_INTERNAL = "internal"  // Audio internal HP (Android 10+)
const val AUDIO_SOURCE_MIC = "mic"           // Microphone eksternal
const val AUDIO_SOURCE_MIX = "mix"           // Kombinasi keduanya
```

### Status Flags (Companion Object)

```kotlin
var isRunning: Boolean = false              // Service sedang aktif
var isStreamingSuccessfully: Boolean = false // Koneksi RTMP stabil
var isAttemptingReconnect: Boolean = false   // Sedang mencoba reconnect
```

---

## Encoding Parameters

### Video Settings

| Parameter | Nilai Default | Rentang Valid | Keterangan |
|-----------|---------------|---------------|------------|
| Codec | H.264 Baseline | - | Profile untuk kompatibilitas maksimal |
| Resolution | 480p | 360p, 480p, 720p | Downscaled proporsional dari resolusi asli |
| FPS | 30 | 30, 60 | Frame per second |
| Bitrate | 3000 Kbps | 500 - 4000 Kbps | Bitrate video |
| GOP Size | 2 detik | - | Keyframe interval |
| Width Alignment | Kelipatan 16 | - | Wajib untuk encoder H.264 |

### Audio Settings

| Parameter | Nilai | Keterangan |
|-----------|-------|------------|
| Codec | AAC | Advanced Audio Coding |
| Sample Rate | 44100 Hz | Kualitas CD |
| Channels | Mono (1) | Mengurangi bandwidth |
| Bitrate | 64 Kbps | Cukup untuk voice |

---

## Broadcast Receiver

### ACTION_STATE_CHANGED

Broadcast yang dikirim saat status streaming berubah.

**Filter:**
```kotlin
IntentFilter(ScreenRecordService.ACTION_STATE_CHANGED)
```

**Status yang Mungkin:**
- `isStreamingSuccessfully = true` → Live stabil
- `isAttemptingReconnect = true` → Sedang reconnect
- `isRunning = true` (tanpa flag lain) → Connecting
- Semua false → Idle

**Contoh Register:**
```kotlin
private val stateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        when {
            ScreenRecordService.isStreamingSuccessfully -> {
                // Update UI ke status LIVE
            }
            ScreenRecordService.isAttemptingReconnect -> {
                // Update UI ke status RECONNECTING
            }
            ScreenRecordService.isRunning -> {
                // Update UI ke status CONNECTING
            }
            else -> {
                // Update UI ke status IDLE
            }
        }
    }
}

// Register di onResume()
registerReceiver(stateReceiver, IntentFilter(ScreenRecordService.ACTION_STATE_CHANGED))

// Unregister di onPause()
unregisterReceiver(stateReceiver)
```

---

## Quick Tile States

Status yang ditampilkan di Quick Settings Tile.

| State | Label | Description |
|-------|-------|-------------|
| INACTIVE | "Live" | Belum mulai live |
| ACTIVE (connecting) | "Connecting..." | Mulai live |
| ACTIVE (reconnecting) | "Reconnecting..." | Koneksi terputus, mencoba lagi |
| ACTIVE (stable) | "Stable" / "Live" | Streaming berjalan normal |

---

## Permission Requirements

### Runtime Permissions

| Permission | Level | Diperlukan Untuk |
|------------|-------|------------------|
| `RECORD_AUDIO` | Dangerous | Menangkap audio (wajib) |
| `POST_NOTIFICATIONS` | Dangerous | Notifikasi (Android 13+) |

### System Dialogs

| Dialog | Trigger | Keterangan |
|--------|---------|------------|
| MediaProjection | Setiap kali mulai live | Tidak bisa di-bypass, requirement Android |
| Audio Permission | Pertama kali / jika ditolak | Standard Android permission dialog |

---

## Error Handling

### Encoder Fallback Logic

Jika encoder menolak setting awal:

```kotlin
if (!genericStream.prepareVideo(width, height, bitrate, fps, gop)) {
    // Fallback 1: Turunkan FPS ke 30 jika awalnya 60
    if (selectedFps > 30) {
        selectedFps = 30
        success = genericStream.prepareVideo(width, height, bitrate, selectedFps, gop)
    }
    
    if (!success) {
        // Stop service dengan error message
        Toast.makeText(context, "Error: HP tidak support resolusi/FPS ini", Toast.LENGTH_LONG).show()
        stopSelf()
    }
}
```

### Reconnect Strategy

```kotlin
// Saat koneksi terputus (onConnectionFailed)
if (reconnectCount < maxReconnectRetries) {
    isAttemptingReconnect = true
    reconnectCount++
    Handler(Looper.getMainLooper()).postDelayed({
        startStream(savedRtmpUrl)  // Coba connect ulang
    }, reconnectDelayMs)  // 5000ms
} else {
    // Max retries exceeded, stop service
    handleStop()
}
```

---

## RootEncoder Library Integration

### Import Statements

```kotlin
import com.pedro.library.generic.GenericStream
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.library.util.BitrateAdapter
```

### ConnectChecker Interface Implementation

```kotlin
class ScreenRecordService : Service(), ConnectChecker {
    
    override fun onConnectionStarted(url: String) {
        // Called saat mulai connect
    }
    
    override fun onConnectionSuccess(reason: String) {
        isStreamingSuccessfully = true
        isAttemptingReconnect = false
        updateNotification()
    }
    
    override fun onConnectionFailed(reason: String) {
        isAttemptingReconnect = true
        // Trigger reconnect logic
    }
    
    override fun onDisconnect() {
        isStreamingSuccessfully = false
        isRunning = false
    }
    
    override fun onAuthError() {
        // Stream key invalid
    }
    
    override fun onAuthSuccess() {
        // Authenticated ke server RTMP
    }
}
```

---

## Notification Configuration

### Channel Setup

```kotlin
private fun createNotificationChannel() {
    val channel = NotificationChannel(
        CHANNEL_ID,
        "Go Go Live Streaming",
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Notifikasi live streaming aktif"
        setShowBadge(false)
    }
    
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(channel)
}
```

### Notification Content

```kotlin
private fun buildNotification(): Notification {
    return NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Go Go Live")
        .setContentText("Sedang melakukan live streaming...")
        .setSmallIcon(R.drawable.ic_tile_live)
        .setOngoing(true)  // Tidak bisa di-swipe away
        .setOnlyAlertOnce(true)
        .setContentIntent(pendingIntent)  // Buka MainActivity
        .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
        .build()
}
```

---

## Resource Locks

### WakeLock & WifiLock

```kotlin
private fun acquireLocks() {
    // Jaga CPU tetap menyala saat screen off
    wakeLock = powerManager.newWakeLock(
        PowerManager.PARTIAL_WAKE_LOCK,
        "GoGoLive::StreamingLock"
    ).apply {
        acquire(10*60*1000L) // Timeout 10 menit (auto-release)
    }
    
    // Jaga WiFi tetap aktif (penting untuk streaming)
    wifiLock = wifiManager.createWifiLock(
        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "GoGoLive::WifiLock"
    ).apply {
        acquire()
    }
}

private fun releaseLocks() {
    wakeLock?.let {
        if (it.isHeld) it.release()
    }
    wifiLock?.let {
        if (it.isHeld) it.release()
    }
}
```

---

## Thread Safety

### Main Handler Usage

Semua operasi UI harus dilakukan di main thread:

```kotlin
private val mainHandler = Handler(Looper.getMainLooper())

// Dari background thread (callback library)
mainHandler.post {
    Toast.makeText(applicationContext, "Message", Toast.LENGTH_SHORT).show()
}

// Delayed execution
mainHandler.postDelayed(runnable, delayMillis)
```

### Periodic Watcher

```kotlin
private fun startPeriodicResolutionWatcher() {
    mainHandler.postDelayed(object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkResolutionAndRestartIfNeeded()
            mainHandler.postDelayed(this, RESOLUTION_WATCH_INTERVAL_MS)
        }
    }, RESOLUTION_WATCH_INTERVAL_MS)
}
```

---

## Version History

### Library Versions

| Component | Version | Notes |
|-----------|---------|-------|
| RootEncoder | 2.7.3 | Versi minimum yang didukung |
| Android Gradle Plugin | 8.13.2 | Required untuk compileSdk 36 |
| Gradle | 8.13 | Wrapper version |
| Compile SDK | 36 | Minimum untuk RootEncoder 2.7.3 |
| Min SDK | 26 | Android Oreo (semua Android Go) |
| Target SDK | 36 | Latest stable |

### Breaking Changes (v2.7.3 vs v2.2.6)

| Old (v2.2.6) | New (v2.7.3) | Migration |
|--------------|--------------|-----------|
| `RtmpDisplay` | `GenericStream` | Ganti class constructor |
| `ConnectCheckerRtmp` | `ConnectChecker` | Interface unified |
| Separate audio/video prepare | Unified prepare methods | API simplification |

---

## Testing Guidelines

### Manual Testing Checklist

- [ ] Izin microphone ditolak → App menampilkan toast
- [ ] Izin screen capture ditolak → Service tidak start
- [ ] RTMP URL kosong → Validasi input
- [ ] Koneksi internet terputus → Auto-reconnect (max 5x)
- [ ] Rotasi layar saat live → Stream restart otomatis
- [ ] Quick Tile toggle → Start/stop berfungsi
- [ ] Settings change → Tersimpan dan diterapkan

### Performance Targets

| Metric | Target | Device Reference |
|--------|--------|------------------|
| Startup time | < 3 detik | Xiaomi Redmi A3 |
| Reconnect delay | ~0.5 detik | - |
| Memory usage | < 100 MB | Android Go |
| Battery drain | Normal foreground service | - |

---

## Security Considerations

### Data Storage

- RTMP URL dan Stream Key disimpan **lokal** di SharedPreferences
- Tidak dikirim ke server manapun
- Dihapus saat uninstall atau "Clear data"

### Foreground Service Requirement

- Notifikasi persisten **tidak dapat disembunyikan**
- Requirement Android untuk MediaProjection + microphone
- User selalu informed saat recording/streaming aktif

### Protected Content

Beberapa aplikasi memproteksi audio mereka dari capture:
- Aplikasi video streaming (Netflix, Disney+, dll)
- Aplikasi musik berlisensi (Spotify, Apple Music)
- Ini adalah fitur keamanan Android, bukan bug aplikasi

---

## Troubleshooting Guide for Developers

### Common Build Errors

**Error: "Unresolved reference: GenericStream"**
```kotlin
// Solution: Alt+Enter di Android Studio untuk auto-import
import com.pedro.library.generic.GenericStream
```

**Error: "AAR metadata requires compileSdk 36"**
```gradle
// Solution: Update compileSdk di app/build.gradle
android {
    compileSdk 36
}
```

**Error: "Gradle sync failed"**
```properties
// Solution: Update gradle-wrapper.properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
```

### Runtime Debugging

```kotlin
// Enable logging di ScreenRecordService
companion object {
    private const val TAG = "GoGoLive-Service"
}

// Log关键 events
Log.d(TAG, "Starting encoding with ${alignedWidth}x${alignedHeight}@${selectedFps}fps")
Log.e(TAG, "Encoder refused settings, attempting fallback")
```

---

## Contact

Untuk pertanyaan teknis lebih lanjut, silakan buka issue di repository GitHub.
