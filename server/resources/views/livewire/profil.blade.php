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
