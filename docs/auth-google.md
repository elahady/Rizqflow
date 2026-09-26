# Rizqflow — Masuk: akun lokal, Google, dan Gmail

Ditulis 2026-09-21. Berkas ini menjelaskan apa yang sudah dibangun untuk halaman masuk, apa yang **harus Anda siapkan** di Google Cloud supaya tombol Google bekerja, dan risiko yang perlu diketahui sebelum rilis.

## Keputusan

Pemilik meminta alur: **splash, lalu halaman masuk dengan Google connect dan Gmail; yang sudah punya riwayat masuk langsung ke menu utama** (2026-09-21). Sesudahnya pemilik menambah **masuk dengan nama pengguna dan sandi serta halaman Daftar** (2026-09-21), untuk yang tidak mau memakai Google. Ini **mengubah keputusan sebelumnya** "tanpa akun" ([konsep.md](konsep.md)). Yang tetap berlaku: data keuangan disimpan di ponsel dan tidak dikirim ke server; tidak ada backend Rizqflow.

## Yang sudah dibangun

| Bagian | Isi |
|---|---|
| Splash | Splash sistem (ikon aplikasi), lalu layar splash Compose (wordmark besar dan tiga titik berdenyut, minimal 1,5 detik) sampai riwayat masuk terbaca, jadi tidak ada kedipan halaman masuk |
| S30 Masuk | Logo tiga ruang, tagline, tiga jaminan, tombol Google **berupa simbol G saja** (nama "Lanjutkan dengan Google" untuk pembaca layar), tombol **Hubungkan Gmail** (opsional), pesan lembut bila gagal |
| Alur | Tanpa riwayat: halaman masuk. Ada riwayat: langsung menu utama. **Keluar** di tab Lainnya menghapus riwayat |
| Akun lokal (S30, S31) | Kolom nama pengguna dan sandi di S30, halaman Daftar S31. Daftar membuat akun di ponsel ini lalu langsung masuk. Lihat bagian "Akun lokal" di bawah |
| Riwayat masuk | Profil singkat di SharedPreferences privat (pengenal, email, nama, penanda Gmail). **Tidak ada token atau sandi yang disimpan** |
| Gmail | Izin `gmail.readonly` diminta lewat AuthorizationClient, bisa sekalian saat masuk atau belakangan dari tab Lainnya. **Aplikasi belum membaca email apa pun**; fitur ini baru menyiapkan izinnya |
| Masuk uji | Tombol khusus **build debug** untuk melewati Google saat pengembangan. Tidak ada di build rilis |
| Tes | `AuthController` 28 tes; domain: sesi dan `StartRouter` 8 tes, `CredentialPolicy` 13, `Pbkdf2PasswordHasher` 10, `LocalAccountService` 14 |

Kode: `app/src/main/kotlin/.../auth/` (logika, penyimpanan, Google) dan `ui/login/` (layar); logika murni (`AuthSession`, `StartRouter`) di `:domain`.

## Akun lokal: nama pengguna dan sandi

Tanpa server, jadi ini **pintu masuk lokal**, bukan sistem akun sungguhan. Keputusan rancangan dan batasannya:

| Hal | Keputusan |
|---|---|
| Penyimpanan | SharedPreferences privat (`accounts`), terpisah dari riwayat masuk (`session`). `allowBackup` mati, jadi tidak ikut cadangan otomatis dan tidak berpindah ke ponsel lain |
| Sandi | **Tidak pernah disimpan.** Yang disimpan hash PBKDF2-HMAC-SHA256, 600.000 putaran (anjuran OWASP), garam acak 16 byte per akun, format `pbkdf2-sha256$putaran$garam$hash`. Jumlah putaran ikut tersimpan sehingga bisa dinaikkan tanpa merusak akun lama |
| Unicode | Sandi dinormalkan NFKC sebelum di-hash, supaya sandi yang sama dari papan ketik berbeda tetap cocok |
| Nama pengguna | 3 sampai 32 karakter, a-z, angka, titik, garis bawah, strip; diawali huruf atau angka; tidak membedakan huruf besar-kecil |
| Sandi (aturan) | Minimal 8, maksimal 128 karakter, tidak boleh sama dengan nama pengguna. Tanpa aturan wajib simbol; panjang lebih berguna |
| Salah sandi | Nama pengguna tak dikenal dan sandi salah **memberi pesan yang sama**, dan yang tak dikenal tetap menjalankan satu pengecekan hash supaya waktu balasnya tidak membocorkan |
| Layar | Sandi tidak masuk penyimpanan state instance (hilang bila layar diputar atau proses dimatikan) |
| Keluar | Menghapus riwayat masuk saja; akun tetap ada dan bisa dipakai masuk lagi |
| Lupa sandi | **Tidak bisa dipulihkan.** Tidak ada server untuk mengirim tautan reset. Peringatan tampil di S31 sebelum tombol Daftar |

Batas yang perlu dipahami:

1. **Ini bukan enkripsi data.** Nama pengguna dan sandi hanya menjadi pintu masuk aplikasi. Isi database keuangan tidak terenkripsi oleh sandi ini. Pengunci sebenarnya (PIN, biometrik, enkripsi database) adalah pekerjaan Tahap 6 (S17, S18).
2. **Orang yang bisa membaca berkas data aplikasi** (misalnya ponsel yang di-root) bisa mengambil hash dan mencoba menebak sandi secara luring; PBKDF2 memperlambat itu tetapi tidak mencegahnya. Sandi yang panjang membantu.
3. **Tanpa pembatasan percobaan.** Belum ada penundaan setelah beberapa kali salah sandi. Orang yang memegang ponsel bisa menebak berulang kali; hash yang lambat (sekitar sepersekian detik per tebakan) adalah satu-satunya rem. Usulan: jeda bertahap setelah 5 kali salah.
4. **Hanya di ponsel ini.** Akun tidak ikut pindah ponsel. Pindah ponsel memakai cadangan terenkripsi (S19) atau masuk dengan Google. Bila Sync (fase 2) dibuat, akun lokal perlu dihubungkan ke akun sungguhan lewat backend yang memverifikasi.
5. **Satu ponsel bisa punya beberapa akun lokal.** Data keuangan belum dipisah per akun; itu perlu diputuskan sebelum Tahap 3 memakai akun sebagai pemilik data (diputuskan 2026-09-21: satu database per akun, lihat [model-data.md](model-data.md)).

## Status penyiapan (2026-09-21)

Proyek Google Cloud **rizqflow** (`rizqflow-509308`) sudah dibuat. Klien Web (`232915442214-5fusjgtntn65tdg34km2ppra9ee6k1ka`) sudah ditempel ke `google_auth.xml`; klien Android (`232915442214-rn110tas126b9e7m5c6s7pq3lb1ojml0`, package `com.roziqrizal.rizqflow`, SHA-1 debug PC ini) terdaftar dan tidak dipakai di kode. **Masuk dengan Google sudah diuji berhasil di emulator** (pilih akun, lalu langsung ke menu utama; Lainnya menampilkan nama dan email, dan riwayat masuk tersimpan tanpa token). Gmail (Data Access dan Gmail API) belum disiapkan dan belum diuji.

**Catatan 2026-09-26:** kunci debug di PC ini sekarang ber-SHA-1 `F2:C2:22:E0:...:4F:3F:7B`, berbeda dari SHA-1 `8A:63:D7:...` yang didaftarkan 2026-09-21 (kunci debug lama). APK debug yang dibangun sekarang ditandatangani kunci baru itu, jadi klien Android di Google Cloud perlu ditambah untuk SHA-1 baru (satu klien Android hanya menampung satu SHA-1, jadi buat klien Android kedua dengan package yang sama). Google Play Console tidak dibutuhkan untuk masuk dengan Google di build debug; itu baru relevan untuk SHA-1 kunci rilis.

## Yang harus Anda siapkan di Google Cloud

Tombol Google menampilkan "belum disiapkan" sampai ini selesai. Saya tidak bisa membuatnya untuk Anda: klien OAuth Web dan Android hanya bisa dibuat lewat console dengan akun Google Anda (tidak ada perintah gcloud atau API untuk itu). Yang sudah saya siapkan: kodenya, nilai yang perlu Anda salin (di bawah), pemeriksa bentuk ID, dan catatan kegagalan di Logcat.

**Nilai yang akan diminta Google** (salin apa adanya):

| Isian | Nilai |
|---|---|
| Package name | `com.roziqrizal.rizqflow` |
| SHA-1 debug (PC ini, dicek 2026-09-26 lewat `signingReport`) | `F2:C2:22:E0:BE:26:B2:F6:E5:FC:47:68:79:95:6C:83:77:4F:3F:7B` |
| Scope Gmail (opsional) | `https://www.googleapis.com/auth/gmail.readonly` |
| Nama aplikasi di consent screen | Rizqflow |

**Langkah** (kira-kira 10 menit):

1. Buka [console.cloud.google.com/projectcreate](https://console.cloud.google.com/projectcreate), buat proyek (misalnya `rizqflow`).
2. **Google Auth Platform** (dulu "OAuth consent screen"): tipe **External**, isi nama aplikasi dan email dukungan, lalu di **Audience** tambahkan akun Google Anda sebagai **Test user**. Biarkan status **Testing**.
3. **Clients, Create client**, dua kali:
   - Tipe **Web application** (nama bebas). Salin **Client ID**-nya, lalu tempel ke `app/src/main/res/values/google_auth.xml` pada `google_web_client_id`. Bentuknya `123456789012-xxxxxxxx.apps.googleusercontent.com`; aplikasi memeriksa bentuk ini dan menolak yang salah tempel.
   - Tipe **Android**: isi package dan SHA-1 dari tabel di atas. ID Android ini **tidak perlu ditempel ke mana pun**; cukup terdaftar supaya Google mengenali aplikasi. Nanti tambahkan SHA-1 kunci rilis (Play Console, App integrity, App signing).
4. Untuk Gmail: **Library, Gmail API, Enable**, lalu di **Data access** tambahkan scope `gmail.readonly`.
5. Uji di emulator: image `google_apis` sudah memuat layanan Google, tetapi emulator belum punya akun. Tambahkan akun Google Anda di Pengaturan emulator (Passwords and accounts) sebelum menekan tombol G. Saya tidak memasukkan kata sandi Google Anda; bagian ini Anda yang lakukan.

Setelah client ID ditempel: `./gradlew :app:assembleDebug`, pasang, tekan tombol G. Bila layar pilih akun Google muncul, konfigurasi dasarnya benar.

**Cek SHA-1 di PC lain** (tidak perlu keytool): `./gradlew :app:signingReport`, ambil baris `SHA1` pada `Variant: debug`. Tiap PC punya kunci debug sendiri, jadi tiap PC pengembang menambah SHA-1-nya ke klien Android.

**Bila gagal**, lihat Logcat dengan filter tag `RizqflowGoogle`. Pengguna hanya melihat pesan lembut; penyebabnya ada di log:

| Gejala | Penyebab yang paling mungkin |
|---|---|
| Pesan "belum disiapkan" walau sudah ditempel | ID bukan berbentuk client ID Google (log: "bukan berbentuk client ID Google"), atau aplikasi belum dibangun ulang setelah mengubah `google_auth.xml` |
| Log "tidak ada akun Google di perangkat" | Emulator atau ponsel belum punya akun Google; tambahkan di Pengaturan |
| Log `GetCredentialException` dengan pesan "developer console is not set up correctly" (kode 10 atau 28444) | Klien **Android** belum ada, atau package/SHA-1 tidak cocok dengan yang dipakai membangun. Cek dengan `signingReport` |
| Layar Google menolak "Access blocked" atau "app not verified" | Proyek berstatus Testing dan akun belum jadi **Test user** |
| Izin Gmail diminta tetapi ditolak | Scope `gmail.readonly` belum ditambahkan di Data access, atau Gmail API belum di-enable |
| Bekerja di debug, gagal di rilis | SHA-1 kunci rilis (App signing Play Console) belum didaftarkan |

Client ID adalah pengenal publik, bukan rahasia, jadi aman masuk ke git.

## Risiko yang perlu diketahui sebelum rilis

1. **Izin baca Gmail adalah cakupan "restricted" di Google.** Selama proyek berstatus Testing, hanya sampai 100 pengguna uji yang terdaftar, dan izinnya kedaluwarsa tiap 7 hari. Untuk rilis publik, Google mewajibkan **verifikasi OAuth dan penilaian keamanan tahunan** oleh penilai pihak ketiga, yang butuh waktu dan biaya. Ini bisa menunda rilis pertama. Saran: rilis pertama **tanpa** tombol Gmail (sudah terpisah dari tombol Google), Gmail menyusul setelah verifikasi.
2. **Play Store**: Data safety dan kebijakan privasi harus menyebut akun Google dan, bila Gmail diaktifkan, akses email. Kebijakan Play tentang data pengguna sensitif berlaku ketat untuk aplikasi finansial.
3. **Identitas ini hanya lokal.** Tidak ada server Rizqflow, jadi ID token Google tidak diverifikasi siapa pun dan riwayat masuk hanyalah penanda di ponsel. Jangan dipakai untuk mengotorisasi Sync (fase 2) tanpa backend yang memverifikasi token.
4. **Butuh internet dan layanan Google** untuk masuk pertama kali. Ponsel tanpa Google Play services (misalnya sebagian perangkat Huawei) tidak bisa masuk.
5. **Prinsip produk**: janji "Datamu tetap di ponselmu" di halaman masuk tetap benar selama tidak ada sync. Ubah teksnya bila Sync dibuat.
6. **Tombol Google** memakai logo G empat warna resmi dalam varian ikon (latar putih, garis tepi tipis; #131314 di mode gelap). Warna dan bentuk logo tidak boleh diubah. Periksa ulang pedoman merek Google sebelum rilis.

## Yang belum dibangun

- Membaca email (parser email bank, draf transaksi dari email), dan cara mencabut izin Gmail dari dalam aplikasi.
- Hapus akun dan data saat keluar (sekarang Keluar hanya menghapus riwayat masuk; akun lokal dan data keuangan tidak disentuh).
- Ganti sandi, hapus akun lokal, jeda setelah salah sandi berulang, dan memisahkan data keuangan per akun.
- Menyambungkan akun ke Sync (fase 2).
