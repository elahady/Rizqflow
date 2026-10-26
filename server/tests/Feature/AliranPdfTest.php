<?php

namespace Tests\Feature;

use App\Livewire\Aliran;
use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\User;
use App\Support\Ledger;
use App\Support\Reports\AliranReport;
use Carbon\CarbonImmutable;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Unduhan PDF Analisa › Aliran (Pro). Angka dan kalimatnya berasal dari AliranReport yang sama dengan
 * layar, jadi tes ini membandingkan keduanya untuk filter yang sama.
 */
class AliranPdfTest extends TestCase
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

        Ledger::recordIncome($this->user, 10_000_000, $this->bca, CarbonImmutable::parse('2026-10-22'), 'Gaji', null);
        Ledger::recordIncome($this->user, 5_000_000, $this->dana, CarbonImmutable::parse('2026-10-23'), 'Usaha', null);
        Ledger::recordExpense($this->user, 1_000_000, $this->pos, $this->bca, CarbonImmutable::parse('2026-10-10'), null);
        Ledger::recordExpense($this->user, 500_000, $this->pos, $this->dana, CarbonImmutable::parse('2026-10-11'), null);
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

    public function test_free_user_is_redirected_to_the_aliran_page(): void
    {
        $free = $this->userWithAccounts(pro: false);

        $this->actingAs($free)->get(route('laporan.aliran.pdf', ['bulan' => '2026-10']))
            ->assertRedirect(route('laporan'));
    }

    public function test_pro_user_downloads_the_aliran_as_a_pdf_named_by_month_and_range(): void
    {
        $response = $this->actingAs($this->user)->get(route('laporan.aliran.pdf', ['bulan' => '2026-10', 'rentang' => '3']));

        $response->assertOk();
        $response->assertHeader('content-type', 'application/pdf');
        $this->assertStringContainsString('aliran-2026-10-3-bulan.pdf', $response->headers->get('content-disposition'));
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_invalid_query_values_fall_back_to_the_defaults_instead_of_erroring(): void
    {
        $response = $this->actingAs($this->user)->get(route('laporan.aliran.pdf', ['bulan' => 'bukan-bulan', 'rentang' => '99', 'akun' => 'ngawur']));

        $response->assertOk();
        $this->assertStringContainsString('aliran-2026-10-1-bulan.pdf', $response->headers->get('content-disposition'));
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_the_pdf_numbers_match_the_screen_for_the_same_account_filter(): void
    {
        $query = ['akun' => 'kustom', 'ids' => (string) $this->dana->id];

        $screen = Livewire::actingAs($this->user)->withQueryParams($query)->test(Aliran::class)->viewData('flow');
        $pdf = AliranReport::build($this->user, CarbonImmutable::parse('2026-10-01'), 1, [$this->dana->id]);

        $this->assertSame(5_000_000, $pdf['layout']['total']);
        $this->assertSame($screen['layout']['total'], $pdf['layout']['total']);
        $this->assertSame($screen['layout']['spent'], $pdf['layout']['spent']);
        $this->assertSame($screen['readouts'], $pdf['readouts']);
        $this->assertSame($screen['rows'], $pdf['rows']);
        $this->assertSame($screen['insights'], $pdf['insights']);

        $this->actingAs($this->user)->get(route('laporan.aliran.pdf', ['bulan' => '2026-10'] + $query))
            ->assertOk()
            ->assertHeader('content-type', 'application/pdf');
    }

    public function test_pdf_template_shows_the_same_numbers_and_readout_as_the_page(): void
    {
        $flow = AliranReport::build($this->user, CarbonImmutable::parse('2026-10-01'), 1, null);

        $html = view('pdf.aliran', [
            'flow' => $flow,
            'rangeLabel' => AliranReport::rangeLabel(CarbonImmutable::parse('2026-10-01'), 1),
            'akunLabel' => AliranReport::accountLabel($this->user, null),
            'userName' => $this->user->name,
        ])->render();

        $this->assertStringContainsString('Semua akun', $html);
        $this->assertStringContainsString('Oktober 2026', $html);
        $this->assertStringContainsString('Rp 15.000.000', $html);
        $this->assertStringContainsString($flow['readouts']['semua'], $html);
        $this->assertStringContainsString('Gaji', $html);
        $this->assertStringContainsString('Usaha', $html);
    }

    public function test_pdf_template_for_an_empty_range_says_so_plainly(): void
    {
        $flow = AliranReport::build($this->user, CarbonImmutable::parse('2019-03-01'), 1, null);

        $html = view('pdf.aliran', [
            'flow' => $flow,
            'rangeLabel' => AliranReport::rangeLabel(CarbonImmutable::parse('2019-03-01'), 1),
            'akunLabel' => AliranReport::accountLabel($this->user, null),
            'userName' => $this->user->name,
        ])->render();

        $this->assertStringContainsString('Belum ada rezeki yang dicatat pada Maret 2019.', $html);
        $this->assertStringNotContainsString('Ringkasan', $html);
    }
}
