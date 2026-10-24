<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Builder;
use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Pos di dalam ruang (Belanja bulanan, Listrik dan air, ...). Lihat tabel `category` di docs/model-data.md.
 * Pos sistem (`is_system`) tidak bisa dihapus atau diganti nama: Zakat mal (ruang Memberi) dan
 * Tak terlacak (setiap ruang, juga tujuan koreksi saldo dan impor yang belum dirapikan).
 */
class Category extends Model
{
    use HasFactory;

    /** Nama pos sistem, sama dengan RoomTemplates.UNTRACKED di :domain. */
    public const UNTRACKED = 'Tak terlacak';

    /** Nama pos sistem zakat di ruang Memberi, sama dengan RoomTemplates.ZAKAT di :domain. */
    public const ZAKAT = 'Zakat mal';

    /** Pos awal ruang baru, sama dengan RuleService.OTHER_CATEGORY di :domain. */
    public const OTHER = 'Lain-lain';

    protected $fillable = [
        'room_id', 'name', 'weight', 'is_system', 'archived', 'sort_order',
    ];

    protected $casts = [
        'is_system' => 'boolean',
        'archived' => 'boolean',
        'weight' => 'integer',
    ];

    public function room(): BelongsTo
    {
        return $this->belongsTo(Room::class);
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class);
    }

    public function scopeActive(Builder $query): Builder
    {
        return $query->where($query->qualifyColumn('archived'), false);
    }

    public function scopeSystem(Builder $query): Builder
    {
        return $query->where($query->qualifyColumn('is_system'), true);
    }

    /** Pos sistem Tak terlacak. */
    public function scopeUntracked(Builder $query): Builder
    {
        return $query->system()->where($query->qualifyColumn('name'), self::UNTRACKED);
    }

    /** Pos sistem Zakat mal. */
    public function scopeZakat(Builder $query): Builder
    {
        return $query->system()->where($query->qualifyColumn('name'), self::ZAKAT);
    }

    public function isUntracked(): bool
    {
        return $this->is_system && $this->name === self::UNTRACKED;
    }

    public function isZakat(): bool
    {
        return $this->is_system && $this->name === self::ZAKAT;
    }
}
