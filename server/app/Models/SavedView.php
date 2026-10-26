<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/** Tampilan tersimpan satu halaman (lihat migrasi saved_views). Hanya milik user pemiliknya. */
class SavedView extends Model
{
    protected $fillable = ['user_id', 'page', 'name', 'filters'];

    protected $casts = [
        'filters' => 'array',
    ];

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
