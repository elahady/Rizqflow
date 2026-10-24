<?php

namespace App\Support;

use Carbon\CarbonImmutable;
use Carbon\CarbonInterface;

/**
 * Periode bulanan untuk pemilih periode di bilah atas (components/layout) dan halaman yang
 * menampilkan data per bulan. Di URL periode ditulis "Y-m" lewat ?bulan=2026-10. Semua hasil
 * berupa CarbonImmutable di awal bulan (tanggal 1, 00:00) memakai zona waktu aplikasi.
 */
class Period
{
    /** Bulan paling awal yang bisa dibuka. */
    public const EARLIEST = '2000-01';

    /** Berapa bulan ke depan dari bulan ini yang masih boleh dibuka (mis. jadwal tagihan). */
    public const MONTHS_AHEAD = 12;

    /**
     * Bulan dari nilai query "Y-m". Kosong atau tidak valid → bulan ini; di luar rentang dibatasi ke
     * 2000-01 sampai bulan ini + 12.
     */
    public static function fromQuery(?string $value): CarbonImmutable
    {
        $current = CarbonImmutable::now()->startOfMonth();

        if ($value === null || ! preg_match('/\A(\d{4})-(0[1-9]|1[0-2])\z/', trim($value), $match)) {
            return $current;
        }

        $month = CarbonImmutable::create((int) $match[1], (int) $match[2], 1)->startOfMonth();
        $earliest = CarbonImmutable::createFromFormat('!Y-m', self::EARLIEST);
        $latest = $current->addMonthsNoOverflow(self::MONTHS_AHEAD);

        return match (true) {
            $month->lessThan($earliest) => $earliest,
            $month->greaterThan($latest) => $latest,
            default => $month,
        };
    }

    /** "Oktober 2026" (nama bulan bahasa Indonesia, tidak tergantung locale aplikasi). */
    public static function label(CarbonInterface $month): string
    {
        return $month->copy()->locale('id')->translatedFormat('F Y');
    }

    /** Nilai untuk query string: "2026-10". */
    public static function query(CarbonInterface $month): string
    {
        return $month->format('Y-m');
    }

    /** Apakah $month adalah bulan berjalan. */
    public static function isCurrent(CarbonInterface $month): bool
    {
        return $month->format('Y-m') === CarbonImmutable::now()->format('Y-m');
    }

    /** Apakah $month sudah lewat (sebelum bulan berjalan). */
    public static function isPast(CarbonInterface $month): bool
    {
        return $month->format('Y-m') < CarbonImmutable::now()->format('Y-m');
    }

    /** Awal bulan sebelumnya. */
    public static function previous(CarbonInterface $month): CarbonImmutable
    {
        return CarbonImmutable::instance($month)->startOfMonth()->subMonthNoOverflow();
    }

    /** Awal bulan berikutnya. */
    public static function next(CarbonInterface $month): CarbonImmutable
    {
        return CarbonImmutable::instance($month)->startOfMonth()->addMonthNoOverflow();
    }

    /**
     * Batas query satu bulan: [awal bulan 00:00:00, akhir bulan 23:59:59.999999]. Batas atas sengaja
     * akhir hari karena occurred_on tersimpan sebagai "Y-m-d 00:00:00" (lihat Room::categoryBreakdown).
     *
     * @return array{0: CarbonImmutable, 1: CarbonImmutable}
     */
    public static function bounds(CarbonInterface $month): array
    {
        $start = CarbonImmutable::instance($month)->startOfMonth();

        return [$start, $start->endOfMonth()];
    }
}
