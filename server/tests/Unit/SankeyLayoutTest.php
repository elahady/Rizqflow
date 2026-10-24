<?php

namespace Tests\Unit;

use App\Support\SankeyLayout;
use PHPUnit\Framework\TestCase;

class SankeyLayoutTest extends TestCase
{
    /** Pola mockup: gaji 18 jt dibagi 10/30/60 ke tiga ruang. */
    private function mockupInput(array $overrides = []): array
    {
        return array_replace_recursive([
            'sources' => [['key' => 'gaji', 'label' => 'Gaji Oktober', 'amount' => 18_000_000]],
            'rooms' => [
                ['key' => 'r1', 'label' => 'Memberi', 'slot' => 1, 'pos' => [
                    ['key' => 'p1', 'label' => 'Sedekah dan infak', 'amount' => 1_300_000],
                    ['key' => 'p2', 'label' => 'Orang tua', 'amount' => 500_000],
                ]],
                ['key' => 'r2', 'label' => 'Diri', 'slot' => 2, 'pos' => [
                    ['key' => 'p3', 'label' => 'Investasi', 'amount' => 1_000_000],
                    ['key' => 'p4', 'label' => 'Jajan dan hobi', 'amount' => 1_092_000],
                ]],
                ['key' => 'r3', 'label' => 'Keluarga', 'slot' => 3, 'pos' => [
                    ['key' => 'p5', 'label' => 'Dapur dan belanja', 'amount' => 4_320_000],
                    ['key' => 'p6', 'label' => 'Tak terlacak', 'amount' => 864_000],
                ]],
            ],
            'links' => [
                ['source' => 'gaji', 'room' => 'r1', 'amount' => 1_800_000],
                ['source' => 'gaji', 'room' => 'r2', 'amount' => 5_400_000],
                ['source' => 'gaji', 'room' => 'r3', 'amount' => 10_800_000],
            ],
        ], $overrides);
    }

    public function test_room_totals_follow_links_and_positions(): void
    {
        $layout = SankeyLayout::build($this->mockupInput());

        $this->assertFalse($layout['empty']);
        $this->assertSame(18_000_000, $layout['total']);
        $this->assertSame(18_000_000, $layout['allocated']);
        $this->assertNull($layout['unallocated']);

        [$memberi, $diri, $keluarga] = $layout['rooms'];
        $this->assertSame([1_800_000, 1_800_000, 0, 0], [$memberi['allocated'], $memberi['spent'], $memberi['remaining'], $memberi['over']]);
        $this->assertSame([5_400_000, 2_092_000, 3_308_000, 0], [$diri['allocated'], $diri['spent'], $diri['remaining'], $diri['over']]);
        $this->assertSame([10_800_000, 5_184_000, 5_616_000, 0], [$keluarga['allocated'], $keluarga['spent'], $keluarga['remaining'], $keluarga['over']]);

        // Pita "sisa belum terpakai" hanya ada bila ada sisa.
        $this->assertNull($memberi['sisa']);
        $this->assertSame(3_308_000, $diri['sisa']['amount']);
        $this->assertSame(8_924_000, $layout['remaining']);
        $this->assertSame(1_800_000 + 2_092_000 + 5_184_000, $layout['spent']);
    }

    public function test_node_heights_are_proportional_to_rupiah(): void
    {
        $layout = SankeyLayout::build($this->mockupInput());
        [$memberi, $diri, $keluarga] = $layout['rooms'];

        // Keluarga 10,8 jt vs Diri 5,4 jt: tepat dua kali lipat (di atas tinggi minimum).
        $this->assertEqualsWithDelta($diri['h'] * 2, $keluarga['h'], 0.05);
        $this->assertEqualsWithDelta($memberi['h'] * 3, $diri['h'], 0.1);

        // Satu sumber setinggi seluruh rezeki: jumlah node ruang (tanpa celah) sama dengan tinggi sumber.
        $this->assertEqualsWithDelta($layout['sources'][0]['h'], $memberi['h'] + $diri['h'] + $keluarga['h'], 0.1);
    }

    public function test_pos_column_stacks_without_overlap_inside_the_diagram(): void
    {
        $layout = SankeyLayout::build($this->mockupInput());

        foreach ($layout['rooms'] as $room) {
            $nodes = $room['pos'];
            if ($room['sisa']) {
                $nodes[] = $room['sisa'];
            }
            $prevBottom = null;
            foreach ($nodes as $node) {
                $this->assertGreaterThanOrEqual(0, $node['y']);
                $this->assertLessThanOrEqual($layout['height'] + 0.01, $node['y'] + $node['h']);
                if ($prevBottom !== null) {
                    $this->assertGreaterThanOrEqual($prevBottom + 8 - 0.01, $node['y']);
                }
                $prevBottom = $node['y'] + $node['h'];
            }
        }

        // Grup ruang berikutnya tidak menimpa grup sebelumnya.
        $roomBottoms = array_map(fn (array $r) => $r['y'] + $r['h'], $layout['rooms']);
        foreach ($layout['rooms'] as $i => $room) {
            if ($i > 0) {
                $this->assertGreaterThan($roomBottoms[$i - 1], $room['y']);
            }
        }
    }

    public function test_unallocated_rupiah_flows_to_its_own_node(): void
    {
        $layout = SankeyLayout::build($this->mockupInput([
            'sources' => [['key' => 'gaji', 'amount' => 20_000_000]],
        ]));

        $this->assertSame(20_000_000, $layout['total']);
        $this->assertSame(2_000_000, $layout['unallocated']['amount']);
        $this->assertCount(1, $layout['unallocated']['links_in']);
        $this->assertSame(18_000_000, $layout['allocated']);
    }

    public function test_unallocated_link_can_also_be_given_explicitly(): void
    {
        $layout = SankeyLayout::build([
            'sources' => [['key' => 'a', 'label' => 'A', 'amount' => 1_000_000]],
            'rooms' => [['key' => 'r1', 'label' => 'Diri', 'pos' => [['key' => 'p', 'label' => 'Jajan', 'amount' => 100_000]]]],
            'links' => [
                ['source' => 'a', 'room' => 'r1', 'amount' => 600_000],
                ['source' => 'a', 'room' => SankeyLayout::UNALLOCATED, 'amount' => 400_000],
            ],
        ]);

        $this->assertSame(400_000, $layout['unallocated']['amount']);
        $this->assertSame(600_000, $layout['rooms'][0]['allocated']);
        $this->assertSame(500_000, $layout['rooms'][0]['remaining']);
    }

    public function test_room_that_overspent_keeps_every_rupiah_and_marks_the_excess(): void
    {
        $layout = SankeyLayout::build($this->mockupInput([
            'rooms' => ['2' => ['pos' => [
                ['key' => 'p5', 'label' => 'Dapur dan belanja', 'amount' => 9_000_000],
                ['key' => 'p6', 'label' => 'Tak terlacak', 'amount' => 3_000_000],
            ]]],
        ]));

        $keluarga = $layout['rooms'][2];
        $this->assertSame(10_800_000, $keluarga['allocated']);
        $this->assertSame(12_000_000, $keluarga['spent']);
        $this->assertSame(1_200_000, $keluarga['over']);
        $this->assertSame(0, $keluarga['remaining']);
        $this->assertNull($keluarga['sisa']);
        $this->assertSame(1_200_000, $layout['over']);

        // Node ruang setinggi terpakai (bukan jatah), dan penanda berada di dalam node-nya.
        $this->assertGreaterThan($layout['rooms'][1]['h'] * 2, $keluarga['h']);
        $this->assertNotNull($keluarga['over_y']);
        $this->assertGreaterThanOrEqual($keluarga['y'], $keluarga['over_y']);
        $this->assertLessThanOrEqual($keluarga['y'] + $keluarga['h'] + 0.01, $keluarga['over_y'] + $keluarga['over_h']);

        // Tinggi pos tetap sebanding terpakai: 9 jt vs 3 jt.
        $pos = $keluarga['pos'];
        $this->assertEqualsWithDelta($pos[1]['h'] * 3, $pos[0]['h'], 0.1);
    }

    public function test_spending_in_a_room_without_any_allocation_is_all_excess(): void
    {
        $layout = SankeyLayout::build([
            'sources' => [['key' => 'a', 'label' => 'A', 'amount' => 1_000_000]],
            'rooms' => [
                ['key' => 'r1', 'label' => 'Diri', 'pos' => []],
                ['key' => 'r0', 'label' => 'Tanpa ruang', 'slot' => null, 'pos' => [['key' => 'p', 'label' => 'Jajan', 'amount' => 70_000]]],
            ],
            'links' => [['source' => 'a', 'room' => 'r1', 'amount' => 1_000_000]],
        ]);

        $this->assertCount(2, $layout['rooms']);
        $tanpa = $layout['rooms'][1];
        $this->assertSame(0, $tanpa['allocated']);
        $this->assertSame(70_000, $tanpa['over']);
        $this->assertEqualsWithDelta($tanpa['y'] + $tanpa['h'], $tanpa['over_y'] + $tanpa['over_h'], 0.01);   // penanda di dasar node
    }

    public function test_very_thin_ribbons_get_a_minimum_visible_size_without_changing_the_amounts(): void
    {
        $layout = SankeyLayout::build([
            'sources' => [['key' => 'a', 'label' => 'Gaji', 'amount' => 100_000_000]],
            'rooms' => [['key' => 'r1', 'label' => 'Diri', 'pos' => [
                ['key' => 'besar', 'label' => 'Besar', 'amount' => 90_000_000],
                ['key' => 'kecil', 'label' => 'Kecil', 'amount' => 10_000],
            ]]],
            'links' => [['source' => 'a', 'room' => 'r1', 'amount' => 100_000_000]],
        ]);

        $room = $layout['rooms'][0];
        $kecil = $room['pos'][1];
        $this->assertSame(10_000, $kecil['amount']);                 // angka tidak berubah
        $this->assertEqualsWithDelta(12.0, $kecil['h'], 0.001);       // node dinaikkan ke tinggi minimum
        $this->assertGreaterThan(10.0, $room['pos'][0]['y'] + $room['pos'][0]['h'] - $room['pos'][0]['y']);
        // Tidak menimpa node di atasnya.
        $this->assertGreaterThanOrEqual($room['pos'][0]['y'] + $room['pos'][0]['h'], $kecil['y']);
        $this->assertSame(9_990_000, $room['remaining']);
    }

    public function test_tiny_source_and_room_nodes_get_room_for_their_labels(): void
    {
        $layout = SankeyLayout::build([
            'sources' => [
                ['key' => 'a', 'label' => 'Gaji', 'amount' => 100_000_000],
                ['key' => 'b', 'label' => 'Hadiah', 'amount' => 50_000],
            ],
            'rooms' => [
                ['key' => 'r1', 'label' => 'Diri', 'pos' => []],
                ['key' => 'r2', 'label' => 'Memberi', 'pos' => []],
            ],
            'links' => [
                ['source' => 'a', 'room' => 'r1', 'amount' => 99_950_000],
                ['source' => 'a', 'room' => 'r2', 'amount' => 50_000],
                ['source' => 'b', 'room' => 'r2', 'amount' => 50_000],
            ],
        ]);

        $this->assertGreaterThanOrEqual(38.0, $layout['sources'][1]['h']);
        $this->assertGreaterThanOrEqual(38.0, $layout['rooms'][1]['h']);
        $this->assertGreaterThan($layout['rooms'][0]['y'] + $layout['rooms'][0]['h'], $layout['rooms'][1]['y']);
        $this->assertSame(100_000, $layout['rooms'][1]['allocated']);
    }

    public function test_empty_input_gives_an_empty_layout(): void
    {
        foreach ([[], ['sources' => [], 'rooms' => [], 'links' => []], ['sources' => [['key' => 'a', 'label' => 'A', 'amount' => 0]], 'rooms' => [], 'links' => []]] as $input) {
            $layout = SankeyLayout::build($input);
            $this->assertTrue($layout['empty']);
            $this->assertSame(0, $layout['total']);
            $this->assertSame([], $layout['rooms']);
            $this->assertNull($layout['unallocated']);
        }
    }

    public function test_income_without_rooms_goes_entirely_to_unallocated(): void
    {
        $layout = SankeyLayout::build(['sources' => [['key' => 'a', 'label' => 'A', 'amount' => 5_000_000]], 'rooms' => [], 'links' => []]);

        $this->assertFalse($layout['empty']);
        $this->assertSame([], $layout['rooms']);
        $this->assertSame(5_000_000, $layout['unallocated']['amount']);
    }

    public function test_diagram_height_stays_near_the_target_for_a_normal_month(): void
    {
        $layout = SankeyLayout::build($this->mockupInput());

        $this->assertLessThanOrEqual(SankeyLayout::TARGET_HEIGHT, $layout['height']);
        $this->assertGreaterThan(SankeyLayout::TARGET_HEIGHT * 0.9, $layout['height']);
    }

    public function test_many_small_nodes_grow_the_diagram_instead_of_overlapping(): void
    {
        $pos = [];
        for ($i = 1; $i <= 40; $i++) {
            $pos[] = ['key' => "p$i", 'label' => "Pos $i", 'amount' => 10_000];
        }
        $layout = SankeyLayout::build([
            'sources' => [['key' => 'a', 'label' => 'Gaji', 'amount' => 10_000_000]],
            'rooms' => [['key' => 'r1', 'label' => 'Diri', 'pos' => $pos]],
            'links' => [['source' => 'a', 'room' => 'r1', 'amount' => 10_000_000]],
        ]);

        $nodes = $layout['rooms'][0]['pos'];
        for ($i = 1; $i < count($nodes); $i++) {
            $this->assertGreaterThanOrEqual($nodes[$i - 1]['y'] + $nodes[$i - 1]['h'], $nodes[$i]['y']);
        }
        $last = end($nodes);
        $this->assertLessThanOrEqual($layout['height'] + 0.01, $last['y'] + $last['h']);
    }

    public function test_links_are_clamped_to_their_source_amount(): void
    {
        // Pita lebih besar dari nilai sumber: sumber naik mengikuti pitanya, tidak ada angka yang hilang.
        $layout = SankeyLayout::build([
            'sources' => [['key' => 'a', 'label' => 'A', 'amount' => 1_000_000]],
            'rooms' => [['key' => 'r1', 'label' => 'Diri', 'pos' => []]],
            'links' => [['source' => 'a', 'room' => 'r1', 'amount' => 1_500_000]],
        ]);

        $this->assertSame(1_500_000, $layout['total']);
        $this->assertSame(1_500_000, $layout['sources'][0]['amount']);
        $this->assertNull($layout['unallocated']);
    }

    public function test_collapse_keeps_the_largest_and_merges_the_rest(): void
    {
        $items = [];
        foreach ([5, 90, 40, 10, 70, 30, 20] as $i => $amount) {
            $items[] = ['key' => "s$i", 'label' => "S$i", 'amount' => $amount];
        }

        [$shown, $map] = SankeyLayout::collapse($items, 4, 'lainnya', 'Lainnya');

        $this->assertSame(['s1', 's4', 's2', 'lainnya'], array_column($shown, 'key'));
        $this->assertSame(5 + 10 + 30 + 20, $shown[3]['amount']);
        $this->assertEquals(['s0' => 'lainnya', 's3' => 'lainnya', 's5' => 'lainnya', 's6' => 'lainnya'], $map);
        $this->assertSame(array_sum(array_column($items, 'amount')), array_sum(array_column($shown, 'amount')));

        [$same, $noMap] = SankeyLayout::collapse(array_slice($items, 0, 3), 4, 'lainnya', 'Lainnya');
        $this->assertCount(3, $same);
        $this->assertSame([], $noMap);
    }

    public function test_percent_helpers_round_with_integers(): void
    {
        $this->assertSame(72, SankeyLayout::percentTenths(1_300_000, 18_000_000));      // 7,2%
        $this->assertSame(1_000, SankeyLayout::percentTenths(18_000_000, 18_000_000));
        $this->assertSame(1, SankeyLayout::percentTenths(1, 1_000));                    // 0,1% (setengah ke atas)
        $this->assertNull(SankeyLayout::percentTenths(5, 0));
        $this->assertSame(7, SankeyLayout::percentWhole(1_300_000, 18_000_000));
        $this->assertSame(8, SankeyLayout::percentWhole(1_440_000, 18_000_000));        // 8,0
        $this->assertSame(99, SankeyLayout::percentFloor(996, 1_000));                  // tidak menyebut 100% sebelum penuh
        $this->assertSame('7,2%', SankeyLayout::formatTenths(72));
        $this->assertSame('100,0%', SankeyLayout::formatTenths(1_000));
        $this->assertSame('—', SankeyLayout::formatTenths(null));
    }
}
