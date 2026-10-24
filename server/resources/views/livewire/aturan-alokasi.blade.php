@push('styles')
<style>
    /* Aturan alokasi: baris ruang dengan isian di kanan. */
    .al-wrap { max-width: 640px; }
    .al-row { display: flex; align-items: center; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--rf-surface-container-high); }
    .al-row:last-of-type { border-bottom: 0; }
    .al-row label, .al-row .al-name { flex: 1 1 0; min-width: 0; margin: 0; font-size: 14px; font-weight: 700; color: var(--rf-on-background); }
    .al-row .rf-input { width: 96px; text-align: right; }
    .al-row .rf-input.wide { width: 150px; }
    .al-unit { font-size: 14px; color: var(--rf-on-surface-variant); }
    .al-last { border-top: 1px solid var(--rf-outline-variant); }
</style>
@endpush

<div class="rf-page al-wrap">

    <x-rf.page-head title="Aturan alokasi" lead="Cara rezeki yang masuk dibagi ke ruang-ruangmu.">
        <x-slot:sub><a class="rf-link" href="{{ route('ruang') }}">← Kembali ke Ruang</a></x-slot:sub>
    </x-rf.page-head>

    <div class="rf-seg" role="group" aria-label="Mode aturan" style="align-self:flex-start; flex-wrap:wrap">
        <button type="button" wire:click="switchMode('persentase')" @class(['rf-seg-btn', 'on' => $mode === 'persentase']) aria-pressed="{{ $mode === 'persentase' ? 'true' : 'false' }}">Persentase</button>
        <button type="button" wire:click="switchMode('lanjutan')" @class(['rf-seg-btn', 'on' => $mode === 'lanjutan']) aria-pressed="{{ $mode === 'lanjutan' ? 'true' : 'false' }}">
            Prioritas dan batas atas @unless ($canUseAdvanced) (Pro) @endunless
        </button>
    </div>

    @if ($editableRooms->isEmpty() && ! $lastRoom)
        <section class="rf-card">
            <div class="rf-empty">
                <x-rf.icon name="grid" />
                <b>Belum ada ruang untuk diatur alokasinya.</b>
                <div class="rf-actions"><a href="{{ route('ruang') }}" class="rf-btn primary sm">Tambah ruang</a></div>
            </div>
        </section>
    @elseif ($editableRooms->isEmpty())
        <x-rf.banner tone="info">
            <strong>{{ $lastRoom->name }}</strong> satu-satunya ruang aktif, jadi otomatis menerima seluruh rezeki yang masuk (100%). Tambah ruang lain dulu di halaman Ruang untuk membagi persentase.
        </x-rf.banner>
    @elseif ($mode === 'persentase')
        <form wire:submit="save" class="rf-card">
            <p class="rf-sub" style="margin-bottom:16px">
                Tiap ruang mendapat persentase dari rezeki yang masuk. Berlaku untuk pemasukan berikutnya; transaksi yang sudah tercatat tidak berubah.
            </p>

            @if ($preview !== [])
                <div style="margin-bottom:12px"><x-rf.stack :segments="$preview" label="Pratinjau pembagian" /></div>
            @endif

            @foreach ($editableRooms as $room)
                <div class="al-row">
                    <i class="rf-dot c{{ $room->slot >= 1 && $room->slot <= 8 ? $room->slot : 'n' }}" aria-hidden="true"></i>
                    <label for="percent-{{ $room->id }}">{{ $room->name }}</label>
                    <input type="number" id="percent-{{ $room->id }}" class="rf-input" wire:model.live.debounce.300ms="percents.{{ $room->id }}" min="0" max="100">
                    <span class="al-unit">%</span>
                </div>
            @endforeach

            @if ($lastRoom)
                <div class="al-row al-last">
                    <i class="rf-dot c{{ $lastRoom->slot >= 1 && $lastRoom->slot <= 8 ? $lastRoom->slot : 'n' }}" aria-hidden="true"></i>
                    <span class="al-name">{{ $lastRoom->name }}</span>
                    <span class="rf-sub">Sisa, otomatis</span>
                    <span class="rf-money" style="min-width:44px; text-align:right">{{ $remaining }}%</span>
                </div>
            @endif

            @if ($remaining < 0)
                <p class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />Total melebihi 100% — kurangi salah satu ruang di atas.</p>
            @endif

            @if ($error)
                <p class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $error }}</p>
            @endif

            <div class="rf-actions" style="margin-top:18px">
                <button type="submit" class="rf-btn primary" @disabled($remaining < 0 || ! $isDirty)>Simpan</button>
            </div>
        </form>
    @else
        <form wire:submit="save" class="rf-card">
            <p class="rf-sub" style="margin-bottom:16px">
                Ruang diisi berurutan menurut prioritas (urutan di halaman Ruang) sampai batas atasnya, lalu kelebihannya mengalir ke ruang berikutnya. Berlaku untuk pemasukan berikutnya; transaksi yang sudah tercatat tidak berubah.
            </p>

            @foreach ($editableRooms as $room)
                <div class="al-row">
                    <i class="rf-dot c{{ $room->slot >= 1 && $room->slot <= 8 ? $room->slot : 'n' }}" aria-hidden="true"></i>
                    <label for="cap-{{ $room->id }}">{{ $room->name }}</label>
                    <span class="al-unit">Rp</span>
                    <input type="number" id="cap-{{ $room->id }}" class="rf-input wide" wire:model.live.debounce.300ms="caps.{{ $room->id }}" min="0" placeholder="Tak terbatas">
                </div>
            @endforeach

            @if ($lastRoom)
                <div class="al-row al-last">
                    <i class="rf-dot c{{ $lastRoom->slot >= 1 && $lastRoom->slot <= 8 ? $lastRoom->slot : 'n' }}" aria-hidden="true"></i>
                    <span class="al-name">{{ $lastRoom->name }}</span>
                    <span class="rf-sub">Sisa, tak terbatas</span>
                </div>
            @endif

            @if ($error)
                <p class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $error }}</p>
            @endif

            <div class="rf-actions" style="margin-top:18px">
                <button type="submit" class="rf-btn primary" @disabled(! $isDirty)>Simpan</button>
            </div>
        </form>
    @endif

</div>
