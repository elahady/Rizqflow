<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Room;
use App\Models\RoomRole;
use App\Models\Transaction;
use App\Support\Feature;
use App\Support\MonthLedger;
use App\Support\Period;
use App\Support\RoomMonth;
use App\Support\Rupiah;
use Carbon\CarbonInterface;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Detail ruang (S11, Ruang.dc.html): kepala (jatah vs terpakai), laju belanja bulan itu, pos-pos,
 * riwayat 6 bulan, transaksi terbaru, dan kartu Peran (S32, Pro) untuk ruang selain Memberi.
 * Sadar periode lewat ?bulan=Y-m (pemilih di bilah atas). Daftar dan kelola ruang (S10,
 * tambah/urutkan/arsipkan) ada di RuangDaftar lewat route('ruang') tanpa parameter.
 *
 * Grafik laju belanja digambar server (SVG); angka per hari disiapkan di sini sebagai teks jadi,
 * Alpine di tampilan hanya memilih baris untuk slider. Semua rupiah dan persen bulat (intdiv).
 */
class Ruang extends Component
{
    /** Bidang grafik laju belanja (satuan SVG), sama dengan Ruang.dc.html. */
    private const CHART_TOP = 16;

    private const CHART_BOTTOM = 250;

    private const CHART_LEFT = 56;

    private const CHART_RIGHT = 660;

    /** Tinggi batang tertinggi di riwayat (px) dan jumlah bulan riwayat. */
    private const BAR_MAX_PX = 150;

    private const HISTORY_MONTHS = 6;

    private const SHORT_MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'Mei', 'Jun', 'Jul', 'Agu', 'Sep', 'Okt', 'Nov', 'Des'];

    #[Url]
    public ?string $bulan = null;

    public Room $room;

    public bool $showRoleForm = false;

    public string $roleKind = 'trader';

    public string $capital = '';

    public string $riskPercent = '0.25';

    public string $dcaAmount = '';

    public string $dcaDay = '1';

    public ?int $dcaAccountId = null;

    public ?int $dcaCategoryId = null;

    public ?string $roleError = null;

    public function mount(Room $room): void
    {
        abort_unless($room->user_id === auth()->id(), 403);

        $this->room = $room;
    }

    public function startPickRole(string $kind): void
    {
        $this->roleError = null;

        if (! auth()->user()->entitlements()->isEnabled(Feature::RoleSystems)) {
            $this->roleError = 'Sistem per peran (Trader/Investor) adalah fitur Pro.';

            return;
        }

        $this->roleKind = $kind;
        $this->capital = '';
        $this->riskPercent = '0.25';
        $this->dcaAmount = '';
        $this->dcaDay = '1';
        $this->dcaAccountId = auth()->user()->accounts()->where('archived', false)->orderBy('sort_order')->value('id');
        $this->dcaCategoryId = $this->room->categories()->where('archived', false)->orderBy('sort_order')->value('id');
        $this->showRoleForm = true;
    }

    public function cancelRoleForm(): void
    {
        $this->showRoleForm = false;
        $this->roleError = null;
    }

    public function saveRole(): void
    {
        $this->roleError = null;

        if ($this->roleKind === 'trader') {
            $capital = (int) $this->capital;
            if ($capital <= 0) {
                $this->roleError = 'Modal harus lebih dari nol.';

                return;
            }

            $riskBp = (int) round(((float) $this->riskPercent) * 100);
            if ($riskBp < 1 || $riskBp > RoomRole::MAX_RISK_BP) {
                $this->roleError = 'Risiko per trade 0,01% sampai 50%.';

                return;
            }

            RoomRole::updateOrCreate(
                ['room_id' => $this->room->id],
                ['kind' => 'trader', 'capital_minor' => $capital, 'risk_bp' => $riskBp,
                    'dca_amount_minor' => null, 'dca_day_of_month' => null, 'dca_account_id' => null, 'dca_category_id' => null, 'dca_last_recorded' => null],
            );
        } else {
            $amount = (int) $this->dcaAmount;
            if ($amount <= 0) {
                $this->roleError = 'Nominal DCA harus lebih dari nol.';

                return;
            }

            $day = (int) $this->dcaDay;
            if ($day < 1 || $day > RoomRole::MAX_DCA_DAY) {
                $this->roleError = 'Tanggal DCA 1 sampai '.RoomRole::MAX_DCA_DAY.'.';

                return;
            }

            $account = auth()->user()->accounts()->where('archived', false)->find($this->dcaAccountId);
            if (! $account) {
                $this->roleError = 'Pilih akun sumber.';

                return;
            }

            $category = $this->room->categories()->where('archived', false)->find($this->dcaCategoryId);
            if (! $category) {
                $this->roleError = 'Pilih kategori yang sesuai dengan ruang.';

                return;
            }

            RoomRole::updateOrCreate(
                ['room_id' => $this->room->id],
                ['kind' => 'investor', 'dca_amount_minor' => $amount, 'dca_day_of_month' => $day,
                    'dca_account_id' => $account->id, 'dca_category_id' => $category->id,
                    'capital_minor' => null, 'risk_bp' => null],
            );
        }

        $this->showRoleForm = false;
    }

    public function removeRole(): void
    {
        $this->room->role?->delete();
    }

    public function recordDcaNow(): void
    {
        $role = $this->room->role;
        if (! $role || $role->kind !== 'investor') {
            return;
        }

        Transaction::create([
            'user_id' => auth()->id(),
            'room_id' => $this->room->id,
            'category_id' => $role->dca_category_id,
            'account_id' => $role->dca_account_id,
            'type' => 'pengeluaran',
            'amount_minor' => $role->dca_amount_minor,
            'category' => $role->dcaCategory->name,
            'note' => 'DCA '.$this->room->name,
            'occurred_on' => Carbon::today(),
        ]);

        Account::where('id', $role->dca_account_id)->decrement('balance_minor', $role->dca_amount_minor);

        $role->update(['dca_last_recorded' => Carbon::today()->startOfMonth()]);
    }

    /** Nama bulan Indonesia tiga huruf (tidak tergantung locale aplikasi). */
    public static function shortMonth(CarbonInterface $date): string
    {
        return self::SHORT_MONTHS[$date->month - 1];
    }

    /** Koordinat SVG dari persepuluh piksel (bilangan bulat), mis. 2432 menjadi "243.2". */
    private static function px(int $tenths): string
    {
        return intdiv($tenths, 10).($tenths % 10 === 0 ? '' : '.'.($tenths % 10));
    }

    /**
     * Langkah sumbu rupiah: kelipatan "bulat" (1, 2, 3, atau 5 x 10^k) terkecil yang membuat jumlah
     * garis tidak lebih dari empat.
     *
     * @return array{0: int, 1: int} [langkah, jumlah langkah]
     */
    private static function axisStep(int $max): array
    {
        $max = max($max, 1);
        for ($scale = 1000; ; $scale *= 10) {
            foreach ([1, 2, 3, 5] as $multiple) {
                $step = $multiple * $scale;
                $count = intdiv($max + $step - 1, $step);
                if ($count <= 4) {
                    return [$step, $count];
                }
            }
        }
    }

    /** "Rp 3 jt" menjadi "3 jt": label sumbu tanpa awalan Rp. */
    private static function axisLabel(int $minor): string
    {
        return $minor === 0 ? '0' : trim(str_replace('Rp', '', Rupiah::compact($minor)));
    }

    /**
     * Data grafik "Laju belanja bulan ini". Tanpa jatah atau bulan belum dimulai tidak ada yang bisa
     * dibandingkan: hanya `reason` ('tanpa_jatah' | 'belum_mulai') yang berisi.
     *
     * @return array<string, mixed>
     */
    private function paceChart(RoomMonth $roomMonth, MonthLedger $ledger): array
    {
        $allocated = $roomMonth->allocatedMinor;
        $last = $ledger->dayOfMonth();
        $days = $ledger->daysInMonth();

        if ($allocated <= 0) {
            return ['reason' => 'tanpa_jatah'];
        }
        if ($last < 1) {
            return ['reason' => 'belum_mulai'];
        }

        $byDay = $roomMonth->spentByDay();
        $cumulative = [];
        $sum = 0;
        for ($day = 1; $day <= $last; $day++) {
            $sum += $byDay[$day] ?? 0;
            $cumulative[$day] = $sum;
        }

        [$step, $count] = self::axisStep(max($allocated, $sum));
        $yMax = $step * $count;
        $height = (self::CHART_BOTTOM - self::CHART_TOP) * 10;
        $y = fn (int $value): int => self::CHART_BOTTOM * 10 - intdiv($height * $value, $yMax);
        $x = fn (int $day): int => self::CHART_LEFT * 10 + ($days > 1 ? intdiv((self::CHART_RIGHT - self::CHART_LEFT) * 10 * ($day - 1), $days - 1) : 0);
        $pace = fn (int $day): int => intdiv($allocated * $day, $days);

        $ticks = [];
        for ($i = 0; $i <= $count; $i++) {
            $value = $step * $i;
            $ticks[] = ['y' => self::px($y($value)), 'text_y' => self::px($y($value) + 40), 'label' => self::axisLabel($value)];
        }

        $xLabels = [];
        foreach ([1, 8, 15, 22, 29] as $day) {
            if ($day <= $days) {
                $xLabels[] = ['x' => self::px($x($day)), 'label' => $day === 1 ? '1 '.self::shortMonth($ledger->month) : (string) $day];
            }
        }

        $points = [];
        $rows = [];
        foreach ($cumulative as $day => $spent) {
            $points[] = self::px($x($day)).','.self::px($y($spent));

            $paceMinor = $pace($day);
            $diff = $spent - $paceMinor;
            $diffPercent = $paceMinor > 0 ? intdiv(abs($diff) * 100, $paceMinor) : 0;
            $rows[] = [
                'day' => $day,
                'label' => $day.' '.self::shortMonth($ledger->month),
                'x' => self::px($x($day)),
                'cy' => self::px($y($spent)),
                'py' => self::px($y($paceMinor)),
                'spent' => Rupiah::format($spent),
                'pace' => Rupiah::format($paceMinor),
                'diff' => match (true) {
                    $diff > 0 => Rupiah::format($diff)." ({$diffPercent}%) di atas laju jatah",
                    $diff < 0 => Rupiah::format(-$diff)." ({$diffPercent}%) di bawah laju jatah",
                    default => 'tepat di laju jatah',
                },
            ];
        }

        $lastSpent = $cumulative[$last];
        $lastPace = $pace($last);
        $lastDiff = $lastSpent - $lastPace;
        $lastPercent = $lastPace > 0 ? intdiv(abs($lastDiff) * 100, $lastPace) : 0;
        $aria = 'Grafik pengeluaran kumulatif '.$this->room->name.' '.Period::label($ledger->month).'. '
            ."Hari ke-{$last} terpakai ".Rupiah::compact($lastSpent).', '
            .($lastDiff === 0 ? 'tepat di laju jatah ' : "sekitar {$lastPercent} persen ".($lastDiff > 0 ? 'di atas' : 'di bawah').' laju jatah ')
            .Rupiah::compact($lastPace).'. Jatah bulan ini '.Rupiah::compact($allocated).'.';

        return [
            'reason' => null,
            'ticks' => $ticks,
            'x_labels' => $xLabels,
            'left' => self::CHART_LEFT,
            'right' => self::CHART_RIGHT,
            'top' => self::CHART_TOP,
            'bottom' => self::CHART_BOTTOM,
            'allocated_y' => self::px($y($allocated)),
            'allocated_label' => 'Jatah '.Rupiah::compact($allocated),
            'allocated_label_y' => self::px($y($allocated) - 80),
            'pace_label_y' => self::px($y($allocated) + 520),
            'pace_from' => self::px($x(1)).','.self::px($y($pace(1))),
            'pace_to' => self::px($x($days)).','.self::px($y($allocated)),
            'line' => implode(' ', $points),
            'area' => implode(' ', $points).' '.self::px($x($last)).','.self::CHART_BOTTOM.' '.self::px($x(1)).','.self::CHART_BOTTOM,
            'rows' => $rows,
            'last' => $last,
            'last_row' => $rows[$last - 1],
            'aria' => $aria,
        ];
    }

    /**
     * Riwayat enam bulan sampai bulan yang dibuka: batang terpakai dan penanda jatah per bulan.
     *
     * @return array{bars: list<array<string, mixed>>, summary: string}
     */
    private function history(CarbonInterface $month): array
    {
        $first = $month->copy()->startOfMonth()->subMonthsNoOverflow(self::HISTORY_MONTHS - 1);
        $entries = [];
        foreach (MonthLedger::months(auth()->user(), $first, $month) as $ledger) {
            $roomMonth = $ledger->room($this->room);
            if ($roomMonth) {
                $entries[] = [$ledger, $roomMonth];
            }
        }

        $max = 1;
        foreach ($entries as [, $roomMonth]) {
            $max = max($max, $roomMonth->spentMinor, $roomMonth->allocatedMinor);
        }

        $bars = [];
        $over = [];
        $running = null;
        foreach ($entries as [$ledger, $roomMonth]) {
            $percent = $roomMonth->percent();
            $isRunning = $ledger->isCurrentMonth();
            $name = explode(' ', Period::label($ledger->month))[0];

            $bars[] = [
                'name' => self::shortMonth($ledger->month),
                'full_name' => $name,
                'percent' => $percent,
                'over' => $roomMonth->isOver(),
                'running' => $isRunning,
                'bar_px' => intdiv(self::BAR_MAX_PX * $roomMonth->spentMinor, $max),
                'tick_px' => $roomMonth->allocatedMinor > 0 ? intdiv(self::BAR_MAX_PX * $roomMonth->allocatedMinor, $max) : null,
                'spent' => Rupiah::format($roomMonth->spentMinor),
                'allocated' => Rupiah::format($roomMonth->allocatedMinor),
            ];

            if ($roomMonth->isOver() && ! $isRunning) {
                $over[] = $name.' melewati jatah '.($percent - 100).'%';
            }
            if ($isRunning) {
                $running = ['name' => $name, 'over' => $roomMonth->isOver(), 'excess' => ($percent ?? 100) - 100];
            }
        }

        $sentences = [];
        if ($over !== []) {
            $sentences[] = implode(', ', $over).'.';
        } elseif (collect($bars)->contains(fn (array $bar) => $bar['percent'] !== null)) {
            $sentences[] = 'Tidak ada bulan yang melewati jatah.';
        } else {
            $sentences[] = 'Belum ada jatah di bulan-bulan ini.';
        }
        if ($running) {
            $sentences[] = $running['over']
                ? "{$running['name']} sudah melewati jatah {$running['excess']}% dan masih berjalan (batang pudar)."
                : "{$running['name']} masih berjalan (batang pudar).";
        }

        return ['bars' => $bars, 'summary' => implode(' ', $sentences)];
    }

    public function render(): View
    {
        $user = auth()->user();
        $month = Period::fromQuery($this->bulan);
        $ledger = MonthLedger::for($user, $month);
        // Jatah, terpakai, status, dan pos diturunkan dari transaksi (MonthLedger); ruang pasti milik user (mount).
        $roomMonth = $ledger->room($this->room);

        $transactions = $this->room->transactions()
            ->where('type', Transaction::TYPE_EXPENSE)
            ->where('occurred_on', '>=', $month->toDateString())
            ->where('occurred_on', '<', $month->addMonthNoOverflow()->toDateString())
            ->orderByDesc('occurred_on')
            ->orderByDesc('id')
            ->limit(6)
            ->get();

        return view('livewire.ruang', [
            'room' => $this->room,
            'month' => $month,
            'ledger' => $ledger,
            'roomMonth' => $roomMonth,
            'categories' => $roomMonth->categoryBreakdown(),
            'chart' => $this->paceChart($roomMonth, $ledger),
            'history' => $this->history($month),
            'transactions' => $transactions,
            'role' => $this->room->role,
            'today' => Carbon::today(),
            'canUseRoles' => $user->entitlements()->isEnabled(Feature::RoleSystems),
            'accounts' => $user->accounts()->where('archived', false)->orderBy('sort_order')->get(),
            'roomCategories' => $this->room->categories()->where('archived', false)->orderBy('sort_order')->get(),
        ])->layout('components.layout', ['title' => $this->room->name.' — Rizqflow', 'period' => $month]);
    }
}
