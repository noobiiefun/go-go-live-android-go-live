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

## Ini live streaming langsung, BUKAN "rekam dulu baru upload"

`ScreenRecordService` hanya memanggil `prepareVideo()` + `prepareAudio()` lalu
`startStream(rtmpUrl)` — **tidak pernah** memanggil `startRecord()` dari library
RootEncoder. Artinya alurnya adalah:

```
Layar HP → encoder H264/AAC (di RAM) → dikirim langsung via RTMP ke YouTube
```

Tidak ada file video yang ditulis ke penyimpanan HP sama sekali, jadi memori
internal kamu **tidak akan penuh** walau live berjam-jam. Encoder cuma memproses
frame di RAM lalu langsung mengirimnya ke internet, mirip cara kerja OBS di PC
saat streaming (bukan cara kerja "record layar → convert → upload manual").

Kalau suatu saat kamu justru **mau** menyimpan salinan lokal juga (misal untuk
arsip), baru perlu ditambahkan pemanggilan `startRecord(path)` secara terpisah —
tapi secara default proyek ini sudah didesain tidak menyimpan apa pun.

## Pengaturan yang sudah disesuaikan untuk Android Go

- Bitrate video default `3 Mbps`, FPS `30` — sudah dinaikkan dari versi awal
  (24fps) supaya gerakan tidak patah-patah. Kalau chipset Redmi A3 kamu masih
  keteteran di 30fps, ada 2 opsi di `ScreenRecordService.kt`:
  1. Turunkan lagi `FPS` (misal ke 25) sambil bitrate tetap.
  2. **Lebih efektif kalau masih patah-patah:** turunkan **resolusi**, bukan fps
     — di `handleStart()` bagi `metrics.widthPixels`/`metrics.heightPixels`
     dengan 1.5 atau 2 sebelum dipakai di `prepareVideo()`. Resolusi lebih
     ringan buat encoder daripada menaikkan fps, dan biasanya penyebab patah
     di HP kelas Go adalah CPU/encoder kewalahan menyamai resolusi asli, bukan
     fps-nya.
- Resolusi capture memakai resolusi asli layar (via `DisplayMetrics`).
- `minSdk 26` (Android Oreo) karena semua perangkat Android Go minimal Oreo.

**Catatan teknis penting soal "patah-patah":** MediaProjection API Android
hanya menghasilkan frame baru saat ada *perubahan* di layar. Kalau layar diam
total (misal cuma nampilkan teks statis), fps otomatis turun sendiri walau
sudah di-set 30 — ini keterbatasan Android, bukan bug aplikasi. Kalau butuh
konten selalu mulus (misal main game), pastikan ada animasi/gerakan terus-menerus
di layar.

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

## Mengganti icon aplikasi & splash screen

**File yang kamu siapkan cukup 1-2 file:**

| Kebutuhan | Format | Ukuran ideal |
|---|---|---|
| Icon aplikasi | PNG latar transparan, persegi | 1024×1024 px (kasih padding ±15% dari tepi) |
| Logo splash screen | PNG latar transparan, persegi | 512×512 px (boleh sama dengan icon) |

**Ganti icon aplikasi:**
1. Buka `app/src/main/res/drawable/ic_launcher_foreground.xml` — hapus isinya
   atau ganti pendekatan: taruh file `ic_launcher_foreground.png` kamu di
   folder yang sama, lalu update
   `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` supaya
   `<foreground android:drawable="@drawable/ic_launcher_foreground" />` merujuk
   ke PNG barumu (hapus file `.xml` lamanya).
2. Cara paling gampang & otomatis: klik kanan folder `app` di Android Studio →
   **New → Image Asset** → pilih source PNG 1024x1024 kamu → Android Studio
   otomatis generate semua ukuran mipmap yang dibutuhkan.
3. Warna latar adaptive icon (kotak solid di belakang logo) ada di
   `app/src/main/res/drawable/ic_launcher_background.xml`, ganti kode warnanya
   sesuai brand kamu.

**Ganti logo splash screen:**
1. Hapus `app/src/main/res/drawable/ic_logo_splash.xml` (placeholder).
2. Taruh file logo kamu dengan nama persis `ic_logo_splash.png` di folder yang
   sama (`app/src/main/res/drawable/`).
3. Ganti warna latar splash di `app/src/main/res/values/colors.xml`
   (`splash_background`) sesuai brand kamu.
4. Tidak perlu ubah kode lain — `Theme.GoGoLive.Splash` di `themes.xml` sudah
   otomatis memakai file itu lewat `androidx.core:core-splashscreen`, yang
   bekerja konsisten dari Android 5 sampai 14+ (termasuk di Android Go).



## Update: pindah ke RootEncoder versi 2.7.3

Versi awal project ini pakai library RootEncoder versi lama (2.2.6, class
`RtmpDisplay`). Versi itu sering gagal di-resolve Jitpack di Android Studio
versi baru (error "Unresolved reference: net", "ConnectCheckerRtmp" dst).

Sekarang project ini pakai versi RootEncoder yang aktif dikembangkan (2.7.3),
dengan API baru:
- `GenericStream` (pengganti `RtmpDisplay`)
- `ScreenSource` (video source untuk capture layar)
- `MicrophoneSource` (audio source)
- `ConnectChecker` (pengganti `ConnectCheckerRtmp`, interface terpadu untuk semua protokol)

**Kalau Android Studio menandai merah baris `import com.pedro.library.generic.GenericStream`**
(garis bawah merah tapi build lain semua sukses), klik kursor di kata
`GenericStream` lalu tekan **Alt+Enter** → pilih "Import class" — Android
Studio akan otomatis mendeteksi package yang benar dari library yang sudah
ter-download. Ini bisa terjadi kalau ada penyesuaian kecil struktur package
antar versi minor library.



Aplikasi ini **tidak mengunci orientasi** — bebas dipakai potrait maupun
landscape, cocok untuk skenario spacedesk (PC lawas jadi extended
display ke HP, lalu HP yang live streaming layarnya).

Yang perlu kamu tahu soal cara kerjanya:

- Saat live **dimulai**, aplikasi mengambil resolusi layar HP **pada saat itu**
  (potrait atau landscape, sesuai kondisi layar HP waktu itu). Jadi kalau HP
  kamu sedang landscape (disamakan sama layar PC via spacedesk), video live
  juga otomatis landscape — tidak perlu setting manual.
- Kalau **di tengah live** orientasi berubah (misal kamu putar HP, atau
  spacedesk pindah mode tampilan), `ScreenRecordService` otomatis mendeteksi
  perubahan itu lewat `onConfigurationChanged()`, lalu **me-restart stream
  RTMP secara singkat** (kurang lebih setengah detik) dengan resolusi baru
  yang sesuai. Izin capture layar yang sudah diberikan di awal **tetap
  dipakai ulang** — user tidak akan diminta konfirmasi izin lagi.
- Kenapa ada jeda restart, bukan langsung mulus? Karena MediaProjection
  Android membuat "kanvas" capture dengan ukuran tetap saat pertama kali
  disiapkan; kalau tidak di-restart saat orientasi berubah, videonya akan
  gepeng/terpotong permanen sampai live dihentikan manual. Restart otomatis
  ini jauh lebih baik daripada itu.
- **Saran praktis:** kalau kamu tahu spacedesk akan selalu di satu orientasi
  tertentu selama sesi live (misal selalu landscape mengikuti PC), lebih
  baik set HP ke orientasi itu **sebelum** menekan "Mulai Live", supaya tidak
  ada jeda restart sama sekali di tengah siaran.

## Logo & branding

Logo "GO GO LIVE — ANDROID GO LIVE" yang kamu berikan sudah dipasang ke:

- **Icon aplikasi** (`ic_launcher_foreground.png` + `ic_launcher_background.xml`):
  memakai simbol icon-nya saja (bubble chat + GO + tombol play + sinyal live),
  background putih diambil transparan otomatis supaya menyatu rapi jadi
  adaptive icon.
- **Splash screen** (`ic_logo_splash.png`): simbol yang sama, tampil sekilas
  saat aplikasi dibuka.
- **Warna tema aplikasi** (`colors.xml`): diambil otomatis dari warna dominan
  logo — biru `#0F5ED3` untuk warna utama, merah `#FC3B49` untuk tombol Stop.
- File lockup lengkap (dengan tulisan "GO GO LIVE ANDROID GO LIVE") ikut
  disimpan di `app/src/main/res/drawable/ic_full_logo_lockup.png` kalau
  sewaktu-waktu mau dipakai di tempat lain (misal halaman "Tentang Aplikasi").

Kalau mau ganti logo lagi nanti, tinggal ikuti panduan di bagian
"Mengganti icon aplikasi & splash screen" di atas.

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
