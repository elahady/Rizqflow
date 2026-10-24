# Mockup web Rizqflow

Mockup klik untuk web (`server/`), dibuat 2026-10-24 bersama pemilik memakai Claude Design (artifact). Folder ini adalah **sumber lengkap** mockup, disimpan di repo supaya tidak hilang kalau artifact tidak bisa dibuka.

- **Artifact:** https://claude.ai/artifact/L3KMKzj1QXGV69hBBhoQKc (versi 15). Akses diatur lewat menu Share di claude.ai: per 2026-10-24 artifact dibagikan ke organisasi pemilik, jadi akun di luar organisasi itu tidak bisa membukanya kecuali diundang. Kalau tidak bisa dibuka, terbitkan ulang dari folder ini (lihat "Menerbitkan ulang").
- **Keputusan dan konteks:** [../../konsep.md](../../konsep.md), butir "Desain web baru" (2026-10-24).
- **Riset pasar khusus web:** [../../research-market/research-web.md](../../research-market/research-web.md).
- Prototipe Android (S01 sampai S35) ada di [../prototype.html](../prototype.html); folder ini hanya untuk web.

## Isi (`project/`)

Urutan tabel mengikuti alur klik: Masuk, Buka kunci, Denah, lalu menu di sidebar.

| Berkas | Layar | Yang didemokan |
|---|---|---|
| `Masuk.dc.html` | Masuk | Kartu masuk meniru habitflow (email dan sandi, Google, Daftar, Lupa sandi, tombol tema; ukuran dan pesan galat disalin dari `habitflow/server/resources/views/components/auth-card.blade.php`), plus catatan bahwa setelah masuk perlu kode 24 kata. Tweaks: `metode` (email-google atau google-saja), `state` (normal, salah, keluar, sesi), `theme`. |
| `Kunci.dc.html` | Buka kunci | Kode pemulihan 24 kata: kolom isian, penghitung kata, pratinjau 24 kolom. Tombol "Isi contoh" hanya ada di mockup. |
| `Main.dc.html` | Denah | Sidebar bergrup, hero rezeki dengan bar alokasi, jadwal 7 hari, tiga kartu ruang dengan "sisa aman per hari" dan laju, transaksi terbaru, perlu perhatian. Ini berkas entry kanvas. |
| `Transaksi.dc.html` | Transaksi | Baris catat cepat, tabel kerja, pilih banyak baris (total dan ekspor), tab "Tak terlacak". |
| `Alirkan.dc.html` | Catat rezeki masuk | Pratinjau pembagian ke ruang dengan metode sisa terbesar (sama dengan `AllocationEngine`); ketik nominal, coba 1.234.567. |
| `Analisa.dc.html` | Analisa, Aliran | Diagram Sankey (pemasukan ke ruang ke pos dan sisa), sorot ruang, tabel kembaran. |
| `Ruang.dc.html` | Ruang (Keluarga) | Laju belanja vs laju jatah (geser hari), tabel pos, riwayat 6 bulan, transaksi terbaru. |
| `Zakat.dc.html` | Zakat | Nisab, haul, tunaikan (dengan lembaga penerima dan nomor bukti setor), rekap tahunan. |
| `Keluarga.dc.html` | Keluarga | Cakupan bersama, tabel "siapa melihat apa", amplop bulanan, notifikasi tanpa nominal. |
| `Riset.dc.html` | Papan riset | Temuan riset web, tingkat keyakinan, sumber (ringkasan teksnya di research-web.md). |
| `Navigasi.dc.html` | Papan navigasi | Audit halaman web yang live, usulan sidebar, urutan kerja, keputusan. |
| `rizqflow.css` | | Token (disalin dari [../tokens.css](../tokens.css)) dan kelas komponen `rf-*`, dipakai bersama semua layar. |
| `canvas.json` | | Indeks kanvas: posisi dan ukuran papan, catatan tempel. |

## Konvensi di mockup

- Layar adalah halaman lebar 1360px yang fluid (menumpuk di lebar ponsel). Kerangka sidebar dan bilah atas disalin per layar; tidak ada komponen bersama antar berkas.
- Semua angka fiktif dan konsisten antar layar (Oktober 2026, persona Ayu dan Dimas, rezeki Rp 18.000.000 dengan aturan 10 / 30 / 60). Bukan data pemilik.
- Teks UI memakai "kamu/-mu" (sesuai wireframe Android dan habitflow). Papan Riset dan Navigasi adalah dokumen untuk pemilik, jadi memakai "Anda".
- Warna ruang dan status memakai token yang sudah divalidasi ([../README.md](../README.md)). Peringatan memakai kuning, ikon, dan teks, tanpa merah; status selalu ikon plus teks.
- Setiap grafik punya tabel kembarannya. Sankey dan grafik laju dihitung dari angka yang sama dengan tabelnya.
- Tweaks `theme` (terang atau gelap) ada di tiap layar; Masuk punya Tweaks tambahan.

## Menerbitkan ulang (dari akun Claude lain)

1. Buat Artifact bertipe **Design** (quickstart `intent: design`, tanpa design system).
2. Terbitkan ke artifact itu dengan `root` = folder ini, `file_path` = `project/canvas.json`, dan `files` = semua berkas di `project/` (bentuk `{"project/X": "project/X"}`).
3. `support.js` yang dirujuk tiap berkas disediakan runtime artifact dan tidak ada di repo. Jangan menerbitkan `index.html`, `SKILL.md`, atau apa pun di `artifact-type/` (milik tipe artifact).

## Belum dibuat dan belum diperiksa

- Layar yang belum dibuat: Jadwal (Tagihan dan Berulang), Utang-piutang, Akun, Impor dan ekspor, Profil dan paket; juga Daftar, verifikasi email, lupa dan reset sandi, serta layar "simpan kode pemulihan 24 kata" saat daftar.
- Render belum diperiksa di browser (tidak ada screenshot). Mode gelap hanya lewat Tweaks `theme` dan belum diuji; lebar ponsel belum diuji.
- Pencarian bahasa natural di bilah atas dan baris catat cepat butuh port parser bahasa natural dari Android.
- Tombol yang tidak berfungsi (filter, "Simpan tampilan", dan sejenisnya) hanya tampilan.
