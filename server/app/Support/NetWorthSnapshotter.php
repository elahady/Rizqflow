<?php

namespace App\Support;

use App\Models\NetWorthSnapshot;
use App\Models\User;
use Illuminate\Support\Carbon;

/**
 * Kekayaan bersih sepanjang waktu (Pro, web-only): menangkap satu baris per hari dari saldo semua
 * akun ditambah harta bersih semua profil zakat aktif, dipanggil dari Denah::mount() setiap halaman
 * itu dibuka — padanan web untuk "setiap aplikasi dibuka", sama seperti RecurringRunner. Aman
 * dipanggil berulang kali per hari (unique [user_id, snapshot_date] di migrasi mencegah duplikat).
 */
class NetWorthSnapshotter
{
    public static function captureIfMissing(User $user, ?Carbon $today = null): void
    {
        $date = ($today ?? Carbon::today())->toDateString();

        if (NetWorthSnapshot::where('user_id', $user->id)->whereDate('snapshot_date', $date)->exists()) {
            return;
        }

        $accountsMinor = (int) $user->accounts()->sum('balance_minor');
        $zakatWealthMinor = (int) $user->zakatProfiles()->active()->sum('net_wealth_minor');

        NetWorthSnapshot::create([
            'user_id' => $user->id,
            'snapshot_date' => $date,
            'accounts_minor' => $accountsMinor,
            'zakat_wealth_minor' => $zakatWealthMinor,
            'total_minor' => $accountsMinor + $zakatWealthMinor,
        ]);
    }
}
