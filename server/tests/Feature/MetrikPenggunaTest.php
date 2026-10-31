<?php

namespace Tests\Feature;

use App\Models\User;
use App\Models\UserMetricSnapshot;
use Illuminate\Console\Scheduling\Schedule;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\Schema;
use Tests\TestCase;

/** Metrik pengguna anonim (Tahap 9): `metrik:pengguna`, hanya hitungan tanpa identitas. */
class MetrikPenggunaTest extends TestCase
{
    use RefreshDatabase;

    private function akun(): void
    {
        User::factory()->create(['plans' => [], 'email' => 'gratis@contoh.test', 'name' => 'Gratis Rahasia']);
        User::factory()->create(['plans' => ['pro']]);
        User::factory()->create(['plans' => ['sync']]);
        User::factory()->create(['plans' => ['pro', 'sync']]);
        User::factory()->create(['plans' => null]);
    }

    public function test_menghitung_pengguna_pro_dan_sync(): void
    {
        $this->akun();

        $this->artisan('metrik:pengguna')
            ->expectsOutputToContain('Pengguna: 5. Pro: 2. Sync: 2.')
            ->assertSuccessful();

        $this->assertDatabaseCount('user_metric_snapshots', 0);
    }

    public function test_simpan_menulis_satu_potret_per_hari_dan_menimpa_di_hari_yang_sama(): void
    {
        Carbon::setTestNow('2026-11-01 04:15:00');
        $this->akun();

        $this->artisan('metrik:pengguna --simpan')->assertSuccessful();
        User::factory()->create(['plans' => ['sync']]);
        $this->artisan('metrik:pengguna --simpan')->assertSuccessful();

        $this->assertSame(1, UserMetricSnapshot::count());
        $potret = UserMetricSnapshot::first();
        $this->assertSame('2026-11-01', substr((string) $potret->tanggal, 0, 10));
        $this->assertSame([6, 2, 3], [$potret->pengguna_total, $potret->pengguna_pro, $potret->pengguna_sync]);

        Carbon::setTestNow('2026-11-02 04:15:00');
        $this->artisan('metrik:pengguna --simpan')->assertSuccessful();
        $this->assertSame(2, UserMetricSnapshot::count());

        Carbon::setTestNow();
    }

    public function test_tabel_potret_tidak_punya_kolom_identitas_atau_keuangan(): void
    {
        $kolom = Schema::getColumnListing('user_metric_snapshots');

        sort($kolom);
        $this->assertSame(
            ['created_at', 'id', 'pengguna_pro', 'pengguna_sync', 'pengguna_total', 'tanggal', 'updated_at'],
            $kolom,
        );
    }

    public function test_keluaran_tidak_memuat_email_atau_nama(): void
    {
        $this->akun();

        $this->artisan('metrik:pengguna --simpan')
            ->doesntExpectOutputToContain('gratis@contoh.test')
            ->doesntExpectOutputToContain('Gratis Rahasia')
            ->assertSuccessful();
    }

    public function test_dijadwalkan_harian_dengan_simpan(): void
    {
        $jadwal = collect(app(Schedule::class)->events())
            ->first(fn ($event) => str_contains($event->command ?? '', 'metrik:pengguna --simpan'));

        $this->assertNotNull($jadwal);
        $this->assertSame('15 4 * * *', $jadwal->expression);
    }
}
