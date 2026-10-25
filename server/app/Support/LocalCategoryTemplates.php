<?php

namespace App\Support;

/**
 * Nama usulan pos lokal Indonesia (porting `LOCAL_CATEGORY_TEMPLATES` di :domain, Management.kt; Tahap
 * 10, Gratis). Hanya usulan nama saat menambah pos di Kelola: pengguna tetap bisa mengetik nama lain,
 * dan usulan yang sudah aktif di ruang itu tidak ditawarkan lagi. Nama-nama ini sengaja sama persis
 * dengan Android (termasuk tanda "&").
 */
final class LocalCategoryTemplates
{
    /** @var list<string> */
    public const NAMES = ['Arisan', 'THR & Lebaran', 'Kiriman orang tua', 'Cicilan & paylater'];

    /**
     * Usulan yang belum dipakai pos aktif di ruang itu. Pembandingan tanpa membedakan huruf besar-kecil,
     * sama seperti `ManagementService` di Android.
     *
     * @param  list<string>  $activeNames  nama pos aktif (tidak diarsipkan) di ruang yang dipilih
     * @return list<string>
     */
    public static function suggestionsFor(array $activeNames): array
    {
        $existing = array_map(mb_strtolower(...), $activeNames);

        return array_values(array_filter(
            self::NAMES,
            fn (string $name) => ! in_array(mb_strtolower($name), $existing, true),
        ));
    }
}
