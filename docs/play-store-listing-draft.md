# Rizqflow — Draf Play Store listing dan Data Safety

> **DRAFT.** Belum final dan belum dipakai di Play Console. Disusun 2026-11-02 untuk Tahap 15 (butir "Store listing", "Kebijakan privasi, deklarasi fitur finansial, dan data safety") dan Tahap P3 (butir "Kata kunci ASO"). Angka harga dan keputusan yang ditandai **[KEPUTUSAN PEMILIK]** belum boleh dipublikasikan.

Sumber: [konsep.md](konsep.md), [monetisasi.md](monetisasi.md), [pemasaran.md](pemasaran.md), [ui-flow.md](ui-flow.md), [server-sync.md](server-sync.md), dan [roadmap.md](roadmap.md) Tahap 9, 13, 15, P3.

Tanda yang dipakai di dokumen ini:
- **[BUTUH ASET DARI PEMILIK/BUILD NYATA]**: gambar, tangkapan layar, atau video yang hanya bisa dibuat dari build rilis sungguhan di perangkat.
- **[KEPUTUSAN PEMILIK]**: pilihan yang belum diambil pemilik. Jangan diisi sendiri.
- **[TAHAN]**: klaim yang belum boleh dipublikasikan sampai terbukti di perangkat (lihat batasan klaim di [pemasaran.md](pemasaran.md)).

---

## 1. Judul dan deskripsi singkat

Play Store tidak punya kolom subtitle. Fungsi subtitle diambil alih oleh **deskripsi singkat** (maks 80 karakter), yang tampil di bawah judul pada kartu hasil pencarian.

**Judul (maks 30 karakter):**

- Usulan utama: `Rizqflow: Keuangan Keluarga` (27 karakter)
- Alternatif: `Rizqflow: Zakat & Keuangan` (26 karakter)

Judul tetap memakai nama merek (lihat [konsep.md](konsep.md), "Cek nama"). Pilihan judul memengaruhi ASO, jadi **[KEPUTUSAN PEMILIK]** dipilih sebelum unggahan AAB pertama (judul bisa diubah nanti, tetapi `applicationId` tidak).

**Deskripsi singkat (maks 80 karakter):**

`Bagi rezeki ke ruang Memberi, Diri, dan Keluarga. Catat keuangan offline.`

(Hitung ulang dengan pencacah karakter sebelum submit; target di bawah 80.)

---

## 2. Deskripsi panjang (maks 4000 karakter)

Teks di bawah ini siap disalin. Bagian yang ditandai `[TAHAN]` dan `[KEPUTUSAN PEMILIK]` diganti atau dihapus sebelum publikasi.

```
Aplikasi lain bertanya: uangmu habis ke mana?
Rizqflow bertanya: apakah setiap hak sudah ditunaikan?

Rizqflow adalah aplikasi keuangan keluarga yang membagi rezeki ke ruang-ruang sesuai aturan yang kamu tentukan. Setiap pemasukan dialirkan ke ruang seperti Memberi, Diri, dan Keluarga. Di Denah, kamu bisa melihat sekilas apakah tiap ruang sudah terpenuhi bulan ini.

Cara kerjanya
- Atur ruang dan persentasenya, misalnya 10% Memberi, 30% Diri, dan 60% Keluarga. Mulai dari tiga hak inti atau dari kosong.
- Catat pemasukan. Sebelum disimpan, kamu melihat pratinjau ke mana rezeki itu mengalir.
- Catat pengeluaran dengan cepat: nominal, ruang, kategori, dan akun. Simpan favorit untuk pengeluaran yang sering berulang.
- Denah menjawab satu pertanyaan: apakah setiap hak sudah ditunaikan bulan ini? Ruang yang mendekati batasnya diberi tanda lembut, tanpa warna merah dan tanpa tekanan.

Ruang dan akun
Ruang Memberi, Diri, dan Keluarga sudah tersedia. Kamu bisa menambah ruang lain, seperti dana darurat, belajar, atau kesehatan. Gratis hingga 5 ruang. Akun tunai, bank, dan dompet digital tidak dibatasi.

Zakat mal, opsional
Bila kamu ingin, aktifkan modul zakat di ruang Memberi. Aplikasi menghitung nisab dari harga emas yang kamu masukkan, memantau haul dengan kalender Hijriyah, dan mencatat saat zakat ditunaikan. Asumsi yang dipakai selalu tampil di layar: nisab 85 gram emas, tarif 2,5%, dan haul satu tahun Hijriyah. Tanggal Hijriyah adalah perkiraan dan bisa berbeda sehari dari penetapan resmi. Rizqflow adalah alat bantu hitung, bukan fatwa. Dasar perhitungan zakat tetap gratis.

Mulai dari perangkatmu sendiri
Semua data tersimpan di ponselmu, dan aplikasi berfungsi penuh tanpa internet. Cadangan lokal terenkripsi dengan sandi dan ekspor CSV selalu gratis. PIN dan sidik jari juga gratis. Tidak ada iklan.

[TAHAN sampai terbukti di perangkat, lihat Tahap 13. Tulis bagian ini hanya setelah itu.]
Sync dan ruang keluarga
Rizqflow Sync (langganan) menyinkronkan data antar-perangkat dan membuka ruang keluarga bersama untuk pasangan. Satu langganan berlaku untuk keluarga sampai 5 anggota. Data yang disinkronkan dienkripsi di perangkat sebelum dikirim.
[Pengganti bila verifikasi belum selesai: hapus seluruh bagian ini dari deskripsi dan tampilkan Sync hanya di layar pembelian.]

Paket
- Gratis: ruang inti, transaksi tanpa batas, aturan persentase, Denah, zakat untuk satu profil, ekspor CSV, cadangan lokal terenkripsi, PIN, dan sidik jari.
- Rizqflow Pro (sekali bayar): ruang peran tambahan, aturan alokasi lanjutan, multi-profil zakat, laporan PDF, widget, dan tema. [KEPUTUSAN PEMILIK: harga Pro belum final, jangan tulis angka di sini.]
- Rizqflow Sync (langganan lewat Google Play): Rp 19.000 per bulan atau Rp 149.000 per tahun, dengan uji coba 7 hari. [KEPUTUSAN PEMILIK: konfirmasi angka setelah produk dibuat di Play Console.] Tidak termasuk Pro. Langganan dikelola dan dibatalkan di Google Play.

Rizqflow dibuat untuk keluarga yang ingin rezeki yang masuk terasa tertata: tenang, bukan panik. Tidak ada streak. Pengingat malam bisa kamu atur atau matikan.

Rezeki mengalir, setiap hak terpenuhi.
```

Catatan untuk penulis deskripsi:
- Frasa kata kunci ASO sudah disebar secara alami di judul, deskripsi singkat, dan paragraf panjang (lihat bagian 4). Jangan menambah pengulangan demi kepadatan kata kunci; Play bisa menurunkan listing yang dianggap spam kata kunci.
- Multi-mata uang, tangkap otomatis dari notifikasi, dan laporan tahunan sengaja tidak disebut karena masuk Versi 1.1 atau belum diverifikasi di perangkat. Widget dan tema Pro sudah ada di kode tetapi belum diverifikasi di perangkat (lihat Tahap 15); sebut hanya setelah diverifikasi.
- Jangan menyebut "bank sync", Gmail, atau AI. Tidak ada di rilis 1.

---

## 3. Aset visual dan metadata toko

| Aset | Ukuran | Status |
|---|---|---|
| Ikon aplikasi | 512 x 512 px | **[BUTUH ASET DARI PEMILIK/BUILD NYATA]** Usulan sumber: `docs/design/logo/rizqflow-icon-mark.svg` (ekspor PNG). Dipastikan dulu sesuai ikon di build rilis. |
| Gambar fitur | 1024 x 500 px | **[BUTUH ASET DARI PEMILIK/BUILD NYATA]** belum ada. |
| Tangkapan layar ponsel | minimal 2, rekomendasi 4 sampai 8 | **[BUTUH ASET DARI PEMILIK/BUILD NYATA]** harus dari build rilis di perangkat, bukan mockup. Kandidat: Denah (S05), Catat (S06), Alirkan rezeki (S07), Detail ruang (S11), Zakat (S14 sampai S16), Laporan (S36). Pakai data contoh yang tidak memuat data keuangan asli pemilik. |
| Video promosi | opsional | **[BUTUH ASET DARI PEMILIK/BUILD NYATA]** direkam dari perangkat (lihat Reels 15 detik di [pemasaran.md](pemasaran.md)). |
| Kategori | - | Keuangan. |
| Kontak developer | - | **[KEPUTUSAN PEMILIK]** email kontak dan situs web. |
| Kebijakan privasi (URL) | - | **[BUTUH ASET DARI PEMILIK/BUILD NYATA]** belum ada. Harus berisi isi bagian 4 dan 5. |
| Target usia dan kuesioner rating konten | - | **[KEPUTUSAN PEMILIK]** usia target, lalu isi kuesioner di konsol. |
| Deklarasi fitur finansial | - | **[KEPUTUSAN PEMILIK]** Cek apakah deklarasi fitur finansial di Play Console berlaku untuk pengelola keuangan pribadi. Roadmap menyebut ini sebagai bagian Tahap 15. |

---

## 4. Kata kunci ASO

Play Store tidak punya kolom kata kunci tersembunyi. Yang dihitung algoritma pencarian adalah judul, deskripsi singkat, dan deskripsi panjang. Karena itu daftar ini adalah **target penempatan teks**, bukan isian metadata.

**Sudah ada di [pemasaran.md](pemasaran.md) (wajib dipakai):**

| Kata kunci | Ditempatkan di |
|---|---|
| catatan keuangan keluarga | Deskripsi panjang (paragraf pembuka); judul usulan utama memuat "Keuangan Keluarga" |
| zakat mal | Deskripsi singkat tidak memuat; deskripsi panjang bagian zakat |
| budget rumah tangga | Deskripsi panjang (paragraf pembuka, bila pemilik setuju) [KEPUTUSAN PEMILIK: frasa ini belum ada di draf paragraf pembuka; tambahkan satu kali] |
| pengelola keuangan | Deskripsi panjang (paragraf pembuka, bila pemilik setuju) [KEPUTUSAN PEMILIK: sama seperti di atas] |

**Usulan tambahan (relevan dengan fitur nyata, bukan kata kunci kosong):**

| Kata kunci | Alasan | Catatan |
|---|---|---|
| alokasi gaji | Sesuai fitur inti: pembagian pemasukan ke ruang | Sudah tercermin di deskripsi |
| anggaran bulanan | Istilah yang sering dicari | Cocok di paragraf cara kerja |
| catat pengeluaran | Istilah sehari-hari | Cocok di paragraf catat pengeluaran |
| kalkulator zakat | Pencarian zakat yang paling umum | Sebut "hitung" dan "alat bantu", bukan kalkulator resmi |
| nisab emas | Istilah spesifik zakat mal | Cocok di bagian zakat |
| haul hijriyah | Pembeda utama | Cocok di bagian zakat |
| dana darurat | Ruang yang umum | Sebut sebagai contoh ruang |
| keuangan keluarga offline | Pembeda privasi | Sesuai janji offline-first |

Yang sengaja **tidak** dipakai: "gratis" dan "terbaik" sebagai kata kunci, "syariah" sebagai label utama (produk universal, modul Islami opsional, lihat [konsep.md](konsep.md)), dan merek pesaing.

---

## 5. Pemetaan Data Safety (Play Console)

**Catatan penting sebelum mengisi formulir:**

1. **Cakupan Sync di rilis 1 belum final.** Roadmap mencatat bahwa kode baru menyinkronkan transaksi pengeluaran ruang keluarga, sedangkan rencana rilis 1 mencakup semua entitas. Isi formulir sesuai cakupan **saat build rilis dibuat**, bukan sesuai draf ini.
2. **Data terenkripsi ujung ke ujung tetap bisa dihitung "dikumpulkan".** Aturan Play menentukan apakah data yang meninggalkan perangkat dihitung dikumpulkan, dan ini harus dicek ulang di dokumentasi Google Play Data safety saat mengisi, bukan diasumsikan dari draf ini. Pemetaan di bawah mengikuti model di [server-sync.md](server-sync.md), tetapi keputusan akhirnya ada di pemilik.
3. **Kategori "riwayat pembelian" belum dicek di konsol** (roadmap Tahap 15). Token pembelian Pro dan Sync disimpan di server terenkripsi dengan `APP_KEY`, **bukan** enkripsi ujung ke ujung, jadi tidak boleh diberi label yang sama dengan data E2E.
4. **Firebase Analytics dan FCM dipakai di rilis 1** (keputusan pemilik 2026-10-24). Tidak ada data keuangan di event atau properti pengguna.

**Pertanyaan umum formulir:**

| Pertanyaan | Jawaban usulan | Catatan |
|---|---|---|
| Apakah aplikasi mengumpulkan atau membagikan data pengguna? | Ya | |
| Apakah semua data dienkripsi saat transit? | Ya | Build rilis hanya boleh memakai `https` (manifest utama `usesCleartextTraffic="false"`). Verifikasi di build rilis. |
| Apakah pengguna bisa meminta penghapusan datanya? | Ya | Lewat **Hapus akun** di aplikasi, yang menghapus data server. Play juga mewajibkan **URL web** untuk permintaan hapus akun: **[BUTUH ASET DARI PEMILIK/BUILD NYATA]**. Catatan: kunci akun di Android Keystore tidak ikut terhapus saat Hapus akun (lihat [roadmap.md](roadmap.md) Tahap 9), dan data di ponsel hilang dengan uninstall. Tulis ini jujur di URL penghapusan. |
| Apakah data dikumpulkan secara opsional? | Sebagian | Data akun hanya ada bila pengguna masuk dengan Google. Data sinkron hanya bila Sync aktif. Analitik dan token FCM ikut bagian yang dapat dimatikan [KEPUTUSAN PEMILIK: pastikan Analytics bisa dimatikan dari pengaturan]. |

**Pemetaan per kategori data:**

| Kategori Play | Jenis data | Dikumpulkan | Dibagikan | Tujuan | Enkripsi transit | Enkripsi at rest | Catatan |
|---|---|---|---|---|---|---|---|
| Personal info | Email address | Ya | Tidak, kecuali penyedia layanan (lihat catatan) | Account management | Ya | Ya (server, enkripsi standar) | Email dari akun Google disimpan di server (`GoogleAccountResolver`). Pemakaian untuk analitik dan promo **belum** diputuskan ([konsep.md](konsep.md)). Jangan centang Marketing atau Analytics sebelum keputusan itu. |
| Personal info | User IDs | Ya | Tidak | Account management | Ya | Ya | ID akun server (`sub` Google dan ID internal). |
| Financial info | Riwayat pembelian (purchase history) | Ya | Tidak, kecuali Google Play sebagai pemroses pembayaran | App functionality | Ya | Ya, tetapi dengan `APP_KEY` server, **bukan E2E** | Token dan status Pro dan Sync. Kategori ini belum dicek di konsol. |
| Financial info | Data keuangan lain (transaksi, saldo, nominal, akun, ruang) | Hanya saat Sync aktif, berisi data terenkripsi | Tidak | App functionality | Ya | Ya, dienkripsi di perangkat sebelum dikirim, server menyimpan blob buram | **[TAHAN]** label "encrypted at rest" dan "tidak dibagikan" bergantung pada verifikasi Tahap 13 (kunci Keystore asli) dan pada cara Play menghitung data E2E. Jangan dipublikasikan sebelum terbukti. Aplikasi tanpa Sync tidak mengirim data keuangan keluar perangkat. |
| Device or other IDs | ID perangkat aplikasi (`device_id`) | Ya | Tidak | App functionality, Account management | Ya | Ya | Dipakai untuk token API dan pemilahan sesi. |
| Device or other IDs | Token FCM | Ya, hanya saat Sync dan ada ruang bersama | Ya, ke Google (FCM) sebagai penyedia layanan | App functionality | Ya | Tidak diperlukan untuk formulir ini, tetapi disembunyikan dari log | Pesan push hanya berisi `roomId` dan `kind`, tanpa nominal. |
| Device or other IDs | ID instance Firebase Analytics | Ya | Ya, ke Google (Firebase) sebagai penyedia layanan | Analytics | Ya | Ya | **[KEPUTUSAN PEMILIK]** cek apakah Advertising ID ikut terkumpul. Aplikasi tanpa iklan sebaiknya menonaktifkannya dan tidak meminta izin `AD_ID`. |
| App activity | Interaksi aplikasi (event Analytics) | Ya | Ya, ke Google (Firebase) sebagai penyedia layanan | Analytics | Ya | Ya | Tidak ada nominal, nama, catatan, atau akun di event (aturan tetap). |
| App info and performance | Log crash | Belum ada | - | - | - | - | Jika Crashlytics dipasang nanti, tambahkan baris ini. |
| Location, Photos, Contacts, Messages | - | Tidak | - | - | - | - | Kamera dipakai untuk memindai QR undangan di perangkat dan tidak dikirim. Izin notifikasi juga tidak dicatat sebagai data. |

**Pihak ketiga dan penyedia layanan (perlu disebut di kebijakan privasi dan formulir):**

| Penyedia | Peran | Data yang diterima | Status |
|---|---|---|---|
| Google (Firebase Analytics) | Analitik | ID instance, event interaksi tanpa data keuangan | Dipakai di rilis 1 (keputusan 2026-10-24). |
| Google (FCM) | Notifikasi push saat ada catatan baru di ruang bersama | Token FCM dan isi pesan `roomId` dan `kind` | Dipakai saat Sync dan proyek Firebase sudah dibuat. Belum aktif: proyek Firebase dan `google-services.json` menunggu pemilik. |
| Google Play Billing dan Play Developer API | Pembayaran dan verifikasi pembelian | Token pembelian, status langganan | Pembayaran diproses Google. Aplikasi tidak melihat data kartu. |
| Brevo (SMTP) | Kode verifikasi email dan lupa sandi (web) | Alamat email dan isi kode | **[KEPUTUSAN PEMILIK]** hanya relevan bila fitur email+sandi web aktif di produksi (keputusan 2026-10-24). Sebut bila dipakai. |
| Penyedia VPS (IDCloudHost) | Hosting server milik pemilik | Data server (terenkripsi untuk isi keuangan) | Penyedia infrastruktur; sebut sebagai penyedia hosting di kebijakan privasi bila pemilik ingin transparan. |

**Dibagikan atau tidak:** formulir Play membedakan "dikumpulkan" dan "dibagikan". Transfer ke penyedia layanan yang memproses data atas nama developer umumnya tidak dihitung "dibagikan", tetapi definisinya harus dicek di dokumentasi Google saat mengisi. Jangan menyatakan "tidak ada data dibagikan" sebelum pengecekan itu selesai.

---

## 6. Daftar keputusan dan aset yang masih terbuka

**Butuh aset dari pemilik atau build nyata:**
1. Ikon 512 x 512 untuk Play (dari `docs/design/logo/rizqflow-icon-mark.svg`, dicek di build).
2. Gambar fitur 1024 x 500.
3. Tangkapan layar ponsel dari build rilis, minimal 2.
4. URL kebijakan privasi (berisi bagian 5 dan rincian Tahap 9 dan 13).
5. URL penghapusan akun berbasis web.
6. Video promosi (opsional).

**Butuh keputusan pemilik:**
1. Judul final: `Rizqflow: Keuangan Keluarga` atau `Rizqflow: Zakat & Keuangan`.
2. Harga Pro final (monetisasi.md menunggu uji minat; deskripsi tidak memuat angka sampai diputuskan).
3. Konfirmasi harga Sync (Rp 19.000 per bulan, Rp 149.000 per tahun) setelah produk dibuat di Play Console.
4. Email kontak developer dan situs web.
5. Target usia dan kuesioner rating konten.
6. Apakah email dipakai untuk analitik atau promo. Mengubah jawaban ini mengubah formulir Data safety.
7. Apakah Advertising ID dimatikan di Firebase Analytics.
8. Apakah fitur email+sandi web dan Brevo aktif saat rilis.
9. Apakah frasa "budget rumah tangga" dan "pengelola keuangan" masuk paragraf pembuka.
10. Apakah deskripsi panjang memuat klaim Sync dan enkripsi (menunggu verifikasi perangkat di Tahap 13) atau memakai versi pengganti.

**Verifikasi teknis yang menentukan isi listing:**
- Cakupan Sync di build rilis (menentukan kolom data keuangan di formulir).
- Pembuktian enkripsi di Android Keystore asli (Tahap 13). Klaim "dienkripsi di perangkat" di deskripsi dan formulir ditahan sampai ada.
- Kategori riwayat pembelian di konsol.
- Build rilis memakai `https` saja.
- Hapus akun di server dan efek pada kunci akun di Keystore.
