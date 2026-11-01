# Peran anggota keluarga dan amplop bulanan

**Status: USULAN Claude, 2026-11-01. Belum disetujui pemilik.** Pemilik menyetujui (2026-10-26) agar Claude menulis usulan rancangan ini lebih dulu; kodenya baru dikerjakan setelah rancangan disetujui. Semua butir yang butuh keputusan ditandai **usulan** dan dikumpulkan di bagian I. Tidak ada yang dipilih sepihak di sini.

Butir roadmap: Tahap 9, "Peran anggota keluarga (pengelola, pemberi jatah, anggota pencatat, anak) dan amplop uang bulanan" ([roadmap.md](roadmap.md)). Dasar yang sudah diputuskan dan dihormati dokumen ini:

- **Akun lintas ruang** (2026-10-19): anggota melihat transaksi ruang bersama beserta nama akun sumbernya, tidak melihat saldo akun itu atau transaksi lain di akun itu ([server-sync.md](server-sync.md), "Model kunci").
- **Keputusan #0** (2026-10-26): pengeluaran pasangan masuk lewat **akun anggota virtual** (`account.shared_from`), bukan dicocokkan ke akun lokal ([sync-desain.md](sync-desain.md) bagian B dan E).
- **Keputusan #2** (2026-10-26): pengeluaran pasangan **ikut "terpakai" ruang** di Denah dan laporan, **tidak menyentuh saldo** akun saya.
- **Keputusan #1** (2026-10-27) dan **model kepemilikan ruang**: pembuat ruang (yang pertama `join` atau push) menjadi pemilik dengan hak keluarkan anggota; anggota lain pencatat. Server: `room_members.is_owner`, `DELETE /api/rooms/{roomId}/members/{userId}`.
- **Keputusan #3**: baris pasangan baca-saja; hanya penulis awal yang bisa mengubah atau menghapus (penulis tunggal ditegakkan server).
- **Keputusan #16**: label penulis = nama panggilan pilihan anggota, bukan email; identitas ditentukan `author` buram dari server.
- **Prinsip**: tenang, bukan panik (peringatan lembut, tidak pernah memblokir); uang selalu `Long` satuan terkecil; yang bisa dihitung tidak disimpan ([model-data.md](model-data.md)); satu lapisan entitlement; server tidak bisa membaca data keuangan.

## A. Tujuan dan batas rilis 1

**Tujuan.** Ruang keluarga yang sekarang (Tahap 9, inti selesai 2026-10-24) sudah bisa dibagi dan dicatat bersama, tetapi belum menjawab dua pertanyaan rumah tangga yang paling sering muncul di riset ([research.md](research-market/research.md): "istri sebagai pengelola kas, suami memberi uang bulanan, anggota ikut mencatat"; "amplop sangat cocok dengan budaya uang bulanan"):

1. **Siapa boleh apa** di ruang bersama, ditulis jelas sekali dan sama di Android, server, dan web.
2. **Berapa jatah tiap orang bulan ini, dan berapa yang sudah dipakai**, tanpa membuka saldo akun siapa pun.

**Batas rilis 1 (catatan roadmap: rilis 1 hanya Pemilik dan Anggota pencatat; peran lain gagasan sesudah rilis).** Dokumen ini memisahkan keduanya di setiap bagian:

| | Rilis 1 | Sesudah rilis (gagasan, belum dijadwalkan) |
|---|---|---|
| Peran | **Pemilik** (pembuat ruang; satu per ruang) dan **Anggota pencatat** | **Pengelola**, **Pemberi jatah**, **Anak**, ubah peran, pindah kepemilikan |
| Amplop | Usulan: amplop per anggota per ruang per bulan, **diatur Pemilik**; rincian terpakai per anggota; peringatan lembut (bergantung keputusan I-2) | "Uang bulanan" dari Pemberi jatah yang dibagi Pengelola; serah-terima uang bulanan sebagai transaksi; notifikasi amplop hampir habis |
| Server | `member_id` buram dan peran di daftar anggota, keluar sendiri, batas 5 anggota, gerbang langganan keluarga (bergantung I-8) | Peran tambahan (terenkripsi atau kolom server, I-10) |

Rilis 1 tidak menambah peran baru; ia merapikan dua peran yang **sudah ada di kode** (pemilik dan pencatat) supaya hak keduanya tertulis, ditegakkan, dan terlihat di UI.

## B. Peran

| Peran | Kapan | Siapa biasanya | Ringkas |
|---|---|---|---|
| **Pemilik** | Rilis 1 | Yang membuat ruang (suami atau istri) | Satu-satunya yang mengundang, mengeluarkan, dan (rilis 1) mengatur amplop. Di rilis 1 Pemilik memegang sekaligus hak yang sesudah rilis dipecah ke Pengelola dan Pemberi jatah |
| **Anggota pencatat** | Rilis 1 | Pasangan, anggota dewasa | Mencatat pengeluaran di ruang bersama, melihat transaksi dan amplop ruang itu |
| Pengelola | Sesudah | Istri sebagai pengelola kas | Membagi uang bulanan menjadi amplop per anggota; melihat semua. Tidak mengundang atau mengeluarkan (itu tetap Pemilik, karena terkait rotasi kunci) |
| Pemberi jatah | Sesudah | Suami pemberi uang bulanan | Menetapkan "uang bulanan" (total yang ia serahkan ke ruang itu tiap bulan); ikut mencatat |
| Anak | Sesudah | Anak remaja dengan uang saku | Mencatat ke amplopnya sendiri; hanya melihat amplop dan catatannya sendiri (lihat I-11 soal batas kerahasiaannya) |

Satu orang punya satu peran per ruang. Pemilik sesudah rilis bisa juga merangkap Pengelola atau Pemberi jatah (peran "Pemilik" menempel pada pembuat ruang dan tidak bisa dipindah sampai ada alur pindah kepemilikan).

## C. Tabel hak per peran

Keterangan: **Ya** = boleh; **Tidak** = tidak boleh; **Sendiri** = hanya miliknya. Kolom terakhir menyebut di mana aturan itu ditegakkan, karena semua anggota memegang kunci ruang yang sama: aturan yang hanya ditegakkan klien adalah aturan tampilan, **bukan** jaminan kriptografis (aplikasi yang dimodifikasi bisa melanggarnya).

| Aksi | Pemilik (R1) | Anggota pencatat (R1) | Pengelola (S) | Pemberi jatah (S) | Anak (S) | Ditegakkan di |
|---|---|---|---|---|---|---|
| Catat pengeluaran di ruang bersama | Ya | Ya | Ya | Ya | Ya (ke amplopnya) | Server: anggota aktif ruang + gerbang Sync |
| Ubah atau hapus catatan sendiri | Ya | Ya | Ya | Ya | Ya | Server: penulis tunggal (#3) |
| Ubah atau hapus catatan orang lain | Tidak | Tidak | Tidak | Tidak | Tidak | Server: penulis tunggal (#3). Pemilik pun tidak; koreksi lewat meminta penulisnya |
| Lihat transaksi ruang bersama | Ya, semua | Ya, semua | Ya, semua | Ya, semua | Sendiri saja | Klien (Anak: tampilan saja, lihat I-11) |
| Lihat nama akun sumber transaksi orang lain | Ya | Ya | Ya | Ya | Tidak | Klien (aturan 2026-10-19) |
| Lihat saldo akun orang lain | Tidak | Tidak | Tidak | Tidak | Tidak | **Struktural**: saldo dan rekaman `account` tidak pernah masuk cakupan bersama |
| Lihat transaksi orang lain di ruang pribadinya | Tidak | Tidak | Tidak | Tidak | Tidak | **Struktural**: cakupan pribadi dienkripsi kunci akun |
| Lihat jatah dan terpakai ruang bersama | Ya | Ya | Ya | Ya | Amplopnya saja | Klien |
| Atur aturan alokasi | Sendiri | Sendiri | Sendiri | Sendiri | Sendiri | **Struktural**: `allocation_config` pribadi; tidak ada aturan alokasi bersama |
| Kelola pos (kategori) ruang bersama | Sendiri (lokal) | Sendiri (lokal) | Sendiri | Sendiri | Tidak | Struktural: kategori bersama hanya nama di payload, dibuat otomatis per perangkat (sync-desain.md B R2) |
| Atur amplop per anggota | Ya | Tidak | Ya | Tidak | Tidak | Klien: penerima mengabaikan baris amplop yang `author`-nya bukan pemegang hak (D.4) |
| Tetapkan uang bulanan (total) | (R1 tidak ada) | Tidak | Tidak | Ya | Tidak | Klien, sama seperti amplop |
| Undang anggota (buat QR) | Ya | Tidak | Tidak | Tidak | Tidak | Klien (tombol disembunyikan); server setelah `join_secret` ada (sync-desain.md G) |
| Keluarkan anggota (+ rotasi kunci) | Ya | Tidak | Tidak | Tidak | Tidak | Server: `is_owner` (sudah ada) |
| Keluar sendiri dari ruang | Tidak (belum ada pindah kepemilikan) | Ya | Ya | Ya | Ya | Server: endpoint baru `leave` (E.2) |
| Ubah peran anggota | (R1 tidak ada) | Tidak | Tidak | Tidak | Tidak | Sesudah: hanya Pemilik |
| Lihat daftar anggota dan perannya | Ya | Ya | Ya | Ya | Ya | Server (`GET members`, sudah ada) |

Catatan pada tabel:

- **Pemilik tidak bisa menyunting catatan anggota.** Ini konsekuensi keputusan #3 yang sudah diambil, bukan usulan baru; dicantumkan supaya tidak ada yang mengira "Pemilik = admin penuh".
- **Saldo tidak pernah terlihat oleh siapa pun selain pemilik akun**, tanpa kecuali per peran. Ini dijaga struktur data (akun tidak pernah dibagi), bukan oleh pengecekan peran.
- **Aturan alokasi tetap pribadi.** Ruang bersama tidak punya aturan alokasi bersama; tiap orang mengalirkan rezekinya sendiri ke ruang itu menurut aturannya sendiri. Amplop (bagian D) adalah lapisan rencana belanja di atasnya, bukan pengganti aturan alokasi.

## D. Model amplop bulanan

### D.1 Pengertian

**Amplop** = rencana berapa yang boleh dibelanjakan **seorang anggota** di **satu ruang bersama** pada **satu bulan**. Amplop adalah angka rencana yang ditetapkan orang (bukan turunan transaksi), jadi ia satu-satunya angka baru yang **disimpan**. Terpakai dan sisa amplop tetap **diturunkan** dari transaksi, sesuai prinsip model-data.md.

Amplop bukan perpindahan uang. Di rilis 1, "suami menyerahkan uang bulanan ke istri" tidak dicatat sebagai apa pun oleh fitur ini (lihat I-7 untuk risiko hitung ganda dan usulan sesudah rilis).

### D.2 Rekaman amplop (usulan)

Amplop berlaku **terus sampai diubah** (usulan I-4), supaya Pemilik tidak mengisi ulang tiap bulan:

| Bidang | Tipe | Arti |
|---|---|---|
| `room_id` | teks | Ruang bersama |
| `member_id` | teks | Id anggota buram (sama dengan `author` dari server, lihat E.1) |
| `from_month` | Int `yyyymm` | Berlaku mulai bulan ini |
| `amount` | Long | Satuan terkecil (rupiah); `0` = amplop dihapus mulai bulan itu |

Amplop anggota M di bulan B = rekaman dengan `from_month` terbesar yang `<= B`. Mengubah amplop bulan lalu tidak diizinkan di UI (riwayat tidak ditulis ulang); mengubah bulan berjalan menulis rekaman `from_month = bulan ini`.

### D.3 Cara menghitung (golden, sama di semua klien)

Untuk ruang bersama R dan bulan B:

| Angka | Rumus | Catatan |
|---|---|---|
| **Terpakai ruang** | jumlah `EXPENSE` di R pada B, **termasuk baris `SHARED`** dari anggota lain | Keputusan #2. Query `spentPerRoom` sekarang sudah tidak menyaring `origin`, jadi baris `SHARED` sudah ikut; yang perlu ditambahkan tes pengunci, bukan logika baru |
| **Saldo akun saya** | tidak berubah oleh baris `SHARED` | Keputusan #2. Akun anggota virtual (`shared_from` terisi) wajib disaring dari semua hitungan akun; tes jaga sudah diwajibkan sync-desain.md E |
| **Terpakai amplop M** | jumlah `EXPENSE` di R pada B yang **penulisnya M** | Baris sendiri: penulis = saya. Baris `SHARED`: penulis dari `author` baris sync (lewat akun anggota virtual) |
| **Sisa amplop M** | amplop M − terpakai amplop M | Boleh negatif (tampil "lewat Rp X", bukan merah) |
| **Total amplop R** | jumlah amplop semua anggota aktif pada B | |
| **Jatah ruang di Denah** | usulan I-3: total amplop R bila ada amplop bulan itu; bila tidak ada, jatah dari alokasi pribadi seperti sekarang | Lihat D.5 |
| **Status amplop** | aturan sama dengan `RoomStatusRules` tipe Mencukupi: `progressBp >= 8500` Perlu perhatian; lewat Rp 1 pun dihitung lewat (dibandingkan sebagai uang, bukan persen dibulatkan) | Usulan I-6 |

Anggota yang dikeluarkan atau keluar di tengah bulan: catatannya tetap terhitung di terpakai ruang dan di amplopnya untuk bulan itu (riwayat tidak hilang); amplopnya tidak lagi ikut total mulai bulan berikutnya.

Tidak ada sisa yang dibawa ke bulan depan (usulan I-5, konsisten dengan model-data.md "tanpa carry-over sisa jatah antar-bulan di v1").

### D.4 Siapa yang boleh menulis amplop

Server tidak tahu jenis rekaman (keputusan #7: `record_type` disamarkan), jadi server **tidak bisa** menegakkan "hanya Pemilik menulis amplop". Usulan: penegakan di klien penerima. Setiap klien mengabaikan baris amplop yang `author`-nya bukan pemegang hak menurut daftar anggota dari server (rilis 1: `role = owner`), dan mencatatnya di `sync_issue` (jenis baru `UNAUTHORIZED_AUTHOR`). Ini cukup untuk keluarga yang saling percaya; aplikasi yang dimodifikasi tetap tidak bisa membuat orang lain menerima amplop palsu, karena `author` diisi server, bukan oleh pengirim.

### D.5 Hubungan amplop dengan jatah ruang

Masalah yang harus dijawab: jatah ruang di Denah sekarang = alokasi pemasukan **saya** ke ruang itu. Di ruang bersama, anggota yang tidak punya pemasukan (mis. istri yang menerima uang bulanan) selalu melihat jatah 0 dan status Menunggu, padahal ia yang paling banyak belanja; pemberi uang melihat jatahnya sendiri dibanding terpakai semua orang.

Pilihan (keputusan I-3):

- **A. Tetap jatah pribadi.** Paling sederhana; dua anggota melihat angka jatah berbeda untuk ruang yang sama.
- **B (rekomendasi). Total amplop menjadi jatah ruang bersama** bila amplop bulan itu ada; semua anggota melihat jatah, terpakai, dan sisa yang sama. Alokasi pribadi tetap tampil sebagai baris kedua "Dialirkan dari rezekimu: Rp X", dan bila total amplop melebihi yang dialirkan, info lembut "Amplop bulan ini Rp 2,0 jt lebih besar dari rezeki yang kamu alirkan ke Keluarga". Tanpa amplop, perilaku sekarang tidak berubah.
- **C. Dua angka berdampingan** (jatah pribadi dan amplop) di kartu Denah. Paling lengkap, paling ramai di kartu kecil.

Alasan rekomendasi B: angka yang sama untuk semua anggota adalah inti "amplop bersama" di keputusan #2, dan tidak mengubah apa pun bagi pengguna yang tidak memakai ruang bersama.

### D.6 Perilaku saat amplop terlampaui

Tenang, bukan panik; tidak pernah memblokir:

- **Saat mencatat** (S06, S24): bila catatan ini membuat amplop saya mencapai 85% atau melewatinya, banner lembut di atas tombol Simpan (pola banner batas risiko Trader): ikon info + "Amplopmu di Keluarga sisa Rp 150.000. Catatan ini melewatinya Rp 37.500." Tombol Simpan tetap aktif dan tidak ada dialog konfirmasi tambahan.
- **Denah dan Detail ruang**: status pakai ikon + teks ("Perlu perhatian", "Lewat amplop Rp 37.500"), tidak pernah warna merah saja.
- **Perlu perhatian** di Denah (maksimal 3 butir): "Amplopmu di Keluarga hampir habis" hanya untuk amplop **milik saya**; amplop orang lain tidak masuk daftar ini supaya tidak terasa mengawasi.
- **Tidak ada notifikasi push** soal amplop di rilis 1 (FCM belum tersambung, dan isi push wajib generik tanpa nominal).

## E. Dampak teknis

### E.1 Server (`server/`)

**Rilis 1:**

1. `GET /api/rooms/{roomId}/members` menambah `member_id` (nilai yang sama dengan `author` di pull: `substr(sha256("rizqflow-sync-author|"+roomId+"|"+userId), 0, 16)`) dan `role` (`owner`/`member`, diturunkan dari `is_owner`). Tanpa `member_id`, klien tidak bisa mengaitkan `author` baris sync dengan nama atau peran, dan juga tidak bisa memanggil `DELETE .../members/{userId}` yang sekarang meminta id internal pengguna (roadmap mencatat endpoint ini "belum dipanggil dari UI"). Usulan: endpoint hapus menerima `member_id` buram; id internal tidak perlu keluar.
2. `POST /api/rooms/{roomId}/leave` (anggota non-pemilik keluar sendiri; 422 untuk pemilik). Sudah ada di desain sync-desain.md G, belum di kode.
3. **Batas 5 anggota** per ruang ditegakkan saat `join` (422 `room_full`). Sekarang `join` tidak membatasi jumlah, padahal Sync dijual "keluarga sampai 5 anggota".
4. **Gerbang langganan keluarga** (bergantung I-8): sekarang `denyWithoutSharedRoom` memeriksa paket **peminta**, jadi setiap anggota butuh langganan Sync sendiri, bertentangan dengan monetisasi.md ("satu langganan per keluarga sampai 5 anggota, bukan per orang"). Usulan: akses ruang bersama (join, members, push/pull baris `room_id` itu) dibuka bila **pemilik ruang** punya Sync aktif, lewat satu metode di `Support\Entitlements` (mis. `allowsSharedRoom(User, roomId)`), bukan pengecekan tersebar.
5. **Tidak ada tabel amplop di server.** Amplop adalah rekaman terenkripsi biasa di `sync_log_entries` cakupan `r:<roomId>`; server tetap tidak bisa membaca nominal. Tidak ada migrasi server untuk amplop.

**Sesudah rilis:** peran tambahan (Pengelola, Pemberi jatah, Anak). Dua pilihan (I-10): kolom `room_members.role` plaintext (server tahu struktur keluarga, termasuk ada anak) atau rekaman `member_role` terenkripsi yang ditulis Pemilik, dengan server hanya tahu `owner`. Rekomendasi: terenkripsi, karena server tidak menegakkan apa pun dari peran selain undang/keluarkan (yang tetap milik Pemilik).

### E.2 Android (Room dan domain)

**Prasyarat (L, sudah tercatat sebagai utang di roadmap Tahap 9 butir Tahap 2):** cakupan ruang bersama masih memakai amplop ad hoc `TransactionSyncCodec` yang hanya mengenal transaksi; `SyncCoordinator` melewati jenis rekaman lain saat pull. Rekaman amplop (dan `key_grant` Tahap 5) butuh cakupan bersama dipindah ke mesin generik `SyncEnvelope`/`SyncRecordHandler`. Rancangan ini **tidak** membuat jalur kedua; ia menunggu (atau ikut memicu) pemindahan itu.

**Skema Room (migrasi aditif berikutnya, versi 10 bila tidak ada migrasi lain lebih dulu; skema sekarang versi 9):**

| Tabel baru | Kolom | Catatan |
|---|---|---|
| `shared_member` (PK `room_id, member_id`, FK `room` CASCADE) | `label` (nama panggilan, #16), `role` (`OWNER`/`MEMBER`; sesudah rilis `MANAGER`/`GIVER`/`CHILD`), `status` (`ACTIVE`/`REMOVED`/`LEFT`), `is_me`, `updated_at` | Cache daftar anggota dari server; dipakai untuk label penulis, penegakan D.4, dan rincian per anggota. `shared_room.role` yang sudah ada tetap untuk peran saya sendiri |
| `shared_envelope` (PK `room_id, member_id, from_month`, FK `room` CASCADE) | `amount` Long | Proyeksi lokal rekaman amplop (D.2); versi dan `author` tetap di `sync_record` seperti rekaman lain |

Kolom baru: tidak ada. Penulis baris `SHARED` sudah ada lewat akun anggota virtual dan `sync_record.author`; usulan kecil: simpan `member_id` di akun anggota virtual (atau petakan lewat `shared_from`) supaya "terpakai amplop M" bisa satu query `GROUP BY` tanpa membaca `sync_record`.

Migrasi aditif tanpa menulis ulang data, dengan pola tes yang sama dengan `MIGRATION_7_8` (jumlah baris per tabel sama, `foreign_key_check` kosong, skema hasil migrasi sama dengan skema dari nol) dan pagar `.premigrate` yang sudah ada.

**Domain (`:domain`, Kotlin murni):** `EnvelopeRules` (amplop efektif per bulan, terpakai per anggota, sisa, status; memakai ulang `RoomStatusRules.progressBp` dan ambang 85%), `RoomRole` dan `RoomPermissions` (satu tempat yang menjawab "peran ini boleh aksi ini?", seperti `Entitlements` menjawab soal paket; layar tidak memeriksa peran sendiri), dan `EnvelopeSyncHandler`.

**Protokol sync:** jenis rekaman baru `envelope` versi 1 di cakupan `r:<roomId>`, payload `{"room":..,"member":..,"from_month":202611,"amount":1500000}` di dalam amplop umum (AAD kanonis, pengisi 256 byte, `record_id` hash). Identitas rekaman deterministik `envelope|<roomId>|<memberId>|<from_month>` supaya dua tulisan untuk bulan yang sama konvergen lewat LWW. Penerima: D.4. Sesudah rilis: `member_role` dan `monthly_allowance` (uang bulanan Pemberi jatah) dengan pola yang sama.

### E.3 Entitlement dan tier

- Ruang keluarga tetap `Feature.SHARED_FAMILY_ROOM` (`Plan.SYNC`). Peran dan amplop **tidak** menambah fitur Pro dan tidak menambah fitur entitlement baru; semua yang bisa mengakses ruang bersama bisa memakai amplop. Alasan: amplop adalah nilai jual Sync keluarga, bukan Pro (Pro tetap ruang, aturan, profil).
- **Konflik yang harus diputuskan (I-8):** di Android, `PurchaseStore` hanya membuka paket dari pembelian Play di perangkat itu, dan `PurchaseClaimCoordinator` sengaja **tidak pernah** membuka paket berdasarkan jawaban server. Anggota keluarga yang tidak membeli Sync sendiri tidak akan pernah melihat layar ruang keluarga terbuka. Usulan: entitlement ruang bersama di Android mendapat sumber kedua yang sempit, "anggota ruang yang pemiliknya Sync aktif" (dari jawaban `GET members`, mis. bidang `sync_active`), hanya untuk ruang itu, tanpa membuka `ENCRYPTED_SYNC` pribadi. Ini perubahan aturan "satu lapisan entitlement" yang perlu persetujuan pemilik; tetap satu lapisan, tapi lapisannya membaca dua sumber.
- Turun paket pemilik: anggota tetap bisa membaca data yang sudah ada di perangkatnya; push dan pull ruang itu berhenti dengan pesan lembut "Langganan Sync pemilik ruang tidak aktif". Tidak ada data yang dihapus.

### E.4 Privasi

- **Anggota tidak melihat saldo akun orang lain**, di peran mana pun: akun tidak pernah masuk cakupan bersama, dan akun anggota virtual (penampung baris `SHARED`) wajib disaring dari daftar akun, saldo, rekonsiliasi, kekayaan bersih, dan ekspor CSV pribadi (sync-desain.md E). Rincian per anggota di amplop hanya menjumlah **pengeluaran di ruang bersama itu**, bukan kemampuan finansial anggota.
- Amplop adalah data keuangan: dienkripsi kunci ruang, server tidak pernah melihat nominal. Yang tetap terlihat server (sudah diungkapkan di sync-desain.md "Apa yang bocor"): siapa berbagi ruang dengan siapa, siapa pemilik, kapan menulis, ukuran. Dengan rekomendasi I-10, peran selain pemilik tidak menambah kebocoran.
- **Anak:** karena semua anggota memegang kunci ruang yang sama, "anak hanya melihat catatannya sendiri" adalah aturan tampilan, bukan kerahasiaan. UI dan kebijakan privasi harus menulisnya jujur (I-11).
- Push notifikasi tetap generik ("Ada transaksi baru di Keluarga"), tanpa nominal, nama pos, atau sisa amplop.
- Teks UI tabel "Siapa melihat apa" (mockup web Keluarga) ditambah satu baris: "Amplop dan terpakai per anggota di ruang ini: semua anggota melihat" (rilis 1).

### E.5 Tes golden bersama Kotlin dan PHP

Sekarang golden PHP adalah port manual dari tes Kotlin (contoh: `SmartInputParserTest`, kasus disalin satu per satu). Untuk amplop usulannya **satu berkas data** yang dibaca kedua bahasa, supaya tidak ada dua salinan yang bisa menyimpang:

- Berkas: `domain/src/test/resources/family/envelope.v1.json`, berisi kasus `{anggota, amplop (dengan from_month), transaksi (penulis, ruang, bulan, jenis, origin, amount), bulan}` dan hasil yang diharapkan (terpakai ruang, terpakai dan sisa per anggota, status, jatah ruang menurut I-3).
- Kotlin: `EnvelopeRulesGoldenTest` di `:domain`. PHP: `tests/Unit/EnvelopeRulesGoldenTest.php` membaca berkas yang sama lewat path relatif repo. Klien web di peramban (Tahap 14, setelah sinkron data asli) membaca berkas yang sama.
- Kasus wajib: baris `SHARED` ikut terpakai ruang dan tidak menggeser saldo akun nyata (#2); `TRANSFER`/`LOAN_*` tidak dihitung; amplop berlaku terus sampai `from_month` berikutnya; `amount = 0` menghapus; lewat Rp 1 tetap "lewat"; tepat 85% sudah Perlu perhatian; bulan tanpa amplop jatuh ke alokasi pribadi; anggota keluar di tengah bulan; baris amplop dari bukan-pemilik diabaikan; nominal besar (Rp 25.783.000) dan tidak bulat.
- Satu vektor amplop terenkripsi untuk rekaman `envelope` ditambahkan ke vektor sync yang ada (`sync/golden/`), supaya web membuktikan bisa membacanya.

Catatan jujur: dengan enkripsi ujung ke ujung, hitungan amplop atas data asli di web akan berjalan di peramban (JS), bukan di PHP. Port PHP berguna untuk halaman web selama masih data contoh dan untuk menjaga kesejajaran; berkas golden yang sama yang membuat ketiganya tetap satu aturan.

## F. Wireframe teks layar yang berubah

### Ruang Keluarga (`RuangKeluargaScreen`), rilis 1, dilihat Pemilik

```
+--------------------------------+
| < Ruang keluarga: Keluarga     |
|                                |
| Anggota (2 dari 5)             |
| (A) Ayu (kamu)        Pemilik  |
| (D) Dimas    Anggota pencatat  |
|                         [...]  |
|                                |
| Amplop November 2026           |
| Jatah Rp 6.000.000             |
| Terpakai Rp 4.420.000          |
| Ayu    [#######...] 3,1/4,0 jt |
| Dimas  [######....] 1,3/2,0 jt |
| [ Atur amplop ]                |
|                                |
| [ Undang anggota ]             |
| [ Sinkronkan sekarang ]        |
+--------------------------------+
```

- Menu `[...]` per anggota (hanya Pemilik): "Keluarkan anggota & buat kunci baru" (sudah ada, kini per anggota lewat `member_id`).
- Dilihat **Anggota pencatat**: tanpa "Undang anggota", tanpa "Atur amplop", tanpa menu `[...]`; ada tombol teks "Keluar dari ruang ini" di bawah dengan konfirmasi ("Catatanmu yang sudah terkirim tetap ada untuk anggota lain").
- Batang bertuliskan angka; status lewat amplop tampil sebagai teks "Lewat Rp 37.500" dengan ikon, bukan warna saja.
- Tanpa amplop: bagian Amplop diganti satu kalimat "Belum ada amplop bulan ini" dan, untuk Pemilik, tombol "Atur amplop".

### Sheet "Atur amplop" (baru), hanya Pemilik di rilis 1

```
+--------------------------------+
| Amplop Keluarga                |
| Berlaku mulai November 2026    |
| sampai diubah                  |
|                                |
| Ayu (kamu)     Rp [4.000.000]  |
| Dimas          Rp [2.000.000]  |
|                                |
| Total amplop   Rp 6.000.000    |
| Dialirkan dari rezekimu ke     |
| Keluarga bulan ini: Rp 5,1 jt  |
| (i) Amplop lebih besar Rp 900rb|
|                                |
|         [ Batal ]  [ Simpan ]  |
+--------------------------------+
```

- Nominal lewat papan angka yang sama dengan Catat. Baris info hanya informasi, tidak menahan Simpan.

### S11 Detail ruang, ruang bersama

```
+--------------------------------+
| < Keluarga          (Bersama)  |
|          (  73%  )             |
|   Terpakai Rp 4.420.000        |
|   dari amplop Rp 6.000.000     |
|   Dialirkan dari rezekimu      |
|   Rp 5.100.000                 |
|                                |
| Amplopmu: sisa Rp 900.000      |
|                                |
| Transaksi terbaru              |
| 18 Nov  Belanja pasar   -150rb |
|         Dimas · Tunai          |
| 17 Nov  Token listrik   -200rb |
|         kamu · BCA             |
| [ Ruang keluarga >]            |
+--------------------------------+
```

- Baris "Dialirkan dari rezekimu" hanya muncul bila rekomendasi I-3 (B) dipilih. Baris pasangan menampilkan nama panggilan dan nama akun sumber, tanpa saldo.

### S05 Denah, kartu ruang bersama

```
| +-------------+                |
| | Keluarga  ⇄ |                |
| | (o) 73%     |                |
| | Berjalan    |                |
| | Amplopmu    |                |
| | sisa 900rb  |                |
| +-------------+                |
```

- Ikon "bersama" plus teks pembaca layar "Ruang bersama". "Perlu perhatian" hanya untuk amplop milik saya (D.6).

### S06 Catat, banner amplop

```
| Ruang     [Keluarga      v]    |
| (i) Amplopmu di Keluarga sisa  |
|     Rp 150.000. Catatan ini    |
|     melewatinya Rp 37.500.     |
|                     [ Simpan ] |
```

### Sesudah rilis (gagasan): sheet "Ubah peran"

```
| Peran Dimas di Keluarga        |
| ( ) Anggota pencatat           |
| ( ) Pengelola                  |
| ( ) Pemberi jatah              |
| ( ) Anak                       |
| Anak hanya melihat amplop dan  |
| catatannya sendiri di aplikasi.|
|         [ Batal ]  [ Simpan ]  |
```

Web (halaman Keluarga di mockup, Tahap 14): bagian "Amplop bulanan" di mockup sudah sesuai rancangan ini (jatah, terpakai dicatat oleh tiap anggota, sisa); label "Pengelola" untuk Ayu di mockup diganti "Pemilik" bila I-1 diputuskan sesuai rekomendasi.

## G. Pilihan desain dengan rekomendasi

| Pilihan | Opsi | Rekomendasi dan alasan |
|---|---|---|
| Granularitas amplop | per ruang saja / per anggota per ruang / per anggota per pos | **Per anggota per ruang per bulan.** Sesuai butir roadmap dan budaya uang bulanan; per pos menambah layar dan tidak sesuai "batasi dimensi" |
| Masa berlaku | diisi tiap bulan / berlaku sampai diubah | **Berlaku sampai diubah** (`from_month`); sekali atur cukup, riwayat bulan lalu tetap benar |
| Siapa menulis amplop | semua anggota / Pemilik / server | **Pemilik di rilis 1**, ditegakkan di klien lewat `author` server; server tetap buta isi |
| Penyimpanan | tabel server / rekaman terenkripsi | **Rekaman terenkripsi** di log sync; tanpa tabel server, tanpa nominal terbaca |
| Jatah ruang bersama di Denah | pribadi / total amplop / dua angka | **Total amplop** bila ada, alokasi pribadi sebagai baris kedua (D.5) |
| Peringatan | blokir / konfirmasi / banner lembut | **Banner lembut**, sesuai prinsip "tidak pernah memblokir" |
| Ambang | 80% / 85% / 100% | **85%**, sama dengan tipe Mencukupi, supaya satu aturan |
| Peran sesudah rilis | kolom server / terenkripsi | **Terenkripsi** (I-10) |
| Gerbang langganan | per anggota / per pemilik ruang | **Per pemilik ruang** untuk cakupan ruang bersama (I-8) |
| Hak di kode | pengecekan tersebar / satu `RoomPermissions` | **Satu `RoomPermissions`** di `:domain`, porting ke PHP bila web menegakkan hal yang sama |

## H. Urutan implementasi bertahap

Ukuran S kecil, M sedang, L besar. Tiap langkah diuji dan di-commit sendiri, dan baru dimulai **setelah pemilik menyetujui rancangan ini**. Semua langkah Android hanya bisa dibuktikan lewat tes otomatis sampai sesi uji dua HP fisik dilakukan.

**Rilis 1**

1. **(S) Server: daftar anggota lengkap.** `member_id` buram dan `role` di `GET members`; hapus anggota memakai `member_id`; tes fitur.
2. **(S) Server: keluar sendiri dan batas 5 anggota.** `POST leave`, 422 `room_full` di `join`; tes.
3. **(S) Tes pengunci keputusan #2.** Terpakai ruang menyertakan `SHARED`, saldo akun nyata tidak berubah, akun anggota virtual tersaring (verifikasi utang `shared_from IS NULL` dari Tahap 1 sudah ditutup).
4. **(S) Android: hak Pemilik dan pencatat di UI.** `RoomRole` + `RoomPermissions`; label peran; sembunyikan Undang dan Keluarkan untuk pencatat; Keluar dari ruang; keluarkan per anggota.
5. **(M) Rincian terpakai per anggota** (tanpa amplop, hanya turunan transaksi) di Ruang keluarga dan Detail ruang. Bisa dirilis sendiri walau amplop ditunda.
6. **(M atau L, bergantung I-8) Gerbang langganan keluarga** di server (`Support\Entitlements`) dan sumber entitlement kedua di Android.
7. **(L, prasyarat) Cakupan ruang bersama ke mesin generik** (sudah tercatat sebagai utang Tahap 2; dibutuhkan juga oleh `key_grant` Tahap 5).
8. **(M) Amplop.** Rekaman `envelope`, migrasi Room (`shared_member`, `shared_envelope`), `EnvelopeRules`, `EnvelopeSyncHandler`, penegakan `author` (D.4), sheet Atur amplop.
9. **(S) Peringatan lembut** di Catat dan status di Denah/Detail ruang.
10. **(M) Golden bersama** `envelope.v1.json` dibaca Kotlin dan PHP, plus vektor rekaman terenkripsi.
11. **(M) Web**: tampilan Amplop di halaman Keluarga (Tahap 14 butir "Peran anggota dan amplop bulanan di web"), setelah sinkron data asli di web ada.

Bila amplop diputuskan sesudah rilis (I-2), rilis 1 berhenti di langkah 1 sampai 6, dan langkah 5 menjadi "amplop" versi paling sederhana.

**Sesudah rilis**

12. **(M) Peran tambahan** Pengelola dan Pemberi jatah: rekaman `member_role`, sheet Ubah peran, `RoomPermissions` diperluas.
13. **(M) Uang bulanan** Pemberi jatah (`monthly_allowance`) dan "belum dibagi" (pola "Belum dialirkan"); serah-terima uang sebagai transaksi yang tidak dihitung ganda (I-7).
14. **(L) Anak**: pembatasan tampilan, atau ruang dan kunci terpisah bila kerahasiaan sungguhan dibutuhkan (I-11).
15. **(S) Notifikasi** "amplopmu hampir habis" lokal di perangkat (bukan push berisi nominal), setelah FCM tersambung bila perlu pemicu.
16. **(M) Pindah kepemilikan** ruang (sekarang Pemilik tidak bisa keluar).

## I. Pertanyaan keputusan untuk pemilik

Semua belum diputuskan. Rekomendasi Claude di tiap butir; jangan dikerjakan sebelum dijawab.

1. **Nama peran pembuat ruang di UI: "Pemilik" atau "Pengelola"?** Mockup web memakai "Pengelola". Rekomendasi: **"Pemilik"** di rilis 1 (sama dengan `is_owner` dan roadmap), dan "Pengelola" disimpan untuk peran terpisah sesudah rilis; mockup web disesuaikan.
2. **Amplop masuk rilis 1 atau sesudah rilis?** Rekomendasi: **rilis 1, versi minimal** (amplop per anggota, diatur Pemilik, langkah 7 sampai 10). Alasannya butir roadmap menggabungkan peran dan amplop, dan riset menyebut amplop sebagai pembeda utama. Biayanya: menunggu prasyarat L (mesin generik cakupan bersama). Bila waktu rilis lebih penting, rilis 1 cukup rincian terpakai per anggota (langkah 5).
3. **Jatah ruang bersama di Denah**: alokasi pribadi (A), total amplop (B), atau dua angka (C)? Rekomendasi: **B** (D.5).
4. **Amplop berlaku sampai diubah, atau diisi per bulan?** Rekomendasi: **berlaku sampai diubah**.
5. **Sisa amplop dibawa ke bulan depan?** Rekomendasi: **tidak** di rilis 1 (sama dengan aturan jatah ruang).
6. **Ambang peringatan amplop 85%** seperti tipe Mencukupi? Rekomendasi: **ya**.
7. **Serah-terima uang bulanan** (suami ke istri) dicatat bagaimana, supaya tidak dihitung ganda dengan belanja istri? Rekomendasi: **rilis 1 tidak dimodelkan** (amplop = rencana belanja, bukan perpindahan uang; pengguna tidak perlu mencatat serah-terima di ruang bersama, dan UI menyebutnya di teks bantuan). Sesudah rilis: transaksi "Serah amplop" yang tidak masuk terpakai ruang.
8. **Langganan keluarga**: anggota tanpa langganan Sync sendiri boleh memakai ruang bersama lewat Sync pemilik? Rekomendasi: **ya, terbatas pada ruang bersama itu** (bukan sync pribadi anggota), maksimal 5 orang termasuk pemilik; ini mengubah aturan "Android tidak pernah membuka paket dari jawaban server" secara sempit (E.3). Teks S21 dan store listing harus menyebutnya jelas.
9. **Batas 5 anggota ditegakkan server saat join?** Rekomendasi: **ya** (422 `room_full`).
10. **Peran sesudah rilis disimpan terenkripsi (server hanya tahu pemilik) atau kolom server?** Rekomendasi: **terenkripsi**.
11. **Anak: pembatasan tampilan saja (kunci ruang sama), atau ruang dan kunci terpisah?** Rekomendasi: **putuskan sesudah rilis**; bila dipilih tampilan saja, teks UI dan kebijakan privasi wajib menyebut bahwa itu bukan kerahasiaan.
12. **Amplop dan terpakai per anggota terlihat oleh semua anggota?** Rekomendasi: **ya di rilis 1** (transparan, seperti mockup web); hanya Anak (sesudah rilis) yang dibatasi ke amplopnya sendiri. "Perlu perhatian" tetap hanya untuk amplop milik sendiri.
13. **Anggota pencatat boleh keluar sendiri dari ruang di rilis 1?** Rekomendasi: **ya** (endpoint `leave`, S).
