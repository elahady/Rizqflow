# Rizqflow — Wireframe seluruh layar

Wireframe teks untuk **semua** layar S01–S28 (kecuali yang sudah ada di [ui-flow.md](ui-flow.md), ditautkan di indeks). Spesifikasi perilaku dan flow ada di ui-flow.md; berkas ini hanya tata letak, keadaan, dan usulan yang perlu dikonfirmasi.

- Semua angka hanya **contoh**, bukan saran keuangan.
- Simbol: `[ ]` tombol, `( )` pilihan, `(o)` terpilih, `v` centang, `!` peringatan lembut, `>` masuk ke layar lain, `===` panel bottom sheet.
- Butir bertanda **Usulan** belum diputuskan pemilik; jangan diperlakukan sebagai keputusan.
- Layar yang sudah ada prototipe kliknya ditandai di kolom Prototipe (lihat [design/README.md](design/README.md)).

## Indeks

| Kode | Layar | Wireframe | Prototipe |
|---|---|---|---|
| S01–S04 | Onboarding | Di sini | Ada (F1) |
| S05 | Denah | [ui-flow.md](ui-flow.md); keadaan kosong di sini | Ada |
| S06 | Catat transaksi | [ui-flow.md](ui-flow.md); tab Transfer di sini | Ada (Transfer belum) |
| S07 | Pratinjau alokasi | [ui-flow.md](ui-flow.md) | Ada |
| S08 | Daftar transaksi | Di sini | Ringkas |
| S09 | Detail dan edit transaksi | Di sini | Belum |
| S10 | Daftar ruang | Di sini | Ringkas |
| S11 | Detail ruang | [ui-flow.md](ui-flow.md) | Ada |
| S12 | Aturan alokasi | Di sini | Ada (F4) |
| S13 | Akun, kategori, favorit | Di sini | Belum |
| S14–S15 | Beranda Zakat, Profil harta | Di sini | Belum |
| S16 | Kartu haul | [ui-flow.md](ui-flow.md) | Ada |
| S17 | Tunaikan zakat | Di sini | Belum |
| S18 | Lainnya | Di sini | Ringkas |
| S19 | Keamanan | Di sini | Belum |
| S20 | Backup, restore, ekspor | Di sini | Belum |
| S21 | Rizqflow Pro | [ui-flow.md](ui-flow.md) | Ada |
| S22 | Mode demo dan tampilan | Di sini | Sebagian (badge dan keluar demo) |
| S23 | Tentang dan disclaimer | Di sini | Belum |
| S24 | Catat kilat | [ui-flow.md](ui-flow.md) | Ada |
| S25 | Koreksi saldo | [ui-flow.md](ui-flow.md) | Ada |
| S26 | Pengingat harian | Di sini | Ada |
| S27–S28 | Draf, Tangkap otomatis (v1.1) | Di sini | Ada |
| S29–S31 | Splash, Masuk (menggantikan S01), Daftar | Di sini | Ada (`?screen=splash`, `?screen=masuk`, `?screen=daftar`) |

## S01 Sambutan

```
+--------------------------------+
|                                |
|                                |
|         (h)  (s)  (r)          |
|                                |
|            Rizqflow            |
|                                |
|  Rezeki mengalir, setiap hak   |
|  terpenuhi.                    |
|                                |
|  v Data tersimpan di ponselmu, |
|    tanpa akun                  |
|  v Jalan tanpa internet        |
|  v Tanpa iklan                 |
|                                |
| [            Mulai           ] |
|     Pulihkan dari cadangan     |
+--------------------------------+
```

- Tanpa bottom navigation. Satu tindakan utama (Mulai); Pulihkan dari cadangan adalah tautan teks menuju flow F7 untuk yang pindah ponsel.
- Tiga ikon di atas memakai ikon dan warna tiga ruang bawaan.

## S02 Pilih pola ruang

```
+--------------------------------+
| <  Pilih pola ruang            |
| Langkah 1 dari 3               |
| Ruang adalah tempat rezeki     |
| dialirkan. Bisa diubah nanti.  |
|                                |
| +----------------------------+ |
| | (o) Tiga hak               | |
| | Rezeki dibagi ke tiga ruang| |
| | (h) Memberi                | |
| |     sedekah, infak, zakat  | |
| | (s) Diri                   | |
| |     dana darurat, investasi| |
| | (r) Keluarga               | |
| |     belanja, listrik       | |
| +----------------------------+ |
| +----------------------------+ |
| | ( ) Mulai kosong           | |
| | Susun ruangmu sendiri nanti| |
| +----------------------------+ |
|                                |
| [            Lanjut          ] |
+--------------------------------+
```

- Pilihan bawaan: Tiga hak. **Mulai kosong** sekaligus menjadi cara melewati langkah ini; S03 dilewati dan penghitung menjadi "Langkah 1 dari 2".
- Mulai kosong berarti Denah tanpa ruang dan rezeki yang masuk menunggu sebagai "belum dialirkan" sampai ada ruang (lihat keadaan kosong S05 di bawah).

## S03 Atur persentase alokasi

```
+--------------------------------+
| <  Atur pembagian              |
| Langkah 2 dari 3               |
| Setiap rezeki dialirkan ke     |
| ruang-ruang ini. Bisa diubah   |
| di Ruang, Aturan alokasi.      |
|                                |
| Contoh untuk Rp 1.000.000      |
| [##|######|#############]      |
|  Memberi 10%  Diri 30%         |
|  Keluarga 60%                  |
|                                |
| (h) Memberi              10%   |
|     Rp 100.000                 |
| (-) ==o=================== (+) |
| (s) Diri                 30%   |
|     Rp 300.000                 |
| (-) ======o=============== (+) |
| (r) Keluarga             60%   |
|     Otomatis: menerima sisa    |
| ------------------------------ |
| Total                    100 v |
|                                |
| [            Lanjut          ] |
+--------------------------------+
```

- Total selalu 100%: ruang terakhir tidak punya slider dan menerima sisanya. Slider ruang lain dibatasi supaya jumlahnya tidak melebihi 100%.
- Setiap slider punya tombol kurangi dan tambah 1% (untuk sentuhan yang tidak presisi dan untuk aksesibilitas).
- Ringkasan Rp 1.000.000 memakai aturan pembulatan yang sama dengan S07.

## S04 Tambah akun pertama

```
+--------------------------------+
| <  Akun pertama                |
| Langkah 3 dari 3               |
| Tempat uangmu berada. Akun lain|
| bisa ditambah nanti.           |
|                                |
| Jenis akun                     |
| [Tunai] [Bank] [Dompet digital]|
| Nama akun                      |
| [ GoPay                      ] |
|                                |
| Saldo awal                     |
|          Rp 50.000             |
| Saldo yang ada sekarang, bukan |
| rezeki.                        |
|                                |
| [ 1 ] [ 2 ] [ 3 ]              |
| [ 4 ] [ 5 ] [ 6 ]              |
| [ 7 ] [ 8 ] [ 9 ]              |
| [000] [ 0 ] [ < ]              |
|                                |
| [       Mulai memakai        ] |
+--------------------------------+
```

- Nama akun wajib; saldo awal boleh nol. Nama bawaan hanya untuk Tunai; jenis lain memberi contoh nama sebagai petunjuk isian.
- **Saldo awal bukan rezeki:** tidak masuk "Rezeki bulan ini" dan tidak dialirkan ke ruang. Ia hanya titik awal untuk Koreksi saldo (S25).
- Sesudah tombol utama, pengguna masuk ke Denah kosong (S05 keadaan kosong).

## S05 Denah, keadaan kosong

```
+--------------------------------+
| Denah                          |
| September 2026                 |
|                                |
| Rezeki bulan ini               |
| Rp 0                           |
|                                |
| +----------------------------+ |
| | Belum ada rezeki bulan ini | |
| | Catat rezeki pertamamu,    | |
| | lalu lihat ia mengalir.    | |
| | [ Catat rezeki pertama  ]  | |
| |    Coba data contoh        | |
| +----------------------------+ |
|                                |
| Ruang                          |
| +-------------+ +-------------+|
| | Memberi     | | Diri        ||
| | (o) 0%      | | (o) 0%      ||
| | Menunggu    | | Menunggu    ||
| | Belum ada   | | Belum ada   ||
| | jatah       | | jatah       ||
| +-------------+ +-------------+|
| Perlu perhatian                |
| Tidak ada yang perlu           |
| diperhatikan sekarang.         |
|                     [ + Catat ]|
+--------------------------------+
|Denah  Transaksi  Ruang  Lainnya|
+--------------------------------+
```

- Ruang baru tampil netral ("Belum ada jatah"), bukan Perlu perhatian.
- Tanpa tanggal Hijriyah dan tanpa butir zakat selama modul Memberi belum diisi.
- **Tanpa ruang** (dari Mulai kosong): bagian Ruang berisi kartu "Belum ada ruang" dengan tombol **Pakai pola Tiga hak**; rezeki yang dicatat tersimpan sebagai "belum dialirkan".
- **Coba data contoh** membuka mode demo (S22): data contoh terpisah dari data pengguna, ada penanda di header, dan keluar dari demo mengembalikan data pengguna.

## S06 Catat transaksi, tab Transfer

```
+--------------------------------+
| x   Catat                      |
| [Pemasukan][Pengeluaran][Trf]  |
|                                |
|            Rp                  |
|          500.000               |
|                                |
| Dari      [Bank Jago      v]   |
| Ke        [Tunai          v]   |
| Tanggal   [Hari ini       v]   |
| Catatan   [Tarik tunai      ]  |
|                                |
| [ 1 ] [ 2 ] [ 3 ]              |
| [ 4 ] [ 5 ] [ 6 ]              |
| [ 7 ] [ 8 ] [ 9 ]              |
| [000] [ 0 ] [ < ]              |
|                                |
| [ Simpan ]                     |
+--------------------------------+
```

- Memindahkan uang antar akun; tidak masuk ruang mana pun dan tidak dihitung sebagai pemasukan atau pengeluaran.
- Dari dan Ke tidak boleh akun yang sama. Dengan satu akun saja, tab Transfer nonaktif dengan penjelasan singkat.
- Biaya admin transfer dicatat sebagai pengeluaran biasa di tab Pengeluaran.

## S08 Daftar transaksi

```
+--------------------------------+
| Transaksi          < Sep 2026 >|
| [ Cari catatan atau nominal  ] |
| (Semua) (Masuk) (Keluar) (Akun)|
|                                |
| +----------------------------+ |
| | ! Kemarin belum ada catatan| |
| |  [Catat sekarang] Tidak ada| |
| +----------------------------+ |
| > 3 draf menunggu (Pro, v1.1)  |
|                                |
| 18 Sep                         |
| (r) Belanja pasar     -150.000 |
|     Belanja bulanan  Bank Jago |
| (r) Token listrik     -200.000 |
|     Listrik dan air  Bank Jago |
| 12 Sep                         |
| (s) Dana darurat    -1.020.000 |
|     Dana darurat     Bank Jago |
| 1 Sep                          |
| (^) Gaji September  +8.500.000 |
|     dialirkan ke 3 ruang       |
|                     [ + Catat ]|
+--------------------------------+
|Denah  Transaksi  Ruang  Lainnya|
+--------------------------------+
```

- Dikelompokkan per tanggal; pemasukan diberi ikon panah naik dan tanda plus, pengeluaran memakai ikon ruang.
- Filter cepat: Semua, Masuk, Keluar, dan Akun (memilih akun lewat sheet). **Diputuskan 2026-09-21:** filter Ruang dan Kategori ada di ikon filter di sisi kolom cari.
- Ketuk baris membuka S09. Geser baris tidak dipakai (hapus ada di S09) supaya tidak terhapus tidak sengaja.
- Banner hari kosong dan baris draf hanya muncul bila berlaku (lihat F8 dan F9). Hasil filter Akun dari S25 menampilkan chip "Akun: Tunai" yang bisa dilepas.
- **Keadaan kosong:** "Belum ada transaksi. Rezeki dan pengeluaran yang kamu catat akan muncul di sini." **Hasil cari kosong:** "Tidak ada yang cocok dengan pencarianmu."

## S09 Detail dan edit transaksi

Pengeluaran:

```
+--------------------------------+
| <  Detail transaksi        [ ] |
|                                |
|            Rp                  |
|         150.000                |
|         Pengeluaran            |
|                                |
| Ruang      [Keluarga       v]  |
| Kategori   [Belanja bulanan v] |
| Akun       [Bank Jago      v]  |
| Tanggal    [18 Sep 2026    v]  |
| Catatan    [Belanja pasar    ] |
|                                |
| Jadikan favorit           [ ]  |
|                                |
| [           Simpan           ] |
|   Hapus transaksi              |
+--------------------------------+
```

Pemasukan:

```
+--------------------------------+
| <  Detail transaksi            |
|                                |
|            Rp                  |
|       8.500.000                |
|          Pemasukan             |
|                                |
| Sumber     [Gaji           v]  |
| Akun       [Bank Jago      v]  |
| Tanggal    [1 Sep 2026     v]  |
| Catatan    [Gaji September  ]  |
|                                |
| Dialirkan saat itu             |
| Memberi   10%    Rp   850.000  |
| Diri      30%    Rp 2.550.000  |
| Keluarga  60%    Rp 5.100.000  |
| Aturan yang berlaku saat itu.  |
|                                |
| [           Simpan           ] |
|   Hapus transaksi              |
+--------------------------------+
```

- Pengeluaran bisa diubah penuh. Layar yang sama dipakai saat **Ubah** pada draf (S27): kolom terisi dari draf.
- **Diputuskan 2026-09-21:** rincian alokasi pemasukan tersimpan sebagai potret saat pemasukan dicatat, jadi mengubah aturan alokasi (S12) tidak mengubah riwayat. Mengubah nominal pemasukan menghitung ulang alokasinya dengan persentase potret itu; ruang dan persentasenya tidak bisa diedit dari sini.
- Hapus meminta konfirmasi singkat lalu tampil snackbar Urungkan. Transaksi hasil Koreksi saldo (kategori Tak terlacak) diperlakukan sama dengan transaksi biasa.
- Tanggal yang dipilih berada di bulan lain memindahkan transaksi ke bulan itu, dan jatah ruang bulan itu ikut berubah.

## S10 Daftar ruang

```
+--------------------------------+
| Ruang                          |
|                                |
| (h) Memberi                 = >|
|     Menunaikan  10%  Berjalan  |
| (s) Diri                    = >|
|     Menumbuhkan 30%  Terpenuhi |
| (r) Keluarga                = >|
|     Mencukupi  60%  Perlu      |
|                       perhatian|
|                                |
| Pembagian rezeki               |
| Memberi 10% Diri 30%           |
| Keluarga 60%                 > |
|                                |
| [ + Tambah ruang ]             |
| 3 dari 5 ruang gratis          |
|                                |
| Diarsipkan (1)               > |
+--------------------------------+
|Denah  Transaksi  Ruang  Lainnya|
+--------------------------------+
```

Tambah ruang (bottom sheet):

```
+================================+
|  Ruang baru                    |
|                                |
|  Nama    [ Orang tua         ] |
|  Tipe    (o) Menunaikan        |
|          ( ) Menumbuhkan       |
|          (o) Mencukupi         |
|  Ikon    (h)(s)(r)(k)(t) ...   |
|                                |
|  [        Tambah ruang       ] |
|  Batal                         |
+================================+
```

- `=` di ujung baris adalah pegangan urutan (seret untuk mengubah). **Diputuskan 2026-09-21:** urutan menentukan prioritas: ruang pertama menerima sisa pembulatan (metode sisa terbesar, pemutus seri), dan urutan tampil di Denah.
- Ketuk baris membuka S11. Tipe menentukan arti "terpenuhi" (lihat konsep.md); ada teks bantu satu kalimat per tipe.
- Batas gratis 5 ruang (diputuskan 2026-09-21): ruang ke-6 memicu S21 (Pro: ruang tak terbatas).
- Arsipkan lewat menu di S11: ruang tidak muncul di Denah dan tidak menerima alokasi baru, riwayat tetap. Menampilkan **Diarsipkan** hanya bila ada.
- Persentase ruang baru dimulai 0%; setelah menambah, layar menawarkan menuju S12 untuk mengaturnya. Ruang baru tanpa jatah tampil netral.

## S12 Aturan alokasi

```
+--------------------------------+
| <  Aturan alokasi              |
| Berlaku untuk pemasukan        |
| berikutnya. Riwayat tidak      |
| berubah.                       |
|                                |
| Contoh untuk Rp 1.000.000      |
| [###|######|###########]       |
|  Memberi 15%  Diri 30%         |
|  Keluarga 55%                  |
|                                |
| (h) Memberi              15%   |
|     Rp 150.000                 |
| (-) ===o================== (+) |
| (s) Diri                 30%   |
|     Rp 300.000                 |
| (-) ======o=============== (+) |
| (r) Keluarga             55%   |
|     Rp 550.000                 |
| (-) =========o============ (+) |
| ------------------------------ |
| Total                    100 v |
|                                |
| > Aturan lanjutan (Pro)        |
| [        Simpan aturan       ] |
+--------------------------------+
```

Total tidak 100%:

```
| Total                    105 ! |
| +----------------------------+ |
| | ! Total 105%. Kurangi 5%   | |
| | supaya genap 100%.         | |
| +----------------------------+ |
| [        Simpan aturan       ] |   (nonaktif)
```

- Jalan masuk: Ruang, Pembagian rezeki; tombol Atur aturan di S11 (baris ruang itu disorot); Lainnya, Aturan alokasi.
- Simpan hanya aktif bila total tepat 100% **dan** ada perubahan. Menekan kembali dengan perubahan belum disimpan menampilkan sheet "Buang perubahan?". Sesudah simpan: snackbar Urungkan.
- Berbeda dengan S03 (ruang terakhir otomatis sisa), di sini semua ruang bebas diubah karena pengguna sudah paham polanya.
- Aturan lanjutan (prioritas, batas atas, sisa mengalir) mengunci ke Pro dan membuka S21.
- Tanpa ruang: empty state dengan tombol Pakai pola Tiga hak.

## S13 Kelola akun, kategori, dan favorit

```
+--------------------------------+
| <  Akun dan kategori           |
| [ Akun ][ Kategori ][ Favorit ]|
|                                |
| Akun                           |
| Tunai                 Rp 312rb |
| Dompet                       > |
| Bank Jago           Rp 6,84 jt |
| Bank                         > |
| GoPay                 Rp 185rb |
| Dompet digital               > |
|                                |
| [ + Tambah akun ]              |
+--------------------------------+
```

Tab Kategori:

```
+--------------------------------+
| <  Akun dan kategori           |
| [ Akun ][ Kategori ][ Favorit ]|
|                                |
| Keluarga                       |
| Belanja bulanan              > |
| Listrik dan air              > |
| Sekolah                      > |
| Lain-lain                    > |
| [ + Tambah kategori ]          |
| Memberi                        |
| Sedekah                      > |
| Infak                        > |
| Zakat mal (sistem)             |
|                                |
| Tak terlacak (sistem)          |
+--------------------------------+
```

Tab Favorit:

```
+--------------------------------+
| <  Akun dan kategori           |
| [ Akun ][ Kategori ][ Favorit ]|
|                                |
| Tampil di Catat kilat (maks 6) |
| Kopi           15.000     = >  |
| Keluarga, Lain-lain, Tunai     |
| Parkir          3.000     = >  |
| Keluarga, Lain-lain, Tunai     |
| Jajan          20.000     = >  |
| Keluarga, Lain-lain, Tunai     |
|                                |
| Favorit baru dibuat dari Catat |
| (Jadikan favorit).             |
+--------------------------------+
```

- Akun: nama, jenis (tunai, bank, dompet digital), saldo menurut catatan. Ketuk membuka sheet ubah nama, arsipkan, dan **Cocokkan saldo** (menuju S25). Akun yang punya transaksi tidak bisa dihapus, hanya diarsipkan.
- Kategori (pos) dikelompokkan per ruang. Kategori sistem **Zakat mal** dan **Tak terlacak** tidak bisa dihapus atau diganti nama.
- Favorit: ubah nama dan nominal, hapus. Urutan otomatis menurut pemakaian (yang paling sering dipakai dulu); seret untuk mengurutkan ditunda karena skema belum punya kolom urutan. Tanpa favorit: "Belum ada favorit. Jadikan favorit dari layar Catat."
- Akun dan kategori **tidak dibatasi** (diputuskan pemilik 2026-09-24; sebelumnya batas gratis 3 akun).

## S14 Beranda Zakat

```
+--------------------------------+
| <  Zakat                       |
|                                |
| +----------------------------+ |
| |       (  Haul berjalan  )  | |
| |       Hari ke-117 dari 354 | |
| |       Jatuh tempo          | |
| |       8 Dzulhijjah 1448    | |
| |       [ Lihat rincian ]    | |
| +----------------------------+ |
|                                |
| Harta bersih   Rp 152.000.000  |
| Nisab hari ini Rp 141.100.000  |
| 85 g x Rp 1.660.000 per gram   |
| Harga emas: input manual,      |
| diperbarui 12 Sep 2026         |
|                                |
| [    Perbarui nilai harta    ] |
|                                |
| Riwayat zakat                  |
| Belum ada zakat yang ditunaikan|
|                                |
| Persentase donasi biasa   [ ]  |
| Bantuan hitung, bukan fatwa.   |
+--------------------------------+
```

- Kartu status besar punya tiga keadaan: **Belum mencapai nisab** (dipantau, belum ada haul), **Haul berjalan**, **Haul genap** (tombol Tunaikan zakat menuju S17). Setiap status memakai ikon dan teks.
- Jalan masuk: kartu Zakat di S11 ruang Memberi.
- Harga emas: input manual (Gratis) dengan tanggal terakhir; sumber otomatis untuk Pro (lihat monetisasi.md). Bila harga belum diisi, nisab tidak dihitung dan layar meminta mengisinya.
- Saklar **Persentase donasi biasa** mengganti modul menjadi mode tanpa nisab dan haul; data zakat tidak dihapus.
- **Belum pernah menyiapkan:** kartu "Mulai dengan mengisi harta" yang menuju S15, sekaligus mengaktifkan modul (sebelum itu tidak ada tanggal Hijriyah di Denah).
- Riwayat zakat berisi tanggal Masehi dan Hijriyah, jumlah, dan tautan ke transaksinya.

## S15 Profil harta

```
+--------------------------------+
| <  Profil harta                |
|                                |
| Harga emas per gram            |
| Rp [ 1.660.000 ]  12 Sep 2026  |
|                                |
| Harta                          |
| Emas (gram)      60 g          |
|                  Rp 99.600.000 |
| Uang dan tabungan              |
|                  Rp 38.000.000 |
| Investasi        Rp 25.000.000 |
| Piutang lancar    Rp 4.000.000 |
| [ + Tambah harta ]             |
|                                |
| Pengurang                      |
| Hutang jangka pendek           |
|                 -Rp 14.600.000 |
|                                |
| Harta bersih   Rp 152.000.000  |
|                                |
| Diperbarui 40 hari lalu.       |
| [          Simpan            ] |
+--------------------------------+
```

- Emas diisi dalam gram dan dinilai dengan harga emas di atas. Nilai lain diisi rupiah.
- Setiap perubahan nilai dicatat bertanggal, karena haul bergantung pada kapan harta mencapai nisab. **Usulan:** jenis harta bisa ditambah bebas dari daftar bawaan (emas, uang dan tabungan, investasi, piutang lancar, lainnya).
- Pengingat rutin memperbarui nilai (pengingat haul dan nilai harta) muncul di "Perlu perhatian" S05.
- Definisi harta wajib zakat dan pengurang mengikuti asumsi fikih default; verifikasi kitab oleh pemilik masih menunggu (lihat konsep.md).

## S17 Tunaikan zakat

```
+--------------------------------+
| x   Tunaikan zakat             |
|                                |
| Haul genap                     |
| 8 Dzulhijjah 1448              |
|                                |
| Zakat (2,5% dari harta bersih) |
|            Rp                  |
|         3.800.000              |
| Harta bersih Rp 152.000.000    |
|                                |
| Akun sumber [Bank Jago      v] |
| Tanggal     [Hari ini       v] |
| Kategori    Zakat mal (Memberi)|
|                                |
| Jumlah bisa diubah. Bantuan    |
| hitung, bukan fatwa.           |
|                                |
| [ 1 ] [ 2 ] [ 3 ]              |
| [ 4 ] [ 5 ] [ 6 ]              |
| [ 7 ] [ 8 ] [ 9 ]              |
| [000] [ 0 ] [ < ]              |
| [       Tunaikan zakat       ] |
+--------------------------------+
```

- Jumlah awal 2,5% dari harta bersih dan boleh diubah. Konfirmasi lewat tombol, bukan otomatis.
- Hasilnya transaksi pengeluaran kategori **Zakat mal** di ruang Memberi, lalu haul baru dimulai (F5). Zakat melampaui jatah ruang Memberi hanya menampilkan banner lembut; tidak memblokir.
- Bila dibuka sebelum haul genap (dari tombol yang sengaja diaktifkan), ada teks "Haul belum genap" dan pengguna tetap bisa menunaikan lebih awal.

## S18 Lainnya

```
+--------------------------------+
| Lainnya                        |
|                                |
| (*) Rizqflow Pro             > |
|     Sekali bayar, tanpa        |
|     langganan                  |
|                                |
| Catat dan pantau               |
| Koreksi saldo                > |
| Pengingat harian             > |
| Tangkap otomatis (Pro)       > |
| Widget catat kilat (Pro)     > |
|                                |
| Atur                           |
| Akun, kategori, favorit      > |
| Aturan alokasi               > |
| Zakat                        > |
|                                |
| Data dan keamanan              |
| Keamanan                     > |
| Backup, restore, ekspor      > |
|                                |
| Tampilan dan bahasa          > |
| Tentang dan disclaimer       > |
+--------------------------------+
|Denah  Transaksi  Ruang  Lainnya|
+--------------------------------+
```

- Daftar bergulir dengan judul kelompok. Butir Rizqflow Pro berubah menjadi "Rizqflow Pro (aktif)" setelah beli.
- Keamanan dan Backup selalu gratis dan tidak diberi penanda Pro.
- Butir Zakat mengaktifkan modul bila belum aktif (menuju S14).

## S19 Keamanan

```
+--------------------------------+
| <  Keamanan                    |
|                                |
| Kunci aplikasi            [x]  |
| Minta PIN saat membuka         |
|                                |
| Ubah PIN                     > |
| Buka dengan sidik jari    [x]  |
|                                |
| Kunci otomatis                 |
| (o) Segera                     |
| ( ) Setelah 1 menit            |
| ( ) Setelah 5 menit            |
|                                |
| Sembunyikan di aplikasi terbaru|
|                           [x]  |
|                                |
| Lupa PIN tidak bisa diatur     |
| ulang. Data dipulihkan dari    |
| cadangan.                      |
+--------------------------------+
```

Buat PIN:

```
+--------------------------------+
| x   Buat PIN                   |
|                                |
|   Masukkan 6 angka             |
|        o o o . . .             |
|                                |
| [ 1 ] [ 2 ] [ 3 ]              |
| [ 4 ] [ 5 ] [ 6 ]              |
| [ 7 ] [ 8 ] [ 9 ]              |
| [   ] [ 0 ] [ < ]              |
+--------------------------------+
```

- **Usulan:** PIN 6 angka diketik dua kali untuk konfirmasi; biometrik hanya bisa diaktifkan setelah ada PIN dan selalu punya PIN sebagai cadangan.
- Tidak ada reset PIN lewat server (tidak ada akun). Karena itu layar ini menjelaskan bahwa jalan keluarnya adalah memulihkan dari cadangan (S20). Beberapa kali salah memasukkan PIN menambah jeda tunggu; data tidak dihapus.
- "Sembunyikan di aplikasi terbaru" mencegah layar tampil di pratinjau aplikasi dan tangkapan layar.
- Layar ini selalu gratis.

## S20 Backup, restore, dan ekspor

```
+--------------------------------+
| <  Backup dan data             |
|                                |
| Cadangan terakhir              |
| 18 Sep 2026, di ponsel ini     |
|                                |
| Cadangan terenkripsi           |
| [     Cadangkan sekarang     ] |
| [    Pulihkan dari berkas    ] |
|                                |
| Ekspor dan impor               |
| Ekspor transaksi (CSV)       > |
| Impor dari Transaksi Harian  > |
|                                |
| Data hanya ada di ponsel ini.  |
| Cadangkan secara berkala.      |
+--------------------------------+
```

Cadangkan (bottom sheet):

```
+================================+
|  Cadangkan data                |
|                                |
|  Sandi   [ ************     ]  |
|  Ulangi  [ ************     ]  |
|  Sandi tidak bisa dipulihkan.  |
|  Tanpa sandi, cadangan tidak   |
|  bisa dibuka.                  |
|                                |
|  [    Simpan dan bagikan     ] |
|  Batal                         |
+================================+
```

Pulihkan (konfirmasi):

```
+================================+
|  Timpa data sekarang?          |
|                                |
|  Semua data di ponsel ini akan |
|  diganti isi cadangan.         |
|                                |
|  Sandi   [ ************     ]  |
|                                |
|  [ Timpa dan pulihkan        ] |
|  Batal                         |
+================================+
```

- Flow F7. Berkas cadangan dienkripsi AES-GCM (lihat konsep.md) dan dibagikan lewat pemilih berkas atau bagikan bawaan Android.
- Sandi salah atau berkas rusak: pesan jelas ("Sandi salah atau berkas rusak"), data sekarang tidak disentuh.
- Pulihkan selalu meminta konfirmasi eksplisit bahwa data sekarang tertimpa. Dari S01 (pengguna baru) tidak ada konfirmasi timpa karena belum ada data.
- **Impor dari Transaksi Harian:** memilih berkas CSV hasil ekspor Notion, memetakan kolom ke akun, kategori, dan ruang, lalu menampilkan pratinjau jumlah baris sebelum benar-benar menyimpan. Ekspor CSV berisi transaksi apa adanya dan selalu gratis.

## S22 Mode demo dan tampilan

```
+--------------------------------+
| <  Tampilan dan bahasa         |
|                                |
| Tema                           |
| [ Otomatis ][ Terang ][ Gelap ]|
|                                |
| Bahasa                         |
| (o) Indonesia                  |
| ( ) English                    |
|                                |
| Ukuran teks mengikuti          |
| pengaturan ponsel.             |
|                                |
| Mode demo                      |
| Coba dengan data contoh, terpi-|
| sah dari datamu.          [ ]  |
+--------------------------------+
```

Header Denah saat mode demo aktif, dan sheet keluar:

```
+--------------------------------+
| Denah              [Mode demo] |
| September 2026                 |
+================================+
|  Mode demo                     |
|  Data contoh terpisah dari     |
|  datamu dan hilang saat keluar.|
|  [ Keluar dari mode demo ]     |
|  Tetap di mode demo            |
+================================+
```

- Mode demo juga dibuka dari **Coba data contoh** di Denah kosong (S05). Selama demo: penanda di header, data tidak tercampur dengan data asli, dan keluar mengembalikan data pengguna.
- Fitur yang butuh izin sistem atau pembelian (Tangkap otomatis) tidak aktif di mode demo.
- Ukuran teks tidak punya pengaturan sendiri: mengikuti ukuran font sistem (angka utama dibatasi 1,25x). **Usulan:** tema tambahan berbayar ada di Pro (lihat monetisasi.md).
- Tema Otomatis mengikuti tema sistem.

## S23 Tentang dan disclaimer

```
+--------------------------------+
| <  Tentang                     |
|                                |
| Rizqflow                       |
| Versi 0.1.0                    |
|                                |
| Privasi                        |
| Data tersimpan di ponsel ini.  |
| Tidak ada akun, iklan, atau    |
| pelacak.                       |
|                                |
| Asumsi fikih zakat mal         |
| Nisab 85 gram emas             |
| Tarif 2,5%                     |
| Haul 1 tahun Hijriyah          |
| Sumber: (diisi setelah         |
| verifikasi kitab)              |
|                                |
| Bantuan hitung, bukan fatwa.   |
| Konsultasikan dengan ulama atau|
| lembaga zakat.                 |
|                                |
| Kebijakan privasi            > |
| Lisensi pihak ketiga         > |
| Kirim masukan                > |
+--------------------------------+
```

- Butir Sumber baru bisa diisi setelah pemilik memverifikasi asumsi fikih dengan kitab; sebelum itu tampil "Asumsi awal, belum diverifikasi".
- Teks disclaimer yang sama tampil di S14 dan S16 (ringkas) dan di sini (lengkap).
- Tanggal Hijriyah disertai keterangan metode kalender setelah keputusan kalender diambil (keputusan terbuka di roadmap).

## S26 Pengingat harian

```
+--------------------------------+
| <  Pengingat harian            |
|                                |
| Pengingat malam           [x]  |
| Satu notifikasi per hari.      |
|                                |
| Jam pengingat                  |
| [ 20.00 ] [ 21.00 ] [ 22.00 ]  |
|                                |
| Pratinjau notifikasi           |
| +----------------------------+ |
| | Rizqflow - pukul 21.00     | |
| | Ada pengeluaran yang belum | |
| | dicatat hari ini?          | |
| | Ketik di sini, misalnya    | |
| | "kopi 25000".              | |
| +----------------------------+ |
|                                |
| [ Simulasikan notifikasi ]     |
+--------------------------------+
```

Notifikasi malam (di luar aplikasi):

```
+--------------------------------+
| Rizqflow                       |
| Ada pengeluaran yang belum     |
| dicatat hari ini?              |
| [ kopi 25000              ][>] |
| [Catat kilat] [Tidak ada]      |
+--------------------------------+
```

- Aktif bawaan; izin notifikasi Android 13+ diminta setelah transaksi pertama disimpan. Bila izin ditolak, layar menjelaskan singkat dan tidak meminta ulang berulang.
- Balasan `kopi 25000` diurai menjadi catatan dan nominal; **Tidak ada** menandai hari itu sudah dicek (F8).

## S27 Draf dari notifikasi (v1.1, Pro)

```
+--------------------------------+
| <  Draf dari notifikasi        |
|                                |
| +----------------------------+ |
| | Rp 25.000     GoPay 08.12  | |
| | Kopi Tuku                  | |
| | Akun GoPay > Keluarga,     | |
| | Lain-lain                  | |
| | [Setujui] [Ubah] Abaikan   | |
| +----------------------------+ |
| +----------------------------+ |
| | Rp 5.000      GoPay 13.05  | |
| | Parkir Mall                | |
| | ! Mungkin sudah dicatat    | |
| | Ada catatan Rp 5.000 di    | |
| | GoPay 10 menit lalu.       | |
| | [Setujui] [Ubah] Abaikan   | |
| +----------------------------+ |
|                                |
| [       Setujui semua (1)    ] |
+--------------------------------+
```

- Draf tidak pernah tersimpan tanpa persetujuan. Setujui: satu ketukan dengan snackbar Urungkan. Ubah: membuka S09 berisi draf. Abaikan: draf dihapus.
- **Setujui semua** hanya mencakup draf tanpa tanda "Mungkin sudah dicatat".
- **Keadaan kosong:** "Belum ada draf. Pembayaran digital berikutnya akan muncul di sini."

## S28 Tangkap otomatis (v1.1, Pro)

```
+--------------------------------+
| <  Tangkap otomatis            |
|                                |
| Rizqflow menyiapkan draf dari  |
| notifikasi pembayaran; kamu    |
| yang menyetujui.               |
|                                |
| Yang dibaca                    |
| Hanya notifikasi aplikasi di   |
| daftar bawah                   |
| Yang disimpan                  |
| Nominal, tujuan, waktu,        |
| aplikasi                       |
| Diproses                       |
| Di perangkat, tidak dikirim    |
|                                |
| Akses notifikasi               |
| ( Belum diberi akses )         |
| [ Buka pengaturan akses     ]  |
|                                |
| Aplikasi yang didukung         |
| GoPay  > akun GoPay       [x]  |
| Bank Jago > akun Jago     [x]  |
+--------------------------------+
```

- Penjelasan tampil sebelum izin diminta. Android tidak memakai dialog biasa untuk akses notifikasi: tombol membuka pengaturan sistem, dan aplikasi memeriksa hasilnya saat kembali.
- Pengguna gratis yang membuka layar ini melihat S21 dengan manfaat "Catat pembayaran digital otomatis". Akses dicabut: draf dan transaksi yang ada tetap, tanpa peringatan mendesak.
- Daftar aplikasi diperluas bertahap; notifikasi dari aplikasi lain diabaikan dan tidak dibaca lebih jauh.

## S29 Splash

```
+--------------------------------+
|                                |
|                                |
|                                |
|                                |
|         +------------+         |
|         | [#][##][###]|        |
|         +------------+         |
|                                |
|                                |
|                                |
|                                |
+--------------------------------+
```

- Meniru splash Al-Kaukaba (keputusan pemilik 2026-09-23): splash sistem menampilkan ikon aplikasi di atas warna token (langsung terlihat sejak frame pertama), lalu layar splash Compose (`SplashContent`) menampilkan wordmark besar (lebar maksimal 350dp) di sepertiga atas dan tiga titik berdenyut berwarna tiga bulatan wordmark di bawah. Ditahan minimal 1,5 detik saat aplikasi dibuka (tidak untuk Catat kilat dari pintasan/tile). Wordmark selebar layar tak bisa ditaruh di splash sistem karena ikonnya dipotong topeng lingkaran (Android 12+). Tanpa tombol.
- Wordmark tetap tampil sampai riwayat masuk terbaca (sekejap), supaya halaman masuk tidak berkedip bagi yang sudah punya riwayat.

## S30 Masuk

```
+--------------------------------+
|         (h)  (s)  (r)          |
|            Rizqflow            |
|  Rezeki mengalir, setiap hak   |
|  terpenuhi.                    |
|                                |
|  Nama pengguna                 |
|  [                           ] |
|  Sandi                         |
|  [                 ] Tampilkan |
|  [           Masuk           ] |
|  ! Nama pengguna atau sandi    |
|    salah.                      |
|                                |
|  ------------ atau ----------- |
|              ( G )             |
|  [     Hubungkan Gmail       ] |
|  Opsional. Izin baca Gmail.    |
|  Belum membaca email apa pun.  |
|                                |
|   Belum punya akun?  Daftar    |
|                                |
|  v Datamu tetap di ponselmu,   |
|    tidak dikirim ke server     |
|  v Jalan tanpa internet        |
|    setelah masuk               |
|  v Tanpa iklan                 |
|      Masuk uji (debug saja)    |
+--------------------------------+
```

- Tiga jalan masuk, berurutan dari yang utama: **nama pengguna dan sandi** (akun lokal), **Google** (tombol simbol G saja: varian ikon resmi, latar putih atau #131314 di mode gelap, garis tepi tipis, 64 dp; namanya "Lanjutkan dengan Google" dibacakan pembaca layar), dan **Hubungkan Gmail** (masuk dan izin baca sekaligus; bisa dilewati dan dihubungkan belakangan dari Lainnya).
- Kolom sandi disamarkan dengan tombol teks **Tampilkan/Sembunyikan** (bukan ikon mata, supaya jelas bagi pembaca layar). Enter di kolom sandi = Masuk. Kolom memberi petunjuk isi otomatis (username, password) ke pengelola sandi sistem.
- Kolom kosong: pesan di bawah kolom yang bersangkutan ("Isi nama pengguna", "Isi sandi"), tanpa memanggil pengecekan. Salah sandi dan nama pengguna tak dikenal memberi **pesan yang sama** ("Nama pengguna atau sandi salah.") supaya orang lain tidak bisa menebak siapa saja yang terdaftar di ponsel ini.
- Pesan lembut, bukan merah, dibacakan pembaca layar: belum disiapkan, gagal masuk, izin Gmail ditolak, sandi salah. Menutup dialog akun Google tidak menampilkan pesan.
- Selama proses berjalan semua tombol dan kolom nonaktif dan tombol yang ditekan menampilkan indikator.
- **Daftar** membuka S31. **Masuk uji** hanya ada di build debug.
- Bagian Akun di **Lainnya**: nama, email atau nama pengguna, "Akun lokal, hanya ada di ponsel ini" untuk akun sandi, status Gmail, Hubungkan Gmail bila belum, dan **Keluar** (menghapus riwayat masuk saja; akun lokal dan data keuangan tidak disentuh).

## S31 Daftar

```
+--------------------------------+
| <  Buat akun                   |
|                                |
| Akun ini hanya ada di ponsel   |
| ini. Data keuanganmu tidak     |
| dikirim ke server.             |
|                                |
| Nama panggilan (opsional)      |
| [                            ] |
| Nama pengguna                  |
| [                            ] |
| 3 sampai 32 karakter: huruf,   |
| angka, titik, garis bawah,     |
| strip                          |
| Sandi                          |
| [                  ] Tampilkan |
| Minimal 8 karakter             |
| Ulangi sandi                   |
| [                  ] Tampilkan |
|                                |
| +----------------------------+ |
| | ! Sandi tidak bisa         | |
| |   dipulihkan karena tidak  | |
| |   ada server. Catat di     | |
| |   tempat yang aman.        | |
| +----------------------------+ |
| [           Daftar           ] |
|                                |
|    Sudah punya akun?  Masuk    |
+--------------------------------+
```

- Empat kolom: nama panggilan (opsional, jadi nama sapaan), nama pengguna, sandi, ulangi sandi.
- **Aturan** (sama di prototipe dan aplikasi, `CredentialPolicy`): nama pengguna 3 sampai 32 karakter, hanya a-z, angka, titik, garis bawah, strip, diawali huruf atau angka, tidak membedakan huruf besar-kecil (disimpan huruf kecil); sandi minimal 8 dan maksimal 128 karakter, spasi dipertahankan, tidak boleh sama dengan nama pengguna. Tidak ada aturan wajib simbol dan huruf besar: panjang lebih berguna daripada komposisi.
- Kesalahan tampil di bawah kolomnya **setelah Daftar ditekan pertama kali**, bukan selagi mengetik. Nama pengguna yang sudah dipakai di ponsel ini muncul sebagai pesan lembut di bawah tombol.
- Peringatan **sandi tidak bisa dipulihkan** tampil sebelum tombol Daftar karena tidak ada server untuk mengirim tautan reset. (Lupa sandi sementara berarti akun itu tidak bisa dibuka; masuk dengan Google tetap bisa.)
- Berhasil: langsung masuk dan lanjut ke S02 Pilih pola ruang, seperti masuk pertama kali dengan Google. Tombol kembali dan tautan **Masuk** kembali ke S30.
- Sandi tidak ikut tersimpan saat layar diputar atau proses dimatikan; yang tersimpan hanya hash-nya (lihat [auth-google.md](auth-google.md)).

## Keadaan yang berlaku di semua layar

Lihat tabel "State dan kasus tepi" di [ui-flow.md](ui-flow.md). Ringkasnya, setiap daftar punya empty state satu kalimat dan satu ajakan; teks 200% tidak boleh terpotong; status memakai ikon dan teks; melewati jatah tidak pernah memblokir.
