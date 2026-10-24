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
