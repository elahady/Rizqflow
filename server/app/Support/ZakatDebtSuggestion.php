<?php

namespace App\Support;

use App\Models\Debt;
use App\Models\User;

/**
 * Saran nilai piutang untuk profil harta zakat (porting `DebtService.zakatSuggestion` di :domain, S15
 * Android). Sisa piutang yang diperkirakan kembali: piutang (arah 'piutang') yang belum lunas dan tidak
 * ditandai macet. Tidak pernah masuk otomatis ke profil; hanya diisi ke form bila pengguna menekan Pakai.
 *
 * Saran utang jangka pendek di Android (pengurang harta) belum diport di sini.
 */
class ZakatDebtSuggestion
{
    public static function receivableMinor(User $user): int
    {
        return (int) Debt::query()
            ->where('user_id', $user->id)
            ->where('direction', 'piutang')
            ->where('collectible', true)
            ->get()
            ->reject(fn (Debt $debt) => $debt->isSettled())
            ->sum(fn (Debt $debt) => $debt->outstandingMinor());
    }
}
