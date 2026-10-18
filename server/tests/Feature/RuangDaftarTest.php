<?php

namespace Tests\Feature;

use App\Livewire\RuangDaftar;
use App\Models\Category;
use App\Models\Room;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

class RuangDaftarTest extends TestCase
{
    use RefreshDatabase;

    private User $user;

    protected function setUp(): void
    {
        parent::setUp();

        $this->user = User::factory()->create();
    }

    private function room(array $attributes = []): Room
    {
        return Room::create($attributes + [
            'user_id' => $this->user->id,
            'name' => 'Keluarga',
            'type' => 'mencukupi',
            'slot' => 3,
            'sort_order' => 1,
        ]);
    }

    public function test_page_lists_active_rooms_and_puts_archived_under_a_separate_section(): void
    {
        $this->room(['name' => 'Memberi', 'sort_order' => 1]);
        $this->room(['name' => 'Arsip lama', 'sort_order' => 2, 'archived' => true]);

        $this->actingAs($this->user)
            ->get(route('ruang'))
            ->assertOk()
            // Ruang aktif tampil di daftar utama; ruang terarsip hanya di bagian "Diarsipkan" di bawahnya.
            ->assertSeeInOrder(['Memberi', 'Diarsipkan (1)', 'Arsip lama']);
    }

    public function test_page_shows_empty_state_without_rooms(): void
    {
        $this->actingAs($this->user)
            ->get(route('ruang'))
            ->assertOk()
            ->assertSee('Belum ada ruang');
    }

    public function test_adding_a_room_creates_default_categories_and_appends_sort_order(): void
    {
        $this->room(['sort_order' => 5]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->set('name', 'Trader')
            ->set('type', 'menumbuhkan')
            ->call('addRoom')
            ->assertSet('error', null)
            ->assertSet('showAddForm', false);

        $room = Room::where('name', 'Trader')->first();
        $this->assertNotNull($room);
        $this->assertSame('menumbuhkan', $room->type);
        $this->assertSame(6, $room->sort_order);
        $this->assertSame(4, $room->slot);
        $this->assertSame(['Lain-lain', 'Tak terlacak'], $room->categories()->orderBy('sort_order')->pluck('name')->all());
    }

    public function test_free_plan_cannot_add_a_sixth_active_room(): void
    {
        for ($i = 1; $i <= 5; $i++) {
            $this->room(['name' => "Ruang $i", 'slot' => $i + 3, 'sort_order' => $i]);
        }

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->set('name', 'Ruang ke-6')
            ->call('addRoom')
            ->assertSet('error', 'Ruang gratis paling banyak 5. Upgrade ke Pro untuk ruang tak terbatas.');

        $this->assertSame(5, Room::count());
    }

    public function test_archiving_a_room_frees_up_a_slot_under_the_free_limit(): void
    {
        for ($i = 1; $i <= 5; $i++) {
            $this->room(['name' => "Ruang $i", 'slot' => $i + 3, 'sort_order' => $i]);
        }
        Room::where('name', 'Ruang 1')->first()->update(['archived' => true]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->set('name', 'Ruang baru')
            ->call('addRoom')
            ->assertSet('error', null);

        $this->assertSame(6, Room::count());
    }

    public function test_pro_plan_can_add_more_than_five_active_rooms(): void
    {
        $this->user->update(['plans' => ['pro']]);
        for ($i = 1; $i <= 5; $i++) {
            $this->room(['name' => "Ruang $i", 'slot' => $i + 3, 'sort_order' => $i]);
        }

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->set('name', 'Ruang ke-6')
            ->call('addRoom')
            ->assertSet('error', null);

        $this->assertSame(6, Room::count());
    }

    public function test_adding_a_room_without_a_name_shows_an_error_and_saves_nothing(): void
    {
        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->set('name', '   ')
            ->call('addRoom')
            ->assertSet('error', 'Nama ruang wajib diisi.');

        $this->assertSame(0, Room::count());
    }

    public function test_room_slots_are_not_recycled_from_archived_rooms(): void
    {
        $first = $this->room(['name' => 'Custom 1', 'slot' => 4, 'sort_order' => 1]);
        $first->update(['archived' => true]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->set('name', 'Custom 2')
            ->call('addRoom');

        $this->assertSame(5, Room::where('name', 'Custom 2')->first()->slot);
    }

    public function test_archiving_a_room_clears_allocation_but_keeps_its_transactions(): void
    {
        $room = $this->room(['allocation_percent' => 60]);
        $category = Category::create(['room_id' => $room->id, 'name' => 'Belanja']);
        Transaction::create([
            'user_id' => $this->user->id,
            'room_id' => $room->id,
            'category_id' => $category->id,
            'type' => 'pengeluaran',
            'amount_minor' => 50_000,
            'occurred_on' => now(),
        ]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->call('archiveRoom', $room->id);

        $room->refresh();
        $this->assertTrue($room->archived);
        $this->assertNull($room->allocation_percent);
        $this->assertSame(1, $room->transactions()->count());
    }

    public function test_restoring_a_room_appends_it_to_the_end_of_the_active_order(): void
    {
        $active = $this->room(['name' => 'Diri', 'sort_order' => 3]);
        $archived = $this->room(['name' => 'Lama', 'sort_order' => 1, 'archived' => true]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->call('restoreRoom', $archived->id);

        $archived->refresh();
        $this->assertFalse($archived->archived);
        $this->assertGreaterThan($active->sort_order, $archived->sort_order);
    }

    public function test_move_up_and_down_swap_sort_order_with_the_neighbor(): void
    {
        $a = $this->room(['name' => 'A', 'sort_order' => 1]);
        $b = $this->room(['name' => 'B', 'sort_order' => 2]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->call('moveDown', $a->id);

        $a->refresh();
        $b->refresh();
        $this->assertSame(2, $a->sort_order);
        $this->assertSame(1, $b->sort_order);
    }

    public function test_moving_the_first_or_last_room_past_the_edge_does_nothing(): void
    {
        $a = $this->room(['name' => 'A', 'sort_order' => 1]);
        $b = $this->room(['name' => 'B', 'sort_order' => 2]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->call('moveUp', $a->id)
            ->call('moveDown', $b->id);

        $a->refresh();
        $b->refresh();
        $this->assertSame(1, $a->sort_order);
        $this->assertSame(2, $b->sort_order);
    }

    public function test_allocation_banner_shows_only_when_total_is_not_100_percent(): void
    {
        $this->room(['allocation_percent' => 40, 'sort_order' => 1]);
        $this->room(['allocation_percent' => 40, 'sort_order' => 2, 'name' => 'Diri']);

        $this->actingAs($this->user)
            ->get(route('ruang'))
            ->assertSee('belum pas 100%');

        Room::where('name', 'Diri')->update(['allocation_percent' => 60]);

        $this->actingAs($this->user)
            ->get(route('ruang'))
            ->assertDontSee('belum pas 100%');
    }

    public function test_a_user_cannot_archive_another_users_room(): void
    {
        $otherRoom = Room::create([
            'user_id' => User::factory()->create()->id,
            'name' => 'Punya orang lain',
            'type' => 'mencukupi',
            'slot' => 3,
            'sort_order' => 1,
        ]);

        Livewire::actingAs($this->user)
            ->test(RuangDaftar::class)
            ->call('archiveRoom', $otherRoom->id)
            ->assertStatus(404);

        $this->assertFalse($otherRoom->refresh()->archived);
    }
}
