<?php

namespace App\Support;

use App\Models\User;
use App\Models\UserMetricSnapshot;
use Carbon\CarbonInterface;

/**
 * Metrik pengguna anonim di server (Tahap 9, server-sync.md "Metrik pengguna anonim"): jumlah akun
 * server, akun dengan paket Pro, dan akun dengan paket Sync, dihitung dari `users.plans` (yang diisi
 * PlanSynchronizer dari pembelian Google Play terverifikasi).
 *
 * Hanya HITUNGAN: tidak pernah membaca atau menyimpan user_id, email, nama, transaksi, nominal, atau
 * isi sinkron. Firebase Analytics bukan sumber angka ini (keputusan pemilik 2026-10-24).
 */
class UserMetrics
{
    /** @return array{pengguna_total: int, pengguna_pro: int, pengguna_sync: int} */
    public function hitung(): array
    {
        return [
            'pengguna_total' => User::query()->count(),
            'pengguna_pro' => User::query()->whereJsonContains('plans', Plan::Pro->value)->count(),
            'pengguna_sync' => User::query()->whereJsonContains('plans', Plan::Sync->value)->count(),
        ];
    }

    /** Simpan potret untuk tanggal itu (UTC). Dijalankan ulang di hari yang sama: menimpa angka hari itu. */
    public function simpan(CarbonInterface $tanggal): UserMetricSnapshot
    {
        return UserMetricSnapshot::query()->updateOrCreate(
            ['tanggal' => $tanggal->copy()->utc()->toDateString()],
            $this->hitung(),
        );
    }
}
