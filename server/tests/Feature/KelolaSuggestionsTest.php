<?php

namespace Tests\Feature;

use App\Livewire\Kelola;
use App\Models\Category;
use App\Models\Room;
use App\Models\User;
use App\Support\LocalCategoryTemplates;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/**
 * Usulan pos lokal (Tahap 10) di form Pos baru Kelola, porting chip usulan di Android (CategoryForm).
 * Chip hanya mengisi kolom nama; pos baru dibuat setelah Simpan, sama seperti Android.
 */
class KelolaSuggestionsTest extends TestCase
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
            'slot' => 1,
            'sort_order' => 1,
        ]);
    }

    public function test_the_four_local_templates_match_android(): void
    {
        $this->assertSame(
            ['Arisan', 'THR & Lebaran', 'Kiriman orang tua', 'Cicilan & paylater'],
            LocalCategoryTemplates::NAMES,
        );
    }

    public function test_the_suggestions_appear_when_the_new_category_form_is_open(): void
    {
        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('tab', 'kategori')
            ->set('selectedRoomId', $this->room->id)
            ->set('showAddCategory', true)
            ->assertSee('Usulan pos lokal')
            ->assertSee('Arisan')
            ->assertSee('THR & Lebaran')
            ->assertSee('Kiriman orang tua')
            ->assertSee('Cicilan & paylater');
    }

    public function test_the_suggestions_are_hidden_while_the_form_is_closed(): void
    {
        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('tab', 'kategori')
            ->set('selectedRoomId', $this->room->id)
            ->assertDontSee('Usulan pos lokal');
    }

    public function test_a_suggestion_already_active_in_the_room_is_not_offered_again(): void
    {
        Category::create(['room_id' => $this->room->id, 'name' => 'arisan', 'sort_order' => 1]);

        $component = Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->set('showAddCategory', true);

        $this->assertSame(['THR & Lebaran', 'Kiriman orang tua', 'Cicilan & paylater'], $component->viewData('suggestions'));
    }

    public function test_an_archived_category_does_not_block_its_suggestion(): void
    {
        Category::create(['room_id' => $this->room->id, 'name' => 'Arisan', 'sort_order' => 1, 'archived' => true]);

        $component = Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->set('showAddCategory', true);

        $this->assertSame(LocalCategoryTemplates::NAMES, $component->viewData('suggestions'));
    }

    public function test_clicking_a_chip_fills_the_name_and_saving_creates_the_category(): void
    {
        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->set('showAddCategory', true)
            ->call('useSuggestion', 'THR & Lebaran')
            ->assertSet('categoryName', 'THR & Lebaran')
            ->assertSet('showAddCategory', true)
            ->call('addCategory')
            ->assertSet('categoryError', null);

        $this->assertSame(1, $this->room->categories()->count());
        $this->assertSame('THR & Lebaran', $this->room->categories()->first()->name);
    }

    public function test_clicking_a_chip_does_not_create_the_category_by_itself(): void
    {
        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->set('showAddCategory', true)
            ->call('useSuggestion', 'Arisan');

        $this->assertSame(0, $this->room->categories()->count());
    }

    public function test_a_name_outside_the_suggestion_list_is_ignored(): void
    {
        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->set('showAddCategory', true)
            ->call('useSuggestion', 'Bebas')
            ->assertSet('categoryName', '');
    }

    public function test_the_suggestions_follow_the_room_selected_in_the_form(): void
    {
        $other = Room::create(['user_id' => $this->user->id, 'name' => 'Diri', 'type' => 'menumbuhkan', 'slot' => 2, 'sort_order' => 2]);
        Category::create(['room_id' => $other->id, 'name' => 'Arisan', 'sort_order' => 1]);

        $component = Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->set('showAddCategory', true);
        $this->assertSame(LocalCategoryTemplates::NAMES, $component->viewData('suggestions'));

        $component->set('selectedRoomId', $other->id);
        $this->assertSame(['THR & Lebaran', 'Kiriman orang tua', 'Cicilan & paylater'], $component->viewData('suggestions'));
    }
}
