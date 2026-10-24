<?php

namespace App\Support;

use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use ArrayObject;
use Carbon\CarbonImmutable;
use Carbon\CarbonInterface;
use Illuminate\Support\Carbon;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\DB;
use InvalidArgumentException;

/**
 * Angka satu bulan untuk satu pengguna, semuanya diturunkan dari transaksi dan potret alokasi
 * ("Yang bisa dihitung tidak disimpan", docs/model-data.md). Porting DenahLoader, RoomStatusRules,
 * SafeToSpend, dan AttentionItem di domain/.../ledger/Denah.kt, serta RoomDetailLoader untuk pos.
 *
 * Murah: MonthLedger::for() memakai tiga kueri (ruang, agregat transaksi per hari/ruang/pos, dan
 * agregat potret alokasi per ruang), ditambah satu kueri pos bila pos atau Tak terlacak diminta.
 * MonthLedger::months() memuat beberapa bulan sekaligus dengan jumlah kueri yang sama (mis. riwayat
 * 6 bulan per ruang). Tidak untuk disimpan di properti publik Livewire: hitung ulang di render().
 *
 * Batas bulan memakai string tanggal setengah terbuka (>= tanggal 1, < tanggal 1 bulan berikutnya),
 * bukan whereBetween dengan batas atas tanggal polos: occurred_on di SQLite tersimpan sebagai
 * "Y-m-d 00:00:00" (atau dengan jam), yang sebagai teks lebih besar dari "Y-m-d", jadi transaksi di
 * hari terakhir bulan bisa tersingkir diam-diam (perbaikan endOfDay di Room::categoryBreakdown lama).
 */
class MonthLedger
{
    public const ATTENTION_ROOM_OVER = 'room_over';

    public const ATTENTION_ROOM_NEAR = 'room_near';

    public const ATTENTION_UNALLOCATED = 'unallocated';

    /** Android menampilkan paling banyak tiga butir Perlu perhatian; attention() sendiri tidak memotong. */
    public const MAX_ATTENTION = 3;

    /** Tanggal 1 bulan ini. */
    public readonly CarbonImmutable $month;

    /** Hari ini (awal hari), penentu bulan berjalan, sisa hari, dan status bulan yang sudah berakhir. */
    public readonly CarbonImmutable $today;

    /** Rezeki bulan ini: jumlah pemasukan. */
    public readonly int $incomeMinor;

    /** Yang sudah dialirkan dari pemasukan bulan ini ke semua ruang, termasuk ruang terarsip. */
    public readonly int $allocatedMinor;

    /** Belum dialirkan: rezeki dikurangi yang dialirkan, tidak pernah negatif. */
    public readonly int $unallocatedMinor;

    /** Terpakai bulan ini: jumlah semua pengeluaran (ruang aktif maupun terarsip). */
    public readonly int $spentMinor;

    public readonly int $incomeCount;

    public readonly int $expenseCount;

    /** @var array<int, RoomMonth> id ruang => RoomMonth, dibuat sekali per ruang */
    private array $roomMonths = [];

    /**
     * @param  Collection<int, Room>  $allRooms  semua ruang pengguna, urut sort_order lalu id
     * @param  array{income: int, income_count: int, spent: int, expense_count: int, by_day: array<int, int>, room_day: array<int, array<int, int>>, room_category: array<int, array<int, array{total: int, count: int}>>, category: array<int, array{total: int, count: int}>, allocated: array<int, int>}  $data
     * @param  ArrayObject<string, mixed>  $shared  cache bersama antar bulan (pos pengguna)
     */
    private function __construct(
        CarbonImmutable $month,
        CarbonImmutable $today,
        private readonly Collection $allRooms,
        private readonly array $data,
        private readonly ArrayObject $shared,
    ) {
        $this->month = $month;
        $this->today = $today;
        $this->incomeMinor = $data['income'];
        $this->incomeCount = $data['income_count'];
        $this->spentMinor = $data['spent'];
        $this->expenseCount = $data['expense_count'];
        $this->allocatedMinor = array_sum($data['allocated']);
        $this->unallocatedMinor = max($this->incomeMinor - $this->allocatedMinor, 0);
    }

    /** Angka bulan $month (tanggal berapa pun di bulan itu). $today bawaan: hari ini. */
    public static function for(User $user, CarbonInterface $month, ?CarbonInterface $today = null): self
    {
        $first = CarbonImmutable::instance($month)->startOfMonth();

        return self::months($user, $first, $first, $today)[$first->format('Y-m')];
    }

    /**
     * Beberapa bulan berturut-turut sekaligus (inklusif), dengan kueri sebanyak satu bulan.
     *
     * @return array<string, self> kunci 'Y-m', urut dari bulan terlama
     */
    public static function months(User $user, CarbonInterface $from, CarbonInterface $to, ?CarbonInterface $today = null): array
    {
        $first = CarbonImmutable::instance($from)->startOfMonth();
        $last = CarbonImmutable::instance($to)->startOfMonth();
        if ($last->lessThan($first)) {
            throw new InvalidArgumentException('Bulan akhir tidak boleh sebelum bulan awal.');
        }
        $end = $last->addMonthNoOverflow();
        $today = CarbonImmutable::instance($today ?? Carbon::today())->startOfDay();

        $rooms = $user->rooms()->orderBy('id')->get(); // relasi sudah urut sort_order
        $shared = new ArrayObject(['room_ids' => $rooms->pluck('id')->all(), 'categories' => null]);

        $data = [];
        for ($m = $first; $m->lessThan($end); $m = $m->addMonthNoOverflow()) {
            $data[$m->format('Y-m')] = [
                'income' => 0, 'income_count' => 0, 'spent' => 0, 'expense_count' => 0,
                'by_day' => [], 'room_day' => [], 'room_category' => [], 'category' => [], 'allocated' => [],
            ];
        }

        $rows = Transaction::query()
            ->where('user_id', $user->id)
            ->whereIn('type', [Transaction::TYPE_INCOME, Transaction::TYPE_EXPENSE])
            ->where('occurred_on', '>=', $first->toDateString())
            ->where('occurred_on', '<', $end->toDateString())
            ->groupByRaw('type, room_id, category_id, substr(occurred_on, 1, 10)')
            ->selectRaw('type, room_id, category_id, substr(occurred_on, 1, 10) as day, SUM(amount_minor) as total, COUNT(*) as n')
            ->toBase()
            ->get();

        foreach ($rows as $row) {
            $ym = substr($row->day, 0, 7);
            if (! isset($data[$ym])) {
                continue;
            }
            $total = (int) $row->total;
            $count = (int) $row->n;
            $bucket = &$data[$ym];

            if ($row->type === Transaction::TYPE_INCOME) {
                $bucket['income'] += $total;
                $bucket['income_count'] += $count;
            } else {
                $day = (int) substr($row->day, 8, 2);
                $bucket['spent'] += $total;
                $bucket['expense_count'] += $count;
                $bucket['by_day'][$day] = ($bucket['by_day'][$day] ?? 0) + $total;

                if ($row->category_id !== null) {
                    $categoryId = (int) $row->category_id;
                    $bucket['category'][$categoryId]['total'] = ($bucket['category'][$categoryId]['total'] ?? 0) + $total;
                    $bucket['category'][$categoryId]['count'] = ($bucket['category'][$categoryId]['count'] ?? 0) + $count;
                }

                if ($row->room_id !== null) {
                    $roomId = (int) $row->room_id;
                    $bucket['room_day'][$roomId][$day] = ($bucket['room_day'][$roomId][$day] ?? 0) + $total;
                    if ($row->category_id !== null) {
                        $categoryId = (int) $row->category_id;
                        $bucket['room_category'][$roomId][$categoryId]['total'] = ($bucket['room_category'][$roomId][$categoryId]['total'] ?? 0) + $total;
                        $bucket['room_category'][$roomId][$categoryId]['count'] = ($bucket['room_category'][$roomId][$categoryId]['count'] ?? 0) + $count;
                    }
                }
            }
            unset($bucket);
        }

        $allocations = DB::table('allocation_entries')
            ->join('transactions', 'transactions.id', '=', 'allocation_entries.transaction_id')
            ->where('transactions.user_id', $user->id)
            ->where('transactions.type', Transaction::TYPE_INCOME)
            ->where('transactions.occurred_on', '>=', $first->toDateString())
            ->where('transactions.occurred_on', '<', $end->toDateString())
            ->groupByRaw('allocation_entries.room_id, substr(transactions.occurred_on, 1, 7)')
            ->selectRaw('allocation_entries.room_id as room_id, substr(transactions.occurred_on, 1, 7) as ym, SUM(allocation_entries.amount_minor) as total')
            ->get();

        foreach ($allocations as $row) {
            if (isset($data[$row->ym])) {
                $roomId = (int) $row->room_id;
                $data[$row->ym]['allocated'][$roomId] = ($data[$row->ym]['allocated'][$roomId] ?? 0) + (int) $row->total;
            }
        }

        $ledgers = [];
        foreach ($data as $ym => $monthData) {
            $ledgers[$ym] = new self(CarbonImmutable::createFromFormat('!Y-m', $ym), $today, $rooms, $monthData, $shared);
        }

        return $ledgers;
    }

    /**
     * Ruang aktif bulan ini (urut sort_order), masing-masing dengan jatah, terpakai, dan status.
     * Ruang terarsip tidak tampil, tetapi alokasinya tetap terhitung di allocatedMinor.
     *
     * @return Collection<int, RoomMonth>
     */
    public function rooms(): Collection
    {
        return $this->allRooms
            ->filter(fn (Room $room) => ! $room->archived)
            ->map(fn (Room $room) => $this->roomMonth($room))
            ->values();
    }

    /** Satu ruang milik pengguna ini (terarsip juga, riwayatnya tetap bisa dilihat); null bila bukan miliknya. */
    public function room(Room|int $room): ?RoomMonth
    {
        $id = $room instanceof Room ? (int) $room->id : $room;
        $found = $this->allRooms->first(fn (Room $r) => (int) $r->id === $id);

        return $found ? $this->roomMonth($found) : null;
    }

    public function isCurrentMonth(): bool
    {
        return $this->month->format('Y-m') === $this->today->format('Y-m');
    }

    /** Bulan yang sudah berakhir (status Menunaikan/Menumbuhkan bisa "Belum tercapai"). */
    public function isPastMonth(): bool
    {
        return $this->month->lessThan($this->today->startOfMonth());
    }

    public function daysInMonth(): int
    {
        return $this->month->daysInMonth;
    }

    /** Hari ini untuk bulan berjalan, hari terakhir untuk bulan lampau, 0 untuk bulan depan. */
    public function dayOfMonth(): int
    {
        if ($this->isCurrentMonth()) {
            return $this->today->day;
        }

        return $this->isPastMonth() ? $this->daysInMonth() : 0;
    }

    /** Sisa hari bulan berjalan termasuk hari ini (minimal 1); 0 untuk bulan lain. */
    public function daysLeft(): int
    {
        return $this->isCurrentMonth() ? $this->daysInMonth() - $this->today->day + 1 : 0;
    }

    /** Bagian bulan yang sudah dilalui (hari ke-n dari jumlah hari), basis point dibulatkan ke bawah. */
    public function timeProgressBp(): int
    {
        return intdiv($this->dayOfMonth() * AllocationEngine::FULL_BP, $this->daysInMonth());
    }

    /**
     * "Sisa aman hari ini" (SafeToSpend di :domain, rumus disetujui 2026-09-24): jatah tersisa ruang
     * bertipe Mencukupi sebelum hari ini (ruang yang lewat jatah dihitung 0) dibagi sisa hari termasuk
     * hari ini, dibulatkan ke bawah, dikurangi pengeluaran hari ini; tidak pernah negatif. Null bila
     * bukan bulan berjalan atau tidak ada ruang Mencukupi yang punya jatah bulan ini.
     *
     * @return array{daily_budget_minor: int, spent_today_minor: int, days_left: int, remaining_minor: int, is_over: bool}|null
     *   `remaining_minor` = max(jatah harian - terpakai hari ini, 0); `is_over` = terpakai hari ini
     *   melewati jatah harian (tampil 0 dengan teks lembut). Sisa setelah pengeluaran baru X:
     *   max(remaining_minor - X, 0).
     */
    public function safeToSpend(): ?array
    {
        if (! $this->isCurrentMonth()) {
            return null;
        }

        $everyday = $this->rooms()->filter(fn (RoomMonth $r) => $r->room->type === Room::TYPE_MENCUKUPI && $r->allocatedMinor > 0);
        if ($everyday->isEmpty()) {
            return null;
        }

        $spentToday = 0;
        $pool = 0;
        foreach ($everyday as $roomMonth) {
            $today = $roomMonth->spentTodayMinor();
            $spentToday += $today;
            $pool += max($roomMonth->allocatedMinor - ($roomMonth->spentMinor - $today), 0);
        }

        $daysLeft = $this->daysLeft();
        $daily = intdiv($pool, $daysLeft);

        return [
            'daily_budget_minor' => $daily,
            'spent_today_minor' => $spentToday,
            'days_left' => $daysLeft,
            'remaining_minor' => $spentToday >= $daily ? 0 : $daily - $spentToday,
            'is_over' => $spentToday > $daily,
        ];
    }

    /** Jumlah pengeluaran bulan ini di pos sistem Tak terlacak (ruang mana pun). */
    public function untrackedCount(): int
    {
        return array_sum(array_map(fn (int $id) => $this->data['category'][$id]['count'] ?? 0, $this->untrackedCategoryIds()));
    }

    /** Rupiah pengeluaran bulan ini di pos sistem Tak terlacak. */
    public function untrackedMinor(): int
    {
        return array_sum(array_map(fn (int $id) => $this->data['category'][$id]['total'] ?? 0, $this->untrackedCategoryIds()));
    }

    /**
     * Pengeluaran bulan ini sampai tanggal $day (inklusif), untuk dibandingkan dengan bulan lalu pada
     * tanggal yang sama. $day di atas jumlah hari bulan itu berarti sebulan penuh.
     */
    public function spentThroughDay(int $day): int
    {
        $sum = 0;
        foreach ($this->data['by_day'] as $d => $total) {
            if ($d <= $day) {
                $sum += $total;
            }
        }

        return $sum;
    }

    /**
     * Pengeluaran semua ruang per tanggal bulan ini.
     *
     * @return array<int, int> tanggal (1..jumlah hari) => rupiah, termasuk hari tanpa pengeluaran (0)
     */
    public function spentByDay(): array
    {
        $days = [];
        for ($day = 1; $day <= $this->daysInMonth(); $day++) {
            $days[$day] = $this->data['by_day'][$day] ?? 0;
        }

        return $days;
    }

    /**
     * Butir "Perlu perhatian" (AttentionItem di :domain) sebagai data mentah, hanya untuk bulan
     * berjalan, urut: ruang Mencukupi yang lewat jatah (persen terbesar dulu), lalu yang mendekati
     * (85% ke atas, belum lewat), lalu rezeki yang belum dialirkan. Saldo akun yang lama tidak
     * dicocokkan (AccountNotReconciled) belum ada karena web belum punya koreksi saldo. Tidak
     * dipotong; Android menampilkan paling banyak MAX_ATTENTION.
     *
     * @return list<array{kind: string, room?: Room, room_month?: RoomMonth, allocated_minor?: int, spent_minor?: int, progress_bp?: int, amount_minor?: int}>
     *   kind 'room_over' dan 'room_near': room, room_month, allocated_minor, spent_minor, progress_bp.
     *   kind 'unallocated': amount_minor.
     */
    public function attention(): array
    {
        if (! $this->isCurrentMonth()) {
            return [];
        }

        $rooms = $this->rooms();
        $item = fn (string $kind, RoomMonth $r) => [
            'kind' => $kind,
            'room' => $r->room,
            'room_month' => $r,
            'allocated_minor' => $r->allocatedMinor,
            'spent_minor' => $r->spentMinor,
            'progress_bp' => $r->progressBp ?? 0,
        ];

        $over = $rooms
            ->filter(fn (RoomMonth $r) => $r->isOver() && $r->room->type === Room::TYPE_MENCUKUPI)
            ->sortByDesc(fn (RoomMonth $r) => $r->progressBp)
            ->map(fn (RoomMonth $r) => $item(self::ATTENTION_ROOM_OVER, $r));

        $near = $rooms
            ->filter(fn (RoomMonth $r) => $r->status() === RoomMonth::STATUS_PERLU_PERHATIAN && ! $r->isOver())
            ->sortByDesc(fn (RoomMonth $r) => $r->progressBp)
            ->map(fn (RoomMonth $r) => $item(self::ATTENTION_ROOM_NEAR, $r));

        $items = [...$over->values()->all(), ...$near->values()->all()];
        if ($this->unallocatedMinor > 0) {
            $items[] = ['kind' => self::ATTENTION_UNALLOCATED, 'amount_minor' => $this->unallocatedMinor];
        }

        return $items;
    }

    /**
     * @internal untuk RoomMonth
     *
     * @return array<int, int> tanggal => rupiah
     */
    public function roomSpentByDay(int $roomId): array
    {
        return $this->data['room_day'][$roomId] ?? [];
    }

    /**
     * @internal untuk RoomMonth
     *
     * @return array<int, array{total: int, count: int}> id pos => terpakai dan jumlah transaksi
     */
    public function roomSpentByCategory(int $roomId): array
    {
        return $this->data['room_category'][$roomId] ?? [];
    }

    /**
     * @internal untuk RoomMonth: semua pos ruang ini (aktif dan terarsip), dimuat sekali per kelompok bulan.
     *
     * @return Collection<int, Category>
     */
    public function categoriesOf(int $roomId): Collection
    {
        return $this->categories()->get($roomId, new Collection);
    }

    /** @return Collection<int, Collection<int, Category>> id ruang => pos */
    private function categories(): Collection
    {
        if ($this->shared['categories'] === null) {
            $this->shared['categories'] = Category::query()
                ->whereIn('room_id', $this->shared['room_ids'])
                ->orderBy('sort_order')
                ->orderBy('id')
                ->get()
                ->groupBy('room_id');
        }

        return $this->shared['categories'];
    }

    /** @return list<int> */
    private function untrackedCategoryIds(): array
    {
        return $this->categories()
            ->flatten(1)
            ->filter(fn (Category $c) => $c->isUntracked())
            ->map(fn (Category $c) => (int) $c->id)
            ->values()
            ->all();
    }

    private function roomMonth(Room $room): RoomMonth
    {
        $id = (int) $room->id;

        return $this->roomMonths[$id] ??= new RoomMonth(
            $room,
            $this->data['allocated'][$id] ?? 0,
            array_sum($this->data['room_day'][$id] ?? []),
            $this,
        );
    }
}
