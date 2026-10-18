<?php

namespace Tests\Feature;

use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Tests\TestCase;

class RoomCategoryBreakdownTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    private Room $room;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
        $this->room = Room::create([
            'user_id' => $this->user->id,
            'name' => 'Keluarga',
            'type' => 'mencukupi',
            'slot' => 3,
            'budget_minor' => 3_000_000,
            'used_minor' => 0,
        ]);
    }

    private function pos(string $name, ?int $weight, array $extra = []): Category
    {
        return Category::create(['room_id' => $this->room->id, 'name' => $name, 'weight' => $weight] + $extra);
    }

    private function spend(Category $category, int $amount, ?string $date = null, string $type = 'pengeluaran'): void
    {
        Transaction::create([
            'user_id' => $this->user->id,
            'room_id' => $this->room->id,
            'category_id' => $category->id,
            'type' => $type,
            'amount_minor' => $amount,
            'occurred_on' => $date ?? now()->startOfMonth()->toDateString(),
        ]);
    }

    public function test_weighted_pos_compare_against_their_slice_of_the_room_budget(): void
    {
        $belanja = $this->pos('Belanja bulanan', 5);
        $listrik = $this->pos('Listrik dan air', 2);
        $sekolah = $this->pos('Sekolah', 3);
        $this->spend($belanja, 1_200_000);
        $this->spend($listrik, 330_000);
        $this->spend($sekolah, 1_020_000);

        $rows = $this->room->categoryBreakdown(now())->keyBy(fn ($r) => $r['category']->name);

        $this->assertSame(1_500_000, $rows['Belanja bulanan']['budget_minor']);
        $this->assertSame(80, $rows['Belanja bulanan']['percent']);
        $this->assertSame(600_000, $rows['Listrik dan air']['budget_minor']);
        $this->assertSame(55, $rows['Listrik dan air']['percent']);
        $this->assertSame(113, $rows['Sekolah']['percent']); // lewat jatah tetap dilaporkan apa adanya
    }

    public function test_unweighted_pos_compare_against_the_whole_room_budget(): void
    {
        $this->spend($this->pos('Belanja', null), 750_000);

        $row = $this->room->categoryBreakdown(now())->first();

        $this->assertSame(3_000_000, $row['budget_minor']);
        $this->assertSame(25, $row['percent']);
    }

    public function test_only_this_months_expenses_count(): void
    {
        $belanja = $this->pos('Belanja', null);
        $this->spend($belanja, 300_000);
        $this->spend($belanja, 900_000, now()->startOfMonth()->subDay()->toDateString());
        $this->spend($belanja, 500_000, type: 'pemasukan');

        $this->assertSame(300_000, $this->room->categoryBreakdown(now())->first()['spent_minor']);
    }

    public function test_archived_and_system_pos_appear_only_when_used_and_rows_sort_by_spending(): void
    {
        $this->pos('Kosong', null, ['sort_order' => 1]);
        $besar = $this->pos('Besar', null, ['sort_order' => 2]);
        $this->pos('Terarsip', null, ['archived' => true]);
        $this->pos('Zakat mal', null, ['is_system' => true]);
        $lamaDipakai = $this->pos('Lama dipakai', null, ['archived' => true]);
        $this->spend($besar, 500_000);
        $this->spend($lamaDipakai, 100_000);

        $names = $this->room->categoryBreakdown(now())->map(fn ($r) => $r['category']->name)->all();

        $this->assertSame(['Besar', 'Lama dipakai', 'Kosong'], $names);
    }

    public function test_room_without_budget_has_no_percent(): void
    {
        $this->room->update(['budget_minor' => 0]);
        $this->spend($this->pos('Belanja', 1), 50_000);

        $this->assertNull($this->room->categoryBreakdown(now())->first()['percent']);
    }

    public function test_room_page_shows_pos_section(): void
    {
        $this->spend($this->pos('Belanja bulanan', null), 600_000);

        $this->actingAs($this->user)
            ->get(route('ruang', $this->room))
            ->assertOk()
            ->assertSeeInOrder(['Pos', 'Belanja bulanan', '20%', 'Transaksi terbaru']);
    }

    public function test_room_page_shows_empty_state_without_pos(): void
    {
        $this->actingAs($this->user)
            ->get(route('ruang', $this->room))
            ->assertOk()
            ->assertSee('Belum ada pos di ruang ini.');
    }
}
