<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Salinan kunci akun yang dibungkus kode pemulihan (Tahap 13). Lihat migrasi `create_recovery_keys_table`
 * dan `Api\RecoveryKeyController`.
 *
 * Sengaja TANPA cast `encrypted` dan tanpa enkripsi ganda dengan APP_KEY: blob ini sudah terenkripsi
 * di klien dengan kunci yang hanya diturunkan dari kode pemulihan. Membungkusnya lagi dengan APP_KEY
 * tidak menambah kerahasiaan terhadap server (APP_KEY ada di server) dan justru memberi kesan server
 * "memegang kunci" serta membuat blob tak terbaca bila APP_KEY berganti (bandingkan PlayPurchase,
 * yang memang butuh token utuh sehingga terenkripsi). Server memperlakukan `wrapped_key` sebagai byte
 * buram: tidak di-cast, tidak dicatat, dan `$hidden` mencegahnya ikut serialisasi tak sengaja
 * (toArray/toJson, respons, log); hanya GET milik pemilik yang membacanya secara eksplisit.
 */
class RecoveryKey extends Model
{
    /** Versi pembungkus yang dikenal: 1 = BackupCrypto AES-256-GCM, kunci PBKDF2 dari frasa 24 kata. */
    public const KNOWN_VERSIONS = [1];

    /** Batas ukuran blob terdekode (byte). Blob sebenarnya (salt+IV+kunci+tag) puluhan byte. */
    public const MAX_BYTES = 8192;

    /**
     * Panjang base64 standar untuk MAX_BYTES byte: 4 * ceil(8192 / 3) = 10924 (ditulis literal karena
     * ekspresi konstanta PHP tak boleh memanggil fungsi; tes menjaganya tetap sama dengan rumus itu).
     * Catatan: 8193 byte juga 10924 karakter, jadi batas ini hanya penjaga awal; ukuran terdekode
     * tetap diperiksa terpisah.
     */
    public const MAX_ENCODED_LENGTH = 10924;

    protected $fillable = [
        'user_id',
        'version',
        'wrapped_key',
    ];

    protected $hidden = [
        'wrapped_key',
    ];

    protected function casts(): array
    {
        return [
            'version' => 'integer',
        ];
    }

    public function user(): BelongsTo
    {
        return $this->belongsTo(User::class);
    }
}
