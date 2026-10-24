<?php

namespace Tests\Unit;

use App\Support\AllocationEngine;
use InvalidArgumentException;
use PHPUnit\Framework\TestCase;

/**
 * Porting AllocationEngineTest.kt di :domain (domain/src/test/.../allocation). Kasus "mata uang
 * masukan dipertahankan" tidak diporting karena web hanya rupiah (tanpa value object Money);
 * kasus "basis point di luar jangkauan" diuji lewat allocate() karena PHP tidak punya kelas BasisPoints.
 */
class AllocationEngineTest extends TestCase
{
    /** @return list<array{room_id: int|string, share_bp: int}> */
    private function rules(array $pairs): array
    {
        $rules = [];
        foreach ($pairs as [$room, $bp]) {
            $rules[] = ['room_id' => $room, 'share_bp' => $bp];
        }

        return $rules;
    }

    private function percentRules(array $pairs): array
    {
        return $this->rules(array_map(fn ($p) => [$p[0], $p[1] * 100], $pairs));
    }

    private function amounts(array $result): array
    {
        return array_column($result['shares'], 'amount_minor');
    }

    // ------------------------------------------------------------------ allocate (persentase)

    public function test_contoh_keputusan_pembulatan_sisa_terbesar(): void
    {
        // Persis: 123.456,7 / 617.283,5 / 493.826,8. Dibulatkan biasa jumlahnya lebih Rp 1.
        $result = AllocationEngine::allocate(1_234_567, $this->percentRules([[1, 10], [2, 50], [3, 40]]));

        $this->assertSame([123_457, 617_283, 493_827], $this->amounts($result));
        $this->assertSame(0, $result['unallocated_minor']);
    }

    public function test_contoh_mockup_10_30_60_dengan_sisa_pembulatan_satu_rupiah(): void
    {
        // Lantai 123.456 / 370.370 / 740.740 (jumlah 1.234.566): sisa Rp 1 ke pecahan terbesar (Memberi, 0,7).
        $result = AllocationEngine::allocate(1_234_567, $this->percentRules([[1, 10], [2, 30], [3, 60]]));

        $this->assertSame([123_457, 370_370, 740_740], $this->amounts($result));
        $this->assertSame(1, $result['rounding_minor']);
        $this->assertSame(0, $result['unallocated_minor']);
        $this->assertSame([1_000, 3_000, 6_000], array_column($result['shares'], 'share_bp'));
    }

    public function test_pembagian_yang_habis_dibagi_tidak_memakai_sisa(): void
    {
        $result = AllocationEngine::allocate(8_500_000, $this->percentRules([[1, 10], [2, 30], [3, 60]]));

        $this->assertSame([850_000, 2_550_000, 5_100_000], $this->amounts($result));
        $this->assertSame(0, $result['unallocated_minor']);
        $this->assertSame(0, $result['rounding_minor']);
    }

    public function test_rezeki_mockup_18_juta(): void
    {
        $result = AllocationEngine::allocate(18_000_000, $this->percentRules([[1, 10], [2, 30], [3, 60]]));

        $this->assertSame([1_800_000, 5_400_000, 10_800_000], $this->amounts($result));
    }

    public function test_hasil_mengikuti_urutan_aturan_dan_membawa_pengenal_ruang(): void
    {
        $result = AllocationEngine::allocate(1_000, $this->rules([['b', 2_000], ['a', 8_000]]));

        $this->assertSame(['b', 'a'], array_column($result['shares'], 'room_id'));
        $this->assertSame([200, 800], $this->amounts($result));
    }

    public function test_sisa_satu_rupiah_jatuh_ke_pecahan_terbesar(): void
    {
        // Rp 100 dengan 33,33% / 33,33% / 33,34%: lantai 33, 33, 33 (99), sisa 1 ke pecahan 0,34.
        $result = AllocationEngine::allocate(100, $this->rules([['a', 3_333], ['b', 3_333], ['c', 3_334]]));

        $this->assertSame([33, 33, 34], $this->amounts($result));
        $this->assertSame(1, $result['rounding_minor']);
    }

    public function test_pecahan_sama_diputus_oleh_urutan_prioritas(): void
    {
        $awalDulu = AllocationEngine::allocate(1, $this->rules([['a', 5_000], ['b', 5_000]]));
        $urutanTerbalik = AllocationEngine::allocate(1, $this->rules([['b', 5_000], ['a', 5_000]]));

        $this->assertSame([1, 0], $this->amounts($awalDulu));
        $this->assertSame('a', $awalDulu['shares'][0]['room_id']);
        $this->assertSame([1, 0], $this->amounts($urutanTerbalik));
        $this->assertSame('b', $urutanTerbalik['shares'][0]['room_id']);
    }

    public function test_dua_sisa_dibagikan_ke_dua_pecahan_terbesar(): void
    {
        // Rp 5 dibagi 33,33% / 33,33% / 33,34%: tepat 1,6665 / 1,6665 / 1,667.
        $result = AllocationEngine::allocate(5, $this->rules([['a', 3_333], ['b', 3_333], ['c', 3_334]]));

        $this->assertSame(5, array_sum($this->amounts($result)));
        $this->assertSame([2, 1, 2], $this->amounts($result));
        $this->assertSame(2, $result['rounding_minor']);
    }

    public function test_total_aturan_di_bawah_100_persen_menyisakan_belum_dialirkan(): void
    {
        $result = AllocationEngine::allocate(1_000_000, $this->percentRules([[1, 10], [2, 30]]));

        $this->assertSame([100_000, 300_000], $this->amounts($result));
        $this->assertSame(600_000, $result['unallocated_minor']);
    }

    public function test_sisa_pembulatan_tidak_mengambil_bagian_yang_belum_dialirkan(): void
    {
        // 9.999 bp dari Rp 100 = 99,99: yang dialirkan Rp 99, sisa Rp 1 tetap belum dialirkan.
        $result = AllocationEngine::allocate(100, $this->rules([['a', 3_333], ['b', 3_333], ['c', 3_333]]));

        $this->assertSame([33, 33, 33], $this->amounts($result));
        $this->assertSame(1, $result['unallocated_minor']);
        $this->assertSame(0, $result['rounding_minor']);
    }

    public function test_tanpa_aturan_seluruh_nominal_belum_dialirkan(): void
    {
        $result = AllocationEngine::allocate(2_000_000, []);

        $this->assertSame([], $result['shares']);
        $this->assertSame(2_000_000, $result['unallocated_minor']);
    }

    public function test_nominal_nol_menghasilkan_bagian_nol(): void
    {
        $result = AllocationEngine::allocate(0, $this->percentRules([['a', 60], ['b', 40]]));

        $this->assertSame([0, 0], $this->amounts($result));
        $this->assertSame(0, $result['unallocated_minor']);
    }

    public function test_satu_rupiah_tetap_habis_terbagi(): void
    {
        $result = AllocationEngine::allocate(1, $this->percentRules([['a', 10], ['b', 30], ['c', 60]]));

        $this->assertSame(1, array_sum($this->amounts($result)));
        $this->assertSame([0, 0, 1], $this->amounts($result));
    }

    public function test_ruang_dengan_nol_persen_tidak_pernah_menerima_sisa(): void
    {
        $result = AllocationEngine::allocate(7, $this->rules([['a', 5_000], ['nol', 0], ['b', 5_000]]));

        $this->assertSame(0, $result['shares'][1]['amount_minor']);
        $this->assertSame(7, array_sum($this->amounts($result)));
    }

    public function test_nominal_sangat_besar_tidak_meluap(): void
    {
        $besar = 900_000_000_000_000;
        $result = AllocationEngine::allocate($besar, $this->rules([['a', 3_333], ['b', 3_333], ['c', 3_334]]));

        $this->assertSame($besar, array_sum($this->amounts($result)));
    }

    public function test_nominal_tepat_di_batas_aman_masih_dihitung_dan_di_atasnya_ditolak(): void
    {
        $this->assertSame(intdiv(PHP_INT_MAX, 10_000), AllocationEngine::MAX_AMOUNT_MINOR);

        $result = AllocationEngine::allocate(AllocationEngine::MAX_AMOUNT_MINOR, $this->rules([['a', 3_333], ['b', 6_667]]));
        $this->assertSame(AllocationEngine::MAX_AMOUNT_MINOR, array_sum($this->amounts($result)));

        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocate(AllocationEngine::MAX_AMOUNT_MINOR + 1, $this->rules([['a', 10_000]]));
    }

    public function test_nominal_negatif_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocate(-1, $this->percentRules([['a', 100]]));
    }

    public function test_ruang_ganda_dalam_aturan_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocate(100, $this->percentRules([['a', 50], ['a', 50]]));
    }

    public function test_total_aturan_lebih_dari_100_persen_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocate(100, $this->percentRules([['a', 60], ['b', 50]]));
    }

    public function test_basis_point_negatif_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocate(100, $this->rules([['a', -1]]));
    }

    public function test_basis_point_di_atas_10000_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocate(100, $this->rules([['a', 10_001]]));
    }

    public function test_persentase_pecahan_tetap_eksak(): void
    {
        // 2,5% dari Rp 1.000.000 tepat Rp 25.000.
        $result = AllocationEngine::allocate(1_000_000, $this->rules([['zakat', 250], ['sisa', 9_750]]));

        $this->assertSame([25_000, 975_000], $this->amounts($result));
    }

    public function test_sifat_umum_pada_dua_ribu_kasus_acak(): void
    {
        mt_srand(20260921);

        for ($n = 0; $n < 2_000; $n++) {
            $count = mt_rand(1, 6);
            $budget = mt_rand(0, AllocationEngine::FULL_BP);
            $rules = [];
            for ($i = 0; $i < $count; $i++) {
                $share = $i === $count - 1 ? $budget : mt_rand(0, $budget);
                $budget -= $share;
                $rules[] = ['room_id' => "r{$i}", 'share_bp' => $share];
            }
            $amount = mt_rand(0, AllocationEngine::MAX_AMOUNT_MINOR);

            $result = AllocationEngine::allocate($amount, $rules);

            // 1. Bagian ditambah belum dialirkan selalu persis nominal.
            $this->assertSame($amount, array_sum($this->amounts($result)) + $result['unallocated_minor']);
            // 2. Tiap bagian hanya boleh lantai atau lantai + 1 dari nilai persisnya.
            foreach ($rules as $i => $rule) {
                $floor = intdiv($amount * $rule['share_bp'], AllocationEngine::FULL_BP);
                $diff = $result['shares'][$i]['amount_minor'] - $floor;
                $this->assertTrue($diff === 0 || $diff === 1, "bagian {$rule['room_id']} menyimpang {$diff} dari lantai");
            }
            // 3. Yang belum dialirkan tidak pernah negatif; sisa pembulatan selalu kurang dari jumlah ruang.
            $this->assertGreaterThanOrEqual(0, $result['unallocated_minor']);
            $this->assertLessThan($count, $result['rounding_minor']);
        }
    }

    // ------------------------------------------------------------------ aturan lanjutan (waterfall)

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

    public function test_waterfall_ruang_ganda_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocateWaterfall(100, [['room_id' => 1, 'cap_minor' => 50], ['room_id' => 1, 'cap_minor' => 50]]);
    }

    public function test_waterfall_batas_atas_negatif_ditolak(): void
    {
        $this->expectException(InvalidArgumentException::class);
        AllocationEngine::allocateWaterfall(100, [['room_id' => 1, 'cap_minor' => -1]]);
    }

    public function test_implied_shares_menghasilkan_kembali_bagian_yang_sama_lewat_allocate(): void
    {
        $amount = 1_000_000;
        $waterfall = AllocationEngine::allocateWaterfall($amount, [
            ['room_id' => 1, 'cap_minor' => 300_000],
            ['room_id' => 2, 'cap_minor' => 500_000],
            ['room_id' => 3, 'cap_minor' => null],
        ]);

        $implied = AllocationEngine::impliedShares($amount, $waterfall['shares']);
        $reapplied = AllocationEngine::allocate($amount, $implied);

        $this->assertSame([3_000, 5_000, 2_000], array_column($implied, 'share_bp'));
        $this->assertSame($this->amounts($waterfall), $this->amounts($reapplied));
    }

    public function test_implied_shares_pada_nominal_nol_tidak_meledak(): void
    {
        $implied = AllocationEngine::impliedShares(0, [['room_id' => 1, 'amount_minor' => 0]]);

        $this->assertSame([['room_id' => 1, 'share_bp' => 0]], $implied);
    }

    public function test_implied_shares_dibulatkan_ke_bawah(): void
    {
        // Rp 1 dan Rp 2 dari Rp 3 = 3.333,3 dan 6.666,7 bp: disimpan 3.333 dan 6.666 (total 9.999).
        $implied = AllocationEngine::impliedShares(3, [['room_id' => 1, 'amount_minor' => 1], ['room_id' => 2, 'amount_minor' => 2]]);

        $this->assertSame([3_333, 6_666], array_column($implied, 'share_bp'));
    }

    // ------------------------------------------------------------------ aritmetika bulat besar

    public function test_mul_div_floor_tanpa_luapan(): void
    {
        $besar = intdiv(PHP_INT_MAX, 2);

        // Padanan tes Kotlin "nominal sangat besar tidak meluap" untuk persentase terpakai.
        $this->assertSame(10_000, AllocationEngine::mulDivFloor($besar, 10_000, $besar));
        $this->assertSame(3_333, AllocationEngine::mulDivFloor(1, 10_000, 3));
        $this->assertSame(0, AllocationEngine::mulDivFloor(0, 10_000, 7));
        $this->assertSame(PHP_INT_MAX, AllocationEngine::mulDivFloor(PHP_INT_MAX, 10_000, 3));
    }

    public function test_mul_div_floor_cocok_dengan_aritmetika_presisi_penuh(): void
    {
        if (! function_exists('bcmul')) {
            $this->markTestSkipped('Ekstensi bcmath tidak tersedia untuk pembanding.');
        }

        mt_srand(424242);
        for ($n = 0; $n < 2_000; $n++) {
            $a = mt_rand(0, PHP_INT_MAX);
            $b = mt_rand(1, PHP_INT_MAX);
            $m = mt_rand(0, 65_535);
            $expected = bcdiv(bcmul((string) $a, (string) $m), (string) $b, 0);
            if (bccomp($expected, (string) PHP_INT_MAX) > 0) {
                $this->assertSame(PHP_INT_MAX, AllocationEngine::mulDivFloor($a, $m, $b));

                continue;
            }
            $this->assertSame((int) $expected, AllocationEngine::mulDivFloor($a, $m, $b), "a={$a} m={$m} b={$b}");
        }
    }
}
