<?php

namespace Tests\Unit;

use App\Support\Rupiah;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** Format rupiah untuk tampilan web; contoh angka mengikuti mockup (docs/design/web-mockup). */
class RupiahTest extends TestCase
{
    private const MINUS = "\u{2212}";

    public function test_format_and_short_keep_their_existing_output(): void
    {
        $this->assertSame('Rp 18.000.000', Rupiah::format(18_000_000));
        $this->assertSame('Rp 0', Rupiah::format(0));
        $this->assertSame('Rp -187.500', Rupiah::format(-187_500));

        $this->assertSame('Rp 1,2 jt', Rupiah::short(1_200_000));
        $this->assertSame('Rp 18 jt', Rupiah::short(18_000_000));
        $this->assertSame('Rp 1,5 M', Rupiah::short(1_500_000_000));
        $this->assertSame('Rp 999.000', Rupiah::short(999_000));
        $this->assertSame('-Rp 2 jt', Rupiah::short(-2_000_000));
    }

    public function test_plain_has_thousand_separators_without_currency(): void
    {
        $this->assertSame('18.000.000', Rupiah::plain(18_000_000));
        $this->assertSame('1.234.567', Rupiah::plain(1_234_567));
        $this->assertSame('0', Rupiah::plain(0));
        $this->assertSame('-25.000', Rupiah::plain(-25_000));
    }

    public function test_signed_takes_the_sign_from_the_transaction_type(): void
    {
        $this->assertSame(self::MINUS.'Rp 187.500', Rupiah::signed(187_500, 'pengeluaran'));
        $this->assertSame('+Rp 18.000.000', Rupiah::signed(18_000_000, 'pemasukan'));
        $this->assertSame('Rp 500.000', Rupiah::signed(500_000, 'transfer'));
        // Tanda hanya dari jenis: nilai negatif tidak menghasilkan dua tanda.
        $this->assertSame(self::MINUS.'Rp 28.000', Rupiah::signed(-28_000, 'pengeluaran'));
        $this->assertSame('Rp 28.000', Rupiah::signed(-28_000, 'transfer'));
    }

    /** @return array<string, array{int, string}> */
    public static function compactCases(): array
    {
        return [
            'juta dua desimal' => [1_092_000, 'Rp 1,09 jt'],
            'juta nol dibuang' => [1_800_000, 'Rp 1,8 jt'],
            'puluhan juta' => [10_800_000, 'Rp 10,8 jt'],
            'juta dibulatkan ke atas' => [2_268_000, 'Rp 2,27 jt'],
            'juta tepat' => [18_000_000, 'Rp 18 jt'],
            'setengah dibulatkan ke atas' => [1_005_000, 'Rp 1,01 jt'],
            'di bawah setengah dibulatkan ke bawah' => [1_004_999, 'Rp 1 jt'],
            'ribuan tanpa desimal' => [500_000, 'Rp 500 rb'],
            'ribuan satu desimal' => [187_500, 'Rp 187,5 rb'],
            'ribuan dibulatkan' => [28_049, 'Rp 28 rb'],
            'ribuan setengah ke atas' => [28_050, 'Rp 28,1 rb'],
            'seribu' => [1_000, 'Rp 1 rb'],
            'naik ke juta saat pembulatan mencapai 1000 rb' => [999_950, 'Rp 1 jt'],
            'tetap ribuan tepat di bawah ambang' => [999_949, 'Rp 999,9 rb'],
            'naik ke miliar saat pembulatan mencapai 1000 jt' => [999_995_000, 'Rp 1 M'],
            'miliar' => [1_200_000_000, 'Rp 1,2 M'],
            'miliar dua desimal' => [1_234_567_890, 'Rp 1,23 M'],
            'ribuan miliar memakai titik' => [12_345_000_000_000, 'Rp 12.345 M'],
            'di bawah seribu' => [999, 'Rp 999'],
            'nol' => [0, 'Rp 0'],
            'negatif juta' => [-1_800_000, self::MINUS.'Rp 1,8 jt'],
            'negatif ribuan' => [-187_500, self::MINUS.'Rp 187,5 rb'],
            'negatif kecil' => [-500, self::MINUS.'Rp 500'],
            'negatif setengah ke atas menurut nilai mutlak' => [-1_005_000, self::MINUS.'Rp 1,01 jt'],
        ];
    }

    #[DataProvider('compactCases')]
    public function test_compact(int $minor, string $expected): void
    {
        $this->assertSame($expected, Rupiah::compact($minor));
    }
}
