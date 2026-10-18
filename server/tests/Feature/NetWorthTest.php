<?php

namespace Tests\Feature;

use App\Livewire\Denah;
use App\Livewire\KekayaanBersih;
use App\Models\Account;
use App\Models\NetWorthSnapshot;
use App\Models\User;
use App\Models\ZakatProfile;
use App\Support\NetWorthSnapshotter;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Livewire\Livewire;
use Tests\TestCase;

class NetWorthTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
    }

    // --- NetWorthSnapshotter ---

    public function test_captures_a_snapshot_combining_accounts_and_active_zakat_profiles(): void
    {
        Account::create(['user_id' => $this->user->id, 'name' => 'Bank', 'type' => 'bank', 'balance_minor' => 4_000_000, 'sort_order' => 1]);
        Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai', 'balance_minor' => 500_000, 'sort_order' => 2]);
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 100_000_000]);
        ZakatProfile::create(['user_id' => $this->user->id, 'name' => 'Lama', 'net_wealth_minor' => 999_000_000, 'archived' => true]);

        NetWorthSnapshotter::captureIfMissing($this->user);

        $snapshot = NetWorthSnapshot::first();
        $this->assertSame(4_500_000, $snapshot->accounts_minor);
        $this->assertSame(100_000_000, $snapshot->zakat_wealth_minor);
        $this->assertSame(104_500_000, $snapshot->total_minor);
    }

    public function test_includes_archived_accounts_in_the_total(): void
    {
        Account::create(['user_id' => $this->user->id, 'name' => 'Bank', 'type' => 'bank', 'balance_minor' => 1_000_000, 'sort_order' => 1, 'archived' => true]);

        NetWorthSnapshotter::captureIfMissing($this->user);

        $this->assertSame(1_000_000, NetWorthSnapshot::first()->accounts_minor);
    }

    public function test_calling_it_twice_the_same_day_does_not_duplicate(): void
    {
        NetWorthSnapshotter::captureIfMissing($this->user);
        NetWorthSnapshotter::captureIfMissing($this->user);

        $this->assertSame(1, NetWorthSnapshot::count());
    }

    public function test_calling_it_on_a_later_day_adds_a_new_row(): void
    {
        NetWorthSnapshotter::captureIfMissing($this->user, Carbon::yesterday());
        NetWorthSnapshotter::captureIfMissing($this->user, Carbon::today());

        $this->assertSame(2, NetWorthSnapshot::count());
    }

    public function test_opening_denah_captures_todays_snapshot_for_any_plan(): void
    {
        Account::create(['user_id' => $this->user->id, 'name' => 'Bank', 'type' => 'bank', 'balance_minor' => 2_000_000, 'sort_order' => 1]);

        Livewire::actingAs($this->user)->test(Denah::class);

        $this->assertSame(1, NetWorthSnapshot::count());
        $this->assertSame(2_000_000, NetWorthSnapshot::first()->accounts_minor);
    }

    // --- Halaman Kekayaan bersih ---

    public function test_free_user_sees_an_upsell(): void
    {
        $this->actingAs($this->user)
            ->get(route('laporan.kekayaan-bersih'))
            ->assertOk()
            ->assertSee('Kekayaan bersih sepanjang waktu adalah fitur Pro.');
    }

    public function test_pro_user_with_no_history_sees_a_building_history_message(): void
    {
        $this->user->update(['plans' => ['pro']]);
        NetWorthSnapshotter::captureIfMissing($this->user);

        $this->actingAs($this->user)
            ->get(route('laporan.kekayaan-bersih'))
            ->assertOk()
            ->assertSee('Riwayat baru mulai tercatat');
    }

    public function test_pro_user_with_history_receives_the_chart_data_event(): void
    {
        $this->user->update(['plans' => ['pro']]);
        NetWorthSnapshotter::captureIfMissing($this->user, Carbon::yesterday());
        NetWorthSnapshotter::captureIfMissing($this->user, Carbon::today());

        $series = null;
        Livewire::actingAs($this->user)
            ->test(KekayaanBersih::class)
            ->assertDispatched('net-worth-chart-data', function ($name, $params) use (&$series) {
                $series = $params['series'];

                return true;
            });

        $this->assertCount(2, $series['labels']);
    }
}
