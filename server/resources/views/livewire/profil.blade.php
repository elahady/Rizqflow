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
        $syncLabels = [
            'aktif' => ['label' => 'Aktif', 'tone' => 'good'],
            'masa_tenggang' => ['label' => 'Masa tenggang', 'tone' => 'warn'],
            'berakhir' => ['label' => 'Berakhir', 'tone' => 'neutral'],
            'tidak_ada' => ['label' => 'Tidak berlangganan', 'tone' => 'neutral'],
        ];
        $syncOn = $syncLabels[$sync['state']];
        $syncDate = $sync['expires_at']?->locale('id')->translatedFormat('j F Y');
    @endphp

    <section class="rf-card" id="langganan-sync" aria-label="Langganan Sync">
        <div class="rf-card-head">
            <h2 class="rf-h2">Langganan Sync</h2>
        </div>

        <div class="rf-rowline pf-plan">
            <div class="rf-grow">
                <b>Status</b>
                <p>
                    @switch($sync['state'])
                        @case('aktif')
                            @if ($syncDate === null)
                                Sync aktif. Tidak ada tanggal berakhir yang tercatat di server.
                            @elseif ($sync['renewing'])
                                Diperpanjang otomatis oleh Google Play. Periode berjalan sampai {{ $syncDate }}.
                            @else
                                Berlaku sampai {{ $syncDate }}. Tidak diperpanjang otomatis.
                            @endif
                            @break
                        @case('masa_tenggang')
                            Pembayaran belum berhasil. Sync tetap bisa dipakai sampai {{ $syncDate }}; perbarui metode pembayaran di Google Play.
                            @break
                        @case('berakhir')
                            Langganan berakhir{{ $syncDate ? ' pada '.$syncDate : '' }}. Fitur sync terkunci sampai berlangganan lagi.
                            @break
                        @default
                            Kamu belum berlangganan Sync. Sync dipakai untuk sinkron antar-perangkat dan ruang keluarga bersama.
                    @endswitch
                </p>
            </div>
            <x-rf.pill :tone="$syncOn['tone']">{{ $syncOn['label'] }}</x-rf.pill>
        </div>

        <x-rf.banner tone="info" style="margin-top:16px">
            Kelola langganan lewat Google Play di perangkat Android-mu, termasuk memperbarui atau membatalkannya. Web belum menyediakan pembayaran, jadi langganan tidak bisa dibeli dari sini.
        </x-rf.banner>
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

    <section class="rf-card" id="perangkat" aria-label="Perangkat yang masuk">
        <div class="rf-card-head">
            <h2 class="rf-h2">Perangkat yang masuk</h2>
        </div>
        <p class="rf-lead" style="margin:0 0 16px">
            Perangkat Android yang masuk ke akun ini. Mencabut satu perangkat membuatnya keluar dan harus masuk lagi;
            data di perangkat itu tidak ikut terhapus. Sesi web di peramban ini tidak ikut dicabut.
        </p>

        @if ($deviceNotice)
            <x-rf.banner tone="good" role="status" style="margin-bottom:16px">{{ $deviceNotice }}</x-rf.banner>
        @endif

        @forelse ($devices as $device)
            <div class="rf-rowline pf-plan" wire:key="perangkat-{{ $device['id'] }}">
                <div class="rf-grow">
                    <b>{{ $device['label'] }}</b>
                    <p>
                        Masuk sejak {{ $device['created_at']->locale('id')->translatedFormat('j F Y') }}.
                        @if ($device['last_used_at'])
                            Terakhir dipakai {{ $device['last_used_at']->locale('id')->translatedFormat('j F Y') }}.
                        @else
                            Belum pernah dipakai.
                        @endif
                        @if ($device['expires_at'] && $device['expires_at']->isPast())
                            Kedaluwarsa pada {{ $device['expires_at']->locale('id')->translatedFormat('j F Y') }}.
                        @elseif ($device['expires_at'])
                            Berlaku sampai {{ $device['expires_at']->locale('id')->translatedFormat('j F Y') }}.
                        @endif
                    </p>
                </div>
                <button type="button" class="rf-btn ghost sm"
                    wire:click="revokeDevice({{ $device['id'] }})"
                    wire:confirm="Cabut perangkat ini? Perangkat itu akan keluar dari akun dan harus masuk lagi."
                    aria-label="Cabut perangkat {{ $device['label'] }} masuk {{ $device['created_at']->locale('id')->translatedFormat('j F Y') }}">
                    <x-rf.icon name="x" />Cabut
                </button>
            </div>
        @empty
            <p class="rf-lead" style="margin:0">Belum ada perangkat Android yang masuk ke akun ini.</p>
        @endforelse

        @if ($devices !== [])
            <div class="rf-actions" style="margin-top:16px">
                <button type="button" class="rf-btn danger"
                    wire:click="revokeAllDevices"
                    wire:confirm="Keluarkan semua perangkat Android dari akun ini? Semuanya harus masuk lagi.">
                    <x-rf.icon name="logout" />Keluar dari semua perangkat
                </button>
            </div>
        @endif
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
