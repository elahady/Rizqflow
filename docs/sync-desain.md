# Desain sinkron entitas penuh, hapus, keluarkan anggota, dan sync otomatis

**Status: USULAN Claude, 2026-10-25. Belum dikonfirmasi pemilik.** Dokumen ini menjadi acuan implementasi tiga butir Tahap 9 ("Sinkron entitas selain pengeluaran", "Sinkron penghapusan di ruang yang dibagi", "Keluarkan anggota: kirim keyset baru ke anggota yang tetap") plus "Sync berjalan otomatis". Bagian J memuat keputusan yang harus diambil pemilik; sampai dijawab, bagian terkait dikerjakan dengan rekomendasi dan ditandai usulan. Server belum di-deploy dan belum ada pengguna sungguhan, jadi mengubah protokol sekarang murah dan sesudah rilis mahal.

Dasar: `SyncEngine`, `SyncController`, entitas Room v7, `AccountWorkspace`, [server-sync.md](server-sync.md), [model-data.md](model-data.md). Desain ini hasil pembacaan kode, belum ada yang dijalankan.

## 0. Temuan dari kode yang mengubah desain

Cacat atau celah pada kode sekarang. Semuanya bisa diperbaiki sebelum rilis.

1. **Akun pasangan dicocokkan ke akun lokal penerima** (`SyncEngine.kt` sekitar baris 138-140). Bila namanya sama ("Tunai"), pengeluaran pasangan mengurangi saldo akun penerima; bila beda, baris pasangan dilewati dan tidak pernah sampai. Keduanya melanggar aturan 2026-10-19 (anggota melihat nama akun sumber, bukan saldonya). Perbaikan: nama akun pasangan menjadi label akun anggota virtual (bagian B dan E).
2. **Pull tidak difilter ruang.** `SyncController` mengembalikan aliran global milik akun, sedangkan kursor klien per ruang; `SyncEngine` memakai argumen `roomId`, bukan `downloaded.roomId`, sehingga baris ruang lain dicoba didekripsi dengan kunci ruang ini lalu dihitung `skipped`. Saat pribadi dan bersama berbagi aliran ini tidak layak: pull wajib punya filter cakupan.
3. **Pull melewati baris sendiri berdasarkan device id.** Kursor ada di SharedPreferences yang tidak per akun, sedangkan restore berkas DB (`BackupFileService.overwrite`) memundurkan data tetapi kursor dan `device_id` tetap maju: baris di antara backup dan kursor tidak pernah ditarik ulang dan dianggap "sudah milikku". Perbaikan: kursor dan status sync pindah ke database akun yang sama, dan echo tidak dilewati lewat device id (LWW idempoten sudah cukup).
4. **Balapan `markSynced`.** Edit yang terjadi saat push berjalan bisa tertandai "sudah terkirim" padahal versi baru belum dikirim. Perbaikan: bersihkan penanda dengan syarat `changed_at` masih sama dengan yang dikirim.
5. **Tombstone diterapkan tanpa LWW.** Tombstone basi bisa menghapus edit yang lebih baru.
6. **`deleted`, `changed_at`, `record_type` ada di luar enkripsi** dan AAD hanya id: server atau penyerang bisa memutar ulang versi lama dengan cap waktu palsu, memalsukan tombstone, atau menukar tipe. Server memang bisa membuang data (tak terhindarkan); tujuannya server tidak bisa memalsukan atau mengubah urutan.
7. **Server:** `push` bukan satu transaksi dan tidak idempoten (ack hilang lalu kirim ulang menggandakan baris; gagal di tengah batch meninggalkan separuh baris); `changed_at` bertipe `timestamp` presisi detik (LWW dengan floor +1 ms butuh milidetik; MySQL TIMESTAMP berhenti di 2038); `join` tidak butuh rahasia (siapa pun dengan Sync bisa menjadi anggota ruang mana pun yang id-nya ia tahu); tidak ada endpoint keluarkan atau keluar, tidak ada konsep pemilik ruang, tidak ada kuota.
8. **Impor kunci menimpa** keyset (`importRoomKey`, `importAccountKeyFromRecovery`): tidak aman begitu ada data yang dienkripsi dengan kunci yang hilang.
9. **Bungkusan pemulihan tidak bisa diperbarui tanpa kode 24 kata** (kode tidak pernah disimpan), sehingga bungkusan versi 2 yang memuat kunci ruang basi setiap ada ruang baru atau rotasi.

## A. Inventaris: apa yang disync

Dua cakupan (scope):

- **Pribadi** (`scope=a`): semua data milik satu pengguna, dienkripsi kunci akun, `room_id` null di server, **id dipertahankan**.
- **Ruang bersama** (`scope=r:<roomId>`): proyeksi turunan, dienkripsi kunci ruang, hanya berisi yang memang boleh dilihat anggota.

| Entitas Room | Cakupan | Catatan |
|---|---|---|
| `room` | Pribadi | Id, nama, urutan, arsip, `giving_mode` per pengguna. Nama ruang bersama hanya dari undangan; urutan, warna, arsip tetap milik masing-masing anggota |
| `account` | Pribadi | Saldo tidak disimpan: hanya `opening_balance`, nama, jenis, arsip, urutan, `last_reconciled_on`. Tidak pernah masuk cakupan bersama |
| `category` | Pribadi | Di cakupan bersama TIDAK ada rekaman kategori; nama kategori dibawa di payload pengeluaran |
| `allocation_rule` + `app_setting.allocation_mode` + `cap_amount` | Pribadi | Satu rekaman singleton `allocation_config` (mode, persen per ruang, batas); atomik karena total wajib 100% |
| `money_transaction` semua jenis (`INCOME`, `EXPENSE`, `TRANSFER`, `LOAN_*`) | Pribadi | Pemasukan menyertakan potret `allocation_entry` di dalam payload (satu rekaman atomik) |
| `money_transaction` `EXPENSE` di ruang bersama aktif | Pribadi dan bersama | Proyeksi `shared_expense`, id transaksi sama, cakupan beda. Pemasukan, transfer, pinjaman tidak pernah masuk cakupan bersama |
| `debt`, `debt_payment` | Pribadi | Pembayaran rekaman terpisah berurut setelah utang |
| `bill`, `recurring_rule` | Pribadi | Transaksi hasilnya sudah punya id deterministik (`bill-<id>-<jatuh tempo>`, `rec-<id>-<tanggal>`), jadi dua perangkat yang menjalankan `runDue` konvergen |
| `zakat_profile`, `wealth_item`, `wealth_check`, `zakat_payment`, `gold_price` (hanya `MANUAL`) | Pribadi, gelombang akhir | Janji "Ganti HP? Datamu tetap bisa dibawa" dan rekap zakat web membutuhkannya. `AUTO` per perangkat (v1.1) |
| `trader_profile`, `dca_plan` | Pribadi, gelombang akhir | Kunci utama `room_id`, jadi `record_id` = id ruang |
| `quick_favorite`, `day_check` | Pribadi, opsional | `use_count` dan `last_used_at` tidak ikut; `day_check` id `d:<epochDay>` |
| `app_setting` lain (pengingat, tema, penanda `*_notified_*`, onboarding) | TIDAK | Milik perangkat. Pengecualian: `allocation_mode` masuk `allocation_config` |
| `allocation_entry` | Tidak berdiri sendiri | Tertanam di pemasukan |

Rekaman baru: `room_key` (keyset ruang plus rahasia pasangan, pribadi, dienkripsi kunci akun; bagian F) dan `key_grant` (bersama, khusus rotasi).

Satu rekaman bisa pindah ruang. Di cakupan pribadi itu hanya ubah field `room_id`; cakupan bersama diturunkan dari keadaan: pengeluaran masuk ke ruang bersama R menerbitkan `shared_expense` live di `r:R`; pindah keluar, dihapus, atau ruang berhenti dibagi menerbitkan tombstone di `r:R`; pengeluaran di ruang pribadi tidak pernah menerbitkan apa pun di `r:*` (dijaga tes properti).

## B. Identitas

- **R1, pribadi.** `record_id` = id lokal. Referensi di payload berupa id. TANPA fallback nama (menutupi bug dan bisa menggabung dua akun berbeda). Referensi yang belum ada berarti ditunda.
- **R2, bersama.** Identitas rekaman = id transaksi milik penulis. Atribut dikenali lewat nama: kategori dicari atau dibuat per ruang berdasarkan nama ternormalisasi (NFKC, trim, rapatkan spasi, huruf kecil `Locale.ROOT`); kategori sistem `Zakat mal` dan `Tak terlacak` dicocokkan lewat nama; nama kosong menjadi `Lain-lain`; penerima MEMBUAT kategori yang belum ada (bukan jatuh ke `Lain-lain`). Akun: label nama + penulis menjadi **akun anggota virtual** (E), bukan akun penerima.
- **R3, penulis tunggal (cakupan bersama).** Rekaman dimiliki penulis pertama (`user_id` server yang pertama terlihat). Karena semua anggota memegang kunci yang sama, kolom `author` di payload bisa dipalsukan; hanya penulis awal yang boleh mengubah atau menghapus. Baris dari `user_id` lain untuk id yang sama diabaikan dan dicatat. Server mengirim `author` (id anggota buram) di tiap baris pull.
- **Tabrakan nama di cakupan pribadi:** terapkan apa adanya dan toleransi nama ganda; jangan gabung otomatis ("Gabungkan akun" fitur terpisah nanti).
- **Referensi belum tiba:** urutan penerapan per halaman pull `room → account → category → allocation_config → transaction → debt → debt_payment → bill → recurring_rule → zakat_* → trader/dca → favorit/day_check`, lalu `changed_at`, lalu id server; pada peringkat sama tombstone sesudah baris live. Sisanya masuk `sync_issue(DEFERRED_MISSING_REF)` dan dicoba lagi di akhir sync dan sync berikutnya; setelah 30 hari atau 20 percobaan tetap tersimpan dan tampil di "Riwayat sinkron".
- **Perangkat baru:** pull cakupan pribadi dari 0 menghasilkan id identik. Onboarding bawaan yang membuat ruang acak di perangkat baru adalah sumber duplikat: deteksi "ruang kerja masih murni" lalu ganti dengan hasil server, atau tanya Gabung/Pulihkan (dengan salinan otomatis).

## C. Format

- **Satu amplop umum + penangan per tipe.** Satu `SyncCodec` JSON UTF-8 netral bahasa (web nanti harus bisa membaca). Per tipe ada DTO di `:data` dan `SyncRecordHandler` di `:domain` (`collectDirty(scope)`, `apply(scope, decoded)`); tahap baru cukup menambah handler.
- **Plaintext:** `[2 byte panjang][JSON][nol pengisi sampai kelipatan 256 byte]`, JSON `{"v":1,"type":"transaction","id":"<id lokal>","d":{...}}`. Penerima memeriksa `SHA-256("rizqflow-wire|"+type+"|"+id) == wireId`. Pengisi menyamarkan ukuran.
- **Versi:** bidang baru hanya opsional dalam versi yang sama; `v` naik hanya bila makna berubah; dekoder lama dipertahankan selamanya dan digembok golden test (`src/test/resources/sync/golden/<tipe>.v<N>.json`); `ignoreUnknownKeys`. `v` lebih tinggi dari yang dikenal: jangan terapkan separuh, catat `sync_issue(UNSUPPORTED_VERSION)` dan tampilkan "perbarui aplikasi"; setelah aplikasi naik, pull ulang penuh (aman karena idempoten).
- **Batas:** payload <= 64 KB sebelum base64; satu push <= 200 baris dan <= 800 KB (`client_max_body_size` Nginx bawaan 1 MB belum dicek, Tahap 12); server menolak total badan berlebih dengan 413.
- **Kolom luar (cleartext):** `record_type` = konstanta `"r"`; `record_id` (wire) = base64url(SHA-256("rizqflow-wire|"+type+"|"+id)) 22 karakter pertama (menyembunyikan awalan id deterministik dan tipe; rantai versi per rekaman tetap terkait, dibutuhkan kompaksi); `room_id` untuk cakupan bersama; `deleted` (hanya petunjuk server); `changed_at_ms` (BIGINT baru).
- **AAD kanonis** (string, sama di Kotlin, PHP, JS): `rizqflow-sync/1|<scope>|<wireId>|<changedAtMs>|<deleted 0/1>|<deviceId>` dengan `scope` = `a` atau `r:<roomId>`. Mengikat scope, wireId, stempel, `deleted`, dan perangkat sehingga memindahkan ciphertext ke cakupan lain, menempelkan tipe lain, memutar ulang versi lama dengan stempel baru, atau memalsukan tombstone gagal dekripsi. Penerima membangun AAD dari kolom luar; selisih = gagal dekripsi, dicatat `TAMPERED_OR_CORRUPT`, baris dilewati.

## D. Konflik

- **Urutan total** = `(changed_at_ms, tombstone menang, deviceId leksikografis terbesar)`. Bila `meta.order >= incoming.order` tidak lakukan apa pun (echo, duplikat, urutan balik aman); bila masuk menang terapkan lalu simpan `changed_at`, `deleted`, `dirty=0`; bila masuk menang atas edit lokal yang masih kotor, edit lokal kalah dan dicatat `sync_issue(OVERWRITTEN)` dengan versi yang kalah.
- **Jam salah:** jam hibrida ringan `SyncClock`: `stamp = max(jamPerangkat + offsetServer, changed_at_rekaman_lokal + 1)`. Floor per rekaman memuat versi jauh yang pernah dilihat sehingga edit setelah melihat versi V selalu menang atas V walau jam tertinggal. Offset dari `server_time_ms` (aditif) di `app_setting` `sync.clock_offset_ms`. Lamport counter penuh tidak perlu.
- **Hapus vs edit bersamaan:** LWW murni (edit bercap lebih baru menghidupkan kembali rekaman); urungkan hapus adalah simpan ulang id yang sama dengan cap baru. Tombstone yang tidak bisa diterapkan karena FK `RESTRICT` (mis. `zakat_payment.transaction_id`) masuk `sync_issue(DEFERRED_FK_RESTRICT)`.
- **Retensi tombstone:** mengubah keputusan 2026-10-19 secara terbatas (keputusan pemilik #6): `sync:compact` boleh menghapus fisik versi yang sudah kalah (>= 90 hari) dan tombstone >= 400 hari yang sudah melewati kursor semua perangkat aktif, hanya dari kolom luar tanpa membaca isi. Server mencatat kursor per perangkat di `sync_devices`; perangkat tak terlihat > 180 hari di-pull dengan 410 `cursor_expired` dan klien pull ulang dari 0 lalu menggabung (tidak menghapus apa pun lokal; lokal yang tak ada di server diterbitkan ulang).

## E. Skema Room v8 dan migrasi

Semua aditif. `synced_at` v7 dibiarkan (deprecated, tidak dipakai; SQLite API 26 tidak bisa `DROP COLUMN`).

| Tabel | Kolom penting |
|---|---|
| `sync_record` (PK `scope, record_type, record_id`) | `changed_at`, `deleted`, `dirty`, `pushed_changed_at`, `author` NULL, `device_id` TEXT NULL (perangkat penulis versi ini; null = perangkat ini; dibutuhkan aturan urutan total bagian D, ditambahkan saat implementasi Tahap 1 2026-10-25); indeks `(scope, dirty)`. Rumah tombstone dan semua stempel versi (juga hasil pull) untuk floor jam |
| `sync_cursor` (PK `scope`) | `after_id`; pindah dari SharedPreferences ke DB akun yang sama |
| `sync_issue` | `scope`, `record_type`, `record_id`, `server_id`, `kind` (`DEFERRED_MISSING_REF`, `DEFERRED_FK_RESTRICT`, `DEFERRED_KEY_MISSING`, `UNSUPPORTED_VERSION`, `TAMPERED_OR_CORRUPT`, `OVERWRITTEN`), `detail`, `raw_row`, `attempts`, `created_at`; dibatasi ±2000 baris dan 90 hari |
| `shared_room` (PK `room_id` FK `room` CASCADE) | `role` (`OWNER`/`MEMBER`), `status` (`ACTIVE`/`REMOVED`/`LEFT`), `joined_at`; tanpa rahasia |

Kolom/nilai baru: `account.shared_from` TEXT NULL; `TransactionOrigin.SHARED` (TEXT, aditif). **Akun anggota virtual**: baris `account` dengan id deterministik, `shared_from` terisi, nama "label (penulis)"; hanya agar FK `NOT NULL RESTRICT` tetap utuh dan query `spentPerRoom` serta riwayat bekerja. Wajib disaring (`shared_from IS NULL`) di `activeAccounts`, pemilih akun, daftar akun, saldo, rekonsiliasi, kekayaan bersih, ekspor CSV pribadi; tes jaga: saldo akun nyata tidak pernah berubah karena baris `SHARED`.

**Hook, bukan trigger.** Penanda kotor ditulis di satu titik per repositori (`SyncRecorder` dipanggil di transaksi Room yang sama dalam `Local*Repository`). Trigger SQL ditolak (`INSERT OR REPLACE`/`@Upsert`, cascade, dan pengamanan "sedang menerapkan dari sync" rumit dan tak terlihat). Penerapan hasil pull memakai konteks `applying` yang tidak menandai kotor. Tes cakupan membaca daftar entitas dari `RizqflowDatabase` dan gagal bila ada tabel tanpa handler atau daftar kecuali eksplisit. Kategori, edit transaksi, dan penghapusan (termasuk cascade ruang -> kategori, utang -> pembayaran) wajib melewati recorder; ganti nama kategori di ruang bersama menandai transaksi di dalamnya kotor.

**Migrasi 7 -> 8 (non-destruktif):** `MIGRATION_7_8` membuat empat tabel + indeks (pernyataan sama persis dengan `createSql` di `8.json`) dan `ALTER TABLE account ADD COLUMN shared_from TEXT DEFAULT NULL`, tanpa menulis ulang data; salinan berkas `.db` ke `.premigrate` sebelum membuka DB dengan `user_version < 8` (hapus setelah 3 pembukaan sukses); backfill di kode (`SyncBootstrap.markAllDirty()` sekali saat sync pertama diaktifkan, idempoten, dalam batch); tes migrasi (pola `MigrationTest.kt`) yang membangun berkas v7 dari `7.json`, mengisi data di SEMUA tabel, menjalankan `MIGRATION_7_8`, lalu memeriksa jumlah baris per tabel sama, `PRAGMA foreign_key_check` kosong, `integrity_check` ok, tabel baru kosong, skema hasil migrasi sama dengan skema dari nol (`8.json`), dan data uji 5000 baris acak tidak berubah.

## F. Kunci

- **Pemetaan:** pribadi -> kunci akun (`AccountKeyProvider`, kini mulai dipakai); ruang bersama -> kunci ruang.
- **Gerbang kunci akun (usulan):** baris pertama cakupan pribadi baru boleh dikirim bila `RecoveryStatus == Configured`. Perangkat baru yang menemukan salinan pemulihan di server wajib menawarkan Pulihkan sebelum membuat kunci acak dan mengirim apa pun.
- **Pulihkan menjaga data lama:** ubah kontrak `importAccountKeyFromRecovery` dari menimpa menjadi **gabung (merge)**: keyset hasil pulihan masuk ke keyset lokal, kunci pulihan jadi primary, kunci lokal tetap ada untuk membaca (verifikasi API Tink 1.24 saat implementasi; cadangan pola proto seperti `rotateRoomKey`). Bila perangkat sudah sempat mengirim baris dengan kunci acak, setelah gabung tandai SEMUA rekaman lokal kotor dan reset kursor ke 0 supaya diterbitkan ulang dengan kunci pulihan.
- **Kunci ruang dan bungkusan pemulihan:** (A) bungkusan versi 2 berisi `{aeadKeyset, roomKeys[]}`: basi dengan sendirinya, tidak direkomendasikan; (B) terima undang ulang lewat QR; **(C, rekomendasi) rekaman `room_key` di log pribadi**: keyset ruang (+ rahasia pasangan) diterbitkan sebagai rekaman pribadi yang dienkripsi kunci akun; bungkusan tetap versi 1; selalu segar dan ikut rotasi.
- **Rotasi dan keluarkan anggota ke anggota yang tetap:** (1) QR ulang; (2) kunci publik per anggota (infrastruktur baru, rawan MITM tanpa verifikasi sidik jari); **(3, rekomendasi) rahasia pasangan dari QR**: QR undangan (v3) memuat juga `member_secret` acak 32 byte per anggota dibuat pemilik; saat rotasi pemilik menerbitkan `key_grant` = AEAD(`member_secret`, keyset baru, AAD `grant|roomId|epoch|memberId`) di log ruang; anggota yang tetap mengambilnya; yang dikeluarkan tidak punya baris. Tanpa primitif baru. Hanya pemilik yang mengundang dan mengeluarkan di v1.
- **Alur keluarkan:** (1) pemilik menandai anggota `removed` di server (pull berikutnya 403 `removed_from_room`); (2) pemilik `rotateRoomKey`, mengekspor keyset, dan mendorong grant untuk tiap anggota tetap sebelum baris lain (urutan id server menjamin grant tiba lebih dulu); (3) anggota tetap mengimpor secara **merge**; (4) baris yang tak terbaca sebelum grant masuk `sync_issue(DEFERRED_KEY_MISSING)` dan dicoba lagi; (5) data lama yang sudah ditarik anggota yang dikeluarkan tetap bisa ia baca (UI harus jujur); baris lama tidak dienkripsi ulang.

## G. Server

Tanpa mematahkan klien dev yang ada (param opsional atau endpoint baru):

- **Push:** satu `DB::transaction`; idempoten lewat unique `(user_id, device_id, record_id, changed_at_ms, deleted)` dengan insert-ignore dan kembalikan id yang sudah ada; batas total badan (413); kolom baru `changed_at_ms` BIGINT (migrasi baru, bukan edit migrasi lama; hindari batas TIMESTAMP 2038); respons menambah `server_time_ms`.
- **Pull:** param opsional `scope=a|r` dan `room_id` (`a` = `user_id = saya AND room_id IS NULL`; `r` = `room_id = X` dan anggota aktif; tanpa param = perilaku lama); baris menambah `changed_at_ms` dan `author`; indeks `(room_id, id)` dan `(user_id, room_id, id)`; kursor per perangkat di `sync_devices`; 403 `removed_from_room`; 410 `cursor_expired`.
- **Filter per jenis:** tidak ada (server tidak boleh tahu jenis); klien menyaring.
- **Ruang bersama:** tabel `shared_rooms` (`room_id` PK, `owner_user_id`, `join_secret_hash`, `key_epoch`) dan `room_members` + `role`, `status`, `removed_at`; `POST /api/rooms`, `POST /api/rooms/{id}/join` wajib `join_secret`, `DELETE /api/rooms/{id}/members/{memberId}` (hanya pemilik), `POST /api/rooms/{id}/leave`, `GET members` menambah `member_id`, `role`, `status`; ruang lama tanpa baris `shared_rooms` diperlakukan seperti sekarang (grandfather).
- **Kuota per akun (usulan):** 250.000 baris, 150 MB payload, 64 KB per baris, 60 push per menit; 422 `quota_exceeded`.
- **Nasib baris:** akun dihapus: baris pribadi ikut (cascade), baris bersama yang ditulisnya ikut (usulan; anggota lain tetap punya salinan lokal tetapi perangkat baru mereka tidak bisa memulihkannya); anggota keluar: baris tetap, status non-aktif; ruang tanpa anggota aktif dibersihkan `sync:compact`.
- **Kompaksi:** `sync:compact` dan `sync:prune-stale-devices` di `routes/console.php` (butuh cron `schedule:run` di VPS, Tahap 12).

## H. Sync otomatis

- **Pemicu (tanpa penjadwal latar, tanpa FCM):** (1) aplikasi masuk ke depan (efek yang sudah ada di `MainHost`, setelah `runDue` dan refresh token; jeda minimum 60 detik); (2) setelah penyimpanan lokal yang menambah baris kotor, debounce 10 detik; (3) tombol "Sinkronkan sekarang" tetap dan mengabaikan backoff; (4) sekali upaya terbaik saat masuk latar (`NonCancellable`, batas ±5 detik).
- **Satu penerbangan:** satu `Mutex` per `AccountWorkspace` (pola `TokenRefresher`); pemicu otomatis bergabung ke run yang berjalan; cakupan diproses berurutan: pribadi, lalu tiap ruang bersama aktif.
- **Backoff:** `Unreachable`/5xx berlipat dua dari 30 detik sampai 15 menit, direset oleh sukses atau sync manual; 429 mengikuti `Retry-After`; `Unauthorized` berhenti sampai sambung ulang; 403 butuh Sync berhenti dan menandai ajakan berlangganan. Kegagalan sync tidak pernah menyentuh atau memblokir data lokal.
- **Cegah loop:** penerapan hasil pull tidak menandai kotor; echo tidak dilewati melainkan hasil LWW; tes: pull lalu push menghasilkan 0 unggahan. FCM nanti hanya pemicu "tarik sekarang" lewat jalur yang sama.

## I. Tahapan implementasi

Ukuran: S kecil, M sedang, L besar. Tiap tahap diuji dan di-commit sendiri.

1. **Fondasi (L, paling berisiko).** Skema Room v8 (E), `SyncClock`, `SyncEnvelope` (AAD, bingkai, id wire), `SyncRecordHandler`, `SyncStore`, `SyncIssue`, `TransactionOrigin.SHARED`, recorder di repositori, salinan `.premigrate`. `SyncEngine` lama tetap jalan. Tes: migrasi (E), golden amplop dan AAD, jam properti, tes cakupan handler, recorder (kotor vs `applying`), `.premigrate`.
   **Status: selesai di kode 2026-10-25 (belum terbukti di perangkat).** Hanya terbukti lewat tes otomatis (JVM dan Robolectric; terverifikasi paksa, 0 gagal: `:domain` 855 menjadi 902, `:data` 243 menjadi 330, `:app` 356 tetap). Kelas: `PreMigrateGuard`, `MIGRATION_7_8` dan `8.json`, `SyncRecorder`, `RoomSyncStore`/`SyncStore`, `SyncBootstrap`, `SyncCoverage` di `:data`; `SyncClock`, `SyncEnvelope`, `SyncOrdering`, `SyncModel`, `SyncRecordHandler` di `:domain/sync`. Yang menyimpang dari desain di atas:
   - **Kolom `device_id` di `sync_record`** (bagian E): tidak ada di daftar awal tetapi dibutuhkan aturan urutan total (bagian D).
   - **`SyncEngine` lama berubah perilaku satu hal**: pengeluaran pasangan hasil pull kini `origin = SHARED` (sebelumnya `IMPORT`) supaya `SyncBootstrap.markAllDirty` tidak menerbitkannya sebagai data pribadi. Tidak ada pembaca `origin` di UI, query, atau laporan. Baris pasangan lama yang sudah bertanda `IMPORT` tidak dimigrasikan (belum ada pengguna sungguhan; perlu keputusan bila ada sebelum rilis).
   - **Pagar `.premigrate` dipasang lewat `openHelperFactory`** (`PreMigrateGuard.openHelperFactory()` di `LocalLedger.open`), jadi berjalan pada akses database pertama (bukan saat `open` dipanggil di thread komposisi). Salinan dihapus hanya setelah minimal 3 pembukaan sukses DAN umur salinan minimal 7 hari (bagian E hanya menyebut 3 pembukaan; hitungan saja bisa tercapai dalam menit karena worker, widget, dan tindakan notifikasi ikut membuka database). Versi tak terbaca tidak menimpa salinan utuh yang sudah ada.
   - **Dependensi baru di `:domain`: `kotlinx-serialization-json`** (hanya runtime JSON, tanpa plugin; untuk membuktikan `wireId == hash(type+id)` di dalam JSON), di samping `kotlinx-coroutines-core` yang sudah ada. Perlu dicatat sebagai keputusan arsitektur untuk pemilik.
   - **Vektor emas bukan format kabel Tink**: `domain/src/test/resources/sync/golden/envelope.v1.json` dibuat dengan AES-GCM JDK; belum dijalankan di klien web atau PHP, dan format kabel Tink belum dibuktikan.
   - **Yang sengaja belum**: `SyncBootstrap.markAllDirty` belum dipanggil siapa pun (Tahap 3); `SyncRecorder` hanya cakupan pribadi `a` dan aktif sejak migrasi 8 untuk semua akun; penjepitan cap jam jauh ke depan saat push dikerjakan di Tahap 2.
   - **Utang untuk Tahap 2** (ditemukan saat mengerjakan): `SyncApplyAbortedException` hanya terdeteksi untuk panggilan `applyIncoming` di luar transaksi lain; `shared_from` vs `AccountRepository.save` (`Account.toEntity` menimpa `shared_from` jadi null) dan query akun belum menyaring `shared_from IS NULL`; arsip ruang belum menandai `allocation_config`; repositori belum punya API hapus ruang, kategori, atau akun (cascade ruang ke kategori tak terjangkau); tes performa impor besar. Belum terbukti: migrasi di perangkat atau emulator, data besar, Android 8 (SQLite 3.18), pagar pada database WAL sungguhan dan ruang penuh (penguncian pagar hanya per proses), performa (tiap penulisan kini ditambah satu transaksi pembungkus dan beberapa query; impor CSV 10 ribu baris dan `sync_record` besar belum diukur).
2. **Mesin generik + ruang bersama pengeluaran + hapus (L).** `SyncCoordinator` per cakupan menggantikan `SyncEngine`, codec umum, akun anggota virtual, baris `SHARED`, tombstone, penulis tunggal; server: push idempoten dan transaksional, `changed_at_ms`, filter pull, `sync_devices`, `server_time_ms`. Tes: tombstone dan LWW, ruang pribadi tidak pernah muncul di `r:*`, saldo akun nyata tidak berubah, push idempoten, ack hilang.
3. **Cakupan pribadi inti + kunci akun + pulihkan (L).** Handler `room`, `account`, `category`, `allocation_config`, `transaction` (semua jenis + potret alokasi); `AccountKeyProvider` merge; gerbang `RecoveryKeyService`; `SyncBootstrap`; alur pulihkan perangkat baru (deteksi ruang kerja murni). Tes: gabung kunci, onboarding murni vs berisi, urutan acak halaman, restore dari kursor 0.
4. **Entitas pribadi lanjutan (M).** Utang + pembayaran, tagihan, berulang, `zakat_*` + `gold_price` MANUAL, `trader_profile`, `dca_plan`, favorit, `day_check`. Tes: golden per tipe, id deterministik konvergen, FK `RESTRICT` pada tombstone.
5. **Keluarkan anggota dan distribusi keyset (L).** Server `shared_rooms`, `room_members` role/status, rahasia join, hapus anggota, keluar; `RoomInvite` v3, `importRoomKey` merge, rekaman `room_key`, `key_grant`, UI keluarkan dan teks jujur.
6. **Sync otomatis (S).** `MainHost`, `SyncCoordinator` (Mutex, backoff, debounce), pesan status.
7. **Retensi, kuota, pengerasan server (M).** `sync:compact`, `sync:prune-stale-devices`, kuota, 410 `cursor_expired`, "Riwayat sinkron", diagnostik sinkron ulang penuh.

## J. Keputusan pemilik dan risiko

### Keputusan pemilik (jangan diputuskan sepihak; rekomendasi Claude)

| # | Keputusan | Rekomendasi |
|---|---|---|
| 0 | Ganti pencocokan akun lokal ke akun anggota virtual (label + penulis) | Ya (pencocokan sekarang menggeser saldo penerima atau membuang baris pasangan) |
| 1 | Pembuat ruang = pemilik; hanya pemilik mengundang dan mengeluarkan | Ya untuk v1 |
| 2 | Pengeluaran pasangan ikut "terpakai" ruang di Denah dan laporan saya (amplop bersama) tanpa menyentuh saldo akun saya | Ya untuk "terpakai" ruang, tidak untuk saldo; terkait butir peran dan amplop, putuskan sebelum UI tahap 2 |
| 3 | Baris pasangan baca-saja (hanya penulis awal yang bisa mengubah) | Ya untuk v1 |
| 4 | Riwayat ruang sebelum dibagi ikut dibagikan? | Ikut, dengan konfirmasi eksplisit (jumlah transaksi, rentang tanggal) dan opsi "mulai hari ini" |
| 5 | Kunci ruang dan pemulihan | Opsi C (rekaman `room_key` di log pribadi, bungkusan tetap versi 1) |
| 6 | Ubah keputusan 2026-10-19: boleh kompaksi dan retensi fisik (versi kalah >= 90 hari, tombstone >= 400 hari dan dilewati semua perangkat aktif) | Ya, dengan batas itu |
| 7 | Samarkan `record_type` (konstan) dan `record_id` (hash) sekarang, sebelum rilis | Ya (murah sekarang, mahal sesudah rilis) |
| 8 | Zakat, peran, favorit, `day_check` ikut sync pribadi, tidak pernah bersama | Ya, gelombang akhir |
| 9 | Saluran rotasi | Opsi 3 (rahasia pasangan dari QR), QR ulang cadangan |
| 10 | Hapus vs edit konkuren: LWW murni | Ya |
| 11 | Perangkat baru dengan ruang kerja berisi: otomatis pulihkan hanya bila murni; selain itu tanya Gabung/Pulihkan dengan salinan otomatis | Ya |
| 12 | Angka kuota akun dan perilaku saat penuh | 250.000 baris, 150 MB, pesan lembut |
| 13 | Akun dihapus: baris bersama yang ditulisnya ikut dihapus | Ya (privasi lebih dulu), diungkapkan di kebijakan privasi |
| 14 | Gerbang: baris pertama baru dikirim setelah kode pemulihan dikonfigurasi | Ya (usulan) |
| 15 | Sync otomatis hanya saat aplikasi di depan; saklar "hanya Wi-Fi" | Ya |
| 16 | Label penulis di baris bersama | Nama panggilan pilihan anggota, bukan email, dengan `author` dari server sebagai penentu identitas |

**Sudah diputuskan pemilik** (sesuai rekomendasi Claude di atas): **#0** (2026-10-26, ya), **#1** (2026-10-27, ya untuk v1 — pembuat ruang jadi pemilik default dengan hak keluarkan anggota, dicatat juga di roadmap.md Tahap 9 butir "Keluarkan anggota"), **#2** (2026-10-26, ya untuk "terpakai" ruang, tidak untuk saldo). **#3–#16 (2026-10-26): semua ya, sesuai rekomendasi Claude di tabel di atas** — baris pasangan read-only (#3), riwayat ruang ikut dibagikan dengan konfirmasi eksplisit dan opsi "mulai hari ini" (#4), kunci ruang opsi C (#5), kompaksi/retensi dengan batas 90/400 hari (#6), samarkan `record_type`/`record_id` sekarang (#7), zakat/peran/favorit/`day_check` tetap sync pribadi (#8), rotasi kunci opsi 3 — rahasia pasangan dari QR (#9), hapus vs edit LWW murni (#10), perangkat baru otomatis pulih bila murni atau tanya Gabung/Pulihkan (#11), kuota 250.000 baris/150 MB (#12), akun dihapus — baris bersama ikut terhapus (#13), gerbang baris pertama setelah kode pemulihan dikonfigurasi (#14), sync otomatis hanya saat aplikasi di depan plus saklar "hanya Wi-Fi" (#15), label penulis pakai nama panggilan bukan email (#16). Semua keputusan di tabel J sudah dijawab; Tahap 9 bagian 2 (mesin generik + UI) tidak lagi menunggu keputusan di sini.

### Risiko

Korupsi data (penerapan idempoten LWW, satu transaksi Room per halaman termasuk kursor, tes properti konvergensi, kegagalan dekripsi dicatat bukan diterapkan separuh); kehilangan data saat migrasi (migrasi aditif, tes hitungan baris, `.premigrate`, `synced_at` tidak dihapus); duplikasi (deteksi ruang kerja murni, nama ganda ditoleransi, push idempoten); loop sync (`applying`, tes pull-lalu-push 0 unggahan, single-flight); jam salah (`SyncClock`); edit lokal kalah diam-diam (`sync_issue(OVERWRITTEN)`); server memalsukan urutan atau tombstone (AAD); anggota palsu menulis atas nama lain (penulis tunggal via `author` server); kunci yatim (gerbang pemulihan, gabung kunci, `room_key`); restore backup berkas DB (kursor di DB yang sama); `join` tanpa rahasia (rahasia di QR, hash di server).

### Apa yang bocor ke server

`record_type` (jumlah per jenis; konstan `"r"`), `record_id` (awalan id deterministik; hash tak berkunci, rantai versi tetap terkait), ukuran (pengisi 256 byte), waktu (ritme pencatatan lewat `changed_at`; tidak ada mitigasi di v1), `room_id` + `user_id` (siapa berbagi ruang dengan siapa; tak terhindarkan), `device_id` (jumlah perangkat), `deleted` (frekuensi hapus). Server tidak pernah mendapat isi, nominal, nama akun atau kategori, maupun kunci.

## K. Strategi pengujian

- **Dua klien tanpa perangkat fisik** di `:domain/src/test`, memakai `InMemoryLedger`: `FakeSyncServer` di memori mengimplementasikan `SyncRemote` dengan keanggotaan, id naik, pagination, kegagalan (jaringan putus, 5xx), ack hilang setelah komit, pengiriman ganda, perusakan baris (ubah `changed_at`, `deleted`, scope), dan 403 `removed_from_room`. Ganti `FakeSyncAead` identitas (mengabaikan AAD) dengan AES-GCM JDK sungguhan supaya uji AAD bermakna. N klien (2 sampai 3) dengan mesin sungguhan, urutan acak operasi, periode offline acak, selisih jam ±jam, ukuran halaman 1-7, `Random(seed)` yang dicetak saat gagal.
- **Invarian akhir** setelah putaran sync penuh: proyeksi tiap klien sama; tak ada baris kotor tersisa; uang tetap Long; ruang pribadi tidak pernah ada di unggahan `r:*`; baris `SHARED` tidak mengubah saldo akun nyata; tombstone tidak menghidupkan kembali kecuali ada edit bercap lebih baru; pull lalu push menghasilkan 0 unggahan.
- **Golden test:** payload per tipe per versi, vektor AAD, vektor dekripsi tetap yang dibuat sekali dengan Tink dan disimpan di `docs/` supaya klien web (Tahap 14) bisa memverifikasi interoperabilitas.
- **Tes server (PHP):** filter pull per cakupan, push idempoten dan transaksional, `changed_at_ms` utuh, anggota dikeluarkan 403, `join` butuh rahasia, kuota, kompaksi menjaga pemenang dan tidak membuang tombstone sebelum kursor semua perangkat aktif, 410 untuk perangkat basi.
- **Tetap tidak terbukti tanpa perangkat atau server hidup:** Android Keystore asli, jam dan zona waktu nyata, QR dan kamera dua HP, baterai dan Doze; MySQL (presisi, penguncian, indeks), batas Nginx/Cloudflare, kinerja pull besar di ponsel; interoperabilitas dekripsi di peramban (Tahap 14), jalur Play untuk entitlement Sync, FCM.
