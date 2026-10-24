<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\HasOne;

/**
 * Ruang (Memberi, Diri, Keluarga, ...). Yang tersimpan hanya identitas dan aturan alokasinya
 * (`allocation_percent` untuk mode persentase, `allocation_cap_minor` untuk mode lanjutan);
 * jatah, terpakai, dan status per bulan diturunkan dari transaksi lewat App\Support\MonthLedger
 * (lihat RoomMonth), tidak pernah disimpan di sini (docs/model-data.md).
 */
class Room extends Model
{
    use HasFactory;

    public const TYPE_MENUNAIKAN = 'menunaikan';

    public const TYPE_MENUMBUHKAN = 'menumbuhkan';

    public const TYPE_MENCUKUPI = 'mencukupi';

    protected $fillable = [
        'user_id', 'name', 'icon', 'type', 'slot',
        'allocation_percent', 'allocation_cap_minor', 'sort_order', 'archived',
    ];

    protected $casts = [
        'archived' => 'boolean',
        'allocation_percent' => 'integer',
        'allocation_cap_minor' => 'integer',
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

    /** Potret alokasi dari pemasukan yang dialirkan ke ruang ini (dasar jatah per bulan). */
    public function allocationEntries(): HasMany
    {
        return $this->hasMany(AllocationEntry::class);
    }

    public function role(): HasOne
    {
        return $this->hasOne(RoomRole::class);
    }
}
