# Rizqflow

Aplikasi finansial berbasis "hak": rezeki yang masuk dialirkan ke ruang-ruang (Memberi, Diri, Keluarga, dan peran lain) lewat aturan alokasi. Produk dari bagian jurnal di **Ruang Finansial** (Notion). Tujuan: portofolio dan monetisasi (freemium). Bagian dari tema situs roziqrizal.com: satu orang, banyak ruang, satu sistem.

## Status Pengembangan

_Diperbarui 17 Oktober 2026. **Direkonstruksi dari `git log` dan dokumen repo, belum diverifikasi pemilik** — koreksi butir yang keliru. Potret keadaan sekarang, bukan log: ubah di tempat setiap selesai pekerjaan nyata (skill `catat-progres`); riwayat ada di `git log`._

**Sumber status hidup: Notion** (hub di bagian "Status dan tracking" di bawah) dan [docs/roadmap.md](docs/roadmap.md) (snapshot terakhir disinkronkan 2026-09-25). Bagian ini ringkasan untuk yang membuka repo tanpa Notion. Commit terakhir: 2026-09-28.

### Sudah selesai
- Tahap 1 (desain UI/UX), 2 (inti domain), 3 (layar inti), 4 (dashboard Denah), 5 (modul Memberi: zakat dan haul) berstatus Selesai di roadmap.
- Tahap 10 dan 11 sudah berjalan: utang-piutang (domain, Room skema v6, layar), laporan bulanan dasar, pintasan folder cadangan, usulan pos lokal, parser bahasa natural Indonesia, layar konfirmasi input cerdas; akun gratis tak terbatas; saklar Pro (uji) di build debug.
- Keputusan 2026-09-24/25: server di VPS pemilik **ikut rilis pertama** (Tahap 9 naik sebelum rilis), sync sebagai langganan sejak rilis, server tidak boleh bisa membaca data keuangan (lihat [docs/server-sync.md](docs/server-sync.md)).
- **2026-10-18: web Rizqflow diputuskan dibuat** (seperti habitflow.roziqrizal.com), membatalkan keputusan "web tidak dibuat". Scaffold awal selesai di `server/` (Laravel 12 + Livewire 4 + Blade, SQLite dev): login/register, halaman Denah/Transaksi/Ruang/Zakat dengan **data contoh/seed** (lihat `database/seeders/DatabaseSeeder.php`), desain pakai token `docs/design/tokens.css`. Sync ke data asli menunggu Tahap 9 selesai — lihat `docs/konsep.md` bagian "Keputusan yang masih terbuka" soal cara browser mendekripsi data e2e.

### Belum selesai / berikutnya
1. **Tahap 0** In Progress: tinggal validasi minat lewat landing page dan daftar tunggu.
2. **Tahap 6** (keamanan, data, polish) dan **Tahap 7** (monetisasi dan Pro) In Progress. Terbuka di Tahap 7: integrasi Google Play Billing (butuh produk in-app dari Tahap 8), harga emas otomatis (riset API), uji alur pembelian dengan penguji lisensi.
3. **Tahap 9** (server, sync, ruang keluarga) Planning, wajib sebelum rilis; **Tahap 12** (infrastruktur server dan deploy, dijalankan pemilik) dan **Tahap 13** (enkripsi ujung ke ujung dan kunci) Backlog.
4. **Tahap 8** (rilis dan portofolio) Backlog: akun developer, kebijakan privasi dan data safety, uji tertutup Play Console, store listing, case study di roziqrizal.com.
5. Sisa Tahap 10–11 (input suara, OCR struk, parser notifikasi) masih terbuka.
6. **Keputusan terbuka, jangan diputuskan sendiri:** verifikasi asumsi fikih zakat dengan kitab; hal yang masih ditandai belum diputuskan di [docs/server-sync.md](docs/server-sync.md).

### Catatan penting
- Dua berkas Gradle (`app/build.gradle.kts`, `settings.gradle.kts`) tampak "berubah" di working tree hanya karena akhir baris (LF/CRLF), bukan isi; jangan ikut di-commit.
- Aturan repo ini: stage file satu per satu, lalu **langsung push** setiap commit (tanpa menunggu diminta) — sama seperti repo personal lain.

## Baca dulu sebelum mengerjakan apa pun

- [docs/konsep.md](docs/konsep.md) — posisi produk, MVP, arsitektur, model data, keputusan (sudah dan terbuka)
- [docs/monetisasi.md](docs/monetisasi.md) — pembagian Gratis / Pro / Sync
- [docs/roadmap.md](docs/roadmap.md) — 10 tahap pengerjaan
- [docs/ui-flow.md](docs/ui-flow.md) — layar S01–S28 dan flow F1–F9
- [docs/wireframe.md](docs/wireframe.md) — wireframe teks seluruh layar (butir "Usulan" belum diputuskan)
- [docs/auth-google.md](docs/auth-google.md) — masuk dengan Google dan Gmail: yang sudah ada, penyiapan Google Cloud, risiko
- [docs/server-sync.md](docs/server-sync.md) — server di VPS Al-Kaukaba, model kunci enkripsi ujung ke ujung, dan yang belum diputuskan
- [docs/design/README.md](docs/design/README.md) — design tokens (`tokens.css`) dan prototipe klik; sumber tunggal warna, font, dan jarak

## Prinsip yang tidak boleh dilanggar

- **Inti universal, Islami sebagai modul opsional** (zakat/haul). Penamaan internal kode netral (`allocation`, `room`, `giving-module`), bukan istilah Islami.
- **Uang = bilangan bulat dalam satuan terkecil** (value object `Money`). Tidak pernah floating point.
- **Android saja**: UI mengikuti konvensi Android (bottom navigation, FAB, bottom sheet, snackbar); pembelian lewat Google Play Billing.
- **Offline-first**: data lokal, aplikasi berfungsi penuh tanpa jaringan. Backup terenkripsi wajib ada.
- **Server ikut rilis 1** (diputuskan 2026-09-24, Tahap 9): akun, verifikasi pembelian, metrik, sync, dan ruang keluarga. **Server tidak boleh bisa membaca data keuangan** (enkripsi ujung ke ujung, kunci di perangkat). Data keuangan tidak dikirim ke server dalam bentuk terbaca, dan metrik hanya anonim tanpa nominal atau isi transaksi.
- **Semua penguncian fitur lewat satu lapisan entitlement**, tidak ada pengecekan Pro yang tersebar.
- **Selalu gratis**: keamanan (PIN/biometrik), ekspor data, dan dasar zakat. **Tanpa iklan.**
- **Tenang, bukan panik**: peringatan lembut, tidak pernah memblokir; status selalu ikon plus teks, bukan hanya warna.
- Asumsi fikih ditampilkan di layar beserta disclaimer: aplikasi adalah bantuan hitung, bukan fatwa.

## Proyek Android

- Application ID: `com.roziqrizal.rizqflow`. Tetap setelah terbit di Play Store (diputuskan 2026-09-20).
- Modul Gradle: `:domain` (Kotlin/JVM murni, tanpa Android), `:data` (Room, skema versi 6; lihat docs/model-data.md), `:app` (Compose). `:app` dan `:data` boleh bergantung pada `:domain`, tidak sebaliknya.
- Versi: Gradle 9.7.1, AGP 9.4.1 (Kotlin bawaan AGP, tanpa plugin `kotlin-android`), Kotlin 2.4.20, compileSdk dan targetSdk 37, min SDK 26. Semua versi ada di `gradle/libs.versions.toml`.
- Build dari terminal butuh **JDK 17 atau lebih baru** lewat `JAVA_HOME` (bukan Java 8 yang ada di PATH). CI memakai 17; JDK 25 bawaan Android Studio juga sudah dicoba dan lulus. Path Android SDK ada di `local.properties`, yang tidak di-commit. Perintah: `./gradlew :domain:test` dan `./gradlew :app:assembleDebug`.
- Android Studio harus **Quail 4 (2026.1.4) atau lebih baru** (syarat minimum AGP 9.4); pakai kanal Stable dan tolak Upgrade Assistant. Chipmunk (2021.2) tidak bisa membuka proyek ini.
- Tes `:domain` dan `:app`: JUnit Jupiter + kotlin.test (`:domain` dengan `allWarningsAsErrors`). Kalkulasi murni diuji dengan golden test tanpa mock, mengikuti [docs/strategi-unit-test.md](docs/strategi-unit-test.md).
- Tema Compose ada di `app/.../ui/theme` dan **bersumber dari `docs/design/tokens.css`**: ubah token di sana lebih dulu, lalu samakan `Color.kt`; `RizqflowTokensTest` menjaga kontras warna. Font Manrope dan Libre Caslon Text (OFL) ada di `res/font`, lisensinya di `assets/licenses`.

## Proyek Web (`server/`)

- Laravel 12 + Livewire 4 (class-based, pola `app/Livewire/*.php` + `resources/views/livewire/*.blade.php`, sama seperti `habitflow/server`), Blade, SQLite untuk dev lokal (`database/database.sqlite`, tidak di-commit).
- Halaman: `Denah` (beranda, S05), `Transaksi` (S08, panel catat di scaffold ini baru tampilan), `Ruang` (detail per ruang, S11 disederhanakan — belum ada breakdown Pos), `Zakat` (S14/S16). Layout bersama di `resources/views/components/layout.blade.php`, token warna/font disalin dari `docs/design/tokens.css` (samakan manual bila tokens.css berubah).
- **Data masih contoh/seed** (`database/seeders/DatabaseSeeder.php`), bukan data sungguhan. Auth email+sandi sementara (bukan keputusan final — lihat `docs/konsep.md`). Login demo: `demo@rizqflow.test` / `password`.
- Jalankan: `php artisan serve` dari `server/`. Setup awal: `composer install`, `cp .env.example .env`, `php artisan key:generate`, `php artisan migrate --seed`.
- **Belum dikerjakan**: sinkron ke data asli (tunggu Tahap 9), deploy produksi (domain/VPS belum diputuskan), breakdown Pos di Ruang, autentikasi final.

## Status dan tracking

- Notion: hub **Rizqflow** di dalam Ruang Finansial, berisi database "🧩 Tahap Rizqflow" (bagian-bagian besar) dan "🚀 Pengembangan Rizqflow" (tugas dan log). Hub: https://app.notion.com/p/3e0edbb47c0b8184a091ddcf591766fd
- Catat progres lewat skill **`/pengembangan-rizqflow`** (Notion + dokumentasi repo). Detail mapping dan ID database ada di skill itu.
- Platform: **Android**, dirilis di Google Play Store. Stack: **Kotlin + Jetpack Compose + Room** (diputuskan 2026-09-19). **Web juga dibuat** (diputuskan 2026-10-18): `server/` (Laravel 12 + Livewire 4 + Blade), scaffold awal pakai data contoh, lihat "Sudah selesai" di atas dan `docs/konsep.md`.
- Arah visual: **opsi A**, pakai ulang identitas homepage roziqrizal.com (diputuskan 2026-09-19); warna dan font disimpan sebagai design tokens, angka besar selalu Manrope.
- Detail stack **disetujui 2026-09-20** (`docs/konsep.md`): modul `:domain`/`:data`/`:app`, min SDK 26, DI manual, backup AES-GCM, tanpa SQLCipher untuk v1. Masih terbuka: kalender Hijriyah untuk haul dan verifikasi asumsi fikih dengan kitab (dikaji lebih jauh nanti). Jangan memutuskan sendiri; tanyakan dulu.
- **Keputusan 2026-09-24:** akun keuangan (tunai, bank, e-wallet) tidak dibatasi, batas ruang gratis tetap 5. Server di VPS milik pemilik ikut rilis pertama dengan cakupan penuh (sync, ruang keluarga, notifikasi) dan Sync dijual sebagai langganan sejak rilis. Bot WhatsApp dan input AI LLM sesudah rilis. **Sudah diputuskan 2026-09-24:** backend Laravel di VPS Al-Kaukaba yang sudah ada (aplikasi, database, PHP-FPM, dan Nginx terpisah; konfigurasi dan skrip deploy di repo; alamat API bisa dikonfigurasi di aplikasi); model kunci ujung ke ujung (Tink, kode pemulihan wajib, kunci per ruang keluarga, undangan lewat QR); harga Sync Rp 19.000 per bulan atau Rp 149.000 per tahun untuk keluarga sampai 5 anggota, trial 7 hari, tidak termasuk Pro. Rincian di [docs/server-sync.md](docs/server-sync.md), Tahap 9, 12, dan 13 [docs/roadmap.md](docs/roadmap.md). **Masih terbuka (jangan dipilih sendiri):** model konflik sync, aturan akun lintas ruang di ruang keluarga, dan apakah Firebase Analytics dipakai. Claude tidak menyentuh VPS produksi tanpa izin eksplisit.

## Konvensi

- Bahasa dokumen dan pesan commit: **Indonesia**.
- Perubahan dokumentasi ditulis di `docs/`; jangan biarkan Notion dan repo saling bertentangan (spesifikasi UI penuh di `docs/ui-flow.md`, ringkasannya di halaman Tahap Notion).
- Setiap perubahan, sekecil apa pun, **langsung di-commit lalu di-push** ke `origin main` begitu selesai — tidak menunggu diminta (diputuskan 2026-10-18, biar kerjaan tidak hilang kalau PC/VM bermasalah). Stage file satu per satu (bukan `git add -A`). Satu perubahan logis = satu commit.
