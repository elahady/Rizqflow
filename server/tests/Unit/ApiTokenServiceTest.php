<?php

namespace Tests\Unit;

use App\Auth\ApiTokenService;
use PHPUnit\Framework\Attributes\DataProvider;
use PHPUnit\Framework\TestCase;

/** Penguraian masa berlaku token API (API_TOKEN_TTL_DAYS): bilangan bulat positif sah, selain itu jatuh ke 90. */
class ApiTokenServiceTest extends TestCase
{
    public function test_the_default_is_90_days(): void
    {
        $this->assertSame(90, ApiTokenService::DEFAULT_TTL_DAYS);
    }

    #[DataProvider('validValues')]
    public function test_a_positive_integer_is_taken_as_is(mixed $raw, int $expected): void
    {
        $this->assertSame($expected, ApiTokenService::parseTtlDays($raw));
    }

    public static function validValues(): array
    {
        return [
            'satu hari' => ['1', 1],
            'tiga puluh hari dari env' => ['30', 30],
            'int langsung' => [45, 45],
            'sembilan puluh eksplisit' => ['90', 90],
            'batas atas' => [(string) ApiTokenService::MAX_TTL_DAYS, ApiTokenService::MAX_TTL_DAYS],
            'spasi di sekitar angka' => [' 7 ', 7],
        ];
    }

    #[DataProvider('invalidValues')]
    public function test_anything_else_falls_back_to_90(mixed $raw): void
    {
        $this->assertSame(90, ApiTokenService::parseTtlDays($raw));
    }

    public static function invalidValues(): array
    {
        return [
            'tidak diisi' => [null],
            'string kosong' => [''],
            'huruf' => ['abc'],
            'huruf dan angka' => ['30 hari'],
            'nol' => ['0'],
            'nol int' => [0],
            'negatif' => ['-5'],
            'negatif int' => [-1],
            'pecahan' => ['1.5'],
            'notasi ilmiah' => ['1e2'],
            'angka berawalan nol' => ['030'],
            'di atas batas atas' => [(string) (ApiTokenService::MAX_TTL_DAYS + 1)],
            'sangat besar' => ['99999999999999999999'],
            'boolean' => [true],
            'larik' => [['30']],
        ];
    }
}
