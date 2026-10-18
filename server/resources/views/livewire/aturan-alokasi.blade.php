@php
    $roomColors = [1 => '#3b6fa8', 2 => '#c4653a', 3 => '#2a9a80', 4 => '#af6dec', 5 => '#a04177', 6 => '#099acf', 7 => '#998214', 8 => '#de4f74'];
@endphp

<div style="display:flex; flex-direction:column; gap:28px; max-width:560px; margin:0 auto; width:100%;">

    <div><a href="{{ route('ruang') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Ruang</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Aturan alokasi</h1>

    @if ($editableRooms->isEmpty() && ! $lastRoom)
        <div class="rf-card" style="text-align:center; padding:40px 26px;">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">Belum ada ruang untuk diatur alokasinya.</p>
            <a href="{{ route('ruang') }}" class="rf-btn primary" style="margin-top:16px; display:inline-flex;">Tambah ruang</a>
        </div>
    @elseif ($editableRooms->isEmpty())
        <div class="rf-card">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">
                <strong style="color:var(--rf-on-background);">{{ $lastRoom->name }}</strong> satu-satunya ruang aktif, jadi otomatis menerima seluruh rezeki yang masuk (100%). Tambah ruang lain dulu di halaman Ruang untuk membagi persentase.
            </p>
        </div>
    @else
        <form wire:submit="save" class="rf-card">
            <p style="font-size:13px; color:var(--rf-on-surface-variant); margin-bottom:18px;">
                Tiap ruang mendapat persentase dari rezeki yang masuk. Berlaku untuk pemasukan berikutnya; transaksi yang sudah tercatat tidak berubah.
            </p>

            @foreach ($editableRooms as $room)
                @php $color = $roomColors[$room->slot] ?? '#727874'; @endphp
                <div class="rf-field" style="display:flex; align-items:center; gap:12px; margin-bottom:14px;">
                    <span style="width:10px; height:10px; border-radius:3px; background:{{ $color }}; flex-shrink:0;" aria-hidden="true"></span>
                    <label for="percent-{{ $room->id }}" style="flex:1; margin:0; font-size:14px; font-weight:600; color:var(--rf-on-background); text-transform:none;">{{ $room->name }}</label>
                    <div style="display:flex; align-items:center; gap:6px;">
                        <input type="number" id="percent-{{ $room->id }}" wire:model.live.debounce.300ms="percents.{{ $room->id }}" min="0" max="100" style="width:72px; text-align:right;">
                        <span style="font-size:14px; color:var(--rf-on-surface-variant);">%</span>
                    </div>
                </div>
            @endforeach

            @if ($lastRoom)
                <div style="display:flex; align-items:center; gap:12px; padding:10px 0; border-top:1px solid var(--rf-outline-variant); margin-top:4px;">
                    @php $color = $roomColors[$lastRoom->slot] ?? '#727874'; @endphp
                    <span style="width:10px; height:10px; border-radius:3px; background:{{ $color }};" aria-hidden="true"></span>
                    <span style="flex:1; font-size:14px; font-weight:600;">{{ $lastRoom->name }}</span>
                    <span style="font-size:13px; color:var(--rf-on-surface-variant);">Sisa, otomatis</span>
                    <span style="font-weight:700; font-size:14px; min-width:44px; text-align:right;">{{ $remaining }}%</span>
                </div>
            @endif

            @if ($remaining < 0)
                <p class="error-text">Total melebihi 100% — kurangi salah satu ruang di atas.</p>
            @endif

            @if ($error)
                <p class="error-text">{{ $error }}</p>
            @endif

            <button type="submit" class="rf-btn primary" style="margin-top:18px;" @disabled($remaining < 0 || ! $isDirty)>Simpan</button>
        </form>
    @endif

</div>
