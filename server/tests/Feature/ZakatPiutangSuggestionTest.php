<?php

namespace Tests\Feature;

use App\Livewire\Zakat;
use App\Models\Account;
use App\Models\Debt;
use App\Models\DebtPayment;
use App\Models\User;
use App\Models\ZakatProfile;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Saran nilai piutang ke profil harta zakat (porting S15 Android): "Dari Utang-piutang: Rp X" dengan
 * tombol Pakai. Hanya piutang yang belum lunas dan tidak macet yang dihitung; Pakai hanya mengisi form,
 * baru tersimpan setelah profil disimpan.
 */
class ZakatPiutangSuggestionTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $account;

    private ZakatProfile $profile;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'balance_minor' => 0, 'sort_order' => 1]);
        $this->profile = ZakatProfile::create([
            'user_id' => $this->user->id, 'name' => 'Utama', 'net_wealth_minor' => 100_000_000,
            'nisab_minor' => 141_100_000, 'status' => 'belum_nisab',
        ]);
    }

    private function debt(string $direction, int $principal, bool $collectible = true, int $repaid = 0): Debt
    {
        $debt = Debt::create([
            'user_id' => $this->user->id, 'direction' => $direction, 'party' => 'Budi '.$principal.$direction,
            'principal_minor' => $principal, 'account_id' => $this->account->id,
            'start_date' => '2026-09-01', 'due_date' => null, 'note' => null, 'collectible' => $collectible,
        ]);

        if ($repaid > 0) {
            DebtPayment::create(['debt_id' => $debt->id, 'amount_minor' => $repaid, 'paid_on' => '2026-10-01', 'account_id' => $this->account->id]);
        }

        return $debt;
    }

    public function test_the_suggestion_shows_the_outstanding_collectible_receivables(): void
    {
        // Dihitung: piutang lancar belum lunas (2 juta dikurangi 500 ribu sudah kembali).
        $this->debt('piutang', 2_000_000, true, 500_000);
        // Tidak dihitung: piutang macet, utang, dan piutang yang sudah lunas.
        $this->debt('piutang', 900_000, false);
        $this->debt('utang', 700_000);
        $this->debt('piutang', 300_000, true, 300_000);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->assertSee('Dari Utang-piutang: Rp 1.500.000 (sisa piutang yang diperkirakan kembali)')
            ->assertSee('Pakai');
    }

    public function test_pakai_fills_the_wealth_field_and_only_saves_with_the_form(): void
    {
        $this->debt('piutang', 1_500_000);

        $component = Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->call('usePiutangSuggestion')
            ->assertSet('netWealth', '1500000');

        // Belum tersimpan: masih nilai lama di database.
        $this->assertSame(100_000_000, $this->profile->refresh()->net_wealth_minor);

        $component->call('saveProfile')->assertSet('editingProfile', false);

        $this->assertSame(1_500_000, $this->profile->refresh()->net_wealth_minor);
    }

    public function test_the_suggestion_is_hidden_when_there_are_no_receivables(): void
    {
        $this->debt('utang', 700_000);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->assertDontSee('Dari Utang-piutang');
    }

    public function test_the_suggestion_is_hidden_once_the_field_already_holds_the_value(): void
    {
        $this->debt('piutang', 1_500_000);

        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->set('netWealth', '1500000')
            ->assertDontSee('Dari Utang-piutang');
    }

    public function test_the_suggestion_is_not_shown_outside_the_profile_form(): void
    {
        $this->debt('piutang', 1_500_000);

        $this->actingAs($this->user)->get(route('zakat'))
            ->assertOk()
            ->assertDontSee('Dari Utang-piutang');
    }

    public function test_pakai_recomputes_on_the_server_and_ignores_no_receivables(): void
    {
        Livewire::actingAs($this->user)
            ->test(Zakat::class)
            ->call('startEditProfile')
            ->call('usePiutangSuggestion')
            ->assertSet('netWealth', '100000000');
    }
}
