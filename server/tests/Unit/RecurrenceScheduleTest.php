<?php

namespace Tests\Unit;

use App\Support\RecurrenceSchedule;
use Illuminate\Support\Carbon;
use PHPUnit\Framework\TestCase;

/**
 * Porting langsung dari RecurrenceScheduleTest di :domain (lihat
 * domain/src/test/kotlin/.../recurring/RecurringTest.kt) — logika tanggalnya murni dan sama
 * persis, jadi kasus ujinya juga sama persis.
 */
class RecurrenceScheduleTest extends TestCase
{
    private function d(int $y, int $m, int $day): Carbon
    {
        return Carbon::create($y, $m, $day);
    }

    /** @return list<Carbon> */
    private function sequence(Carbon $start, string $frequency, int $count): array
    {
        $out = [$start];
        for ($i = 1; $i < $count; $i++) {
            $out[] = RecurrenceSchedule::next($start, $frequency, $out[count($out) - 1]);
        }

        return $out;
    }

    private function assertDatesEqual(array $expected, array $actual): void
    {
        $this->assertSame(
            array_map(fn (Carbon $c) => $c->toDateString(), $expected),
            array_map(fn (Carbon $c) => $c->toDateString(), $actual),
        );
    }

    public function test_harian_maju_satu_hari(): void
    {
        $this->assertDatesEqual(
            [$this->d(2026, 9, 29), $this->d(2026, 9, 30), $this->d(2026, 10, 1)],
            $this->sequence($this->d(2026, 9, 29), 'harian', 3),
        );
    }

    public function test_mingguan_maju_tujuh_hari_dan_melewati_pergantian_bulan(): void
    {
        $this->assertDatesEqual(
            [$this->d(2026, 9, 24), $this->d(2026, 10, 1), $this->d(2026, 10, 8)],
            $this->sequence($this->d(2026, 9, 24), 'mingguan', 3),
        );
    }

    public function test_bulanan_yang_mulai_tanggal_31_jatuh_di_hari_terakhir_bulan_pendek_lalu_kembali_ke_31(): void
    {
        $this->assertDatesEqual(
            [$this->d(2026, 1, 31), $this->d(2026, 2, 28), $this->d(2026, 3, 31), $this->d(2026, 4, 30), $this->d(2026, 5, 31)],
            $this->sequence($this->d(2026, 1, 31), 'bulanan', 5),
        );
    }

    public function test_bulanan_yang_mulai_tanggal_29_februari_kabisat_kembali_ke_28_lalu_29_tahun_kabisat_berikutnya(): void
    {
        $seq = $this->sequence($this->d(2028, 2, 29), 'bulanan', 13);
        $this->assertSame('2028-03-29', $seq[1]->toDateString());
        $this->assertSame('2029-02-28', $seq[12]->toDateString());
    }

    public function test_bulanan_melewati_pergantian_tahun(): void
    {
        $this->assertDatesEqual(
            [$this->d(2026, 11, 15), $this->d(2026, 12, 15), $this->d(2027, 1, 15)],
            $this->sequence($this->d(2026, 11, 15), 'bulanan', 3),
        );
    }

    public function test_kemunculan_berikutnya_dari_tanggal_di_antara_dua_kemunculan_bulanan(): void
    {
        $start = $this->d(2026, 1, 15);
        $this->assertSame('2026-09-15', RecurrenceSchedule::next($start, 'bulanan', $this->d(2026, 8, 20))->toDateString());
        $this->assertSame('2026-10-15', RecurrenceSchedule::next($start, 'bulanan', $this->d(2026, 9, 15))->toDateString());
        $this->assertSame('2026-09-15', RecurrenceSchedule::next($start, 'bulanan', $this->d(2026, 9, 14))->toDateString());
    }

    public function test_kemunculan_berikutnya_dari_tanggal_di_antara_dua_kemunculan_mingguan(): void
    {
        $start = $this->d(2026, 9, 1);
        $this->assertSame('2026-09-08', RecurrenceSchedule::next($start, 'mingguan', $this->d(2026, 9, 7))->toDateString());
        $this->assertSame('2026-09-15', RecurrenceSchedule::next($start, 'mingguan', $this->d(2026, 9, 8))->toDateString());
    }

    public function test_sebelum_tanggal_awal_kemunculan_pertamanya_adalah_tanggal_awal_itu_sendiri(): void
    {
        foreach (['harian', 'mingguan', 'bulanan'] as $f) {
            $this->assertSame('2026-09-24', RecurrenceSchedule::next($this->d(2026, 9, 24), $f, $this->d(2026, 1, 1))->toDateString(), $f);
            $this->assertSame('2026-09-24', RecurrenceSchedule::firstOnOrAfter($this->d(2026, 9, 24), $f, $this->d(2026, 9, 24))->toDateString(), $f);
        }
    }

    public function test_first_on_or_after_mengembalikan_tanggal_itu_sendiri_bila_tepat_kemunculan(): void
    {
        $start = $this->d(2026, 1, 15);
        $this->assertSame('2026-09-15', RecurrenceSchedule::firstOnOrAfter($start, 'bulanan', $this->d(2026, 9, 15))->toDateString());
        $this->assertSame('2026-10-15', RecurrenceSchedule::firstOnOrAfter($start, 'bulanan', $this->d(2026, 9, 16))->toDateString());
    }
}
