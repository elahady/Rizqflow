@php
    $roomColors = [1 => '#3b6fa8', 2 => '#c4653a', 3 => '#2a9a80', 4 => '#af6dec', 5 => '#a04177', 6 => '#099acf', 7 => '#998214', 8 => '#de4f74'];
    $typeLabel = ['menunaikan' => 'Menunaikan', 'menumbuhkan' => 'Menumbuhkan', 'mencukupi' => 'Mencukupi'];
@endphp

<div style="display:flex; flex-direction:column; gap:28px; max-width:880px; margin:0 auto; width:100%;">

    <div style="display:flex; align-items:baseline; justify-content:space-between; flex-wrap:wrap; gap:12px;">
        <h1 class="rf-serif" style="font-size:26px;">Ruang</h1>
        @if (! $showAddForm)
            <button type="button" wire:click="$set('showAddForm', true)" class="rf-btn primary">+ Ruang baru</button>
        @endif
    </div>

    @if ($rooms->isNotEmpty() && $allocationTotal !== 100)
        <div class="rf-card" role="status" style="font-size:13px; color:var(--rf-on-surface-variant);">
            Total alokasi rezeki ruang aktif sekarang {{ $allocationTotal }}%, belum pas 100%. Atur ulang persentase tiap ruang supaya rezeki yang masuk terbagi semuanya.
        </div>
    @endif

    @if ($showAddForm)
        <form wire:submit="addRoom" class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:16px;">Ruang baru</h2>

            <div class="rf-field">
                <label for="room-name">Nama ruang</label>
                <input type="text" id="room-name" wire:model="name" maxlength="40" autofocus>
            </div>

            <div class="rf-field">
                <label for="room-type">Tipe ruang</label>
                <select id="room-type" wire:model="type">
                    <option value="menunaikan">Menunaikan — tersalurkan sampai target (mis. Memberi)</option>
                    <option value="menumbuhkan">Menumbuhkan — dana benar-benar masuk ke tujuan (mis. investasi)</option>
                    <option value="mencukupi">Mencukupi — kebutuhan tertutup tanpa lewat jatah (mis. Keluarga)</option>
                </select>
            </div>

            @if ($error)
                <p class="error-text">{{ $error }}</p>
            @endif

            <div style="display:flex; gap:12px; margin-top:8px;">
                <button type="submit" class="rf-btn primary">Simpan</button>
                <button type="button" wire:click="$set('showAddForm', false)" class="rf-btn tonal">Batal</button>
            </div>
        </form>
    @endif

    @forelse ($rooms as $index => $room)
        @php $color = $roomColors[$room->slot] ?? '#727874'; @endphp
        <div class="rf-card" style="display:flex; align-items:center; gap:16px; flex-wrap:wrap;">
            <div style="width:40px; height:40px; border-radius:11px; background:{{ $color }}; color:#fff; display:flex; align-items:center; justify-content:center; font-weight:700; flex-shrink:0;">{{ mb_substr($room->name, 0, 1) }}</div>

            <div style="flex:1; min-width:180px;">
                <a href="{{ route('ruang.detail', $room) }}" style="font-weight:700; font-size:15px; color:inherit;">{{ $room->name }}</a>
                <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">
                    {{ $typeLabel[$room->type] ?? $room->type }}
                    @if ($room->allocation_percent)
                        · {{ $room->allocation_percent }}% dari alokasi
                    @endif
                </div>
            </div>

            <div style="display:flex; align-items:center; gap:6px;">
                <button type="button" wire:click="moveUp({{ $room->id }})" @disabled($index === 0) class="rf-btn tonal" aria-label="Naikkan urutan {{ $room->name }}" style="padding:8px 12px;">↑</button>
                <button type="button" wire:click="moveDown({{ $room->id }})" @disabled($index === $rooms->count() - 1) class="rf-btn tonal" aria-label="Turunkan urutan {{ $room->name }}" style="padding:8px 12px;">↓</button>
                <button type="button" wire:click="archiveRoom({{ $room->id }})" wire:confirm="Arsipkan {{ $room->name }}? Persennya kembali ke 0% dan perlu diatur ulang, tapi riwayat transaksinya tetap ada." class="rf-btn tonal" style="padding:8px 14px;">Arsipkan</button>
            </div>
        </div>
    @empty
        <div class="rf-card" style="text-align:center; padding:40px 26px;">
            <h2 class="rf-serif" style="font-size:22px; margin-bottom:10px;">Belum ada ruang</h2>
            <p style="font-size:14px; color:var(--rf-on-surface-variant); max-width:440px; margin:0 auto 18px;">
                Ruang adalah tempat rezeki dialirkan sesuai hak masing-masing. Tambah ruang pertama untuk mulai, atau pakai pola Tiga hak (Memberi, Diri, Keluarga) lewat aplikasi Android.
            </p>
        </div>
    @endforelse

    @if ($archivedRooms->isNotEmpty())
        <details class="rf-card">
            <summary style="cursor:pointer; font-weight:700; font-size:14px;">Diarsipkan ({{ $archivedRooms->count() }})</summary>
            <div style="margin-top:16px; display:flex; flex-direction:column; gap:12px;">
                @foreach ($archivedRooms as $room)
                    <div style="display:flex; align-items:center; gap:14px; padding:10px 0; border-top:1px solid var(--rf-outline-variant);">
                        <span style="flex:1; font-size:14px; color:var(--rf-on-surface-variant);">{{ $room->name }}</span>
                        <button type="button" wire:click="restoreRoom({{ $room->id }})" class="rf-btn tonal" style="padding:8px 14px;">Pulihkan</button>
                    </div>
                @endforeach
            </div>
        </details>
    @endif

</div>
