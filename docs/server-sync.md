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

## Belum diputuskan

- ~~**Firebase Analytics** dipakai atau cukup Play Console plus data akun server.~~ **Diputuskan pemilik 2026-10-24: Firebase Analytics dan FCM dipakai di rilis 1.** Aturan: tidak ada data keuangan (nominal, nama, catatan, akun) di event atau properti pengguna; keduanya disebut di kebijakan privasi dan Data Safety; jumlah pengguna, Pro, dan Sync tetap dihitung dari server.
- Skema Room perlu penanda waktu ubah, penanda hapus, dan pengenal perangkat di hampir semua tabel (menyentuh model data; lihat [model-data.md](model-data.md) saat dikerjakan).
