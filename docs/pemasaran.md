# Rizqflow — Pemasaran

Dokumen ini mencatat strategi pemasaran Rizqflow yang dijalankan **paralel dengan pengembangan teknis** (diskusi pemilik 2026-10-24). Isinya alasan dan aturan main; checklist dan statusnya ada di jalur **Tahap P0–P4** di [roadmap.md](roadmap.md), bukan di sini.

Sebagian besar isi di bawah masih **usulan**. Yang sudah diputuskan ada di bagian "Keputusan yang sudah diambil"; sisanya menunggu pemilik (lihat "Keputusan yang masih terbuka").

## Titik awal (2026-10-24)

- Landing page dan form daftar tunggu sudah live di [roziqrizal.com/karya/rizqflow](https://roziqrizal.com/karya/rizqflow) (repo `roziqrizalcom`, lihat Tahap 0 di roadmap.md), tapi belum ada trafik dan pendaftar. Ambang "minat tervalidasi" belum ditentukan.
- Hero landing page masih ilustrasi konsep karena waktu dibuat aplikasinya dianggap belum ada. Sekarang aplikasi sudah sebagian besar jadi, jadi tangkapan layar sungguhan lebih meyakinkan.
- Target rilis 4–8 minggu paruh waktu (Opsi A, lihat "Jalur ke rilis" di roadmap.md), jadi rilis kira-kira akhir November sampai Desember 2026.
- Rilis pertama hanya menjual **Pro sekali bayar**; Sync baru di 1.1 ([monetisasi.md](monetisasi.md)). Harga Pro belum final (usulan Rp 99.000 dulu, naik begitu OCR/suara/laporan mendarat).
- Pemilik bekerja sendiri, paruh waktu di sela kantor. Pemasaran harus murah waktu dan sebisa mungkin memakai ulang hasil kerja teknis sebagai bahan konten.

## Positioning (usulan, belum diputuskan)

Produk tetap universal (inti tidak Islami, modul Memberi opsional, lihat [konsep.md](konsep.md)), tetapi **pemasaran awal membidik ceruk tajam: keluarga muslim muda, dengan zakat mal dan haul Hijriyah sebagai pintu masuk.**

Alasan:
- Riset pasar menyimpulkan "aplikasi keuangan untuk semua" sulit menang; Finku menggratiskan premium sampai Maret 2027, jadi bersaing di fitur umum kalah harga ([research-market/research.md](research-market/research.md), "Catatan eksekusi": mulai dari niche).
- Tracker zakat dengan haul Hijriyah adalah pembeda yang sulit ditiru (konsep.md, "Pembeda utama"), sedangkan literasi keuangan syariah baru 43% (SNLIK 2025): ada ruang edukasi yang sekaligus jadi konten.
- Pengelola kas rumah tangga umumnya istri, dan "family finance berbasis peran" adalah peluang terbesar yang disebut riset — cocok dengan ruang Keluarga.

Pesan inti yang diulang di semua saluran: *"Aplikasi lain bertanya uangmu habis ke mana. Rizqflow bertanya: apakah setiap hak sudah ditunaikan?"*

## Saluran

| Saluran | Peran | Catatan |
|---|---|---|
| **Al-Kaukaba** (aplikasi pemilik, sekitar 62 perangkat aktif bulanan) | Saluran pertama, audiens paling tepat | Muslim yang peduli hisab/kalender Hijriyah = pasar fitur haul. Satu pengumuman dalam aplikasi atau notifikasi yang mengarah ke daftar tunggu |
| **Jaringan pribadi** | Pendaftar awal dan penguji beta | Status WA, grup kantor, keluarga, kajian |
| **Instagram `@rizqflowra`** (akun khusus Rizqflow) | Saluran konten utama | Diusulkan pemilik 2026-10-24; `@rizqflow` sudah dipakai orang lain. Lihat aturan boost di bawah |
| Threads, TikTok, X | Cadangan nama, posting silang | Amankan nama yang sama dengan Instagram sekaligus walau belum aktif dipakai |
| **Play Store** | Penemuan setelah rilis | ASO: "catatan keuangan keluarga", "zakat mal", "budget rumah tangga", "pengelola keuangan"; masuk ke Store listing Tahap 8 |
| Email daftar tunggu | Pengumuman rilis dan harga perdana | Pendaftar sudah opt-in eksplisit lewat form, jadi berbeda dari email akun aplikasi (consent email promo akun masih terbuka, lihat konsep.md). Pengiriman bisa memakai Brevo yang sudah dipilih untuk web |
| Ustadz, perencana keuangan syariah, komunitas masjid/kajian | Kampanye musim zakat | Setelah rilis, menjelang Ramadan |

Semua tautan ke landing page memakai UTM per saluran (mis. `?utm_source=instagram`, `?utm_source=alkaukaba`) supaya pendaftar bisa dihitung asalnya. Analitik landing page cukup yang ringan dan menghormati privasi (mis. Umami dipasang sendiri di VPS), sejalan dengan janji privasi Rizqflow; pelacak iklan berat dihindari.

## Konten

Ritme usulan: 2–3 posting per minggu, diproduksi dari 3–4 template visual yang mengikuti arah visual opsi A (identitas roziqrizal.com, `docs/design/tokens.css`).

Empat tema, dengan contoh:

1. **Edukasi zakat mal** (paling sering disimpan, dan simpanan adalah sinyal yang membuat konten disebar): "Hartamu sudah haul belum?", "Beda nisab emas dan perak", "Kenapa haul pakai tahun Hijriyah".
2. **Konsep hak**: "Gajimu sudah dibagi untuk hak siapa saja?" dengan visual Memberi / Diri / Keluarga.
3. **Demo aplikasi**: Reels rekaman layar 15 detik (catat kilat, Denah, hitung haul).
4. **Cerita membangun** (build in public): kenapa tanpa iklan, kenapa ekspor dan keamanan selalu gratis, keputusan desain.

Batasan klaim: janji "datamu dienkripsi, bahkan server tidak bisa membacanya" **baru boleh dipakai di konten setelah terbukti** di perangkat dengan Android Keystore asli (Tahap 13, lihat "Verifikasi manual yang masih berutang" di roadmap.md). Asumsi fikih zakat (85 g emas, 2,5%, haul 1 tahun Hijriyah) juga masih menunggu verifikasi kitab oleh pemilik; konten edukasi zakat sebaiknya menyebut asumsi yang dipakai, atau ditinjau ustadz sebelum dipublikasikan.

## Instagram dan boost

**Akun khusus `@rizqflowra` dibuat** (usulan pemilik 2026-10-24; `@rizqflow` sudah dipakai). Sebelum dipromosikan ke mana pun, akun diisi dulu 9–12 posting: akun kosong dengan nol pengikut terlihat kurang meyakinkan, apalagi untuk aplikasi yang menyimpan data keuangan. Bio menaut ke landing page dengan `utm_source=instagram`.

Isian profil (draf 2026-10-24, mengikuti usulan positioning zakat; sesuaikan bila positioning diputuskan lain):

- **Jenis akun:** Profesional → Bisnis, kategori aplikasi/perangkat lunak (dibutuhkan untuk Insights dan boost).
- **Foto profil:** [design/logo/rizqflow-profile-square.png](design/logo/rizqflow-profile-square.png) (1080×1080, dirender dari [rizqflow-profile-square.svg](design/logo/rizqflow-profile-square.svg): logo mark di latar `--rf-inverse-surface`, aman untuk crop lingkaran).
- **Nama** (ikut pencarian Instagram): `Rizqflow · Keuangan Keluarga & Zakat`
- **Bio:**
  ```
  Rezeki mengalir, setiap hak terpenuhi.
  Atur keuangan keluarga per hak + hitung zakat & haul Hijriyah.
  Segera di Play Store. Daftar tunggu ↓
  ```
- **Tautan:** `https://roziqrizal.com/karya/rizqflow?utm_source=instagram&utm_medium=bio`

**Boost ditahan sampai syaratnya terpenuhi**, karena hitungannya ketat untuk produk sekali bayar:

- Pro Rp 99.000 dikurangi potongan Play 15% menyisakan sekitar Rp 84.000.
- Kalau 5–10% pendaftar daftar tunggu akhirnya membeli Pro (**asumsi, belum ada data**), satu pendaftar bernilai sekitar **Rp 4.000–8.000**.
- Jadi boost baru menguntungkan bila biaya per pendaftar di bawah angka itu. Untuk akun baru tanpa reputasi, itu sulit.

Aturan main usulan:

1. Jangan boost konten yang dibuat khusus untuk iklan. Boost **posting organik yang terbukti paling banyak disimpan atau dibagikan**.
2. Uji kecil dulu: sekitar Rp 20.000–30.000 per hari selama 5–7 hari. Target: Indonesia, usia 22–40, minat keuangan syariah, zakat, keluarga atau parenting, dan pernikahan.
3. **Batas berhenti**: biaya per pendaftar (dihitung dari UTM) jauh di atas Rp 8.000 → hentikan, kembali ke organik.
4. Boost lebih besar paling masuk akal (a) setelah aplikasi ada di Play Store, karena iklan langsung mengarah ke unduhan, dan (b) menjelang Ramadan.

Hitungan membaik saat langganan Sync masuk di 1.1: satu pelanggan tahunan menyisakan sekitar Rp 127.000 per tahun dan berulang ([monetisasi.md](monetisasi.md)), jadi anggaran iklan lebih mudah kembali modal.

## Kalender

| Waktu (perkiraan) | Fase | Fokus |
|---|---|---|
| Sekarang–rilis (Nov–Des 2026) | P0, P1 | Fondasi, daftar tunggu, build in public, uji boost kecil |
| Bersamaan uji tertutup Tahap 8 | P2 | Pendaftar daftar tunggu jadi penguji beta; masukan dan testimoni |
| Rilis | P3 | Email ke daftar tunggu, harga perdana, ASO, ulasan pertama dari penguji |
| Jan–Feb 2027 | P4 | Kampanye musim zakat menjelang **Ramadan 1448 H (perkiraan mulai sekitar 8 Februari 2027**, pastikan dengan kalender Hijriyah Al-Kaukaba) |

Jeda sekitar dua bulan antara rilis dan Ramadan sengaja dipakai untuk merapikan aplikasi dari masukan pengguna awal sebelum puncak pencarian zakat.

## Metrik

- **Sebelum rilis:** jumlah pendaftar daftar tunggu, rasio pengunjung landing page yang mendaftar, saluran asal (UTM), biaya per pendaftar bila ada boost.
- **Setelah rilis:** retensi D7 dan D30, **jumlah hari mencatat per minggu** (metrik utama menurut riset, bukan jumlah unduhan), konversi ke Pro, rating dan ulasan Play Store.

Metrik setelah rilis butuh pengukuran di aplikasi atau server; apakah Firebase Analytics dipakai masih keputusan terbuka (roadmap.md, "Keputusan yang masih punya pemilik"), dan metrik pengguna anonim di server ada di Tahap 9.

## Keputusan yang sudah diambil

- **Pemasaran berjalan paralel dengan pengembangan**, dilacak sebagai jalur Tahap P0–P4 di roadmap.md, strateginya di dokumen ini (2026-10-24).
- **Akun Instagram khusus Rizqflow** dibuat, bukan memakai akun pribadi pemilik (usulan pemilik 2026-10-24).
- **Nama akun media sosial: `rizqflowra`** (dipilih pemilik 2026-10-24, tersedia di Instagram). `@rizqflow` sudah dipakai pihak lain; usulan `rizqflowapp` tidak dipakai. Nama yang sama dipakai juga di Threads, TikTok, dan X bila tersedia.

## Keputusan yang masih terbuka

- **Positioning**: membidik ceruk keluarga muslim muda dengan zakat sebagai pintu masuk (usulan di atas), atau umum sejak awal.
- **Waktu pemasaran per minggu** (usulan 2–3 jam).
- **Tampil sebagai founder** dengan nama dan wajah sendiri atau tidak, dan di platform mana pemilik sudah punya audiens (LinkedIn, Instagram, Threads, TikTok).
- **Ambang daftar tunggu** yang dianggap "minat tervalidasi" sampai rilis (mis. 100 pendaftar); dipakai untuk menutup butir "Validasi minat" di Tahap 0.
- **Anggaran boost per bulan** yang direlakan sebagai biaya eksperimen.
- **Siapa pemakai `@rizqflow` di Instagram**: bila produk atau bisnis keuangan yang aktif, itu risiko merek yang perlu dicatat di [konsep.md](konsep.md) ("Cek nama") dan dipertimbangkan sebelum nama dan `applicationId` terkunci di unggahan AAB pertama (Tahap 8).
- **Pembuat desain konten**: pemilik sendiri lewat Canva, atau Claude menyusun naskah carousel dan template.
- **Bentuk harga perdana**: harga awal yang lebih rendah untuk semua pembeli lalu naik, atau kode promo Play khusus pendaftar daftar tunggu. Bergantung pada harga final Pro ([monetisasi.md](monetisasi.md)).
