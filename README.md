# Go Go Live — Android Go Live Streaming (background, bukan overlay)

Aplikasi Android sederhana untuk **live streaming seluruh layar ke YouTube Live**,
dioptimalkan untuk perangkat **Android Go** seperti Xiaomi Redmi A3.

## Kenapa ini "berjalan di belakang layar", bukan overlay?

Banyak aplikasi screen recorder pakai **bubble/overlay** (izin *"Draw over other apps"*)
supaya ada tombol mengambang. Itu memakan RAM ekstra dan kurang cocok untuk Android Go.

Proyek ini sengaja **tidak** memakai overlay. Arsitekturnya:

1. `MainActivity` cuma dipakai sebentar: ambil RTMP URL + Stream Key, minta izin
   capture layar (`MediaProjection`), lalu **menyerahkan tugas ke Service**.
2. `ScreenRecordService` adalah **foreground service** biasa — tidak menggambar
   apa pun di layar. Satu-satunya yang terlihat adalah **notifikasi wajib** di
   status bar (aturan keamanan Android supaya user selalu tahu layarnya sedang
   direkam/disiarkan — ini tidak bisa dihilangkan oleh aplikasi manapun, termasuk
   Mobizen/AZ Screen Recorder dll).
3. Setelah live jalan, kamu bisa kunci layar / buka aplikasi lain, siaran tetap
   jalan karena diproses oleh Service, bukan oleh Activity yang mengambang.

## Kenapa dialog izin capture layar tetap muncul?

Android **mewajibkan** dialog sistem "Mulai merekam atau mentransmisikan dengan
[App]?" setiap kali aplikasi memakai `MediaProjection`. Ini tidak bisa dilewati
atau dijalankan otomatis tanpa persetujuan user (ini proteksi privasi Android,
berlaku untuk semua aplikasi termasuk yang ada di Play Store).

## Struktur proyek

```
app/src/main/java/com/gogolive/androidgo/
├── ui/MainActivity.kt          # input RTMP URL/Key + minta izin
└── service/ScreenRecordService.kt  # capture layar + encode + kirim RTMP (foreground service)
```

Library yang dipakai: [RootEncoder](https://github.com/pedroSG94/RootEncoder)
(`RtmpDisplay`) — meng-encode video H264 + audio AAC dari `MediaProjection` lalu
mengirimkannya via protokol RTMP, tanpa perlu root.

## Cara build

1. Buka folder ini dengan **Android Studio** (Giraffe/Koala ke atas).
2. Biarkan Android Studio membuat ulang Gradle Wrapper jar (`gradle/wrapper/gradle-wrapper.jar`)
   secara otomatis saat sync pertama kali (file jar binernya sengaja tidak
   disertakan di repo ini, cukup `gradle-wrapper.properties` yang sudah ada).
3. Sync Gradle → Run ke perangkat Xiaomi Redmi A3 (aktifkan USB debugging dulu).

## Cara dapat RTMP URL + Stream Key YouTube

1. Buka [YouTube Studio](https://studio.youtube.com) → **Buat** → **Live streaming**.
2. Salin **Stream URL** (biasanya `rtmp://a.rtmp.youtube.com/live2`) dan
   **Stream key**-nya.
3. Tempel di aplikasi: URL di kolom pertama, key di kolom kedua.
4. Tekan **Mulai Live**, setujui dialog capture layar, live akan muncul di
   YouTube Studio setelah beberapa detik.

## Pengaturan yang sudah disesuaikan untuk Android Go

- Bitrate video default `2.5 Mbps`, FPS `24` — cukup ringan untuk chipset kelas
  entry Android Go, bisa diturunkan lagi di `ScreenRecordService.kt`
  (`VIDEO_BITRATE`, `FPS`) kalau masih lag.
- Resolusi capture memakai resolusi asli layar (via `DisplayMetrics`), bisa
  diturunkan (misal dibagi 2) di `handleStart()` kalau perangkat kurang kuat.
- `minSdk 26` (Android Oreo) karena semua perangkat Android Go minimal Oreo.

## Batasan yang perlu diketahui

- Notifikasi "sedang live" **tidak bisa disembunyikan** — ini kebijakan Android,
  bukan bug aplikasi.
- Kalau perangkat mematikan aplikasi di background secara agresif (umum di
  MIUI/Android Go), tambahkan aplikasi ini ke daftar **"Tanpa batasan baterai"**
  di Pengaturan → Baterai, supaya `ScreenRecordService` tidak dibunuh sistem.
- Audio yang di-capture untuk sekarang hanya dari microphone perangkat
  (`prepareAudio()` default). Menangkap audio internal (suara game/musik)
  butuh `AudioPlaybackCaptureConfiguration` (Android 10+) — bisa ditambahkan
  sebagai pengembangan lanjutan.

## Upload ke GitHub

```bash
cd go-go-live-android-go-live
git init
git add .
git commit -m "Initial commit: Go Go Live - Android Go screen live streaming"
git branch -M main
git remote add origin https://github.com/<username>/go-go-live-android-go-live.git
git push -u origin main
```

> Catatan: nama repo GitHub sebaiknya pakai tanda hubung (`go-go-live-android-go-live`)
> karena GitHub tidak mengizinkan spasi pada nama repo.
