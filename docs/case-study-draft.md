# Rizqflow: aplikasi yang bertanya "apakah semua hak sudah terpenuhi?"

> **Status dokumen:** draf untuk roziqrizal.com (Tahap 15 roadmap.md). Belum final. Rizqflow belum dirilis publik dan belum ada di Google Play. Setiap klaim di bawah bisa dicek ke `docs/konsep.md`, `docs/server-sync.md`, dan `docs/roadmap.md`.

## Masalah

Hampir semua aplikasi keuangan pribadi bertanya satu hal: uangmu habis ke mana? Pertanyaan itu berguna, tetapi sering datang terlambat. Di akhir bulan, catatan pengeluaran memang lengkap, tetapi tidak menjawab apakah tanggung jawab yang seharusnya didanai, seperti tabungan darurat, kebutuhan keluarga, atau kewajiban berbagi, benar-benar sudah tertunaikan.

Masalah ini dimulai dari kebiasaan pemilik proyek sendiri. Ia mencatat keuangannya di Notion (jurnal Ruang Finansial), dan dari situ terlihat pola yang berulang: pengeluaran kecil sering terlewat, baik yang tunai, QRIS, maupun e-wallet. Aplikasi pencatat biasa tidak menolong, karena yang rusak bukan kemampuan menghitung melainkan kebiasaan mencatat. Dari pengamatan itu muncul pertanyaan produk yang berbeda: bukan "uangmu habis ke mana", melainkan **"apakah setiap hak sudah ditunaikan?"**

Rizqflow dibangun untuk menjawab pertanyaan kedua. Rezeki yang masuk dialirkan ke ruang-ruang (Memberi, Diri, Keluarga, dan peran lain) lewat aturan alokasi. Tiap ruang punya arti "terpenuhi" sendiri: Memberi terpenuhi bila dana tersalurkan mencapai target, Diri bila dana benar-benar masuk ke akun tujuan, dan Keluarga bila kebutuhan tertutup tanpa melewati jatah.

<!-- [BUTUH SCREENSHOT/VIDEO DEMO NYATA]: tangkapan layar Denah dari perangkat sungguhan, dengan beberapa ruang berstatus berbeda. Jangan memakai mockup. -->

## Pendekatan

Keputusan desain yang paling menentukan diambil di awal dan dicatat di `docs/konsep.md`:

**1. Offline-first.** Data hidup di ponsel. Aplikasi berfungsi penuh tanpa jaringan, dan backup lokal terenkripsi selalu gratis. Server ikut rilis pertama, tetapi hanya sebagai salinan terenkripsi untuk sinkron, bukan sumber kebenaran. Alasannya sederhana: data keuangan yang tidak perlu dikirim ke mana pun tidak bisa bocor dari sana.

**2. Uang sebagai bilangan bulat, tanpa floating point.** Semua nominal disimpan dalam satuan terkecil (rupiah tanpa desimal) lewat value object `Money`. Persentase disimpan sebagai basis point, sehingga 2,5 persen tetap eksak. Pilihan ini kecil, tetapi menghapus satu kelas bug yang sulit dicari: selisih satu rupiah yang muncul di akhir bulan.

**3. Enkripsi ujung ke ujung untuk privasi finansial.** Data dienkripsi di perangkat sebelum dikirim, dengan Google Tink (AES-GCM). Kunci dibuat acak di perangkat dan disimpan di Android Keystore. Kode pemulihan 24 kata (skema BIP-39) memungkinkan pemulihan di perangkat baru, dan server hanya menyimpan salinan kunci yang sudah terbungkus kode itu. Setiap ruang keluarga yang dibagi memakai kunci sendiri, sehingga ruang pribadi tidak ikut terbuka.

**4. Freemium dengan Pro sekali bayar dan Sync berlangganan.** Keamanan, ekspor data, dan dasar zakat tidak pernah dikunci, karena itu hak dasar pengguna dan membangun kepercayaan. Yang berbayar adalah skala dan kenyamanan. Semua penguncian fitur melewati satu lapisan entitlement, sehingga model harga bisa berubah tanpa membongkar kode.

**5. Inti universal, modul Islami opsional.** Rule engine alokasi tidak Islami. Zakat dan haul adalah modul yang bisa dinyalakan atau dimatikan. Penamaan internal kode netral (`allocation`, `room`, `giving-module`).

## Sorotan teknis

### 1. Rule engine alokasi dengan basis point dan sisa terbesar

Setiap pemasukan dibagi ke ruang-ruang menurut persentase. Dalam bilangan bulat, pembagian hampir selalu menyisakan rupiah. Aturan yang dipilih adalah metode sisa terbesar: tiap ruang menerima bagian yang dibulatkan ke bawah, lalu sisa rupiah dibagikan satu per satu ke ruang dengan pecahan terbesar, dan urutan prioritas ruang memutus seri.

Contoh: Rp 1.234.567 dibagi 10, 50, dan 40 persen. Pembulatan ke bawah menghasilkan 123.456, 617.283, dan 493.826, sehingga selisih 2 rupiah. Dua rupiah itu diberikan ke ruang dengan pecahan terbesar (.8 dan .7), hasil akhirnya 123.457, 617.283, dan 493.827. Jumlahnya selalu persis sama dengan nominal.

Rule engine dibuat sebagai strategi yang bisa ditukar: mode persentase, dan mode lanjutan (waterfall dengan prioritas dan batas atas bulanan, sisa mengalir otomatis ke ruang berikutnya). Keduanya diuji dengan golden test yang tidak memakai mock, sehingga hasil hitungan bisa diperiksa dengan tangan.

<!-- [BUTUH SCREENSHOT/VIDEO DEMO NYATA]: layar pratinjau alokasi (S07) dari perangkat sungguhan, menampilkan pembagian satu pemasukan ke tiga ruang. -->

### 2. Sync append-only dengan last-write-wins, di atas data terenkripsi

Sinkron antar-perangkat dirancang dengan batasan yang jelas: server tidak boleh membaca isi data, tetapi tetap harus bisa memutuskan versi mana yang berlaku.

Model yang dipilih adalah log tambah-saja per rekaman. Setiap perubahan (tambah, ubah, hapus) dikirim sebagai baris baru berisi payload terenkripsi, ditambah label routing yang buram bagi server: id ruang, jenis rekaman, id rekaman, id perangkat, dan waktu perubahan. Tidak ada `UPDATE` atau `DELETE` fisik di server. Hapus adalah baris baru dengan tanda hapus. Saat membaca, aplikasi memilih baris dengan waktu paling baru per rekaman (last-write-wins), setelah mendekripsi, bukan di server.

Pilihan ini sengaja sederhana. Transaksi keuangan jarang diubah setelah dicatat, sehingga konflik sungguhan (dua perangkat mengubah rekaman yang sama dalam jendela waktu yang sama) jarang terjadi. Dengan model ini server tidak perlu tahu isi data untuk bekerja, dan enkripsi ujung ke ujung tidak terganggu.

Ruang keluarga memperumit model ini. Anggota ruang bersama melihat transaksi ruang itu beserta nama akun sumbernya, tetapi tidak melihat saldo akun itu atau transaksi lain yang memakai akun yang sama di ruang lain. Pembatasan ini ada di sisi tampilan dan protokol sinkron, bukan dengan memisahkan akun per ruang.

<!-- [BUTUH SCREENSHOT/VIDEO DEMO NYATA]: layar Ruang keluarga dengan undangan QR dari dua perangkat fisik, serta contoh transaksi yang tersinkron. Video pendek yang memperlihatkan catatan di satu perangkat muncul di perangkat kedua. -->

### 3. Kalkulator zakat dan haul dengan kalender Hijriyah

Modul zakat menghitung nisab dari harga emas per gram (default sementara 85 gram), tarif 2,5 persen, dan haul satu tahun Hijriyah. Kalender Hijriyah memakai `HijrahChronology` bawaan Java (Umm al-Qura) di balik antarmuka `HijriCalendar`, sehingga implementasi lain, seperti hisab atau penetapan Kemenag, bisa ditukar tanpa mengubah logika inti. Karena kalender ini bisa berbeda satu hari dari penetapan resmi, tanggal Hijriyah di aplikasi ditampilkan sebagai perkiraan.

Hal yang paling sering salah dalam kalkulator zakat adalah haul yang terputus. Ada dua kebijakan yang bisa diganti: reset dari nol, atau hanya diperiksa di awal dan akhir haul. Bawaan sementara adalah reset dari nol, dan asumsi ini sengaja ditandai sebagai **menunggu verifikasi rujukan kitab**. Aplikasi ini bantuan hitung, bukan fatwa, dan disclaimer itu tampil di layar.

<!-- [BUTUH SCREENSHOT/VIDEO DEMO NYATA]: kartu zakat (S14) dari perangkat sungguhan, dengan status haul dan rincian perhitungan. -->

## Status

Per 2 November 2026:

- **Belum dirilis.** Rizqflow belum ada di Google Play. Pembuatan aplikasi di Play Console, uji pembelian dengan penguji lisensi, kebijakan privasi, dan data safety masih menjadi pekerjaan Tahap 15.
- **Terbukti lewat tes otomatis:** logika inti (alokasi, nisab, haul, sinkron, kunci), dengan tes domain dan data yang berjalan di JVM tanpa perangkat.
- **Belum terbukti di perangkat:** sinkron dua perangkat fisik, ruang keluarga, pemulihan kunci di Android Keystore asli, dan migrasi database di perangkat lama.
- **Server:** API akun, sinkron, dan verifikasi pembelian sudah ada dan diuji dengan database uji dan klien palsu. Belum dijalankan di MySQL produksi dan belum terhubung ke Google Play sungguhan.
- **Web:** dashboard sudah berjalan sebagai scaffold dengan data contoh. Sinkron data asli lewat enkripsi di peramban masuk fase berikutnya dan belum dikerjakan. Tampilan web yang publik belum dipromosikan sebelum itu.

Ini bukan hasil akhir. Keputusan tentang model harga, asumsi fikih zakat, dan beberapa aturan sync masih terbuka, dan sebagian sudah berubah selama pengerjaan: misalnya Sync sempat direncanakan untuk versi 1.1, lalu dikembalikan ke rilis pertama.

<!-- [BUTUH SCREENSHOT/VIDEO DEMO NYATA]: tangkapan layar yang jujur tentang keadaan saat ini (mis. mode demo di perangkat sungguhan), bukan mockup desain. Video demo satu alur utama: catat pengeluaran, lihat pratinjau alokasi, lalu buka Denah. -->

## Yang akan dikerjakan berikutnya

- Menyelesaikan pengujian di perangkat dan di Play Console sebelum rilis pertama.
- Menentukan harga final Pro dan Sync lewat uji minat.
- Verifikasi rujukan kitab untuk asumsi zakat dan kebijakan haul.
- Sinkron data asli di web dengan kunci yang diturunkan di peramban.
