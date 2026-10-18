<?php

namespace Tests\Feature;

use App\Livewire\AturanAlokasi;
use App\Models\Room;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

class AturanAlokasiTest extends TestCase
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
            'name' => 'Ruang',
            'type' => 'mencukupi',
            'slot' => 1,
            'sort_order' => 1,
        ]);
    }

    public function test_page_lists_editable_rooms_and_shows_the_last_one_as_the_remainder(): void
    {
        $this->room(['name' => 'Memberi', 'sort_order' => 1, 'allocation_percent' => 10]);
        $this->room(['name' => 'Diri', 'sort_order' => 2, 'allocation_percent' => 30]);
        $this->room(['name' => 'Keluarga', 'sort_order' => 3, 'allocation_percent' => 60]);

        $this->actingAs($this->user)
            ->get(route('aturan-alokasi'))
            ->assertOk()
            ->assertSee('Memberi')
            ->assertSee('Diri')
            ->assertSeeInOrder(['Keluarga', 'Sisa, otomatis', '60%']);
    }

    public function test_saving_valid_percentages_sets_the_last_room_to_the_remainder(): void
    {
        $memberi = $this->room(['name' => 'Memberi', 'sort_order' => 1]);
        $diri = $this->room(['name' => 'Diri', 'sort_order' => 2]);
        $keluarga = $this->room(['name' => 'Keluarga', 'sort_order' => 3]);

        Livewire::actingAs($this->user)
            ->test(AturanAlokasi::class)
            ->set("percents.{$memberi->id}", 10)
            ->set("percents.{$diri->id}", 30)
            ->call('save')
            ->assertSet('error', null);

        $this->assertSame(10, $memberi->refresh()->allocation_percent);
        $this->assertSame(30, $diri->refresh()->allocation_percent);
        $this->assertSame(60, $keluarga->refresh()->allocation_percent);
    }

    public function test_saving_more_than_100_percent_is_rejected_and_changes_nothing(): void
    {
        $memberi = $this->room(['name' => 'Memberi', 'sort_order' => 1, 'allocation_percent' => 10]);
        $diri = $this->room(['name' => 'Diri', 'sort_order' => 2, 'allocation_percent' => 30]);
        $this->room(['name' => 'Keluarga', 'sort_order' => 3, 'allocation_percent' => 60]);

        Livewire::actingAs($this->user)
            ->test(AturanAlokasi::class)
            ->set("percents.{$memberi->id}", 70)
            ->set("percents.{$diri->id}", 70)
            ->call('save')
            ->assertSet('error', 'Total persentase melebihi 100%.');

        $this->assertSame(10, $memberi->refresh()->allocation_percent);
        $this->assertSame(30, $diri->refresh()->allocation_percent);
    }

    public function test_a_single_active_room_shows_an_info_message_instead_of_a_form(): void
    {
        $this->room(['name' => 'Satu-satunya']);

        $this->actingAs($this->user)
            ->get(route('aturan-alokasi'))
            ->assertOk()
            ->assertSee('satu-satunya ruang aktif');
    }

    public function test_page_without_rooms_invites_adding_one(): void
    {
        $this->actingAs($this->user)
            ->get(route('aturan-alokasi'))
            ->assertOk()
            ->assertSee('Belum ada ruang untuk diatur alokasinya.');
    }

    public function test_cannot_change_another_users_room_allocation(): void
    {
        $other = User::factory()->create();
        $otherRoom = Room::create([
            'user_id' => $other->id,
            'name' => 'Punya orang lain',
            'type' => 'mencukupi',
            'slot' => 1,
            'sort_order' => 1,
            'allocation_percent' => 50,
        ]);
        $this->room(['sort_order' => 1]);
        $this->room(['name' => 'Kedua', 'sort_order' => 2]);

        Livewire::actingAs($this->user)
            ->test(AturanAlokasi::class)
            ->set("percents.{$otherRoom->id}", 99)
            ->call('save');

        $this->assertSame(50, $otherRoom->refresh()->allocation_percent);
    }
}
