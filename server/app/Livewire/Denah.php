<?php

namespace App\Livewire;

use App\Models\Room;
use App\Models\Transaction;
use App\Support\AllocationEngine;
use App\Support\MonthLedger;
use App\Support\NetWorthSnapshotter;
use App\Support\Period;
use App\Support\RecurrenceSchedule;
use App\Support\RecurringRunner;
use App\Support\Rupiah;
use App\Support\RoomMonth;
use Carbon\CarbonImmutable;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Beranda (S05 Denah, mockup docs/design/web-mockup/project/Main.dc.html): ringkasan rezeki satu
 * bulan, jadwal 7 hari, kartu ruang, transaksi terbaru, dan "perlu perhatian". Semua angka diturunkan
 * dari transaksi dan potret alokasi lewat App\Support\MonthLedger; bulan mengikuti ?bulan= (pemilih
 * periode di bilah atas). Jadwal 7 hari dan perbandingan dengan bulan lalu hanya untuk bulan berjalan.
 */
class Denah extends Component
{
    /** Jendela "Jadwal 7 hari": hari ini sampai hari ini + 7. */
    private const SCHEDULE_DAYS = 7;

    /** Paling banyak baris di kartu jadwal; sisanya ada di halaman Jadwal. */
    private const SCHEDULE_MAX_ROWS = 6;

    /** Haul zakat masuk "Perlu perhatian" bila tinggal sekian hari. */
    private const HAUL_ALERT_DAYS = 30;

    private const TYPE_LABELS = [
        Room::TYPE_MENUNAIKAN => 'Menunaikan',
        Room::TYPE_MENUMBUHKAN => 'Menumbuhkan',
        Room::TYPE_MENCUKUPI => 'Mencukupi',
    ];

    private const NUMBER_WORDS = [1 => 'satu', 2 => 'dua', 3 => 'tiga', 4 => 'empat', 5 => 'lima', 6 => 'enam', 7 => 'tujuh', 8 => 'delapan', 9 => 'sembilan', 10 => 'sepuluh'];

    #[Url]
    public ?string $bulan = null;

    public ?string $recurringBanner = null;

    public string $recurringTone = 'info';

    /**
     * Tidak ada penjadwal latar belakang: kemunculan transaksi berulang (S33) dicatat di sini,
     * setiap kali Denah dibuka — padanan web untuk "setiap aplikasi dibuka" di mobile.
     */
    public function mount(): void
    {
        $user = auth()->user();
        $result = RecurringRunner::runDue($user);

        if ($result['recorded'] > 0) {
            $this->recurringBanner = $result['recorded'].' transaksi berulang dicatat.';
            $this->recurringTone = 'good';
        } elseif ($result['blocked'] !== []) {
            $this->recurringBanner = count($result['blocked']).' aturan berulang tertahan — akun, ruang, atau kategorinya sudah diarsipkan.';
            $this->recurringTone = 'warn';
        }

        // Dicatat untuk semua user (bukan hanya Pro) supaya riwayat sudah ada begitu upgrade.
        NetWorthSnapshotter::captureIfMissing($user);
    }

    public function render(): View
    {
        $user = auth()->user();
        $month = Period::fromQuery($this->bulan);
        $today = CarbonImmutable::today();
        $ledger = MonthLedger::for($user, $month, $today);
        $isCurrent = $ledger->isCurrentMonth();
        $rooms = $ledger->rooms();
        $monthName = $month->locale('id')->translatedFormat('F');

        // Dari tautan ke halaman lain: bulan hanya dibawa bila bukan bulan berjalan.
        $periodQuery = $isCurrent ? [] : ['bulan' => Period::query($month)];

        $needAttentionRooms = $rooms->filter(fn (RoomMonth $r) => $r->status() === RoomMonth::STATUS_PERLU_PERHATIAN)->count();

        return view('livewire.denah', [
            'recurringBanner' => $this->recurringBanner,
            'recurringTone' => $this->recurringTone,
            'dateLabel' => now()->locale('id')->translatedFormat('l, j F Y'),
            'ledger' => $ledger,
            'isCurrent' => $isCurrent,
            'monthName' => $monthName,
            'periodQuery' => $periodQuery,
            'lead' => $this->lead($ledger, $rooms->count(), $needAttentionRooms, $monthName),
            'income' => $this->incomeCard($user, $ledger, $rooms, $month, $today, $monthName),
            'schedule' => $isCurrent ? $this->schedule($user, $today) : null,
            'roomCards' => $this->roomCards($ledger, $rooms),
            'recent' => $this->recent($user, $month),
            'attention' => $this->attention($user, $ledger, $rooms, $today),
        ])->layout('components.layout', ['title' => 'Denah — Rizqflow', 'period' => $month]);
    }

    /** Kalimat lead kepala halaman, disusun dari data; benar untuk semua kombinasi. */
    private function lead(MonthLedger $ledger, int $roomCount, int $needAttentionRooms, string $monthName): string
    {
        $untracked = $ledger->untrackedCount();

        if ($roomCount === 0 && $ledger->incomeMinor <= 0) {
            return 'Belum ada ruang dan belum ada rezeki. Mulailah dengan menyusun ruang dan aturan alokasi.';
        }

        $first = match (true) {
            $ledger->incomeMinor <= 0 => "Belum ada rezeki masuk di {$monthName}.",
            $ledger->unallocatedMinor > 0 => "Sebagian rezeki {$monthName} belum dialirkan.",
            default => "Rezeki {$monthName} sudah dialirkan penuh.",
        };

        $issues = [];
        if ($needAttentionRooms > 0) {
            $issues[] = $this->number($needAttentionRooms).' ruang perlu perhatian';
        }
        if ($untracked > 0) {
            $issues[] = $this->number($untracked).' transaksi belum punya pos';
        }

        if ($issues !== []) {
            return $first.' '.ucfirst(implode(' dan ', $issues)).'.';
        }

        if ($ledger->incomeMinor <= 0) {
            return $ledger->isCurrentMonth() ? $first.' Catat rezeki masuk supaya bisa dialirkan ke ruang.' : $first;
        }

        return $first.' Semuanya berjalan tenang.';
    }

    private function number(int $n): string
    {
        return self::NUMBER_WORDS[$n] ?? (string) $n;
    }

    /**
     * Kartu rezeki: nominal, keterangan pemasukan terakhir, bar pembagian aktual, dan tiga statistik.
     *
     * @return array<string, mixed>
     */
    private function incomeCard($user, MonthLedger $ledger, $rooms, CarbonImmutable $month, CarbonImmutable $today, string $monthName): array
    {
        $last = $user->transactions()->income()->inMonth($month)
            ->with('allocationEntries.room')
            ->orderByDesc('occurred_on')->orderByDesc('id')
            ->first();

        $caption = null;
        if ($last) {
            $origin = $last->note ?: $last->category;
            $label = $last->allocationLabel();
            $caption = ($last->allocationEntries->isNotEmpty() ? 'Dialirkan ' : 'Dicatat ')
                .$last->occurred_on->locale('id')->translatedFormat('j M')
                .($origin ? ' dari '.$origin : '')
                .($label ? ' · aturan '.$label : '');
        }

        // Bar dan legenda: jatah aktual per ruang bulan itu, dalam basis point dari seluruh yang dialirkan.
        $segments = [];
        if ($ledger->allocatedMinor > 0) {
            foreach ($rooms as $roomMonth) {
                if ($roomMonth->allocatedMinor <= 0) {
                    continue;
                }
                $segments[] = [
                    'slot' => $roomMonth->room->slot,
                    'bp' => $this->roundedBp($roomMonth->allocatedMinor, $ledger->allocatedMinor),
                    'label' => $roomMonth->room->name,
                ];
            }
        }

        // Terpakai: pembanding bulan lalu pada tanggal yang sama (hanya bulan berjalan).
        $compare = null;
        if ($ledger->isCurrentMonth()) {
            $previousName = $month->subMonthNoOverflow()->locale('id')->translatedFormat('F');
            $previous = MonthLedger::for($user, $month->subMonthNoOverflow(), $today)->spentThroughDay($today->day);
            $diff = $ledger->spentMinor - $previous;
            if ($previous > 0 || $ledger->spentMinor > 0) {
                if ($previous <= 0) {
                    $compare = ['icon' => null, 'text' => "Belum ada pengeluaran di {$previousName} pada tanggal yang sama"];
                } elseif ($diff === 0) {
                    $compare = ['icon' => null, 'text' => "Sama dengan {$previousName} pada tanggal yang sama"];
                } else {
                    $percent = intdiv(AllocationEngine::mulDivFloor(abs($diff), 200, $previous) + 1, 2);
                    $compare = [
                        'icon' => $diff < 0 ? 'arrow-down' : 'arrow-up',
                        'text' => Rupiah::format(abs($diff)).' ('.$percent.'%) '.($diff < 0 ? 'di bawah' : 'di atas')." {$previousName} pada tanggal yang sama",
                    ];
                }
            }
        }

        // Sisa: jatah ruang yang belum terpakai (ruang yang melewati jatah dihitung 0).
        $remaining = 0;
        $spreadRooms = [];
        foreach ($rooms as $roomMonth) {
            $left = $roomMonth->remainingMinor();
            if ($left > 0) {
                $remaining += $left;
                $spreadRooms[] = $roomMonth->room->name;
            }
        }

        return [
            'caption' => $caption,
            'segments' => $segments,
            'compare' => $compare,
            'remaining' => $remaining,
            'remainingNote' => match (true) {
                $spreadRooms === [] => $ledger->allocatedMinor > 0 ? 'Tidak ada ruang yang bersisa' : 'Belum ada jatah ruang',
                $ledger->isCurrentMonth() => $ledger->daysLeft().' hari lagi, tersebar di '.$this->joinNames($spreadRooms),
                default => 'Tersebar di '.$this->joinNames($spreadRooms),
            },
            'remainingLabel' => $ledger->isCurrentMonth() ? 'Sisa bulan ini' : 'Sisa jatah',
        ];
    }

    /** "A", "A dan B", atau "A, B, dan C". */
    private function joinNames(array $names): string
    {
        if (count($names) <= 2) {
            return implode(' dan ', $names);
        }

        return implode(', ', array_slice($names, 0, -1)).', dan '.end($names);
    }

    /** Basis point dibulatkan setengah ke atas: $part dari $total. */
    private function roundedBp(int $part, int $total): int
    {
        return intdiv(AllocationEngine::mulDivFloor($part, 2 * AllocationEngine::FULL_BP, $total) + 1, 2);
    }

    /**
     * Jadwal 7 hari: tagihan aktif yang jatuh tempo paling lambat 7 hari lagi (termasuk yang terlambat)
     * dan kemunculan transaksi berulang aktif di jendela itu.
     *
     * @return array{rows: list<array<string, mixed>>, more: int}
     */
    private function schedule($user, CarbonImmutable $today): array
    {
        $start = Carbon::today();
        $end = $start->copy()->addDays(self::SCHEDULE_DAYS);
        $rows = [];

        $bills = $user->bills()->where('active', true)->get();
        foreach ($bills as $bill) {
            if ($bill->isFinished() || $bill->next_due->greaterThan($end)) {
                continue;
            }
            $status = $bill->statusOn($start);
            $rows[] = [
                'date' => $bill->next_due->copy(),
                'title' => $bill->name,
                'kind' => 'Tagihan · dibayar manual',
                'amount' => Rupiah::format($bill->amount_minor),
                'income' => false,
                'pill' => $this->schedulePill($status),
            ];
        }

        $rules = $user->recurringRules()->where('active', true)->with(['account', 'room', 'category'])->get();
        foreach ($rules as $rule) {
            if ($rule->isFinished() || ! $rule->isUsable()) {
                continue;
            }
            $due = $rule->next_due->greaterThan($start) ? $rule->next_due->copy() : $start->copy();
            for ($steps = 0; $steps < 10 && $due->lessThanOrEqualTo($end) && ($rule->end_date === null || $due->lessThanOrEqualTo($rule->end_date)); $steps++) {
                $isIncome = $rule->type === 'pemasukan';
                $rows[] = [
                    'date' => $due->copy(),
                    'title' => $rule->note ?: ($isIncome ? ($rule->source ?: 'Pemasukan') : ($rule->category?->name ?? 'Pengeluaran')),
                    'kind' => 'Berulang · dicatat otomatis',
                    'amount' => $isIncome ? Rupiah::signed($rule->amount_minor, Transaction::TYPE_INCOME) : Rupiah::format($rule->amount_minor),
                    'income' => $isIncome,
                    'pill' => $this->schedulePill($this->statusOf($due, $start)),
                ];
                $due = RecurrenceSchedule::next($rule->start_date, $rule->frequency, $due);
            }
        }

        usort($rows, fn (array $a, array $b) => [$a['date']->toDateString(), $a['title']] <=> [$b['date']->toDateString(), $b['title']]);
        $more = max(count($rows) - self::SCHEDULE_MAX_ROWS, 0);
        $rows = array_map(function (array $row) {
            $row['day'] = $row['date']->locale('id')->translatedFormat('D');
            $row['dayNumber'] = $row['date']->format('j');

            return $row;
        }, array_slice($rows, 0, self::SCHEDULE_MAX_ROWS));

        return ['rows' => $rows, 'more' => $more];
    }

    /** Status jatuh tempo, sama dengan Bill::statusOn: hari ini, segera (paling lambat 3 hari), mendatang. */
    private function statusOf(Carbon $due, Carbon $today): string
    {
        return match (true) {
            $due->lessThan($today) => 'terlambat',
            $due->equalTo($today) => 'hari_ini',
            $due->lessThanOrEqualTo($today->copy()->addDays(3)) => 'segera',
            default => 'mendatang',
        };
    }

    /** @return array{tone: string, icon: ?string, label: string} */
    private function schedulePill(string $status): array
    {
        return match ($status) {
            'terlambat' => ['tone' => 'warn', 'icon' => 'warn', 'label' => 'Terlambat'],
            'hari_ini' => ['tone' => 'warn', 'icon' => 'clock', 'label' => 'Hari ini'],
            'segera' => ['tone' => 'warn', 'icon' => 'clock', 'label' => 'Segera'],
            default => ['tone' => 'neutral', 'icon' => '', 'label' => 'Mendatang'],
        };
    }

    /**
     * Data kartu ruang. Teks kaki kartu dipilih di sini supaya Blade tinggal menampilkan.
     *
     * @return list<array<string, mixed>>
     */
    private function roomCards(MonthLedger $ledger, $rooms): array
    {
        $cards = [];
        foreach ($rooms as $roomMonth) {
            $room = $roomMonth->room;
            $status = $roomMonth->status();
            $type = self::TYPE_LABELS[$room->type] ?? '';

            // Persen yang tampil: bagian aktual bulan itu bila sudah ada yang dialirkan, kalau tidak aturan sekarang.
            $sharePercent = $ledger->allocatedMinor > 0 && $roomMonth->allocatedMinor > 0
                ? intdiv($this->roundedBp($roomMonth->allocatedMinor, $ledger->allocatedMinor) + 50, 100)
                : ($roomMonth->shareBp !== null ? intdiv($roomMonth->shareBp, 100) : null);

            $showPace = $ledger->isCurrentMonth() && $roomMonth->progressBp !== null && $status !== RoomMonth::STATUS_TERPENUHI;
            $paceText = null;
            if ($showPace) {
                $timeBp = $ledger->timeProgressBp();
                $paceText = 'Hari ke-'.$ledger->dayOfMonth().' dari '.$ledger->daysInMonth().' · laju '.match (true) {
                    $roomMonth->progressBp > $timeBp => 'di atas waktu',
                    $roomMonth->progressBp < $timeBp => 'di bawah waktu',
                    default => 'sejalan dengan waktu',
                };
            }

            $remaining = $roomMonth->remainingMinor();
            $footer = match (true) {
                $roomMonth->allocatedMinor <= 0 => ['kind' => 'menunggu'],
                $status === RoomMonth::STATUS_TERPENUHI => ['kind' => $room->type === Room::TYPE_MENCUKUPI ? 'terpenuhi_hemat' : 'terpenuhi'],
                $remaining < 0 => ['kind' => 'lewat', 'amount' => -$remaining],
                $ledger->isCurrentMonth() => ['kind' => 'sisa_aman', 'amount' => $remaining, 'safe' => $roomMonth->safePerDayMinor() ?? 0],
                default => ['kind' => 'sisa', 'amount' => $remaining],
            };

            $cards[] = [
                'month' => $roomMonth,
                'room' => $room,
                'colorClass' => $room->slot >= 1 && $room->slot <= 8 ? 'c'.$room->slot : 'cn',
                'caption' => $type.($sharePercent !== null ? ' · '.$sharePercent.'%' : ''),
                'percent' => $roomMonth->percent() ?? 0,
                'warn' => $status === RoomMonth::STATUS_PERLU_PERHATIAN,
                'pillIcon' => match ($roomMonth->statusTone()) {
                    'neutral' => 'clock',
                    default => null,
                },
                'showPace' => $showPace,
                'paceText' => $paceText,
                'paceTime' => $ledger->timeProgressBp() / 100,
                'footer' => $footer,
            ];
        }

        return $cards;
    }

    /**
     * Lima transaksi terbaru bulan itu.
     *
     * @return \Illuminate\Support\Collection<int, Transaction>
     */
    private function recent($user, CarbonImmutable $month)
    {
        return $user->transactions()->inMonth($month)
            ->with(['room', 'pos', 'account', 'allocationEntries.room'])
            ->orderByDesc('occurred_on')->orderByDesc('id')
            ->limit(5)
            ->get();
    }

    /**
     * Butir "Perlu perhatian": ruang lewat/mendekati jatah, rezeki belum dialirkan, pos yang lewat jatah,
     * haul zakat, dan transaksi Tak terlacak. Tiap butir: text (teks biasa), href, dan pil (tone, icon, label).
     *
     * @return list<array{text: string, href: string, tone: string, icon: ?string, label: string}>
     */
    private function attention($user, MonthLedger $ledger, $rooms, CarbonImmutable $today): array
    {
        $items = [];
        $query = $ledger->isCurrentMonth() ? [] : ['bulan' => Period::query($ledger->month)];

        foreach ($ledger->attention() as $item) {
            if ($item['kind'] === MonthLedger::ATTENTION_UNALLOCATED) {
                $items[] = [
                    'text' => Rupiah::format($item['amount_minor']).' rezeki bulan ini belum dialirkan',
                    'href' => route('aturan-alokasi'),
                    'tone' => 'info',
                    'icon' => null,
                    'label' => 'Belum dialirkan',
                ];

                continue;
            }

            /** @var RoomMonth $roomMonth */
            $roomMonth = $item['room_month'];
            $href = route('ruang.detail', ['room' => $item['room']] + $query);
            if ($item['kind'] === MonthLedger::ATTENTION_ROOM_OVER) {
                $items[] = [
                    'text' => $item['room']->name.' melewati jatah bulan ini ('.$roomMonth->percent().'% terpakai)',
                    'href' => $href,
                    'tone' => 'warn',
                    'icon' => null,
                    'label' => 'Melewati jatah',
                ];
            } else {
                $left = max($roomMonth->remainingMinor(), 0);
                $items[] = [
                    'text' => $item['room']->name.' terpakai '.$roomMonth->percent().'%; sisa '.Rupiah::format($left).' untuk '.$ledger->daysLeft().' hari',
                    'href' => $href,
                    'tone' => 'warn',
                    'icon' => null,
                    'label' => 'Perlu perhatian',
                ];
            }
        }

        // Pos (bukan pos sistem) yang melewati jatahnya, paling jauh dulu.
        $overPos = [];
        foreach ($rooms as $roomMonth) {
            foreach ($roomMonth->categoryBreakdown() as $row) {
                if ($row['category']->is_system || $row['progress_bp'] === null || $row['progress_bp'] <= AllocationEngine::FULL_BP) {
                    continue;
                }
                $overPos[] = ['room' => $roomMonth->room, 'name' => $row['category']->name, 'over' => intdiv($row['progress_bp'] - AllocationEngine::FULL_BP, 100), 'bp' => $row['progress_bp']];
            }
        }
        usort($overPos, fn (array $a, array $b) => $b['bp'] <=> $a['bp']);
        foreach (array_slice($overPos, 0, 3) as $pos) {
            $items[] = [
                'text' => 'Pos '.$pos['name'].' melewati jatah '.$pos['over'].'%',
                'href' => route('ruang.detail', ['room' => $pos['room']] + $query),
                'tone' => 'warn',
                'icon' => null,
                'label' => 'Melewati jatah',
            ];
        }

        // Haul zakat yang tinggal sebentar (hanya bulan berjalan).
        if ($ledger->isCurrentMonth()) {
            $profiles = $user->zakatProfiles()->active()->where('status', 'haul_berjalan')->get();
            foreach ($profiles as $profile) {
                if (! $profile->haul_day || ! $profile->haul_total_days) {
                    continue;
                }
                $daysLeft = max($profile->haul_total_days - $profile->haul_day, 0);
                if ($daysLeft > self::HAUL_ALERT_DAYS) {
                    continue;
                }
                $name = $profiles->count() > 1 ? ' '.$profile->name : '';
                $items[] = [
                    'text' => $daysLeft === 0
                        ? "Haul zakat{$name} jatuh tempo hari ini"
                        : "Haul zakat{$name} jatuh tempo ≈ ".$today->addDays($daysLeft)->locale('id')->translatedFormat('j M')." ({$daysLeft} hari lagi)",
                    'href' => route('zakat'),
                    'tone' => 'warn',
                    'icon' => 'clock',
                    'label' => 'Segera',
                ];
            }
        }

        if ($ledger->untrackedCount() > 0) {
            $items[] = [
                'text' => $ledger->untrackedCount().' transaksi di Tak terlacak ('.Rupiah::format($ledger->untrackedMinor()).') belum punya pos.',
                'href' => route('transaksi', ['tab' => 'tak'] + $query),
                'tone' => 'info',
                'icon' => null,
                'label' => 'Perlu dirapikan',
            ];
        }

        return $items;
    }
}
