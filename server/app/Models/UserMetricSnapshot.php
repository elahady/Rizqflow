<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * Potret harian metrik pengguna anonim (Tahap 9): hanya hitungan agregat, tanpa identitas atau data
 * keuangan. Ditulis oleh UserMetrics::simpan lewat perintah `metrik:pengguna --simpan`.
 */
class UserMetricSnapshot extends Model
{
    protected $fillable = ['tanggal', 'pengguna_total', 'pengguna_pro', 'pengguna_sync'];

    protected function casts(): array
    {
        // `tanggal` sengaja tanpa cast `date`: cast itu menulis "Y-m-d H:i:s" di SQLite sehingga pencarian
        // updateOrCreate dengan "Y-m-d" tidak cocok dan menabrak indeks unik.
        return [
            'pengguna_total' => 'integer',
            'pengguna_pro' => 'integer',
            'pengguna_sync' => 'integer',
        ];
    }
}
