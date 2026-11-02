# Rizqflow — Draf template konten visual dan caption (Tahap P0 dan P1)

> **[BUTUH DESAIN VISUAL ASLI — ini hanya spesifikasi teks/layout, bukan file gambar jadi]**
>
> Dokumen ini berisi spesifikasi tertulis (layout, palet, font, elemen) dan naskah caption. Tidak ada gambar yang dihasilkan di sini. Eksekusi visual dilakukan pemilik di **Canva** (keputusan 2026-10-27, lihat `pemasaran.md`, "Keputusan yang sudah diambil"). Lihat bagian "Yang perlu dikerjakan pemilik" di bawah.

Dasar: [pemasaran.md](pemasaran.md) (positioning, empat tema, aturan boost, batasan klaim), [design/tokens.css](design/tokens.css) (arah visual opsi A), [konsep.md](konsep.md), dan [monetisasi.md](monetisasi.md).

Nama akun sosial media: **`rizqflowra`** (`@rizqflow` sudah dipakai pihak lain; keputusan pemilik 2026-10-24). Tautan di caption memakai UTM `utm_source=instagram`, karena tautan di caption Instagram tidak bisa diklik: caption menyebut "link di bio", dan tautan bio adalah `https://roziqrizal.com/karya/rizqflow?utm_source=instagram&utm_medium=bio`. Tautan per posting memakai `utm_medium=post` dan `utm_campaign` sesuai tema.

---

## Bagian 1: Spesifikasi template visual

Semua template feed memakai kanvas **1080 × 1080 px** (feed Instagram). Carousel memakai ukuran yang sama per slide. Untuk Reels (video vertikal, nanti), ukurannya **1080 × 1920 px** dan memakai grid yang sama; ini belum perlu dibuat sekarang.

### Aturan bersama (berlaku untuk semua template)

- **Palet dasar** (dari `tokens.css`, mode terang): latar `--rf-background` `#faf9f7`; kartu `--rf-surface-container-lowest` `#ffffff` atau `--rf-surface-container-low` `#f5f3f1`; teks utama `--rf-on-surface` `#1b1c1b`; teks sekunder `--rf-on-surface-variant` `#424845`; garis `--rf-outline-variant` `#c2c8c3`; aksen utama `--rf-primary` `#4d6359`; permukaan gelap `--rf-inverse-surface` `#30312f` dengan teks `--rf-inverse-on-surface` `#f2f0ee`.
- **Warna ruang** hanya untuk strip, ikon, dan blok kecil, bukan untuk teks kecil: Memberi `--rf-room-1` `#3b6fa8`; Diri `--rf-room-2` `#c4653a`; Keluarga `--rf-room-3` `#2a9a80`.
- **Font**: judul memakai **Libre Caslon Text** (`--rf-font-display`); semua teks lain dan **semua angka** memakai **Manrope** (`--rf-font-ui`). Kalau salah satu tidak tersedia di Canva, unduh dari Google Fonts (lisensi OFL, sama seperti di `res/font` aplikasi).
- **Ukuran teks minimal** 32 px di kanvas 1080 px (agar tetap terbaca di layar ponsel). Judul 72–88 px.
- **Logo**: logo mark kecil di pojok kiri atas, lebar sekitar 96–120 px, dengan margin 64 px. Sumber: `docs/design/logo/rizqflow-icon-mark.svg` (atau versi putih untuk latar gelap). Logo tidak boleh diubah bentuk atau warnanya di luar varian yang ada.
- **Handle** `rizqflowra` di pojok kanan bawah, ukuran 28–32 px, warna teks sekunder.
- **Margin aman**: 64 px dari tepi kanvas.
- **Tanpa merah, tanpa streak, tanpa ikon peringatan merah** (prinsip "tenang, bukan panik" dari `konsep.md`).
- **Kontras**: uji kontras teks di atas setiap latar sebelum dipakai (target WCAG AA). Warna ruang tidak diuji sebagai latar teks kecil di sini.
- **Disclaimer zakat** (wajib di template T1): "Bantuan hitung, bukan fatwa." dan sebutkan asumsi yang dipakai.

---

### T1: Judul Haul (Edukasi zakat mal, tema 1)

- **Dipakai untuk**: tema 1 "Edukasi zakat mal" (contoh: "Hartamu sudah haul belum?", "Beda nisab emas dan perak", "Kenapa haul pakai tahun Hijriyah").
- **Latar**: `#faf9f7`.
- **Elemen**:
  1. Logo mark pojok kiri atas.
  2. Label kecil di atas judul (Manrope, 32 px, `#4d6359`): "Zakat mal" atau "Haul Hijriyah".
  3. Judul pertanyaan besar (Libre Caslon Text, 80 px, `#1b1c1b`), maksimal 3 baris, rata kiri.
  4. Satu garis aksen `#4d6359` tebal 6 px sepanjang 120 px di bawah judul.
  5. Kotak asumsi (latar `#ffffff`, garis `#c2c8c3` 1 px, radius 16 px): "Asumsi yang dipakai: nisab 85 gram emas, kadar 2,5%, haul 1 tahun Hijriyah." (Manrope, 32 px).
  6. Baris disclaimer (Manrope, 28 px, `#424845`): "Bantuan hitung, bukan fatwa."
  7. Handle `rizqflowra` pojok kanan bawah.
- **Slide carousel**: slide 1 judul; slide 2–3 penjelasan singkat dengan layout yang sama; slide terakhir CTA "Link di bio".

### T2: Tiga Ruang (Konsep hak, tema 2)

- **Dipakai untuk**: tema 2 "Konsep hak" (contoh: "Gajimu sudah dibagi untuk hak siapa saja?"), dengan visual Memberi / Diri / Keluarga.
- **Latar**: `#faf9f7`.
- **Elemen**:
  1. Logo mark pojok kiri atas.
  2. Judul pertanyaan besar (Libre Caslon Text, 72–80 px, `#1b1c1b`), di bagian atas.
  3. Tiga kartu berdampingan atau bertumpuk vertikal (latar `#ffffff`, radius 20 px, garis `#c2c8c3`).
  4. Setiap kartu punya strip atas 12 px dengan warna ruang (Memberi `#3b6fa8`, Diri `#c4653a`, Keluarga `#2a9a80`), ikon ruang sederhana berbentuk geometris (bukan ilustrasi gambar), dan nama ruang (Manrope, 40 px, `#1b1c1b`). Satu baris contoh isi di bawah nama (Manrope, 28–32 px, `#424845`): Memberi = zakat, sedekah, donasi; Diri = dana darurat, investasi, belajar; Keluarga = nafkah, tabungan bersama, tanggungan.
  5. Garis penghubung tipis `#c2c8c3` dari atas ke ketiga kartu, sebagai simbol "satu sumber rezeki dialirkan".
  6. Baris pesan (Manrope, 32 px): "Rezeki mengalir, setiap hak terpenuhi."
  7. Handle `rizqflowra` pojok kanan bawah.
- **Catatan**: nama ruang dan isi contoh mengikuti `konsep.md` (label netral; inti tidak Islami). Ruang "Memberi" tetap netral; sebut zakat sebagai contoh isi, bukan sebagai nama ruang.

### T3: Layar Aplikasi (Demo, tema 3)

- **Dipakai untuk**: tema 3 "Demo aplikasi" (contoh: catat kilat, Denah, hitung haul). Juga dipakai sebagai slide "tangkapan layar" di carousel.
- **Latar**: `#30312f` (`--rf-inverse-surface`), teks `#f2f0ee` (`--rf-inverse-on-surface`). Varian terang: latar `#faf9f7`.
- **Elemen**:
  1. Judul pendek (Libre Caslon Text, 72 px, `#f2f0ee`), maksimal 2 baris, misalnya "Catat dalam 5 detik." atau "Semua ruang, satu layar."
  2. Bingkai ponsel sederhana atau kartu layar: persegi panjang dengan radius 20 px, berisi **tangkapan layar aplikasi sungguhan** (`[TANGKAPAN LAYAR: Denah / catat kilat / hitung haul]`). Ukuran bingkai sekitar 560 × 900 px, rata tengah atau miring 0 derajat (jangan dimiringkan).
  3. Satu baris keterangan di bawah bingkai (Manrope, 32 px, `#f2f0ee`), maksimal 60 karakter.
  4. Logo mark pojok kiri atas (versi putih, `rizqflow-icon-mark` varian putih).
  5. Handle `rizqflowra` pojok kanan bawah.
- **Catatan**: sebelum tangkapan layar sungguhan siap (roadmap P0 "Ganti hero landing page dengan tangkapan layar aplikasi sungguhan"), pakai kotak placeholder bertuliskan "TANGKAPAN LAYAR" dan jangan posting template ini sebagai bukti fitur. Template Reels 15 detik memakai grid yang sama dalam 1080 × 1920.

### T4: Catatan Pembangun (Cerita membangun, tema 4)

- **Dipakai untuk**: tema 4 "Cerita membangun" (build in public): kenapa tanpa iklan, kenapa ekspor dan keamanan gratis, keputusan desain.
- **Latar**: `#ffffff` (`--rf-surface-container-lowest`), dengan blok kutipan `#efeeec` (`--rf-surface-container`).
- **Elemen**:
  1. Label "Catatan #n" (Manrope, 32 px, `#4d6359`, huruf tebal) di atas kiri.
  2. Judul (Libre Caslon Text, 72 px, `#1b1c1b`), maksimal 2 baris: contoh "Kenapa tanpa iklan?"
  3. Blok isi (Manrope, 36 px, `#1b1c1b`, line-height 1.4), maksimal 4 baris pendek. Bila ada kutipan atau pesan inti, pakai blok `#efeeec` dengan garis kiri 6 px `#4d6359`.
  4. Baris penutup (Manrope, 28 px, `#424845`): "Dibangun sendiri, paruh waktu, sambil dipakai untuk mengatur keuanganku sendiri." Sesuai kenyataan (pemilik paruh waktu).
  5. Logo mark pojok kiri atas dan handle `rizqflowra` pojok kanan bawah.
- **Catatan**: foto wajah pemilik (tampil sebagai founder, keputusan 2026-10-26) bisa ditambahkan sebagai lingkaran 200 px di pojok kanan atas, tetapi tidak wajib untuk template dasar.

---

## Bagian 2: Draf caption (12 caption, 3 per tema)

Setiap caption: **hook** (baris pertama, yang muncul sebelum "selengkapnya"), **body**, **CTA**, **hashtag**. Catatan produksi (template, slide, verifikasi) ditulis di bawah tiap caption dan tidak ikut diposting.

Aturan naskah (dari `pemasaran.md` dan `konsep.md`):
- Tidak ada klaim enkripsi "server tidak bisa membaca data" sebelum terbukti di perangkat dengan Android Keystore asli (Tahap 13).
- Tidak ada harga Pro atau Sync di caption (harga final belum diputuskan).
- Tidak ada fitur yang belum rilis dinyatakan sebagai sudah ada; tangkap otomatis dari notifikasi adalah v1.1 (Pro) dan tidak disebut sebagai fitur rilis 1.
- Setiap fiqh zakat wajib menyebut asumsi yang dipakai, dan posting bertanda **[VERIFIKASI KITAB]** harus diperiksa pemilik (atau ustadz) sebelum dipublikasikan (aturan di `pemasaran.md`, "Batasan klaim").
- Tanggal Hijriyah selalu disebut sebagai perkiraan.

### Tema 1: Edukasi zakat mal (T1)

**Caption 1.1: Haul**

> Hartamu sudah haul belum?
>
> Zakat mal tidak dihitung per bulan atau per tahun Masehi. Patokannya **haul**: kepemilikan harta yang sudah berjalan satu tahun dalam kalender Hijriyah.
>
> Tahun Hijriyah sekitar 354 hari, jadi tanggal haul bergeser sekitar 11 hari setiap tahun Masehi. Itu sebabnya banyak orang lupa tanggal haulnya.
>
> Rizqflow menghitung nisab dan haul dengan asumsi: nisab 85 gram emas, kadar 2,5%, haul 1 tahun Hijriyah. Tanggal Hijriyah di aplikasi adalah perkiraan.
>
> Bantuan hitung, bukan fatwa. Daftar tunggu rilisnya di link bio.
>
> #zakatmal #haulhijriyah #keuangankeluarga #rizqflow

*Produksi*: T1, carousel 3 slide (judul, penjelasan haul, asumsi + CTA). **[VERIFIKASI KITAB]**: angka 354 hari dan pola haul; cek ulang rujukannya bila ditampilkan sebagai fakta fikih.

**Caption 1.2: Nisab emas dan perak**

> Beda nisab emas dan perak, dan kenapa ini penting untuk zakatmu.
>
> Nisab adalah batas harta minimal sebelum zakat wajib. Ada dua patokan yang dikenal: emas dan perak. Nilainya tidak sama, karena harga emas dan perak berbeda.
>
> Rizqflow memakai **emas (85 gram)** sebagai acuan, dan harga emas dimasukkan manual di versi gratis. Kalau kamu ingin patokan lain, tulis di kolom catatan atau tanyakan ke ustadz kepercayaanmu.
>
> Bantuan hitung, bukan fatwa. Link di bio.
>
> #zakatmal #nisab #keuanganislami #rizqflow

*Produksi*: T1, 1 slide atau carousel 2 slide. **[VERIFIKASI KITAB]**: pernyataan soal patokan perak dan pilihan emas sebagai acuan; keputusan pemilik soal fikih masih terbuka (`konsep.md`, "Haul saat harta turun di bawah nisab"). Jangan menyebut angka nisab perak sebelum diverifikasi.

**Caption 1.3: Kenapa tahun Hijriyah**

> Kenapa haul pakai tahun Hijriyah, bukan tahun Masehi?
>
> Karena ibadah zakat dikaitkan dengan waktu kepemilikan menurut kalender Islam. Rizqflow menghitung haul dengan kalender Hijriyah berbasis tabel Umm al-Qura, jadi tanggalnya bisa berbeda sehari dari penetapan pemerintah.
>
> Itu sebabnya tanggal Hijriyah di aplikasi selalu ditandai sebagai perkiraan. Cek juga jadwal resmi di kementerian atau lembaga yang kamu percaya.
>
> Bantuan hitung, bukan fatwa.
>
> #haulhijriyah #zakatmal #kalenderhijriyah #rizqflow

*Produksi*: T1, carousel 3 slide. **[VERIFIKASI KITAB]** untuk klaim "Kenapa" (dasar fikih). Penyebutan Umm al-Qura sesuai keputusan 2026-09-21 (`konsep.md`).

### Tema 2: Konsep hak (T2)

**Caption 2.1: Gaji dan hak**

> Gajimu sudah dibagi untuk hak siapa saja?
>
> Kebanyakan orang menerima gaji, lalu bertanya uangnya habis ke mana. Coba balik pertanyaannya: sebelum belanja, sudah ada bagian untuk apa saja?
>
> Di Rizqflow, rezeki yang masuk dialirkan ke tiga ruang inti: **Memberi**, **Diri**, dan **Keluarga**. Kamu bisa menambah ruang sendiri sesuai peranmu.
>
> Daftar tunggu rilisnya di link bio.
>
> #keuangankeluarga #budgetingkeluarga #rizqflow #rezeki

*Produksi*: T2, 1 slide utama + 1 slide penjelasan ruang. Tidak ada klaim fiqh di caption ini.

**Caption 2.2: Pesan inti**

> Aplikasi lain bertanya: uangmu habis ke mana?
>
> Rizqflow bertanya: apakah setiap hakmu sudah tertunaikan?
>
> Bedanya kecil, tapi mengubah cara kamu melihat gaji. Hak Allah lewat memberi, hak diri untuk tumbuh dan siaga, hak keluarga untuk dinafkahi dan dicukupi.
>
> Contoh sederhana: dari gaji Rp 10 juta, 10% ke Memberi, 40% ke Diri, dan 50% ke Keluarga. Angkanya bisa kamu ubah sesuai kondisi.
>
> Link di bio untuk daftar tunggu.
>
> #rizqflow #keuangankeluarga #memberi #hakkeluarga

*Produksi*: T2 dengan slide contoh alokasi. Contoh persentase adalah ilustrasi, bukan rekomendasi. Angka di slide wajib ditandai "contoh" dan memakai Manrope (angka selalu Manrope).

**Caption 2.3: Uang habis tanpa sadar**

> Uangmu habis tanpa sadar? Biasanya bukan karena boros.
>
> Seringnya karena tidak ada aturan pembagian di awal bulan. Uang keluar satu-satu, lalu tiba-tiba akhir bulan dan hak yang seharusnya terpenuhi malah terlewat.
>
> Rizqflow dibuat untuk membalik urutannya: atur dulu pembagian per ruang, lalu lihat setiap ruang sudah terpenuhi atau belum. Tanpa merah, tanpa menghakimi.
>
> Tautan daftar tunggu ada di bio.
>
> #keuangankeluarga #budgetingkeluarga #rizqflow #mulaidariaturan

*Produksi*: T2, carousel 3 slide (masalah, aturan, cara membaca ruang). Tidak ada klaim statistik; jika ingin memakai angka literasi keuangan syariah (SNLIK 2025, 43%), cantumkan sumbernya di slide.

### Tema 3: Demo aplikasi (T3)

**Caption 3.1: Catat kilat**

> Catat pengeluaran dalam 5 detik. Ini caranya.
>
> Ketuk favorit, nominal dan kategori sudah terisi. Tidak perlu membuka form panjang setiap kali jajan kopi.
>
> Tujuannya sederhana: mencatat harus lebih murah daripada lupa.
>
> Rekaman layar di video. Daftar tunggu di link bio.
>
> #rizqflow #catatkeuangan #pengeluaran #aplikasikeuangan

*Produksi*: T3, Reels 15 detik (1080 × 1920) atau carousel dengan tangkapan layar. **[TANGKAPAN LAYAR/REKAMAN]** dibuat dari build debug atau build pemilik, bukan dari mockup. Ukur durasi "5 detik" sebelum memakai judul ini; kalau tidak akurat, ganti judulnya dengan "Catat kilat tanpa form panjang". Pastikan fitur yang ditunjukkan ada di kode dan lolos tes.

**Caption 3.2: Denah**

> Semua ruang dalam satu layar.
>
> Denah menunjukkan jatah tiap ruang dan mana yang sudah terpenuhi. Angkanya tidak menyalahkan, hanya memberi tahu.
>
> Kamu bisa lihat sekilas: ruang mana yang masih longgar, dan mana yang perlu perhatian.
>
> Tautan daftar tunggu ada di bio.
>
> #rizqflow #budgetingkeluarga #keuangankeluarga #dashboardkeuangan

*Produksi*: T3, carousel 2–3 slide (tangkapan layar Denah dengan data contoh atau data sungguhan yang sudah disamarkan). Jangan memakai data pribadi pemilik tanpa menyamarkannya.

**Caption 3.3: Hitung haul**

> Hitung nisab dan haul zakat mal dalam satu layar.
>
> Masukkan harta, harga emas, dan tanggal mulai haul. Rizqflow menampilkan apakah nisab tercapai dan kapan haul genap.
>
> Asumsinya ada di layar: nisab 85 gram emas, kadar 2,5%, haul 1 tahun Hijriyah. Tanggal Hijriyah adalah perkiraan.
>
> Bantuan hitung, bukan fatwa. Link di bio.
>
> #zakatmal #haulhijriyah #rizqflow #hitungzakat

*Produksi*: T3, video layar 15 detik atau carousel. **[VERIFIKASI KITAB]**: pastikan layar yang direkam memang menampilkan asumsi dan disclaimer. Angka contoh harta harus fiktif dan ditandai "contoh".

### Tema 4: Cerita membangun (T4)

**Caption 4.1: Kenapa tanpa iklan**

> Kenapa Rizqflow tidak pakai iklan?
>
> Aplikasi yang menyimpan data keuangan dan data zakat, lalu menampilkan iklan, merusak kepercayaan. Penghasilannya juga kecil, jadi tidak sebanding dengan risikonya.
>
> Karena itu Rizqflow dibiayai lewat versi Pro sekali bayar dan langganan Sync untuk yang butuh sinkron antar-perangkat. Tanpa iklan, tanpa pelacak iklan.
>
> Ikuti perjalanan membangunnya di sini.
>
> #buildinpublic #rizqflow #aplikasikeuangan #indiedev

*Produksi*: T4, carousel 2 slide dengan blok kutipan. Jangan menyebut harga Pro atau Sync (belum final). Kalimat "tanpa pelacak iklan" sesuai `pemasaran.md` (analitik ringan, bukan pelacak iklan berat), tetapi cek ulang setelah keputusan analitik final.

**Caption 4.2: Ekspor dan keamanan gratis**

> Kenapa ekspor data dan keamanan selalu gratis di Rizqflow?
>
> Data keuangan itu milikmu. Kalau kamu ingin pindah atau membuat cadangan, kamu berhak mengambilnya tanpa membayar. Kunci PIN dan biometrik juga tidak dijual sebagai fitur premium, karena data yang tidak terlindungi bukan fitur.
>
> Dasar zakat satu profil juga gratis, karena itu kewajiban agama, bukan paket premium.
>
> Ini prinsip yang dipegang, bukan janji sementara.
>
> #buildinpublic #rizqflow #keamanandata #keuanganislami

*Produksi*: T4, 1 slide + carousel. Klaim "selalu gratis" sesuai keputusan di `monetisasi.md`. **Tidak boleh** menulis "datamu dienkripsi, bahkan server tidak bisa membacanya" di caption ini; klaim itu menunggu bukti di perangkat (Tahap 13).

**Caption 4.3: Keputusan desain yang sulit**

> Keputusan desain yang paling sulit di Rizqflow: tidak memakai warna merah untuk uang yang kurang.
>
> Aplikasi keuangan sering membuat panik lewat merah, streak, atau peringatan keras. Rizqflow memilih yang lebih tenang: peringatan lembut, tanpa streak, dan selalu ada teks di samping setiap ikon.
>
> Tujuannya: kamu mau kembali mencatat besok, bukan merasa bersalah hari ini.
>
> Masih dibangun paruh waktu. Daftar tunggu di link bio.
>
> #buildinpublic #rizqflow #desainproduk #keuangantenang

*Produksi*: T4, carousel 2–3 slide (screenshot ringkas status lembut, bila sudah siap). Pernyataan "tanpa streak" dan "tanpa merah" sesuai prinsip di `konsep.md`; cek lagi bila desain berubah.

---

## Bagian 3: Hashtag dan aturan posting

- Pakai 4–6 hashtag per caption; hashtag yang sama tidak diulang di tiap posting berturut-turut.
- Setiap posting menyebut "link di bio" atau "daftar tunggu di bio", bukan tautan mentah yang tidak bisa diklik.
- Untuk tautan per posting (opsional, bila dibutuhkan untuk penghitungan UTM di Story atau slide): `https://roziqrizal.com/karya/rizqflow?utm_source=instagram&utm_medium=post&utm_campaign=<tema>` dengan `<tema>` = `zakat`, `hak`, `demo`, atau `build`.
- Ritme 2–3 posting per minggu sesuai roadmap P1, dengan 3 tema zakat dan konsep hak sebagai inti awal, lalu demo dan cerita membangun bergantian.

---

## Bagian 4: Checklist sebelum setiap posting

- [ ] Template dipakai sesuai tema (T1–T4), ukuran 1080 × 1080 (atau 1080 × 1920 untuk Reels).
- [ ] Palet dan font dari `tokens.css`; judul Libre Caslon Text, angka dan teks lain Manrope.
- [ ] Logo mark dan handle `rizqflowra` terpasang.
- [ ] Tidak ada harga Pro atau Sync di teks.
- [ ] Tidak ada klaim enkripsi "server tidak bisa membaca" dan tidak ada fitur yang belum rilis dinyatakan sudah ada.
- [ ] Zakat: asumsi disebut, disclaimer "bukan fatwa" ada, tanggal Hijriyah ditandai perkiraan, dan posting bertanda **[VERIFIKASI KITAB]** sudah diperiksa.
- [ ] Tangkapan layar dan rekaman memakai data contoh atau sudah disamarkan.
- [ ] Caption menyebut "link di bio", bukan tautan mentah.

---

## Yang perlu dikerjakan pemilik

Yang tidak bisa dikerjakan di sini (Claude tidak menghasilkan gambar):

1. **Eksekusi visual di Canva** (keputusan 2026-10-27): ambil spesifikasi T1–T4 dari bagian 1 dan buat template-nya di Canva, lalu isi 12 caption di bagian 2. Bila Canva tidak tersedia untuk Libre Caslon Text atau Manrope, unduh font dari Google Fonts.
2. **Tangkapan layar dan rekaman sungguhan** untuk T3 dan roadmap P0 "Ganti hero landing page dengan tangkapan layar aplikasi sungguhan". Pakai build yang sudah lolos tes, bukan mockup.
3. **Verifikasi kitab atau ustadz** untuk semua posting bertanda [VERIFIKASI KITAB] (nisab, haul, tahun Hijriyah). Ini aturan di `pemasaran.md` dan `konsep.md`, bukan keputusan Claude.
4. **Akun sosial media**: profil Instagram `@rizqflowra` (sudah dibuat 2026-10-24) perlu diisi sesuai draf di `pemasaran.md`, diubah ke akun Profesional/Bisnis, dan nama yang sama diamankan di Threads, TikTok, dan X (roadmap P0, butir "Amankan nama `rizqflowra`").
5. **Persetujuan naskah**: caption ini draf. Pemilik menentukan mana yang dipakai, diubah, atau dibuang.
6. **Keputusan terbuka yang memengaruhi konten**: siapa pemakai `@rizqflow` di Instagram (`pemasaran.md`, "Keputusan yang masih terbuka"), dan harga final Pro (jangan disebut di konten sampai diputuskan).

**Next step yang disarankan** (bukan dikerjakan sekarang): setelah pemilik menyetujui naskah, template Canva dibuat dari spesifikasi di bagian 1, lalu tiap caption dirender ke posting. Claude bisa membantu memperbarui naskah berikutnya dan memeriksa checklist di bagian 4, tetapi tidak membuat gambar final.
