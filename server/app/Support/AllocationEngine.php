<?php

namespace App\Support;

use InvalidArgumentException;

/**
 * Porting AllocationEngine di :domain (domain/.../allocation/AllocationEngine.kt). Kalkulator murni
 * tanpa Laravel, jadi bisa diuji sebagai unit test biasa. Uang selalu bilangan bulat rupiah dan
 * persentase selalu basis point (1% = 100, 100% = 10.000); tidak ada floating point di mana pun.
 *
 * **Batas nominal.** Kotlin memakai BigInteger; PHP hanya punya int 64-bit. Hitungan inti adalah
 * nominal × basis point, jadi nominal paling besar yang aman adalah PHP_INT_MAX / 10.000 =
 * Rp 922.337.203.685.477 (sekitar 922 triliun, jauh di atas nominal wajar). Di atasnya ditolak
 * dengan InvalidArgumentException, bukan dihitung dengan hasil meluap.
 *
 * Validasi di sini setara `require` di Kotlin (galat program, InvalidArgumentException). Pesan
 * yang bisa ditampilkan ke pengguna ada di App\Support\Ledger (LedgerException).
 */
class AllocationEngine
{
    /** 100% dalam basis point. */
    public const FULL_BP = 10_000;

    /** Nominal terbesar yang aman: nominal × 10.000 harus muat di int 64-bit. */
    public const MAX_AMOUNT_MINOR = 922_337_203_685_477;

    /**
     * Alokasi persentase dengan **metode sisa terbesar** (keputusan 2026-09-21): tiap ruang lebih dulu
     * menerima bagian yang dibulatkan ke bawah, lalu sisa rupiahnya (selalu kurang dari jumlah ruang)
     * dibagikan satu-satu ke ruang dengan pecahan terbesar; pecahan sama diputus oleh urutan aturan
     * (ruang lebih awal = prioritas lebih tinggi). Ruang 0% tidak pernah menerima sisa. Bila total
     * aturan di bawah 100%, bagian yang tidak diatur menjadi `unallocated_minor` ("belum dialirkan").
     *
     * Contoh: Rp 1.234.567 dengan 10/50/40% menjadi 123.457 / 617.283 / 493.827; dengan 10/30/60%
     * menjadi 123.457 / 370.370 / 740.740 dan `rounding_minor` = 1.
     *
     * @param  list<array{room_id: int, share_bp: int}>  $rules  urutan = prioritas ruang
     * @return array{
     *     shares: list<array{room_id: int, share_bp: int, amount_minor: int}>,
     *     unallocated_minor: int,
     *     rounding_minor: int,
     * }
     *   `shares` mengikuti urutan $rules. Jumlah semua `amount_minor` + `unallocated_minor` selalu
     *   persis nominal. `rounding_minor` adalah sisa pembulatan (rupiah) yang dibagikan satu-satu
     *   ke ruang dengan pecahan terbesar, untuk keterangan "Pembulatan: sisa Rp X dibagikan ...".
     *
     * @throws InvalidArgumentException nominal negatif atau di atas MAX_AMOUNT_MINOR, basis point di
     *                                  luar 0..10.000, ruang ganda, atau total aturan di atas 100%
     */
    public static function allocate(int $amountMinor, array $rules): array
    {
        self::assertAmount($amountMinor);
        $rules = array_values($rules);

        $seen = [];
        $totalBp = 0;
        foreach ($rules as $rule) {
            $bp = $rule['share_bp'];
            if ($bp < 0 || $bp > self::FULL_BP) {
                throw new InvalidArgumentException("Basis point harus 0 sampai ".self::FULL_BP.", bukan {$bp}");
            }
            if (isset($seen[$rule['room_id']])) {
                throw new InvalidArgumentException('Satu ruang muncul lebih dari sekali di aturan');
            }
            $seen[$rule['room_id']] = true;
            $totalBp += $bp;
        }
        if ($totalBp > self::FULL_BP) {
            throw new InvalidArgumentException("Total aturan melebihi 100%: {$totalBp} basis point");
        }

        $amounts = [];
        $fractions = [];
        foreach ($rules as $i => $rule) {
            // Aman dari luapan: nominal <= MAX_AMOUNT_MINOR dan basis point <= 10.000.
            $exact = $amountMinor * $rule['share_bp'];
            $amounts[$i] = intdiv($exact, self::FULL_BP);
            $fractions[$i] = $exact % self::FULL_BP;
        }

        // Bagian yang memang diatur; sisanya tidak dibagikan ke ruang mana pun.
        $target = intdiv($amountMinor * $totalBp, self::FULL_BP);
        $rounding = $target - array_sum($amounts);

        // Pecahan terbesar dulu, pecahan sama: urutan aturan lebih awal.
        $order = array_keys($rules);
        usort($order, fn (int $a, int $b) => [$fractions[$b], $a] <=> [$fractions[$a], $b]);

        $leftover = $rounding;
        foreach ($order as $i) {
            if ($leftover === 0 || $fractions[$i] === 0) {
                break;
            }
            $amounts[$i]++;
            $leftover--;
        }
        if ($leftover !== 0) {
            // Tidak mungkin terjadi: sisa selalu lebih kecil dari jumlah ruang berpecahan.
            throw new \LogicException("Sisa pembulatan tidak habis dibagikan: {$leftover}");
        }

        $shares = [];
        foreach ($rules as $i => $rule) {
            $shares[] = ['room_id' => $rule['room_id'], 'share_bp' => $rule['share_bp'], 'amount_minor' => $amounts[$i]];
        }

        return [
            'shares' => $shares,
            'unallocated_minor' => $amountMinor - $target,
            'rounding_minor' => $rounding,
        ];
    }

    /**
     * Alokasi lanjutan (Pro, "Aturan alokasi lanjutan" di docs/monetisasi.md): ruang diisi berurutan
     * menurut prioritas (urutan $caps, sama dengan rooms.sort_order), masing-masing sampai batas
     * atasnya (atau seluruh sisa bila tak terbatas), dan kelebihannya mengalir ke ruang berikutnya.
     * Aritmetika bulat eksak tanpa pembulatan, karena tiap ruang hanya mengambil min(sisa, batas).
     *
     * @param  list<array{room_id: int, cap_minor: ?int}>  $caps  urutan prioritas; cap_minor null = tak terbatas
     * @return array{shares: list<array{room_id: int, amount_minor: int}>, unallocated_minor: int}
     *
     * @throws InvalidArgumentException nominal negatif, ruang ganda, atau batas atas negatif
     */
    public static function allocateWaterfall(int $amountMinor, array $caps): array
    {
        if ($amountMinor < 0) {
            throw new InvalidArgumentException("Nominal yang dialirkan tidak boleh negatif: {$amountMinor}");
        }

        $seen = [];
        foreach ($caps as $cap) {
            if (isset($seen[$cap['room_id']])) {
                throw new InvalidArgumentException('Satu ruang muncul lebih dari sekali di batas atas');
            }
            $seen[$cap['room_id']] = true;
            if ($cap['cap_minor'] !== null && $cap['cap_minor'] < 0) {
                throw new InvalidArgumentException('Batas atas tidak boleh negatif');
            }
        }

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

    /**
     * Persentase (basis point) yang, dipakai lagi lewat allocate() pada nominal yang sama, menghasilkan
     * kembali $shares. Dipakai untuk menyimpan potret aturan lanjutan sebagai persentase biasa
     * (allocation_entries.share_bp), supaya ubah nominal nanti tetap dihitung ulang lewat potret
     * persentase, tanpa kolom snapshot baru. Dibulatkan ke bawah dengan sengaja (sama seperti Kotlin):
     * hanya dasar hitung ulang bila nominal transaksi diubah, bukan untuk alokasi yang sedang dicatat.
     *
     * @param  list<array{room_id: int, amount_minor: int}>  $shares
     * @return list<array{room_id: int, share_bp: int}>
     */
    public static function impliedShares(int $amountMinor, array $shares): array
    {
        if ($amountMinor <= 0) {
            return array_map(fn (array $s) => ['room_id' => $s['room_id'], 'share_bp' => 0], $shares);
        }

        return array_map(function (array $s) use ($amountMinor) {
            $bp = self::mulDivFloor(max($s['amount_minor'], 0), self::FULL_BP, $amountMinor);

            return ['room_id' => $s['room_id'], 'share_bp' => min($bp, self::FULL_BP)];
        }, $shares);
    }

    /**
     * floor($a × $m / $b) tanpa luapan dan tanpa floating point, untuk $a >= 0, $m >= 0, $b > 0.
     * Hasil di atas PHP_INT_MAX dijenuhkan ke PHP_INT_MAX. Dipakai untuk basis point dari nominal
     * berapa pun (mis. persentase terpakai di MonthLedger), padanan BigInteger di Kotlin.
     */
    public static function mulDivFloor(int $a, int $m, int $b): int
    {
        if ($a < 0 || $m < 0 || $b <= 0) {
            throw new InvalidArgumentException('mulDivFloor butuh a >= 0, m >= 0, dan b > 0');
        }

        // Jalur cepat untuk nominal wajar: perkaliannya muat di int 64-bit.
        if ($m === 0 || $a <= intdiv(PHP_INT_MAX, $m)) {
            return intdiv($a * $m, $b);
        }

        $q = intdiv($a, $b);
        $r = $a % $b;

        if ($m !== 0 && $q > intdiv(PHP_INT_MAX, $m)) {
            return PHP_INT_MAX;
        }
        $high = $q * $m;

        // floor($r × $m / $b) dengan $r < $b, lewat penggandaan biner supaya tidak pernah meluap:
        // setiap langkah sisa tetap di bawah $b, dan hasil bagiannya di bawah $m.
        $quotient = 0;
        $remainder = 0;
        for ($bit = PHP_INT_SIZE * 8 - 2; $bit >= 0; $bit--) {
            $quotient *= 2;
            if ($remainder >= $b - $remainder) {
                $remainder -= $b - $remainder;
                $quotient++;
            } else {
                $remainder *= 2;
            }

            if (($m >> $bit) & 1) {
                if ($remainder >= $b - $r) {
                    $remainder -= $b - $r;
                    $quotient++;
                } else {
                    $remainder += $r;
                }
            }
        }

        return $high > PHP_INT_MAX - $quotient ? PHP_INT_MAX : $high + $quotient;
    }

    private static function assertAmount(int $amountMinor): void
    {
        if ($amountMinor < 0) {
            throw new InvalidArgumentException("Nominal yang dialirkan tidak boleh negatif: {$amountMinor}");
        }
        if ($amountMinor > self::MAX_AMOUNT_MINOR) {
            throw new InvalidArgumentException('Nominal melebihi batas aman '.self::MAX_AMOUNT_MINOR);
        }
    }
}
