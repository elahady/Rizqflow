<?php

namespace App\Models;

use Carbon\CarbonInterface;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\HasOne;
use Illuminate\Support\Collection;

class Room extends Model
{
    use HasFactory;

    protected $fillable = [
        'user_id', 'name', 'icon', 'type', 'slot',
        'allocation_percent', 'allocation_cap_minor', 'budget_minor', 'used_minor', 'sort_order', 'archived',
    ];

    protected $casts = [
        'archived' => 'boolean',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }

    /**
     * Ruang yang belum diarsipkan — dipakai di Denah, S10, dan S11.
     */
    public function scopeActive($query)
    {
        return $query->where('archived', false);
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class);
    }

    public function categories(): HasMany
    {
        return $this->hasMany(Category::class);
    }

    public function role(): HasOne
    {
        return $this->hasOne(RoomRole::class);
    }

    /**
     * Pos-pos ruang ini untuk Detail ruang (S11) pada satu bulan, mengikuti RoomDetailLoader di :domain.
     * Pembanding tiap pos adalah irisan jatah ruang menurut bobot bila pos punya bobot, kalau tidak
     * seluruh jatah ruang. Pos aktif selalu tampil; pos terarsip dan pos sistem hanya bila bulan itu
     * memakainya. Urut dari pengeluaran terbesar.
     *
     * @return Collection<int, array{category: Category, spent_minor: int, budget_minor: int, percent: ?int}>
     */
    public function categoryBreakdown(CarbonInterface $month): Collection
    {
        $spentBy = $this->transactions()
            ->where('type', 'pengeluaran')
            ->whereBetween('occurred_on', [$month->copy()->startOfMonth()->toDateString(), $month->copy()->endOfMonth()->toDateString()])
            ->whereNotNull('category_id')
            ->groupBy('category_id')
            ->selectRaw('category_id, SUM(amount_minor) as total')
            ->pluck('total', 'category_id');

        $own = $this->categories()->get();
        $weightTotal = $own->where('archived', false)->sum(fn (Category $c) => $c->weight ?? 0);

        return $own
            ->filter(fn (Category $c) => (! $c->archived && ! $c->is_system) || ($spentBy[$c->id] ?? 0) > 0)
            ->map(function (Category $c) use ($spentBy, $weightTotal) {
                $spent = (int) ($spentBy[$c->id] ?? 0);
                $budget = $c->weight !== null && $weightTotal > 0
                    ? intdiv($this->budget_minor * $c->weight, $weightTotal)
                    : $this->budget_minor;

                return [
                    'category' => $c,
                    'spent_minor' => $spent,
                    'budget_minor' => $budget,
                    // Dibulatkan ke bawah seperti progressBp di :domain; bisa lebih dari 100.
                    'percent' => $budget > 0 ? intdiv($spent * 100, $budget) : null,
                ];
            })
            ->sortBy([['spent_minor', 'desc'], [fn ($a, $b) => $a['category']->sort_order <=> $b['category']->sort_order]])
            ->values();
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
