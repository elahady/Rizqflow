# Kebijakan Privasi Rizqflow (DRAFT)

> **STATUS: DRAFT. BELUM DIREVIEW HUKUM.** Jangan dipublikasikan di Play Console, situs, atau aplikasi sebelum pengacara atau konsultan pelindungan data meninjau bagian yang ditandai `[PERLU REVIEW HUKUM]`, dan pemilik memutuskan bagian yang ditandai `[BUTUH KEPUTUSAN PEMILIK]`.
>
> **Terkait:** [docs/roadmap.md](roadmap.md), Tahap 15, butir "Kebijakan privasi + data safety, uji tertutup Play Console, store listing, case study" (sekitar baris 172), dan butir "Kebijakan privasi + deklarasi data safety (menyebut server dan UU PDP)" (sekitar baris 80, catatan "Konsekuensi" di Tahap 9). Sumber fakta: [konsep.md](konsep.md), [server-sync.md](server-sync.md), [monetisasi.md](monetisasi.md), [auth-google.md](auth-google.md).
>
> **Tanggal draf:** 2 November 2026. **Bahasa:** Indonesia (target awal Indonesia). Versi Inggris menyusul bila rilis internasional.

**Penanda yang dipakai di draf ini:**

- `[PERLU REVIEW HUKUM]`: butuh pendapat pengacara atau ahli UU PDP sebelum dipakai.
- `[BUTUH KEPUTUSAN PEMILIK]`: keputusan produk yang belum diambil, harus diisi sebelum rilis.
- `[BUTUH KEPUTUSAN PEMILIK PASCA-RILIS]`: sengaja ditunda ke pasca-rilis, tetapi kebijakan rilis 1 harus tetap jujur tentangnya.
- `[PERLU VERIFIKASI KODE]`: klaim yang harus dicocokkan dengan kode sebelum rilis, karena belum tentu sudah terpasang.

---

## 1. Ringkasan

Rizqflow adalah aplikasi pencatat keuangan pribadi yang membagi penghasilan ke "ruang" (misalnya Memberi, Diri, Keluarga) menurut aturan yang kamu tentukan. Aplikasi ini dibuat dengan prinsip:

- **Data keuanganmu disimpan di ponselmu.** Aplikasi tetap berfungsi penuh tanpa internet.
- **Jika kamu memakai Sync**, salinan data keuanganmu dikirim ke server dalam keadaan **terenkripsi ujung ke ujung**. Kuncinya ada di perangkatmu. Server tidak bisa membaca isi transaksi, nominal, nama kategori, catatan, atau nama akunmu.
- **Tidak ada iklan.** Kami tidak menjual data pribadimu.

Kebijakan ini menjelaskan data apa saja yang kami simpan, untuk apa, siapa yang ikut memproses, dan hak apa yang kamu punya.

---

## 2. Pengendali data dan kontak

- Pengembang: `[NAMA PENGEMBANG, BUTUH KEPUTUSAN PEMILIK]`
- Kontak privasi: `[ALAMAT EMAIL KONTAK, BUTUH KEPUTUSAN PEMILIK]`
- Pelindungan data pribadi (DPO): `[BUTUH KEPUTUSAN PEMILIK, PERLU REVIEW HUKUM apakah kewajiban pejabat pelindungan data berlaku]`

---

## 3. Data apa yang kami kumpulkan

### 3.1 Data yang hanya ada di ponselmu

Berikut tidak pernah dikirim ke server kami, kecuali kamu memakai Sync (lihat 3.4):

- Semua transaksi (pemasukan, pengeluaran, transfer), nominal, tanggal, kategori, catatan.
- Daftar ruang, aturan alokasi, akun (tunai, bank, e-wallet), dan kategori.
- Transaksi berulang, tagihan, utang-piutang, dan jadwal DCA atau batas risiko (fitur Pro).
- Nama pengguna dan kata sandi untuk akun lokal (tanpa Google), yang hanya ada di ponsel.
- Berkas cadangan lokal (backup) dan berkas ekspor CSV. Berkas ini kamu simpan sendiri di lokasi yang kamu pilih.
- Pengaturan aplikasi, PIN, dan biometrik. Kunci enkripsi disimpan di Android Keystore.

### 3.2 Akun Google dan akun server

Saat kamu masuk dengan Google, server kami menyimpan:

- **Alamat email** akun Google. Email ini dipakai untuk mengenali akun (dan pemulihan) dan juga dipakai untuk menghitung jumlah pengguna serta broadcast promo di masa depan (lihat bagian 6).
- **Nama tampilan** dari Google.
- **Pengenal akun Google (`sub`)**, bukan email, supaya akun tetap dikenali walau email berubah.
- **Kata sandi**, hanya untuk akun yang mendaftar dengan email dan sandi di web. Disimpan dalam bentuk terenkripsi satu arah (hash), tidak pernah dalam bentuk asli. `[PERLU VERIFIKASI KODE]`
- Waktu akun dibuat dan waktu masuk terakhir.

Server **tidak** menyimpan token akses Google. ID token dari Google hanya dipakai untuk memverifikasi kamu saat masuk, lalu dibuang.

### 3.3 Token perangkat untuk notifikasi (FCM)

Jika kamu memakai Sync, aplikasi mendaftarkan **token perangkat** ke server. Token ini adalah alamat dari Firebase Cloud Messaging (FCM), dipakai untuk mengirim notifikasi "ada catatan baru" ke perangkat anggota ruang keluarga. Yang tersimpan:

- Token FCM per perangkat, dengan pengenal pemasangan aplikasi (UUID acak, dibuat saat aplikasi dipasang).
- Dihapus saat kamu keluar, saat kamu menghapus akun, atau bila Google menyatakan token tidak berlaku lagi.

### 3.4 Metadata sinkronisasi (saat Sync dipakai)

Saat Sync aktif, setiap perubahan data dikirim sebagai **baris log terenkripsi**. Server hanya melihat hal berikut:

| Yang disimpan server | Isi | Catatan |
|---|---|---|
| Isi perubahan (payload) | Terenkripsi. Server tidak punya kuncinya | Nominal, kategori, catatan, nama akun, dan lainnya tidak terbaca |
| Pengenal ruang (`room_id`) | Pengenal acak, bukan nama ruang | Dipakai untuk mengarahkan data ke anggota yang tepat |
| Pengenal akun (`account_id`) | Pengenal acak, bukan nama akun | Hanya label routing |
| Jenis rekaman (`record_type`) | Label jenis, misalnya "transaksi" | Tidak berisi nilai |
| Pengenal rekaman (`record_id`) | Pengenal acak per rekaman | Bukan isi, tetapi dipakai server untuk mengenali baris yang sama |
| Pengenal perangkat (`device_id`) | Pengenal pemasangan | Untuk membedakan perangkatmu dari perangkat lain |
| Waktu perubahan (`changed_at`) dan nomor urut | Waktu | Untuk urutan sinkronisasi |
| Tanda hapus | Ya atau tidak | Penghapusan ditulis sebagai baris baru, tidak menghapus baris lama |
| Ukuran data | Jumlah dan besar baris | Untuk kuota dan kapasitas server |

Yang dapat disimpulkan dari metadata ini: kapan dan seberapa sering kamu sinkron, berapa banyak perubahan yang kamu buat, dan berapa anggota dalam satu ruang. Yang **tidak** bisa disimpulkan: berapa uangmu, untuk apa, dan dari siapa.

`[PERLU VERIFIKASI KODE]` Konfirmasikan bahwa daftar di atas sama dengan kolom tabel `sync_log_entries` dan `push_tokens` di server, dan tidak ada kolom tambahan.

### 3.5 Data pembelian

- Saat kamu membeli Pro atau langganan Sync lewat Google Play, Google memproses pembayarannya. **Rizqflow tidak pernah menerima nomor kartu, rekening, atau data pembayaran lain.**
- Server kami menyimpan **token pembelian Google Play**, nama produk, dan status pembelian, supaya paket kamu bisa diverifikasi dan dipulihkan di perangkat lain. Verifikasi dilakukan lewat Google Play Developer API.
- Paket yang kamu miliki (misalnya Pro atau Sync) disimpan sebagai daftar di akunmu di server, untuk menghitung jumlah pengguna Pro dan Sync.

### 3.6 Analitik (Firebase Analytics)

Lihat bagian 5. Ringkasnya: Firebase Analytics dipakai untuk memahami penggunaan aplikasi secara umum. **Tidak ada data keuangan yang pernah masuk ke event atau properti pengguna Analytics.**

### 3.7 Yang tidak kami kumpulkan

- Data lokasi, kontak, galeri, atau berkas di ponselmu (kecuali kamu sendiri memilih berkas cadangan atau ekspor).
- Iklan dan pelacakan lintas aplikasi. Rizqflow tidak memuat iklan.
- Data kesehatan dan data biometrik. Biometrik hanya dipakai di perangkat untuk membuka aplikasi dan tidak dikirim ke server.
- Akses ke Gmail. `[PERLU KONFIRMASI: auth-google.md menyebut akses email hanya bila Gmail diaktifkan. Pastikan scope Gmail tidak diminta di rilis 1, lalu tulis "tidak mengakses Gmail"]`
- Foto struk dan rekaman suara. Fitur ini belum ada di rilis 1; bila nanti ditambahkan, kebijakan ini wajib diperbarui dan diberi tahu ke pengguna.

---

## 4. Enkripsi ujung ke ujung

Rizqflow menerapkan enkripsi ujung ke ujung untuk data yang disinkronkan. Prinsipnya:

- **Kunci akun dibuat secara acak di perangkatmu** dan disimpan di Android Keystore.
- **Data dienkripsi di perangkat sebelum dikirim.** Server hanya menyimpan data yang sudah terenkripsi.
- **Kunci ruang keluarga** adalah kunci terpisah per ruang yang dibagi. Saat kamu mengeluarkan anggota, kunci ruang diganti untuk data berikutnya.
- **Kode pemulihan 24 kata** ditampilkan sekali saat Sync diaktifkan dan wajib kamu catat. Salinan kunci akun dibungkus dengan kode ini sebelum disimpan di server. Server tidak bisa membukanya.

Yang server **bisa** dan **tidak bisa** baca:

| Server **bisa** | Server **tidak bisa** |
|---|---|
| Melihat siapa yang sinkron, kapan, dan berapa banyak data | Membaca nominal, kategori, catatan, atau nama akun |
| Melihat metadata di bagian 3.4 | Membaca isi transaksi, saldo, atau aturan alokasi |
| Menyimpan email, nama, dan pengenal akun Google | Membuka salinan kunci akun (terbungkus kode 24 kata) |
| Menyimpan token FCM dan token pembelian | Membuka data anggota ruang keluarga |

Klaim yang kami pakai di aplikasi dan di kebijakan ini adalah: **"Data keuanganmu selalu terenkripsi, hanya kamu yang bisa baca."** Kami sengaja **tidak** menulis "data tidak pernah dikirim ke server", karena Sync memang mengirim data terenkripsi ke server.

**Keterbatasan yang harus diketahui pengguna:**

- Saat ini, sinkronisasi baru mencakup **pengeluaran** di ruang keluarga yang dibagi. Entitas lain (pemasukan, transfer, akun, kategori, tagihan, dan seterusnya) sedang dikerjakan. `[PERLU VERIFIKASI KODE saat rilis: daftar entitas yang benar-benar tersinkron]`
- Pemulihan kunci saat ini mencakup **kunci akun**. Kunci ruang keluarga belum ikut dibungkus. Ruang keluarga yang dibagi perlu diundang ulang lewat QR di perangkat baru. `[BUTUH KEPUTUSAN PEMILIK sebelum rilis: apakah ini cukup untuk rilis 1, dan apa yang ditampilkan ke pengguna]`
- Jika semua perangkat hilang **dan** kode 24 kata hilang, data di Sync tidak bisa dipulihkan oleh siapa pun, termasuk kami. Ponsel adalah sumber kebenaran utama.
- `[PERLU REVIEW HUKUM]` Klaim "server tidak bisa membaca" harus didukung pengujian nyata sebelum rilis (sesuai keputusan pemilik 2026-10-24). Sampai itu terbukti, kebijakan ini harus memakai bahasa yang sama dengan layar masuk aplikasi.

---

## 5. Firebase Analytics dan Firebase Cloud Messaging

Kami memakai dua layanan Firebase dari Google:

### 5.1 Firebase Cloud Messaging (FCM)

- Dipakai untuk mengirim notifikasi "ada catatan baru" ke anggota ruang keluarga.
- Isi notifikasi **hanya** berisi pengenal ruang dan jenis kabar. Tidak berisi nominal, nama, catatan, akun, atau identitas pencatat.
- Google (FCM) melihat bahwa sebuah ruang menerima catatan baru dan kapan. Google tidak melihat isinya.

### 5.2 Firebase Analytics

- Dipakai untuk memahami penggunaan aplikasi secara umum, misalnya layar mana yang sering dibuka dan apakah fitur tertentu dipakai.
- **Aturan tetap kami:** tidak ada data keuangan di event atau properti pengguna Analytics. Data keuangan berarti: nominal, nama kategori, nama ruang, catatan, nama akun, saldo, dan aturan alokasi.
- Jumlah pengguna aktif, pengguna Pro, dan pengguna Sync **dihitung dari server kami**, bukan dari Analytics.
- `[PERLU VERIFIKASI KODE sebelum rilis]` Firebase Analytics belum terpasang di aplikasi (menunggu proyek Firebase). Sebelum rilis: tinjau semua pemanggilan `logEvent` dan `setUserProperty` untuk memastikan tidak ada nilai dari data keuangan, dan tulis daftar event di lampiran.
- `[BUTUH KEPUTUSAN PEMILIK]` Mekanisme persetujuan (consent) untuk Analytics. Dokumen konsep mencatat ini belum dibahas. Pertanyaan: apakah Analytics perlu toggle opt-out di aplikasi, dan apakah Firebase Analytics dimatikan secara default sampai pengguna setuju. `[PERLU REVIEW HUKUM]` untuk UU PDP dan kebijakan Google Play.

---

## 6. Email untuk menghitung pengguna dan promo di masa depan

Email akun Google kamu bisa dipakai untuk:

1. **Menghitung jumlah pengguna aktif** (dihitung dari server, tidak dibagikan ke pihak lain).
2. **Mengirim broadcast promo** di masa depan, misalnya pengumuman fitur atau kode promo untuk pendaftar daftar tunggu.

Saat ini **belum ada** fitur broadcast promo. Ketika fitur itu dibangun, kebijakan ini akan diperbarui dan kamu akan diberi tahu sebelum email dipakai untuk promo.

`[BUTUH KEPUTUSAN PEMILIK PASCA-RILIS]` Mekanisme persetujuan untuk email promo belum diputuskan. Sampai diputuskan, kebijakan rilis 1 menulisnya apa adanya:

- Pemakaian email untuk promo **bersifat implisit**: email yang kamu berikan saat masuk dengan Google dapat dipakai untuk mengirim promo, dan tidak ada kotak centang opt-in terpisah di rilis 1.
- Kamu bisa meminta berhenti menerima promo dengan menghubungi `[ALAMAT EMAIL KONTAK]`. `[BUTUH KEPUTUSAN PEMILIK: apakah ada tautan berhenti langganan di setiap email promo, dan apakah itu cukup]`
- Pemakaian ini akan **ditinjau ulang setelah rilis**, dan pendekatan persetujuan eksplisit (opt-in) dapat diterapkan kemudian.

`[PERLU REVIEW HUKUM]` Pendekatan implisit untuk promo dapat bermasalah di bawah UU PDP, karena persetujuan harus diberikan secara sah dan dapat dibuktikan. Sebelum broadcast pertama dikirim, pendekatan ini wajib dinilai ulang. Deklarasi Data Safety di Play Console juga perlu menyebut pemakaian untuk pemasaran secara terpisah, bukan hanya "fungsi aplikasi".

---

## 7. Dasar pemrosesan data (UU PDP)

Rizqflow memproses data pribadi berdasarkan **Undang-Undang Nomor 27 Tahun 2022 tentang Pelindungan Data Pribadi (UU PDP)**. Dasar pemrosesan yang kami gunakan:

| Data | Tujuan | Dasar yang diusulkan | Catatan |
|---|---|---|---|
| Email, nama, pengenal Google | Akun dan masuk | Pelaksanaan layanan yang kamu minta (kontrak) | `[PERLU REVIEW HUKUM]` |
| Token FCM dan pengenal perangkat | Notifikasi ke anggota ruang | Pelaksanaan layanan Sync, dengan Sync yang kamu aktifkan sendiri | `[PERLU REVIEW HUKUM]`: token FCM termasuk data pengenal perangkat |
| Metadata sinkronisasi | Menjalankan Sync dan pemulihan | Pelaksanaan layanan yang kamu minta | Isi terenkripsi, metadata tetap perlu dijelaskan |
| Data pembelian | Verifikasi paket | Pelaksanaan kontrak jual beli | `[PERLU REVIEW HUKUM]` |
| Email untuk promo | Promo | Persetujuan | `[BUTUH KEPUTUSAN PEMILIK PASCA-RILIS]`, lihat bagian 6 |
| Firebase Analytics | Memahami penggunaan | Persetujuan atau kepentingan sah | `[BUTUH KEPUTUSAN PEMILIK]`, lihat bagian 5.2 |

`[PERLU REVIEW HUKUM]` Hal yang perlu dinilai pengacara:

- Apakah **data keuangan pribadi** termasuk data pribadi yang bersifat spesifik menurut UU PDP. Kami menganggap ya sebagai pendekatan hati-hati.
- Apakah dasar kontrak cukup untuk Sync, atau perlu persetujuan eksplisit tersendiri saat Sync diaktifkan.
- Apakah Rizqflow perlu menunjuk pejabat pelindungan data pribadi.
- Pasal rujukan UU PDP di kebijakan final, dan kewajiban pemberitahuan kebocoran data kepada pengguna dan lembaga terkait.

---

## 8. Dengan siapa data dibagikan

Kami **tidak menjual** data pribadimu. Data dibagikan hanya kepada pihak berikut, sebatas yang diperlukan:

- **Google**: masuk dengan akun Google, Google Play (pembelian dan verifikasi), Firebase Cloud Messaging, Firebase Analytics.
- **Penyedia server dan hosting**: server Rizqflow berjalan di VPS yang disewa dari IDCloudHost (Indonesia). `[PERLU KONFIRMASI: lokasi data center dan apakah ada cadangan di luar Indonesia]`
- **Penyedia pengiriman email**: untuk kode verifikasi dan reset sandi di web. `[PERLU KONFIRMASI penyedia final, saat ini Brevo, dan belum diaktifkan di produksi]`
- **Anggota ruang keluarga yang kamu undang**: mereka melihat transaksi ruang yang dibagi beserta nama akun sumbernya, tetapi **tidak** melihat saldo akun itu atau transaksi lain di ruang lain. Anggota yang dikeluarkan tetap bisa membaca data yang sudah mereka terima sebelum dikeluarkan.

---

## 9. Penyimpanan dan retensi

- **Data di ponsel** disimpan selama aplikasi terpasang dan kamu tidak menghapusnya. Menghapus aplikasi menghapus data lokalnya.
- **Data di server** (akun, token, metadata sinkronisasi, dan data pembelian) disimpan selama akunmu aktif.
- **Cadangan server:** database dicadangkan setiap hari. Salinan cadangan ikut tersimpan sampai cadangan itu dihapus menurut jadwal retensi cadangan. `[BUTUH KEPUTUSAN PEMILIK: berapa lama cadangan disimpan, misalnya 30 hari, dan tulis angka itu di sini]`
- **Log server** (termasuk alamat IP dan waktu permintaan) dapat disimpan untuk keamanan dan pemecahan masalah. `[PERLU VERIFIKASI: konfigurasi log di VPS dan lama penyimpanannya, belum tercatat di repo]`

---

## 10. Hak kamu

Kamu berhak:

- **Mengakses** data yang kami simpan tentang akunmu.
- **Memperbaiki** data akun yang salah.
- **Menghapus akun dan data server** (lihat bagian 10.1).
- **Menarik persetujuan** yang sudah kamu berikan, misalnya berhenti menerima promo.
- **Mengajukan keberatan** atas pemrosesan tertentu, dan mengajukan keluhan ke lembaga pelindungan data pribadi yang berwenang.
- **Mengekspor data** kamu dalam format CSV atau cadangan lokal terenkripsi. Fitur ini gratis dan tersedia di aplikasi.

Untuk menggunakan hak ini, hubungi `[ALAMAT EMAIL KONTAK]`. `[PERLU REVIEW HUKUM: batas waktu respons sesuai UU PDP]`

### 10.1 Hapus akun

Saat kamu menghapus akun di menu Lainnya, yang terjadi:

- **Di server:** akun, token masuk, token perangkat FCM, riwayat pembelian yang terkait akun itu, dan semua baris sinkronisasi milik akun itu **dihapus**. Semua token masuk dicabut.
- **Di ponsel:** **data di ponselmu tidak disentuh.** Transaksi, ruang, akun, dan cadangan lokal tetap ada dan tetap bisa dipakai. Penghapusan akun server tidak mengubah apa pun di perangkat.
- **Langganan Google Play:** menghapus akun di Rizqflow **tidak membatalkan** langganan Sync di Google Play. Kamu harus membatalkannya sendiri lewat Google Play. `[PERLU REVIEW HUKUM dan PERLU KONFIRMASI: apakah aplikasi sudah mengarahkan pengguna ke pembatalan di Google Play saat hapus akun]`
- **Anggota ruang keluarga:** data yang sudah mereka terima tetap ada di perangkat mereka. Penghapusan akunmu tidak menghapus salinan di perangkat anggota lain.
- **Cadangan server:** salinan di cadangan harian ikut terhapus sesuai jadwal retensi cadangan (lihat bagian 9).
- Penghapusan akun server **butuh konfirmasi tambahan**: jika kunci aplikasi aktif, kamu diminta PIN; jika tidak, kamu mengetik kata "HAPUS".
- Setelah sesi berakhir, kamu diminta masuk ulang dulu sebelum bisa menghapus akun.

Jika kamu ingin menghapus data dari ponsel, hapus datanya lewat aplikasi atau copot aplikasinya.

---

## 11. Keamanan

- Data di ponsel dilindungi kunci di Android Keystore dan dapat dikunci dengan PIN atau biometrik.
- Data sinkronisasi dienkripsi ujung ke ujung dengan pustaka Google Tink (AES-GCM dan enkripsi hibrida). Kami tidak menulis kriptografi sendiri.
- Komunikasi dengan server memakai HTTPS. Token masuk kedaluwarsa dan dapat dicabut.
- Sesi masuk di server memakai token yang kedaluwarsa dan dicabut saat keluar atau saat kata sandi diubah.
- `[PERLU VERIFIKASI KODE]` Sebelum rilis, pastikan pengujian kriptografi di Keystore asli sudah lulus (roadmap Tahap 13). Klaim keamanan di sini tidak boleh lebih kuat daripada hasil pengujian itu.

---

## 12. Anak-anak

Rizqflow tidak ditujukan untuk anak di bawah umur `[PERLU REVIEW HUKUM dan BUTUH KEPUTUSAN PEMILIK: usia minimum pengguna, dan apakah target audiens di Play Console diisi "dewasa"]`.

---

## 13. Perubahan kebijakan

Kami dapat memperbarui kebijakan ini, misalnya saat fitur baru seperti broadcast promo, pemindaian struk, atau sinkronisasi entitas lain ditambahkan. Perubahan yang berdampak pada data pribadimu akan diberitahukan di dalam aplikasi sebelum berlaku. Tanggal pembaruan terakhir ditulis di bagian atas dokumen ini.

---

## 14. Daftar hal yang harus selesai sebelum publikasi

Ringkasan dari penanda di atas:

**[PERLU REVIEW HUKUM]**
1. Dasar pemrosesan per jenis data menurut UU PDP (bagian 7).
2. Apakah data keuangan pribadi termasuk data pribadi spesifik, dan apakah Sync membutuhkan persetujuan eksplisit (bagian 7).
3. Pendekatan implisit untuk email promo (bagian 6).
4. Mekanisme persetujuan dan dasar hukum Firebase Analytics (bagian 5.2).
5. Kewajiban pejabat pelindungan data pribadi (bagian 2).
6. Batas waktu respons permintaan hak pengguna (bagian 10).
7. Klaim "server tidak bisa membaca" dan kesesuaiannya dengan pengujian nyata (bagian 4).
8. Penghapusan akun dan langganan Google Play (bagian 10.1).
9. Usia minimum pengguna (bagian 12).

**[BUTUH KEPUTUSAN PEMILIK]**
1. Nama pengembang dan alamat email kontak (bagian 2).
2. Mekanisme persetujuan untuk Analytics dan apakah opt-out tersedia di aplikasi (bagian 5.2).
3. Lama retensi cadangan server (bagian 9).
4. Cakupan pemulihan kunci ruang keluarga untuk rilis 1 (bagian 4).
5. Usia minimum dan target audiens di Play Console (bagian 12).

**[BUTUH KEPUTUSAN PEMILIK PASCA-RILIS]**
1. Mekanisme persetujuan untuk email promo, termasuk apakah opt-in eksplisit diterapkan (bagian 6).
2. Tautan berhenti langganan di email promo (bagian 6).

**[PERLU VERIFIKASI KODE]**
1. Daftar kolom metadata sinkronisasi dan push token sama dengan yang tertulis (bagian 3.4 dan 3.3).
2. Scope Gmail tidak diminta di rilis 1 (bagian 3.7).
3. Daftar event dan properti Firebase Analytics tidak berisi data keuangan (bagian 5.2).
4. Entitas yang benar-benar tersinkron saat rilis (bagian 4).
5. Hapus akun mengarahkan pengguna ke pembatalan Google Play, atau belum (bagian 10.1).
6. Konfigurasi log VPS dan lama penyimpanannya (bagian 9).
7. Kata sandi akun web di-hash (bagian 3.2).

---

*Draf ini disusun dari dokumen repo Rizqflow per 2 November 2026. Bukan nasihat hukum. Harus ditinjau oleh pengacara sebelum dipublikasikan.*
