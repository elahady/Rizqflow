# Rizqflow — Konsep

> Rezeki mengalir, setiap hak terpenuhi.

Dokumen ini merangkum keputusan awal proyek supaya tidak perlu diulang dari nol.

## Tujuan

Aplikasi finansial untuk **portofolio**. Yang dinilai adalah cara berpikir produk dan kualitas teknisnya, bukan bersaing dengan aplikasi finansial yang sudah ada di pasaran. Aplikasi pencatat pengeluaran generik sengaja tidak dibangun.

## Masalah & sudut pandang

Aplikasi finansial biasa bertanya: *"uangmu habis ke mana?"*

Rizqflow bertanya: *"apakah setiap hak sudah ditunaikan?"*

Hidup dipandang sebagai kumpulan peran/kewajiban. Tiap peran punya jatah uang dan aturan sendiri, dan aplikasi memastikan tidak ada yang terabaikan. Ini sejalan dengan tema situs [roziqrizal.com](https://roziqrizal.com): satu orang, banyak ruang, satu sistem.

## Posisi produk: inti universal + modul opsional

Inti aplikasi **tidak Islami**. Ini *role-based / values-based budgeting* yang bisa dipakai siapa saja. Sisi Islami hadir sebagai modul yang bisa dinyalakan atau dimatikan.

| Hak (sumber ide) | Ruang (label netral) | Isi contoh |
|---|---|---|
| Hak Rabb | Memberi | zakat, sedekah, donasi |
| Hak diri | Diri | investasi, dana darurat, belajar, kesehatan |
| Hak keluarga | Keluarga | nafkah, tabungan bersama, tanggungan |

Peran spesifik (Investor, Trader, Husband, dll.) menjadi ruang tambahan dengan sistemnya sendiri.

Saat menulis README dan case study: **buka dengan masalah umum** ("uang habis tanpa sadar apakah semua tanggung jawab terpenuhi"), lalu tunjukkan sisi Islami sebagai contoh implementasi yang mendalam.

## Pembeda utama

**Tracker zakat mal dengan haul kalender Hijriyah.** Nisab dari harga emas, haul dihitung dalam tahun Hijriyah. Aplikasi zakat yang ada umumnya hanya kalkulator. Keahlian hisab/kalender Hijriyah dari proyek Al-Kaukaba menjadi keunggulan yang sulit ditiru.

## Cakupan MVP

Masuk:
- Input transaksi (pemasukan, pengeluaran)
- Aturan alokasi pemasukan ke ruang-ruang (mis. persentase per ruang)
- Dashboard satu layar: kondisi semua ruang, mana yang belum terpenuhi
- Modul Memberi: mode zakat/haul Hijriyah **atau** persentase donasi biasa
- Backup dan restore lokal terenkripsi (wajib: data hanya di perangkat, tanpa backup ganti ponsel berarti kehilangan riwayat)
- Mode demo dengan data contoh (penilai portofolio bisa mencoba tanpa data keuangan asli)
- Kemudahan mencatat: catat kilat dan favorit, pengingat malam, koreksi saldo (lihat bagian Disiplin mencatat)

Sengaja tidak masuk (agar tidak melebar):
- Sinkronisasi rekening bank
- Fitur investasi lengkap / portofolio saham
- Sinkronisasi server (baru di fase 2, sebagai langganan Sync). Masuk dengan akun Google dan akun lokal (nama pengguna dan sandi) sudah ada sejak 2026-09-21 tetapi hanya identitas lokal, tanpa server ([auth-google.md](auth-google.md))

## Disiplin mencatat

Masalah nyata dari pemilik proyek, dan alasan aplikasi ini layak dipakai sendiri: pengeluaran kecil sering terlewat karena lupa atau malas, baik yang tunai, QRIS, maupun e-wallet. Aplikasi tidak bisa memaksa disiplin. Yang bisa dilakukan adalah membuat mencatat lebih murah daripada lupa, dan membuat yang terlewat mudah dikejar tanpa rasa bersalah.

| Mekanisme | Mengatasi | Layar |
|---|---|---|
| **Catat kilat**: pintasan ikon, tile Quick Settings, dan favorit (nominal, kategori, ruang, akun) yang tersimpan dengan satu ketukan | Terlalu banyak langkah | S24 |
| **Pengingat malam**: satu notifikasi per hari dengan kolom balasan (misalnya `kopi 25000`) dan tindakan "Tidak ada" | Tidak ada pemicu | S26 |
| **Koreksi saldo**: pengguna memasukkan saldo sebenarnya per akun; selisihnya dicatat sebagai satu baris "Tak terlacak" | Malas membereskan yang bolong | S25 |
| **Petunjuk hari kosong**: teks lembut bila kemarin belum ada catatan pengeluaran dan belum ditandai "Tidak ada" | Tidak sadar ada yang bolong | S08 |

Karena kasusnya bercampur (tunai, QRIS, e-wallet), tidak ada mekanisme yang boleh bergantung pada satu jenis pembayaran. Koreksi saldo bekerja per akun untuk semua jenis: tunai, e-wallet, dan bank (QRIS memotong saldo akun yang dipakai membayar). Inilah jaring pengaman universalnya.

**Tangkap otomatis** (disetujui 2026-09-20; dikerjakan di versi 1.1 setelah rilis; masuk Pro): aplikasi membaca notifikasi pembayaran dari aplikasi bank dan e-wallet di perangkat, lalu menyiapkan **draf** transaksi yang tinggal disetujui. Ini bukan sinkronisasi rekening bank (yang tetap tidak dibuat). Keterbatasannya: hanya menjangkau yang digital, bukan tunai (Koreksi saldo tetap jaring pengamannya), izin akses notifikasi itu sensitif, dan parser tiap aplikasi perlu dirawat. Karena itu rancangannya berhati-hati (usulan, dimatangkan saat tahapnya tiba):

- Selalu berupa draf; tidak pernah menyimpan transaksi tanpa persetujuan pengguna.
- Hanya notifikasi dari daftar aplikasi keuangan yang didukung yang diproses; notifikasi lain diabaikan dan tidak dibaca lebih jauh.
- Yang disimpan hanya nominal, nama tujuan, waktu, dan aplikasi sumber; teks notifikasi asli dibuang.
- Diproses di perangkat, tidak dikirim ke mana pun.
- Versi pertama hanya pengeluaran; pemasukan tetap lewat S06 dan S07 karena harus dialirkan.
- Draf yang mirip transaksi manual (nominal dan akun sama dalam rentang waktu dekat) ditandai "Mungkin sudah dicatat".
- Izin dijelaskan dulu sebelum diminta, dan bisa dicabut kapan saja tanpa merusak data.

Semua mekanisme mengikuti prinsip "tenang, bukan panik": tanpa streak, tanpa warna merah, dan bisa dimatikan.

## Arsitektur (arah)

- **Rule engine alokasi** dengan strategi yang bisa ditukar. Modul Memberi punya dua strategi (`zakat-haul-hijri`, `percentage`), dan ruang lain bisa ditambah tanpa mengubah inti.
- **Offline-first**, data disimpan lokal. Data keuangan itu sensitif, dan untuk portofolio pendekatan ini menghindari urusan keamanan server.
- Penamaan internal kode **netral** (`allocation`, `room`, `giving-module`), bukan istilah Islami, supaya konsisten dengan posisi "inti universal + modul".
- Logika inti (alokasi, nisab, haul) dibuat bisa diuji unit, dengan pendekatan yang sama seperti alkaukabaandroid.

## Draft model data

Draf awal di bawah sudah dirinci menjadi rancangan lengkap dan skema Room di [model-data.md](model-data.md); bila berbeda, model-data.md yang berlaku.

- **Room** — ruang/peran (nama, tipe ruang, target bulanan)
- **AllocationRule** — cara membagi pemasukan ke ruang (persentase, prioritas)
- **Account** — dompet/rekening tempat uang berada (bank, dompet digital, tunai), seperti tabel Akun di jurnal Ruang Finansial
- **Category** — pos pengeluaran/pemasukan di dalam sebuah ruang, seperti tabel Kategori di jurnal Ruang Finansial
- **Transaction** — pemasukan/pengeluaran/transfer, terkait ke Account, Category, dan Room
- **QuickEntry** — favorit untuk catat kilat (nama, nominal, Category, Room, Account, urutan pemakaian)
- **BalanceCheck** — hasil koreksi saldo (Account, saldo menurut catatan, saldo sebenarnya, selisih, Transaction yang dihasilkan bila ada)
- **CaptureDraft** — draf dari notifikasi, fitur v1.1 (aplikasi sumber, nominal, nama tujuan, waktu, Account usulan, status menunggu/disetujui/diabaikan)
- **AssetProfile** — profil harta yang dihitung zakatnya (emas, tabungan, investasi, piutang, pengurang)
- **HaulRecord** — pelacakan nisab dan haul (tanggal mulai Hijriyah, nisab saat itu, status)

Semua nominal disimpan sebagai **bilangan bulat dalam satuan terkecil** (value object `Money`), tidak pernah floating point.

## Tipe ruang: arti "hak terpenuhi"

"Terpenuhi" tidak berarti sama untuk semua ruang. Tiga tipe berikut sudah dikonfirmasi (2026-09-20), dengan ambang 85% untuk tipe Mencukupi:

| Tipe ruang | Contoh | Terpenuhi bila |
|---|---|---|
| Menunaikan | Memberi | Dana yang tersalurkan mencapai target bulan itu (atau zakat sudah ditunaikan saat haul genap) |
| Menumbuhkan | Diri (investasi, dana darurat) | Dana yang benar-benar masuk ke akun tujuan mencapai target |
| Mencukupi | Keluarga (nafkah, kebutuhan) | Kebutuhan bulan itu tertutup dan pengeluaran tidak melebihi jatah; masuk Perlu perhatian saat pemakaian mencapai 85% jatah atau lebih |

## Dokumen terkait

- [monetisasi.md](monetisasi.md) — model bisnis Gratis / Pro / Sync
- [roadmap.md](roadmap.md) — 10 tahap pengerjaan
- [ui-flow.md](ui-flow.md) — layar S01–S28 dan flow F1–F9

## Risiko

- **Izin baca Gmail (restricted scope)** bisa menunda rilis: Google mewajibkan verifikasi OAuth dan penilaian keamanan tahunan untuk pengguna publik. Saran: rilis pertama tanpa tombol Gmail. Rincian di [auth-google.md](auth-google.md).

- **Cakupan melebar.** Jaga MVP tetap sempit.
- **Perbedaan pendapat fikih** soal nisab dan haul. Tulis asumsi yang dipakai secara eksplisit, atau sediakan pilihan.
- **Data sensitif.** Simpan lokal, jangan kirim ke mana pun tanpa alasan kuat.

## Cek nama (hasil awal, 2026-09-20)

Dicek lewat GitHub API, RDAP domain (dengan domain kontrol), dan pencarian web. Belum menggantikan pengecekan manual di Play Store dan pangkalan merek.

| Yang dicek | Rizqflow | Rizqly (cadangan) |
|---|---|---|
| Domain .com | Sudah terdaftar sejak 24 Jul 2026 (Hostinger, DNS parkir); pemilik proyek memastikan bukan miliknya, jadi dipegang pihak lain | Sudah terdaftar sejak 14 Feb 2026 (NameCheap) |
| Domain .app | Belum terdaftar | Sudah terdaftar; situsnya aktif sebagai pelacak pengeluaran pribadi |
| Domain .id, .co.id, .net | Belum terdaftar | .id belum terdaftar |
| Nama pengguna atau organisasi GitHub | Bebas | Bebas |
| Repo GitHub bernama sama | Ada repo orang lain (faz1303/Rizqflow, tanpa deskripsi) | Ada rizqly-app (mumar000) |
| Pencarian web | Ada rizqflow.tech: perusahaan rekayasa dan AI, bidang berbeda | Ada aplikasi keuangan Rizqly (rizqly.app): **satu kategori dengan Rizqflow** |
| Google Play | Pencarian "rizqflow" tanpa hasil (perlu diverifikasi langsung di aplikasi Play Store) | Belum dicek langsung |

Kesimpulan awal: **Rizqflow masih layak dipakai**. **Rizqly bukan cadangan yang baik** karena bentrok dengan aplikasi keuangan yang sudah aktif; cari cadangan lain bila perlu. Yang masih perlu dicek manual: pencarian di Play Store dan pangkalan merek DJKI (pdki-indonesia.dgip.go.id). Pemilik proyek memastikan (2026-09-20) belum pernah mendaftarkan domain apa pun, jadi rizqflow.com dipegang pihak lain. Alternatif yang masih bebas: rizqflow.app atau rizqflow.id; domain sendiri belum tentu perlu karena landing page bisa berada di roziqrizal.com. Catatan: package name aplikasi (misalnya com.roziqrizal.rizqflow) harus unik di Play Store, sedangkan judul umumnya boleh sama; tetap hindari nama yang membingungkan.

## Keputusan yang sudah diambil

- Nama: **Rizqflow** (akar r-z-q dari "Roziq" = rezeki, ditambah "flow" untuk aliran alokasi). Cadangan: *Rizqly* (lihat Cek nama: bentrok dengan aplikasi keuangan yang sudah ada).
- Posisi: inti universal + modul Islami opsional.
- Data: offline-first.
- Monetisasi: freemium; Pro sekali bayar; Sync sebagai langganan fase 2; tanpa iklan. Detail di [monetisasi.md](monetisasi.md).
- Keamanan, ekspor data, dan dasar zakat selalu gratis.
- Platform: **Android saja**, dirilis di Google Play Store (2026-09-19). Web tidak dibuat; landing page dan case study tetap di roziqrizal.com. Pembelian lewat Google Play Billing.
- Stack: **Kotlin + Jetpack Compose + Room** (2026-09-19). Compose dipilih untuk kartu ruang, cincin progres, dan animasi aliran; alkaukabaandroid memakai Views/XML, jadi ada kurva belajar yang diterima.
- Arah visual: **opsi A**, pakai ulang identitas homepage roziqrizal.com (palet sage Material 3, Manrope, Libre Caslon Text), dengan warna dan font disimpan sebagai design tokens (2026-09-19). Detail di [ui-flow.md](ui-flow.md).
- Widget, termasuk widget catat kilat, masuk **Pro** (2026-09-20). Pintasan ikon, tile Quick Settings, dan balasan notifikasi tetap gratis, karena mencatat dengan cepat adalah janji utama.
- Selisih Koreksi saldo dicatat di **ruang dari pengeluaran terakhir di akun itu** (2026-09-20). Bila akun belum punya pengeluaran, usulannya ruang bertipe Mencukupi. Selisih adalah transaksi biasa, jadi ikut dihitung dalam jatah ruang itu.
- Pengingat malam **aktif secara bawaan** dan bisa dimatikan (2026-09-20).
- **Tangkap otomatis dari notifikasi** disetujui: dikerjakan di versi 1.1 setelah rilis, dan masuk **Pro** (2026-09-20).
- **Asumsi fikih default zakat mal** (2026-09-20): nisab 85 gram emas, tarif 2,5%, haul 1 tahun Hijriyah. Ini **default sementara**: pemilik akan memverifikasinya dengan rujukan kitab, dan bila ada perubahan cukup mengganti konstanta di modul domain. Tetap ditampilkan di layar beserta disclaimer.
- **Aturan pembulatan alokasi: metode sisa terbesar** (2026-09-21). Tiap ruang menerima bagian yang dibulatkan ke bawah; sisa rupiah dibagikan satu-satu ke ruang dengan pecahan terbesar, dan urutan prioritas ruang memutus seri. Jumlah bagian selalu persis nominal (contoh: Rp 1.234.567 dengan 10/50/40% menjadi 123.457, 617.283, 493.827). Persentase disimpan sebagai basis point supaya 2,5% tetap eksak. Sudah diimplementasikan dan diuji di `AllocationEngine`.
- **Masuk dengan Google dan Gmail** (2026-09-21, permintaan pemilik): aplikasi dibuka dengan splash, lalu halaman masuk; yang sudah punya riwayat masuk langsung ke menu utama. **Ini mengubah keputusan "tanpa akun".** Identitas hanya lokal (tidak ada server Rizqflow, tidak ada token yang disimpan); data keuangan tetap di ponsel. Izin baca Gmail diminta opsional dan aplikasi belum membaca email. Langkah penyiapan Google Cloud dan risiko (cakupan Gmail "restricted" butuh verifikasi dan penilaian keamanan untuk rilis publik) ada di [auth-google.md](auth-google.md).
- **Data dimiliki per akun: satu database per akun** (2026-09-21, disetujui pemilik). Tiap akun (Google atau lokal) punya berkas database sendiri; berganti akun tidak mencampur data, dan Keluar tidak menghapus apa pun. Berkas diberi nama dari hash pengenal akun, bukan email. Lihat [model-data.md](model-data.md).
- **Batas gratis: 5 ruang** (2026-09-21, disetujui pemilik): ruang ke-6 memicu Pro (`PlanEntitlements.FREE_ROOM_LIMIT`, lihat monetisasi.md). **Akun dan kategori tidak dibatasi** (2026-09-24: batas 3 akun dicabut karena multi-dompet gratis adalah kebutuhan dasar pengguna Indonesia).
- **Usulan wireframe S08, S09, S12 disetujui** (2026-09-21): filter Ruang dan Kategori di ikon filter samping kolom cari; rincian alokasi pemasukan tersimpan sebagai potret saat dicatat (mengubah aturan tidak mengubah riwayat, mengubah nominal menghitung ulang dengan persentase potret); urutan ruang menentukan prioritas sisa pembulatan dan urutan di Denah.
- **Kalender Hijriyah untuk haul: Umm al-Qura** (2026-09-21), lewat `HijrahChronology` bawaan Java di balik antarmuka `HijriCalendar`. Berbasis tabel, deterministik, dan tanpa internet. Bisa berbeda satu hari dari penetapan Kemenag, jadi tanggal Hijriyah tampil sebagai perkiraan; kalender lain (Kemenag, hisab Al-Kaukaba) tinggal menukar implementasi.
- **Arti "terpenuhi" per tipe ruang** dikonfirmasi (2026-09-20): Menunaikan dan Menumbuhkan terpenuhi saat penggunaan mencapai jatah; Mencukupi masuk Perlu perhatian pada pemakaian 85% jatah atau lebih.
- **Status di akhir bulan** (2026-09-21): Mencukupi terpenuhi bila terpakai tidak melebihi jatah setelah bulannya berakhir. Menunaikan dan Menumbuhkan yang belum tercapai di bulan yang sudah berakhir berlabel netral "Belum tercapai" (tanpa merah, tidak masuk Perlu perhatian). Jatah dihitung per bulan dan tidak dibawa ke bulan berikutnya.
- **Sisa aman hari ini** (2026-09-24, disetujui pemilik): jatah tersisa ruang bertipe Mencukupi sebelum hari ini, dibagi sisa hari bulan itu termasuk hari ini (dibulatkan ke bawah), dikurangi pengeluaran hari ini. Menunaikan dan Menumbuhkan tidak ikut karena itu tabungan atau zakat. Tidak pernah negatif: ruang yang sudah melewati jatah dihitung 0 dan tidak memakan jatah ruang lain, jatah hari ini terlampaui tampil 0 dengan teks lembut. Hanya untuk bulan berjalan. Implementasi: `SafeToSpend` di `Denah.kt`.
- **Investasi** dicatat sebagai pengeluaran di ruang Diri (kategori Investasi atau Dana darurat); belum ada akun tujuan. Lebihan jatah ruang lain bisa diinvestasikan dengan mencatat pengeluaran di Diri; fitur "Alihkan sisa" antar ruang menjadi ide lanjutan.
- **Sistem per peran** (2026-09-23, Pro): Trader dan Investor menjadi modul opsional per ruang dengan cakupan ringan, tanpa portofolio atau harga aset. Trader: batas risiko per trade dari modal yang diisi pengguna. Investor: jadwal DCA yang dicatat sebagai pengeluaran (mengikuti keputusan investasi di atas). Rancangan di [monetisasi.md](monetisasi.md).
- **Cek nama** (2026-09-21): pencarian "rizqflow" di Google Play dan pangkalan merek DJKI (pdki-indonesia.dgip.go.id, mode Normal) tanpa hasil. Nama aman dipakai.
- **Sumber harga emas** (2026-09-20): versi gratis memakai input manual; harga otomatis lewat API masuk Pro. Riset sumber API dilakukan di Tahap 7.
- **Detail stack Android** disetujui pemilik (2026-09-20):
  - **Tiga modul Gradle.** `:domain` (Kotlin/JVM murni, tanpa dependensi Android) berisi `Money`, alokasi, nisab, haul, dan antarmuka repositori; `:data` (Room dan implementasi repositori); `:app` (Jetpack Compose, ViewModel, perakitan dependensi). Unit test domain berjalan cepat di JVM mengikuti `docs/strategi-unit-test.md` di alkaukabaandroid (JUnit + MockK).
  - **Min SDK 26 (Android 8.0); target SDK terbaru yang stabil** saat Tahap 2 dimulai (cek syaratnya di Play Console). Alasan min SDK 26: `java.time` (termasuk kalender Hijriyah `HijrahChronology`) dan channel notifikasi tersedia bawaan tanpa desugaring. Sebaran perangkat dicek di dialog proyek baru Android Studio.
  - **DI manual:** injeksi lewat konstruktor, satu kelas perakit di `:app`, dan pabrik ViewModel, sama seperti alkaukabaandroid. Graf dependensinya kecil; pindah ke Hilt bila graf membesar.
  - **Enkripsi:** backup dienkripsi dengan kata sandi (AES-256-GCM, kunci dari PBKDF2 dengan salt acak, format berkas berversi, dan diuji). Database Room **tanpa SQLCipher untuk v1**: data sudah terlindungi sandbox aplikasi dan enkripsi penyimpanan Android, PIN/biometrik menutup akses lewat aplikasi, dan `allowBackup` dimatikan supaya salinan otomatis tidak keluar dari kendali. Tinjau ulang bila model ancaman mencakup perangkat root; SQLCipher menambah ukuran aplikasi dan urusan pengelolaan kunci.
  - **UI dan domain:** Material 3 sebagai basis dengan tema dari design tokens (peta di `docs/design/README.md`), navigation-compose, `StateFlow` dan coroutine. `Money` berupa value class berisi bilangan bulat satuan terkecil (rupiah tanpa desimal) dengan penanda mata uang untuk multi-mata uang di Pro. Kalender Hijriyah diakses lewat antarmuka `HijriCalendar` di `:domain` supaya implementasinya (hisab Al-Kaukaba atau Umm al-Qura) bisa ditukar.
- **Prototipe klik disetujui** pemilik (2026-09-20) sebagai acuan layar kunci dan kemudahan mencatat. Prototipe F1 (onboarding) dan F4 (ubah aturan) belum ada.
- **Application ID** `com.roziqrizal.rizqflow` (2026-09-20). Tetap setelah terbit di Play Store.
- **Pengingat haul (2026-09-24):** tetap gratis untuk satu profil; pengingat untuk profil tambahan masuk Pro bersama multi-profil haul ([monetisasi.md](monetisasi.md)).
- **compileSdk dan targetSdk 37** (2026-09-20): Compose terbaru (BOM 2026.09) menuntut compileSdk 37, dan target mengikuti keputusan "target SDK terbaru yang stabil". Emulator yang ada baru API 36, jadi perlu image API 37 sebelum rilis. Tes `:domain` memakai JUnit Jupiter (alkaukabaandroid memakai JUnit 4) supaya golden test bertabel bisa memakai tes berparameter.

## Keputusan yang masih terbuka

- **Domain sendiri:** rizqflow.com dipegang pihak lain; .app dan .id bebas. Landing page bisa berada di roziqrizal.com, jadi domain sendiri belum tentu perlu.
- **Haul saat harta turun di bawah nisab di tengah tahun:** terputus lalu mulai dari nol (bawaan sementara), atau hanya diperiksa di awal dan akhir haul (pendapat lain). Keduanya sudah ada sebagai `HaulBreakPolicy`; menunggu verifikasi kitab oleh pemilik.
- **Harga final** Pro dan Sync, berdasarkan uji minat.
- **Pengingat malam:** jam bawaan dan kapan izin notifikasi Android 13+ diminta. Usulan: pukul 21.00, dan izin diminta setelah transaksi pertama disimpan, bukan di awal onboarding.
- **Aplikasi bank dan e-wallet yang didukung lebih dulu** untuk tangkap otomatis. Usulan: yang paling sering dipakai, dilihat dari Transaksi Harian di Ruang Finansial.
