<?php

namespace App\Livewire;

use App\Models\Bill;
use App\Models\RecurringRule;
use App\Models\User;
use App\Support\RecurrenceSchedule;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Illuminate\Support\Collection;
use Livewire\Component;

/**
 * Jadwal gabungan tagihan dan transaksi berulang (menu Uang › Jadwal, docs/design/web-mockup).
 * Tab Agenda menampilkan kemunculan 30 hari ke depan dari tagihan yang belum lunas dan tidak dijeda
 * (dibayar manual lewat Tagihan) serta aturan berulang yang aktif (dicatat otomatis oleh RecurringRunner),
 * dikelompokkan per tanggal. Tagihan yang terlambat ikut tampil di tanggal jatuh tempo aslinya.
 * Murni tampilan: tidak ada yang dicatat atau diubah dari sini. Tab Tagihan dan Berulang adalah
 * halaman Tagihan dan TransaksiBerulang (komponen x-jadwal.tabs).
 */
class Jadwal extends Component
{
    /** Jendela agenda: hari ini sampai 29 hari sesudahnya (30 hari). */
    public const WINDOW_DAYS = 30;

    /** Batas pengaman putaran kemunculan per tagihan atau aturan. */
    private const MAX_OCCURRENCES = 100;

    private const DAY_NAMES = [1 => 'Sen', 2 => 'Sel', 3 => 'Rab', 4 => 'Kam', 5 => 'Jum', 6 => 'Sab', 7 => 'Min'];

    private const MONTH_NAMES = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];

    /**
     * Kemunculan dalam jendela agenda, urut tanggal lalu jenis (tagihan dulu) lalu judul.
     *
     * @return Collection<int, array<string, mixed>>
     */
    public static function occurrences(User $user, Carbon $today): Collection
    {
        $end = $today->copy()->addDays(self::WINDOW_DAYS - 1);
        $items = [];

        $bills = $user->bills()->with(['room', 'category', 'account'])->where('active', true)->get();
        foreach ($bills as $bill) {
            array_push($items, ...self::billOccurrences($bill, $today, $end));
        }

        $rules = $user->recurringRules()->with(['room', 'category', 'account'])->where('active', true)->get();
        foreach ($rules as $rule) {
            array_push($items, ...self::ruleOccurrences($rule, $today, $end));
        }

        return collect($items)
            ->sort(fn (array $a, array $b) => [$a['date']->timestamp, $a['kind'] === 'tagihan' ? 0 : 1, mb_strtolower($a['title'])]
                <=> [$b['date']->timestamp, $b['kind'] === 'tagihan' ? 0 : 1, mb_strtolower($b['title'])])
            ->values();
    }

    /** @return list<array<string, mixed>> */
    private static function billOccurrences(Bill $bill, Carbon $today, Carbon $end): array
    {
        if ($bill->isFinished()) {
            return [];
        }

        $usable = $bill->isUsable();
        $limit = $bill->frequency === null ? 1 : $bill->remainingInstallments();
        $date = $bill->next_due->copy()->startOfDay();
        $items = [];

        for ($count = 0; $count < self::MAX_OCCURRENCES && ($limit === null || $count < $limit) && $date->lessThanOrEqualTo($end); $count++) {
            // Yang terlambat hanya kemunculan pertama (jatuh tempo yang belum dibayar); sesudahnya hanya yang belum lewat.
            if ($count === 0 || $date->greaterThanOrEqualTo($today)) {
                $status = match (true) {
                    ! $usable => 'perlu_dicek',
                    $date->lessThan($today) => 'terlambat',
                    $date->equalTo($today) => 'hari_ini',
                    $date->lessThanOrEqualTo($today->copy()->addDays(3)) => 'segera',
                    default => 'mendatang',
                };
                $title = $bill->name;
                if ($bill->total_installments !== null) {
                    $title .= ' ('.($bill->paid_count + $count + 1).'/'.$bill->total_installments.')';
                }
                $items[] = [
                    'kind' => 'tagihan',
                    'date' => $date->copy(),
                    'title' => $title,
                    'sub' => $bill->room->name.' › '.$bill->category->name,
                    'kind_label' => 'Tagihan · dibayar manual',
                    'amount' => $bill->amount_minor,
                    'direction' => 'keluar',
                    'status' => $status,
                    'note' => $usable ? null : 'Akun, ruang, atau kategorinya sudah diarsipkan.',
                    'url' => route('tagihan'),
                ];
            }

            if ($bill->frequency === null) {
                break;
            }
            $date = RecurrenceSchedule::next($bill->start_date, $bill->frequency, $date);
        }

        return $items;
    }

    /** @return list<array<string, mixed>> */
    private static function ruleOccurrences(RecurringRule $rule, Carbon $today, Carbon $end): array
    {
        if ($rule->isFinished()) {
            return [];
        }

        $usable = $rule->isUsable();
        $expense = $rule->type === 'pengeluaran';
        $date = $rule->next_due->copy()->startOfDay();
        $items = [];

        $make = function (Carbon $on, string $status, ?string $note = null) use ($rule, $expense) {
            $target = $expense ? $rule->room?->name.' › '.$rule->category?->name : 'Pemasukan'.($rule->source ? ' › '.$rule->source : '');

            return [
                'kind' => 'berulang',
                'date' => $on,
                'title' => $rule->note ?: ($expense ? ($rule->category?->name ?? 'Pengeluaran') : ($rule->source ?: 'Pemasukan')),
                'sub' => $target,
                'kind_label' => 'Berulang · dicatat otomatis',
                'amount' => $rule->amount_minor,
                'direction' => $expense ? 'keluar' : 'masuk',
                'status' => $status,
                'note' => $note,
                'url' => route('transaksi.berulang'),
            ];
        };

        // Sudah lewat tanggalnya tetapi belum dicatat (RecurringRunner jalan saat Denah dibuka): tampil hari ini.
        if ($date->lessThan($today)) {
            $items[] = $make($today->copy(), $usable ? 'menunggu' : 'tertahan', 'Seharusnya '.$date->day.' '.self::MONTH_NAMES[$date->month - 1].'; dicatat saat Denah dibuka.');
            $date = RecurrenceSchedule::firstOnOrAfter($rule->start_date, $rule->frequency, $today);
        }

        for ($count = 0; $count < self::MAX_OCCURRENCES && $date->lessThanOrEqualTo($end); $count++) {
            if ($rule->end_date !== null && $date->greaterThan($rule->end_date)) {
                break;
            }
            $items[] = $make($date->copy(), ! $usable ? 'tertahan' : ($date->equalTo($today) ? 'hari_ini' : 'terjadwal'));
            $date = RecurrenceSchedule::next($rule->start_date, $rule->frequency, $date);
        }

        return $items;
    }

    public function render(): View
    {
        $user = auth()->user();
        $today = Carbon::today();
        $items = self::occurrences($user, $today);

        $groups = $items->groupBy(fn (array $item) => $item['date']->toDateString())
            ->map(function (Collection $group, string $key) use ($today) {
                $date = Carbon::parse($key);
                $diff = $today->diffInDays($date, false);
                $relative = match (true) {
                    $diff === 0 => 'Hari ini · ',
                    $diff === 1 => 'Besok · ',
                    $diff < 0 => 'Lewat jatuh tempo · ',
                    default => '',
                };

                return [
                    'date' => $date,
                    'day_name' => self::DAY_NAMES[$date->dayOfWeekIso],
                    'label' => $relative.$date->day.' '.self::MONTH_NAMES[$date->month - 1],
                    'items' => $group->values(),
                ];
            })
            ->values();

        $out = $items->where('direction', 'keluar');
        $in = $items->where('direction', 'masuk');

        return view('livewire.jadwal', [
            'groups' => $groups,
            'items' => $items,
            'outMinor' => $out->sum('amount'),
            'outCount' => $out->count(),
            'inMinor' => $in->sum('amount'),
            'inCount' => $in->count(),
            'lateCount' => $items->where('status', 'terlambat')->count(),
            'today' => $today,
        ])->layout('components.layout', ['title' => 'Jadwal — Rizqflow']);
    }
}
