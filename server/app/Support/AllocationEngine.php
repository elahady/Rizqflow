<?php

namespace App\Support;

/**
 * Porting dari AllocationEngine.allocateWaterfall di :domain (Pro, "Aturan alokasi lanjutan"):
 * ruang diisi berurutan menurut prioritas (urutan $caps dalam array, sama dengan urutan
 * rooms.sort_order yang dipakai AturanAlokasi), masing-masing sampai batas atasnya (atau seluruh
 * sisa bila tak terbatas), dan kelebihannya mengalir ke ruang berikutnya. Aritmetika bulat eksak,
 * tidak ada pembulatan sama sekali karena setiap ruang hanya mengambil min(sisa, batas).
 *
 * Web belum punya proses pencatatan pemasukan yang benar-benar memanggil ini (lihat roadmap.md
 * Tahap 14 — "Catat transaksi baru" masih scaffold); kelas ini dulu dipakai sebagai alat hitung
 * dan diuji sendiri, menyusul dipakai sungguhan begitu pencatatan pemasukan web ada.
 */
class AllocationEngine
{
    /**
     * @param  list<array{room_id: int, cap_minor: ?int}>  $caps  urutan prioritas; cap_minor null = tak terbatas
     * @return array{shares: list<array{room_id: int, amount_minor: int}>, unallocated_minor: int}
     */
    public static function allocateWaterfall(int $amountMinor, array $caps): array
    {
        $remaining = $amountMinor;
        $shares = [];

        foreach ($caps as $cap) {
            $limit = $cap['cap_minor'];
            $take = $limit === null ? $remaining : min($remaining, $limit);
            $remaining -= $take;
            $shares[] = ['room_id' => $cap['room_id'], 'amount_minor' => $take];
        }

        return ['shares' => $shares, 'unallocated_minor' => $remaining];
    }
}
