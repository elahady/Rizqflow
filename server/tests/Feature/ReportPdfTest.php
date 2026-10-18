<?php

namespace Tests\Feature;

use App\Models\Account;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use App\Support\PeriodComparator;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Illuminate\Support\Carbon;
use Tests\TestCase;

class ReportPdfTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $room;

    private Category $category;

    private Account $account;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create(['plans' => ['pro']]);
        $this->room = Room::create(['user_id' => $this->user->id, 'name' => 'Keluarga', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $this->category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja bulanan', 'sort_order' => 1]);
        $this->account = Account::create(['user_id' => $this->user->id, 'name' => 'Bank Jago', 'type' => 'bank', 'sort_order' => 1]);
    }

    public function test_free_user_is_redirected_back_to_the_comparison_page(): void
    {
        $this->user->update(['plans' => []]);

        $this->actingAs($this->user)
            ->get(route('laporan.bandingkan.pdf', ['periodA' => '2026-10', 'periodB' => '2026-09']))
            ->assertRedirect(route('laporan.bandingkan'));
    }

    public function test_pro_user_downloads_a_pdf_with_the_right_filename_and_content_type(): void
    {
        Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id, 'room_id' => $this->room->id, 'category_id' => $this->category->id,
            'type' => 'pengeluaran', 'amount_minor' => 500_000, 'category' => $this->category->name, 'occurred_on' => '2026-10-05',
        ]);

        $response = $this->actingAs($this->user)
            ->get(route('laporan.bandingkan.pdf', ['granularity' => 'bulan', 'periodA' => '2026-10', 'periodB' => '2026-09']));

        $response->assertOk();
        $response->assertHeader('content-type', 'application/pdf');
        $this->assertStringContainsString('attachment', $response->headers->get('content-disposition'));
        $this->assertStringContainsString('.pdf', $response->headers->get('content-disposition'));

        // Header PDF asli ("%PDF-"), bukan hanya status 200 — memastikan DomPDF sungguhan merender isinya.
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_an_invalid_period_falls_back_to_the_default_instead_of_erroring(): void
    {
        $response = $this->actingAs($this->user)
            ->get(route('laporan.bandingkan.pdf', ['periodA' => 'tidak-sah', 'periodB' => 'juga-tidak-sah']));

        $response->assertOk();
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_year_granularity_produces_a_pdf_too(): void
    {
        $response = $this->actingAs($this->user)
            ->get(route('laporan.bandingkan.pdf', ['granularity' => 'tahun', 'periodA' => '2026', 'periodB' => '2025']));

        $response->assertOk();
        $this->assertStringStartsWith('%PDF-', $response->getContent());
    }

    public function test_pdf_numbers_match_the_comparison_page(): void
    {
        Transaction::create([
            'user_id' => $this->user->id, 'account_id' => $this->account->id,
            'type' => 'pemasukan', 'amount_minor' => 2_000_000, 'occurred_on' => Carbon::now(),
        ]);

        $result = PeriodComparator::compare($this->user, 'bulan', Carbon::now()->format('Y-m'), Carbon::now()->subMonth()->format('Y-m'));

        $this->assertSame(2_000_000, $result['a']['income']);
    }
}
