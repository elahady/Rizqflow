@php
    use App\Support\Rupiah;

    // ?tab= yang tidak dikenal jatuh ke tab Akun.
    $activeTab = in_array($tab, ['akun', 'kategori', 'favorit'], true) ? $tab : 'akun';
    $typeLabels = ['tunai' => 'Tunai', 'bank' => 'Bank', 'e_wallet' => 'E-wallet'];
@endphp

@push('styles')
<style>
    .kl-row-actions { display: flex; flex-wrap: wrap; gap: 8px; flex: none; }
    .kl-edit { flex: 1 1 0; min-width: 0; display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
    .kl-edit .rf-input { flex: 1 1 140px; min-width: 0; }
    .kl-edit .rf-input.kl-num { flex: 0 1 140px; }
    .kl-amt { white-space: nowrap; font-variant-numeric: tabular-nums; }
    .kl-meta { font-size: 12.5px; color: var(--rf-on-surface-variant); margin-top: 2px; }
    .kl-archived summary { cursor: pointer; font-weight: 700; font-size: 14px; }
    .kl-archived .rf-rowline:first-of-type { margin-top: 10px; }
    .kl-head-end { align-items: flex-end; }
    .kl-room-pick { max-width: 280px; }
</style>
@endpush

<div class="rf-page">
    <x-rf.page-head sub="Pengaturan" title="Akun, kategori, dan favorit"
        lead="Tempat uangmu disimpan, pos pengeluaran di tiap ruang, dan pengeluaran yang sering kamu catat." />

    <x-rf.tabs label="Bagian pengelolaan" :items="[
        ['label' => 'Akun', 'href' => route('kelola', ['tab' => 'akun']), 'active' => $activeTab === 'akun'],
        ['label' => 'Kategori', 'href' => route('kelola', ['tab' => 'kategori']), 'active' => $activeTab === 'kategori'],
        ['label' => 'Favorit', 'href' => route('kelola', ['tab' => 'favorit']), 'active' => $activeTab === 'favorit'],
    ]" />

    {{-- ===== Akun ===== --}}
    @if ($activeTab === 'akun')
        @if ($showAddAccount || $editingAccountId)
            <form wire:submit="{{ $editingAccountId ? 'updateAccount' : 'addAccount' }}" class="rf-card">
                <div class="rf-card-head">
                    <h2 class="rf-h2">{{ $editingAccountId ? 'Ubah akun' : 'Akun baru' }}</h2>
                </div>
                <div class="rf-form-grid">
                    <div class="rf-field">
                        <label for="account-name">Nama akun</label>
                        <input type="text" id="account-name" class="rf-input" wire:model="accountName" maxlength="40" autofocus>
                    </div>
                    <div class="rf-field">
                        <label for="account-type">Jenis</label>
                        <select id="account-type" class="rf-input" wire:model="accountType">
                            <option value="tunai">Tunai</option>
                            <option value="bank">Bank</option>
                            <option value="e_wallet">E-wallet</option>
                        </select>
                    </div>
                    @if (! $editingAccountId)
                        <div class="rf-field">
                            <label for="account-balance">Saldo awal</label>
                            <input type="number" id="account-balance" class="rf-input" wire:model="accountBalance" min="0">
                        </div>
                    @endif
                </div>
                @if ($accountError)
                    <div class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $accountError }}</div>
                @endif
                <div class="rf-actions" style="margin-top:16px">
                    <button type="submit" class="rf-btn primary">Simpan</button>
                    <button type="button" wire:click="{{ $editingAccountId ? 'cancelEditAccount' : '$set(\'showAddAccount\', false)' }}" class="rf-btn ghost">Batal</button>
                </div>
            </form>
        @endif

        <section class="rf-card">
            <div class="rf-card-head">
                <h2 class="rf-h2">Akun</h2>
                @if (! $showAddAccount && ! $editingAccountId)
                    <button type="button" wire:click="$set('showAddAccount', true)" class="rf-btn primary sm"><x-rf.icon name="plus" />Akun baru</button>
                @endif
            </div>

            @forelse ($accounts as $account)
                <div class="rf-rowline" wire:key="akun-{{ $account->id }}">
                    <div class="rf-grow">
                        <b>{{ $account->name }}</b>
                        <div class="kl-meta">{{ $typeLabels[$account->type] ?? $account->type }}</div>
                    </div>
                    <b class="kl-amt">{{ Rupiah::format($account->balance_minor) }}</b>
                    <div class="kl-row-actions">
                        <button type="button" wire:click="startEditAccount({{ $account->id }})" class="rf-btn ghost sm"><x-rf.icon name="pencil" />Ubah</button>
                        <button type="button" wire:click="archiveAccount({{ $account->id }})" wire:confirm="Arsipkan {{ $account->name }}?" class="rf-btn ghost sm">Arsipkan</button>
                    </div>
                </div>
            @empty
                <div class="rf-empty">
                    <x-rf.icon name="card" />
                    <b>Belum ada akun.</b>
                    <p>Tambahkan tunai, rekening bank, atau e-wallet tempat uangmu disimpan.</p>
                </div>
            @endforelse
        </section>

        @if ($archivedAccounts->isNotEmpty())
            <details class="rf-card kl-archived">
                <summary>Diarsipkan ({{ $archivedAccounts->count() }})</summary>
                @foreach ($archivedAccounts as $account)
                    <div class="rf-rowline" wire:key="akun-arsip-{{ $account->id }}">
                        <span class="rf-grow rf-sub">{{ $account->name }}</span>
                        <button type="button" wire:click="restoreAccount({{ $account->id }})" class="rf-btn tonal sm">Pulihkan</button>
                    </div>
                @endforeach
            </details>
        @endif
    @endif

    {{-- ===== Kategori ===== --}}
    @if ($activeTab === 'kategori')
        @if ($rooms->isEmpty())
            <section class="rf-card">
                <div class="rf-empty">
                    <x-rf.icon name="grid" />
                    <b>Belum ada ruang.</b>
                    <p>Pos pengeluaran ada di dalam ruang, jadi buat ruang dulu.</p>
                    <div class="rf-actions"><a class="rf-btn tonal sm" href="{{ route('ruang') }}">Tambah ruang dulu</a></div>
                </div>
            </section>
        @else
            @if ($showAddCategory)
                <form wire:submit="addCategory" class="rf-card">
                    <div class="rf-card-head"><h2 class="rf-h2">Pos baru</h2></div>
                    <div class="rf-field">
                        <label for="category-name">Nama pos</label>
                        <input type="text" id="category-name" class="rf-input" wire:model="categoryName" maxlength="40" autofocus>
                    </div>
                    @if ($categoryError)
                        <div class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $categoryError }}</div>
                    @endif
                    <div class="rf-actions" style="margin-top:16px">
                        <button type="submit" class="rf-btn primary">Simpan</button>
                        <button type="button" wire:click="$set('showAddCategory', false)" class="rf-btn ghost">Batal</button>
                    </div>
                </form>
            @endif

            <section class="rf-card">
                <div class="rf-card-head kl-head-end">
                    <div class="rf-field kl-room-pick">
                        <label for="category-room">Ruang</label>
                        <select id="category-room" class="rf-input" wire:model.live="selectedRoomId">
                            @foreach ($rooms as $room)
                                <option value="{{ $room->id }}">{{ $room->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    @if (! $showAddCategory && ! $editingCategoryId)
                        <button type="button" wire:click="$set('showAddCategory', true)" class="rf-btn primary sm"><x-rf.icon name="plus" />Pos baru</button>
                    @endif
                </div>

                @if (! $showAddCategory && $categoryError)
                    <div class="rf-error" role="alert" style="margin-bottom:12px"><x-rf.icon name="warn" />{{ $categoryError }}</div>
                @endif

                @forelse ($categories as $category)
                    <div class="rf-rowline" wire:key="pos-{{ $category->id }}">
                        @if ($editingCategoryId === $category->id)
                            <form wire:submit="updateCategory" class="kl-edit">
                                <input type="text" class="rf-input" wire:model="editingCategoryName" aria-label="Nama pos" autofocus>
                                <button type="submit" class="rf-btn primary sm">Simpan</button>
                                <button type="button" wire:click="cancelEditCategory" class="rf-btn ghost sm">Batal</button>
                            </form>
                        @else
                            <b class="rf-grow">{{ $category->name }}</b>
                            <div class="kl-row-actions">
                                <button type="button" wire:click="startEditCategory({{ $category->id }})" class="rf-btn ghost sm"><x-rf.icon name="pencil" />Ubah</button>
                                <button type="button" wire:click="archiveCategory({{ $category->id }})" class="rf-btn ghost sm">Arsipkan</button>
                            </div>
                        @endif
                    </div>
                @empty
                    <div class="rf-empty">
                        <x-rf.icon name="list" />
                        <b>Belum ada pos di ruang ini.</b>
                        <p>Pos mengelompokkan pengeluaran di dalam ruang, mis. Dapur dan belanja.</p>
                    </div>
                @endforelse
            </section>

            @if ($archivedCategories->isNotEmpty())
                <details class="rf-card kl-archived">
                    <summary>Diarsipkan ({{ $archivedCategories->count() }})</summary>
                    @foreach ($archivedCategories as $category)
                        <div class="rf-rowline" wire:key="pos-arsip-{{ $category->id }}">
                            <span class="rf-grow rf-sub">{{ $category->name }}</span>
                            <button type="button" wire:click="restoreCategory({{ $category->id }})" class="rf-btn tonal sm">Pulihkan</button>
                        </div>
                    @endforeach
                </details>
            @endif
        @endif
    @endif

    {{-- ===== Favorit ===== --}}
    @if ($activeTab === 'favorit')
        @if ($showAddFavorite)
            <form wire:submit="addFavorite" class="rf-card">
                <div class="rf-card-head"><h2 class="rf-h2">Favorit baru</h2></div>
                <div class="rf-form-grid">
                    <div class="rf-field">
                        <label for="favorite-name">Nama</label>
                        <input type="text" id="favorite-name" class="rf-input" wire:model="favoriteName" maxlength="30" autofocus>
                    </div>
                    <div class="rf-field">
                        <label for="favorite-amount">Nominal</label>
                        <input type="number" id="favorite-amount" class="rf-input" wire:model="favoriteAmount" min="1">
                    </div>
                    <div class="rf-field">
                        <label for="favorite-category">Kategori</label>
                        <select id="favorite-category" class="rf-input" wire:model="favoriteCategoryId">
                            <option value="">Pilih kategori</option>
                            @foreach ($favoriteCategoryOptions as $option)
                                <option value="{{ $option->id }}">{{ $option->room->name }} — {{ $option->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    <div class="rf-field">
                        <label for="favorite-account">Akun</label>
                        <select id="favorite-account" class="rf-input" wire:model="favoriteAccountId">
                            <option value="">Pilih akun</option>
                            @foreach ($favoriteAccountOptions as $option)
                                <option value="{{ $option->id }}">{{ $option->name }}</option>
                            @endforeach
                        </select>
                    </div>
                </div>
                @if ($favoriteError)
                    <div class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $favoriteError }}</div>
                @endif
                <div class="rf-actions" style="margin-top:16px">
                    <button type="submit" class="rf-btn primary">Simpan</button>
                    <button type="button" wire:click="$set('showAddFavorite', false)" class="rf-btn ghost">Batal</button>
                </div>
            </form>
        @endif

        <section class="rf-card">
            <div class="rf-card-head">
                <div>
                    <h2 class="rf-h2">Favorit</h2>
                    <p class="rf-sub">Favorit untuk catat kilat pengeluaran: satu ketukan di aplikasi. Paling banyak 6.</p>
                </div>
                @if (! $showAddFavorite && ! $editingFavoriteId && $favorites->count() < 6)
                    <button type="button" wire:click="$set('showAddFavorite', true)" class="rf-btn primary sm"><x-rf.icon name="plus" />Favorit baru</button>
                @endif
            </div>

            @forelse ($favorites as $favorite)
                <div class="rf-rowline" wire:key="fav-{{ $favorite->id }}">
                    @if ($editingFavoriteId === $favorite->id)
                        <form wire:submit="updateFavorite" class="kl-edit">
                            <input type="text" class="rf-input" wire:model="editingFavoriteName" aria-label="Nama favorit" autofocus>
                            <input type="number" class="rf-input kl-num" wire:model="editingFavoriteAmount" aria-label="Nominal favorit">
                            <button type="submit" class="rf-btn primary sm">Simpan</button>
                            <button type="button" wire:click="cancelEditFavorite" class="rf-btn ghost sm">Batal</button>
                        </form>
                    @else
                        <div class="rf-grow">
                            <b>{{ $favorite->name }}</b>
                            <div class="kl-meta">{{ $favorite->category->name }} · {{ $favorite->account->name }}</div>
                        </div>
                        <b class="kl-amt">{{ Rupiah::format($favorite->amount_minor) }}</b>
                        <div class="kl-row-actions">
                            <button type="button" wire:click="startEditFavorite({{ $favorite->id }})" class="rf-btn ghost sm"><x-rf.icon name="pencil" />Ubah</button>
                            <button type="button" wire:click="deleteFavorite({{ $favorite->id }})" wire:confirm="Hapus favorit {{ $favorite->name }}?" class="rf-btn ghost sm"><x-rf.icon name="trash" />Hapus</button>
                        </div>
                    @endif
                </div>
            @empty
                <div class="rf-empty">
                    <x-rf.icon name="heart" />
                    <b>Belum ada favorit.</b>
                    <p>Simpan pengeluaran yang sering kamu catat, mis. kopi pagi, supaya cukup satu ketukan.</p>
                </div>
            @endforelse

            @if ($favoriteError && ! $showAddFavorite)
                <div class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $favoriteError }}</div>
            @endif
        </section>
    @endif
</div>
