@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:28px; max-width:760px; margin:0 auto; width:100%;">

    <h1 class="rf-serif" style="font-size:26px;">Akun, kategori, dan favorit</h1>

    <div style="display:flex; gap:8px; border-bottom:1px solid var(--rf-outline-variant);">
        @foreach (['akun' => 'Akun', 'kategori' => 'Kategori', 'favorit' => 'Favorit'] as $key => $label)
            <button type="button" wire:click="$set('tab', '{{ $key }}')"
                style="background:none; border:none; padding:10px 6px; margin-bottom:-1px; cursor:pointer; font-family:'Manrope',sans-serif; font-size:14px; font-weight:700;
                       color:{{ $tab === $key ? 'var(--rf-primary)' : 'var(--rf-on-surface-variant)' }};
                       border-bottom:2px solid {{ $tab === $key ? 'var(--rf-primary)' : 'transparent' }};">
                {{ $label }}
            </button>
        @endforeach
    </div>

    {{-- ===== Akun ===== --}}
    @if ($tab === 'akun')
        <div style="display:flex; flex-direction:column; gap:16px;">
            @if (! $showAddAccount && ! $editingAccountId)
                <button type="button" wire:click="$set('showAddAccount', true)" class="rf-btn primary" style="align-self:flex-start;">+ Akun baru</button>
            @endif

            @if ($showAddAccount || $editingAccountId)
                <form wire:submit="{{ $editingAccountId ? 'updateAccount' : 'addAccount' }}" class="rf-card">
                    <div class="rf-field">
                        <label for="account-name">Nama akun</label>
                        <input type="text" id="account-name" wire:model="accountName" maxlength="40" autofocus>
                    </div>
                    <div class="rf-field">
                        <label for="account-type">Jenis</label>
                        <select id="account-type" wire:model="accountType">
                            <option value="tunai">Tunai</option>
                            <option value="bank">Bank</option>
                            <option value="e_wallet">E-wallet</option>
                        </select>
                    </div>
                    @if (! $editingAccountId)
                        <div class="rf-field">
                            <label for="account-balance">Saldo awal</label>
                            <input type="number" id="account-balance" wire:model="accountBalance" min="0">
                        </div>
                    @endif
                    @if ($accountError)
                        <p class="error-text">{{ $accountError }}</p>
                    @endif
                    <div style="display:flex; gap:12px; margin-top:8px;">
                        <button type="submit" class="rf-btn primary">Simpan</button>
                        <button type="button" wire:click="{{ $editingAccountId ? 'cancelEditAccount' : '$set(\'showAddAccount\', false)' }}" class="rf-btn tonal">Batal</button>
                    </div>
                </form>
            @endif

            @forelse ($accounts as $account)
                <div class="rf-card" style="display:flex; align-items:center; gap:14px;">
                    <div style="flex:1;">
                        <div style="font-weight:700; font-size:14px;">{{ $account->name }}</div>
                        <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">
                            {{ ['tunai' => 'Tunai', 'bank' => 'Bank', 'e_wallet' => 'E-wallet'][$account->type] ?? $account->type }}
                            · {{ Rupiah::format($account->balance_minor) }}
                        </div>
                    </div>
                    <button type="button" wire:click="startEditAccount({{ $account->id }})" class="rf-btn tonal" style="padding:8px 14px;">Ubah</button>
                    <button type="button" wire:click="archiveAccount({{ $account->id }})" wire:confirm="Arsipkan {{ $account->name }}?" class="rf-btn tonal" style="padding:8px 14px;">Arsipkan</button>
                </div>
            @empty
                <p style="font-size:14px; color:var(--rf-on-surface-variant);">Belum ada akun.</p>
            @endforelse

            @if ($archivedAccounts->isNotEmpty())
                <details class="rf-card">
                    <summary style="cursor:pointer; font-weight:700; font-size:14px;">Diarsipkan ({{ $archivedAccounts->count() }})</summary>
                    <div style="margin-top:16px; display:flex; flex-direction:column; gap:12px;">
                        @foreach ($archivedAccounts as $account)
                            <div style="display:flex; align-items:center; gap:14px; padding:10px 0; border-top:1px solid var(--rf-outline-variant);">
                                <span style="flex:1; font-size:14px; color:var(--rf-on-surface-variant);">{{ $account->name }}</span>
                                <button type="button" wire:click="restoreAccount({{ $account->id }})" class="rf-btn tonal" style="padding:8px 14px;">Pulihkan</button>
                            </div>
                        @endforeach
                    </div>
                </details>
            @endif
        </div>
    @endif

    {{-- ===== Kategori ===== --}}
    @if ($tab === 'kategori')
        <div style="display:flex; flex-direction:column; gap:16px;">
            @if ($rooms->isEmpty())
                <p style="font-size:14px; color:var(--rf-on-surface-variant);">Belum ada ruang. <a href="{{ route('ruang') }}">Tambah ruang dulu</a>.</p>
            @else
                <div class="rf-field" style="max-width:280px;">
                    <label for="category-room">Ruang</label>
                    <select id="category-room" wire:model.live="selectedRoomId">
                        @foreach ($rooms as $room)
                            <option value="{{ $room->id }}">{{ $room->name }}</option>
                        @endforeach
                    </select>
                </div>

                @if (! $showAddCategory && ! $editingCategoryId)
                    <button type="button" wire:click="$set('showAddCategory', true)" class="rf-btn primary" style="align-self:flex-start;">+ Pos baru</button>
                @endif

                @if ($showAddCategory)
                    <form wire:submit="addCategory" class="rf-card">
                        <div class="rf-field">
                            <label for="category-name">Nama pos</label>
                            <input type="text" id="category-name" wire:model="categoryName" maxlength="40" autofocus>
                        </div>
                        @if ($categoryError)
                            <p class="error-text">{{ $categoryError }}</p>
                        @endif
                        <div style="display:flex; gap:12px; margin-top:8px;">
                            <button type="submit" class="rf-btn primary">Simpan</button>
                            <button type="button" wire:click="$set('showAddCategory', false)" class="rf-btn tonal">Batal</button>
                        </div>
                    </form>
                @endif

                @if (! $showAddCategory && $categoryError)
                    <p class="error-text">{{ $categoryError }}</p>
                @endif

                @forelse ($categories as $category)
                    <div class="rf-card" style="display:flex; align-items:center; gap:14px;">
                        @if ($editingCategoryId === $category->id)
                            <form wire:submit="updateCategory" style="flex:1; display:flex; gap:10px; align-items:center;">
                                <input type="text" wire:model="editingCategoryName" style="flex:1; padding:9px 12px; border:1px solid var(--rf-outline-variant); border-radius:10px;" autofocus>
                                <button type="submit" class="rf-btn primary" style="padding:8px 14px;">Simpan</button>
                                <button type="button" wire:click="cancelEditCategory" class="rf-btn tonal" style="padding:8px 14px;">Batal</button>
                            </form>
                        @else
                            <span style="flex:1; font-size:14px; font-weight:600;">{{ $category->name }}</span>
                            <button type="button" wire:click="startEditCategory({{ $category->id }})" class="rf-btn tonal" style="padding:8px 14px;">Ubah</button>
                            <button type="button" wire:click="archiveCategory({{ $category->id }})" class="rf-btn tonal" style="padding:8px 14px;">Arsipkan</button>
                        @endif
                    </div>
                @empty
                    <p style="font-size:14px; color:var(--rf-on-surface-variant);">Belum ada pos di ruang ini.</p>
                @endforelse

                @if ($archivedCategories->isNotEmpty())
                    <details class="rf-card">
                        <summary style="cursor:pointer; font-weight:700; font-size:14px;">Diarsipkan ({{ $archivedCategories->count() }})</summary>
                        <div style="margin-top:16px; display:flex; flex-direction:column; gap:12px;">
                            @foreach ($archivedCategories as $category)
                                <div style="display:flex; align-items:center; gap:14px; padding:10px 0; border-top:1px solid var(--rf-outline-variant);">
                                    <span style="flex:1; font-size:14px; color:var(--rf-on-surface-variant);">{{ $category->name }}</span>
                                    <button type="button" wire:click="restoreCategory({{ $category->id }})" class="rf-btn tonal" style="padding:8px 14px;">Pulihkan</button>
                                </div>
                            @endforeach
                        </div>
                    </details>
                @endif
            @endif
        </div>
    @endif

    {{-- ===== Favorit ===== --}}
    @if ($tab === 'favorit')
        <div style="display:flex; flex-direction:column; gap:16px;">
            <p style="font-size:13px; color:var(--rf-on-surface-variant); margin:0;">Favorit untuk catat kilat pengeluaran — satu ketukan di aplikasi. Paling banyak {{ 6 }}.</p>

            @if (! $showAddFavorite && ! $editingFavoriteId && $favorites->count() < 6)
                <button type="button" wire:click="$set('showAddFavorite', true)" class="rf-btn primary" style="align-self:flex-start;">+ Favorit baru</button>
            @endif

            @if ($showAddFavorite)
                <form wire:submit="addFavorite" class="rf-card">
                    <div class="rf-field">
                        <label for="favorite-name">Nama</label>
                        <input type="text" id="favorite-name" wire:model="favoriteName" maxlength="30" autofocus>
                    </div>
                    <div class="rf-field">
                        <label for="favorite-amount">Nominal</label>
                        <input type="number" id="favorite-amount" wire:model="favoriteAmount" min="1">
                    </div>
                    <div class="rf-field">
                        <label for="favorite-category">Kategori</label>
                        <select id="favorite-category" wire:model="favoriteCategoryId">
                            <option value="">Pilih kategori</option>
                            @foreach ($favoriteCategoryOptions as $option)
                                <option value="{{ $option->id }}">{{ $option->room->name }} — {{ $option->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    <div class="rf-field">
                        <label for="favorite-account">Akun</label>
                        <select id="favorite-account" wire:model="favoriteAccountId">
                            <option value="">Pilih akun</option>
                            @foreach ($favoriteAccountOptions as $option)
                                <option value="{{ $option->id }}">{{ $option->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    @if ($favoriteError)
                        <p class="error-text">{{ $favoriteError }}</p>
                    @endif
                    <div style="display:flex; gap:12px; margin-top:8px;">
                        <button type="submit" class="rf-btn primary">Simpan</button>
                        <button type="button" wire:click="$set('showAddFavorite', false)" class="rf-btn tonal">Batal</button>
                    </div>
                </form>
            @endif

            @forelse ($favorites as $favorite)
                <div class="rf-card" style="display:flex; align-items:center; gap:14px;">
                    @if ($editingFavoriteId === $favorite->id)
                        <form wire:submit="updateFavorite" style="flex:1; display:flex; gap:10px; align-items:center; flex-wrap:wrap;">
                            <input type="text" wire:model="editingFavoriteName" style="flex:1; min-width:120px; padding:9px 12px; border:1px solid var(--rf-outline-variant); border-radius:10px;" autofocus>
                            <input type="number" wire:model="editingFavoriteAmount" style="width:120px; padding:9px 12px; border:1px solid var(--rf-outline-variant); border-radius:10px;">
                            <button type="submit" class="rf-btn primary" style="padding:8px 14px;">Simpan</button>
                            <button type="button" wire:click="cancelEditFavorite" class="rf-btn tonal" style="padding:8px 14px;">Batal</button>
                        </form>
                    @else
                        <div style="flex:1;">
                            <div style="font-weight:700; font-size:14px;">{{ $favorite->name }} · {{ Rupiah::format($favorite->amount_minor) }}</div>
                            <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">{{ $favorite->category->name }} · {{ $favorite->account->name }}</div>
                        </div>
                        <button type="button" wire:click="startEditFavorite({{ $favorite->id }})" class="rf-btn tonal" style="padding:8px 14px;">Ubah</button>
                        <button type="button" wire:click="deleteFavorite({{ $favorite->id }})" wire:confirm="Hapus favorit {{ $favorite->name }}?" class="rf-btn tonal" style="padding:8px 14px;">Hapus</button>
                    @endif
                </div>
            @empty
                <p style="font-size:14px; color:var(--rf-on-surface-variant);">Belum ada favorit.</p>
            @endforelse

            @if ($favoriteError && ! $showAddFavorite)
                <p class="error-text">{{ $favoriteError }}</p>
            @endif
        </div>
    @endif

</div>
