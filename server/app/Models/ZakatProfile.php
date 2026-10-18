<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

class ZakatProfile extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'name', 'net_wealth_minor', 'nisab_minor',
        'haul_started_label', 'haul_due_label', 'haul_day', 'haul_total_days', 'status', 'archived',
    ];

    protected $casts = [
        'archived' => 'boolean',
    ];

    public function scopeActive($query)
    {
        return $query->where('archived', false);
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function isAboveNisab(): bool
    {
        return $this->net_wealth_minor >= $this->nisab_minor;
    }

    /** 2,5% dari harta bersih — bisa diubah pengguna sebelum disimpan, lihat docs/ui-flow.md S17. */
    public function suggestedZakatMinor(): int
    {
        return (int) round($this->net_wealth_minor * 0.025);
    }
}
