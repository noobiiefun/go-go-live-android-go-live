# Go Go Live — Android Go Live Streaming

[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://developer.android.com/)
[![Min SDK](https://img.shields.io/badge/min%20SDK-26-orange.svg)](https://developer.android.com/about/versions/oreo/android-8.0)
[![Kotlin](https://img.shields.io/badge/kotlin-1.9+-purple.svg)](https://kotlinlang.org/)

Aplikasi Android sederhana untuk **live streaming seluruh layar ke YouTube Live**, dioptimalkan untuk perangkat **Android Go** seperti Xiaomi Redmi A3.

> 📚 **Dokumentasi Lengkap**: Lihat folder [`docs/`](docs/) untuk panduan detail, API reference, dan kontribusi.

---

## 📋 Ringkasan

| Fitur | Deskripsi |
|-------|-----------|
| 🎯 **Target** | Android Go (RAM rendah) |
| 📹 **Streaming** | RTMP ke YouTube/Facebook/dll |
| 🔊 **Audio** | Internal, Microphone, atau Mix |
| ⚡ **Quick Tile** | Mulai live dari Quick Settings |
| 🔄 **Auto-Rotate** | Support portrait & landscape |
| 🛡️ **Tanpa Overlay** | Tidak pakai "Draw over apps" |
| 💾 **No Recording** | Stream langsung, tidak simpan file |

---

## 🚀 Kenapa Aplikasi Ini Berbeda?

### Tanpa Overlay = Lebih Ringan

Banyak aplikasi screen recorder menggunakan **bubble/overlay** (izin *"Draw over other apps"*) yang memakan RAM ekstra. Go Go Live **tidak** menggunakan overlay sama sekali.

**Arsitektur kami:**

```
┌─────────────────────────────────────┐
│   MainActivity (UI Sementara)       │
│   - Input RTMP URL & Stream Key     │
│   - Request Permissions             │
└─────────────────────────────────────┘
                ↓
┌─────────────────────────────────────┐
│   ScreenRecordService (Foreground)  │
│   - Capture Layar (MediaProjection) │
│   - Encode H.264 + AAC              │
│   - Stream RTMP                     │
│   - Hanya Notifikasi di Status Bar  │
└─────────────────────────────────────┘
                ↓
┌─────────────────────────────────────┐
│   YouTube Live Server               │
└─────────────────────────────────────┘
```

Setelah live dimulai, Anda bisa:
- ✅ Kunci layar
- ✅ Buka aplikasi lain
- ✅ Siaran tetap berjalan di background

Satu-satunya indikator adalah **notifikasi wajib** di status bar (requirement keamanan Android).

---

## 📦 Instalasi & Build

### Prasyarat

- **Android Studio**: Giraffe/Koala atau lebih baru
- **JDK**: 17+
- **Device**: Android 8.0+ (API 26), minimal RAM 2GB

### Langkah Build

```bash
# 1. Clone repository
git clone https://github.com/<username>/go-go-live-android-go-live.git
cd go-go-live-android-go-live

# 2. Buka di Android Studio
# File → Open → Pilih folder project

# 3. Sync Gradle (otomatis download dependencies)

# 4. Run ke device
# Aktifkan USB Debugging dulu
# Klik Run (Shift+F10)
```

> **Catatan**: File `gradle-wrapper.jar` sengaja tidak disertakan di repo. Android Studio akan generate otomatis saat sync pertama kali.

### Build Manual (Command Line)

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (perlu signing config)
./gradlew assembleRelease
```

APK akan ada di `app/build/outputs/apk/`

---

## 🎮 Cara Menggunakan

### 1. Dapatkan RTMP URL & Stream Key

**YouTube:**
1. Buka [YouTube Studio](https://studio.youtube.com)
2. **Buat** → **Live streaming**
3. Salin **Stream URL** (misal: `rtmp://a.rtmp.youtube.com/live2`)
4. Salin **Stream Key** (kode unik channel Anda)

**Platform Lain:**
- Facebook Live, Twitch, dll — semua yang support RTMP

### 2. Konfigurasi Aplikasi

1. Buka **Go Go Live**
2. Masukkan **RTMP URL** di kolom pertama
3. Masukkan **Stream Key** di kolom kedua
4. Pilih **sumber audio**:
   - 🎵 **Audio Internal HP** (disarankan untuk spacedesk)
   - 🎤 **Microphone HP** (untuk komentar suara)
   - 🔀 **Campur** (kombinasi keduanya)

### 3. Mulai Live

1. Tekan tombol **Mulai Live**
2. Setujui dialog izin **capture layar** dari sistem
3. Live dimulai dalam beberapa detik

> ⚠️ **Penting**: Dialog izin capture layar **wajib muncul setiap kali** mulai live — ini proteksi privasi Android, tidak bisa di-bypass.

### 4. Stop Live

Tekan tombol **Stop** di aplikasi atau lewat notifikasi.

---

## ⚙️ Pengaturan Kualitas

Buka menu **Settings** untuk menyesuaikan:

| Parameter | Opsi | Rekomendasi Android Go |
|-----------|------|------------------------|
| **Resolusi** | 360p / 480p / 720p | 480p |
| **FPS** | 30 / 60 | 30 fps |
| **Bitrate** | 500 - 4000 Kbps | 1000-2000 Kbps |
| **Audio Source** | Internal / Mic / Mix | Internal |

### Speed Test Upload

Aplikasi menyediakan speed test untuk rekomendasi bitrate:

1. Buka **Settings**
2. Tekan **Run Speed Test**
3. Lihat hasil dan rekomendasi

| Upload Speed | Resolusi Rekomendasi |
|--------------|---------------------|
| < 0.5 Mbps | Terlalu rendah 😞 |
| 0.5 - 1.5 Mbps | 360p |
| 1.5 - 4.0 Mbps | 480p ✅ |
| > 4.0 Mbps | 720p 🚀 |

---

## 🎯 Quick Settings Tile

Mulai live tanpa buka aplikasi penuh:

### Cara Menambahkan Tile

1. Tarik panel notifikasi ke bawah
2. Ketuk ikon **pensil/edit**
3. Cari tile **"Live"** (icon sinyal)
4. Drag ke area tile aktif

### Cara Menggunakan

- **Tap sekali**: Mulai/stop live
- **Status tile**: 
  - ⚪ Abu-abu = Idle
  - 🔵 Biru = Connecting...
  - 🟢 Hijau = Live (Stable)
  - 🟠 Oranye = Reconnecting...

> 💡 **Tips**: Sangat berguna untuk skenario **spacedesk** (live sambil main game di PC) karena tidak mengganggu orientasi landscape.

---

## 🔧 Troubleshooting

### Live Sering Putus

**Solusi:**
1. Tambahkan aplikasi ke **"Tanpa batasan baterai"** di Pengaturan → Baterai
2. Turunkan bitrate di Settings
3. Pastikan koneksi WiFi stabil

### Video Patah-patah

**Penyebab:** Encoder CPU/GPU kewalahan

**Solusi:**
1. Turunkan FPS ke 30 (atau 25)
2. Turunkan resolusi (480p → 360p)
3. Pastikan ada gerakan terus-menerus di layar (MediaProjection hanya update saat ada perubahan)

### Resolusi Gepeng/Terpotong

Aplikasi sudah support **auto-rotation**. Jika orientasi berubah saat live:
- Stream restart otomatis (~0.5 detik jeda)
- Izin capture dipakai ulang (tidak minta lagi)

**Saran:** Set orientasi yang diinginkan **sebelum** mulai live untuk menghindari jeda.

### Audio Tidak Terdengar

**Kemungkinan:**
1. Sumber audio salah dipilih
2. Aplikasi source memproteksi audio (Netflix, Spotify, dll)

**Solusi:**
- Ganti sumber audio di Settings
- Untuk spacedesk: gunakan **Audio Internal HP**

### Error "SecurityException: Starting FGS with type microphone"

**Penyebab:** Izin RECORD_AUDIO belum diberikan

**Solusi:**
- Berikan izin microphone di Pengaturan → Apps → Go Go Live → Permissions

### Build Error "Unresolved reference: GenericStream"

**Solusi:**
```kotlin
// Alt+Enter di Android Studio untuk auto-import
import com.pedro.library.generic.GenericStream
import com.pedro.common.ConnectChecker
```

Lebih lengkap lihat: [`docs/API_REFERENCE.md`](docs/API_REFERENCE.md)

---

## 📁 Struktur Proyek

```
go-go-live-android-go-live/
├── app/
│   ├── src/main/
│   │   ├── java/com/gogolive/androidgo/
│   │   │   ├── ui/
│   │   │   │   ├── MainActivity.kt          # UI utama
│   │   │   │   ├── SettingsActivity.kt      # Halaman settings
│   │   │   │   └── QuickStartActivity.kt    # Bridge Quick Tile
│   │   │   └── service/
│   │   │       ├── ScreenRecordService.kt   # Core streaming
│   │   │       └── LiveQuickTileService.kt  # Quick Settings
│   │   ├── res/
│   │   │   ├── layout/                      # XML layouts
│   │   │   ├── drawable/                    # Assets
│   │   │   ├── values/                      # Strings, colors
│   │   │   └── mipmap-*/                    # Icons
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── docs/
│   ├── README.md              # Dokumentasi utama
│   ├── API_REFERENCE.md       # Technical details
│   └── CONTRIBUTING.md        # Panduan kontribusi
├── README.md                  # File ini
└── build.gradle
```

---

## 🛠️ Teknologi

| Komponen | Versi | Keterangan |
|----------|-------|------------|
| **Language** | Kotlin 1.9+ | Modern Android development |
| **Min SDK** | 26 | Android Oreo (semua Android Go) |
| **Target SDK** | 36 | Latest stable |
| **Gradle** | 8.13 | Build system |
| **AGP** | 8.13.2 | Android Gradle Plugin |
| **RootEncoder** | 2.7.3 | RTMP streaming library |

### Library Dependencies

```gradle
// RTMP Streaming
implementation 'com.github.pedroSG94:RootEncoder:2.7.3'

// AndroidX
implementation 'androidx.core:core-ktx:1.13.+'
implementation 'androidx.appcompat:appcompat:1.7.+'
implementation 'com.google.android.material:material:1.12.+'

// ViewBinding
viewBinding {
    enabled = true
}
```

---

## 🎨 Customization

### Ganti Icon & Branding

Lihat panduan lengkap di bagian **[Mengganti Icon & Branding](#mengganti-icon--branding)** di bawah.

### Ubah Warna Tema

Edit `app/src/main/res/values/colors.xml`:

```xml
<color name="primary">#0F5ED3</color>     <!-- Warna utama -->
<color name="accent">#FC3B49</color>      <!-- Tombol Stop -->
<color name="splash_background">#FFFFFF</color>
```

---

## 🤝 Berkontribusi

Kami sangat terbuka untuk kontribusi! Lihat panduan lengkap di:

👉 **[docs/CONTRIBUTING.md](docs/CONTRIBUTING.md)**

### Cara Cepat Berkontribusi

1. **Fork** repository ini
2. Buat branch baru (`git checkout -b feature/amazing-feature`)
3. Commit perubahan (`git commit -m 'feat: add amazing feature'`)
4. Push (`git push origin feature/amazing-feature`)
5. Buka **Pull Request**

### Yang Kami Butuhkan

- 🐛 Laporan bug
- 💡 Ide fitur baru
- 📝 Perbaikan dokumentasi
- 🔧 Code improvements
- 🌍 Terjemahan bahasa lain

---

## ❓ FAQ

**Q: Apakah aplikasi menyimpan rekaman video?**  
A: **Tidak.** Semua frame di-encode di RAM dan langsung dikirim via RTMP. Tidak ada file video yang disimpan.

**Q: Bisakah streaming ke selain YouTube?**  
A: **Ya**, selama platform tersebut support RTMP (Facebook, Twitch, custom server, dll).

**Q: Kenapa notifikasi "sedang live" tidak bisa dihilangkan?**  
A: Ini requirement keamanan Android untuk semua foreground service yang menggunakan MediaProjection. **Tidak bisa di-bypass** oleh aplikasi manapun.

**Q: Apakah perlu root?**  
A: **Tidak.** Aplikasi bekerja tanpa root access.

**Q: Support Android versi berapa?**  
A: Minimal **Android 8.0 (Oreo/API 26)**. Semua perangkat Android Go sudah memenuhi requirement ini.

**Q: Kenapa dialog izin capture layar muncul setiap kali?**  
A: Ini proteksi privasi Android. Setiap aplikasi yang ingin capture layar **wajib** minta izin user setiap sesi. Tidak ada cara legal untuk bypass ini.

---

## 🎨 Mengganti Icon & Branding

### Persiapan File

Siapkan 1-2 file PNG:

| File | Ukuran | Keterangan |
|------|--------|------------|
| Icon aplikasi | 1024×1024 px | Latar transparan, kasih padding ~15% |
| Logo splash | 512×512 px | Bisa sama dengan icon |

### Cara 1: Pakai Android Studio (Direkomendasikan)

1. Klik kanan folder `app` di Android Studio
2. **New → Image Asset**
3. Pilih source file PNG kamu
4. Android Studio auto-generate semua ukuran yang dibutuhkan
5. Klik **Finish**

### Cara 2: Manual

**Ganti Icon:**
1. Hapus file lama di `app/src/main/res/drawable/ic_launcher_foreground.xml`
2. Taruh file PNG kamu dengan nama `ic_launcher_foreground.png` di folder yang sama
3. Update `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` jika perlu

**Ganti Splash Screen:**
1. Hapus `app/src/main/res/drawable/ic_logo_splash.xml`
2. Taruh file logo kamu dengan nama `ic_logo_splash.png` di folder yang sama
3. Ganti warna background di `colors.xml` (`splash_background`)

**Ubah Warna Tema:**
```xml
<!-- colors.xml -->
<color name="primary">#0F5ED3</color>      <!-- Biru -->
<color name="accent">#FC3B49</color>       <!-- Merah -->
<color name="splash_background">#FFFFFF</color>
```

File lockup lengkap tersedia di:  
`app/src/main/res/drawable/ic_full_logo_lockup.png`

---

## 📄 Lisensi

Proyek ini dibuat untuk keperluan pembelajaran dan penggunaan pribadi.

Silakan digunakan, dimodifikasi, dan didistribusikan sesuai kebutuhan.

---

## 📞 Kontak & Support

- 📧 **Email**: [Tambahkan email jika ada]
- 🐛 **Bug Report**: [GitHub Issues](https://github.com/<username>/go-go-live-android-go-live/issues)
- 💬 **Diskusi**: [GitHub Discussions](https://github.com/<username>/go-go-live-android-go-live/discussions)

---

## 🙏 Acknowledgments

- **[RootEncoder](https://github.com/pedroSG94/RootEncoder)** oleh Pedro Sánchez — Library RTMP streaming yang powerful
- **[Android Go](https://www.android.com/go/)** — Platform yang memungkinkan device entry-level tetap produktif
- **Komunitas Android Developer** — Dukungan dan inspirasi tanpa henti

---

<div align="center">

**Made with ❤️ for Android Go users**

⭐ Jika proyek ini membantu, jangan lupa beri bintang!

</div>
