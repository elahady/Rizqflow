@php
    $user = auth()->user();
    $entitlements = $user->entitlements();
    $isPro = $entitlements->isEnabled(App\Support\Feature::ReportsAndInsights);
    $isSync = $entitlements->isEnabled(App\Support\Feature::EncryptedSync);
    $displayName = $user->name ?: $user->email;
    $initial = mb_strtoupper(mb_substr((string) $displayName, 0, 1));

    $plans = [
        [
            'name' => 'Gratis',
            'on' => true,
            'desc' => 'Tiga ruang inti plus ruang peran sampai 5, transaksi tak terbatas, dasar zakat, dan ekspor data.',
        ],
        [
            'name' => 'Pro',
            'on' => $isPro,
            'desc' => 'Ruang tanpa batas, aturan alokasi lanjutan, laporan dan insight, serta impor CSV massal.',
        ],
        [
            'name' => 'Sync',
            'on' => $isSync,
            'desc' => 'Sinkron antar-perangkat dan ruang keluarga bersama. Terpisah dari Pro.',
        ],
    ];
@endphp

@push('styles')
<style>
    .pf-narrow { max-width: 720px; }
    .pf-id { display: flex; align-items: center; gap: 16px; }
    .pf-avatar {
        width: 52px; height: 52px; border-radius: 999px; flex: none; display: flex; align-items: center; justify-content: center;
        background: var(--rf-primary-fixed); color: var(--rf-on-primary-container); font-weight: 800; font-size: 20px;
    }
    .pf-id b { display: block; font-size: 16px; overflow-wrap: anywhere; }
    .pf-plan { align-items: flex-start; }
    .pf-plan p { margin-top: 2px; font-size: 13px; color: var(--rf-on-surface-variant); }
    .pf-danger { border-color: var(--rf-outline); }
    .pf-swatch { display: inline-block; width: 12px; height: 12px; border-radius: 999px; margin-right: 6px; vertical-align: -1px; }
</style>
@endpush

<div class="rf-page pf-narrow">
    <x-rf.page-head sub="Pengaturan" title="Profil dan paket" />

    <section class="rf-card" aria-label="Akun">
        <div class="pf-id">
            <span class="pf-avatar" aria-hidden="true">{{ $initial }}</span>
            <div>
                <b>{{ $user->name }}</b>
                <span class="rf-sub">{{ $user->email }}</span>
            </div>
        </div>
    </section>

    <section class="rf-card">
        <div class="rf-card-head">
            <h2 class="rf-h2">Paket</h2>
        </div>

        @foreach ($plans as $plan)
            <div class="rf-rowline pf-plan">
                <div class="rf-grow">
                    <b>{{ $plan['name'] }}</b>
                    <p>{{ $plan['desc'] }}</p>
                </div>
                @if ($plan['on'])
                    <x-rf.pill tone="good">{{ $plan['name'] === 'Gratis' ? 'Termasuk' : 'Aktif' }}</x-rf.pill>
                @else
                    <x-rf.pill tone="neutral" icon="lock">Belum aktif</x-rf.pill>
                @endif
            </div>
        @endforeach

        <x-rf.banner tone="info" style="margin-top:16px">
            Pembelian Pro dan Sync dilakukan lewat aplikasi Android (Google Play). Pembayaran lewat web belum tersedia.
            Ekspor data selalu gratis.
        </x-rf.banner>
    </section>

    @php
        $storedPalette = $user->themePalette();
        $activePalette = $user->effectiveThemePalette();
        $canUsePalette = $user->entitlements()->isEnabled(App\Support\Feature::Themes);
    @endphp
    <section class="rf-card" id="tampilan" aria-label="Warna aksen">
        <div class="rf-card-head">
            <h2 class="rf-h2">Warna aksen</h2>
        </div>
        <p class="rf-lead" style="margin:0 0 16px">
            Warna tombol dan penanda. Pilihan disimpan di akunmu, jadi ikut ke perangkat lain. Sage gratis;
            Laut, Pasir, dan Anggur adalah fitur Pro.
        </p>

        @if ($storedPalette !== $activePalette)
            <x-rf.banner tone="info" style="margin-bottom:16px">
                Pilihan {{ $storedPalette->label() }} tersimpan dan kembali berlaku begitu Pro aktif. Sementara itu
                tampilan memakai Sage.
            </x-rf.banner>
        @endif

        <div class="rf-seg" role="group" aria-label="Palet warna" style="flex-wrap:wrap">
            @foreach (App\Support\ThemePalette::cases() as $option)
                <button type="button" wire:click="choosePalette('{{ $option->value }}')"
                    @class(['rf-seg-btn', 'on' => $storedPalette === $option])
                    aria-pressed="{{ $storedPalette === $option ? 'true' : 'false' }}">
                    <span class="pf-swatch" style="background: {{ $option->swatch() }}" aria-hidden="true"></span>{{ $option->label().($option->isPro() && ! $canUsePalette ? ' (Pro)' : '') }}
                </button>
            @endforeach
        </div>

        @if ($paletteError)
            <p class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $paletteError }}</p>
        @endif
    </section>

    <section class="rf-card" id="kata-sandi" aria-label="Kata sandi">
        <div class="rf-card-head">
            <h2 class="rf-h2">Kata sandi</h2>
        </div>

        @if ($user->hasPassword())
            <p class="rf-lead" style="margin:0 0 16px">
                Kata sandi dipakai untuk masuk dengan email dan kata sandi, di samping Google. Mengubahnya
                mengeluarkan perangkat lain dari akun ini.
            </p>
        @else
            <x-rf.banner tone="info" style="margin-bottom:16px">
                Akunmu masuk lewat Google dan belum punya kata sandi. Tetapkan satu supaya kamu juga bisa masuk
                dengan email {{ $user->email }} dan kata sandi.
            </x-rf.banner>
        @endif

        @if ($passwordSaved)
            <x-rf.banner tone="good" role="status" style="margin-bottom:16px">
                {{ $passwordSaved === 'set' ? 'Kata sandi sudah ditetapkan. Mulai sekarang kamu bisa masuk dengan email dan kata sandi.' : 'Kata sandi sudah diubah. Perangkat lain perlu masuk lagi.' }}
            </x-rf.banner>
        @endif

        <form wire:submit="savePassword">
            <div class="rf-form-grid">
                @if ($user->hasPassword())
                    <div class="rf-field">
                        <label for="pw-current">Kata sandi saat ini</label>
                        <input type="password" id="pw-current" class="rf-input" wire:model="current_password" autocomplete="current-password" required>
                        @error('current_password')
                            <p class="rf-error" role="alert"><x-rf.icon name="warn" />{{ $message }}</p>
                        @enderror
                    </div>
                @endif
                <div class="rf-field">
                    <label for="pw-new">{{ $user->hasPassword() ? 'Kata sandi baru' : 'Kata sandi' }}</label>
                    <input type="password" id="pw-new" class="rf-input" wire:model="password" autocomplete="new-password" required>
                    <p class="rf-hint">Minimal 8 karakter, dan bukan kata sandi yang umum dipakai.</p>
                    @error('password')
                        <p class="rf-error" role="alert"><x-rf.icon name="warn" />{{ $message }}</p>
                    @enderror
                </div>
                <div class="rf-field">
                    <label for="pw-confirm">Ulangi kata sandi</label>
                    <input type="password" id="pw-confirm" class="rf-input" wire:model="password_confirmation" autocomplete="new-password" required>
                </div>
            </div>
            <div class="rf-actions" style="margin-top:16px">
                <button type="submit" class="rf-btn primary">
                    <x-rf.icon name="check" />{{ $user->hasPassword() ? 'Ubah kata sandi' : 'Tetapkan kata sandi' }}
                </button>
            </div>
        </form>
    </section>

    <section class="rf-card pf-danger">
        <div class="rf-card-head">
            <h2 class="rf-h2">Hapus akun</h2>
        </div>
        <p class="rf-lead" style="margin-top:0">
            Menghapus akun ini dari server Rizqflow, termasuk semua ruang, transaksi, dan data lain yang
            tersimpan di sini. Data di aplikasi Android-mu tidak ikut terhapus: ponsel tetap sumber
            kebenaran. Tindakan ini tidak bisa diurungkan.
        </p>
        <div class="rf-actions" style="margin-top:16px">
            <button type="button" wire:click="deleteAccount"
                wire:confirm="Hapus akun ini dari server? Semua data di sini (ruang, transaksi, dan lainnya) akan terhapus permanen. Data di aplikasi Android tidak ikut terhapus."
                class="rf-btn danger">
                <x-rf.icon name="trash" />Hapus akun
            </button>
        </div>
    </section>
</div>
