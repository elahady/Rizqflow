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

Catatan:

- `NGINX_LISTEN_IP_PROTOCOL=ipv4` wajib di `.env.production` (VPS tanpa IPv6).
- `APP_ENV=production` mematikan masuk demo; hanya Masuk dengan Google. Origin
  `https://rizqflow.roziqrizal.com` harus terdaftar di Authorized JavaScript origins klien Web
  Google Cloud (lihat `docs/auth-google.md`).
- Data di produksi masih kosong per pengguna sampai sync Tahap 9; seed contoh **tidak** dijalankan
  di produksi.
- Jangan generate ulang `APP_KEY` di produksi: sesi dan data terenkripsi dengan kunci lama tidak terbaca.
