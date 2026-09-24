# Rizqflow — Monetisasi

Dokumen ini mencatat model bisnis yang sudah disepakati (2026-09-19). Angka harga bersifat **indikatif untuk diuji**, bukan final.

## Keputusan

**Freemium dengan Pro sekali bayar.** Langganan hanya muncul untuk Sync (fase 2), karena hanya di situ ada biaya server yang berulang.

**Pembaruan 2026-09-24:** server dan langganan Sync ikut **rilis pertama**, bukan fase 2 (lihat Tahap 9 di [roadmap.md](roadmap.md)). Pro tetap sekali bayar lewat Google Play Billing; Sync langganan lewat produk langganan Play dengan verifikasi di server. Baris "fase 2" di bawah dibaca sebagai "rilis 1".

## Prinsip pembagian

1. **Versi gratis harus membuktikan janji utama.** Pengguna gratis bisa membagi rezeki ke hak-hak dan melihat denahnya. Kalau tidak, konsepnya tidak pernah terasa.
2. **Yang berbayar adalah skala dan kenyamanan, bukan kewajiban.** Zakat itu kewajiban agama, jadi perhitungan dasarnya (nisab dan haul satu profil) tetap gratis. Menaruhnya di balik paywall merusak brand.
3. **Batasi dimensi, jangan jumlah transaksi.** Membatasi "maksimal 50 transaksi" membuat pengguna kesal. Membatasi jumlah ruang atau kerumitan aturan terasa wajar.

## Pembagian fitur

| Tingkat | Isi | Harga indikatif |
|---|---|---|
| **Gratis** | Tiga ruang inti (Memberi, Diri, Keluarga) plus beberapa ruang peran; transaksi tak terbatas; aturan alokasi persentase dasar; dashboard Denah; nisab dan haul untuk satu profil harta (harga emas input manual); mode donasi persentase; PIN/biometrik; ekspor CSV; backup lokal terenkripsi | Rp 0 |
| **Pro** (sekali bayar) | Ruang peran tak terbatas dengan sistem khusus per peran (Trader: batas risiko per trade, Investor: jadwal DCA); aturan alokasi lanjutan (prioritas, batas atas, sisa mengalir ke ruang lain); multi-profil haul dengan pengingat; harga emas otomatis; laporan dan insight bulanan/tahunan (PDF); multi-mata uang; widget dan tema; tangkap otomatis dari notifikasi (v1.1) | Rp 99–149 ribu |
| **Sync** (langganan, sejak rilis 1) | Backup dan sinkron antar-perangkat terenkripsi; ruang keluarga bersama (suami-istri) | **Rp 19.000 per bulan atau Rp 149.000 per tahun**, satu langganan untuk keluarga sampai 5 anggota, trial 7 hari (disetujui pemilik 2026-09-24 sebagai harga awal) |

Yang **sengaja tidak** dipaywall:

- **Keamanan** (PIN/biometrik): data keuangan yang tidak terlindungi bukan fitur premium.
- **Ekspor data dan backup lokal**: data itu milik pengguna, dan ini membangun kepercayaan.
- **Dasar zakat** (nisab, haul satu profil, tunaikan zakat).

## Kenapa sekali bayar dulu, bukan langganan

- Aplikasi offline-first dan tanpa server, jadi tidak ada biaya berulang yang membenarkan langganan. Pengguna akan bertanya kenapa membayar tiap bulan untuk sesuatu yang berjalan di ponselnya sendiri.
- Pengguna Indonesia sensitif terhadap tagihan berulang; pembelian sekali bayar lebih mudah dijual.
- Langganan baru masuk akal untuk Sync: ada biaya server dan nilai berkelanjutan. **Ruang keluarga bersama** adalah kandidat terkuat karena paling terasa bagi peran Keluarga.

## Yang dihindari

- **Iklan.** Aplikasi yang menyimpan data keuangan dan zakat, ditambah iklan, merusak kepercayaan; penghasilannya juga kecil.
- **Paywall di fitur inti zakat atau keamanan.**

## Dampak arsitektur

Rancang **lapisan entitlement** dari awal: satu tempat yang menjawab "fitur ini boleh dipakai user ini?". Batas ruang, aturan lanjutan, dan multi-profil haul semuanya bertanya ke sana, sehingga model harga bisa berubah tanpa membongkar kode. Ini juga selaras dengan arsitektur modul yang bisa ditukar, dan menjadi bukti desain di portofolio.

**Status implementasi (2026-09-23):** lapisan ini sudah berjalan. `Entitlements`/`PlanEntitlements`/`Feature`/`Plan` (`:domain`, sejak Tahap 2) menjawab "boleh atau tidak"; `PurchaseStore` (`:app`, SharedPreferences app-wide, sejak paket Google Play terikat ke akun Play Store perangkat, bukan ke satu akun ledger lokal) menyimpan paket yang dimiliki; `LivePlanEntitlements` membacanya ulang tiap dipanggil supaya perubahan langsung berlaku. Paywall S21 dan titik penguncian ruang/akun sudah membuka bottom sheet ini. Yang **belum**: `PurchaseStore.grant()` belum dipanggil dari mana pun — tombol Beli dan Pulihkan pembelian di S21 sengaja hanya menampilkan pesan "belum tersedia" sampai Google Play Billing sungguhan terpasang (perlu listing Play Console, Tahap 8, lebih dulu).

Konsekuensi yang diterima: karena aplikasi offline, penguncian di sisi klien bisa dilewati oleh pengguna yang mahir. Untuk harga sekali bayar dengan taruhan kecil, ini bisa diterima; verifikasi cukup lewat status pembelian dari toko.

## Catatan realistis

Aplikasi niche seperti ini kemungkinan menghasilkan uang kecil. Anggap monetisasi sebagai **eksperimen produk yang nyata** sekaligus bahan cerita portofolio (merancang dan menguji model bisnis), bukan sumber penghasilan yang bisa diandalkan.

## Yang masih perlu diputuskan

- [x] **Pengingat haul: satu profil gratis, multi-profil Pro** (disetujui pemilik 2026-09-24, sebelumnya default kerja sejak 2026-09-23). Konsisten dengan prinsip 2 (dasar zakat selalu gratis): tanpa Pro hanya profil pertama yang disapa; profil lain tetap tersimpan dan bisa dipakai, hanya pengingatnya diam. Konsekuensi: setelah turun paket, pengingat profil tambahan mati tanpa sepengetahuan pengguna, jadi kartu profil perlu baris info lembut "Pengingat aktif hanya untuk profil pertama" (tugas di Tahap 7).
- [x] **Batas ruang gratis: 5 ruang** total (3 inti + 2 peran), disetujui pemilik 2026-09-21.
- [x] **Akun (tunai, bank, e-wallet) tidak dibatasi** (diputuskan pemilik 2026-09-24, menggantikan batas 3 akun dari 2026-09-21). Alasan: riset pasar menyebut multi-dompet yang dikunci premium adalah keluhan khas pengguna Indonesia, dan pengguna rata-rata memegang 2–4 e-wallet ([research.md](research-market/research.md)). Yang dijual Pro tetap ruang, aturan, dan profil, bukan dompet. Kategori juga tidak dibatasi. Kalau nanti ada batas jumlah anggota keluarga, itu bagian Sync (ruang keluarga bersama), bukan akun keuangan.
- [x] **Widget catat kilat**: masuk Pro (2026-09-20). Pintasan ikon, tile Quick Settings, dan balasan notifikasi tetap gratis karena mencatat dengan cepat adalah janji utama (lihat "Disiplin mencatat" di [konsep.md](konsep.md)).
- [x] **Harga awal Sync** (2026-09-24, disetujui pemilik): Rp 19.000 per bulan atau Rp 149.000 per tahun (diskon sekitar 35% untuk tahunan), **satu langganan per keluarga sampai 5 anggota, bukan per orang** karena nilai jualnya ruang keluarga; trial 7 hari lewat Play Billing. Cadangan lokal terenkripsi dan ekspor data tetap gratis; yang berbayar hanya sync antar-perangkat dan ruang keluarga. **Langganan Sync tidak otomatis memberi fitur Pro**, supaya Pro sekali bayar tetap masuk akal. Harga disimpan di Play Console, bukan di kode. Dasar hitung: Play memotong sekitar 15% untuk langganan (cek ulang di Play Console), jadi Rp 149.000 per tahun menyisakan sekitar Rp 127.000; biaya per pengguna hampir nol karena yang disimpan hanya blob kecil.
- [ ] **Harga final Pro** (placeholder Rp 129.000) dan konfirmasi harga Sync, sebaiknya berdasarkan uji minat (landing page atau daftar tunggu). Harga Pro dan Sync tahunan berdekatan, jadi perlu diuji bersama.
- [x] **Platform**: Android saja (2026-09-19), jadi pembelian lewat Google Play Billing: produk in-app sekali beli untuk Pro, langganan untuk Sync di fase 2.
- [ ] **Kewajiban di luar kode**: akun developer, profil pembayaran, dan pajak atas pendapatan aplikasi (lihat [roadmap.md](roadmap.md), Tahap 8).

## Titik penguncian (rancangan)

| Aksi pengguna | Gratis | Pro |
|---|---|---|
| Menambah ruang peran | Sampai batas kecil | Tak terbatas, dengan sistem per peran |
| Aturan alokasi | Persentase dasar | Prioritas, batas atas, sisa mengalir ke ruang lain |
| Profil harta zakat | Satu profil | Multi-profil (emas, tabungan, usaha, anggota keluarga) |
| Harga emas | Input manual | Otomatis |
| Laporan | Ringkasan bulanan di aplikasi | Laporan dan insight bulanan/tahunan, PDF |
| Menangkap pembayaran digital | Catat kilat dan Koreksi saldo | Tangkap otomatis dari notifikasi (v1.1) |
| Lainnya | – | Multi-mata uang, widget, tema |

**Aturan alokasi lanjutan, rancangan lengkap (disetujui pemilik 2026-09-23, sudah diimplementasikan — lihat roadmap.md Tahap 7):**
- **Prioritas = waterfall**, bukan sekadar pemutus seri seperti mode persentase: ruang diisi satu-satu berurutan sampai batas atasnya, menggantikan cara kerja persentase untuk seluruh ruleset saat mode ini aktif.
- **Batas atas** dalam **nominal rupiah tetap per bulan** (bukan persentase kedua yang membingungkan).
- **Sisa (overflow)** mengalir **otomatis berantai** ke ruang berikutnya sesuai urutan prioritas; ruang tanpa batas atas (kosong) berarti tak terbatas dan menampung seluruh sisa — cocok sebagai ruang penutup di prioritas terakhir.
- **Cakupan: satu toggle untuk seluruh ruleset**, bukan campur persentase dan lanjutan dalam satu ruleset yang sama (lebih sederhana dipahami dan diimplementasikan).

**Sistem per peran, rancangan (disetujui pemilik 2026-09-23, sudah diimplementasikan; layar S32):**
- **Modul opsional per ruang** (seperti modul Memberi), bukan tipe ruang baru; satu ruang satu peran. Memasang dan mengubah butuh Pro; melepas selalu boleh, dan turun paket hanya membuat peringatan dan pengingat diam.
- **Trader:** batas risiko per trade = modal (diisi pengguna) x persen risiko. Pengeluaran di ruang itu yang melewatinya memunculkan peringatan lembut saat mencatat; tidak pernah memblokir.
- **Investor:** jadwal DCA bulanan (nominal, tanggal 1 sampai 28, akun, kategori). Dicatat sebagai pengeluaran di ruang itu (sesuai keputusan investasi = pengeluaran, [konsep.md](konsep.md)), bukan transfer; pengingat sekali per bulan.
- Sengaja tanpa harga aset, portofolio, atau jurnal trade lengkap ("Sengaja tidak masuk" di konsep.md).

Alur saat pengguna menyentuh fitur terkunci ada di [ui-flow.md](ui-flow.md) (flow F6 dan layar S21).
