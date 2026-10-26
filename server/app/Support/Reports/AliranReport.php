<?php

namespace App\Support\Reports;

use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\Period;
use App\Support\Rupiah;
use App\Support\SankeyLayout;
use Carbon\CarbonImmutable;
use Illuminate\Support\Collection;

/**
 * Agregasi Analisa › Aliran (Pro): angka, tabel kembaran, readout per fokus ruang, dan kalimat
 * "Yang terlihat". Dipakai bersama oleh App\Livewire\Aliran (layar) dan
 * App\Http\Controllers\ReportPdfController::aliran (PDF), supaya angka di unduhan selalu sama persis
 * dengan yang tampil di layar (pola sama dengan App\Support\PeriodComparator).
 *
 * Semua angka dihitung di server dari transaksi dan potret alokasi (allocation_entries) milik pengguna
 * yang diberikan, lalu ditata oleh SankeyLayout. Filter akun (null = semua akun) berlaku pada pemasukan
 * (akun tempat rezeki masuk) dan pengeluaran (akun tempat dibayar), sehingga diagram tetap konsisten.
 *
 * Sumber pemasukan dikelompokkan menurut sumbernya (kolom `category`: Gaji, Usaha, ...), paling banyak
 * MAX_SOURCES node (sisanya "Lainnya"); pos per ruang paling banyak MAX_POS node (sisanya "Pos lainnya").
 */
class AliranReport
{
    private const MAX_SOURCES = 6;

    private const MAX_POS = 7;

    /**
     * Susunan lengkap hasil untuk satu rentang. Bentuknya sama dengan data view lama ('flow'):
     * layout, focus, rows, readouts, insights, aria, overNote.
     *
     * @param  list<int>|null  $accountIds  null = semua akun; daftar kosong = tidak ada akun yang dipilih
     * @return array<string, mixed>
     */
    public static function build(User $user, CarbonImmutable $end, int $count, ?array $accountIds = null): array
    {
        $start = $end->subMonthsNoOverflow($count - 1);
        // Batas setengah terbuka (>= tanggal 1 awal, < tanggal 1 bulan setelah akhir), seperti MonthLedger.
        $from = $start->toDateString();
        $to = $end->addMonthNoOverflow()->toDateString();

        $allRooms = $user->rooms()->orderBy('sort_order')->orderBy('id')->get();
        $roomIds = $allRooms->pluck('id')->map(fn ($id) => (int) $id)->all();
        $categories = Category::query()->whereIn('room_id', $roomIds)->get()->keyBy('id');

        [$sources, $links] = self::sourcesAndLinks($user, $from, $to, $roomIds, $accountIds);
        [$rooms, $untrackedMinor] = self::roomsWithPos($user, $from, $to, $allRooms, $categories, $roomIds, $accountIds);

        $layout = SankeyLayout::build(['sources' => $sources, 'rooms' => $rooms, 'links' => $links]);

        $focus = $allRooms->filter(fn (Room $room) => ! $room->archived)
            ->map(fn (Room $room) => ['key' => 'r'.$room->id, 'name' => $room->name])->values()->all();

        if ($layout['empty']) {
            return ['layout' => $layout, 'focus' => $focus, 'rows' => [], 'readouts' => [], 'insights' => [], 'aria' => '', 'overNote' => null];
        }

        return [
            'layout' => $layout,
            'focus' => $focus,
            'rows' => self::tableRows($layout),
            'readouts' => self::readouts($layout, $focus),
            'insights' => self::insights($layout, $untrackedMinor, $end, $count),
            'aria' => self::ariaLabel($layout),
            'overNote' => $layout['over'] > 0 ? $layout['over'] : null,
        ];
    }

    /** "Oktober 2026" untuk satu bulan, atau "Agustus 2026 – Oktober 2026" untuk rentang. */
    public static function rangeLabel(CarbonImmutable $end, int $count): string
    {
        $start = $end->subMonthsNoOverflow($count - 1);

        return $count === 1 ? Period::label($end) : Period::label($start).' – '.Period::label($end);
    }

    /**
     * Daftar id akun yang dipilih dari filter. 'semua' (atau nilai lain) berarti null (tanpa filter).
     * 'kustom' memakai daftar id dipisah koma, dan hanya id akun milik $user yang lolos.
     *
     * @return list<int>|null
     */
    public static function accountIds(User $user, string $akun, string $ids): ?array
    {
        if ($akun !== 'kustom') {
            return null;
        }

        $wanted = collect(explode(',', $ids))->map(fn ($value) => (int) trim($value))->filter()->unique()->values()->all();
        $own = $user->accounts()->pluck('id')->map(fn ($id) => (int) $id)->all();

        return array_values(array_intersect($wanted, $own));
    }

    /** Label filter akun untuk judul dan PDF: "Semua akun" atau nama akun yang dipilih. */
    public static function accountLabel(User $user, ?array $accountIds): string
    {
        if ($accountIds === null) {
            return 'Semua akun';
        }
        if ($accountIds === []) {
            return 'Tidak ada akun dipilih';
        }

        $names = $user->accounts()->whereIn('id', $accountIds)->orderBy('sort_order')->orderBy('id')->pluck('name')->all();

        return implode(', ', $names);
    }

    /**
     * Sumber (pemasukan dikelompokkan menurut sumbernya) dan pita sumber → ruang dari potret alokasi.
     *
     * @param  list<int>  $roomIds
     * @param  list<int>|null  $accountIds
     * @return array{0: list<array<string, mixed>>, 1: list<array<string, mixed>>}
     */
    private static function sourcesAndLinks(User $user, string $from, string $to, array $roomIds, ?array $accountIds): array
    {
        $incomes = Transaction::query()
            ->where('user_id', $user->id)
            ->income()
            ->where('occurred_on', '>=', $from)
            ->where('occurred_on', '<', $to)
            ->when($accountIds !== null, fn ($query) => $query->whereIn('account_id', $accountIds))
            ->with(['allocationEntries', 'account'])
            ->orderBy('occurred_on')
            ->orderBy('id')
            ->get();

        $groups = [];
        foreach ($incomes as $income) {
            $name = trim((string) $income->category);
            $groups[$name === '' ? 'Pemasukan' : $name][] = $income;
        }

        $sources = [];
        $links = [];
        $index = 0;
        foreach ($groups as $name => $transactions) {
            $key = 's'.(++$index);
            /** @var Collection<int, Transaction> $transactions */
            $transactions = collect($transactions);
            $label = $name;
            $detail = $transactions->count().' pemasukan';
            if ($transactions->count() === 1) {
                $only = $transactions->first();
                $label = trim((string) $only->note) !== '' ? trim((string) $only->note) : $name;
                $detail = $only->occurred_on->locale('id')->translatedFormat('j M').($only->account ? ' · '.$only->account->name : '');
            }
            $sources[] = ['key' => $key, 'label' => $label, 'detail' => $detail, 'amount' => (int) $transactions->sum('amount_minor')];

            foreach ($transactions as $income) {
                foreach ($income->allocationEntries as $entry) {
                    if (in_array((int) $entry->room_id, $roomIds, true) && $entry->amount_minor > 0) {
                        $links[] = ['source' => $key, 'room' => 'r'.$entry->room_id, 'amount' => (int) $entry->amount_minor];
                    }
                }
            }
        }

        [$sources, $map] = SankeyLayout::collapse($sources, self::MAX_SOURCES, 'sLainnya', 'Lainnya');
        if ($map) {
            foreach ($links as &$link) {
                $link['source'] = $map[$link['source']] ?? $link['source'];
            }
            unset($link);
        }

        return [$sources, $links];
    }

    /**
     * Ruang beserta pos-posnya dari pengeluaran rentang. Pengeluaran tanpa ruang yang dikenal masuk ke
     * ruang bantu "Tanpa ruang" supaya tidak ada rupiah yang hilang.
     *
     * @param  Collection<int, Room>  $allRooms
     * @param  Collection<int, Category>  $categories
     * @param  list<int>  $roomIds
     * @param  list<int>|null  $accountIds
     * @return array{0: list<array<string, mixed>>, 1: int}  ruang untuk SankeyLayout dan jumlah pos Tak terlacak
     */
    private static function roomsWithPos(User $user, string $from, string $to, Collection $allRooms, Collection $categories, array $roomIds, ?array $accountIds): array
    {
        $rows = Transaction::query()
            ->where('user_id', $user->id)
            ->expense()
            ->where('occurred_on', '>=', $from)
            ->where('occurred_on', '<', $to)
            ->when($accountIds !== null, fn ($query) => $query->whereIn('account_id', $accountIds))
            ->groupBy('room_id', 'category_id')
            ->selectRaw('room_id, category_id, SUM(amount_minor) as total')
            ->toBase()
            ->get();

        $byRoom = [];
        $untracked = 0;
        foreach ($rows as $row) {
            $category = $row->category_id !== null ? $categories->get((int) $row->category_id) : null;
            $roomId = (int) ($row->room_id ?? $category?->room_id ?? 0);
            $roomKey = in_array($roomId, $roomIds, true) ? 'r'.$roomId : 'r0';
            $posKey = $category ? 'p'.$category->id : 'p0';
            $byRoom[$roomKey][$posKey]['label'] = $category?->name ?? 'Tanpa pos';
            $byRoom[$roomKey][$posKey]['amount'] = ($byRoom[$roomKey][$posKey]['amount'] ?? 0) + (int) $row->total;
            if ($category?->isUntracked()) {
                $untracked += (int) $row->total;
            }
        }

        $rooms = [];
        foreach ($allRooms as $room) {
            $rooms[] = self::roomEntry('r'.$room->id, $room->name, $room->slot, $byRoom['r'.$room->id] ?? [], (bool) $room->archived, $room->icon);
        }
        if (isset($byRoom['r0'])) {
            $rooms[] = self::roomEntry('r0', 'Tanpa ruang', null, $byRoom['r0'], false, null);
        }

        return [$rooms, $untracked];
    }

    /**
     * @param  array<string, array{label: string, amount: int}>  $pos
     * @return array<string, mixed>
     */
    private static function roomEntry(string $key, string $label, mixed $slot, array $pos, bool $archived, ?string $icon): array
    {
        $items = [];
        foreach ($pos as $posKey => $item) {
            $items[] = ['key' => $key.'-'.$posKey, 'label' => $item['label'], 'amount' => $item['amount']];
        }
        [$items] = SankeyLayout::collapse($items, self::MAX_POS, $key.'-lain', 'Pos lainnya');

        return ['key' => $key, 'label' => $label, 'slot' => $slot, 'icon' => $icon, 'archived' => $archived, 'pos' => $items];
    }

    /**
     * Baris tabel kembaran grafik: per ruang pos-posnya lalu sisa, lalu "Belum dialirkan".
     *
     * @param  array<string, mixed>  $layout
     * @return list<array<string, mixed>>
     */
    private static function tableRows(array $layout): array
    {
        $total = $layout['total'];
        $rows = [];
        foreach ($layout['rooms'] as $room) {
            $lines = [];
            foreach ($room['pos'] as $pos) {
                $lines[] = ['pos' => $pos['label'], 'amount' => $pos['amount'], 'sisa' => false];
            }
            if ($room['remaining'] > 0) {
                $lines[] = ['pos' => 'Sisa belum terpakai', 'amount' => $room['remaining'], 'sisa' => true];
            }
            foreach ($lines as $line) {
                $rows[] = [
                    'slot' => $room['slot'], 'room' => $room['label'], 'pos' => $line['pos'], 'amount' => $line['amount'], 'sisa' => $line['sisa'],
                    'ofRoom' => SankeyLayout::formatTenths(SankeyLayout::percentTenths($line['amount'], $room['allocated'])),
                    'ofTotal' => SankeyLayout::formatTenths(SankeyLayout::percentTenths($line['amount'], $total)),
                ];
            }
        }
        if ($layout['unallocated']) {
            $rows[] = [
                'slot' => null, 'room' => 'Belum dialirkan', 'pos' => '—', 'amount' => $layout['unallocated']['amount'], 'sisa' => true,
                'ofRoom' => '—',
                'ofTotal' => SankeyLayout::formatTenths(SankeyLayout::percentTenths($layout['unallocated']['amount'], $total)),
            ];
        }

        return $rows;
    }

    /**
     * Teks readout untuk tiap fokus ('semua' dan 'r{id}' tiap ruang aktif).
     *
     * @param  array<string, mixed>  $layout
     * @param  list<array{key: string, name: string}>  $focus
     * @return array<string, string>
     */
    private static function readouts(array $layout, array $focus): array
    {
        $total = $layout['total'];
        $spent = $layout['spent'];
        $all = 'Semua ruang: '.Rupiah::format($spent).' terpakai dari '.Rupiah::format($total).' rezeki ('.SankeyLayout::percentFloor($spent, $total).'%)';
        $all .= '; sisa belum terpakai '.Rupiah::format($layout['remaining']);
        if ($layout['unallocated']) {
            $all .= '; belum dialirkan '.Rupiah::format($layout['unallocated']['amount']);
        }
        if ($layout['over'] > 0) {
            $all .= '; melewati jatah '.Rupiah::format($layout['over']);
        }
        $readouts = ['semua' => $all.'.'];

        $byKey = [];
        foreach ($layout['rooms'] as $room) {
            $byKey[$room['key']] = $room;
        }

        foreach ($focus as $item) {
            $room = $byKey[$item['key']] ?? null;
            if ($room === null) {
                $readouts[$item['key']] = $item['name'].': belum ada jatah maupun pengeluaran pada rentang ini.';

                continue;
            }

            if ($room['allocated'] <= 0) {
                $text = $item['name'].': belum ada jatah pada rentang ini, tetapi sudah terpakai '.Rupiah::format($room['spent']).'.';
            } else {
                $text = $item['name'].': '.Rupiah::format($room['spent']).' dari '.Rupiah::format($room['allocated']).' terpakai ('.SankeyLayout::percentFloor($room['spent'], $room['allocated']).'%)';
                $text .= match (true) {
                    $room['over'] > 0 => '; melewati jatah '.Rupiah::format($room['over']).'.',
                    $room['remaining'] > 0 => '; sisa '.Rupiah::format($room['remaining']).'.',
                    default => ', jatah terpenuhi.',
                };
            }
            $top = self::topPos($room);
            if ($top && $room['spent'] > 0) {
                $text .= ' Pos terbesar: '.$top['label'].', '.SankeyLayout::percentWhole($top['amount'], $room['spent']).'% dari yang terpakai.';
            }
            $readouts[$item['key']] = $text;
        }

        return $readouts;
    }

    /**
     * Pos terbesar sebuah ruang yang bukan gabungan "Pos lainnya".
     *
     * @param  array<string, mixed>  $room
     * @return array<string, mixed>|null
     */
    private static function topPos(array $room): ?array
    {
        foreach ($room['pos'] as $pos) {
            if (! str_ends_with((string) $pos['key'], '-lain')) {
                return $pos;
            }
        }

        return null;
    }

    /**
     * Kalimat "Yang terlihat": ruang penyerap rezeki terbesar, ruang melewati jatah, ruang dengan sisa
     * terbesar, dan pos Tak terlacak. Netral, tanpa nada peringatan merah.
     *
     * @param  array<string, mixed>  $layout
     * @return list<array<string, mixed>>
     */
    private static function insights(array $layout, int $untrackedMinor, CarbonImmutable $end, int $count): array
    {
        $total = $layout['total'];
        $rooms = collect($layout['rooms']);
        $items = [];

        $biggest = $rooms->filter(fn (array $r) => $r['allocated'] > 0)->sortByDesc('allocated')->first();
        if ($biggest) {
            $text = $biggest['label'].' menyerap '.SankeyLayout::percentWhole($biggest['allocated'], $total).'% rezeki.';
            $top = self::topPos($biggest);
            if ($top && $biggest['spent'] > 0) {
                $text .= ' Dari yang terpakai di sana, '.SankeyLayout::percentWhole($top['amount'], $biggest['spent']).'% jatuh ke '.$top['label'].'.';
            }
            $items[] = ['slot' => $biggest['slot'], 'roomIcon' => $biggest['icon'] ?? null, 'before' => $text];
        }

        foreach ($rooms->filter(fn (array $r) => $r['over'] > 0) as $room) {
            $items[] = [
                'slot' => $room['slot'], 'roomIcon' => $room['icon'] ?? null,
                'before' => $room['label'].' terpakai '.Rupiah::format($room['over']).' di atas jatahnya'
                    .($room['allocated'] > 0 ? ' ('.Rupiah::format($room['allocated']).')' : ', karena belum ada jatah pada rentang ini').'. Bagian itu ditandai di diagram.',
            ];
        }

        $roomLeft = $rooms->filter(fn (array $r) => $r['remaining'] > 0)->sortByDesc('remaining')->first();
        if ($roomLeft) {
            $items[] = [
                'slot' => $roomLeft['slot'], 'roomIcon' => $roomLeft['icon'] ?? null,
                'before' => $roomLeft['label'].' masih menyisakan '.Rupiah::format($roomLeft['remaining']).', yaitu '.SankeyLayout::percentWhole($roomLeft['remaining'], $roomLeft['allocated']).'% dari jatahnya.',
            ];
        }

        if ($untrackedMinor > 0 && $layout['spent'] > 0) {
            $items[] = [
                'slot' => null, 'roomIcon' => false,
                'before' => Rupiah::format($untrackedMinor).' masih Tak terlacak ('.SankeyLayout::percentWhole($untrackedMinor, $layout['spent']).'% dari seluruh pengeluaran). Rapikan lewat ',
                'link' => ['label' => 'Transaksi', 'href' => route('transaksi', ['tab' => 'tak', 'bulan' => Period::query($end)])],
                'after' => '.',
            ];
        }

        return $items;
    }

    /** @param  array<string, mixed>  $layout */
    private static function ariaLabel(array $layout): string
    {
        $parts = [];
        foreach ($layout['rooms'] as $room) {
            $parts[] = $room['label'].' '.Rupiah::compact($room['allocated']);
        }
        if ($layout['unallocated']) {
            $parts[] = 'belum dialirkan '.Rupiah::compact($layout['unallocated']['amount']);
        }

        return 'Diagram aliran rezeki. Total '.Rupiah::compact($layout['total']).' mengalir ke '.implode(', ', $parts)
            .', lalu ke pos dan sisa. Tabel di bawah memuat semua angka.';
    }
}
