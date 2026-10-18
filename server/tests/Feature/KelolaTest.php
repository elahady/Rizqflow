<?php

namespace Tests\Feature;

use App\Livewire\Kelola;
use App\Models\Account;
use App\Models\Category;
use App\Models\QuickEntry;
use App\Models\Room;
use App\Models\User;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

class KelolaTest extends TestCase
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

    // --- Akun ---

    public function test_adding_an_account_appends_sort_order_and_sets_initial_balance(): void
    {
        Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai', 'sort_order' => 1]);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('accountName', 'Bank Jago')
            ->set('accountType', 'bank')
            ->set('accountBalance', '500000')
            ->call('addAccount')
            ->assertSet('accountError', null);

        $account = Account::where('name', 'Bank Jago')->first();
        $this->assertSame('bank', $account->type);
        $this->assertSame(500_000, $account->balance_minor);
        $this->assertSame(2, $account->sort_order);
    }

    public function test_adding_an_account_without_a_name_shows_an_error(): void
    {
        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('accountName', ' ')
            ->call('addAccount')
            ->assertSet('accountError', 'Nama akun wajib diisi.');

        $this->assertSame(0, Account::count());
    }

    public function test_updating_an_account_changes_name_and_type_but_not_balance(): void
    {
        $account = Account::create(['user_id' => $this->user->id, 'name' => 'Dompet', 'type' => 'tunai', 'balance_minor' => 10_000, 'sort_order' => 1]);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->call('startEditAccount', $account->id)
            ->set('accountName', 'GoPay')
            ->set('accountType', 'e_wallet')
            ->call('updateAccount');

        $account->refresh();
        $this->assertSame('GoPay', $account->name);
        $this->assertSame('e_wallet', $account->type);
        $this->assertSame(10_000, $account->balance_minor);
    }

    public function test_archiving_and_restoring_an_account(): void
    {
        $account = Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai', 'sort_order' => 1]);

        Livewire::actingAs($this->user)->test(Kelola::class)->call('archiveAccount', $account->id);
        $this->assertTrue($account->refresh()->archived);

        Livewire::actingAs($this->user)->test(Kelola::class)->call('restoreAccount', $account->id);
        $this->assertFalse($account->refresh()->archived);
    }

    public function test_cannot_archive_another_users_account(): void
    {
        $otherAccount = Account::create(['user_id' => User::factory()->create()->id, 'name' => 'Punya orang lain', 'type' => 'tunai', 'sort_order' => 1]);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->call('archiveAccount', $otherAccount->id)
            ->assertStatus(404);

        $this->assertFalse($otherAccount->refresh()->archived);
    }

    // --- Kategori ---

    public function test_adding_a_category_to_the_selected_room(): void
    {
        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->set('categoryName', 'Belanja bulanan')
            ->call('addCategory')
            ->assertSet('categoryError', null);

        $this->assertSame(1, $this->room->categories()->count());
        $this->assertSame('Belanja bulanan', $this->room->categories()->first()->name);
    }

    public function test_system_categories_cannot_be_renamed_or_archived(): void
    {
        $system = Category::create(['room_id' => $this->room->id, 'name' => 'Tak terlacak', 'is_system' => true]);
        Category::create(['room_id' => $this->room->id, 'name' => 'Lain-lain']);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->call('startEditCategory', $system->id)
            ->call('archiveCategory', $system->id);

        $this->assertFalse($system->refresh()->archived);
        $this->assertSame('Tak terlacak', $system->name);
    }

    public function test_the_last_regular_category_in_a_room_cannot_be_archived(): void
    {
        $onlyOne = Category::create(['room_id' => $this->room->id, 'name' => 'Lain-lain']);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('selectedRoomId', $this->room->id)
            ->call('archiveCategory', $onlyOne->id)
            ->assertSet('categoryError', 'Ruang ini butuh minimal satu pos biasa — tambah pos lain dulu sebelum mengarsipkan yang terakhir.');

        $this->assertFalse($onlyOne->refresh()->archived);
    }

    public function test_archiving_one_of_several_categories_is_allowed(): void
    {
        $a = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja']);
        Category::create(['room_id' => $this->room->id, 'name' => 'Lain-lain']);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->call('archiveCategory', $a->id);

        $this->assertTrue($a->refresh()->archived);
    }

    public function test_renaming_a_regular_category(): void
    {
        $category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja']);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->call('startEditCategory', $category->id)
            ->set('editingCategoryName', 'Belanja bulanan')
            ->call('updateCategory');

        $this->assertSame('Belanja bulanan', $category->refresh()->name);
    }

    // --- Favorit ---

    public function test_adding_a_favorite(): void
    {
        $category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja']);
        $account = Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai']);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('favoriteName', 'Kopi')
            ->set('favoriteAmount', '15000')
            ->set('favoriteCategoryId', $category->id)
            ->set('favoriteAccountId', $account->id)
            ->call('addFavorite')
            ->assertSet('favoriteError', null);

        $favorite = QuickEntry::first();
        $this->assertSame('Kopi', $favorite->name);
        $this->assertSame(15_000, $favorite->amount_minor);
    }

    public function test_cannot_add_more_than_six_favorites(): void
    {
        $category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja']);
        $account = Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai']);

        for ($i = 1; $i <= 6; $i++) {
            QuickEntry::create(['user_id' => $this->user->id, 'name' => "Favorit $i", 'amount_minor' => 1000, 'category_id' => $category->id, 'account_id' => $account->id]);
        }

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->set('favoriteName', 'Ketujuh')
            ->set('favoriteAmount', '1000')
            ->set('favoriteCategoryId', $category->id)
            ->set('favoriteAccountId', $account->id)
            ->call('addFavorite')
            ->assertSet('favoriteError', 'Paling banyak 6 favorit.');

        $this->assertSame(6, QuickEntry::count());
    }

    public function test_updating_a_favorite_changes_only_name_and_amount(): void
    {
        $category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja']);
        $account = Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai']);
        $favorite = QuickEntry::create(['user_id' => $this->user->id, 'name' => 'Kopi', 'amount_minor' => 15_000, 'category_id' => $category->id, 'account_id' => $account->id]);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->call('startEditFavorite', $favorite->id)
            ->set('editingFavoriteName', 'Kopi susu')
            ->set('editingFavoriteAmount', '20000')
            ->call('updateFavorite');

        $favorite->refresh();
        $this->assertSame('Kopi susu', $favorite->name);
        $this->assertSame(20_000, $favorite->amount_minor);
        $this->assertSame($category->id, $favorite->category_id);
    }

    public function test_deleting_a_favorite(): void
    {
        $category = Category::create(['room_id' => $this->room->id, 'name' => 'Belanja']);
        $account = Account::create(['user_id' => $this->user->id, 'name' => 'Tunai', 'type' => 'tunai']);
        $favorite = QuickEntry::create(['user_id' => $this->user->id, 'name' => 'Kopi', 'amount_minor' => 15_000, 'category_id' => $category->id, 'account_id' => $account->id]);

        Livewire::actingAs($this->user)->test(Kelola::class)->call('deleteFavorite', $favorite->id);

        $this->assertSame(0, QuickEntry::count());
    }

    public function test_cannot_delete_another_users_favorite(): void
    {
        $otherUser = User::factory()->create();
        $otherRoom = Room::create(['user_id' => $otherUser->id, 'name' => 'Lain', 'type' => 'mencukupi', 'slot' => 1, 'sort_order' => 1]);
        $category = Category::create(['room_id' => $otherRoom->id, 'name' => 'Belanja']);
        $account = Account::create(['user_id' => $otherUser->id, 'name' => 'Tunai', 'type' => 'tunai']);
        $favorite = QuickEntry::create(['user_id' => $otherUser->id, 'name' => 'Kopi', 'amount_minor' => 15_000, 'category_id' => $category->id, 'account_id' => $account->id]);

        Livewire::actingAs($this->user)
            ->test(Kelola::class)
            ->call('deleteFavorite', $favorite->id)
            ->assertStatus(404);

        $this->assertSame(1, QuickEntry::count());
    }

    public function test_page_renders_all_three_tabs(): void
    {
        $this->actingAs($this->user)
            ->get(route('kelola'))
            ->assertOk()
            ->assertSee('Akun')
            ->assertSee('Kategori')
            ->assertSee('Favorit');
    }
}
