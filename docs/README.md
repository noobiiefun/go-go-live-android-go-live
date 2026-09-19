# Dokumentasi Go Go Live

Dokumentasi lengkap untuk aplikasi **Go Go Live** — solusi live streaming layar Android yang dioptimalkan untuk perangkat Android Go.

## Daftar Isi

1. [Ringkasan Proyek](#ringkasan-proyek)
2. [Arsitektur Aplikasi](#arsitektur-aplikasi)
3. [Panduan Pengguna](#panduan-pengguna)
4. [Panduan Pengembang](#panduan-pengembang)
5. [Struktur Kode](#struktur-kode)
6. [Konfigurasi Teknis](#konfigurasi-teknis)
7. [Pemecahan Masalah](#pemecahan-masalah)

---

## Ringkasan Proyek

**Go Go Live** adalah aplikasi Android sederhana untuk live streaming seluruh layar ke YouTube Live, dioptimalkan khusus untuk perangkat **Android Go** seperti Xiaomi Redmi A3.

### Keunggulan Utama

- **Tanpa Overlay**: Tidak menggunakan bubble/overlay yang memakan RAM ekstra
- **Background Streaming**: Siaran tetap berjalan meski Anda membuka aplikasi lain
- **Optimized for Android Go**: Konfigurasi default disesuaikan untuk perangkat spesifikasi rendah
- **Quick Settings Tile**: Mulai/stop live langsung dari panel notifikasi
- **Auto-Rotation Support**: Mendukung perubahan orientasi layar secara otomatis

---

## Arsitektur Aplikasi

### Desain Tanpa Overlay

Berbeda dengan aplikasi screen recorder lain yang menggunakan izin *"Draw over other apps"*, Go Go Live menggunakan arsitektur yang lebih ringan:

```
┌─────────────────────────────────────────────────────────┐
│                    User Interface                        │
│                   (MainActivity.kt)                      │
│  - Input RTMP URL & Stream Key                           │
│  - Permission Request (Audio, Screen Capture)            │
│  - Status Display                                        │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│               Foreground Service                         │
│            (ScreenRecordService.kt)                      │
│  - Screen Capture (MediaProjection)                      │
│  - Video Encoding (H.264)                                │
│  - Audio Encoding (AAC)                                  │
│  - RTMP Streaming                                        │
│  - Auto-Reconnect Logic                                  │
│  - Orientation Change Handler                            │
└─────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────┐
│                  YouTube Live Server                     │
│              (via RTMP Protocol)                         │
└─────────────────────────────────────────────────────────┘
```

### Komponen Utama

| Komponen | Tipe | Deskripsi |
|----------|------|-----------|
| `MainActivity` | Activity | UI utama untuk input konfigurasi dan kontrol live |
| `SettingsActivity` | Activity | Halaman pengaturan resolusi, FPS, bitrate, dan audio |
| `QuickStartActivity` | Activity | Activity transparan untuk Quick Tile (tanpa UI) |
| `ScreenRecordService` | Service | Core service untuk capture dan streaming |
| `LiveQuickTileService` | TileService | Tile di Quick Settings panel |

---

## Panduan Pengguna

### Persiapan Sebelum Live

#### 1. Dapatkan RTMP URL dan Stream Key dari YouTube

1. Buka [YouTube Studio](https://studio.youtube.com)
2. Klik **Buat** → **Live streaming**
3. Salin **Stream URL** (biasanya `rtmp://a.rtmp.youtube.com/live2`)
4. Salin **Stream Key** (kode unik untuk channel Anda)

#### 2. Konfigurasi Aplikasi

1. Buka aplikasi Go Go Live
2. Masukkan **RTMP URL** di kolom pertama
3. Masukkan **Stream Key** di kolom kedua
4. Pilih sumber audio yang diinginkan:
   - **Audio Internal HP**: Untuk menangkap suara dari sistem (disarankan untuk spacedesk)
   - **Microphone HP**: Untuk komentar suara Anda
   - **Campur**: Kombinasi keduanya

#### 3. Mulai Live

1. Tekan tombol **Mulai Live**
2. Setujui dialog izin capture layar dari sistem Android
3. Live akan dimulai dalam beberapa detik

> **Catatan**: Notifikasi "sedang live" di status bar tidak dapat disembunyikan — ini adalah kebijakan keamanan Android.

### Menggunakan Quick Settings Tile

Untuk mulai live tanpa membuka aplikasi:

1. Tarik panel notifikasi ke bawah
2. Ketuk ikon pensil/edit
3. Cari tile **"Live"** dan tambahkan ke area aktif
4. Sekarang Anda bisa mulai/stop live langsung dari Quick Settings

### Mengatur Kualitas Streaming

Buka **Settings** untuk menyesuaikan:

| Pengaturan | Opsi | Rekomendasi |
|------------|------|-------------|
| Resolusi | 360p / 480p / 720p | 480p untuk Android Go |
| FPS | 30 / 60 | 30 fps untuk stabilitas |
| Bitrate | 500 - 4000 Kbps | Sesuaikan dengan upload speed |
| Audio Source | Internal / Mic / Mix | Internal untuk spacedesk |

### Speed Test Upload

Aplikasi menyediakan fitur speed test untuk membantu menentukan bitrate optimal:

1. Buka **Settings**
2. Tekan **Run Speed Test**
3. Lihat rekomendasi bitrate berdasarkan hasil

| Upload Speed | Rekomendasi |
|--------------|-------------|
| < 0.5 Mbps | Terlalu rendah untuk live |
| 0.5 - 1.5 Mbps | Gunakan 360p |
| 1.5 - 4.0 Mbps | Gunakan 480p |
| > 4.0 Mbps | Bisa 720p |

---

## Panduan Pengembang

### Prasyarat Development

- **Android Studio**: Giraffe/Koala atau lebih baru
- **Gradle**: 8.13
- **Android Gradle Plugin**: 8.13.2
- **Compile SDK**: 36
- **Min SDK**: 26 (Android Oreo)

### Setup Project

```bash
# Clone repository
git clone https://github.com/<username>/go-go-live-android-go-live.git
cd go-go-live-android-go-live

# Buka di Android Studio
# Biarkan Gradle sync otomatis
# Jalankan ke device (Xiaomi Redmi A3 atau Android Go lainnya)
```

### Struktur Folder

```
app/src/main/
├── java/com/gogolive/androidgo/
│   ├── ui/
│   │   ├── MainActivity.kt          # UI utama & permission handling
│   │   ├── SettingsActivity.kt      # Halaman pengaturan
│   │   └── QuickStartActivity.kt    # Bridge untuk Quick Tile
│   └── service/
│       ├── ScreenRecordService.kt   # Core streaming service
│       └── LiveQuickTileService.kt  # Quick Settings integration
├── res/
│   ├── layout/                      # XML layouts
│   ├── drawable/                    # Assets grafis
│   ├── values/                      # Strings, colors, themes
│   └── mipmap-*/                    App icons
└── AndroidManifest.xml              # Permissions & components
```

### Library Dependencies

Proyek ini menggunakan **[RootEncoder](https://github.com/pedroSG94/RootEncoder)** versi 2.7.3:

```gradle
implementation 'com.github.pedroSG94.RootEncoder:library:2.7.3'
```

Komponen yang digunakan:
- `GenericStream`: Core RTMP streaming
- `ScreenSource`: Video capture dari MediaProjection
- `MicrophoneSource` / `InternalAudioSource` / `MixAudioSource`: Audio capture
- `ConnectChecker`: Callback connection status
- `BitrateAdapter`: Adaptive bitrate adjustment

### Build Configuration

**Root `build.gradle`:**
```gradle
plugins {
    id 'com.android.application' version '8.13.2' apply false
}
```

**App `build.gradle`:**
```gradle
android {
    compileSdk 36
    defaultConfig {
        minSdk 26
        targetSdk 36
    }
}
```

**Gradle Wrapper (`gradle-wrapper.properties`):**
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.13-bin.zip
```

---

## Struktur Kode

### MainActivity.kt

**Tanggung Jawab:**
- Menampilkan UI input RTMP URL dan Stream Key
- Meminta izin runtime (RECORD_AUDIO, POST_NOTIFICATIONS)
- Meluncurkan dialog izin MediaProjection
- Menyimpan konfigurasi ke SharedPreferences
- Memonitor status service via BroadcastReceiver

**Alur Kerja:**
```kotlin
onCreate() → restoreSavedRtmpFields()
onStartClicked() → maybeStartLive() → proceedToScreenCapture()
→ startStreamingService() → ContextCompat.startForegroundService()
```

**SharedPreferences Keys:**
- `KEY_RTMP_URL`: URL RTMP server
- `KEY_STREAM_KEY`: Stream key YouTube
- `KEY_AUDIO_SOURCE`: Sumber audio (internal/mic/mix)
- `KEY_FPS`: Frame rate (30/60)
- `KEY_BITRATE`: Bitrate video (Kbps)
- `KEY_RESOLUTION`: Resolusi target (360/480/720)

### ScreenRecordService.kt

**Tanggung Jawab:**
- Capture layar via MediaProjection API
- Encode video H.264 + audio AAC
- Streaming RTMP ke YouTube
- Handle auto-reconnect saat koneksi terputus
- Deteksi dan handle perubahan orientasi layar
- Manajemen resource locks (WakeLock, WifiLock)

**Fitur Kunci:**

#### 1. Auto-Reconnect Logic
```kotlin
private val maxReconnectRetries = 5
private val reconnectDelayMs = 5000L
```
Service akan mencoba reconnect hingga 5 kali dengan delay 5 detik jika koneksi terputus.

#### 2. Orientation Change Handler
```kotlin
override fun onConfigurationChanged(newConfig: Configuration?) {
    // Stop stream lama → Restart dengan resolusi baru
    // MediaProjection token dipakai ulang (tidak minta izin lagi)
}
```

#### 3. Resolution Downscaling
```kotlin
when (savedResolution) {
    360 → targetWidth = 360, proporsional height
    480 → targetWidth = 480, proporsional height
    else → resolusi asli layar
}
// Output encoder harus kelipatan 16
val alignedWidth = (targetWidth / 16) * 16
```

#### 4. Resource Locks
```kotlin
private fun acquireLocks() {
    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
    wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)
}
```
Mencegah Android Go mematikan service secara agresif.

### QuickStartActivity.kt

**Tanggung Jawab:**
- Activity transparan tanpa UI
- Dipicu oleh LiveQuickTileService
- Meminta izin audio dan screen capture
- Meluncurkan ScreenRecordService dengan konfigurasi tersimpan

**Kenapa Perlu Activity Terpisah?**
- MainActivity memiliki UI penuh yang bisa mengganggu orientasi layar
- QuickStartActivity menggunakan tema `Translucent.NoTitleBar` sehingga tidak menggambar apa-apa
- Penting untuk skenario spacedesk di mana orientasi landscape harus dipertahankan

### LiveQuickTileService.kt

**Tanggung Jawab:**
- Menyediakan tile di Quick Settings panel
- Toggle start/stop live
- Update status tile (inactive/connecting/active/reconnecting)

**Cara Kerja:**
```kotlin
onClick() → if (isRunning) stopLive() else startLiveViaTrampoline()
startLiveViaTrampoline() → startActivity(QuickStartActivity)
```

---

## Konfigurasi Teknis

### Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

### Service Declaration

```xml
<service
    android:name=".service.ScreenRecordService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection|microphone" />

<service
    android:name=".service.LiveQuickTileService"
    android:exported="true"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE" />
    </intent-filter>
</service>
```

### Default Encoding Settings

| Parameter | Default Value | Keterangan |
|-----------|---------------|------------|
| Video Codec | H.264 Baseline Profile | Untuk kompatibilitas maksimal |
| Audio Codec | AAC | 44100Hz, Mono, 64Kbps |
| Resolution | 480p (default) | Dapat diubah di Settings |
| FPS | 30 (default) | Dapat diubah ke 60 |
| Bitrate | 3000 Kbps (default) | Disesuaikan dengan upload speed |
| GOP Size | 2 detik | Keyframe interval |
| Reconnect Retries | 5 kali | Dengan delay 5 detik |

### Notification Channel

Service membuat notification channel dengan prioritas tinggi untuk memastikan notifikasi selalu terlihat sesuai requirement Android untuk foreground service.

---

## Pemecahan Masalah

### Live Sering Putus/Terputus

**Penyebab:**
- Upload speed tidak stabil
- Android Go membunuh service secara agresif

**Solusi:**
1. Tambahkan aplikasi ke daftar **"Tanpa batasan baterai"** di Pengaturan → Baterai
2. Turunkan bitrate di Settings
3. Pastikan WiFi lock aktif (otomatis di service)

### Video Patah-patah (Frame Drop)

**Penyebab:**
- Encoder CPU/GPU kewalahan
- FPS terlalu tinggi untuk device

**Solusi:**
1. Turunkan FPS ke 30 (atau 25 jika masih patah)
2. Turunkan resolusi (misal dari 480p ke 360p)
3. Pastikan ada gerakan terus-menerus di layar (MediaProjection hanya menghasilkan frame baru saat ada perubahan)

### Resolusi Video Gepeng/Terpotong

**Penyebab:**
- Perubahan orientasi tidak tertangani dengan baik

**Solusi:**
- Aplikasi sudah mendukung auto-rotation dengan restart stream otomatis
- Untuk hasil terbaik, set orientasi yang diinginkan **sebelum** memulai live

### Audio Tidak Terdengar

**Penyebab:**
- Sumber audio salah dipilih
- Aplikasi source menandai audio sebagai "protected content"

**Solusi:**
1. Coba ganti sumber audio di Settings:
   - Internal → untuk suara sistem/spacedesk
   - Microphone → untuk komentar suara
   - Mix → kombinasi keduanya
2. Beberapa aplikasi (video/music streaming) memproteksi audio mereka — ini keterbatasan Android, bukan bug aplikasi

### Error "SecurityException: Starting FGS with type microphone"

**Penyebab:**
- Izin RECORD_AUDIO belum diberikan

**Solusi:**
- Aplikasi akan otomatis meminta izin microphone sebelum mulai live
- Jika ditolak, berikan izin manual di Pengaturan → Apps → Go Go Live → Permissions

### Build Error "Unresolved reference: net" atau "ConnectCheckerRtmp"

**Penyebab:**
- Versi RootEncoder lama (2.2.6) tidak kompatibel

**Solusi:**
- Pastikan menggunakan RootEncoder 2.7.3 di `app/build.gradle`
- Import class yang benar:
  ```kotlin
  import com.pedro.library.generic.GenericStream
  import com.pedro.common.ConnectChecker
  ```
- Jika masih error, klik `Alt+Enter` pada class yang bermasalah di Android Studio

### Gradle Sync Gagal

**Penyebab:**
- Versi Gradle/AGP tidak cocok

**Solusi:**
- Pastikan `gradle-wrapper.properties` menggunakan Gradle 8.13
- Pastikan root `build.gradle` menggunakan AGP 8.13.2
- Invalidate caches: File → Invalidate Caches / Restart

---

## FAQ

**Q: Apakah aplikasi ini menyimpan rekaman video?**  
A: Tidak. Semua frame di-encode di RAM dan langsung dikirim via RTMP. Tidak ada file video yang disimpan ke storage.

**Q: Bisakah saya mengganti icon dan splash screen?**  
A: Ya. Lihat panduan di README.md bagian "Mengganti icon aplikasi & splash screen".

**Q: Kenapa tidak bisa menghilangkan notifikasi "sedang live"?**  
A: Ini adalah requirement keamanan Android untuk semua foreground service yang menggunakan MediaProjection. Tidak bisa di-bypass.

**Q: Apakah support streaming ke platform selain YouTube?**  
A: Ya, selama platform tersebut mendukung protokol RTMP. Cukup masukkan RTMP URL dan stream key dari platform tujuan.

**Q: Apakah perlu root?**  
A: Tidak. Aplikasi bekerja tanpa root access.

---

## Lisensi

Proyek ini dibuat untuk keperluan pembelajaran dan penggunaan pribadi.

## Kontak & Support

Untuk pertanyaan atau issue, silakan buka issue di repository GitHub.
