<?php

namespace App\Support\SmartInput;

/**
 * Satu kata masukan beserta status terpakainya selama penguraian; padanan kelas privat Token di
 * SmartInput.kt. Kata yang terpakai (nominal, tanggal, akun, kata jenis, nama pos) tidak masuk catatan.
 *
 * @internal Hanya untuk SmartInputParser.
 */
final class Token
{
    /** Tanda baca yang dibuang dari ujung kata. Titik sengaja tidak termasuk ("rp." dan "25.000"). */
    public const EDGE_PUNCTUATION = ',;:!?()"\'';

    /** Huruf kecil tanpa tanda baca di ujung; dasar semua pencocokan. */
    public readonly string $low;

    public bool $used = false;

    public function __construct(public readonly string $raw)
    {
        $this->low = trim(mb_strtolower($raw, 'UTF-8'), self::EDGE_PUNCTUATION);
    }
}
