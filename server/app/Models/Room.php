<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

class Room extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'name', 'icon', 'type', 'slot',
        'allocation_percent', 'budget_minor', 'used_minor', 'sort_order',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class);
    }

    public function usedPercent(): int
    {
        if ($this->budget_minor <= 0) {
            return 0;
        }

        return (int) round(min($this->used_minor / $this->budget_minor, 1) * 100);
    }

    public function remainingMinor(): int
    {
        return max($this->budget_minor - $this->used_minor, 0);
    }

    /**
     * Status ruang mengikuti tipenya (Menunaikan, Menumbuhkan, Mencukupi) — lihat docs/konsep.md.
     * Ambang 85% untuk tipe Mencukupi dikonfirmasi 2026-09-20.
     */
    public function status(): string
    {
        $percent = $this->usedPercent();

        if ($percent >= 100) {
            return 'terpenuhi';
        }

        if ($this->type === 'mencukupi' && $percent >= 85) {
            return 'perlu_perhatian';
        }

        return 'berjalan';
    }
}
