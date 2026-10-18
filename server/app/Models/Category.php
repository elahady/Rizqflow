<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Factories\HasFactory;
use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Pos di dalam ruang (Belanja bulanan, Listrik dan air, ...). Lihat tabel `category` di docs/model-data.md.
 */
class Category extends Model
{
    use HasFactory;

    protected $fillable = [
        'room_id', 'name', 'weight', 'is_system', 'archived', 'sort_order',
    ];

    protected $casts = [
        'is_system' => 'boolean',
        'archived' => 'boolean',
    ];

    public function room(): BelongsTo
    {
        return $this->belongsTo(Room::class);
    }

    public function transactions(): HasMany
    {
        return $this->hasMany(Transaction::class);
    }
}
