# Rizqflow — Desain

Arah visual **opsi A** (identitas homepage roziqrizal.com), diputuskan 2026-09-19. Folder ini berisi design tokens dan prototipe klik untuk layar kunci. Prototipe ini untuk menilai tampilan dan alur, **bukan kode produksi**.

## Isi folder

| File | Fungsi |
|---|---|
| [tokens.css](tokens.css) | Design tokens: satu tempat untuk warna, font, jarak, bentuk, dan gerak |
| [prototype.html](prototype.html), [prototype.css](prototype.css), [prototype.js](prototype.js) | Prototipe klik (HTML, CSS, dan JavaScript murni, tanpa build) |
| [Web (server/): design system dan komponen](#web-server-design-system-dan-komponen) | Cara memakai `rizqflow.css`, `rizqflow.js`, dan komponen `<x-rf.*>` di web Laravel (bagian di bawah) |
| [web-mockup/](web-mockup/README.md) | Mockup web (artifact Claude Design, 2026-10-24): sumber 9 layar, papan riset dan navigasi, CSS bersama |

## Cara membuka

Klik dua kali `prototype.html`. Font Manrope dan Libre Caslon Text dimuat dari Google Fonts, jadi butuh internet; tanpa internet prototipe tetap jalan dengan font cadangan.

Kontrol di panel kiri: tema (otomatis, terang, gelap), ukuran teks (100%, 150%, 200%), dan reset data contoh.

Langsung ke layar tertentu lewat parameter URL: `prototype.html?screen=denah&theme=dark&scale=2`.

| `screen=` | Layar |
|---|---|
| `denah`, `transaksi`, `ruang`, `lainnya` | Tab utama |
| `alokasi` (tambah `&edit=1` untuk mode Ubah sekali ini) | S07 Pratinjau alokasi |
| `catat`, `catat-keluar` (opsional `&amount=2000000`) | S06 Catat transaksi |
| `detail-memberi`, `detail-diri`, `detail-keluarga` | S11 Detail ruang |
| `haul` | S16 Kartu haul |
| `pro` | S21 Paywall |
| `kilat` | S24 Catat kilat |
| `koreksi` (opsional `&acct=tunai&actual=265000`) | S25 Koreksi saldo |
| `pengingat` | S26 Pengingat harian |
| `notif` | Notifikasi malam (F8) |
| `draf`, `tangkap` | S27 Draf dan S28 Tangkap otomatis (otomatis dalam mode Pro; `&pro=1` membuka mode Pro di layar mana pun) |
| `splash`, `masuk`, `daftar` | S29 Splash (beralih sendiri setelah 1,4 detik), S30 Masuk (kolom nama pengguna dan sandi, Google, Gmail), S31 Daftar. Akun lokal disimulasikan di `rf-accounts` (nama pengguna dan sidik sederhana, bukan keamanan). Riwayat masuk disimulasikan di penyimpanan browser (`rf-login`); tombol *Mulai dari splash* di panel kiri dan Lainnya, *Akun Google* mengosongkannya |
| `sambutan`, `pola`, `persen`, `akun` | S01–S04 Onboarding (tambah `&template=kosong` untuk pola Mulai kosong). Tombol *Mulai dari awal* di panel kiri menjalankan seluruh F1 |
| `kosong`, `kosong-tanpa-ruang` | S05 Denah pengguna baru: dengan pola Tiga hak, atau tanpa ruang sama sekali |
| `aturan` (opsional `&focus=diri`) | S12 Aturan alokasi |
| `txdetail` (opsional `&id=t7`) | S09 Detail dan edit transaksi (`t1` sampai `t7` adalah data contoh) |
| `kelola` (opsional `&tab=kategori` atau `favorit`) | S13 Akun, kategori, dan favorit |
| `zakat` (opsional `&haul=genap`), `harta`, `tunai` | S14 Beranda Zakat, S15 Profil harta, S17 Tunaikan zakat |
| `keamanan`, `pin`, `kunci` | S19 Keamanan, buat PIN, simulasi kunci aplikasi |
| `cadangan`, `impor` | S20 Backup dan restore, pratinjau impor dari Transaksi Harian |
| `tampilan`, `tentang` | S22 Tampilan dan bahasa, S23 Tentang dan disclaimer |

## Cakupan prototipe

**Sudah ada:** S05 Denah, S06 Catat (pemasukan dan pengeluaran), S07 Pratinjau alokasi (termasuk Ubah sekali ini), S11 Detail ruang, S16 Kartu haul, S21 Paywall. Versi ringkas: S08 Transaksi, S10 Ruang, S18 Lainnya.

**Ditambah 2026-09-20 (kemudahan mencatat):** S24 Catat kilat, S25 Koreksi saldo, S26 Pengingat harian, notifikasi malam dengan balasan langsung, petunjuk hari kosong di S08, saklar Jadikan favorit di S06, serta S27 Draf dari notifikasi dan S28 Tangkap otomatis (keduanya v1.1, Pro).

**Flow yang bisa dicoba:**
- **F0** splash lalu masuk (2026-09-21, permintaan pemilik): S29 Splash, S30 Masuk dengan Google dan Gmail (simulasi), lalu pola ruang (F1). Yang sudah pernah masuk langsung ke Denah. S30 menggantikan S01 Sambutan; alur awal default prototipe tetap Denah data contoh supaya tes lama tidak berubah, dan F0 dijalankan lewat `?screen=splash` atau tombol di panel.
- **F5** dari harta sampai zakat ditunaikan: kartu Zakat di ruang Memberi atau Lainnya, Zakat; isi harta (S15); di bawah nisab dipantau, mencapai nisab memulai haul; kartu *Hanya di prototipe* mempercepat haul supaya Tunaikan zakat (S17) bisa dicoba.
- **F7** backup dan restore: buat cadangan, ubah data, pulihkan; sandi salah dan berkas rusak menampilkan pesan tanpa menyentuh data.
- **F1** pertama kali membuka aplikasi: S01 Sambutan, S02 pola (Tiga hak atau Mulai kosong), S03 persentase (slider, Keluarga otomatis menerima sisa sehingga total selalu 100%), S04 akun pertama dan saldo awal, lalu Denah kosong dengan dua jalan (catat rezeki pertama, atau coba data contoh). Pola Mulai kosong berakhir di Denah tanpa ruang dengan tombol Pakai pola Tiga hak.
- **F4** ubah aturan: Ruang, Pembagian rezeki (atau Atur aturan di detail ruang), geser persentase; Simpan aktif hanya bila total 100% dan ada perubahan; keluar dengan perubahan menanyakan Buang perubahan; aturan baru langsung dipakai pemasukan berikutnya.
- **F2** rezeki masuk dialirkan, penuh: bar dan cincin bergerak, status ruang berubah.
- **F3** pengeluaran, penuh: banner lembut saat jatah terlampaui (tidak pernah memblokir) dan snackbar Urungkan.
- **F5** sebagian: ruang Memberi lalu kartu Zakat mal lalu kartu haul.
- **F6** sheet Pro dengan tiga pemicu (Rizqflow Pro, Tangkap otomatis, Widget catat kilat): manfaat menyesuaikan pemicu, dan aksi tadi dilanjutkan setelah beli. Tidak ada pembayaran sungguhan.
- **F8** mengejar yang terlewat: Catat kilat (favorit satu ketukan), notifikasi malam dengan balasan seperti "kopi 25000", Koreksi saldo (selisih, saldo lebih besar, cocok), dan petunjuk hari kosong.
- **F9** tangkap otomatis (v1.1): beli, beri akses (simulasi), simulasikan pembayaran masuk, lalu Setujui, Ubah, atau Abaikan draf, termasuk tanda "Mungkin sudah dicatat".

**Ditambah 2026-09-21:** onboarding S01–S04 (flow F1), S12 Aturan alokasi (flow F4), Denah pengguna baru (kosong, tanpa ruang), dan mode demo yang terpisah dari data pengguna (penanda di header, keluar dari demo mengembalikan data pengguna). Rezeki yang tidak habis terbagi kini tersimpan sebagai "belum dialirkan" (sebelumnya hilang saat total persentase kurang dari 100%).

**Ditambah 2026-09-21 (melengkapi seluruh layar):** S09 detail dan edit transaksi (ubah, hapus, Urungkan; pemasukan menghitung ulang alokasi dari persentase saat itu), tab Transfer di S06, pencarian dan filter di S08, tambah dan arsipkan ruang (S10, S11; ruang ke-6 memicu paywall; ruang ke-4 dan seterusnya berwarna netral), S13 akun, kategori, dan favorit, S14 sampai S17 zakat (harga emas dan harta bisa diubah, status nisab dan haul dihitung, Tunaikan zakat membuat transaksi dan memulai haul baru; tanggal Hijriyah dari kalender Umm al-Qura milik browser), S19 keamanan (buat PIN dua kali, kunci simulasi, salah lima kali menahan sementara), S20 backup dan restore (sandi, sandi salah, berkas rusak, konfirmasi timpa, ekspor CSV, pratinjau impor), S22 tampilan, S23 tentang. Pembulatan alokasi kini sisa terbesar, sama dengan `AllocationEngine` di `:domain`.

**Belum ada:** tidak ada layar S01–S28 yang belum dibuat. Yang masih disimulasikan: pembayaran, izin, notifikasi, berbagi berkas, pilih berkas CSV, sidik jari, dan harga emas otomatis. Wireframe teks ada di [../wireframe.md](../wireframe.md).

**Diuji splash dan masuk (F0) 2026-09-21:** 17 pemeriksaan klik otomatis (splash beralih sendiri, masuk simulasi, riwayat di penyimpanan browser, langsung ke Denah bila sudah pernah masuk, Keluar, dan tanpa overflow di mode gelap dan teks 200%). Regresi 82 dan 116 pemeriksaan sebelumnya tetap lulus.

**Diuji layar lengkap 2026-09-21:** 116 pemeriksaan klik otomatis (Chrome headless, tidak disimpan di repo) untuk tab Transfer, S08 sampai S10, S13 sampai S15, S17, S19, S20, S22, dan S23, ditambah pemeriksaan overflow 15 layar di mode gelap dan teks 200%; tanpa galat konsol. Bersama 82 pemeriksaan layar awal, seluruhnya lulus.

**Diuji dengan pola data nyata 2026-09-21:** 26 baris representatif dari Transaksi Harian (deskripsi panjang, catatan panjang, pasangan transfer, gaji Rp 17.805.137) dimuat ke prototipe di salinan sementara (isi data nyata tidak masuk repo). Temuan tata letak di teks 200%: baris daftar terjepit karena nominal 8 digit berdampingan dengan teks (nominal kini pindah ke baris bawah pada 150% dan 200%), keterangan yang tidak terpecah, dan kolom cari terpotong (teksnya dipendekkan). Pembagian Rp 17.805.137 menjadi 1.780.514 / 5.341.541 / 10.683.082 habis tanpa selisih. Temuan tentang data (transfer dua baris, tanda nominal tidak konsisten) ada di [../model-data.md](../model-data.md).

**Diuji 2026-09-21 (layar awal):** skrip klik otomatis baru (Chrome headless, tidak disimpan di repo) menjalankan 82 pemeriksaan: regresi F2 dan F3, F1 penuh (kedua pola, batas slider, nama akun wajib), rezeki pertama di data baru, F4 (total 105%, simpan, buang perubahan, aturan baru dipakai), jalur tanpa ruang (sisa tidak menggandakan saldo), mode demo, dan Tangkap otomatis di data baru; tanpa galat konsol. Layar baru diperiksa di mode terang, gelap, dan teks 200% tanpa overflow.

**Diuji sebelumnya:** skrip klik otomatis (Chrome headless) menjalankan F2 dan F3 termasuk Urungkan tanpa error konsol; tampilan diperiksa di mode terang, gelap, dan teks 150% dan 200%. Pembaruan 2026-09-20: skrip klik otomatis baru (Chrome headless, tidak disimpan di repo) menjalankan 63 pemeriksaan untuk F2, F3, F6, F8, dan F9 tanpa galat konsol; layar baru diperiksa di mode gelap dan teks 200%, yang menemukan keterangan draf pecah baris dan sudah diperbaiki.

## Keputusan desain

- **Chrome dari homepage, warna data terpisah.** Warna latar, teks, tombol, dan kartu memakai palet sage homepage. Warna identitas ruang (bar alokasi dan cincin; 3 slot inti plus 5 slot custom) datang dari palet data yang divalidasi, karena sage yang pucat tidak cukup untuk membedakan tiga ruang.
- **Tenang, bukan panik.** Peringatan memakai kuning lembut dengan ikon dan teks, bukan merah. Melewati jatah tidak memblokir.
- **Status = ikon + teks.** Terpenuhi (centang), Berjalan (jam), Perlu perhatian (segitiga). Warna hanya penguat.
- **Angka besar selalu Manrope**, bukan serif; Libre Caslon Text hanya untuk judul layar dan judul bagian.
- **Aturan bar dan cincin** (dari panduan visualisasi data): tebal 16px, jeda 2px antar segmen, ujung data membulat 4px, jalur cincin berupa warna ruang yang lebih muda, legenda selalu ada, teks tidak diwarnai warna seri.
- **Denah = grid kartu**, bukan ilustrasi denah harfiah, supaya tetap rapi saat ruangnya banyak.
- **Teks besar (sp) ikut skala, ukuran elemen (dp) tidak.** Angka hero dibatasi 1,25x dan label navigasi 1,3x; kartu ruang menjadi 1 kolom di 150% ke atas. Pengujian 200% menemukan angka hero pecah di tengah, dan ini yang diperbaiki.
- **Status ruang mengikuti tipe ruang** (dikonfirmasi 2026-09-20, lihat [konsep.md](../konsep.md)): Menunaikan dan Menumbuhkan terpenuhi saat penggunaan mencapai jatah; Mencukupi masuk Perlu perhatian saat pemakaian 85% atau lebih dari jatah.

## Hasil validasi warna

Dijalankan dengan `validate_palette.js` (skill visualisasi data), bentuk `--pairs all` karena kartu ruang bisa bersebelahan di grid.

| Mode | Memberi | Diri | Keluarga | Permukaan | Hasil | CVD terburuk | Normal terburuk | Kontras |
|---|---|---|---|---|---|---|---|---|
| Terang | `#3b6fa8` | `#c4653a` | `#2a9a80` | `#ffffff` | Lolos semua | ΔE 9,7 | ΔE 15,9 | semua ≥ 3:1 |
| Gelap | `#4384d0` | `#cf6a3a` | `#22a37a` | `#1e201e` | Lolos semua | ΔE 8,7 | ΔE 18,2 | semua ≥ 3:1 |

Kontras teks (WCAG, target ≥ 4,5:1): teks utama di kartu terang 17,1; teks sekunder 9,4; sage utama di kartu terang 6,5; di mode gelap teks utama 12,8, teks sekunder 9,6, sage utama 9,6. Semua lolos.

Status memakai palet tetap yang reserved (good `#0ca30c`, warning `#fab219`, serious `#ec835a`, critical `#d03b3b`), selalu dengan ikon dan label. Prototipe hanya memakai good dan warning; critical sengaja tidak dipakai.

### Warna ruang custom: slot 4 sampai 8

**Diputuskan 2026-09-26.** Ruang custom (yang dibuat pengguna) mendapat 5 slot warna tambahan, jadi total 8 slot. Hue sengaja menghindari hijau (bentrok dengan sage chrome dan status good) dan merah kritis (status critical).

| Slot | Nama | Terang | Gelap |
|---|---|---|---|
| 4 | Violet | `#af6dec` | `#7547ee` |
| 5 | Plum | `#a04177` | `#aa558d` |
| 6 | Langit | `#099acf` | `#1fa3bb` |
| 7 | Emas tua | `#998214` | `#966c13` |
| 8 | Mawar | `#de4f74` | `#db426d` |

Validasi (`validate_palette.js`, permukaan terang `#ffffff` dan `#faf9f7`, gelap `#1e201e`):

| Cakupan | Mode | Hasil | CVD terburuk | Normal terburuk | Kontras |
|---|---|---|---|---|---|
| Slot 1–5, `--pairs all` | Terang | Lolos semua | ΔE 8,9 | ΔE 15,8 | semua ≥ 3:1 |
| Slot 1–5, `--pairs all` | Gelap | Lolos semua | ΔE 8,4 | ΔE 15,5 | semua ≥ 3:1 |
| Slot 1–8, adjacent | Terang | Lolos semua | ΔE 8,2 | ΔE 18,9 | semua ≥ 3:1 (di kedua permukaan terang) |
| Slot 1–8, adjacent | Gelap | Lolos semua | ΔE 8,6 | ΔE 18,9 | semua ≥ 3:1 |
| Slot 1–8, `--pairs all` | Terang dan gelap | **Gagal** (dua warna bisa sulit dibedakan) | ΔE 1,9 terang, 2,7 gelap | ΔE 10,7 terang, 9,8 gelap | — |

Artinya: slot 1–5 aman bersebelahan di urutan apa pun, dan itu persis batas ruang Gratis (5 ruang). Slot 6–8 hanya aman bila bersebelahan dengan tetangga slotnya. Delapan warna tidak mungkin semuanya lolos all-pairs pada batas ini (batas metode, bukan salah pilih), jadi aturan di bawah wajib diikuti.

**Aturan warna ruang**

1. **Warna melekat pada ruang, bukan pada urutan.** Slot disimpan di ruang itu (`colorSlot`) dan tidak berubah saat ruang lain ditambah, diurutkan, atau diarsipkan.
2. **Ruang inti** memakai slot 1–3 secara tetap (Memberi, Diri, Keluarga).
3. **Ruang custom** otomatis mendapat slot bebas terkecil di antara 4–8. Di layar S10, pengguna boleh mengganti ke slot bebas lain lewat pemilih lima titik. Tidak ada pemilih warna bebas; hanya slot yang sudah divalidasi. Slot yang sedang dipakai ruang aktif tidak muncul di pemilih, jadi tidak ada dua ruang aktif dengan warna sama.
4. **Mengarsipkan ruang membebaskan slotnya.** Riwayat ruang yang diarsipkan tampil netral dengan nama dan ikon, bukan warna lama. Memulihkan ruang mengambil slot bebas terkecil.
5. **Ruang ke-9 dan seterusnya (Pro)** memakai warna netral `--rf-room-neutral` dengan ikon dan nama sebagai identitas. Warna tidak pernah diputar ulang atau dibuat otomatis.
6. **Bar alokasi selalu diurutkan menurut slot**, bukan menurut urutan kustom pengguna di Denah. Dengan begitu segmen bersebelahan hanya pasangan adjacent yang sudah lolos. Ruang netral digabung menjadi satu segmen "Ruang lain".
7. **Identitas tidak boleh hanya lewat warna.** Setiap ruang selalu tampil dengan ikon dan nama (kartu, legenda, dan label langsung di bar). Ruang custom wajib punya ikon (usulan: dipilih dari daftar bawaan di S10, dengan ikon bawaan bila tidak dipilih).
8. **Warna ruang tidak dipakai untuk status.** Status tetap memakai palet status dan ikon plus teks.

## Peta ke Jetpack Compose

**Sudah diimplementasikan 2026-09-21** di `app/src/main/kotlin/.../ui/theme` (`Color.kt`, `Type.kt`, `Theme.kt`); token di `tokens.css` tetap sumbernya, dan `RizqflowTokensTest` menjaga kontras warna hasil validasi. Cuplikan di bawah tinggal sebagai penjelasan.

Nama peran warna di `tokens.css` sama dengan Material 3, jadi langsung dipetakan ke `ColorScheme`:

```kotlin
val RizqflowLight = lightColorScheme(
    primary = Color(0xFF4D6359),            // --rf-primary
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF8FA79B),
    onPrimaryContainer = Color(0xFF273C33),
    inversePrimary = Color(0xFFB3CCBF),
    secondary = Color(0xFF48626E),
    secondaryContainer = Color(0xFFCBE7F5),
    background = Color(0xFFFAF9F7),
    surface = Color(0xFFFAF9F7),
    onSurface = Color(0xFF1B1C1B),
    onSurfaceVariant = Color(0xFF424845),
    outline = Color(0xFF727874),
    outlineVariant = Color(0xFFC2C8C3),
    error = Color(0xFFBA1A1A),
    // ...sisanya dari tokens.css (surfaceContainer*, inverse*, onError*)
)
```

| Token | Di Compose |
|---|---|
| `--rf-room-1..8`, `--rf-room-neutral`, `--rf-status-*` | Objek tersendiri lewat `CompositionLocal` (bukan bagian `ColorScheme`); nilai terang dan gelap berbeda |
| `--rf-font-ui` (Manrope), `--rf-font-display` (Libre Caslon Text) | `FontFamily` dari berkas font atau Google Fonts (lisensi OFL); `display` hanya untuk judul |
| `--rf-size-*` | `sp` di `Typography`; angka hero dibatasi dengan `Density(fontScale = min(fontScale, 1.25f))` |
| Bar tumpuk | `Row` dengan `weight` per segmen dan `Arrangement.spacedBy(2.dp)` |
| Cincin | `Canvas` dengan `drawArc`; animasi `animateFloatAsState` (900 ms, kurva `0.2, 0, 0, 1`) |
| Gerak | Hormati pengaturan animasi sistem (durasi animasi 0) |

## Yang perlu dikonfirmasi setelah melihat prototipe

**Prototipe disetujui pemilik pada 2026-09-20.** Butir di bawah tetap dicatat sebagai asumsi yang ikut menjadi dasar; warna ruang ke-4 dan seterusnya sudah divalidasi dan diputuskan 2026-09-26 (lihat bagian Warna ruang custom di bawah). Aplikasi Android dan prototipe masih memakai warna netral untuk slot 4 ke atas.

1. **Mode gelap.** Ini turunan dari palet sage karena homepage belum punya mode gelap. Nilainya bisa diubah di satu blok di `tokens.css`.
2. **Ambang 85%** untuk Perlu perhatian di ruang Mencukupi: sudah dikonfirmasi 2026-09-20.
3. **Tanggal Hijriyah** pada layar S05 dan S16 hanyalah perkiraan; metode kalender masih keputusan terbuka.
4. **Semua angka** (harga emas, harta, harga Pro Rp 129.000) hanya contoh.
5. **Kemudahan mencatat (S24–S28)**: jumlah favorit, isi teks Koreksi saldo yang menghindari kesan menghakimi, dan apakah notifikasi malam perlu kolom balasan langsung.

## Keterbatasan

Data tidak disimpan (reset saat halaman dimuat ulang), tanggal tetap September 2026, dan tidak ada pembayaran, notifikasi, atau izin sistem sungguhan (semuanya disimulasikan). Prototipe HTML tidak menguji nuansa native (gestur, haptik, ukuran sentuh); itu dinilai saat implementasi Compose di Tahap 3.

## Web (server/): design system dan komponen

Diterapkan 2026-10-24 (fase 1 desain web baru, lihat [konsep.md](../konsep.md) butir "Desain web baru"). Tampilannya mengikuti mockup di [web-mockup/](web-mockup/README.md); bagian ini menjelaskan cara memakainya di `server/` (Laravel 12 + Livewire 4 + Blade).

### Berkas

| Berkas | Fungsi |
|---|---|
| `server/public/css/rizqflow.css` | Design system web: token, reset, dan semua kelas komponen (`rf-*`, `au-*`, grafik `ch-*`/`sk-*`). Dimuat dengan `?v={filemtime}` supaya cache peramban ikut berganti. |
| `server/public/js/rizqflow.js` | Tema, pintasan keyboard, kotak perintah bilah atas, dan penutup menu. Tanpa build dan tanpa dependensi (kotak perintah memakai Alpine yang sudah dibundel Livewire). |
| `server/resources/views/components/layout.blade.php` | Kerangka aplikasi: sidebar bergrup, bilah atas, isi. |
| `server/resources/views/components/auth-layout.blade.php` | Kerangka tamu (halaman masuk). |
| `server/resources/views/components/rf/*.blade.php` | Komponen `<x-rf.*>`. |
| `server/resources/views/partials/theme-init.blade.php`, `theme-toggle.blade.php` | Skrip tema di `<head>` dan tombol ganti tema. |

### Aturan sinkron token

`tokens.css` adalah sumber tunggal. `rizqflow.css` memuat salinan token (terang di `:root`; gelap di `@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) }` dan `:root[data-theme="dark"]`). Salinan ini **manual**: ubah `tokens.css` lebih dulu, lalu samakan tiga blok token di `rizqflow.css` (terang, gelap mengikuti sistem, gelap pilihan pengguna). Token baru dari mockup: `--rf-pill-good|warn|info-bg|fg` dan `--rf-income`; `rizqflow.css` juga menyediakan alias `--pill-*` supaya markup mockup `.dc.html` bisa dipakai apa adanya. Teks peringatan memakai `#735a0b` (kontras ±5,9:1 di atas `#fef3d6`), menggantikan `#8a6d13` di layout web lama (±4,4:1, di bawah batas 4,5:1). Android tidak memakai `#8a6d13`, jadi tidak ada yang perlu diubah di sana.

### Tema

Pilihan pengguna disimpan di `localStorage` kunci `rizqflow-theme` (`light` atau `dark`) dan dipasang ke `<html data-theme>` oleh `partials/theme-init` sebelum CSS dimuat (tanpa kedip). Tanpa pilihan, tema mengikuti preferensi sistem. Tombol bertanda `data-rf-theme-toggle` mengganti tema. `public/js/rizqflow.js` menyediakan `rizqflowCurrentTheme()` (tema efektif `'light'|'dark'`, memperhitungkan preferensi sistem), `rizqflowSetTheme(theme)`, dan `rizqflowToggleTheme()`; setiap perubahan mengirim `CustomEvent('rizqflow:theme', { detail: { theme } })` di `document`, dipakai grafik dan tombol Google yang perlu digambar ulang.

### Layout dan periode

```php
->layout('components.layout', ['title' => 'Transaksi — Rizqflow'])                  // tanpa pemilih periode
->layout('components.layout', ['title' => 'Denah — Rizqflow', 'period' => $bulan])  // $bulan: CarbonImmutable awal bulan
```

Halaman yang punya pemilih periode menyimpan `#[Url] public ?string $bulan = null;` lalu memakai `$bulan = Period::fromQuery($this->bulan)`. Tombol ‹ › di bilah atas adalah tautan ke URL yang sama dengan `?bulan=Y-m`; › nonaktif pada bulan berjalan. Halaman lama yang tidak mengirim `period` tetap berfungsi tanpa pemilih. CSS khusus halaman ditaruh lewat `@push('styles')` (dirender di `<head>`), skrip lewat `@push('scripts')` (akhir `<body>`).

### Pintasan keyboard

| Tombol | Aksi |
|---|---|
| `Ctrl/Cmd + K` | Fokus ke kotak perintah (`#rf-cmd-input`), juga dari dalam isian biasa |
| `C` | Fokus ke `#catat-catatan` bila ada di halaman; selain itu buka `data-catat-url` (Transaksi `#catat`) |
| `R` | Buka `data-rezeki-url` (Catat rezeki masuk) |
| `Esc` | Tutup menu Catat dan dropdown kotak perintah |

`C` dan `R` diabaikan saat fokus di input, textarea, select, atau elemen contenteditable, dan saat ada Ctrl/Cmd/Alt/Shift. URL tujuan dibaca dari atribut `data-catat-url` dan `data-rezeki-url` di `<body>`. Kotak perintah menawarkan "Catat pengeluaran" (`transaksi?catat=...`) dan "Cari transaksi" (`transaksi?cari=...`); Catat lebih dulu bila teks mengandung angka.

### Komponen `<x-rf.*>`

| Komponen | Props | Contoh |
|---|---|---|
| `icon` | `name` + atribut diteruskan ke `<svg>`; `aria-label` membuatnya `role="img"`, tanpa itu `aria-hidden`. Nama tak dikenal tidak merender apa pun | `<x-rf.icon name="check" />` |
| `pill` | `tone` good/warn/info/neutral (default neutral), `icon` (bawaan: good=check, warn=warn, info=info; `""` = tanpa ikon) | `<x-rf.pill tone="warn">Perlu perhatian</x-rf.pill>` |
| `banner` | `tone` good/warn/info/neutral (default info), `icon` seperti pil | `<x-rf.banner tone="good" role="status">Disimpan.</x-rf.banner>` |
| `room-icon` | `room` (model Room) atau `slot` + `icon`; slot di luar 1..8 → netral | `<x-rf.room-icon :room="$room" />` |
| `room-name` | `room` (model atau teks), `slot`, `pos` (teks atau model) | `<x-rf.room-name :slot="3" room="Keluarga" pos="Dapur dan belanja" />` |
| `ring` | `percent` (boleh > 100), `warn`, `size` (64), `slot`, `text`, `label` | `<x-rf.ring :percent="87" warn :slot="3" label="Keluarga terpakai 87 persen" />` |
| `stack` | `segments` (`slot`, `bp`, `label`), `label`, `legend` | `<x-rf.stack :segments="$segmen" label="Pembagian rezeki" legend />` |
| `track` | `percent`, `slot`, `label` | `<x-rf.track :percent="30" :slot="2" />` |
| `pace` | `used`, `time`, `slot`, `label` | `<x-rf.pace :used="87" :time="77.4" :slot="3" />` |
| `tabs` | `items` (`label`, `href`, `active`, `count`, `muted`), `label` | `<x-rf.tabs :items="[['label' => 'Aliran', 'href' => route('laporan'), 'active' => true]]" />` |
| `page-head` | `title`, `sub`, `lead` (teks atau slot bernama), slot `actions` | `<x-rf.page-head sub="Oktober 2026" title="Transaksi" />` |

Aturan warna ruang (di atas) berlaku: `stack` mengurutkan segmen menurut slot dan menggabungkan slot netral jadi satu "Ruang lain". Slot warna dibaca dari atribut `slot` (di Blade `$slot` adalah isi komponen). Daftar nama ikon dan rincian props ada di komentar di atas tiap berkas komponen.

### Helper

- `App\Support\Rupiah`: `format` dan `short` (tetap), `plain` (`18.000.000`), `signed` (`−Rp 187.500`, `+Rp 18.000.000`), `compact` (`Rp 1,09 jt`, `Rp 187,5 rb`, `Rp 1,2 M`). Murni aritmetika bulat, pembulatan setengah ke atas.
- `App\Support\Period`: `fromQuery`, `label` (`Oktober 2026`), `query` (`2026-10`), `isCurrent`, `isPast`, `previous`, `next`, `bounds`.
