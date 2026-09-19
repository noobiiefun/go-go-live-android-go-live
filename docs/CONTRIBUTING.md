# Panduan Kontribusi - Go Go Live

Terima kasih atas minat Anda untuk berkontribusi pada proyek **Go Go Live**! Dokumen ini berisi panduan lengkap untuk mengembangkan dan mengirimkan kontribusi Anda.

---

## Daftar Isi

1. [Cara Berkontribusi](#cara-berkontribusi)
2. [Setup Development](#setup-development)
3. [Standar Kode](#standar-kode)
4. [Workflow Git](#workflow-git)
5. [Pull Request Guidelines](#pull-request-guidelines)
6. [Bug Reporting](#bug-reporting)
7. [Feature Requests](#feature-requests)

---

## Cara Berkontribusi

Ada banyak cara untuk berkontribusi pada proyek ini:

### 1. Melaporkan Bug
- Gunakan GitHub Issues dengan template "Bug Report"
- Sertakan langkah reproduksi yang jelas
- Lampirkan logcat jika memungkinkan

### 2. Menyarankan Fitur Baru
- Buat issue dengan label "enhancement"
- Jelaskan use case dan manfaat fitur
- Diskusikan implementasi dengan maintainer

### 3. Memperbaiki Bug
- Fork repository
- Buat branch baru untuk fix
- Submit pull request dengan referensi ke issue

### 4. Menambah Fitur
- Diskusikan dulu di issue sebelum mulai coding
- Pastikan fitur sesuai dengan filosofi proyek (ringan, Android Go-friendly)
- Submit pull request dengan dokumentasi

### 5. Memperbaiki Dokumentasi
- Pull request untuk typo, klarifikasi, atau contoh tambahan
- Selalu disambut dengan senang hati!

---

## Setup Development

### Prasyarat

| Software | Versi Minimum | Catatan |
|----------|---------------|---------|
| Android Studio | Giraffe (2022.3.1) | Koala direkomendasikan |
| JDK | 17 | Termasuk di Android Studio |
| Gradle | 8.13 | Auto-download via wrapper |
| Android SDK | API 36 | Auto-install via SDK Manager |
| Device/Emulator | API 26+ | Xiaomi Redmi A3 direkomendasikan |

### Langkah Setup

```bash
# 1. Fork repository
# Klik tombol "Fork" di GitHub

# 2. Clone fork Anda
git clone https://github.com/<username>/go-go-live-android-go-live.git
cd go-go-live-android-go-live

# 3. Buka di Android Studio
# File → Open → Pilih folder project

# 4. Sync Gradle
# Tunggu hingga sync selesai (download dependencies)

# 5. Build project
./gradlew assembleDebug

# 6. Run ke device
# Pastikan USB debugging aktif
# Klik Run (Shift+F10)
```

### Verifikasi Setup

```bash
# Jalankan tests (jika ada)
./gradlew test

# Check code style
./gradlew ktlintCheck

# Build release APK
./gradlew assembleRelease
```

---

## Standar Kode

### Kotlin Style Guide

Ikuti [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) resmi.

#### Naming Conventions

```kotlin
// Class & Object - PascalCase
class MainActivity : AppCompatActivity()

// Function & Variable - camelCase
fun startStreamingService() {
    val rtmpUrl = ""
}

// Constant - SCREAMING_SNAKE_CASE
const val MAX_RECONNECT_RETRIES = 5

// Private property - camelCase dengan underscore prefix (opsional)
private var _isRunning = false

// Resource IDs - camelCase dengan jenis prefix
val btnStart: Button
val tvStatus: TextView
val etRtmpUrl: EditText
```

#### Formatting

```kotlin
// Indentation: 4 spasi (bukan tab)
// Line length: max 120 karakter
// Blank lines: antar fungsi, dalam class

// Good ✅
class ScreenRecordService : Service(), ConnectChecker {
    
    private lateinit var genericStream: GenericStream
    
    override fun onCreate() {
        super.onCreate()
        genericStream = GenericStream(baseContext, this, NoVideoSource(), MicrophoneSource())
    }
    
    // Blank line sebelum fungsi baru
    private fun handleStart(intent: Intent) {
        // Implementation
    }
}

// Bad ❌
class ScreenRecordService:Service(),ConnectChecker{
    private lateinit var genericStream:GenericStream
    override fun onCreate(){super.onCreate();genericStream=GenericStream(...)}
}
```

#### Comments & Documentation

```kotlin
/**
 * Service ini adalah "otak" dari aplikasi.
 *
 * PENTING soal kenapa ini BUKAN overlay:
 * - Service biasa (foreground service) TIDAK menggambar apapun di layar.
 * - Satu-satunya yang terlihat ke user adalah notifikasi wajib di status bar.
 */
class ScreenRecordService : Service(), ConnectChecker {
    
    /**
     * Jaring pengaman tambahan di luar onConfigurationChanged().
     * Beberapa vendor Android tidak selalu memicu callback config-change.
     */
    private fun startPeriodicResolutionWatcher() {
        // Implementation
    }
}
```

#### Error Handling

```kotlin
// Good ✅ - Specific exception handling
try {
    genericStream.prepareVideo(width, height, bitrate, fps, gop)
} catch (e: IllegalArgumentException) {
    Log.e(TAG, "Invalid video parameters: ${e.message}")
    handleFallback()
} catch (e: IllegalStateException) {
    Log.e(TAG, "Encoder in wrong state: ${e.message}")
    stopSelf()
}

// Bad ❌ - Catch all exceptions
try {
    // risky operation
} catch (e: Exception) {
    // silent fail
}
```

### Android Best Practices

#### Activity & Fragment

```kotlin
// Gunakan ViewBinding
private lateinit var binding: ActivityMainBinding

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    binding = ActivityMainBinding.inflate(layoutInflater)
    setContentView(binding.root)
    
    // Setup UI
    binding.btnStart.setOnClickListener { onStartClicked() }
}

// Hindari memory leaks
private val stateReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        // Handle broadcast
    }
}

override fun onResume() {
    super.onResume()
    registerReceiver(stateReceiver, filter)
}

override fun onPause() {
    super.onPause()
    unregisterReceiver(stateReceiver) // Always unregister!
}
```

#### Service

```kotlin
// Foreground service requirement
override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
        ACTION_START -> handleStart(intent)
        ACTION_STOP -> handleStop()
    }
    return START_NOT_STICKY // Don't auto-restart if killed
}

// Release resources on destroy
override fun onDestroy() {
    super.onDestroy()
    releaseLocks()
    genericStream.stopStream()
}
```

#### SharedPreferences

```kotlin
// Gunakan extension function untuk edit
import androidx.core.content.edit

prefs.edit {
    putString(KEY_RTMP_URL, rtmpUrl)
    putInt(KEY_FPS, 30)
    apply() // Auto-commits
}

// Atau commit() jika perlu synchronous
prefs.edit().putString(KEY_RTMP_URL, rtmpUrl).commit()
```

---

## Workflow Git

### Branch Naming

```bash
# Bug fixes
git checkout -b fix/issue-123-crash-on-rotation

# New features
git checkout -b feature/audio-mix-mode

# Documentation
git checkout -b docs/update-api-reference

# Refactoring
git checkout -b refactor/service-lifecycle
```

### Commit Messages

Ikuti [Conventional Commits](https://www.conventionalcommits.org/):

```bash
# Format: <type>(<scope>): <description>

# Examples:
git commit -m "fix(service): handle null MediaProjection on restart"
git commit -m "feat(settings): add bitrate selection options"
git commit -m "docs(readme): update installation steps"
git commit -m "refactor(ui): extract permission logic to helper class"
git commit -m "perf(encoder): reduce memory allocation in encoding loop"
git commit -m "test(service): add unit tests for reconnect logic"
```

### Commit Best Practices

```bash
# Good ✅ - Atomic commits
git add app/src/main/java/com/gogolive/androidgo/service/ScreenRecordService.kt
git commit -m "fix(service): prevent memory leak in orientation change"

# Bad ❌ - Too many changes in one commit
git add .
git commit -m "fixed some stuff and added new features"
```

### Rebase vs Merge

```bash
# Update branch dengan main terbaru (preferred)
git fetch origin
git rebase origin/main

# Resolve conflicts jika ada
git add <file>
git rebase --continue

# Push setelah rebase (force push diperlukan)
git push --force-with-lease
```

---

## Pull Request Guidelines

### PR Checklist

Sebelum submit PR, pastikan:

- [ ] Code mengikuti standar kode proyek
- [ ] Tidak ada warning di Android Studio
- [ ] Tested di device/emulator
- [ ] Commit messages deskriptif
- [ ] Referensi ke issue (jika ada)
- [ ] Dokumentasi updated (jika perlu)

### PR Template

```markdown
## Deskripsi
Jelaskan perubahan yang Anda buat secara singkat.

## Jenis Perubahan
- [ ] Bug fix (non-breaking change)
- [ ] New feature (non-breaking change)
- [ ] Breaking change (fix or feature that breaks existing functionality)
- [ ] Documentation update
- [ ] Code refactoring
- [ ] Performance improvement

## Issue Terkait
Fixes #123 (jika ada)

## Testing
Langkah testing yang sudah dilakukan:
1. Buka aplikasi
2. Mulai live streaming
3. Putar layar device
4. Verifikasi stream restart otomatis

## Screenshots (jika perlu)
Tambahkan screenshot jika perubahan mempengaruhi UI.

## Checklist
- [ ] Saya telah membaca panduan kontribusi
- [ ] Code saya tidak mengandung hardcoded values
- [ ] Saya telah menambahkan komentar untuk logic yang kompleks
- [ ] Change log updated (jika perlu)
```

### Review Process

1. **Automated Checks**: CI/CD akan run build dan tests
2. **Code Review**: Maintainer akan review code dalam 1-7 hari
3. **Feedback**: Jika ada perubahan yang diminta, lakukan dan push lagi
4. **Approval**: Setelah approved, PR akan di-merge ke main
5. **Close**: Issue terkait akan ditutup otomatis

### Response Time

- Bug fixes: 1-3 hari
- Feature requests: 3-7 hari (diskusi dulu)
- Documentation: 1-2 hari
- Questions: 1-5 hari

---

## Bug Reporting

### Bug Report Template

```markdown
**Deskripsi Bug**
Jelaskan bug secara singkat dan jelas.

**Langkah Reproduksi**
1. Buka aplikasi
2. Isi RTMP URL dan Stream Key
3. Tekan "Mulai Live"
4. Lihat error

**Perilaku yang Diharapkan**
Aplikasi seharusnya mulai live streaming tanpa crash.

**Perilaku yang Terjadi**
Aplikasi crash dengan error "SecurityException: Starting FGS with type microphone"

**Environment**
- Device: Xiaomi Redmi A3
- Android Version: 13 (Go Edition)
- App Version: 1.0.0
- Build: Debug/Release

**Screenshots/Logcat**
```
12-34 56:789 E/GoGoLive-Service: SecurityException starting foreground service
at android.app.Service.startForeground(Service.java:...)
```

**Informasi Tambahan**
Bug terjadi setelah update ke versi 1.0.0, sebelumnya tidak ada masalah.
```

### Severity Levels

| Level | Deskripsi | Contoh |
|-------|-----------|--------|
| Critical | App crash, data loss | Crash saat mulai live |
| High | Feature broken, no workaround | Audio tidak terdengar sama sekali |
| Medium | Feature partially broken | Reconnect butuh waktu lama |
| Low | Minor inconvenience | Typo di UI |

---

## Feature Requests

### Feature Request Template

```markdown
**Apakah fitur ini terkait masalah? Jelaskan.**
Saat ini, pengguna harus membuka aplikasi penuh untuk mulai live. Ini mengganggu saat menggunakan spacedesk dalam mode landscape.

**Deskripsi Solusi yang Diinginkan**
Tambahkan Quick Settings Tile yang memungkinkan mulai/stop live tanpa membuka aplikasi.

**Alternatif yang Pernah Dipertimbangkan**
Widget homescreen, tapi Quick Tile lebih accessible dan tidak memakan space.

**Konteks Tambahan**
Fitur ini akan sangat berguna untuk skenario:
- Live gaming dengan spacedesk
- Presentasi layar tanpa interrupt
- Streaming jangka panjang

**Mockup (opsional)**
[Tambahkan sketch atau referensi UI]
```

### Feature Prioritization

Features akan diprioritaskan berdasarkan:

1. **Impact**: Seberapa banyak user yang terbantu
2. **Effort**: Kompleksitas implementasi
3. **Alignment**: Kesesuaian dengan visi proyek (Android Go optimization)
4. **Urgency**: Kebutuhan user yang mendesak

### Feature Status Labels

- `status/triage`: Belum di-review
- `status/discussion`: Sedang didiskusikan
- `status/accepted`: Akan diimplementasikan
- `status/in-progress`: Sedang dikerjakan
- `status/on-hold`: Ditunda (alasan tertentu)
- `status/declined`: Tidak akan diimplementasikan

---

## Code of Conduct

### Our Pledge

Kami berkomitmen untuk membuat komunitas kami inklusif dan ramah untuk semua orang, terlepas dari:

- Latar belakang
- Pengalaman
- Identitas gender
- Orientasi seksual
- Disabilitas
- Penampilan pribadi
- Ras
- Agama

### Expected Behavior

- Gunakan bahasa yang inklusif dan menghormati
- Terima kritik konstruktif dengan lapang dada
- Fokus pada apa yang terbaik untuk komunitas
- Tunjukkan empati terhadap anggota komunitas lain

### Unacceptable Behavior

- Komentar seksis, rasis, atau diskriminatif
- Menghina atau merendahkan orang lain
- Spam atau trolling
- Melecehkan anggota lain secara publik atau privat

### Enforcement

Pelanggaran code of conduct akan ditindak oleh maintainer dengan:
1. Peringatan tertulis
2. Ban sementara
3. Ban permanen

---

## Recognition

Kontributor akan diakui di:

- **README.md**: Daftar kontributor utama
- **RELEASE.md**: Changelog setiap release
- **GitHub Contributors**: Halaman contributors repository

---

## Questions?

Jika Anda memiliki pertanyaan:

1. Cek dokumentasi yang sudah ada
2. Cari di issues (mungkin sudah pernah ditanyakan)
3. Buat issue baru dengan label "question"
4. Hubungi maintainer via email (jika tersedia)

---

## License

Dengan berkontribusi, Anda menyetujui bahwa kontribusi Anda dilisensikan di bawah lisensi yang sama dengan proyek ini.

---

Terima kasih telah berkontribusi untuk membuat **Go Go Live** lebih baik! 🎉
