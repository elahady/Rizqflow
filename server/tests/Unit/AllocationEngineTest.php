<?php

namespace Tests\Unit;

use App\Support\AllocationEngine;
use PHPUnit\Framework\TestCase;

/**
 * Porting dari kasus uji waterfall di AllocationEngineTest.kt di :domain. Validasi ruang ganda dan
 * batas atas negatif tidak diulang di sini — di web itu ditangani oleh validasi form
 * AturanAlokasi, bukan oleh kelas kalkulator murni ini.
 */
class AllocationEngineTest extends TestCase
{
    private function amounts(array $result): array
    {
        return array_column($result['shares'], 'amount_minor');
    }

    public function test_waterfall_fills_in_order_up_to_the_cap_then_the_next_room(): void
    {
        $result = AllocationEngine::allocateWaterfall(1_000_000, [
            ['room_id' => 1, 'cap_minor' => 300_000],
            ['room_id' => 2, 'cap_minor' => 500_000],
            ['room_id' => 3, 'cap_minor' => null],
        ]);

        $this->assertSame([300_000, 500_000, 200_000], $this->amounts($result));
        $this->assertSame(0, $result['unallocated_minor']);
    }

    public function test_waterfall_stops_when_the_amount_runs_out_and_later_rooms_get_zero(): void
    {
        $result = AllocationEngine::allocateWaterfall(300_000, [
            ['room_id' => 1, 'cap_minor' => 300_000],
            ['room_id' => 2, 'cap_minor' => 500_000],
            ['room_id' => 3, 'cap_minor' => null],
        ]);

        $this->assertSame([300_000, 0, 0], $this->amounts($result));
        $this->assertSame(0, $result['unallocated_minor']);
    }

    public function test_waterfall_without_an_unlimited_room_leaves_an_unallocated_remainder(): void
    {
        $result = AllocationEngine::allocateWaterfall(1_000_000, [
            ['room_id' => 1, 'cap_minor' => 300_000],
            ['room_id' => 2, 'cap_minor' => 500_000],
        ]);

        $this->assertSame([300_000, 500_000], $this->amounts($result));
        $this->assertSame(200_000, $result['unallocated_minor']);
    }

    public function test_waterfall_with_no_caps_at_all_the_first_room_takes_everything(): void
    {
        $result = AllocationEngine::allocateWaterfall(750_000, [
            ['room_id' => 1, 'cap_minor' => null],
            ['room_id' => 2, 'cap_minor' => null],
        ]);

        $this->assertSame([750_000, 0], $this->amounts($result));
        $this->assertSame(0, $result['unallocated_minor']);
    }
}
