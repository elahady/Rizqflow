<?php

namespace App\Support;

use App\Models\Category;
use App\Models\Room;
use Carbon\CarbonImmutable;
use Illuminate\Support\Collection;

/**
 * Satu ruang pada satu bulan, diturunkan dari transaksi dan potret alokasi (porting RoomCard dan
 * RoomStatusRules di domain/.../ledger/Denah.kt, plus RoomDetailLoader untuk pos). Dibuat oleh
 * MonthLedger, jangan dibuat sendiri. Tidak untuk disimpan di properti publik Livewire (tidak bisa
 * diserialisasi): hitung ulang di render().
 *
 * Status selalu ditampilkan sebagai ikon plus teks (statusLabel), bukan warna saja (statusTone).
 */
class RoomMonth
{
    public const STATUS_MENUNGGU = 'menunggu';

    public const STATUS_BERJALAN = 'berjalan';

    public const STATUS_TERPENUHI = 'terpenuhi';

    public const STATUS_PERLU_PERHATIAN = 'perlu_perhatian';

    public const STATUS_BELUM_TERCAPAI = 'belum_tercapai';

    /** Ambang perlu perhatian tipe Mencukupi, inklusif (85%, dikonfirmasi 2026-09-20). */
    public const WARN_AT_BP = 8_500;

    /** Persentase terpakai dijenuhkan di sini (Int.MAX_VALUE, sama seperti Kotlin). */
    public const PROGRESS_CAP_BP = 2_147_483_647;

    private const LABELS = [
        self::STATUS_MENUNGGU => 'Menunggu rezeki',
        self::STATUS_BERJALAN => 'Berjalan',
        self::STATUS_TERPENUHI => 'Terpenuhi',
        self::STATUS_PERLU_PERHATIAN => 'Perlu perhatian',
        self::STATUS_BELUM_TERCAPAI => 'Belum tercapai',
    ];

    private const TONES = [
        self::STATUS_MENUNGGU => 'info',
        self::STATUS_BERJALAN => 'neutral',
        self::STATUS_TERPENUHI => 'good',
        self::STATUS_PERLU_PERHATIAN => 'warn',
        self::STATUS_BELUM_TERCAPAI => 'neutral',
    ];

    /** Terpakai dibanding jatah dalam basis point, dibulatkan ke bawah; null bila belum ada jatah. Bisa lebih dari 10.000. */
    public readonly ?int $progressBp;

    /** Persentase aturan sekarang (allocation_percent × 100); null bila kosong atau 0. Bukan potret bulan itu. */
    public readonly ?int $shareBp;

    public function __construct(
        public readonly Room $room,
        /** Jatah bulan itu: jumlah potret alokasi pemasukan bulan itu untuk ruang ini. */
        public readonly int $allocatedMinor,
        /** Terpakai bulan itu: jumlah pengeluaran ruang ini di bulan itu. */
        public readonly int $spentMinor,
        private readonly MonthLedger $ledger,
    ) {
        $this->progressBp = self::progressBpOf($allocatedMinor, $spentMinor);
        $percent = (int) $room->allocation_percent;
        $this->shareBp = $percent > 0 ? $percent * 100 : null;
    }

    /** RoomStatusRules.progressBp: terpakai × 10.000 / jatah, dibulatkan ke bawah, tanpa luapan. */
    public static function progressBpOf(int $allocatedMinor, int $spentMinor): ?int
    {
        if ($allocatedMinor <= 0) {
            return null;
        }

        return min(AllocationEngine::mulDivFloor(max($spentMinor, 0), AllocationEngine::FULL_BP, $allocatedMinor), self::PROGRESS_CAP_BP);
    }

    /**
     * RoomStatusRules.statusOf (arti "terpenuhi" per tipe, docs/konsep.md):
     * - Menunaikan dan Menumbuhkan: terpenuhi saat terpakai mencapai jatah; selebihnya berjalan, atau
     *   belum tercapai (netral, bukan peringatan) bila bulannya sudah berakhir.
     * - Mencukupi: perlu perhatian mulai 85% jatah (inklusif, termasuk melewati jatah). Setelah
     *   bulannya berakhir, terpenuhi bila terpakai tidak melebihi jatah (dibandingkan sebagai rupiah).
     * Bulan tanpa jatah selalu menunggu.
     */
    public static function statusOf(string $type, int $allocatedMinor, int $spentMinor, bool $monthOver): string
    {
        $progress = self::progressBpOf($allocatedMinor, $spentMinor);
        if ($progress === null) {
            return self::STATUS_MENUNGGU;
        }

        if ($type === Room::TYPE_MENCUKUPI) {
            if ($monthOver) {
                return $spentMinor <= $allocatedMinor ? self::STATUS_TERPENUHI : self::STATUS_PERLU_PERHATIAN;
            }

            return $progress >= self::WARN_AT_BP ? self::STATUS_PERLU_PERHATIAN : self::STATUS_BERJALAN;
        }

        if ($progress >= AllocationEngine::FULL_BP) {
            return self::STATUS_TERPENUHI;
        }

        return $monthOver ? self::STATUS_BELUM_TERCAPAI : self::STATUS_BERJALAN;
    }

    /** Teks pil untuk status mana pun (mis. riwayat bulan lain). */
    public static function labelFor(string $status): string
    {
        return self::LABELS[$status] ?? $status;
    }

    /** Nada pil rf-pill untuk status mana pun: good, warn, neutral, atau info. */
    public static function toneFor(string $status): string
    {
        return self::TONES[$status] ?? 'neutral';
    }

    public function month(): CarbonImmutable
    {
        return $this->ledger->month;
    }

    public function ledger(): MonthLedger
    {
        return $this->ledger;
    }

    /** Persen terpakai dari jatah, dibulatkan ke bawah; null bila belum ada jatah. Bisa lebih dari 100. */
    public function percent(): ?int
    {
        return $this->progressBp === null ? null : intdiv($this->progressBp, 100);
    }

    /** Jatah dikurangi terpakai; negatif bila terpakai melewati jatah. */
    public function remainingMinor(): int
    {
        return $this->allocatedMinor - $this->spentMinor;
    }

    public function isOver(): bool
    {
        return $this->allocatedMinor > 0 && $this->spentMinor > $this->allocatedMinor;
    }

    /** 'menunggu' | 'berjalan' | 'terpenuhi' | 'perlu_perhatian' | 'belum_tercapai' */
    public function status(): string
    {
        return self::statusOf((string) $this->room->type, $this->allocatedMinor, $this->spentMinor, $this->ledger->isPastMonth());
    }

    /** "Menunggu rezeki", "Berjalan", "Terpenuhi", "Perlu perhatian", atau "Belum tercapai". */
    public function statusLabel(): string
    {
        return self::labelFor($this->status());
    }

    /** 'good' | 'warn' | 'neutral' | 'info' untuk kelas rf-pill. */
    public function statusTone(): string
    {
        return self::toneFor($this->status());
    }

    /**
     * "Aman ≈ Rp X per hari": sisa jatah (tidak pernah negatif) dibagi sisa hari bulan ini termasuk
     * hari ini, dibulatkan ke bawah. Null bila bukan bulan berjalan atau ruang belum punya jatah.
     * Beda dengan MonthLedger::safeToSpend (Sisa aman hari ini), yang memisahkan pengeluaran hari ini.
     */
    public function safePerDayMinor(): ?int
    {
        if (! $this->ledger->isCurrentMonth() || $this->allocatedMinor <= 0) {
            return null;
        }

        return intdiv(max($this->remainingMinor(), 0), $this->ledger->daysLeft());
    }

    /**
     * Laju belanja dibanding laju waktu (bulan berjalan): true bila persen terpakai di atas persen
     * hari yang sudah lewat ("laju di atas waktu"). Null bila bukan bulan berjalan atau belum ada jatah.
     */
    public function isAheadOfTime(): ?bool
    {
        if (! $this->ledger->isCurrentMonth() || $this->progressBp === null) {
            return null;
        }

        return $this->progressBp > $this->ledger->timeProgressBp();
    }

    /** Pengeluaran ruang ini pada hari ini (0 bila bukan bulan berjalan). */
    public function spentTodayMinor(): int
    {
        return $this->ledger->isCurrentMonth()
            ? ($this->ledger->roomSpentByDay($this->room->id)[$this->ledger->today->day] ?? 0)
            : 0;
    }

    /**
     * Pengeluaran per tanggal bulan itu, untuk grafik laju belanja.
     *
     * @return array<int, int> tanggal (1..jumlah hari) => rupiah, termasuk hari tanpa pengeluaran (0)
     */
    public function spentByDay(): array
    {
        $byDay = $this->ledger->roomSpentByDay($this->room->id);
        $days = [];
        for ($day = 1; $day <= $this->ledger->daysInMonth(); $day++) {
            $days[$day] = $byDay[$day] ?? 0;
        }

        return $days;
    }

    /**
     * Pos-pos ruang ini pada bulan itu (RoomDetailLoader di :domain). Pembanding tiap pos adalah irisan
     * jatah ruang menurut bobot bila pos punya bobot (bobot pos aktif dijumlahkan), kalau tidak seluruh
     * jatah ruang ("kosong = ikut jatah ruang"). Pos aktif selalu tampil; pos terarsip dan pos sistem
     * hanya bila bulan itu memakainya. Urut dari pengeluaran terbesar, lalu urutan pos.
     *
     * @return Collection<int, array{category: Category, spent_minor: int, budget_minor: int, progress_bp: ?int, percent: ?int, count: int}>
     *   `percent` dibulatkan ke bawah dan bisa lebih dari 100; null bila jatah pos 0. `count` = jumlah
     *   transaksi pos itu bulan itu (mis. "Lihat 3 transaksi" untuk Tak terlacak).
     */
    public function categoryBreakdown(): Collection
    {
        $spentBy = $this->ledger->roomSpentByCategory($this->room->id);
        $own = $this->ledger->categoriesOf($this->room->id);
        $weightTotal = (int) $own->where('archived', false)->sum(fn (Category $c) => $c->weight ?? 0);

        return $own
            ->filter(fn (Category $c) => (! $c->archived && ! $c->is_system) || ($spentBy[$c->id]['total'] ?? 0) > 0)
            ->map(function (Category $c) use ($spentBy, $weightTotal) {
                $spent = $spentBy[$c->id]['total'] ?? 0;
                $budget = $c->weight !== null && $weightTotal > 0
                    ? AllocationEngine::mulDivFloor($this->allocatedMinor, $c->weight, $weightTotal)
                    : $this->allocatedMinor;
                $progress = self::progressBpOf($budget, $spent);

                return [
                    'category' => $c,
                    'spent_minor' => $spent,
                    'budget_minor' => $budget,
                    'progress_bp' => $progress,
                    'percent' => $progress === null ? null : intdiv($progress, 100),
                    'count' => $spentBy[$c->id]['count'] ?? 0,
                ];
            })
            ->sort(fn (array $a, array $b) => [$b['spent_minor'], $a['category']->sort_order, $a['category']->id]
                <=> [$a['spent_minor'], $b['category']->sort_order, $b['category']->id])
            ->values();
    }
}
