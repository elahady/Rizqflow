<?php

namespace App\Support;

/**
 * Tata letak diagram Sankey Analisa › Aliran (rezeki → ruang → pos dan sisa), murni perhitungan:
 * tanpa kueri, tanpa Blade. Masukannya angka rupiah (int), keluarannya koordinat SVG yang siap digambar.
 *
 * Aturan:
 * - Tinggi sebanding rupiah. Semua geometri dihitung dalam sen-piksel (1/100 px) sebagai bilangan bulat
 *   (`intdiv(rupiah × P, total)`), baru dibagi 100 di keluaran, jadi tidak ada float di jalur uang.
 * - Skala P dicari lewat pencarian biner supaya tinggi diagram mendekati TARGET_HEIGHT; node dan pita
 *   yang sangat kecil dinaikkan ke tinggi minimum tampil (MIN_*), tanpa mengubah angka yang diberikan.
 *   Bila banyak node kecil membuat diagram lebih tinggi dari target, tinggi total ikut membesar
 *   (`height`), tidak ada yang dipotong atau ditumpuk.
 * - Jumlah per ruang selalu konsisten: jatah (allocated) = jumlah pita sumber → ruang; terpakai (spent) =
 *   jumlah pos; sisa = max(jatah − terpakai, 0); melewati jatah (over) = max(terpakai − jatah, 0).
 *   Node ruang setinggi max(jatah, terpakai): bagian "melewati jatah" digambar sebagai penanda di dasar
 *   node ruang (`over_y`/`over_h`), sehingga tidak ada rupiah yang hilang dan diagram tidak rusak.
 * - Sisa rupiah sumber yang tidak punya pita ke ruang mana pun mengalir ke node "Belum dialirkan"
 *   (UNALLOCATED), di kolom ruang, tanpa keluaran.
 *
 * Masukan build():
 *   sources: list<{key, label, detail?, amount}>   (kunci lain ikut diteruskan ke keluaran)
 *   rooms:   list<{key, label, slot?, pos: list<{key, label, amount}>}>  (urutan = urutan tampil)
 *   links:   list<{source, room, amount}>           (room boleh UNALLOCATED; pasangan yang sama dijumlahkan)
 */
final class SankeyLayout
{
    /** Kunci node tujuan untuk rezeki yang belum dialirkan. */
    public const UNALLOCATED = 'belum';

    public const WIDTH = 1060;

    public const SOURCE_X = 0;

    public const ROOM_X = 400;

    public const POS_X = 800;

    public const SOURCE_W = 170;

    public const ROOM_W = 170;

    public const POS_W = 12;

    /** Tinggi diagram yang dituju (px) sebelum node minimum memaksanya lebih tinggi. */
    public const TARGET_HEIGHT = 520;

    // Ukuran dalam sen-piksel (1/100 px).
    private const MIN_SOURCE = 3_800;

    private const MIN_ROOM = 3_800;

    /** Tinggi minimum node ruang yang melewati jatah: muat label di atas dan penanda di dasar. */
    private const MIN_ROOM_OVER = 6_000;

    private const MIN_POS = 1_200;

    private const MIN_LINK = 100;

    private const GAP_SOURCE = 1_600;

    private const GAP_POS = 800;

    private const GAP_GROUP = 2_400;

    private const SCALE_MIN = 1_500;

    /**
     * @param  array{sources?: list<array<string, mixed>>, rooms?: list<array<string, mixed>>, links?: list<array<string, mixed>>}  $input
     * @return array<string, mixed>
     */
    public static function build(array $input): array
    {
        $model = self::model($input);

        if ($model['total'] <= 0) {
            return ['empty' => true, 'total' => 0, 'width' => self::WIDTH, 'height' => 0.0, 'sources' => [], 'rooms' => [], 'unallocated' => null, 'allocated' => 0, 'spent' => 0, 'remaining' => 0, 'over' => 0];
        }

        $target = self::TARGET_HEIGHT * 100;
        $best = self::SCALE_MIN;
        if (self::arrange($model, $best)['height_centi'] <= $target) {
            $lo = $best;
            $hi = $target;
            while ($lo < $hi) {
                $mid = intdiv($lo + $hi + 1, 2);
                if (self::arrange($model, $mid)['height_centi'] <= $target) {
                    $lo = $mid;
                } else {
                    $hi = $mid - 1;
                }
            }
            $best = $lo;
        }

        $result = self::arrange($model, $best);
        $result['empty'] = false;

        return $result;
    }

    /**
     * Gabungkan item terkecil jadi satu item "lainnya" bila lebih dari $max. Item diurutkan menurun menurut
     * `amount` (urutan sama dipertahankan stabil); item gabungan selalu paling akhir dan menjumlahkan
     * `amount`. Mengembalikan [item tampil, peta kunci lama → kunci "lainnya"] untuk memetakan ulang tautan.
     *
     * @param  list<array<string, mixed>>  $items  tiap item punya `key` dan `amount`
     * @return array{0: list<array<string, mixed>>, 1: array<string, string>}
     */
    public static function collapse(array $items, int $max, string $otherKey, string $otherLabel): array
    {
        $indexed = [];
        foreach ($items as $i => $item) {
            $indexed[] = [$i, $item];
        }
        usort($indexed, fn (array $a, array $b) => [$b[1]['amount'], $a[0]] <=> [$a[1]['amount'], $b[0]]);
        $sorted = array_map(fn (array $pair) => $pair[1], $indexed);

        if (count($sorted) <= $max) {
            return [$sorted, []];
        }

        $keep = array_slice($sorted, 0, max($max - 1, 0));
        $rest = array_slice($sorted, max($max - 1, 0));
        $map = [];
        $sum = 0;
        foreach ($rest as $item) {
            $map[(string) $item['key']] = $otherKey;
            $sum += (int) $item['amount'];
        }
        $keep[] = ['key' => $otherKey, 'label' => $otherLabel, 'amount' => $sum, 'detail' => count($rest).' lainnya'];

        return [$keep, $map];
    }

    /** Persen dibulatkan setengah ke atas dalam per sepuluh persen (72 = 7,2%); null bila pembagi 0. */
    public static function percentTenths(int $part, int $whole): ?int
    {
        return $whole <= 0 ? null : intdiv($part * 2_000 + $whole, 2 * $whole);
    }

    /** Persen bulat, dibulatkan setengah ke atas; null bila pembagi 0. */
    public static function percentWhole(int $part, int $whole): ?int
    {
        return $whole <= 0 ? null : intdiv($part * 200 + $whole, 2 * $whole);
    }

    /** Persen bulat dibulatkan ke bawah (cara Denah/RoomMonth); null bila pembagi 0. */
    public static function percentFloor(int $part, int $whole): ?int
    {
        return $whole <= 0 ? null : intdiv(max($part, 0) * 100, $whole);
    }

    /** "7,2%" dari per sepuluh persen; "—" bila tidak terdefinisi. */
    public static function formatTenths(?int $tenths): string
    {
        return $tenths === null ? '—' : intdiv($tenths, 10).','.($tenths % 10).'%';
    }

    /**
     * Normalisasi masukan: buang yang nol, jumlahkan tautan sama, alirkan sisa sumber ke "Belum dialirkan".
     *
     * @return array<string, mixed>
     */
    private static function model(array $input): array
    {
        $links = [];
        foreach ($input['links'] ?? [] as $link) {
            $amount = (int) ($link['amount'] ?? 0);
            if ($amount > 0) {
                $pair = $link['source'].'|'.$link['room'];
                $links[$pair] = ($links[$pair] ?? 0) + $amount;
            }
        }

        $roomKeys = [];
        foreach ($input['rooms'] ?? [] as $room) {
            $roomKeys[(string) $room['key']] = true;
        }

        // Sumber: nilainya tidak boleh lebih kecil dari jumlah pitanya; selisih ke atas = belum dialirkan.
        $sources = [];
        $outBySource = [];
        $unallocated = 0;
        foreach ($input['sources'] ?? [] as $source) {
            $key = (string) $source['key'];
            $out = [];
            $sum = 0;
            foreach ($links as $pair => $amount) {
                [$from, $to] = explode('|', $pair, 2);
                if ($from === $key && $to !== self::UNALLOCATED && isset($roomKeys[$to])) {
                    $out[$to] = $amount;
                    $sum += $amount;
                }
            }
            $explicitUnallocated = $links[$key.'|'.self::UNALLOCATED] ?? 0;
            $sum += $explicitUnallocated;
            $amount = max((int) $source['amount'], $sum);
            if ($amount <= 0) {
                continue;
            }
            $rest = $amount - $sum + $explicitUnallocated;
            if ($rest > 0) {
                $out[self::UNALLOCATED] = $rest;
                $unallocated += $rest;
            }
            $sources[] = array_merge($source, ['key' => $key, 'amount' => $amount]);
            $outBySource[$key] = $out;
        }

        $total = array_sum(array_column($sources, 'amount'));

        $rooms = [];
        foreach ($input['rooms'] ?? [] as $room) {
            $key = (string) $room['key'];
            $allocated = 0;
            foreach ($outBySource as $out) {
                $allocated += $out[$key] ?? 0;
            }
            $pos = [];
            foreach ($room['pos'] ?? [] as $item) {
                if ((int) $item['amount'] > 0) {
                    $pos[] = array_merge($item, ['key' => (string) $item['key'], 'amount' => (int) $item['amount']]);
                }
            }
            $spent = array_sum(array_column($pos, 'amount'));
            if ($allocated <= 0 && $spent <= 0) {
                continue;
            }
            $rooms[] = array_merge($room, [
                'key' => $key, 'pos' => $pos, 'allocated' => $allocated, 'spent' => $spent,
                'remaining' => max($allocated - $spent, 0), 'over' => max($spent - $allocated, 0),
            ]);
        }

        return ['total' => $total, 'sources' => $sources, 'rooms' => $rooms, 'out' => $outBySource, 'unallocated' => $unallocated];
    }

    /**
     * Susun semua node dan pita pada skala $p (sen-piksel untuk seluruh `total`).
     *
     * @param  array<string, mixed>  $model
     * @return array<string, mixed>
     */
    private static function arrange(array $model, int $p): array
    {
        $total = $model['total'];
        $hv = fn (int $amount): int => intdiv($amount * $p, $total);
        $lt = fn (int $amount): int => $amount > 0 ? max($hv($amount), self::MIN_LINK) : 0;

        // 1) Ukuran tiap grup ruang (node ruang + kolom pos) dan node "Belum dialirkan".
        $groups = [];
        foreach ($model['rooms'] as $room) {
            $inSum = 0;
            foreach ($model['sources'] as $source) {
                $inSum += $lt($model['out'][$source['key']][$room['key']] ?? 0);
            }
            $inSum += $lt($room['over']);

            $posHeights = [];
            $outSum = 0;
            foreach ($room['pos'] as $item) {
                $outSum += $lt($item['amount']);
                $posHeights[] = max(self::MIN_POS, $hv($item['amount']));
            }
            $sisaHeight = 0;
            if ($room['remaining'] > 0) {
                $outSum += $lt($room['remaining']);
                $sisaHeight = max(self::MIN_POS, $hv($room['remaining']));
            }
            $nodeCount = count($posHeights) + ($sisaHeight > 0 ? 1 : 0);
            $stackHeight = array_sum($posHeights) + $sisaHeight + self::GAP_POS * max($nodeCount - 1, 0);
            $roomHeight = max($room['over'] > 0 ? self::MIN_ROOM_OVER : self::MIN_ROOM, $hv(max($room['allocated'], $room['spent'])), $inSum, $outSum);

            $groups[] = ['room' => $room, 'h' => $roomHeight, 'pos_heights' => $posHeights, 'sisa_h' => $sisaHeight,
                'extent' => max($roomHeight, $stackHeight), 'in_sum' => $inSum];
        }

        $unallocatedHeight = 0;
        if ($model['unallocated'] > 0) {
            $inSum = 0;
            foreach ($model['sources'] as $source) {
                $inSum += $lt($model['out'][$source['key']][self::UNALLOCATED] ?? 0);
            }
            $unallocatedHeight = max(self::MIN_ROOM, $hv($model['unallocated']), $inSum);
        }

        $columnHeight = 0;
        foreach ($groups as $group) {
            $columnHeight += $group['extent'] + self::GAP_GROUP;
        }
        if ($unallocatedHeight > 0) {
            $columnHeight += $unallocatedHeight + self::GAP_GROUP;
        }
        $columnHeight = max($columnHeight - self::GAP_GROUP, 0);

        // 2) Tinggi tiap sumber.
        $sourceHeights = [];
        $sourcesHeight = 0;
        foreach ($model['sources'] as $source) {
            $outSum = 0;
            foreach ($model['out'][$source['key']] as $amount) {
                $outSum += $lt($amount);
            }
            $sourceHeights[$source['key']] = max(self::MIN_SOURCE, $hv($source['amount']), $outSum);
            $sourcesHeight += $sourceHeights[$source['key']] + self::GAP_SOURCE;
        }
        $sourcesHeight = max($sourcesHeight - self::GAP_SOURCE, 0);

        $height = max($columnHeight, $sourcesHeight);
        $sourceTop = intdiv($height - $sourcesHeight, 2);
        $groupTop = intdiv($height - $columnHeight, 2);

        // 3) Posisi node. Kursor pita keluar sumber dan masuk ruang dikelola per node.
        $sources = [];
        $sourceCursor = [];
        $y = $sourceTop;
        foreach ($model['sources'] as $source) {
            $h = $sourceHeights[$source['key']];
            $sources[$source['key']] = array_merge($source, [
                'x' => self::SOURCE_X, 'y' => self::c($y), 'w' => self::SOURCE_W, 'h' => self::c($h), 'cy' => self::c($y + intdiv($h, 2)),
            ]);
            $sourceCursor[$source['key']] = $y;
            $y += $h + self::GAP_SOURCE;
        }

        $rooms = [];
        $unallocatedNode = null;
        $y = $groupTop;
        $unallocatedIn = []; // pita sumber → belum dialirkan
        foreach ($groups as $group) {
            $room = $group['room'];
            $roomY = $y;
            $roomH = $group['h'];

            // Pita masuk dari sumber (urut sumber), lalu penanda melewati jatah di bawahnya.
            $inLinks = [];
            $inCursor = $roomY;
            foreach ($model['sources'] as $source) {
                $amount = $model['out'][$source['key']][$room['key']] ?? 0;
                if ($amount <= 0) {
                    continue;
                }
                $thick = $lt($amount);
                $from = $sourceCursor[$source['key']];
                $sourceCursor[$source['key']] += $thick;
                $inLinks[] = ['source' => $source['key'], 'amount' => $amount, 'd' => self::ribbon(self::SOURCE_X + self::SOURCE_W, $from, $thick, self::ROOM_X, $inCursor, $thick)];
                $inCursor += $thick;
            }
            $overH = $room['over'] > 0 ? $lt($room['over']) : 0;
            $overY = $room['over'] > 0 ? $roomY + $roomH - $overH : null; // di dasar node, di bawah label

            // Kolom pos: pos terbesar dulu (urutan masukan), "Sisa belum terpakai" terakhir.
            $pos = [];
            $outLinks = [];
            $outCursor = $roomY;
            $stackY = $roomY;
            foreach ($room['pos'] as $i => $item) {
                $nodeH = $group['pos_heights'][$i];
                $thick = $lt($item['amount']);
                $outLinks[] = ['key' => $item['key'], 'amount' => $item['amount'], 'sisa' => false,
                    'd' => self::ribbon(self::ROOM_X + self::ROOM_W, $outCursor, $thick, self::POS_X, $stackY, $nodeH)];
                $outCursor += $thick;
                $pos[] = array_merge($item, ['x' => self::POS_X, 'y' => self::c($stackY), 'w' => self::POS_W, 'h' => self::c($nodeH), 'cy' => self::c($stackY + intdiv($nodeH, 2))]);
                $stackY += $nodeH + self::GAP_POS;
            }
            $sisa = null;
            if ($group['sisa_h'] > 0) {
                $nodeH = $group['sisa_h'];
                $thick = $lt($room['remaining']);
                $outLinks[] = ['key' => 'sisa', 'amount' => $room['remaining'], 'sisa' => true,
                    'd' => self::ribbon(self::ROOM_X + self::ROOM_W, $outCursor, $thick, self::POS_X, $stackY, $nodeH)];
                $sisa = ['amount' => $room['remaining'], 'x' => self::POS_X, 'y' => self::c($stackY), 'w' => self::POS_W, 'h' => self::c($nodeH), 'cy' => self::c($stackY + intdiv($nodeH, 2))];
            }

            $rooms[] = array_merge($room, [
                'x' => self::ROOM_X, 'y' => self::c($roomY), 'w' => self::ROOM_W, 'h' => self::c($roomH), 'cy' => self::c($roomY + intdiv($roomH, 2)),
                'over_y' => $overY === null ? null : self::c($overY), 'over_h' => self::c($overH),
                'pos' => $pos, 'sisa' => $sisa, 'links_in' => $inLinks, 'links_out' => $outLinks,
            ]);
            $y += $group['extent'] + self::GAP_GROUP;
        }

        if ($unallocatedHeight > 0) {
            $inCursor = $y;
            foreach ($model['sources'] as $source) {
                $amount = $model['out'][$source['key']][self::UNALLOCATED] ?? 0;
                if ($amount <= 0) {
                    continue;
                }
                $thick = $lt($amount);
                $from = $sourceCursor[$source['key']];
                $sourceCursor[$source['key']] += $thick;
                $unallocatedIn[] = ['source' => $source['key'], 'amount' => $amount, 'd' => self::ribbon(self::SOURCE_X + self::SOURCE_W, $from, $thick, self::ROOM_X, $inCursor, $thick)];
                $inCursor += $thick;
            }
            $unallocatedNode = [
                'amount' => $model['unallocated'], 'x' => self::ROOM_X, 'y' => self::c($y), 'w' => self::ROOM_W,
                'h' => self::c($unallocatedHeight), 'cy' => self::c($y + intdiv($unallocatedHeight, 2)), 'links_in' => $unallocatedIn,
            ];
        }

        $allocated = array_sum(array_column($rooms, 'allocated'));

        return [
            'total' => $total,
            'width' => self::WIDTH,
            'height' => self::c($height),
            'height_centi' => $height,
            'sources' => array_values($sources),
            'rooms' => $rooms,
            'unallocated' => $unallocatedNode,
            'allocated' => $allocated,
            'spent' => array_sum(array_column($rooms, 'spent')),
            'remaining' => array_sum(array_column($rooms, 'remaining')),
            'over' => array_sum(array_column($rooms, 'over')),
            'scale' => $p,
        ];
    }

    /** Sen-piksel → px dengan dua desimal. */
    private static function c(int $centi): float
    {
        return round($centi / 100, 2);
    }

    /** Path pita: kurva kubik dari tepi kanan node asal ke tepi kiri node tujuan (sen-piksel masukan). */
    private static function ribbon(int $x0, int $y0, int $h0, int $x1, int $y1, int $h1): string
    {
        $xm = intdiv($x0 + $x1, 2);
        $f = fn (int $centi): string => rtrim(rtrim(number_format($centi / 100, 2, '.', ''), '0'), '.');
        $fx = fn (int $px): string => (string) $px;

        return 'M'.$fx($x0).' '.$f($y0)
            .' C'.$fx($xm).' '.$f($y0).' '.$fx($xm).' '.$f($y1).' '.$fx($x1).' '.$f($y1)
            .' L'.$fx($x1).' '.$f($y1 + $h1)
            .' C'.$fx($xm).' '.$f($y1 + $h1).' '.$fx($xm).' '.$f($y0 + $h0).' '.$fx($x0).' '.$f($y0 + $h0).' Z';
    }
}
