# Rizqflow — Server, Sync, dan enkripsi ujung ke ujung

Keputusan pemilik 2026-09-24. Ringkasan alasannya ada di [konsep.md](konsep.md) dan tahapnya di [roadmap.md](roadmap.md) (Tahap 9, 12, dan 13). Dokumen ini adalah rujukan rancangan; hal yang belum diputuskan ditandai jelas.

## Ringkasan keputusan

- Server ikut **rilis pertama** dengan cakupan penuh: akun, verifikasi pembelian, metrik, sync antar-perangkat, ruang keluarga, notifikasi anggota.
- ~~Server berjalan di VPS Al-Kaukaba yang sudah ada~~ **Diganti (keputusan pemilik, dicatat di repo build-server):** server berjalan di **VPS IDCloudHost `103.54.170.198`** bersama roziqrizal.com dan habitflow, sebagai container Docker (pola habitflow), bukan di VPS Al-Kaukaba. Web Rizqflow sudah ter-deploy di sana 2026-10-18 (`server/DEPLOY.md`). Pemisahan di bawah tetap berlaku, dengan padanan Docker: container dan volume sendiri, database dan pengguna sendiri di `shared-mysql`, `.env.production` sendiri. Pindah ke VPS khusus tetap dipermudah lewat alamat API yang bisa dikonfigurasi.
- **Server tidak boleh bisa membaca data keuangan.** Data disimpan terenkripsi ujung ke ujung; kunci ada di perangkat.
- **Data di ponsel adalah sumber kebenaran**; server hanya menyimpan salinan terenkripsi. Aplikasi tetap berfungsi penuh offline.
- **Sync dijual sebagai langganan sejak rilis** (harga di [monetisasi.md](monetisasi.md)).

## Infrastruktur (Tahap 12)

Dasar: Al-Kaukaba (`alkaukabaweb`) dan roziqrizalcom sama-sama Laravel 12, PHP 8.2; VPS memakai Nginx, PHP-FPM 8.2, MySQL, dan SSL Certbot. Paket `laravel/sanctum` dan `google/apiclient` sudah dipakai di Al-Kaukaba dan cocok untuk verifikasi ID token Google dan Google Play Developer API.

Pemisahan yang wajib walau satu VPS, karena ini data keuangan:

| Hal | Aturan |
|---|---|
| Aplikasi | Aplikasi Laravel Rizqflow sendiri, bukan bagian dari repo Al-Kaukaba atau roziqrizalcom |
| Domain | Subdomain API sendiri (misalnya `api.rizqflow.app`), SSL sendiri |
| Database | Database dan pengguna MySQL sendiri; jangan memakai `laravel_api` milik Al-Kaukaba |
| PHP-FPM | Pool dan pengguna sistem terpisah per aplikasi |
| Nginx | Server block sendiri; **konfigurasi disimpan di repo** (di VPS hanya ada di `/etc/nginx/`, tidak ikut git) |
| Deploy | Skrip deploy di repo (bukan `git pull` manual tanpa catatan); backup database dulu, migrate, cache konfigurasi dan rute, uji endpoint |
| Rahasia | `.env` terpisah; kunci dan token tidak dibagi antar aplikasi |
| Backup | Backup database Rizqflow terjadwal, dan restore-nya diuji |
| Token | Kedaluwarsa dan bisa dicabut. **Jangan meniru token permanen Al-Kaukaba**. Diimplementasikan 2026-10-25, lihat bagian "Token API Android (Tahap 12)" di bawah |

Catatan dari CLAUDE.md Al-Kaukaba yang berlaku juga di sini: setiap habis `git pull` yang mengubah rute, jalankan `route:cache` ulang; dan hindari rute yang berakhiran `.php` supaya tidak terkena masalah `location ~ \.php$` di Nginx.

**Risiko yang diterima:** RAM VPS kemungkinan kecil (VS Code Server pernah dicopot demi hemat RAM) dan satu VPS yang bermasalah menjatuhkan semua situs di dalamnya. Spesifikasi (`free -m`, `df -h`, swap) dicek pemilik sebelum apa pun dipasang.

**Alamat API di aplikasi bisa dikonfigurasi** (bukan ditanam), supaya pindah ke VPS khusus tidak membutuhkan rilis ulang yang rumit. Diimplementasikan 2026-10-25 sebagai konfigurasi saat build (bagian "Token API Android (Tahap 12)" di bawah); belum diuji di perangkat atau terhadap server hidup.

## Model kunci (Tahap 13)

Tujuan: server tidak bisa membaca data, pasangan bisa berbagi ruang Keluarga, dan pemulihan tetap ada.

| Bagian | Keputusan |
|---|---|
| Pustaka | Google Tink (AES-GCM dan enkripsi hibrida). Tidak menulis kripto sendiri |
| Kunci akun | Dibuat acak di perangkat, disimpan dalam Android Keystore |
| Pemulihan | **Kode pemulihan wajib** saat Sync diaktifkan (misalnya 24 kata, ditampilkan sekali dan wajib dicatat pengguna). Salinan kunci akun terbungkus kode itu disimpan di server, yang tidak bisa membukanya. **Diimplementasikan 2026-10-25 (hanya terbukti lewat tes otomatis), dengan cakupan yang lebih sempit dari rancangan:** yang dibungkus baru **kunci akun**; **kunci ruang keluarga belum ikut dibungkus** (format versi 1). Kunci akun saat ini juga belum mengenkripsi data apa pun (sync yang ada hanya transaksi pengeluaran ruang keluarga, dengan kunci ruang), jadi memulihkan kunci di perangkat baru belum mengembalikan data, dan ruang keluarga yang dibagi harus diundang ulang lewat QR. Memasukkan kunci ruang sebagai versi 2 atau menerima undang ulang adalah keputusan pemilik yang belum diambil. Lihat bagian "Salinan kunci pemulihan (Tahap 13)" di bawah |
| Kunci ruang keluarga | Kunci acak terpisah per ruang yang dibagi, sehingga ruang pribadi tidak ikut terbuka |
| Mengundang pasangan | Bertemu langsung dan memindai QR untuk memverifikasi kunci, tidak bergantung pada server |
| Mengeluarkan anggota | Kunci ruang diganti untuk data berikutnya. Data lama yang sudah mereka miliki tetap bisa mereka baca dan ini dijelaskan ke pengguna |
| Notifikasi | Push dari server hanya berisi kabar umum ("ada catatan baru"); aplikasi mengambil dan mendekripsi sendiri, jadi nominal tidak bocor |
| Akun lintas ruang | **Keputusan pemilik 2026-10-19:** anggota ruang Keluarga melihat transaksi ruang itu beserta nama akun sumbernya, tapi **tidak** melihat saldo akun itu maupun transaksi lain yang memakai akun itu di ruang lain (Memberi, Diri, ruang Keluarga lain). Akun tetap satu per pengguna, dipakai lintas ruang seperti sekarang — pembatasannya di sisi tampilan/sync (hanya transaksi milik ruang yang dibagi yang ikut disinkronkan ke anggota, bukan riwayat penuh akunnya), bukan dengan memisahkan akun per ruang |

Yang tetap terlihat server: siapa yang sinkron, kapan, dan ukuran data. Ini diungkapkan di kebijakan privasi.

Data hilang total hanya bila semua perangkat hilang **dan** kode pemulihan hilang, karena ponsel adalah sumber kebenaran.

## Model konflik sinkronisasi (Tahap 9)

**Keputusan pemilik 2026-10-19:** log operasi tambah-saja (append-only) per rekaman, bukan baris yang ditimpa. Tiap perubahan (tambah, ubah, hapus) dari perangkat mana pun dikirim sebagai baris log baru berisi payload terenkripsi, `room_id`/`account_id` (untuk routing, bukan isinya — tetap buram bagi server), `record_type` dan `record_id` (pengenal rekaman di domain, supaya baris-baris untuk rekaman yang sama bisa dikenali tanpa membaca isinya), `device_id`, dan `changed_at`. Tidak ada `UPDATE`/`DELETE` fisik di server — penghapusan adalah baris baru dengan tanda hapus (`deleted`), bukan menghapus baris lama. Resolusi konflik: baris dengan `changed_at` paling baru per `record_id` yang menang (last-write-wins), histori lama tetap tersimpan untuk audit/debug — **dikerjakan di aplikasi setelah mendekripsi, bukan di server** (server tidak tahu mana yang "menang", cuma menyimpan dan meneruskan semua baris apa adanya). Alasan: transaksi keuangan di Rizqflow jarang diubah setelah dicatat (kebanyakan tambah baru), jadi konflik sungguhan (dua perangkat mengubah rekaman yang sama persis di jendela waktu yang sama) jarang terjadi; skema ini juga paling sederhana untuk dibangun lebih dulu, dan tetap kompatibel dengan enkripsi ujung ke ujung karena server tidak perlu membaca payload untuk memutuskan siapa menang.

**Diimplementasikan 2026-10-19** (`server/`, tabel `sync_log_entries`, model `SyncLogEntry`): kolom persis seperti rancangan di atas, plus `id` (auto-increment, dipakai sebagai kursor pull — bukan `changed_at`, supaya tidak kena selisih jam antar perangkat) dan `user_id` (pemilik Sync, `cascadeOnDelete` ikut hapus akun). `room_id` adalah `RoomId` domain Android, **bukan** FK ke tabel `rooms` milik web (beda ruang ID antara domain Android dan skema web Tahap 14) — dibiarkan buram, cuma label routing. `record_type` juga label bebas dari Android, tidak divalidasi di server supaya domain Android bisa berkembang tanpa migrasi server.

Dua endpoint di `Api\SyncController`, keduanya butuh `auth:sanctum` dan `Feature::EncryptedSync` (plan Sync, lewat `Entitlements` yang sama dengan Pro — lihat CLAUDE.md "satu lapisan entitlement"):
- `POST /api/sync/push` — terima banyak baris sekaligus (maks 500/batch), simpan apa adanya, balas id yang baru dibuat.
- `GET /api/sync/pull?after=&limit=` — baris milik pengguna itu dengan `id` lebih besar dari `after`, urut naik, dibatasi `limit` (bawaan 200, maks 500); balas `next_after` dan `has_more` untuk paginasi. **Pull dari `after=0` di perangkat baru = restore dari backup**, karena seluruh riwayat perubahan ada di log ini.

10 tes fitur baru (`SyncControllerTest`); 258 tes server hijau. Diverifikasi manual lewat curl ke server dev: push lalu pull mengembalikan baris yang sama. **Belum ada**: dipanggil dari aplikasi Android (klien HTTP belum ada sama sekali di Android), dan pembatasan akses berbasis keanggotaan ruang Keluarga untuk pull milik anggota lain (menunggu task "Ruang keluarga bersama" — untuk sekarang pull cuma menyaring per `user_id` pemilik akun, belum per ruang yang dibagi).

## Pembelian Pro dan Sync (Tahap 9)

**Keputusan pemilik 2026-10-24 (setuju usulan):** aplikasi mengirim token pembelian ke server setelah masuk; server memverifikasinya lewat Google Play Developer API lalu mengisi `users.plans`; pembelian lama dipulihkan otomatis saat pertama masuk; satu token hanya bisa diklaim satu akun Rizqflow (yang pertama mengklaim, sisanya ditolak dengan pesan lembut); tanpa checkout web di rilis 1. Berlaku untuk Pro dan untuk langganan Sync. Alasannya: Pro dan Sync dibeli lewat Google Play dan terikat ke akun Play di perangkat, bukan ke akun Rizqflow, sehingga web dan gerbang Sync di server tidak punya jalan lain untuk tahu seseorang sudah membeli.

**Diimplementasikan 2026-10-24** (server di `server/app/Play/`, Android di `PurchaseClaimCoordinator`). **Status: hanya terbukti lewat tes otomatis dengan klien Play palsu** (server 898 tes hijau, Android `:domain` 772, `:data` 149, `:app` 252). Tidak pernah menyentuh Google Play sungguhan, server hidup, atau perangkat; daftar yang masih asumsi ada di akhir bagian ini.

**Hak akses di ponsel tidak berubah.** Paket di ponsel tetap dibuka oleh Google Play lewat `PurchaseController` (Tahap 7). Pengiriman token ke server hanya laporan: `PurchaseClaimCoordinator` sengaja tidak memegang `PurchaseStore`, jadi jawaban server (apa pun, termasuk `claimed_by_other` atau gagal) tidak pernah membuka, mencabut, atau menahan paket di ponsel, dan jawaban `plans` dari server tidak dipakai menentukan paket di ponsel. Yang dipengaruhi hanya `users.plans` di server, yaitu apa yang dikenali web dan gerbang `Feature::EncryptedSync`/`SharedFamilyRoom`.

### Endpoint

| Endpoint | Autentikasi | Fungsi |
|---|---|---|
| `POST /api/purchases/claim` (`Api\PurchaseController@claim`) | `auth:sanctum`, throttle 10 per menit per akun | Aplikasi melaporkan token pembelian; server memverifikasi ke Play, mencatat, lalu menurunkan `users.plans` |
| `POST /api/play/notifications` (`Api\PlayNotificationController@receive`) | Tanpa Sanctum; JWT OIDC Google di header `Authorization`, throttle 120 per menit per IP | Endpoint push Pub/Sub untuk Real-time Developer Notifications (RTDN) |

**Klaim.** Body `{"purchases":[{"product_id":"rizqflow_pro","purchase_token":"..."}]}`, 1 sampai 10 item (`product_id` maksimum 100 karakter, token maksimum 4096 karakter). Produk dikenal: `rizqflow_pro` (produk sekali beli, INAPP, paket `pro`, diverifikasi lewat `purchases.productsv2.getproductpurchasev2`) dan `rizqflow_sync` (langganan, SUBS, paket `sync`, base plan `monthly` dan `yearly` sama-sama membuka Sync, diverifikasi lewat `purchases.subscriptionsv2.get`).
- 200: `{"plans":["pro","sync"],"results":[{"product_id":"...","status":"..."}]}`. `plans` adalah paket akun ini di server **setelah** klaim (urutan tetap pro lalu sync, hanya yang dimiliki); `results` sejajar dengan urutan masukan.
- Arti `status`: `active` sah sekarang dan dikaitkan ke akun ini; `pending` pembayaran tertunda, tidak membuka paket; `inactive` terverifikasi tetapi tidak berhak sekarang (kedaluwarsa, ditahan atau on hold, dijeda, dikembalikan dananya, dibatalkan dan masa aksesnya sudah lewat, produk tidak cocok, atau token yang belum dikenal Play); `claimed_by_other` token sudah diklaim akun Rizqflow lain (server tidak pernah menyebut akun siapa); `unknown_product` `product_id` tidak dikenal; `unverifiable` Play tidak terjangkau atau gagal sementara untuk item itu (klien mencoba lagi nanti, tidak ada yang berubah).
- 503 `{"message":"..."}` bila verifikasi Play belum dikonfigurasi (tanpa kredensial): tidak ada perubahan paket sama sekali, apa pun isi permintaan. 401 tanpa token API. 422 bila bentuk body salah. Seluruh `/api/*` menjawab galat sebagai JSON walau klien tidak mengirim `Accept: application/json` (klien Android tidak mengirimnya; tanpa itu 401 dan 422 berubah menjadi pengalihan 302 ke `/login`).

**RTDN.** Badan Pub/Sub `{"message":{"data":"<base64 JSON DeveloperNotification>","messageId":"..."},"subscription":"..."}`; `DeveloperNotification` memuat `packageName` dan salah satu dari `subscriptionNotification`, `oneTimeProductNotification`, `voidedPurchaseNotification`, atau `testNotification`.
- Autentikasi: header `Authorization: Bearer` berupa JWT OIDC yang ditandatangani Google; tanda tangan lewat JWKS Google, lalu audience diperiksa ketat terhadap `PLAY_RTDN_AUDIENCE`, dan bila `PLAY_RTDN_SERVICE_ACCOUNT_EMAIL` diisi, klaim email harus sama dan `email_verified`. Audience belum dikonfigurasi: 503 (endpoint tidak pernah menerima tanpa autentikasi). Token tidak sah: 401 tanpa alasan rinci.
- Pesan sah atau tidak relevan (badan rusak, paket aplikasi lain, token yang belum pernah diklaim, `testNotification`, Play menjawab tak dikenal): 204, supaya Pub/Sub tidak mengulang pesan yang tidak akan pernah bisa diproses. Galat sementara saat memverifikasi ke Play: 500, supaya Pub/Sub mengulang. Kredensial Play belum ada saat memproses: 503.
- **Isi notifikasi bukan status.** Hanya `purchaseToken` yang dipakai untuk tahu baris mana yang berubah; status selalu ditanya ulang ke Play. Karena itu notifikasi duplikat atau berurutan terbalik aman, dan `voidedPurchaseNotification` pun hanya memicu verifikasi ulang.

### Aturan satu token satu akun

- Yang pertama mengklaim menang. Batasan unik pada `play_purchases.token_hash` (sha256 token) adalah penjaga terakhir terhadap permintaan yang berbalapan; yang kalah mendapat `claimed_by_other`, dan bila pemenangnya akun yang sama klaimnya idempoten.
- Klaim ulang oleh akun yang sama menyegarkan status, bukan sekadar no-op, dan token yang sama dua kali dalam satu permintaan hanya menanyai Play sekali.
- Token yang sudah final saat pertama diklaim (kedaluwarsa, dikembalikan dananya) tidak dicatat. Akibatnya token final tidak memblokir akun lain.
- **Hapus akun** menghapus baris pembelian akun itu (`cascadeOnDelete`); token itu lalu bebas diklaim akun lain. Pesan `claimed_by_other` di aplikasi sengaja netral dan belum memberi petunjuk jalan keluar (butir di roadmap.md Tahap 9).

### Aturan penurunan `users.plans`

`PlanSynchronizer` adalah satu-satunya yang menulis `users.plans` untuk jalur pembelian; `users.plans` tidak pernah diturunkan dari kiriman klien atau isi notifikasi.

| Jenis | Berhak sekarang bila |
|---|---|
| Pro (sekali beli) | Dibeli dan belum dikembalikan dananya (sinyal refund: `purchaseState` `CANCELLED`, atau `PURCHASED` dengan `refundableQuantity` 0; **asumsi belum terbukti**, lihat di bawah) |
| Sync (langganan) | Status `ACTIVE`, `IN_GRACE_PERIOD`, atau `CANCELED` **dan** waktu sekarang masih sebelum `expiryTime`. Tidak berhak: `ON_HOLD`, `PAUSED`, `EXPIRED`, `PENDING` |

- Paket yang punya baris pembelian ada di `users.plans` bila **ada** baris yang berhak sekarang; begitu sebuah paket punya baris pembelian, ia mengikuti baris-barisnya. Paket yang tidak punya baris pembelian sama sekali dibiarkan apa adanya, jadi akun seed, uji, atau `tinker` tidak terhapus.
- Hanya paket yang barisnya **baru dijawab Play pada panggilan itu** yang dinilai ulang. Klaim yang hanya memuat Pro tidak menyentuh Sync; baris yang tidak ditanyakan ke Play atau yang Play-nya gagal tidak boleh mencabut apa pun hanya karena baris lain di akun yang sama berubah.
- **Kegagalan menghubungi Play tidak pernah memberi atau mencabut paket**: 401, 403, 429, 5xx, galat jaringan, dan jawaban yang tak terpahami menjadi `unverifiable` pada klaim, 500 pada notifikasi (Pub/Sub mengulang), dan dilewati lalu dicoba lagi pada `play:reconcile`. Kode 400 dan 404 untuk token yang sudah tercatat dibiarkan (bisa salah konfigurasi, jadi tidak mencabut); 410 untuk langganan berarti kedaluwarsa.
- Pengurangan paket hanya terjadi lewat tiga jalur yang semuanya bertanya ke Play: klaim ulang dari aplikasi, notifikasi RTDN, dan `play:reconcile`.

### `play:reconcile`

Perintah artisan (`--limit=200`) yang memverifikasi ulang baris yang belum final: langganan yang `expires_at`-nya kosong atau sudah atau hampir (sehari lagi) lewat, atau yang terakhir diverifikasi lebih dari 3 hari lalu; pembelian tertunda lebih dari sehari; dan produk sekali beli yang terakhir diverifikasi lebih dari 30 hari lalu (jaring pengaman bila notifikasi refund terlewat). Urutannya: yang belum pernah dicoba lebih dulu, lalu yang paling lama tidak dicoba (kolom `last_checked_at`), supaya baris yang macet tidak menutup antrean. Tanpa kredensial Play: peringatan dan kode keluar 0. Ada baris gagal: kode keluar 1, sisanya tetap diproses dan tidak ada yang dicabut. Dijadwalkan harian 03:30 di `routes/console.php`, **tetapi penjadwal Laravel baru jalan bila ada cron `schedule:run` di VPS dan saat ini belum ada** (Tahap 12, kerja pemilik). Tanpa cron dan tanpa RTDN yang terhubung, langganan Sync yang kedaluwarsa tetap menyimpan paketnya di server.

### Konfigurasi

Variabel `.env` (nama saja; semuanya opsional dan kosong berarti fitur mati, klaim dan notifikasi menjawab 503): `PLAY_PACKAGE_NAME` (bawaan `com.roziqrizal.rizqflow`), `PLAY_SERVICE_ACCOUNT_JSON` (jalur berkas JSON akun layanan, atau isinya dalam base64), `PLAY_RTDN_AUDIENCE`, dan `PLAY_RTDN_SERVICE_ACCOUNT_EMAIL`. Nilai asli hanya di `.env.production` di VPS. Langkah pemasangan (akun layanan Play API dengan izin lihat saja, topik Pub/Sub, langganan push ke `https://rizqflow-api.roziqrizal.com/api/play/notifications` dengan token OIDC) ada di `server/DEPLOY.md` bagian "Pembelian Google Play" dan **belum pernah dijalankan**; audience dan email akun layanan di Pub/Sub harus persis sama dengan nilai di `.env`.

### Konsekuensi privasi

- **Server kini menyimpan token pembelian.** Tabel `play_purchases` menyimpan, per akun: produk, paket, `token_hash` (sha256), token pembelian, status, `expires_at`, `auto_renewing`, `acknowledged`, `last_verified_at`, dan `last_checked_at`. Token disimpan **terenkripsi dengan `APP_KEY` di sisi server, bukan enkripsi ujung ke ujung**: server harus bisa membaca token utuh untuk verifikasi ulang ke Google, jadi ini sengaja berbeda dari data keuangan pengguna (transaksi, akun, saldo), yang tetap tidak terbaca server. Token disembunyikan dari serialisasi model dan tidak pernah masuk log, pesan galat, atau respons API (pengecualian Google tidak dicatat karena pesannya memuat URL yang berisi token).
- Server jadi tahu siapa membeli Pro atau Sync, kapan, dan statusnya (aktif, ditahan, kedaluwarsa, dikembalikan dananya). Ini menambah daftar "yang tetap terlihat server" di atas dan harus disebut di kebijakan privasi dan deklarasi Data Safety (Tahap 8; kategori yang tepat di formulir belum dicek).
- Mengganti `APP_KEY` membuat token tersimpan tak terbaca: baris itu dilewati (tercatat di log dengan id baris) sampai aplikasi mengklaim ulang token yang sama, yang menulis ulangnya. Jadi `APP_KEY` jangan diganti.
- Hanya akun yang punya akun server (masuk dengan Google) yang melaporkan token; akun lokal dan mode demo tidak pernah mengirimnya. Token API server di Android kini satu berkas per akun supaya akun Google lain di perangkat yang sama tidak melaporkan pembelian atas nama akun sebelumnya.
- Kunci akun layanan Play API adalah rahasia baru di server: hanya di `.env.production`, dengan izin minimum (lihat saja; izin kelola sengaja tidak diberikan karena kode hanya membaca, dan kunci yang bocor dengan izin kelola bisa membatalkan atau mengembalikan dana pelanggan).

### Yang belum terbukti dan keterbatasan

Semua di bawah ini baru asumsi; pembuktiannya di Tahap 8 (penguji lisensi, aplikasi dipasang dari Play) dan Tahap 12.

- **Bentuk jawaban Play sungguhan belum pernah dilihat.** Fixture tes disusun dari model `vendor/google/apiclient-services` dan dokumentasi, lalu diurai klien Google asli dengan HTTP palsu; nama field, nilai enum, dan format `expiryTime` di Play nyata belum dikonfirmasi. Jawaban Play tidak memuat `packageName`, jadi paket diperiksa lewat URL permintaan (token paket lain dijawab 400 atau 404 oleh Play, juga asumsi).
- **Refund Pro** hanya terdeteksi bila `productsv2` sendiri berubah. Notifikasi `voidedPurchaseNotification` bukan bukti, dan tidak ada jalur cadangan ke Voided Purchases API. Bila Play tidak mengubah `purchaseState` atau `refundableQuantity` setelah refund, Pro tetap aktif; bila `refundableQuantity` bernilai 0 pada pembelian yang tidak di-refund, pembeli sah ditolak.
- **`expiryTime` pada masa tenggang** diasumsikan diperpanjang Play sampai akhir masa tenggang; bila tidak, pelanggan dalam masa tenggang kehilangan Sync (bukan Pro) lebih cepat dari seharusnya.
- **Token langganan pengganti tidak diikuti.** Server tidak membaca `linkedPurchaseToken` atau `expiredPurchaseToken`: pelanggan yang mengganti base plan atau berlangganan lagi dari Play Store mendapat token baru yang diabaikan notifikasi (belum pernah diklaim) sampai aplikasi dibuka dan mengklaimnya. Memperluas aturan itu adalah keputusan pemilik, belum diambil.
- **Autentikasi OIDC** terbukti hanya dengan kunci RSA buatan tes dan JWKS palsu (tanda tangan, audience, issuer, kedaluwarsa, algoritma `none`, email, `email_verified`); belum dengan token sungguhan dari Pub/Sub. Belum dicek bahwa Cloudflare dan nginx meneruskan `Authorization` dan tidak memblokir POST dari Google.
- **MySQL.** Semua tes memakai SQLite; `lockForUpdate` pada `PlanSynchronizer` dan jenis pengecualian pelanggaran unik di MySQL produksi belum terbukti. Migrasi `play_purchases` belum dijalankan di produksi dan server belum di-deploy.
- **Batas ukuran** (token 4096 karakter, JWT 8192 byte, data Pub/Sub 64 KB, timeout Google 10 detik) adalah pilihan implementasi; panjang token Play sungguhan belum diperiksa, dan bila Play pernah menerbitkan token lebih panjang dari 4096 karakter, klaimnya ditolak 422.
- **Android** belum diuji di perangkat atau terhadap server hidup; kabel Compose (`MainHost`, `AppRoot`) tidak punya tes, hanya keputusan di `PurchaseClaimCoordinator`.

## Token API Android (Tahap 12)

**Diimplementasikan 2026-10-25** (server: `server/app/Auth/ApiTokenService.php`, `Api\AccountController`, `config/sanctum.php`; Android: `TokenRefresher`, `ServerSession`, `ServerSignOut` di `:app/sync`). **Status: hanya terbukti lewat tes otomatis** (SQLite di memori dan remote palsu; dijalankan paksa, 0 gagal: server 1012 tes dan 22100 asersi, Android `:domain` 785, `:data` 198, `:app` 328). Tidak pernah diuji di perangkat atau emulator, di MySQL, atau terhadap server hidup; daftar yang belum terbukti ada di akhir bagian ini. Langkah operasional (variabel `.env`, penjadwal, pembersihan) ada di `server/DEPLOY.md` bagian "Token API Android".

Tujuannya memenuhi aturan infrastruktur "token kedaluwarsa dan bisa dicabut, jangan meniru token permanen Al-Kaukaba". Catatan sejarah: sebelum 2026-10-25 `config/sanctum.php` tidak ada, sehingga `expiration` Sanctum bernilai null dan token yang diterbitkan sejak 2026-10-19 tidak pernah kedaluwarsa. Berkas itu kini ada dan token terbitan lama tanpa `expires_at` kedaluwarsa lewat jaring pengaman `created_at` (Guard menolak token yang `created_at`-nya lebih tua dari masa berlaku).

### Masa berlaku dan penerbitan

- Masa berlaku token baru `API_TOKEN_TTL_DAYS` hari: bilangan bulat 1 sampai 1825, bawaan **90**; kosong, huruf, pecahan, nol, negatif, atau di atas 1825 jatuh ke 90. Dicatat di kolom `personal_access_tokens.expires_at`. `ApiTokenService` adalah satu-satunya yang menerbitkan token dan menghitung masa berlakunya; `config/sanctum.php` memakai fungsi penguraian yang sama untuk `expiration` (dalam menit).
- Token kedaluwarsa atau yang sudah dicabut ditolak 401 JSON oleh `auth:sanctum` di semua endpoint terlindungi (seluruh `/api/*` menjawab galat sebagai JSON).
- **Plafon 20 token aktif per akun** (satu per perangkat): masuk dari perangkat baru saat sudah penuh membuang token yang paling lama tak dipakai (`last_used_at`, atau `created_at` bila belum pernah dipakai). Nama token `android:<device_id>`; masuk ulang di perangkat yang sama hanya mengganti token perangkat itu. `device_id` opsional di `POST /api/auth/google`, bila ada 1 sampai 64 karakter `A-Za-z0-9._-` (selain itu 422), tanpa itu `android:default`. Aplikasi mengirim UUID acak per pemasangan.
- Penerbitan per perangkat (`issueForDevice`) satu transaksi: kunci baris pengguna `FOR UPDATE`, token lama perangkat itu dicabut, kelebihan dibuang, token baru terbit. Bila MySQL membatalkan transaksi karena deadlock (kode 1213), diulang sampai 3 kali.

### Endpoint

| Endpoint | Autentikasi dan batas | Fungsi |
|---|---|---|
| `POST /api/auth/google` | Tanpa token; **30 per menit per IP** (limiter `api-login`) | Body `{"credential":"<ID token Google>","device_id":"..."}`. 200 `{"token":"...","expires_at":"2027-01-22T10:00:00Z","user":{"id","name","email"}}` (`expires_at` ISO-8601 UTC detik penuh). 422 bila ID token tidak sah atau `device_id` salah bentuk |
| `POST /api/auth/refresh` | `auth:sanctum`; **10 per menit** per akun + nama token perangkat (limiter `api-token-refresh`) | Memutar token yang dipakai: token itu dicabut, token baru (nama dan abilities sama) terbit dengan masa berlaku penuh. 200 `{"token","expires_at"}`. 401 bila kedaluwarsa atau sudah dicabut; klien lalu masuk ulang dengan Google |
| `POST /api/auth/logout` | `auth:sanctum`; **sengaja tanpa throttle** | Mencabut hanya token yang dipakai (204). Token perangkat lain tetap |
| `DELETE /api/auth/tokens` | `auth:sanctum`; **sengaja tanpa throttle** | Mencabut semua token akun, keluar dari semua perangkat (204) |

- **Rotasi atomik.** Pencabutan adalah `DELETE` atas id token dalam satu transaksi dengan penerbitan token baru; hanya satu dari dua refresh bersamaan dengan token yang sama yang melihat tepat satu baris terhapus, jadi paling banyak satu token baru terbit dan yang kalah dijawab 401 tanpa menerbitkan apa pun. Bila penerbitan gagal, token lama tetap utuh.
- **Kunci throttle refresh** adalah akun + nama token perangkat, bukan id token, sebuah penyimpangan sadar: id token berganti di tiap rotasi, jadi batas per id tidak pernah tercapai. Akibatnya pemegang token curian yang memutar berulang hanya menghabiskan jatah garis keturunan token itu, bukan jatah perangkat lain milik korban.
- **Logout dan cabut-semua tanpa throttle** supaya pemegang token curian tidak bisa menghalangi pemilik mencabut dengan menghabiskan jatah (429). Efeknya idempoten, tetapi panggilan kedua dengan token yang sudah dicabut dijawab 401 oleh `auth:sanctum`.
- **Kata sandi.** Mengubah kata sandi yang sudah ada dan mereset kata sandi mencabut semua token API akun (mengubah sandi tidak memutus sesi web yang sedang dipakai). Menetapkan sandi **pertama** untuk akun Google tidak mencabut apa pun. Hapus akun juga mencabut semua token.
- **Pembersihan.** `sanctum:prune-expired --hours=24` dijadwalkan harian 03:45 di `routes/console.php`. Token kedaluwarsa sudah ditolak tanpa itu; perintah ini hanya merapikan tabel. Penjadwal Laravel baru jalan bila ada cron `schedule:run` di VPS dan **cron itu belum ada** (butir Tahap 12, kerja pemilik; cron yang sama dengan `play:reconcile`).

### Perilaku di Android

- `TokenRefreshPolicy` (`:domain`, murni): token diputar bila sisa masa berlakunya 30 hari atau kurang; jeda minimum 1 hari antar putar (jam ponsel yang salah tidak membuat putar tiap aplikasi dibuka); token lama yang tanggalnya belum diketahui diputar sekali. Yang menentukan sesi berakhir adalah jawaban 401 server, bukan jam ponsel.
- `TokenRefresher`: satu putar pada satu waktu (Mutex), berjalan `NonCancellable`, token baru **disimpan sebelum dipakai** (token lama sudah dicabut server), penulisan di dispatcher IO. Refresh tidak mendahului sync paket Play.
- `ServerSession` (satu per `AccountWorkspace`) adalah satu-satunya penangan 401: semua remote dibungkus guard (`GuardedRemotes.kt`) kecuali `loginWithGoogle`. 401 membersihkan token dan menaikkan keadaan perlu-sambungkan-ulang; baris "Sambungkan ulang ke server" di menu Lainnya (hanya akun Google) masuk Google ulang untuk akun yang sama. Kegagalan lain, termasuk jaringan mati, tidak pernah mencabut apa pun. Aplikasi tetap offline-first: sesi berakhir hanya menghentikan sync dan fitur server, tidak menyentuh data lokal maupun paket dari Google Play.
- `ServerSignOut`: keluar tidak menunggu jaringan. Token lokal dibersihkan secara sinkron, pencabutan di server (`POST /api/auth/logout`) dicoba di latar dengan batas 5 detik sebagai upaya terbaik; berlaku juga saat keluar dari mode demo. Konsekuensinya, token yang gagal dicabut karena offline tetap hidup di server sampai berakhir (90 hari), walau sudah tidak ada di perangkat.
- Hapus akun setelah sesi berakhir tidak lagi berpura-pura sukses: pengguna diarahkan menyambungkan ulang dulu. Baris Hapus akun disembunyikan di mode demo.
- **Alamat API** dikonfigurasi saat build (`BuildConfig.API_BASE_URL`, prioritas `-PrizqflowApiBaseUrl`, lalu `rizqflow.apiBaseUrl` di `local.properties`, lalu env `RIZQFLOW_API_BASE_URL`, lalu bawaan per build type); rilis wajib `https://` dan alamat wajib berakhir `/`. Panggilan jaringan dibatasi `callTimeout` 30 detik. Http polos hanya diizinkan di build debug lewat network security config khusus debug. Cara memakainya untuk HP fisik ada di [roadmap.md](roadmap.md) Tahap 9 dan CLAUDE.md "Proyek Android".

### Yang belum terbukti, keputusan pemilik, dan utang

- **Belum pernah dicoba** di perangkat atau emulator, di MySQL, atau terhadap server hidup. Penyambungan lambda Compose di `AppRoot`/`MainHost` hanya terbukti lolos kompilasi (keputusannya sudah diekstrak ke fungsi murni yang teruji); alur Credential Manager untuk sambungkan ulang tidak teruji; cleartext debug baru terbukti lewat isi manifest gabungan (debug `usesCleartextTraffic=true` dengan `networkSecurityConfig`, rilis `false` tanpa `networkSecurityConfig`), belum lewat panggilan http sungguhan.
- **Deadlock InnoDB** pada login bersamaan tidak teruji: SQLite tidak punya gap lock dan mengabaikan `FOR UPDATE`, jadi tes hanya membuktikan bahwa kegagalan serupa deadlock diulang, bukan bahwa MySQL tidak lagi menemuinya.
- **IP asli untuk throttle masuk** (30 per menit per IP) bergantung pada `trustProxies` dan IP asli yang diteruskan Cloudflare dan nginx, **belum dicek di VPS**. Bila yang terbaca selalu IP proxy, batas itu berlaku untuk semua pengguna sekaligus.
- **Masa tenggang token lama setelah rotasi: tidak dikerjakan, keputusan pemilik.** Bila proses aplikasi mati tepat antara jawaban refresh dan penyimpanan token baru, sesi hilang dan pulih lewat masuk ulang Google (data lokal aman). Masa tenggang beberapa detik mengecilkan kasus itu tetapi memperpanjang jendela bagi token curian. Hal serupa bila jawaban refresh hilang di jaringan setelah rotasi berhasil di server.
- **Sesi sliding**: token yang terus diputar tidak pernah memaksa masuk Google ulang. Bila perlu batas keras, simpan `created_at` rantai awal; catatan desain, belum diputuskan.
- **Utang `MAX_TTL_DAYS` 1825:** kolom `expires_at` bertipe TIMESTAMP di MySQL berhenti di 2038-01-19, jadi sekitar 2033-01-20 batas 1825 hari usang. Perbaikan: batas dinamis atau kolom DATETIME.
- **Daftar perangkat** yang masuk dengan tombol cabut belum ada di UI mana pun; `DELETE /api/auth/tokens` belum dipakai layar mana pun (butir Tahap 14).
- Migrasi dan konfigurasi belum di-deploy; Hapus akun setelah sesi berakhir tidak punya tes pada tingkat layar.

## Salinan kunci pemulihan (Tahap 13)

**Diimplementasikan 2026-10-25** (server: `Api\RecoveryKeyController`, model `RecoveryKey`, migrasi `2026_10_28_000001_create_recovery_keys_table`, trait `DeniesWithoutSync`; Android: `RecoveryKeyService`, `RecoveryKeyRemote`/`RetrofitRecoveryKeyRemote`, `RecoverySetupScreen`, `RecoveryRestoreScreen`). **Status: hanya terbukti lewat tes otomatis** (SQLite di memori, remote dan penyimpanan palsu, Robolectric; dijalankan paksa, 0 gagal: server 1059 tes dan 22438 asersi, Android `:domain` 855, `:data` 243, `:app` 356). Tidak pernah diuji di perangkat atau emulator, di Android Keystore asli, di MySQL, atau terhadap server hidup. Langkah operasional ada di `server/DEPLOY.md` bagian "Salinan kunci pemulihan".

Tujuannya memenuhi baris "Pemulihan" di tabel Model kunci: server menyimpan satu salinan kunci akun yang terbungkus kode pemulihan 24 kata, tanpa pernah bisa membukanya. Pembungkusan terjadi di klien (`AccountKeyRecoveryCrypto.wrap`: AES-256-GCM lewat `BackupCrypto`, kunci PBKDF2 300.000 iterasi dari 24 kata, format `version` 1); server hanya menyimpan dan mengembalikan byte yang sama. Kode 24 kata tidak pernah disimpan atau dikirim dari aplikasi: ia hanya ada di memori proses (`RecoveryCodeHolder`) selama layar tampil-sekali terbuka.

### Endpoint

Semua di dalam `auth:sanctum`, memakai limiter `recovery-key` (**20 per menit per akun, satu jatah bersama untuk ketiga rute**), dan hanya menyentuh baris milik pemilik token (tidak ada id atau `user_id` dari klien yang dipakai).

| Endpoint | Butuh Sync | Fungsi |
|---|---|---|
| `PUT /api/account/recovery-key` | **Ya** (403 `{"message":"Butuh langganan Sync."}`) | Body `{"wrapped_key":"<base64>","version":1}`. Menyimpan atau mengganti satu-satunya salinan (atomik lewat `upsert` atas `user_id` unik). 200 `{"version","updated_at"}` (ISO-8601 UTC, tanpa mengulang blob). 422 bila body tidak sah |
| `GET /api/account/recovery-key` | Tidak | 200 `{"wrapped_key","version","updated_at"}` dengan `Cache-Control: no-store, private`. Belum ada salinan: 404 `{"message":"Belum ada kode pemulihan","code":"no_recovery_key"}` |
| `DELETE /api/account/recovery-key` | Tidak | Idempoten: 204 juga bila belum ada |

- **Gating (usulan Claude, belum dikonfirmasi pemilik).** Hanya `PUT` yang butuh `Feature::EncryptedSync`. `GET` dan `DELETE` sengaja tidak, supaya pengguna yang langganannya berakhir tetap bisa memulihkan kunci miliknya sendiri (itu datanya, bukan fitur berbayar) dan menghapus salinannya. Penentu "boleh atau tidak" tetap satu tempat (`DeniesWithoutSync`, dipakai juga `SyncController`).
- **404 yang bisa dibedakan.** Hanya 404 dari aplikasi ini yang memuat `code: "no_recovery_key"`; klien (`RetrofitRecoveryKeyRemote`) menganggap "belum ada salinan" hanya bila `code` itu ada. 404 tanpa `code` berarti rute belum di-deploy atau Nginx atau proxy yang menjawab, dan diperlakukan sebagai galat, bukan "belum ada". Pembedaan ini bergantung pada asumsi bahwa Nginx dan Cloudflare tidak menimpa isi 404 aplikasi, yang belum dicek di VPS.
- **Validasi `wrapped_key`.** Wajib base64 **standar kanonik**: didekode strict lalu di-encode ulang harus sama persis dengan masukan, jadi spasi, pemisah baris, base64 URL-safe, dan padding yang hilang ditolak (klien wajib Base64 standar dengan padding dan tanpa pemisah baris). Panjang teks dibatasi 10924 karakter lebih dulu (`bail`, supaya string raksasa tidak didekode), lalu ukuran terdekode harus 1 sampai 8192 byte (`RecoveryKey::MAX_BYTES`; blob sebenarnya hanya puluhan byte). `version` wajib integer sungguhan (`integer:strict`: "1", 1.0, dan true ditolak) dan hanya nilai di `RecoveryKey::KNOWN_VERSIONS`, kini `[1]`. Penjaga ukuran ini baru bekerja setelah body diterima Laravel; `client_max_body_size` Nginx dan `post_max_size` PHP belum diperiksa.

### Penyimpanan tanpa enkripsi ganda

Blob disimpan sebagai **byte biner** (kolom `binary`, BLOB di SQLite dan MySQL; hasil dekode base64, bukan teks base64): lebih kecil 25%, tanpa urusan charset atau collation, dan tidak pernah dikira teks. **Tanpa cast `encrypted` dan tanpa enkripsi ganda dengan `APP_KEY`**, sengaja berbeda dari `play_purchases`. Alasannya: blob sudah terenkripsi di klien dengan kunci yang hanya diturunkan dari kode pemulihan; membungkusnya lagi dengan `APP_KEY` tidak menambah kerahasiaan terhadap server (`APP_KEY` ada di server), memberi kesan seolah server memegang kunci, dan membuat blob tak terbaca bila `APP_KEY` berganti. Sebaliknya `play_purchases` harus dibaca utuh oleh server, jadi di sana enkripsi `APP_KEY` memang berarti. Tidak ada kolom lain yang bisa membocorkan (tanpa frasa, petunjuk, atau hash kode); satu akun satu baris, hapus akun ikut menghapusnya (`cascadeOnDelete`).

`wrapped_key` disembunyikan dari serialisasi model `RecoveryKey` dan relasi `User::recoveryKey` (`$hidden`), tidak pernah masuk log atau pesan galat, dan hanya `GET` milik pemilik yang membacanya secara eksplisit. `QueryException` ditangkap di `PUT` karena pesannya memuat SQL beserta nilai binding (isi blob): log hanya mencatat `user_id` dan `sql_state`, respons berupa galat umum 500.

### Perilaku di Android (ringkas)

- `enableRecovery`: membuat kode baru, membungkus kunci akun, lalu memeriksa bahwa bungkusan terbuka kembali dengan kode itu dan hasilnya sama dengan kunci asli (pagar keselamatan) sebelum diunggah. Setelah `PUT`, dilakukan `GET` dan byte dibandingkan; kode baru ditampilkan hanya bila sama. Unggah dan pencatatan berjalan `NonCancellable` dan satu per satu (`Mutex`).
- Penanda lokal `RecoveryStatus` (`NotConfigured`, `PendingConfirmation`, `Configured`, membawa `updated_at` server) tidak memuat rahasia. `PendingConfirmation` dicatat **sebelum** unggah supaya unggahan yang terkomit tetapi balasannya hilang tidak meninggalkan penanda yang berbohong. Hasil ambigu (jaringan putus, 5xx, balasan rusak, atau salinan tak bisa dibaca kembali) tidak pernah menampilkan kode dan meninggalkan `PendingConfirmation`; jalan keluarnya membuat kode baru. `compareWithServer` membandingkan `updated_at` server dengan yang dicatat, untuk mendeteksi salinan yang diganti dari perangkat lain.
- Bila perangkat belum pernah mengatur kode tetapi server sudah punya salinan (dari perangkat lain), unggah tidak menimpanya diam-diam (`ExistingCopyOnServer`; pengguna memilih pulihkan atau menimpa lewat dialog).
- `recoverKey`: salinan diambil, dibuka dengan kode, lalu dipasang lewat `AccountKeyProvider.importAccountKeyFromRecovery`, yang **menimpa** kunci akun lokal. Impor adalah langkah terakhir dan kegagalan di tahap mana pun tidak menyentuh kunci lokal. Kebijakan menimpa aman sekarang hanya karena belum ada data lokal yang dienkripsi dengan kunci akun; begitu sinkron entitas akun ada, pulihkan wajib menjaga data lama (mis. menyimpan kunci lama di keyset yang sama seperti `rotateRoomKey`).
- Kunci akun kini per akun (`AndroidKeystoreAccountKeyProvider(context, accountId)`): keyset, berkas, dan alias Keystore dipisah per akun Google di ponsel yang sama.

### Yang belum terbukti, keputusan pemilik, dan utang

- **INTI: kunci akun yang dipulihkan saat ini tidak mengenkripsi apa pun.** `getOrCreateAccountKey` tidak punya pemanggil produksi, dan `SyncEngine` memakai kunci ruang yang **tidak** ikut bungkusan versi 1. Pulihkan di perangkat baru memulihkan kunci akun saja; ruang keluarga yang dibagi harus diundang ulang lewat QR. **Keputusan pemilik:** memasukkan kunci ruang ke bungkusan sebagai format `version` 2 sebelum rilis (server sudah menolak versi selain 1 dan klien menolak versi tak dikenal), atau menerima undang ulang. Teks di aplikasi sudah dibuat jujur soal ini.
- **Keputusan pemilik:** `FLAG_SECURE` khusus layar tampil-sekali (sekarang screenshot, thumbnail recents, dan perekam layar tidak dicegah, hanya peringatan teks; `FLAG_SECURE` app-wide dihapus 2026-10-24), batas waktu pemegang kode saat aplikasi ke latar, dan penyeragaman `KeyboardType`. Verifikasi 3 kata acak dan aturan gating di atas adalah usulan Claude yang belum dikonfirmasi.
- **Belum diuji:** Android Keystore asli (Robolectric jatuh ke penyimpanan cleartext, jadi butir "Buktikan Android Keystore asli" di roadmap tetap terbuka); perangkat atau emulator; layar Compose, tombol Back, rotasi, dialog berlapis, dan pembaca layar hanya lolos kompilasi; MySQL (kolom `binary`, `upsert`); server hidup; waktu PBKDF2 300.000 iterasi di ponsel sungguhan tidak diukur.
- **Hasil `Ready` yang sudah terkomit tetapi layarnya hilang karena rotasi** tidak sampai ke layar: pengguna melihat tawaran membuat kode baru.
- **Token API yang dicuri** bisa `DELETE` (atau `PUT` bila Sync aktif) dan merusak kemampuan memulihkan; setara `DELETE /api/account` yang sudah ada.
- Kunci Keystore akun tidak dihapus saat Hapus akun; beberapa instans `AccountWorkspace` untuk akun yang sama punya cache kunci sendiri (belum berdampak karena belum ada pemakai kunci akun).
- Respons 5xx diperlakukan ambigu, jadi status "sudah diatur" bisa turun ke "belum selesai" setelah unggah ulang yang tak bisa dipastikan.
- Migrasi `recovery_keys` dan rute baru belum di-deploy (perlu `route:cache` ulang); cadangan database VPS kini memuat blob ini (tetap buram, dan kode pemulihan tidak pernah ada di server).

## Belum diputuskan

- ~~**Firebase Analytics** dipakai atau cukup Play Console plus data akun server.~~ **Diputuskan pemilik 2026-10-24: Firebase Analytics dan FCM dipakai di rilis 1.** Aturan: tidak ada data keuangan (nominal, nama, catatan, akun) di event atau properti pengguna; keduanya disebut di kebijakan privasi dan Data Safety; jumlah pengguna, Pro, dan Sync tetap dihitung dari server.
- Skema Room perlu penanda waktu ubah, penanda hapus, dan pengenal perangkat di hampir semua tabel (menyentuh model data; lihat [model-data.md](model-data.md) saat dikerjakan).
