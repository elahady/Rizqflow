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
  http 301 ke https, `/login` 200 lewat Cloudflare, tombol Google dirender. Belum diuji: klik masuk
  sungguhan.
- Claude tidak bisa mengerjakan DNS dan origin Google dari sesi tanpa akses: tidak ada token API Cloudflare
  di PC ini, dan origin klien OAuth tidak bisa diubah lewat `gcloud`/API (hanya Console). Supaya
  DNS bisa dikerjakan Claude lain kali: token Cloudflare "Edit zone DNS" khusus zona
  `roziqrizal.com`, disimpan sebagai env var `CLOUDFLARE_API_TOKEN` (jangan ditempel ke chat).
- Insiden deploy pertama: tanpa `name: rizqflow`, compose ini bentrok dengan habitflow (folder
  sama-sama `server`) dan menimpa container habitflow ±2 menit. Jangan hapus `name:` di
  `docker-compose.yml`. Kronologi di repo build-server, ROADMAP Fase 3.

Catatan:

- `NGINX_LISTEN_IP_PROTOCOL=ipv4` wajib di `.env.production` (VPS tanpa IPv6).
- `APP_ENV=production` mematikan masuk demo; hanya Masuk dengan Google. Origin
  `https://rizqflow.roziqrizal.com` harus terdaftar di Authorized JavaScript origins klien Web
  Google Cloud (lihat `docs/auth-google.md`).
- Data di produksi masih kosong per pengguna sampai sync Tahap 9; seed contoh **tidak** dijalankan
  di produksi.
- Jangan generate ulang `APP_KEY` di produksi: sesi dan data terenkripsi dengan kunci lama tidak terbaca.
