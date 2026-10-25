<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Category;
use App\Models\QuickEntry;
use App\Support\LocalCategoryTemplates;
use Illuminate\Contracts\View\View;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Kelola akun, kategori, dan favorit (S13): tiga tab dalam satu halaman, sama seperti mobile.
 */
class Kelola extends Component
{
    private const ACCOUNT_TYPES = ['tunai', 'bank', 'e_wallet'];

    private const MAX_FAVORITES = 6;

    /** Tab aktif; ikut URL (?tab=akun|kategori|favorit) supaya bisa ditautkan langsung, mis. tombol "+ Pos" di halaman Ruang. */
    #[Url]
    public string $tab = 'akun';

    // Akun
    public bool $showAddAccount = false;

    public string $accountName = '';

    public string $accountType = 'tunai';

    public string $accountBalance = '0';

    public ?int $editingAccountId = null;

    public ?string $accountError = null;

    // Kategori
    public ?int $selectedRoomId = null;

    public bool $showAddCategory = false;

    public string $categoryName = '';

    public ?int $editingCategoryId = null;

    public string $editingCategoryName = '';

    public ?string $categoryError = null;

    // Favorit
    public bool $showAddFavorite = false;

    public string $favoriteName = '';

    public string $favoriteAmount = '';

    public ?int $favoriteCategoryId = null;

    public ?int $favoriteAccountId = null;

    public ?int $editingFavoriteId = null;

    public string $editingFavoriteName = '';

    public string $editingFavoriteAmount = '';

    public ?string $favoriteError = null;

    public function mount(): void
    {
        $this->selectedRoomId = auth()->user()->rooms()->active()->first()?->id;
    }

    // --- Akun ---

    public function addAccount(): void
    {
        $this->accountError = null;
        $name = trim($this->accountName);

        if ($name === '') {
            $this->accountError = 'Nama akun wajib diisi.';

            return;
        }

        if (! in_array($this->accountType, self::ACCOUNT_TYPES, true)) {
            $this->accountError = 'Jenis akun tidak dikenal.';

            return;
        }

        $user = auth()->user();
        $maxSort = (int) $user->accounts()->max('sort_order');

        Account::create([
            'user_id' => $user->id,
            'name' => $name,
            'type' => $this->accountType,
            'balance_minor' => max((int) $this->accountBalance, 0),
            'sort_order' => $maxSort + 1,
        ]);

        $this->reset(['accountName', 'accountBalance', 'showAddAccount']);
        $this->accountType = 'tunai';
    }

    public function startEditAccount(int $accountId): void
    {
        $account = auth()->user()->accounts()->findOrFail($accountId);
        $this->editingAccountId = $account->id;
        $this->accountName = $account->name;
        $this->accountType = $account->type;
        $this->accountError = null;
    }

    public function updateAccount(): void
    {
        $this->accountError = null;
        $name = trim($this->accountName);

        if ($name === '') {
            $this->accountError = 'Nama akun wajib diisi.';

            return;
        }

        $account = auth()->user()->accounts()->findOrFail($this->editingAccountId);
        $account->update(['name' => $name, 'type' => $this->accountType]);
        $this->cancelEditAccount();
    }

    public function cancelEditAccount(): void
    {
        $this->reset(['editingAccountId', 'accountName', 'accountBalance', 'accountError']);
        $this->accountType = 'tunai';
    }

    public function archiveAccount(int $accountId): void
    {
        auth()->user()->accounts()->findOrFail($accountId)->update(['archived' => true]);
    }

    public function restoreAccount(int $accountId): void
    {
        auth()->user()->accounts()->findOrFail($accountId)->update(['archived' => false]);
    }

    // --- Kategori ---

    public function addCategory(): void
    {
        $this->categoryError = null;
        $name = trim($this->categoryName);

        if ($name === '') {
            $this->categoryError = 'Nama pos wajib diisi.';

            return;
        }

        $room = auth()->user()->rooms()->active()->find($this->selectedRoomId);
        if (! $room) {
            $this->categoryError = 'Pilih ruang dulu.';

            return;
        }

        $maxSort = (int) $room->categories()->max('sort_order');

        Category::create([
            'room_id' => $room->id,
            'name' => $name,
            'sort_order' => $maxSort + 1,
        ]);

        $this->reset(['categoryName', 'showAddCategory']);
    }

    /**
     * Chip usulan pos lokal (Android: FilterChip di CategoryForm): hanya mengisi kolom nama, pengguna
     * tetap menekan Simpan. Nama di luar daftar usulan diabaikan.
     */
    public function useSuggestion(string $name): void
    {
        if (! in_array($name, LocalCategoryTemplates::NAMES, true)) {
            return;
        }

        $this->categoryName = $name;
        $this->categoryError = null;
    }

    public function startEditCategory(int $categoryId): void
    {
        $category = $this->ownedCategory($categoryId);
        if (! $category || $category->is_system) {
            return;
        }

        $this->editingCategoryId = $category->id;
        $this->editingCategoryName = $category->name;
        $this->categoryError = null;
    }

    public function updateCategory(): void
    {
        $this->categoryError = null;
        $name = trim($this->editingCategoryName);

        if ($name === '') {
            $this->categoryError = 'Nama pos wajib diisi.';

            return;
        }

        $category = $this->ownedCategory($this->editingCategoryId);
        if ($category && ! $category->is_system) {
            $category->update(['name' => $name]);
        }

        $this->cancelEditCategory();
    }

    public function cancelEditCategory(): void
    {
        $this->reset(['editingCategoryId', 'editingCategoryName', 'categoryError']);
    }

    public function archiveCategory(int $categoryId): void
    {
        $this->categoryError = null;
        $category = $this->ownedCategory($categoryId);

        if (! $category || $category->is_system) {
            return;
        }

        $remaining = $category->room->categories()
            ->where('archived', false)
            ->where('is_system', false)
            ->where('id', '!=', $category->id)
            ->count();

        if ($remaining < 1) {
            $this->categoryError = 'Ruang ini butuh minimal satu pos biasa — tambah pos lain dulu sebelum mengarsipkan yang terakhir.';

            return;
        }

        $category->update(['archived' => true]);
    }

    public function restoreCategory(int $categoryId): void
    {
        $category = $this->ownedCategory($categoryId);
        $category?->update(['archived' => false]);
    }

    private function ownedCategory(?int $categoryId): ?Category
    {
        if (! $categoryId) {
            return null;
        }

        return Category::whereHas('room', fn ($q) => $q->where('user_id', auth()->id()))->find($categoryId);
    }

    // --- Favorit ---

    public function addFavorite(): void
    {
        $this->favoriteError = null;
        $user = auth()->user();

        if ($user->quickEntries()->count() >= self::MAX_FAVORITES) {
            $this->favoriteError = 'Paling banyak '.self::MAX_FAVORITES.' favorit.';

            return;
        }

        $name = trim($this->favoriteName);
        $amount = (int) $this->favoriteAmount;

        if ($name === '' || $amount <= 0) {
            $this->favoriteError = 'Nama dan nominal wajib diisi.';

            return;
        }

        $category = $this->ownedCategory($this->favoriteCategoryId);
        $account = $user->accounts()->find($this->favoriteAccountId);

        if (! $category || ! $account) {
            $this->favoriteError = 'Pilih kategori dan akun.';

            return;
        }

        QuickEntry::create([
            'user_id' => $user->id,
            'name' => $name,
            'amount_minor' => $amount,
            'category_id' => $category->id,
            'account_id' => $account->id,
        ]);

        $this->reset(['favoriteName', 'favoriteAmount', 'favoriteCategoryId', 'favoriteAccountId', 'showAddFavorite']);
    }

    public function startEditFavorite(int $quickEntryId): void
    {
        $favorite = auth()->user()->quickEntries()->findOrFail($quickEntryId);
        $this->editingFavoriteId = $favorite->id;
        $this->editingFavoriteName = $favorite->name;
        $this->editingFavoriteAmount = (string) $favorite->amount_minor;
        $this->favoriteError = null;
    }

    public function updateFavorite(): void
    {
        $this->favoriteError = null;
        $name = trim($this->editingFavoriteName);
        $amount = (int) $this->editingFavoriteAmount;

        if ($name === '' || $amount <= 0) {
            $this->favoriteError = 'Nama dan nominal wajib diisi.';

            return;
        }

        auth()->user()->quickEntries()->findOrFail($this->editingFavoriteId)
            ->update(['name' => $name, 'amount_minor' => $amount]);

        $this->cancelEditFavorite();
    }

    public function cancelEditFavorite(): void
    {
        $this->reset(['editingFavoriteId', 'editingFavoriteName', 'editingFavoriteAmount', 'favoriteError']);
    }

    public function deleteFavorite(int $quickEntryId): void
    {
        auth()->user()->quickEntries()->findOrFail($quickEntryId)->delete();
    }

    public function render(): View
    {
        $user = auth()->user();
        $rooms = $user->rooms()->active()->get();
        $selectedRoom = $rooms->firstWhere('id', $this->selectedRoomId) ?? $rooms->first();

        return view('livewire.kelola', [
            'accounts' => $user->accounts()->where('archived', false)->get(),
            'archivedAccounts' => $user->accounts()->where('archived', true)->get(),
            'rooms' => $rooms,
            'selectedRoom' => $selectedRoom,
            'categories' => $selectedRoom?->categories()->where('archived', false)->orderBy('sort_order')->get() ?? collect(),
            // Usulan pos lokal hanya saat form Pos baru terbuka, dan tidak menawarkan nama yang sudah aktif.
            'suggestions' => $this->showAddCategory && $selectedRoom
                ? LocalCategoryTemplates::suggestionsFor($selectedRoom->categories()->where('archived', false)->pluck('name')->all())
                : [],
            'archivedCategories' => $selectedRoom?->categories()->where('archived', true)->get() ?? collect(),
            'favoriteCategoryOptions' => Category::whereHas('room', fn ($q) => $q->where('user_id', $user->id)->active())
                ->where('archived', false)->where('is_system', false)->with('room')->get(),
            'favoriteAccountOptions' => $user->accounts()->where('archived', false)->get(),
            'favorites' => $user->quickEntries()->with(['category', 'account'])->get(),
        ])->layout('components.layout', ['title' => 'Akun dan kategori — Rizqflow']);
    }
}
