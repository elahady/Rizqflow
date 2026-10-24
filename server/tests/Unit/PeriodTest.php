<?php

namespace Tests\Unit;

use App\Support\Period;
use Carbon\Carbon;
use Carbon\CarbonImmutable;
use PHPUnit\Framework\TestCase;

/** Periode bulanan untuk pemilih periode di bilah atas (?bulan=Y-m). "Hari ini" dipatok 24 Okt 2026. */
class PeriodTest extends TestCase
{
    protected function setUp(): void
    {
        parent::setUp();
        CarbonImmutable::setTestNow(CarbonImmutable::create(2026, 10, 24, 15, 30));
    }

    protected function tearDown(): void
    {
        CarbonImmutable::setTestNow();
        parent::tearDown();
    }

    public function test_from_query_reads_a_valid_month_as_the_start_of_that_month(): void
    {
        $month = Period::fromQuery('2026-08');

        $this->assertSame('2026-08-01 00:00:00', $month->toDateTimeString());
        $this->assertSame('2026-02-01', Period::fromQuery(' 2026-02 ')->toDateString());
    }

    public function test_from_query_falls_back_to_the_current_month_for_empty_or_invalid_values(): void
    {
        foreach ([null, '', 'abc', '2026-13', '2026-00', '2026-8', '26-08', '2026-08-01', '2026-08x', '+2026-08'] as $value) {
            $this->assertSame('2026-10-01 00:00:00', Period::fromQuery($value)->toDateTimeString(), var_export($value, true));
        }
    }

    public function test_from_query_is_clamped_between_2000_and_twelve_months_ahead(): void
    {
        $this->assertSame('2000-01-01', Period::fromQuery('1999-12')->toDateString());
        $this->assertSame('2000-01-01', Period::fromQuery('2000-01')->toDateString());
        $this->assertSame('2027-10-01', Period::fromQuery('2027-10')->toDateString());
        $this->assertSame('2027-10-01', Period::fromQuery('2031-01')->toDateString());
    }

    public function test_label_is_indonesian_regardless_of_the_instance_locale(): void
    {
        $this->assertSame('Oktober 2026', Period::label(CarbonImmutable::create(2026, 10, 1)));
        $this->assertSame('Mei 2026', Period::label(Carbon::create(2026, 5, 17)->locale('en')));
        $this->assertSame('Agustus 2025', Period::label(CarbonImmutable::create(2025, 8, 31)));
    }

    public function test_label_does_not_change_the_locale_of_a_mutable_instance(): void
    {
        $month = Carbon::create(2026, 10, 1)->locale('en');

        Period::label($month);

        $this->assertSame('en', $month->locale());
    }

    public function test_query_formats_year_and_month(): void
    {
        $this->assertSame('2026-10', Period::query(CarbonImmutable::create(2026, 10, 24)));
        $this->assertSame('2000-01', Period::query(Carbon::create(2000, 1, 1)));
    }

    public function test_is_current_and_is_past_compare_with_today(): void
    {
        $this->assertTrue(Period::isCurrent(CarbonImmutable::create(2026, 10, 1)));
        $this->assertTrue(Period::isCurrent(CarbonImmutable::create(2026, 10, 31)));
        $this->assertFalse(Period::isCurrent(CarbonImmutable::create(2026, 9, 30)));
        $this->assertFalse(Period::isCurrent(CarbonImmutable::create(2025, 10, 1)));

        $this->assertTrue(Period::isPast(CarbonImmutable::create(2026, 9, 1)));
        $this->assertTrue(Period::isPast(CarbonImmutable::create(2025, 12, 1)));
        $this->assertFalse(Period::isPast(CarbonImmutable::create(2026, 10, 1)));
        $this->assertFalse(Period::isPast(CarbonImmutable::create(2026, 11, 1)));
    }

    public function test_previous_and_next_do_not_overflow_month_ends(): void
    {
        $this->assertSame('2026-02-01', Period::previous(CarbonImmutable::create(2026, 3, 31))->toDateString());
        $this->assertSame('2026-02-01', Period::next(CarbonImmutable::create(2026, 1, 31))->toDateString());
        $this->assertSame('2027-01-01', Period::next(CarbonImmutable::create(2026, 12, 1))->toDateString());
    }

    public function test_previous_does_not_mutate_a_mutable_instance(): void
    {
        $month = Carbon::create(2026, 10, 1);

        Period::previous($month);
        Period::next($month);

        $this->assertSame('2026-10-01', $month->toDateString());
    }

    public function test_bounds_cover_the_whole_last_day(): void
    {
        [$start, $end] = Period::bounds(CarbonImmutable::create(2026, 2, 14, 9));

        $this->assertSame('2026-02-01 00:00:00', $start->toDateTimeString());
        $this->assertSame('2026-02-28 23:59:59', $end->toDateTimeString());
    }
}
