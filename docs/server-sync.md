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
- `GET /api/sync/pull?after=&limit=&device_id=` — baris milik pengguna itu dengan `id` lebih besar dari `after`, urut naik, dibatasi `limit` (bawaan 200, maks 500); balas `next_after` dan `has_more` untuk paginasi. **Param opsional `scope=a|r` dan `room_id` (2026-11-01) menarik satu cakupan saja dengan kursor per cakupan: lihat bagian "Pull per cakupan dan waktu per cakupan" di bawah; tanpa `scope` perilaku di bawah ini tidak berubah.** **Pull dari `after=0` di perangkat baru = restore dari backup**, karena seluruh riwayat perubahan ada di log ini. **`device_id` wajib sejak 2026-11-01** (1 sampai 100 karakter): kursor perangkat di `sync_devices` yang menahan kompaksi tombstone, jadi perangkat yang tidak tercatat bisa melewatkan penghapusan. Tanpa `device_id` (atau kosong) dijawab 422 `{"message":"...","code":"device_id_required","errors":{"device_id":[...]}}` tanpa baris apa pun; aplikasi Android sudah selalu mengirimnya. Urutan jawaban: 401 tanpa token, 403 tanpa Sync, lalu 422.

**Lantai per rekaman (2026-11-01, Tahap 9).** Saat `sync:compact` menghapus fisik sebuah tombstone, `changed_at_ms`-nya dicatat di tabel `sync_record_floors` (model `SyncRecordFloor`; kolom `user_id`, `scope` = `room_id` atau string kosong untuk data pribadi, `record_id`, `floor_ms`; tanpa isi rekaman). Push berikutnya untuk rekaman yang sama dengan `changed_at_ms` <= lantai ditolak diam-diam (tidak masuk `ids`, sama seperti baris bukan penulis pertama; baris lain di batch yang sama tetap tersimpan), karena versi lama yang terlambat itu akan menghidupkan kembali rekaman yang sudah dihapus: tombstone yang mengalahkannya sudah tidak ada. Versi yang lebih baru dari lantai tetap diterima (edit sesudah hapus, last-write-wins). Lantai data pribadi hanya berlaku untuk akun itu; lantai ruang bersama berlaku untuk semua anggota ruang. Ikut terhapus bersama akun. **Belum terbukti:** hanya diuji di SQLite; di MySQL ada jendela balapan kecil antara push yang membaca lantai dan kompaksi yang sedang berjalan (tidak ada kunci baris bersama), dan perangkat yang menerbitkan ulang rekaman lama dengan cap waktu BARU (mis. backfill) tidak tertahan lantai.

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
- Server jadi tahu siapa membeli Pro atau Sync, kapan, dan statusnya (aktif, ditahan, kedaluwarsa, dikembalikan dananya). Ini menambah daftar "yang tetap terlihat server" di atas dan harus disebut di kebijakan privasi dan deklarasi Data Safety (Tahap 15; kategori yang tepat di formulir belum dicek).
- Mengganti `APP_KEY` membuat token tersimpan tak terbaca: baris itu dilewati (tercatat di log dengan id baris) sampai aplikasi mengklaim ulang token yang sama, yang menulis ulangnya. Jadi `APP_KEY` jangan diganti.
- Hanya akun yang punya akun server (masuk dengan Google) yang melaporkan token; akun lokal dan mode demo tidak pernah mengirimnya. Token API server di Android kini satu berkas per akun supaya akun Google lain di perangkat yang sama tidak melaporkan pembelian atas nama akun sebelumnya.
- Kunci akun layanan Play API adalah rahasia baru di server: hanya di `.env.production`, dengan izin minimum (lihat saja; izin kelola sengaja tidak diberikan karena kode hanya membaca, dan kunci yang bocor dengan izin kelola bisa membatalkan atau mengembalikan dana pelanggan).

### Yang belum terbukti dan keterbatasan

Semua di bawah ini baru asumsi; pembuktiannya di Tahap 15 (penguji lisensi, aplikasi dipasang dari Play) dan Tahap 12.

- **Bentuk jawaban Play sungguhan belum pernah dilihat.** Fixture tes disusun dari model `vendor/google/apiclient-services` dan dokumentasi, lalu diurai klien Google asli dengan HTTP palsu; nama field, nilai enum, dan format `expiryTime` di Play nyata belum dikonfirmasi. Jawaban Play tidak memuat `packageName`, jadi paket diperiksa lewat URL permintaan (token paket lain dijawab 400 atau 404 oleh Play, juga asumsi).
- **Refund Pro** hanya terdeteksi bila `productsv2` sendiri berubah. Notifikasi `voidedPurchaseNotification` bukan bukti, dan tidak ada jalur cadangan ke Voided Purchases API. Bila Play tidak mengubah `purchaseState` atau `refundableQuantity` setelah refund, Pro tetap aktif; bila `refundableQuantity` bernilai 0 pada pembelian yang tidak di-refund, pembeli sah ditolak.
- **`expiryTime` pada masa tenggang** diasumsikan diperpanjang Play sampai akhir masa tenggang; bila tidak, pelanggan dalam masa tenggang kehilangan Sync (bukan Pro) lebih cepat dari seharusnya.
- **Token langganan pengganti kini diikuti otomatis** (2026-10-26, Tahap 9): `linkedPurchaseToken` dan `outOfAppPurchaseContext.expiredPurchaseToken` dari `subscriptionsv2.get` (keduanya khusus SUBS; INAPP tidak punya field ini) dibawa sebagai `PlayVerification::$predecessorToken`. `PlayPurchaseChainResolver` mengaitkan token baru yang belum pernah diklaim ke pemilik baris pendahulu saat RTDN tiba, sebelum aplikasi dibuka; klaim manual oleh akun lain atas token penerus semacam itu ditolak `claimed_by_other`. Kolom `play_purchases.superseded_by_token_hash` murni jejak audit. Belum diuji terhadap Play sungguhan.
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

Tujuannya memenuhi baris "Pemulihan" di tabel Model kunci: server menyimpan satu salinan kunci akun (dan sejak 2026-10-27, kunci ruang keluarga) yang terbungkus kode pemulihan 24 kata, tanpa pernah bisa membukanya. Pembungkusan terjadi di klien (`AccountKeyRecoveryCrypto.wrap`: AES-256-GCM lewat `BackupCrypto`, kunci PBKDF2 300.000 iterasi dari 24 kata — kriptonya sama untuk semua `version`); server hanya menyimpan dan mengembalikan byte yang sama, tidak pernah membaca isinya. **`version` 2 (2026-10-27, keputusan pemilik 2026-10-26, roadmap.md Tahap 13):** isi yang dibungkus kini [`RecoveryBundle`](../domain/src/main/kotlin/com/roziqrizal/rizqflow/domain/sync/RecoveryBundle.kt) — kunci akun PLUS kunci semua ruang keluarga AKTIF yang diikuti perangkat saat `enableRecovery` dipanggil (format panjang-diawali + checksum CRC32, mengikuti gaya `RoomInvite`) — bukan lagi bytes kunci akun mentah. Klien kini hanya menulis `version` 2; `version` 1 lama (hanya kunci akun, belum pernah dipakai pengguna sungguhan) tetap bisa dibuka `recoverKey` supaya tidak mogok bila ditemukan. Kode 24 kata tidak pernah disimpan atau dikirim dari aplikasi: ia hanya ada di memori proses (`RecoveryCodeHolder`) selama layar tampil-sekali terbuka.

### Endpoint

Semua di dalam `auth:sanctum`, memakai limiter `recovery-key` (**20 per menit per akun, satu jatah bersama untuk ketiga rute**), dan hanya menyentuh baris milik pemilik token (tidak ada id atau `user_id` dari klien yang dipakai).

| Endpoint | Butuh Sync | Fungsi |
|---|---|---|
| `PUT /api/account/recovery-key` | **Ya** (403 `{"message":"Butuh langganan Sync."}`) | Body `{"wrapped_key":"<base64>","version":1\|2}`. Menyimpan atau mengganti satu-satunya salinan (atomik lewat `upsert` atas `user_id` unik). 200 `{"version","updated_at"}` (ISO-8601 UTC, tanpa mengulang blob). 422 bila body tidak sah |
| `GET /api/account/recovery-key` | Tidak | 200 `{"wrapped_key","version","updated_at"}` dengan `Cache-Control: no-store, private`. Belum ada salinan: 404 `{"message":"Belum ada kode pemulihan","code":"no_recovery_key"}` |
| `DELETE /api/account/recovery-key` | Tidak | Idempoten: 204 juga bila belum ada |

- **Gating (usulan Claude, belum dikonfirmasi pemilik).** Hanya `PUT` yang butuh `Feature::EncryptedSync`. `GET` dan `DELETE` sengaja tidak, supaya pengguna yang langganannya berakhir tetap bisa memulihkan kunci miliknya sendiri (itu datanya, bukan fitur berbayar) dan menghapus salinannya. Penentu "boleh atau tidak" tetap satu tempat (`DeniesWithoutSync`, dipakai juga `SyncController`).
- **404 yang bisa dibedakan.** Hanya 404 dari aplikasi ini yang memuat `code: "no_recovery_key"`; klien (`RetrofitRecoveryKeyRemote`) menganggap "belum ada salinan" hanya bila `code` itu ada. 404 tanpa `code` berarti rute belum di-deploy atau Nginx atau proxy yang menjawab, dan diperlakukan sebagai galat, bukan "belum ada". Pembedaan ini bergantung pada asumsi bahwa Nginx dan Cloudflare tidak menimpa isi 404 aplikasi, yang belum dicek di VPS.
- **Validasi `wrapped_key`.** Wajib base64 **standar kanonik**: didekode strict lalu di-encode ulang harus sama persis dengan masukan, jadi spasi, pemisah baris, base64 URL-safe, dan padding yang hilang ditolak (klien wajib Base64 standar dengan padding dan tanpa pemisah baris). Panjang teks dibatasi 10924 karakter lebih dulu (`bail`, supaya string raksasa tidak didekode), lalu ukuran terdekode harus 1 sampai 8192 byte (`RecoveryKey::MAX_BYTES`; blob sebenarnya hanya puluhan byte). `version` wajib integer sungguhan (`integer:strict`: "1", 1.0, dan true ditolak) dan hanya nilai di `RecoveryKey::KNOWN_VERSIONS`, kini `[1, 2]` (2 ditambahkan 2026-10-27; server hanya memeriksa angkanya, tidak pernah membaca isi blob untuk membedakannya). Penjaga ukuran ini baru bekerja setelah body diterima Laravel; `client_max_body_size` Nginx dan `post_max_size` PHP belum diperiksa.

### Penyimpanan tanpa enkripsi ganda

Blob disimpan sebagai **byte biner** (kolom `binary`, BLOB di SQLite dan MySQL; hasil dekode base64, bukan teks base64): lebih kecil 25%, tanpa urusan charset atau collation, dan tidak pernah dikira teks. **Tanpa cast `encrypted` dan tanpa enkripsi ganda dengan `APP_KEY`**, sengaja berbeda dari `play_purchases`. Alasannya: blob sudah terenkripsi di klien dengan kunci yang hanya diturunkan dari kode pemulihan; membungkusnya lagi dengan `APP_KEY` tidak menambah kerahasiaan terhadap server (`APP_KEY` ada di server), memberi kesan seolah server memegang kunci, dan membuat blob tak terbaca bila `APP_KEY` berganti. Sebaliknya `play_purchases` harus dibaca utuh oleh server, jadi di sana enkripsi `APP_KEY` memang berarti. Tidak ada kolom lain yang bisa membocorkan (tanpa frasa, petunjuk, atau hash kode); satu akun satu baris, hapus akun ikut menghapusnya (`cascadeOnDelete`).

`wrapped_key` disembunyikan dari serialisasi model `RecoveryKey` dan relasi `User::recoveryKey` (`$hidden`), tidak pernah masuk log atau pesan galat, dan hanya `GET` milik pemilik yang membacanya secara eksplisit. `QueryException` ditangkap di `PUT` karena pesannya memuat SQL beserta nilai binding (isi blob): log hanya mencatat `user_id` dan `sql_state`, respons berupa galat umum 500.

### Perilaku di Android (ringkas)

- `enableRecovery`: membuat kode baru, membungkus kunci akun, lalu memeriksa bahwa bungkusan terbuka kembali dengan kode itu dan hasilnya sama dengan kunci asli (pagar keselamatan) sebelum diunggah. Setelah `PUT`, dilakukan `GET` dan byte dibandingkan; kode baru ditampilkan hanya bila sama. Unggah dan pencatatan berjalan `NonCancellable` dan satu per satu (`Mutex`).
- Penanda lokal `RecoveryStatus` (`NotConfigured`, `PendingConfirmation`, `Configured`, membawa `updated_at` server) tidak memuat rahasia. `PendingConfirmation` dicatat **sebelum** unggah supaya unggahan yang terkomit tetapi balasannya hilang tidak meninggalkan penanda yang berbohong. Hasil ambigu (jaringan putus, 5xx, balasan rusak, atau salinan tak bisa dibaca kembali) tidak pernah menampilkan kode dan meninggalkan `PendingConfirmation`; jalan keluarnya membuat kode baru. `compareWithServer` membandingkan `updated_at` server dengan yang dicatat, untuk mendeteksi salinan yang diganti dari perangkat lain.
- Bila perangkat belum pernah mengatur kode tetapi server sudah punya salinan (dari perangkat lain), unggah tidak menimpanya diam-diam (`ExistingCopyOnServer`; pengguna memilih pulihkan atau menimpa lewat dialog).
- `recoverKey`: salinan diambil, dibuka dengan kode, lalu dipasang lewat `AccountKeyProvider.importAccountKeyFromRecovery`, yang **menimpa** kunci akun lokal. Impor adalah langkah terakhir dan kegagalan di tahap mana pun SEBELUM impor tidak menyentuh kunci lokal. Kebijakan menimpa aman sekarang hanya karena belum ada data lokal yang dienkripsi dengan kunci akun; begitu sinkron entitas akun ada, pulihkan wajib menjaga data lama (mis. menyimpan kunci lama di keyset yang sama seperti `rotateRoomKey`). **Sejak `version` 2 (2026-10-27):** setelah kunci akun terpasang, setiap kunci ruang yang ikut terbungkus dipasang lewat `RoomKeyProvider.importRoomKey` (juga **menimpa**, kebijakan sama). Satu ruang gagal dipasang tidak membatalkan kunci akun yang sudah berhasil atau ruang lain — dilewati diam-diam, ruang itu tetap bisa diundang ulang lewat QR seperti version 1.
- Kunci akun kini per akun (`AndroidKeystoreAccountKeyProvider(context, accountId)`): keyset, berkas, dan alias Keystore dipisah per akun Google di ponsel yang sama.

### Yang belum terbukti, keputusan pemilik, dan utang

- **INTI (masih berlaku setelah `version` 2): kunci akun yang dipulihkan tidak mengenkripsi apa pun.** `getOrCreateAccountKey` tidak punya pemanggil produksi untuk data akun. **Sejak 2026-10-27, kunci ruang keluarga AKTIF yang diikuti perangkat SAAT kode dibuat ikut terbungkus dan terpulihkan** (format `version` 2, `RecoveryBundle`), jadi pulihkan di perangkat baru kini memulihkan kunci ruang-ruang itu juga, bukan cuma kunci akun — ruang yang diikuti SETELAH kode lama dibuat (atau bergabung ulang setelah dikeluarkan) tidak otomatis tercakup kode LAMA itu, perlu kode baru atau tetap diundang ulang lewat QR. Belum diuji di perangkat/Keystore asli atau terhadap server hidup (lihat "Belum diuji" di bawah); belum ada migrasi data nyata untuk pengguna `version` 1 lama karena belum ada pengguna sungguhan.
- **Keputusan pemilik:** `FLAG_SECURE` khusus layar tampil-sekali (sekarang screenshot, thumbnail recents, dan perekam layar tidak dicegah, hanya peringatan teks; `FLAG_SECURE` app-wide dihapus 2026-10-24), batas waktu pemegang kode saat aplikasi ke latar, dan penyeragaman `KeyboardType`. Verifikasi 3 kata acak dan aturan gating di atas adalah usulan Claude yang belum dikonfirmasi.
- **Belum diuji:** Android Keystore asli (Robolectric jatuh ke penyimpanan cleartext, jadi butir "Buktikan Android Keystore asli" di roadmap tetap terbuka); perangkat atau emulator; layar Compose, tombol Back, rotasi, dialog berlapis, dan pembaca layar hanya lolos kompilasi; MySQL (kolom `binary`, `upsert`); server hidup; waktu PBKDF2 300.000 iterasi di ponsel sungguhan tidak diukur.
- **Hasil `Ready` yang sudah terkomit tetapi layarnya hilang karena rotasi** tidak sampai ke layar: pengguna melihat tawaran membuat kode baru.
- **Token API yang dicuri** bisa `DELETE` (atau `PUT` bila Sync aktif) dan merusak kemampuan memulihkan; setara `DELETE /api/account` yang sudah ada.
- Kunci Keystore akun tidak dihapus saat Hapus akun; beberapa instans `AccountWorkspace` untuk akun yang sama punya cache kunci sendiri (belum berdampak karena belum ada pemakai kunci akun).
- Respons 5xx diperlakukan ambigu, jadi status "sudah diatur" bisa turun ke "belum selesai" setelah unggah ulang yang tak bisa dipastikan.
- Migrasi `recovery_keys` dan rute baru belum di-deploy (perlu `route:cache` ulang); cadangan database VPS kini memuat blob ini (tetap buram, dan kode pemulihan tidak pernah ada di server).

## Push notifikasi (Tahap 9/13)

**Sisi Android diimplementasikan 2026-11-01** (`RizqflowMessagingService`, `SyncPushReceiver`, `FcmAvailability`, `FcmTokenSource` di `:app/push`; `PushTokenRegistrar`/`PushTokenSync` di `:domain`; `RetrofitPushTokenRegistrar` di `:data`). Pengirim di server dikerjakan agen lain secara terpisah. **Status: hanya terbukti lewat tes otomatis dan build tanpa `google-services.json`.** Tidak pernah diuji dengan FCM sungguhan, proyek Firebase, perangkat, atau server hidup (proyek Firebase pemilik belum ada).

### Kontrak endpoint

Kontrak di bawah **mengikuti implementasi server** (`PushTokenController`, 2026-11-01); klien Android disesuaikan ke jalur ini saat penggabungan. Draf awal sisi Android (`POST /api/push/tokens`, 403 tanpa Sync) tidak dipakai.

| Endpoint | Autentikasi | Badan | Jawaban |
|---|---|---|---|
| `PUT /api/push-token` | `auth:sanctum` | `{"device_id":"<pengenal pemasangan>","token":"<token FCM>"}`; kunci lain, termasuk `platform:"android"` yang tetap dikirim klien, diabaikan | 200 `{device_id, updated_at}`; upsert per (`user_id`, `device_id`); 422 bila badan salah. **Server tidak menuntut Sync**; klien sendiri hanya mendaftar bila punya Sync |
| `DELETE /api/push-token/{device_id}` | `auth:sanctum` | Tanpa badan | 204, idempoten (juga bila belum pernah mendaftar); hanya menyentuh baris milik pemilik token API; tanpa gerbang Sync |

- `device_id` adalah UUID acak per pemasangan yang sama dengan yang dikirim saat masuk (`POST /api/auth/google`), bukan pengenal perangkat sungguhan.
- Klien memetakan galat seperti endpoint lain (`RemoteError`): 401 mengakhiri sesi lewat `ServerSession`, 403 `sync_required` (atau tanpa kode) `SyncRequired` dan 403 berkode lain `Rejected`, 422 `Rejected`, lainnya `Unexpected`, jaringan mati `Unreachable`.

### Bentuk pesan push (wajib diikuti pengirim di server)

- **Data message murni**: blok `data` berisi **tepat** dua kunci, `roomId` (pengenal ruang, 1 sampai 128 karakter) dan `kind` (`NEW_RECORD`, `MEMBER_REMOVED`, atau `ROOM_KEY_ROTATED`). Tanpa blok `notification`: blok itu ditampilkan sistem sendiri saat aplikasi di latar tanpa melewati gerbang Sync dan parser aplikasi.
- **Kunci lain apa pun membuat seluruh pesan ditolak** (`SyncNotificationPayload.parse`), bukan diabaikan: nominal, nama, catatan, akun, judul, atau teks tidak boleh pernah ada di push. Teks notifikasi tetap di aplikasi (`reminder_sync_*`).
- Server hanya mengirim ke token akun yang punya Sync; aplikasi juga memeriksa ulang di sisi penerima.

### Perilaku di Android

- **Kapan token dikirim**: saat ruang kerja dibuka, setelah masuk atau sambung ulang ke server, saat Sync baru dimiliki, dan saat Firebase memberi token baru (`onNewToken`, tanpa membuka database akun). **Hanya bila** ada token FCM, pengguna punya Sync (`Feature.ENCRYPTED_SYNC`, lewat lapisan entitlement), dan ada token API server; akun lokal, akun uji, dan mode demo tidak pernah mengirim. Gagal atau offline dibiarkan diam dan dicoba lagi pada pemicu berikutnya.
- **Kapan token dicabut**: saat keluar (di latar, sebelum `POST /api/auth/logout`, dengan token API yang sama; batas waktu sendiri sehingga gagal mencabut tidak menghalangi pencabutan token API) dan sebelum Hapus akun (upaya terbaik, batas 5 detik). Mencabut tidak digerbang Sync.
- **Penerima**: notifikasi hanya bila ada akun server yang masuk, akun itu punya Sync, dan data lolos parser. Teks generik tanpa nama ruang (penerima tidak membuka database akun di latar). Mengetuknya membuka aplikasi; aplikasi belum otomatis menarik data setelah push (sync tetap lewat pemicu yang sudah ada).
- **Tanpa `google-services.json`** (keadaan sekarang): plugin `com.google.gms.google-services` tidak diterapkan, Firebase tidak terinisialisasi, `FcmAvailability` bernilai false, sehingga tidak ada token FCM, tidak ada panggilan `/api/push/*`, dan penerima tidak pernah dipanggil. Aplikasi tidak crash.

### Langkah pemasangan (pemilik)

1. Buat proyek Firebase, daftarkan aplikasi Android `com.roziqrizal.rizqflow` (tambahkan sidik jari SHA-1/SHA-256 debug dan rilis bila nanti perlu).
2. Unduh `google-services.json` dan taruh di `app/google-services.json`. Berkas ini ada di `.gitignore`; **jangan di-commit** sebelum diputuskan aman.
3. Build ulang (`./gradlew :app:assembleDebug`): plugin google-services otomatis diterapkan karena berkasnya ada.
4. Untuk pengirim di server: buat akun layanan dengan peran Firebase Cloud Messaging (FCM HTTP v1), simpan kuncinya hanya di `.env.production` VPS (nama variabel mengikuti implementasi server).
5. Uji di perangkat: masuk dengan akun yang punya Sync, pastikan `PUT /api/push-token` tercatat di server, kirim data message uji `{roomId, kind}`, lalu keluar dan pastikan `DELETE` terpanggil.

### Yang belum terbukti

- Belum pernah ada token FCM sungguhan, pesan FCM sungguhan, atau `onNewToken` yang benar-benar terpanggil; kompatibilitas plugin google-services 4.5.0 dengan AGP 9.4.1 hanya dicoba dengan `google-services.json` tiruan (lihat roadmap.md Tahap 9), bukan berkas asli.
- Izin `POST_NOTIFICATIONS` Android 13+ tetap diminta lewat alur pengingat yang sudah ada; push sync tidak memintanya sendiri, jadi pengguna yang menolak izin tidak melihat notifikasi ini.
- Pengguna yang kehilangan Sync tidak mencabut tokennya otomatis; aplikasi menolak menampilkan dan server diharapkan tidak mengirim.

## Notifikasi push "ada catatan baru" (Tahap 9/13)

**Diimplementasikan 2026-11-01, sisi server saja** (`server/app/Push/`: antarmuka `FcmSender`, `FcmHttpV1Sender` asli, `NullFcmSender` tanpa konfigurasi, `SyncNotificationPayload`, `RoomActivityNotifier`; `Api\PushTokenController`; tabel `push_tokens`). **Status: hanya terbukti lewat tes otomatis** (pengirim palsu `Tests\Support\FakeFcmSender` dan HTTP palsu, SQLite di memori). Belum pernah mengirim ke FCM sungguhan; proyek Firebase belum ada (tindakan pemilik); penerima FCM di Android dikerjakan terpisah. Variabel env dan langkah pemasangan di `server/DEPLOY.md` bagian "Notifikasi push FCM".

- **Pemicu:** setelah `POST /api/sync/push` yang menambah baris BARU ke sebuah `room_id` (bukan ulangan idempoten, bukan baris yang ditolak penjaga penulis pertama atau lantai, dan tidak bila batch ditolak kuota), server mengirim satu kabar per ruang ke semua token perangkat anggota LAIN ruang itu. Pencatat tidak diberi kabar, termasuk perangkatnya yang lain (mereka mendapatkannya lewat pull). Data pribadi (`room_id` kosong) tidak memicu apa pun. Kiriman berjalan setelah jawaban terkirim (`defer`), tanpa antrean dan tanpa pengulangan.
- **Isi kabar = kontrak `SyncNotificationPayload` di `:domain`:** pesan DATA FCM dengan tepat dua kunci, `roomId` dan `kind` (`NEW_RECORD`; `MEMBER_REMOVED` dan `ROOM_KEY_ROTATED` dikenal tetapi belum dipicu server). Tanpa blok `notification`, tanpa nominal, nama, catatan, akun, id rekaman, `device_id`, atau identitas pencatat. `FcmSender::send` hanya menerima `SyncNotificationPayload`, jadi tidak ada jalan mengirim data lain lewat antarmuka itu; tes membuktikan data yang dikirim persis dua kunci itu walau payload sinkron dan profil pencatat berisi penanda rahasia.
- **Token perangkat:** `PUT /api/push-token` `{"device_id","token"}` dan `DELETE /api/push-token/{deviceId}` (`auth:sanctum`, limiter `push-token` 20 per menit per akun, tanpa syarat langganan). Satu baris per (akun, `device_id`); satu token FCM hanya milik satu baris (token yang sama didaftarkan akun lain pindah ke akun itu); plafon 20 per akun. Keluar (`/auth/logout`, bila nama token API `android:<device_id>` cocok) mencabut token push perangkat itu, keluar dari semua perangkat dan hapus akun mencabut semuanya. Token `UNREGISTERED` dari FCM dibuang.
- **Yang kini terlihat server, tambahan:** token FCM per perangkat (disimpan polos karena harus dikirim ke FCM; disembunyikan dari serialisasi dan log). Google (FCM) melihat bahwa sebuah ruang menerima catatan baru dan kapan, tidak isinya. Perlu disebut di kebijakan privasi dan Data Safety (Tahap 15).
- **Belum:** pemicu `MEMBER_REMOVED`/`ROOM_KEY_ROTATED`, pengulangan kiriman yang gagal, batas laju kabar per ruang (push beruntun = kabar beruntun, diredam `collapse_key` di perangkat yang offline), uji di MySQL, dan uji terhadap FCM sungguhan.

## Metrik pengguna anonim (Tahap 9)

**Diimplementasikan 2026-11-01, sisi server saja** (`App\Support\UserMetrics`, perintah `metrik:pengguna`, tabel `user_metric_snapshots`). Jumlah pengguna, Pro, dan Sync dihitung dari server (keputusan pemilik 2026-10-24), bukan dari Firebase Analytics: `pengguna_total` = jumlah akun server (`users`), `pengguna_pro` dan `pengguna_sync` = akun yang `users.plans`-nya memuat `pro` atau `sync` (diisi `PlanSynchronizer` dari pembelian Play terverifikasi; akun seed atau uji yang diberi paket lewat `tinker` ikut terhitung).

- `php artisan metrik:pengguna` menampilkan tiga angka itu; `--simpan` juga menulis potret hari itu (tanggal UTC, satu baris per hari, dijalankan ulang di hari yang sama menimpa). Dijadwalkan harian 04:15 dengan `--simpan` di `routes/console.php`; baru berjalan bila ada cron `schedule:run` di VPS (Tahap 12).
- **Tanpa identitas dan tanpa data keuangan:** tabel `user_metric_snapshots` hanya punya `tanggal` dan tiga hitungan (dijaga tes daftar kolom); keluaran perintah tidak memuat email atau nama.
- **Tidak ada endpoint ringkasan untuk admin.** Server belum punya konsep admin atau peran operator; menambahkannya (siapa admin, lewat apa) adalah keputusan pemilik. Sampai itu, angka dibaca lewat perintah di atas atau tabelnya, langsung di VPS.
- Belum ada: akun yang hanya lokal (tanpa masuk server) tidak terhitung sama sekali, karena server tidak tahu keberadaannya; jumlah pemasangan atau pengguna aktif datang dari Play Console atau Firebase Analytics (proyek Firebase belum ada, menunggu pemilik). Belum diuji di MySQL (`whereJsonContains` pada kolom `plans`).

## Keanggotaan ruang yang dibagi (Tahap 9)

Rangkuman kontrak `Api\RoomMembershipController` (`auth:sanctum`, limiter `room-membership` 60 per menit per akun, satu jatah bersama). Pemilik ruang = anggota pertama yang tercatat untuk sebuah `room_id` (`room_members.is_owner`, keputusan 2026-10-27); anggota lain pencatat. Id ruang 1 sampai 100 karakter (sama dengan batas `room_id` di push): lebih panjang dijawab 422 `invalid_room_id` sebelum menyentuh database.

| Endpoint | Butuh Sync | Jawaban sukses | Galat (`code`) |
|---|---|---|---|
| `POST /api/rooms/{roomId}/join` (badan opsional `{"invite_hash": "<64 hex>"}`) | Ya | 204, idempoten (anggota yang sudah ada tidak butuh undangan) | 403 `sync_required`, 403 `invite_required` (non-anggota tanpa hash di ruang yang sudah berpenghuni), 403 `invite_invalid` (hash tak dikenal, sudah dipakai, atau milik ruang lain), 403 `invite_expired`, 422 `room_full`, 422 `invalid_room_id`. Ruang tanpa anggota: penggabung pertama jadi pemilik tanpa undangan |
| `POST /api/rooms/{roomId}/invites` (badan `{"invite_hash": "<64 hex>"}`) | Ya (Sync pemilik) | 201 `{"expires_at": ISO8601}`; idempoten untuk hash yang sama dan belum dipakai (masa berlaku diperbarui) | 403 `sync_required`, 403 `not_room_owner`, 409 `invite_used`, 422 validasi (hash bukan 64 heksadesimal), 422 `invalid_room_id`. Di atas 20 undangan menunggu per ruang, yang paling lama (belum dipakai) disingkirkan, tidak ditolak; yang kedaluwarsa dan belum dipakai dibuang saat penerbitan |
| `GET /api/rooms/{roomId}/members` | Ya | 200 `{"members":[{user_id,member_id,name,email,you,owner,role,invite_hash}]}`; `invite_hash` = undangan yang dipakai anggota itu, `null` untuk pembuat ruang | 403 `sync_required`, 403 `not_room_member` (pemanggil bukan anggota), 422 `invalid_room_id` |
| `POST /api/rooms/{roomId}/leave` | **Tidak** | 204 | 404 `not_room_member` (juga ulangan setelah berhasil keluar), 422 `owner_cannot_leave`, 422 `invalid_room_id` |
| `DELETE /api/rooms/{roomId}/members/{userId}` | Ya | 204 | 403 `sync_required`, 403 `not_room_owner`, 404 `member_not_found`, 422 `owner_cannot_be_removed`, 422 `invalid_room_id`; `userId` non-numerik = 404 `not_found` |
| `POST /api/rooms/{roomId}/members/{userId}/make-owner` | Ya | 204 | 403 `sync_required`, 403 `not_room_owner`, 404 `member_not_found`, 422 `already_owner`, 422 `invalid_room_id`; `userId` non-numerik = 404 `not_found` |

**Undangan terdaftar (key_grant langkah 1, 2026-11-03; hanya terbukti lewat tes SQLite, belum di MySQL atau server hidup).** `invite_hash` = SHA-256 heksadesimal dari HKDF(`member_secret`, `"rizqflow-invite"`); server tidak pernah menerima `member_secret`. Sekali pakai, kedaluwarsa 7 hari (`RoomInvite::LIFETIME_DAYS`), tanpa masa tenggang untuk ruang lama (keputusan pemilik 2026-11-03). `POST /api/sync/push` **tidak lagi mendaftarkan** non-anggota ke ruang yang sudah punya anggota: dijawab 403 `not_room_member` untuk seluruh batch (ruang tanpa anggota tetap dibuat oleh pendorong pertamanya, yang jadi pemilik). Penyimpangan dari rencana di sync-desain.md bagian F: **keanggotaan tetap dihapus fisik** (keluar/dikeluarkan), tidak diberi status `removed`, karena syarat undangan sekali pakai sudah menutup celah bekas anggota yang mendaftar ulang sendiri; kode `removed_from_room` dan kolom `revoked_at` karena itu tidak ada. Hash tidak dibedakan antara "tak pernah ada" dan "sudah dipakai" (`invite_invalid`), tetapi kedaluwarsa punya kode sendiri supaya pemilik tahu harus menerbitkan ulang. Akun pemakai yang dihapus ikut menghapus baris undangannya (cascade), jadi QR lama tidak hidup lagi. **Android belum memakai ini** (langkah 2): sampai klien mengirim `invite_hash`, anggota kedua tidak bisa bergabung ke server ini.

- **Keluar sendiri (`leave`, 2026-11-01)** hanya menghapus baris keanggotaan pemanggil. Baris sync yang pernah ia tulis tetap di server (anggota lain masih membutuhkannya; sesuai sync-desain.md bagian G "anggota keluar: baris tetap"), dan ia berhenti menerima baris ruang itu pada pull berikutnya. Tidak butuh Sync (seperti `DELETE /api/push-token`): pengguna yang langganannya berakhir tetap bisa melepaskan diri. Setelah keluar ia bisa bergabung lagi lewat undangan baru, selalu sebagai bukan-pemilik.
- **Pemilik tidak bisa keluar sebelum memindahkan kepemilikan**: kalau boleh keluar langsung, ruang tanpa pemilik dan tak seorang pun bisa mengeluarkan anggota lagi. Jawabannya 422 `owner_cannot_leave`; pemilik tunggal pun tidak bisa — ia harus memindahkan kepemilikan dulu (lihat `make-owner` di bawah), atau ruang itu akan selamanya butuh pemiliknya.
- **Pindah kepemilikan (`make-owner`, 2026-11-02)**: pemilik memindahkan `is_owner` ke anggota lain dalam satu transaksi (`lockForUpdate` atas seluruh baris anggota ruang, diurutkan `id`, supaya dua permintaan serentak tidak menghasilkan dua pemilik atau nol pemilik — SQLite di tes mengabaikan kunci baris tapi transaksinya tetap serial, MySQL produksi belum diuji). Hanya kolom `is_owner` yang berubah: TIDAK mengeluarkan siapa pun, TIDAK merotasi kunci ruang, dan TIDAK menyentuh `sync_log_entries` — pemilik lama dan baru tetap anggota dengan kunci yang sama. Target yang sudah pemilik (termasuk pemanggil memindahkan ke dirinya sendiri) ditolak 422 `already_owner`, bukan idempoten 204 — pemanggil diberi tahu eksplisit bahwa tidak ada yang berubah, konsisten dengan 422 `owner_cannot_be_removed` di `remove`. Setelah transfer sukses, pemilik LAMA bisa memanggil `leave` seperti anggota biasa (diuji eksplisit). Android: tombol "Jadikan pemilik" di `RuangKeluargaScreen` (hanya pemilik, lewat `RoomMembershipRemote.transferOwnership`), bottom sheet pilih anggota + dialog konfirmasi mengikuti pola "Keluarkan anggota". Belum diuji di MySQL (kunci baris sungguhan) atau perangkat nyata.
- **Celah yang BELUM tertutup (sama dengan anggota yang dikeluarkan):** server belum membedakan "keluar atau dikeluarkan" dari "tidak pernah bergabung". `join` tidak butuh rahasia dan `push` ke sebuah `room_id` mendaftarkan pengirimnya otomatis, jadi aplikasi yang keluar/dikeluarkan tetapi masih menyinkronkan ruang itu tercatat lagi. Menutupnya butuh status `removed` dan undangan terdaftar (sync-desain.md bagian F "Rencana distribusi keyset otomatis"), yang menunggu empat keputusan pemilik; tidak dikerjakan di sini. Klien wajib berhenti menyinkronkan ruang itu setelah `leave`.
- **Klien Android (2026-11-01, hanya terbukti lewat tes otomatis, belum diuji di perangkat atau server hidup):**
  - **Pemetaan galat per kode.** `mapHttpError` membaca `code` dari badan galat. 403 menjadi `SyncRequired` (paywall Sync) HANYA bila `code` = `sync_required` atau 403 tanpa kode sama sekali (server lama). 403 berkode lain (`not_room_member`, `not_room_owner`, `forbidden`, kode baru yang belum dikenal) menjadi `Rejected(message, code)` dan tidak pernah membuka paywall. 404 dan 422 berkode keanggotaan ruang (`not_room_member`, `member_not_found`, `owner_cannot_leave`, `owner_cannot_be_removed`, `invalid_room_id`) juga `Rejected` berkode; 404 polos tanpa kode tetap `Unexpected(404)` (rute belum ada atau proxy). Layar ruang keluarga memetakan `code` ke teks aplikasi sendiri dan tidak pernah menampilkan `message` server: "Kamu bukan anggota ruang ini", "Hanya pemilik ruang yang bisa melakukan ini", dan seterusnya. Kode tak dikenal jatuh ke pesan milik aksinya.
  - **`leave` dipanggil dari UI.** `RoomMembershipRemote.leave` + `RetrofitRoomMembershipRemote`; `SharedRoomLeaver` (`:domain`) mencabut keanggotaan lalu menandai ruang `LEFT` di `shared_room` (`SharedRoomActivation.markLeft`). 204 dan 404 `not_room_member` (ulangan setelah jawaban hilang, atau sudah dikeluarkan pemilik) sama-sama menandai `LEFT` (idempoten); 422 `owner_cannot_leave` tidak mengubah apa pun. Tombol "Keluar dari ruang" di `RuangKeluargaScreen` hanya untuk peran MEMBER menurut server; pemilik melihat penjelasan. Data lokal ruang (transaksi, kategori, kunci ruang) TIDAK dihapus; sinkron otomatis, sinkron ulang penuh, dan riwayat hanya menyentuh ruang `ACTIVE`, jadi berhenti. Ruang `LEFT` yang dibuka lagi di layar tidak melakukan `join` otomatis (layar hanya menjelaskan cara bergabung lagi); bergabung lagi lewat undangan baru memulihkan `ACTIVE`. `leave` tidak digerbang Sync di klien maupun server.
  - **429.** `Unexpected(429, retryAfterSeconds)`: jeda dari header `Retry-After`, atau `retry_after` di badan bila header tak terbaca (bukan angka bulat tidak negatif dianggap tidak ada). Layar Ruang Keluarga (muat anggota, sinkron manual, keluarkan, keluar), pemindai undangan, tombol "Sinkronkan sekarang" (`ManualSyncFeedback`), layar kode pemulihan, dan masuk email menampilkan "Terlalu sering. Coba lagi sekitar N detik/menit lagi" (menit dibulatkan ke atas), bukan "gagal" generik. Tidak ada coba ulang otomatis baru: yang menunda tetap `AutoSyncOrchestrator` (`SyncBackoff` mengikuti `Retry-After`); layar tidak memuat ulang daftar anggota setelah 429.
  - **Utang yang terlihat dari sini:** pull atau push sinkron ke ruang yang bukan lagi milik pengguna (403 `not_room_member`) kini `Rejected` berkode, bukan lagi `SyncRequired` yang keliru membuka paywall; tetapi orkestrator dan sinkron ruang bersama belum mengenalinya sebagai "ruang ini sudah tidak boleh disinkronkan" (hanya masuk backoff biasa), dan belum menandai ruang `REMOVED`. Pengguna yang langganan Sync-nya sudah berakhir tidak bisa membuka layar Ruang Keluarga (digerbang paywall), jadi tidak bisa memakai `leave` dari UI walau server mengizinkannya.

## Pull per cakupan dan waktu per cakupan (Tahap 9, sisi server)

**Diimplementasikan 2026-11-01, sisi server saja** (`Api\SyncController@pull` dan `@push`, `Api\SyncHistoryController`, `App\Support\SyncRetention`; tabel `sync_device_cursors`, `sync_scope_activity`, `sync_scope_watermarks`; migrasi `2026_11_01_000010` dan `2026_11_01_000011`; tes `tests/Feature/Sync/SyncScope*Test.php`). **Status: hanya terbukti lewat tes SQLite di memori.** Belum diuji di MySQL atau server hidup, dan **klien Android belum memakainya** (agen Android berikutnya mengadopsi lewat kontrak di bawah). Semuanya aditif: klien yang ada (pull tanpa `scope`) berjalan persis seperti sebelumnya.

### Endpoint: `GET /api/sync/pull` dengan `scope`

| Param | Arti |
|---|---|
| `scope=a` | Cakupan pribadi: baris dengan `user_id = saya` DAN `room_id` kosong. Tidak pernah memuat baris ruang, termasuk ruang yang ditulis sendiri. `room_id` dilarang |
| `scope=r` dan `room_id=X` | Satu ruang bersama: semua baris `room_id = X` dari semua anggota. Hanya untuk anggota aktif ruang X (maks 100 karakter, sama dengan push). Tidak pernah memuat data pribadi siapa pun |
| tanpa `scope` | Perilaku lama: aliran gabungan (baris sendiri plus semua ruang yang diikuti), kursor global per perangkat. `room_id` tanpa `scope` ditolak |

Param lain tidak berubah (`device_id` wajib, `after`, `limit`). Bentuk jawaban sama dengan pull lama (`entries`, `next_after`, `has_more`, `server_time_ms`) **ditambah dua bidang hanya pada pull ber-`scope`**: `scope` (`"a"` atau `"r"`) dan `room_id` (id ruang, atau `null` untuk `a`). Server lama mengabaikan param yang tidak dikenal dan menjawab aliran gabungan tanpa dua bidang itu: **klien yang meminta `scope` wajib memeriksa bidang `scope` di jawaban, dan bila tidak ada, menganggapnya server lama (jangan menerapkan barisnya sebagai satu cakupan; pakai pull lama).**

Urutan pemeriksaan: 401 tanpa token; 403 `sync_required` tanpa Sync; 422 `device_id_required`; validasi `after`/`limit` (422 `validation_failed`); 422 `invalid_scope` / `invalid_room_id`; 403 `not_room_member`; 410 `cursor_expired`.

| Status | `code` | Kapan | Yang harus dilakukan klien |
|---|---|---|---|
| 403 | `sync_required` | tanpa langganan Sync | paywall, seperti sekarang |
| 403 | `not_room_member` | `scope=r` dan akun bukan anggota ruang itu (belum pernah, keluar, atau dikeluarkan pemilik) | **bukan paywall.** Berhenti menyinkronkan ruang itu dan tandai lokal `LEFT`/`REMOVED`. `mapHttpError` Android memetakan semua 403 ke paywall kecuali `not_room_owner`: wajib dibedakan lewat `code` |
| 410 | `cursor_expired` | `after` > 0 dan di bawah watermark kompaksi cakupan itu | atur ulang kursor cakupan ITU saja ke 0 dan tarik ulang cakupan itu; cakupan lain tidak terpengaruh |
| 422 | `invalid_scope` | `scope` bukan `a`/`r`, `scope=r` tanpa `room_id`, `scope=a` dengan `room_id`, `room_id` tanpa `scope` | bug klien |
| 422 | `invalid_room_id` | `room_id` lebih dari 100 karakter | bug klien |

**`removed_from_room` belum ada.** sync-desain.md bagian G merencanakan 403 `removed_from_room` untuk anggota yang dikeluarkan, tetapi server menghapus fisik baris `room_members` saat keluar atau dikeluarkan, jadi "dikeluarkan" tidak bisa dibedakan dari "tidak pernah bergabung": keduanya `not_room_member`. Membedakannya butuh status `removed` di `room_members`, yang bagian dari keputusan pemilik soal undangan terdaftar (tidak dikerjakan). Klien sebaiknya menangani `removed_from_room` sama dengan `not_room_member` supaya tidak berubah saat kode itu ada.

**Pull tidak mendaftarkan siapa pun.** Berbeda dengan `push` (mendaftarkan pengirim ke `room_id` otomatis) dan `join`, pull per cakupan tidak memanggil `RoomMember::tambahAnggota`. **Celah join ulang bekas anggota TIDAK ditutup dan tidak diubah** (keputusan pemilik): bekas anggota yang aplikasinya masih mem-`push` atau `join` ke ruang itu tercatat lagi, dan sesudahnya pull ruang itu berhasil lagi. Klien wajib berhenti menyinkronkan ruang setelah `leave` atau `not_room_member`.

### Kursor per cakupan

- Id baris log (`after`, `next_after`, `entries[].id`) adalah **satu nomor global** yang dibagi semua cakupan dan semua akun, tetapi kursor hanya bermakna untuk cakupan yang menariknya. **Satu kursor per cakupan per perangkat; jangan memakai `next_after` sebuah cakupan sebagai `after` cakupan lain.** Cakupan sepi: kursor tetap di tempat selama tidak ada baris baru (lompatan id besar normal).
- Server mencatat kursor per `(akun, device_id, cakupan)` di `sync_device_cursors` pada setiap pull ber-`scope` (kursor = `next_after` jawaban itu; ditimpa apa adanya, termasuk turun bila klien tarik ulang dari 0 atau dari kursor lebih kecil: itu keadaan perangkat yang sebenarnya). Pull ber-`scope` **tidak** menyentuh `sync_devices` (kursor global), dan pull lama tidak menyentuh `sync_device_cursors`.
- **Halaman terakhir** (`has_more` false) memajukan `next_after` ke watermark cakupan bila baris terakhir yang dibaca lebih kecil darinya. Tanpa ini, cakupan yang tombstone terakhirnya baru dikompaksi terjebak: kursor selalu di bawah watermark, jadi tiap pull dijawab 410 dan menarik ulang dari 0 tidak pernah menyelesaikannya. Perubahan yang sama berlaku untuk pull lama (terhadap watermark global); keduanya aman karena halaman terakhir berarti seluruh sisa baris yang terlihat sudah terbaca. Klien cukup menyimpan `next_after` apa adanya.

### Kompaksi dan 410

- **Sebuah perangkat dianggap sudah melewati tombstone bila SALAH SATU kursornya sudah melewatinya**: kursor global `sync_devices` (>= id tombstone, artinya semua yang terlihat sampai id itu sudah diterima) atau kursor cakupan tombstone itu (`a` untuk tombstone pribadi akunnya, `r:X` untuk tombstone ruang X). Kesimpulannya: (1) kompaksi tidak pernah menghapus tombstone yang belum dilewati perangkat aktif mana pun yang memakai kursor per cakupan untuk cakupan itu (tes: perangkat yang tertinggal menahannya, menyusul lalu terhapus); (2) perangkat yang baru pindah ke pull per cakupan dengan kursor global yang tertinggal tidak menahan kompaksi selama kursor cakupannya sudah lewat (tanpa aturan ini ia menahan sampai dianggap tidak aktif 180 hari); (3) perangkat yang tidak punya kursor untuk cakupan itu tidak menahan cakupan itu, karena tarikan pertamanya atas cakupan itu dari 0 sehingga tidak ada keadaan lokal yang butuh tombstone; (4) untuk tombstone ruang, hanya perangkat anggota ruang yang saat ini aktif (di `room_members`) yang dihitung, jadi kursor bekas anggota tidak menahan.
- Perangkat aktif = `last_seen_at` dalam 180 hari (sama dengan sebelumnya, per kursor). Perangkat yang kembali setelah itu dengan `after` di bawah watermark cakupannya dijawab 410, lalu klien menarik ulang cakupan itu dari 0 dan menggabung.
- **Watermark per cakupan** (`sync_scope_watermarks`: kunci `a|<userId>` atau `r|<roomId>`, id tombstone terbesar yang dihapus fisik di cakupan itu, naik di transaksi kompaksi yang sama) dipakai 410 pull ber-`scope`: hanya cakupan yang tombstone-nya dikompaksi yang mengembalikan 410. Pull lama tetap memakai watermark global `sync_compaction_state` (global >= semua watermark cakupan, jadi tetap benar, hanya lebih kasar). Pilihan per cakupan menghindari tarik ulang penuh di cakupan sepi setiap ada tombstone cakupan lain yang dikompaksi.
- `sync:prune-stale-devices` kini juga membuang kursor per cakupan yang `last_seen_at`-nya lebih dari 180 hari dan catatan waktu per cakupan yang pull dan push-nya sama-sama lebih lama dari itu (keluaran tambahan: "Kursor cakupan tidak aktif dihapus: N." dan "Catatan waktu cakupan dihapus: N."). Aman dengan alasan yang sama seperti kursor global.
- Indeks baru di `sync_log_entries`: `(room_id, id)` dan `(user_id, room_id, id)` untuk pull per cakupan.

### Waktu push dan pull per cakupan: `GET /api/sync/history`

Bidang baru (bidang lama tidak diubah): `scopes` di tingkat akun, dan `devices[].scopes` per perangkat.

```json
{
  "scopes": [
    {"scope": "a", "room_id": null, "last_push_at": "2026-11-01T10:00:00Z", "last_pull_at": "2026-11-01T10:05:00Z"},
    {"scope": "r:ruang-1", "room_id": "ruang-1", "last_push_at": null, "last_pull_at": "2026-11-01T10:05:00Z"}
  ],
  "devices": [{"device_id": "...", "...bidang lama...": "", "scopes": [{"scope": "a", "room_id": null, "last_push_at": "...", "last_pull_at": "...", "cursor": 123}]}]
}
```

- Tingkat akun: **selalu** memuat cakupan pribadi dan semua ruang yang masih diikuti (waktu `null` bila belum pernah), nilai terbaru dari semua perangkat. `last_push_at` dilengkapi dari `MAX(created_at)` log milik akun itu supaya data sebelum pencatatan ada tetap terisi (tertahan oleh kompaksi: bisa lebih tua dari push sebenarnya). Ruang yang sudah ditinggalkan tidak ditampilkan.
- Per perangkat: hanya cakupan yang punya catatan untuk perangkat itu; `cursor` = kursor per cakupan perangkat itu (`null` bila belum pernah pull ber-`scope` untuk cakupan itu). `devices[].cursor` yang lama tetap kursor global. Perangkat yang hanya memakai pull ber-`scope` kini muncul dengan `active` sesuai kursor cakupan terbarunya (`last_pull_at` perangkat = yang terbaru dari kursor global dan kursor cakupan).
- **Pencatatan:** `POST /api/sync/push` mencatat `last_push_at` per `(perangkat dari entri, cakupan)` untuk cakupan yang benar-benar menerima baris (baru atau ulangan idempoten; baris yang ditolak penjaga penulis pertama atau lantai tidak dihitung; batch yang gagal kuota di-rollback bersama catatannya). Pull ber-`scope` mencatat `last_pull_at` cakupannya; **pull lama (tanpa `scope`) mencatat pribadi dan semua ruang yang diikuti**, jadi layar Riwayat sinkron terisi per cakupan bahkan sebelum klien pindah ke pull per cakupan.
- **Privasi:** hanya `user_id`, `device_id`, id ruang, dan dua waktu, semua sudah terlihat server lewat log dan kursor. Tidak ada isi, jumlah baris, id rekaman, atau nominal. Tabel ikut terhapus bersama akun (`cascadeOnDelete`).

### Kontrak klien (untuk agen Android berikutnya)

Pull per cakupan menggantikan pull gabungan. Satu siklus sinkron:

1. **Pribadi dulu.** `GET /api/sync/pull?scope=a&device_id=<D>&after=<kursor a>&limit=200`. Ulangi dengan `after = next_after` selama `has_more`. Simpan `next_after` terakhir sebagai kursor cakupan `a` (tabel `sync_cursor`, `scope = "a"`).
2. **Lalu tiap ruang bersama yang `ACTIVE` secara lokal**, satu per satu: `GET /api/sync/pull?scope=r&room_id=<X>&device_id=<D>&after=<kursor r:X>&limit=200`, dengan loop dan penyimpanan kursor yang sama (`sync_cursor.scope = "r:<X>"`). Urutannya tidak memengaruhi kebenaran; pribadi dulu mengikuti sync-desain.md bagian H.
3. **Periksa gema.** Pastikan jawaban memuat `scope` dan `room_id` yang diminta. Tanpa itu = server lama: pakai pull lama (tanpa `scope`, kursor global) dan jangan terapkan baris sebagai cakupan tertentu.
4. **Setiap cakupan punya kursor sendiri.** Jangan mengirim kursor sebuah cakupan ke cakupan lain, dan jangan memakai `next_after` pribadi untuk ruang. Perangkat harus memakai `device_id` yang sama untuk semua panggilan (sama seperti `push`).
5. **Penanganan galat per cakupan:** 410 `cursor_expired` hanya mengatur ulang kursor cakupan itu ke 0 (dan menggabung hasil, tanpa menghapus data lokal). 403 `not_room_member` pada satu ruang menghentikan ruang itu saja (tandai `LEFT`/`REMOVED`, hentikan push dan pull-nya) dan bukan paywall; 403 `sync_required` tetap paywall. Cek `code`, bukan hanya status 403. 429 mengikuti `Retry-After` (tiap panggilan masuk jatah `sync-pull` 600 per menit per akun; satu siklus butuh 1 + jumlah ruang panggilan per halaman).
6. **`push` tidak berubah.** Baris yang baru di-push tetap kembali lewat pull cakupan (gema); terapkan dengan aturan urutan total seperti biasa. Tidak ada param push baru; server mencatat waktu push per cakupan dari `room_id` dan `device_id` tiap entri.
7. **Pindah dari pull gabungan (migrasi sekali jalan).** Kursor global `G` lama berarti "semua yang terlihat sampai `G` sudah diterima". Aman: kursor pribadi `a` = `G`. **Untuk tiap ruang, mulai dari 0** (aman dan idempoten karena penerapan memakai urutan total; `G` tidak menjamin baris ruang yang baru diikuti dengan id lebih kecil dari `G` sudah diterima). Setelah itu jangan memanggil pull lama dari `device_id` yang sama. Kursor lama yang tertinggal tidak merusak apa pun: kompaksi menganggap perangkat sudah melewati bila kursor cakupannya lewat.
8. **Riwayat sinkron:** baca `scopes` (tingkat akun) dan `devices[].scopes` dari `GET /api/sync/history`; `last_push_at`/`last_pull_at` per cakupan menggantikan keterangan "per perangkat". `devices[]` lama tidak berubah.
9. **Jangan** bergantung pada `next_after` sebagai "id baris terakhir yang diterima": pada halaman terakhir nilainya bisa melompat ke watermark. Simpan saja apa adanya.

### Yang belum terbukti dan keputusan terbuka

- **Hanya tes SQLite di memori.** Sintaks SQL dibuat portabel (`COALESCE`, `upsert`, `insertOrIgnore`, tanpa fitur khusus SQLite) tetapi belum dijalankan di MySQL: `upsert` Laravel (`ON DUPLICATE KEY UPDATE`), subquery `EXISTS` bersarang di kompaksi, dan indeks baru belum terbukti di MySQL. Tidak ada uji beban; pull ber-`scope=r` memakai indeks `(room_id, id)`, pull lama masih `OR` dan tidak diubah.
- **Collation MySQL pada `room_id`:** kolom `sync_log_entries.room_id` dan `room_members.room_id` memakai collation bawaan MySQL (tidak peka huruf besar-kecil), jadi `room_id=ABC` dan `abc` dianggap ruang yang sama oleh pemeriksaan keanggotaan dan pull (sama seperti pull lama). Aman selama id ruang berupa UUID acak; belum diuji dan bukan hal yang diubah di sini.
- **Balapan penulisan kursor:** dua pull bersamaan dari perangkat yang sama menimpa kursor dengan nilai yang terakhir menulis (bukan maksimum). Disengaja (kursor harus bisa turun), tetapi satu pull yang lambat bisa menurunkan kursor sementara; akibatnya hanya kompaksi lebih konservatif, bukan data hilang.
- **Jendela balapan kecil** antara pull yang membaca `after` dan kompaksi yang berjalan bersamaan (juga ada pada pull lama dan lantai per rekaman) tidak dibuktikan di MySQL.
- **Kursor lompat ke watermark pada halaman terakhir** dianggap aman karena ditaruh hanya setelah seluruh baris terlihat terbaca, dan id di bawah watermark adalah tombstone yang usianya >= 400 hari; baris yang baru di-commit dengan id lebih kecil dari baris yang sudah dibaca (urutan commit MySQL tidak selalu sama dengan urutan id auto-increment antar akun) adalah risiko lama pull berbasis id yang tidak diubah di sini.
- **Dikeluarkan lalu tetap menulis:** lihat "Pull tidak mendaftarkan siapa pun" di atas; menunggu empat keputusan `key_grant` di sync-desain.md bagian F.
- **Keputusan pemilik yang relevan:** kapan pull lama (tanpa `scope`) dihapus; status `removed` untuk `removed_from_room`; dan apakah `last_push_at` tingkat akun cukup, atau Android perlu `?device_id=` untuk hanya melihat perangkat ini (saat ini cukup `devices[].scopes` yang dicocokkan dengan `device_id` ponsel itu sendiri).

## Kode galat JSON dan batas laju (Tahap 9, pengerasan sisi server)

**Diimplementasikan 2026-11-01** (`App\Support\ApiErrorResponses`, hook `respond` di `bootstrap/app.php`; limiter di `AppServiceProvider`). **Status: hanya terbukti lewat tes SQLite memori.**

Galat `/api/*` berbentuk `{"message": "...", "code": "...", ...}`. `message` untuk manusia (Indonesia), `code` stabil untuk percabangan klien. Galat buatan controller membawa kodenya sendiri (mis. `device_id_required`, `quota_exceeded`, `no_recovery_key`, `not_room_owner`, `sync_required`); galat dari pengecualian Laravel diseragamkan:

| Status | `code` | Catatan |
|---|---|---|
| 401 | `unauthenticated` | tanpa token, kedaluwarsa, atau dicabut |
| 403 | `forbidden` | hanya 403 tanpa kode sendiri |
| 404 | `not_found` | rute atau data tak ada (404 buatan controller membawa kode sendiri, mis. `no_recovery_key`) |
| 405 | `method_not_allowed` | |
| 413 | `payload_too_large` | |
| 422 | `validation_failed` | isian `errors` bawaan Laravel tetap ada; 422 buatan controller punya kode sendiri |
| 429 | `rate_limited` | plus `retry_after` (detik) dan header `Retry-After` |
| 5xx | `server_error` | |

Saat `APP_DEBUG` aktif pesan asli tidak diganti (jejak galat tetap terbaca), tetapi `code` tetap ditambahkan; produksi wajib `APP_DEBUG=false`. Perubahan ini aditif: isian lama, status, dan header tidak berubah. Dua kontrak yang memang berubah: 403 "butuh Sync" kini membawa `code` `sync_required`, dan 404 rute-tak-ada kini membawa `not_found` (bukan lagi tanpa `code`; klien pemulihan kunci hanya memercayai `no_recovery_key`, jadi tidak terpengaruh).

**Batas laju per akun** (kunci akun, bukan IP, jadi tidak bergantung `trustProxies`; sengaja sangat longgar karena hanya pagar terhadap klien yang berputar, sinkron wajar jauh di bawahnya): `POST /api/sync/push` 120 per menit (limiter `sync-push`), `GET /api/sync/pull` dan `GET /api/sync/history` 600 per menit (`sync-pull`), rute keanggotaan ruang 60 per menit (`room-membership`). Sinkron pertama akun pada kuota penuh (250.000 baris = 500 pull) tetap muat dalam satu menit. Klien yang kena 429 cukup mencoba lagi sesudah `Retry-After`; kursor tidak maju sehingga tidak ada data hilang. **Klien Android menangani 429 sejak 2026-11-01** (`Unexpected(429, retryAfter)` dan pesan lembut "terlalu sering"; lihat bagian "Keanggotaan ruang yang dibagi", butir Klien Android); batas lain yang sudah ada (login, refresh token, klaim pembelian, kunci pemulihan, token push, RTDN) tidak diubah.

## Interoperabilitas amplop sinkron dengan klien non-Android (Tahap 9/14)

**Diimplementasikan 2026-11-01** (`server/tests/Unit/SyncEnvelopeInteropTest.php`, kelas acuan `server/tests/Support/SyncEnvelopeReference.php`; 26 tes, 93 asersi). Implementasi PHP murni (openssl AES-256-GCM, tanpa Tink) membaca dan menulis byte yang sama dengan vektor emas `domain/src/test/resources/sync/golden/envelope.v1.json`: wireId termasuk id unicode, AAD kanonis termasuk cap waktu `Long.MAX_VALUE`, bingkai berpengisi 256 byte, dekripsi ciphertext vektor, dan ciphertext identik byte demi byte dari PHP. Mengubah satu bidang kolom luar (scope, cap waktu, `deleted`, perangkat, wireId), membalik satu byte, atau mengganti kunci, IV, atau tag membuat otentikasi gagal. Untuk `shared_expense.v1.json` (tanpa ciphertext): wireId, AAD hidup dan tombstone, dan JSON amplop yang dibangun PHP sama persis dengan plaintext Android (urutan kunci, tanpa escape unicode; tombstone memakai objek kosong `{}`, di PHP harus `stdClass`, bukan array kosong yang menjadi `[]`), ditambah bolak-balik enkripsi.

**Batas jujur (jangan dibesar-besarkan):** vektor itu dibuat dengan AES/GCM/NoPadding bawaan JDK, **bukan format kabel Tink**. Tes ini membuktikan AAD, wireId, bingkai, dan AES-GCM murni (IV 12 byte terpisah, `ciphertext || tag`). Ia **tidak** membuktikan bahwa klien web bisa membaca blob nyata dari aplikasi: aplikasi mengenkripsi lewat Tink AEAD, yang menambahkan awalan kunci 5 byte dan menaruh IV di depan ciphertext, dan kuncinya keyset Tink (bukan 32 byte mentah). Sebelum Tahap 14 membaca data nyata, salah satu harus dibuktikan terpisah: parser format kabel Tink di peramban dengan vektor yang dibuat lewat Tink sungguhan, atau AEAD murni di sisi Android. Vektor `shared_expense.v1.json` tidak memuat ciphertext, jadi untuknya hanya plaintext, wireId, dan AAD yang dibandingkan lintas bahasa. Kelas acuan sengaja di `tests/Support`, bukan `app/`: server tidak boleh bisa mendekripsi data keuangan. Hanya PHP/openssl lokal; JavaScript (WebCrypto) belum dicoba.

## Belum diputuskan

- ~~**Firebase Analytics** dipakai atau cukup Play Console plus data akun server.~~ **Diputuskan pemilik 2026-10-24: Firebase Analytics dan FCM dipakai di rilis 1.** Aturan: tidak ada data keuangan (nominal, nama, catatan, akun) di event atau properti pengguna; keduanya disebut di kebijakan privasi dan Data Safety; jumlah pengguna, Pro, dan Sync tetap dihitung dari server.
- Skema Room perlu penanda waktu ubah, penanda hapus, dan pengenal perangkat di hampir semua tabel (menyentuh model data; lihat [model-data.md](model-data.md) saat dikerjakan).
