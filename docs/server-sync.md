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
| Token | Kedaluwarsa dan bisa dicabut. **Jangan meniru token permanen Al-Kaukaba** |

Catatan dari CLAUDE.md Al-Kaukaba yang berlaku juga di sini: setiap habis `git pull` yang mengubah rute, jalankan `route:cache` ulang; dan hindari rute yang berakhiran `.php` supaya tidak terkena masalah `location ~ \.php$` di Nginx.

**Risiko yang diterima:** RAM VPS kemungkinan kecil (VS Code Server pernah dicopot demi hemat RAM) dan satu VPS yang bermasalah menjatuhkan semua situs di dalamnya. Spesifikasi (`free -m`, `df -h`, swap) dicek pemilik sebelum apa pun dipasang.

**Alamat API di aplikasi bisa dikonfigurasi** (bukan ditanam), supaya pindah ke VPS khusus tidak membutuhkan rilis ulang yang rumit.

## Model kunci (Tahap 13)

Tujuan: server tidak bisa membaca data, pasangan bisa berbagi ruang Keluarga, dan pemulihan tetap ada.

| Bagian | Keputusan |
|---|---|
| Pustaka | Google Tink (AES-GCM dan enkripsi hibrida). Tidak menulis kripto sendiri |
| Kunci akun | Dibuat acak di perangkat, disimpan dalam Android Keystore |
| Pemulihan | **Kode pemulihan wajib** saat Sync diaktifkan (misalnya 24 kata, ditampilkan sekali dan wajib dicatat pengguna). Salinan kunci akun terbungkus kode itu disimpan di server, yang tidak bisa membukanya |
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

## Belum diputuskan

- ~~**Firebase Analytics** dipakai atau cukup Play Console plus data akun server.~~ **Diputuskan pemilik 2026-10-24: Firebase Analytics dan FCM dipakai di rilis 1.** Aturan: tidak ada data keuangan (nominal, nama, catatan, akun) di event atau properti pengguna; keduanya disebut di kebijakan privasi dan Data Safety; jumlah pengguna, Pro, dan Sync tetap dihitung dari server.
- Skema Room perlu penanda waktu ubah, penanda hapus, dan pengenal perangkat di hampir semua tabel (menyentuh model data; lihat [model-data.md](model-data.md) saat dikerjakan).
