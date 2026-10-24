@php
    use App\Support\Period;
    use App\Support\Rupiah;

    $typeLabel = ['menunaikan' => 'Menunaikan', 'menumbuhkan' => 'Menumbuhkan', 'mencukupi' => 'Mencukupi'];
    $segments = $rooms->filter(fn ($room) => (int) $room->allocation_percent > 0)
        ->map(fn ($room) => ['slot' => $room->slot, 'bp' => (int) $room->allocation_percent * 100, 'label' => $room->name])
        ->values()->all();
@endphp

@push('styles')
<style>
    /* Daftar ruang: baris kartu dengan statistik bulan berjalan. */
    .rd-room { display: flex; align-items: center; gap: 18px 24px; flex-wrap: wrap; }
    .rd-id { flex: 1 1 220px; min-width: 0; display: flex; align-items: center; gap: 14px; }
    .rd-name { font-weight: 700; font-size: 15px; }
    a.rd-name:hover { text-decoration: underline; }
    .rd-stat { flex: 1.4 1 260px; min-width: 0; display: flex; flex-direction: column; gap: 8px; }
    .rd-stat-line { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; flex-wrap: wrap; font-size: 13px; color: var(--rf-on-surface-variant); }
    .rd-stat-line b { color: var(--rf-on-background); }
    .rd-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .rd-archived { display: flex; align-items: center; gap: 14px; padding: 10px 0; border-top: 1px solid var(--rf-outline-variant); }
</style>
@endpush

<div class="rf-page">

    <x-rf.page-head :sub="Period::label(now()).' · bulan berjalan'" title="Ruang dan pos">
        <x-slot:actions>
            <a href="{{ route('aturan-alokasi') }}" class="rf-btn tonal sm">Atur alokasi</a>
            @if (! $showAddForm)
                <button type="button" wire:click="$set('showAddForm', true)" class="rf-btn primary sm"><x-rf.icon name="plus" />Ruang baru</button>
            @endif
        </x-slot:actions>
    </x-rf.page-head>

    @if ($rooms->isNotEmpty() && $allocationTotal !== 100)
        <x-rf.banner tone="warn" role="status">
            Total alokasi rezeki ruang aktif sekarang {{ $allocationTotal }}%, belum pas 100%.
            <a class="rf-link" href="{{ route('aturan-alokasi') }}">Atur ulang persentase →</a>
        </x-rf.banner>
    @endif

    @if ($showAddForm)
        <form wire:submit="addRoom" class="rf-card">
            <div class="rf-card-head"><h2 class="rf-h2">Ruang baru</h2></div>

            <div class="rf-form-grid">
                <div class="rf-field">
                    <label for="room-name">Nama ruang</label>
                    <input type="text" id="room-name" class="rf-input" wire:model="name" maxlength="40" autofocus>
                </div>

                <div class="rf-field">
                    <label for="room-type">Tipe ruang</label>
                    <select id="room-type" class="rf-input" wire:model="type">
                        <option value="menunaikan">Menunaikan — tersalurkan sampai target (mis. Memberi)</option>
                        <option value="menumbuhkan">Menumbuhkan — dana benar-benar masuk ke tujuan (mis. investasi)</option>
                        <option value="mencukupi">Mencukupi — kebutuhan tertutup tanpa lewat jatah (mis. Keluarga)</option>
                    </select>
                </div>
            </div>

            @if ($error)
                <p class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $error }}</p>
            @endif

            <div class="rf-actions" style="margin-top:18px">
                <button type="submit" class="rf-btn primary">Simpan</button>
                <button type="button" wire:click="$set('showAddForm', false)" class="rf-btn tonal">Batal</button>
            </div>
        </form>
    @endif

    @if ($segments !== [])
        <section class="rf-card" aria-labelledby="rd-bagi">
            <div class="rf-card-head"><h2 class="rf-h2" id="rd-bagi">Pembagian rezeki</h2><a class="rf-link" href="{{ route('aturan-alokasi') }}">Atur aturan →</a></div>
            <x-rf.stack :segments="$segments" label="Pembagian rezeki" legend />
        </section>
    @endif

    @forelse ($rooms as $index => $room)
        @php
            $roomMonth = $roomMonths[$room->id];
            $percent = $roomMonth->percent();
            $slotClass = $room->slot >= 1 && $room->slot <= 8 ? 'c'.$room->slot : 'cn';
        @endphp
        <div class="rf-card rd-room {{ $slotClass }}">
            <div class="rd-id">
                <x-rf.room-icon :room="$room" />
                <div style="min-width:0">
                    <a href="{{ route('ruang.detail', $room) }}" class="rd-name">{{ $room->name }}</a>
                    <div class="rf-sub" style="margin-top:2px">
                        {{ $typeLabel[$room->type] ?? $room->type }}
                        @if ($room->allocation_percent)
                            · {{ $room->allocation_percent }}% dari alokasi
                        @endif
                    </div>
                </div>
            </div>

            <div class="rd-stat">
                <div class="rd-stat-line">
                    <span>Terpakai <b>{{ Rupiah::format($roomMonth->spentMinor) }}</b> dari jatah <b>{{ Rupiah::format($roomMonth->allocatedMinor) }}</b></span>
                    <x-rf.pill :tone="$roomMonth->statusTone()" :icon="in_array($roomMonth->status(), ['berjalan', 'belum_tercapai'], true) ? 'clock' : null">{{ $roomMonth->statusLabel() }}</x-rf.pill>
                </div>
                <div class="rf-row" style="gap:10px; flex-wrap:nowrap">
                    <x-rf.track style="flex:1" :percent="$percent ?? 0" />
                    <span class="rf-money rf-sub" style="min-width:40px; text-align:right">{{ $percent === null ? '—' : $percent.'%' }}</span>
                </div>
            </div>

            <div class="rd-actions">
                <button type="button" wire:click="moveUp({{ $room->id }})" @disabled($index === 0) class="rf-btn ghost sm" aria-label="Naikkan urutan {{ $room->name }}"><x-rf.icon name="arrow-up" /></button>
                <button type="button" wire:click="moveDown({{ $room->id }})" @disabled($index === $rooms->count() - 1) class="rf-btn ghost sm" aria-label="Turunkan urutan {{ $room->name }}"><x-rf.icon name="arrow-down" /></button>
                <button type="button" wire:click="archiveRoom({{ $room->id }})" wire:confirm="Arsipkan {{ $room->name }}? Persennya kembali ke 0% dan perlu diatur ulang, tapi riwayat transaksinya tetap ada." class="rf-btn ghost sm">Arsipkan</button>
            </div>
        </div>
    @empty
        <section class="rf-card">
            <div class="rf-empty">
                <x-rf.icon name="grid" />
                <b>Belum ada ruang</b>
                <p>Ruang adalah tempat rezeki dialirkan sesuai hak masing-masing. Tambah ruang pertama untuk mulai, atau pakai pola Tiga hak (Memberi, Diri, Keluarga) lewat aplikasi Android.</p>
                @if (! $showAddForm)
                    <div class="rf-actions"><button type="button" wire:click="$set('showAddForm', true)" class="rf-btn primary sm"><x-rf.icon name="plus" />Ruang baru</button></div>
                @endif
            </div>
        </section>
    @endforelse

    @if ($archivedRooms->isNotEmpty())
        <details class="rf-card">
            <summary style="cursor:pointer; font-weight:700; font-size:14px;">Diarsipkan ({{ $archivedRooms->count() }})</summary>
            <div style="margin-top:12px">
                @foreach ($archivedRooms as $room)
                    <div class="rd-archived">
                        <x-rf.room-icon :slot="0" :icon="$room->icon" />
                        <span class="rf-grow rf-sub" style="font-size:14px">{{ $room->name }}</span>
                        <button type="button" wire:click="restoreRoom({{ $room->id }})" class="rf-btn tonal sm">Pulihkan</button>
                    </div>
                @endforeach
            </div>
        </details>
    @endif

</div>
