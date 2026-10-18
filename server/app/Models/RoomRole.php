<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Support\Carbon;

/**
 * Sistem per peran (Pro, S32): lihat Role.kt di :domain untuk acuan lengkap. Investor: "Catat
 * sekarang" di App\Livewire\Ruang membuat pengeluaran biasa, menggerakkan saldo akun — sama
 * seperti Bill::pay(). Trader: peringatan risiko per trade saat Catat belum bisa diterapkan di web
 * karena belum ada proses pencatatan pengeluaran yang sungguhan berjalan (lihat roadmap.md).
 */
class RoomRole extends Model
{
    use HasFactory;

    public const MAX_RISK_BP = 5_000;

    public const MAX_DCA_DAY = 28;

    protected $fillable = [
        'room_id', 'kind', 'capital_minor', 'risk_bp',
        'dca_amount_minor', 'dca_day_of_month', 'dca_account_id', 'dca_category_id', 'dca_last_recorded',
    ];

    protected $casts = [
        'dca_last_recorded' => 'date',
    ];

    public function room(): BelongsTo
    {
        return $this->belongsTo(Room::class);
    }

    public function dcaAccount(): BelongsTo
    {
        return $this->belongsTo(Account::class, 'dca_account_id');
    }

    public function dcaCategory(): BelongsTo
    {
        return $this->belongsTo(Category::class, 'dca_category_id');
    }

    /** Modal dikali persentase risiko, dibulatkan ke bawah — eksak tanpa floating point. */
    public function maxRiskPerTradeMinor(): int
    {
        return intdiv($this->capital_minor * $this->risk_bp, 10_000);
    }

    public function dcaStatusOn(Carbon $today): string
    {
        if ($this->dca_last_recorded !== null && $this->dca_last_recorded->isSameMonth($today)) {
            return 'selesai';
        }

        return $today->day >= $this->dca_day_of_month ? 'jatuh_tempo' : 'akan_datang';
    }
}
