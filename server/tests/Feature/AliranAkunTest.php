<?php

namespace Tests\Feature;

use App\Livewire\Aliran;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\User;
use App\Support\Ledger;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Analisa › Aliran: filter akun "Semua akun" (bawaan) dan "Kustom" (pilih beberapa akun). Filter ini
 * hanya menyaring angka; tidak membuka fitur baru untuk pengguna non-Pro. "Hari ini" dibekukan ke
 * 24 Oktober 2026 seperti AliranTest.
 */
class AliranAkunTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Account $bca;

    private Account $dana;

    private Category $pos;

    protected function setUp(): void
    {
        parent::setUp();

        $this->travelTo(CarbonImmutable::parse('2026-10-24 10:00'));
        $this->user = $this->userWithAccounts();
        $this->bca = $this->user->accounts()->where('name', 'BCA')->firstOrFail();
        $this->dana = $this->user->accounts()->where('name', 'Dana')->firstOrFail();
        $keluarga = $this->user->rooms()->where('name', 'Keluarga')->firstOrFail();
        $this->pos = Category::factory()->create(['room_id' => $keluarga->id, 'name' => 'Belanja', 'sort_order' => 1]);

        // Oktober: pemasukan 10 juta ke BCA dan 5 juta ke Dana; pengeluaran 1 juta dari BCA dan 500 ribu dari Dana.
        $this->income(10_000_000, $this->bca);
        $this->income(5_000_000, $this->dana);
        $this->spend(1_000_000, $this->bca);
        $this->spend(500_000, $this->dana);
    }

    private function userWithAccounts(bool $pro = true): User
    {
        $user = User::factory()->create($pro ? ['plans' => ['pro']] : []);
        Account::factory()->create(['user_id' => $user->id, 'name' => 'BCA', 'sort_order' => 1]);
        Account::factory()->create(['user_id' => $user->id, 'name' => 'Dana', 'sort_order' => 2]);
        Room::factory()->percent(10)->type(Room::TYPE_MENUNAIKAN)->create(['user_id' => $user->id, 'name' => 'Memberi', 'slot' => 1, 'sort_order' => 1, 'icon' => 'memberi']);
        Room::factory()->percent(30)->type(Room::TYPE_MENUMBUHKAN)->create(['user_id' => $user->id, 'name' => 'Diri', 'slot' => 2, 'sort_order' => 2, 'icon' => 'diri']);
        Room::factory()->percent(60)->create(['user_id' => $user->id, 'name' => 'Keluarga', 'slot' => 3, 'sort_order' => 3, 'icon' => 'keluarga']);

        return $user;
    }

    private function income(int $amount, Account $account): void
    {
        Ledger::recordIncome($this->user, $amount, $account, CarbonImmutable::parse('2026-10-22'), 'Gaji', null);
    }

    private function spend(int $amount, Account $account): void
    {
        Ledger::recordExpense($this->user, $amount, $this->pos, $account, CarbonImmutable::parse('2026-10-10'), null);
    }

    private function aliran(array $query = [], ?User $as = null)
    {
        return Livewire::actingAs($as ?? $this->user)->withQueryParams($query)->test(Aliran::class);
    }

    /** @return list<int> */
    private function ids(string $csv): array
    {
        $ids = array_map('intval', array_filter(explode(',', $csv), fn ($v) => $v !== ''));
        sort($ids);

        return $ids;
    }

    public function test_semua_akun_is_the_default_and_counts_every_account(): void
    {
        $component = $this->aliran()
            ->assertSet('akun', 'semua')
            ->assertSee('Semua akun')
            ->assertSee('Total Rp 15.000.000.');

        $flow = $component->viewData('flow');
        $this->assertSame(15_000_000, $flow['layout']['total']);
        $this->assertSame(1_500_000, $flow['layout']['spent']);
        $component->assertSet('akunIds', '');
    }

    public function test_kustom_with_one_account_counts_only_that_accounts_income_and_spending(): void
    {
        $component = $this->aliran(['akun' => 'kustom', 'ids' => (string) $this->dana->id])
            ->assertSee('Total Rp 5.000.000.')
            ->assertSee('Kustom');

        $flow = $component->viewData('flow');
        $this->assertSame(5_000_000, $flow['layout']['total']);
        $this->assertSame(500_000, $flow['layout']['spent']);
    }

    public function test_choosing_kustom_selects_every_account_first_and_toggling_narrows_it(): void
    {
        $component = $this->aliran()
            ->call('setAkun', 'kustom')
            ->assertSet('akun', 'kustom');

        $this->assertSame([$this->bca->id, $this->dana->id], $this->ids($component->get('akunIds')));
        $component->assertSee('Total Rp 15.000.000.');

        $component->call('toggleAkun', $this->bca->id)
            ->assertSee('Total Rp 5.000.000.');
        $this->assertSame([$this->dana->id], $this->ids($component->get('akunIds')));
    }

    public function test_the_last_remaining_account_cannot_be_unchecked(): void
    {
        $component = $this->aliran(['akun' => 'kustom', 'ids' => (string) $this->dana->id])
            ->call('toggleAkun', $this->dana->id);

        $this->assertSame([$this->dana->id], $this->ids($component->get('akunIds')));
        $component->assertSee('Total Rp 5.000.000.');
    }

    public function test_a_foreign_account_id_is_ignored_in_the_url_and_in_toggles(): void
    {
        $other = $this->userWithAccounts();
        $foreign = $other->accounts()->where('name', 'BCA')->firstOrFail();

        $component = $this->aliran(['akun' => 'kustom', 'ids' => (string) $foreign->id]);
        $this->assertSame(0, $component->viewData('flow')['layout']['total']);

        $component->call('toggleAkun', $foreign->id);
        $this->assertSame([], $this->ids($component->get('akunIds')));
    }

    public function test_switching_back_to_semua_clears_the_selection_and_counts_everything(): void
    {
        $component = $this->aliran(['akun' => 'kustom', 'ids' => (string) $this->dana->id])
            ->call('setAkun', 'semua')
            ->assertSet('akun', 'semua')
            ->assertSet('akunIds', '')
            ->assertSee('Total Rp 15.000.000.');

        $this->assertSame(15_000_000, $component->viewData('flow')['layout']['total']);
    }

    public function test_the_pdf_link_carries_the_account_filter(): void
    {
        $this->aliran(['akun' => 'kustom', 'ids' => (string) $this->dana->id])
            ->assertSee('/laporan/aliran/pdf?', false)
            ->assertSee('akun=kustom', false)
            ->assertSee('ids='.$this->dana->id, false);
    }

    public function test_a_free_user_still_gets_no_flow_when_using_the_account_filter(): void
    {
        $free = $this->userWithAccounts(pro: false);
        $dana = $free->accounts()->where('name', 'Dana')->firstOrFail();

        $component = $this->aliran(['akun' => 'kustom', 'ids' => (string) $dana->id], $free)
            ->call('setAkun', 'kustom')
            ->call('toggleAkun', $dana->id)
            ->assertDontSee('Rincian (tabel kembaran grafik)');

        $component->assertViewHas('flow', null);
    }
}
