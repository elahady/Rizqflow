# Deploy web Rizqflow

Web dijalankan di VPS IDCloudHost `103.54.170.198`, bersama roziqrizalcom dan habitflow (aturan
pemisahan dan detail VPS: repo [build-server](https://github.com/elahady/build-server)). Polanya
sama dengan `habitflow/server/DEPLOY.md`.

| Hal | Nilai |
|---|---|
| Domain | `https://rizqflow.roziqrizal.com` (Cloudflare proxied, Origin Certificate wildcard di nginx host) |
| Kode di VPS | `~/apps/rizqflow` (clone repo ini), aplikasi di `~/apps/rizqflow/server` |
| Container | `rizqflow` (image `rizqflow:latest`), port `127.0.0.1:8082` → 8080 |
| Database | MySQL `shared-mysql`, database dan pengguna `rizqflow` (network `shared-db`) |
| Kredensial | `~/apps/rizqflow/server/.env.production` di VPS saja (APP_KEY, sandi DB). Tidak di git |

**Git push bukan deploy.** Wajib sebelum deploy: `php artisan test` di `server/` lulus semua.

```bash
ssh -i ~/.ssh/id_ed25519_vps_jakarta elahady@103.54.170.198
cd ~/apps/rizqflow && git pull --ff-only origin main
cd server && docker compose build && docker compose up -d --force-recreate
# Migrasi tanpa terpotong timeout SSH (gotcha di build-server ROADMAP Fase 3):
docker exec -d rizqflow sh -c "php artisan migrate --force > /tmp/migrate.log 2>&1"
docker exec rizqflow pgrep -f "artisan migrate" || docker exec rizqflow cat /tmp/migrate.log
```

## Status (18 Oktober 2026)

- Deploy pertama selesai: container `rizqflow` healthy, 10 migrasi jalan, nginx site
  `/etc/nginx/sites-available/rizqflow.roziqrizal.com` aktif (diuji `curl --resolve` dari VPS: 200).
- **Live** di https://rizqflow.roziqrizal.com. Pemilik sudah mengisi DNS A record `rizqflow` →
  `103.54.170.198` (Proxied) di Cloudflare dan Authorized JavaScript origin
  `https://rizqflow.roziqrizal.com` di klien Web Google Cloud proyek `rizqflow`. Dicek dari luar:
  http 301 ke https, `/login` 200 lewat Cloudflare, tombol Google dirender, dan masuk dengan Google
  sungguhan berhasil (pemilik, akun produksi pertama punya `google_sub`, tanpa galat di log).
- Claude tidak bisa mengerjakan DNS dan origin Google dari sesi tanpa akses: tidak ada token API Cloudflare
  di PC ini, dan origin klien OAuth tidak bisa diubah lewat `gcloud`/API (hanya Console). Supaya
  DNS bisa dikerjakan Claude lain kali: token Cloudflare "Edit zone DNS" khusus zona
  `roziqrizal.com`, disimpan sebagai env var `CLOUDFLARE_API_TOKEN` (jangan ditempel ke chat).
- Insiden deploy pertama: tanpa `name: rizqflow`, compose ini bentrok dengan habitflow (folder
  sama-sama `server`) dan menimpa container habitflow ±2 menit. Jangan hapus `name:` di
  `docker-compose.yml`. Kronologi di repo build-server, ROADMAP Fase 3.
- **(19 Oktober 2026)** Insiden ini ternyata meninggalkan jejak: container `habitflow` sampai
  sekarang masih berlabel compose project lama `"server"` (bukan `"habitflow"`), jadi deploy
  habitflow wajib pakai `docker compose -p server ...` sampai dirapikan. Detail di
  `habitflow/server/DEPLOY.md` bagian "Hal-hal yang pernah bikin error". `.env.production`
  habitflow juga sempat hilang total dari VPS dan PC lokal hari itu (diselamatkan lewat
  `docker exec <container> env` dari container lama yang masih hidup) — kalau `.env.production`
  rizqflow di repo ini pernah hilang, pakai cara yang sama sebagai jalan darurat terakhir.
- **(24 Oktober 2026) Deploy redesain web** (commit `2b0b50c`): backup manual dulu
  (`mysqldump` lewat `docker exec -e MYSQL_PWD=... shared-mysql`, hasil di
  `~/backups-manual/rizqflow-pra-redesain-2026-10-24-0522.sql.gz`) karena migrasi menghapus kolom.
  Container ternyata belum di-build ulang sejak 18 Oktober (deploy sebelumnya hanya `git pull`),
  jadi 21 migrasi jalan sekaligus — semuanya DONE. `docker compose build` memakan lebih dari 10
  menit (composer install), dan MySQL di sini lambat (tiap migrasi 1–50 detik), jadi pola
  `docker exec -d` untuk migrasi memang perlu. Habitflow tidak tersentuh.

Catatan:

- `NGINX_LISTEN_IP_PROTOCOL=ipv4` wajib di `.env.production` (VPS tanpa IPv6).
- Masuk web: **Google** dan **email+kata sandi** (sejak 2026-10-24); akun dengan email yang sama
  digabung. Masuk email+sandi tidak butuh pengirim email. **Daftar, verifikasi email, dan Lupa kata
  sandi** butuh email dan dikendalikan saklar `EMAIL_CODES_ENABLED` (lihat "Email produksi (Brevo)"
  di bawah): selama `MAIL_MAILER` belum diset di `.env.production` (bawaan `log`), saklar otomatis
  mati, tautan Daftar dan "Lupa kata sandi?" hilang, dan route-nya mengalihkan ke Masuk dengan pesan
  ramah. Akun Google tetap bisa menetapkan kata sandi di Profil tanpa email. Akun demo seed
  (`demo@rizqflow.test` / `password`) hanya ada di database lokal; di produksi seed tidak dijalankan.
- Origin `https://rizqflow.roziqrizal.com` harus terdaftar di Authorized JavaScript origins klien Web
  Google Cloud (lihat `docs/auth-google.md`).
- Migrasi `2026_10_26_000001_create_email_codes_table` membuat tabel `email_codes` dan mengisi
  `email_verified_at` akun lama (semua akun lama berasal dari Google, jadi dianggap terverifikasi).
  Tidak ada worker antrean: email dikirim langsung saat permintaan (sinkron).
- Data di produksi masih kosong per pengguna sampai sync Tahap 9; seed contoh **tidak** dijalankan
  di produksi.
- Jangan generate ulang `APP_KEY` di produksi: sesi dan data terenkripsi dengan kunci lama tidak terbaca.

## Email produksi (Brevo)

Daftar, verifikasi email, dan Lupa kata sandi mengirim kode 6 angka lewat email. Penyedia yang dipilih
pemilik: **Brevo SMTP** (paket gratis, 300 email per hari). Domain pengirim: `mail.roziqrizal.com`
(subdomain khusus, supaya tidak mengganggu email `roziqrizal.com` yang sudah ada). Sebelum langkah ini
selesai, fitur email tetap mati dan tersembunyi (saklar `EMAIL_CODES_ENABLED`, lihat Catatan).

1. **Akun Brevo.** Daftar di brevo.com dengan email pemilik, verifikasi email akun, lengkapi profil
   pengirim yang diminta Brevo. Pilih paket gratis.
2. **Domain pengirim.** Di Brevo: *Senders, Domains & Dedicated IPs → Domains → Add a domain* →
   `mail.roziqrizal.com`. Brevo menampilkan catatan DNS yang harus dibuat; **salin nilainya persis dari
   dashboard Brevo** (nilai dan jumlahnya bisa berubah, jangan menyalin dari dokumen ini). Biasanya:
   - TXT `brevo-code:...` untuk membuktikan kepemilikan domain,
   - DKIM: dua CNAME `brevo1._domainkey.mail` dan `brevo2._domainkey.mail` (atau satu TXT DKIM),
   - SPF: TXT `v=spf1 include:spf.brevo.com ~all` di `mail.roziqrizal.com` (kalau Brevo memintanya),
   - DMARC: TXT `_dmarc.mail` berisi `v=DMARC1; p=none; rua=mailto:<email pemilik>` (mulai dari `p=none`).
3. **Isi DNS di Cloudflare** (zona `roziqrizal.com`, DNS → Add record). Semua catatan email harus
   **DNS only (awan abu-abu, bukan Proxied)**, kalau tidak DKIM tidak terbaca. Lalu di Brevo klik
   *Authenticate this domain* dan tunggu statusnya hijau (bisa beberapa menit sampai jam).
4. **Pengirim.** Di *Senders*, tambahkan pengirim `noreply@mail.roziqrizal.com` (nama "Rizqflow").
   Karena domainnya sudah terautentikasi, alamat ini tidak butuh kotak masuk sungguhan.
5. **Kunci SMTP.** Di Brevo: *SMTP & API → SMTP → Generate a new SMTP key* (nama `rizqflow-prod`).
   Catat **Login SMTP** (bentuknya `xxxx@smtp-brevo.com`, bukan email akun) dan **kunci SMTP**
   (hanya tampil sekali). Jangan ditempel ke chat atau commit.
6. **`.env.production` di VPS** (`~/apps/rizqflow/server/.env.production`), tambahkan:

   ```
   MAIL_MAILER=smtp
   MAIL_HOST=smtp-relay.brevo.com
   MAIL_PORT=587
   MAIL_USERNAME=<Login SMTP Brevo>
   MAIL_PASSWORD=<kunci SMTP Brevo>
   MAIL_FROM_ADDRESS="noreply@mail.roziqrizal.com"
   MAIL_FROM_NAME="Rizqflow"
   MAIL_DAILY_LIMIT=300
   OWNER_EMAIL=<email pemilik, untuk peringatan 80% batas harian>
   ```

   `MAIL_SCHEME` dibiarkan kosong (port 587 memakai STARTTLS otomatis). `EMAIL_CODES_ENABLED` tidak
   perlu diisi: menyala otomatis begitu `MAIL_MAILER` bukan `log`/`array`.
7. **Terapkan.** `env_file` hanya dibaca saat container dibuat, jadi `docker compose up -d
   --force-recreate` (bukan `restart`) dari `~/apps/rizqflow/server`.
8. **Uji kirim.** Dari VPS:

   ```bash
   docker exec rizqflow php artisan tinker --execute="Mail::raw('Uji Rizqflow', fn (\$m) => \$m->to('<email pribadi>')->subject('Uji Rizqflow'));"
   ```

   Cek kotak masuk (dan spam). Buka headers pesan: `dkim=pass`, `spf=pass`, `dmarc=pass`. Di Brevo
   *Transactional → Logs* kirimannya harus tercatat. Lalu uji dari peramban: buka
   `https://rizqflow.roziqrizal.com/register` (tautan Daftar sudah muncul di halaman Masuk),
   daftar dengan email Gmail pribadi, masukkan kode dari email, dan pastikan bisa masuk. Uji juga
   "Lupa kata sandi?".
9. **Batas harian.** Gratis 300 email per hari. Kalau pemakaian mencapai 80% (`MAIL_DAILY_LIMIT`),
   pemilik dikirimi peringatan sekali per hari. Kalau terlewati, kode pendaftar baru tidak terkirim
   sampai besok (Daftar tetap menjawab sama; pengguna bisa meminta kirim ulang).
10. **Mundur.** Hapus `MAIL_MAILER` dari `.env.production` (atau set `EMAIL_CODES_ENABLED=false`) lalu
    `--force-recreate`: Daftar dan Lupa kata sandi kembali tersembunyi, masuk email+sandi dan Google
    tetap jalan.

## Pembelian Google Play (verifikasi pembelian dan notifikasi)

Tahap 9, kode selesai 2026-10-24 di `server/app/Play/`. Aplikasi Android mengirim token pembelian Pro
(`rizqflow_pro`) dan langganan Sync (`rizqflow_sync`) ke `POST /api/purchases/claim`; server
memverifikasinya ke Google Play Developer API lalu mengisi `users.plans`. Paket **tidak pernah**
diberikan tanpa verifikasi Play. Satu token hanya bisa diklaim satu akun (yang pertama mengklaim).
Perubahan dari sisi Play (perpanjangan, kedaluwarsa, penahanan, pengembalian dana) masuk lewat
`POST /api/play/notifications` (Real-time Developer Notifications), dan perintah `play:reconcile`
menjadi jaring pengaman harian.

**Status: hanya terbukti lewat tes otomatis dengan klien palsu.** Tidak ada kredensial Play asli di
repo atau di mesin pengembangan; bentuk jawaban Play, tanda tangan JWT Pub/Sub sungguhan, dan
penjadwal belum pernah dijalankan terhadap layanan sungguhan. Uji nyata ada di Tahap 8 (penguji
lisensi + aplikasi dari Play). Tanpa kredensial, server aman: klaim dijawab 503 dan endpoint
notifikasi dijawab 503 (tidak ada paket yang berubah).

### Variabel `.env.production` (semuanya opsional; kosong = fitur mati)

| Variabel | Isi |
|---|---|
| `PLAY_PACKAGE_NAME` | ID paket aplikasi; bawaan `com.roziqrizal.rizqflow` |
| `PLAY_SERVICE_ACCOUNT_JSON` | Kunci akun layanan Google Cloud: jalur berkas JSON, isi JSON mentah, atau **isi JSON dalam base64 satu baris** (paling cocok untuk `env_file` Docker: `base64 -w0 kunci.json`). Rahasia: hanya di `.env.production` di VPS, jangan di git atau chat |
| `PLAY_RTDN_AUDIENCE` | Audience JWT OIDC pada langganan push Pub/Sub; bila dibiarkan bawaan di Pub/Sub, isinya URL endpoint: `https://rizqflow-api.roziqrizal.com/api/play/notifications` (harus SAMA PERSIS dengan URL langganan push; sampai subdomain itu hidup, host web `rizqflow.roziqrizal.com` melayani aplikasi yang sama dan boleh dipakai sementara, asal audience dan URL ikut diganti bersamaan). Kosong = endpoint menolak semua permintaan dengan 503 |
| `PLAY_RTDN_SERVICE_ACCOUNT_EMAIL` | Email akun layanan penanda tangan push Pub/Sub. **Dianjurkan diisi**: tanpa ini, identitas Google mana pun yang tahu audience bisa membuat token yang lolos (dampaknya terbatas karena notifikasi hanya memicu verifikasi ulang ke Play, tetapi tetap boros kuota) |

`env_file` hanya dibaca saat container dibuat, jadi terapkan dengan `docker compose up -d
--force-recreate`. Migrasi `2026_10_27_000001_create_play_purchases_table` ikut jalan lewat langkah
migrasi deploy biasa.

### Pemasangan (tindakan pemilik; belum dijalankan, nama menu di konsol Google bisa berubah)

1. **Google Cloud.** Di proyek yang dipakai Rizqflow: aktifkan *Google Play Android Developer API*,
   buat akun layanan khusus (mis. `rizqflow-play-verifier`), buat kunci JSON.
2. **Play Console.** *Pengguna dan izin* (Users and permissions): undang email akun layanan itu dan
   beri izin pada aplikasi Rizqflow hanya *Lihat data keuangan, pesanan, dan respons survei
   pembatalan*. Kode hanya membaca (`purchases.subscriptionsv2.get` dan
   `purchases.productsv2.getproductpurchasev2`), jadi **jangan** memberi *Kelola pesanan dan
   langganan*: bila kunci JSON bocor, izin itu memungkinkan pemegangnya membatalkan, mencabut, atau
   mengembalikan dana seluruh pelanggan. Tambahkan izin kelola hanya bila nanti server benar-benar
   meng-acknowledge atau mencabut pembelian. Izin baru bisa butuh beberapa jam untuk berlaku; bila
   log mencatat "Play API menolak akun layanan" (401/403) padahal sudah berjam-jam, itu tanda izin
   baca ini belum cukup di akun Anda (belum pernah dicoba terhadap Play sungguhan).
3. **Isi `PLAY_SERVICE_ACCOUNT_JSON`** (base64) dan `PLAY_PACKAGE_NAME` bila berbeda, lalu
   `--force-recreate`. Uji dari VPS bahwa verifikasi aktif: `docker exec rizqflow php artisan
   play:reconcile` tidak lagi mencetak "belum dikonfigurasi" (tanpa pembelian tercatat ia mencetak
   "Diperiksa 0").
4. **Pub/Sub untuk RTDN.** Buat topik (mis. `play-rtdn`); beri peran *Pub/Sub Publisher* pada topik itu
   ke `google-play-developer-notifications@system.gserviceaccount.com`. Buat langganan **push** ke
   `https://rizqflow-api.roziqrizal.com/api/play/notifications` (host yang sama dengan `API_BASE_URL` build rilis Android; subdomain ini harus sudah hidup, Tahap 12) dengan **autentikasi diaktifkan**
   (pilih akun layanan penanda tangan, mis. `rizqflow-pubsub-push`, dan audience). Agen layanan
   Pub/Sub proyek butuh peran *Service Account Token Creator* pada akun layanan penanda tangan itu.
   Isi `PLAY_RTDN_AUDIENCE` dan `PLAY_RTDN_SERVICE_ACCOUNT_EMAIL` sama dengan pilihan tadi.
5. **Hubungkan di Play Console.** *Monetisasi > Penyiapan monetisasi > Notifikasi pengembang
   waktu nyata*: isi nama topik Pub/Sub, simpan, lalu kirim notifikasi uji. Notifikasi uji harus
   dijawab 204 (lihat log container; tidak ada baris galat).
6. **Cloudflare/nginx** tidak boleh memblokir POST dari Google ke `/api/play/notifications` dan harus
   meneruskan header `Authorization`.

### Perilaku yang perlu diketahui

- **Notifikasi bukan sumber kebenaran.** Isi notifikasi hanya dipakai untuk tahu token mana yang
  berubah; status selalu diambil ulang dari Play. Notifikasi duplikat atau terbalik urutan aman.
  Token yang belum pernah diklaim, paket aplikasi lain, dan testNotification dijawab 204 tanpa efek.
- **Kode jawaban endpoint notifikasi:** 503 audience (atau kredensial Play) belum dikonfigurasi, 401
  token tidak sah (tanpa alasan rinci), 204 diproses/tidak relevan/badan rusak (tidak diulang karena
  pesan rusak tidak akan pernah bisa diurai), 500 Play gagal sementara (Pub/Sub mengulang).
- **Kegagalan menghubungi Play tidak pernah memberi atau mencabut paket** (klaim: status
  `unverifiable`; notifikasi: 500; reconcile: dilewati dan dicoba lagi besok). Yang dinilai ulang
  hanya paket yang barisnya baru dijawab Play pada panggilan itu: klaim yang hanya memuat Pro tidak
  menyentuh paket Sync, dan Sync yang Play-nya gagal tidak dicabut walau `expires_at` tersimpannya
  sudah lewat (baris itu baru dinilai ulang saat Play menjawab untuknya: klaim ulang dari aplikasi,
  notifikasi RTDN, atau `play:reconcile`).
- **Paket tanpa baris pembelian dibiarkan apa adanya**, jadi akun uji/seed/tinker yang diberi `plans`
  manual tidak terhapus. Begitu sebuah paket punya baris pembelian, paket itu mengikuti baris-barisnya.
- **Token pembelian** disimpan terenkripsi dengan `APP_KEY` (dibutuhkan untuk verifikasi ulang) dan
  tidak pernah masuk log, pesan galat, atau respons. Mengganti `APP_KEY` membuat token tersimpan tak
  terbaca: baris itu dilewati (tercatat di log dengan id baris) sampai aplikasi mengklaim ulang token
  yang sama, yang menulis ulangnya. Karena itu `APP_KEY` tetap jangan diganti.
- **Hapus akun** menghapus baris pembelian akun itu (cascade); tokennya lalu bebas diklaim akun lain.

### Keterbatasan dan asumsi yang belum terbukti (uji nyata di Tahap 8)

- **Pengembalian dana Pro hanya terdeteksi bila `productsv2` sendiri berubah** (`purchaseState`
  `CANCELLED`, atau `refundableQuantity` 0). Notifikasi `voidedPurchaseNotification` hanya dipakai
  sebagai penunjuk token yang dicek ulang, bukan sebagai bukti (aturan "isi notifikasi bukan
  status"), dan tidak ada jalur cadangan ke Voided Purchases API. Dokumentasi Play memisahkan
  pembatalan dari refund pembelian berstatus Purchased, dan tidak menyebut field `productsv2`
  berubah setelah refund. **Asumsi ini belum terbukti**: buktikan di Tahap 8 dengan satu pembelian
  uji (penguji lisensi) yang direfund dengan pencabutan lalu jalankan `play:reconcile` atau tunggu
  notifikasinya; bila Pro tidak tercabut, tambahkan pencocokan lewat
  `purchases.voidedpurchases.list`. Pemeriksaan ulang produk sekali beli tiap 30 hari memakai
  `productsv2` yang sama, jadi tidak menolong untuk kasus ini.
- **Token pengganti langganan tidak diikuti.** Server tidak membaca `linkedPurchaseToken` atau
  `outOfAppPurchaseContext.expiredPurchaseToken`. Bila pelanggan Sync mengganti base plan (bulanan ke
  tahunan) atau berlangganan lagi dari menu Play Store tanpa membuka Rizqflow, token lama menjadi tidak
  berlaku dan token baru yang belum pernah diklaim diabaikan notifikasi (204, sesuai aturan "token
  yang belum pernah diklaim: abaikan"). Akibatnya Sync di server/web bisa hilang sementara sampai
  aplikasi dibuka dan mengklaim token baru (otomatis saat aplikasi dibuka). Memperluas aturan itu
  (mengikuti token pengganti ke pemilik baris lama) adalah keputusan pemilik, belum diambil.
- **Baris yang terus dilewati** (Play menjawab 400/404 untuk token tercatat) tidak menutup antrean
  `play:reconcile`: tiap percobaan dicatat di `last_checked_at` sehingga baris itu pindah ke belakang
  antrean, tetapi baris itu tetap dihitung jatuh tempo dan memakai kuota tiap hari sampai tokennya
  diklaim ulang atau Play menjawab tegas (410 untuk langganan = kedaluwarsa).
- **Semua `/api/*` menjawab galat sebagai JSON** (401, 422, dan seterusnya) walau klien tidak
  mengirim `Accept: application/json` (klien Android tidak mengirimnya; sebelumnya 401 berubah
  menjadi pengalihan 302 ke `/login`).

### Penjadwal (`play:reconcile`)

`play:reconcile` dijadwalkan harian pukul 03:30 di `routes/console.php`, memverifikasi ulang
langganan yang hampir atau sudah lewat masa berlaku (dan baris yang verifikasinya basi) supaya
langganan yang kedaluwarsa **tanpa notifikasi** tetap dicabut. **Penjadwal Laravel baru jalan bila
ada cron `schedule:run` di server, dan saat ini belum ada** (Dockerfile dan `docker-compose.yml` repo ini
tidak menjalankan penjadwal). Itu pekerjaan pemilik di Tahap 12. Salah satunya, di crontab host VPS:

```cron
* * * * * docker exec rizqflow php artisan schedule:run >> /dev/null 2>&1
```

Sampai itu terpasang, jalankan manual (aman diulang): `docker exec rizqflow php artisan
play:reconcile` (opsi `--limit=200` membatasi jumlah baris per jalan; kode keluar 1 bila ada baris yang
gagal diverifikasi, mis. Play tak terjangkau). Tanpa penjadwal dan tanpa notifikasi RTDN yang
terhubung, langganan yang kedaluwarsa tetap menyimpan paket Sync sampai salah satunya berjalan (atau
sampai aplikasi mengklaim ulang tokennya dan Play menjawab: server tidak mencabut paket hanya karena
`expires_at` tersimpan sudah lewat, tanpa jawaban Play untuk baris itu).
