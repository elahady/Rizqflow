@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:22px;">

    <div style="display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap;">
        <h1 class="rf-serif" style="font-size:28px;">Transaksi</h1>
        <div style="display:flex; gap:10px;">
            <a href="{{ route('transaksi.berulang') }}" class="rf-btn tonal">Berulang</a>
            <a href="{{ route('tagihan') }}" class="rf-btn tonal">Tagihan</a>
            <a href="{{ route('utang-piutang') }}" class="rf-btn tonal">Utang-piutang</a>
            <a href="{{ route('transaksi.ekspor') }}" class="rf-btn tonal">Ekspor CSV</a>
        </div>
    </div>

    <div style="display:flex; flex-wrap:wrap; gap:24px;">

        <div style="flex:999 1 560px; min-width:0; display:flex; flex-direction:column; gap:18px;">
            <div class="rf-card" style="padding:8px 22px;">
                @forelse ($transactions as $dateLabel => $items)
                    <div style="font-size:12px; font-weight:700; letter-spacing:0.03em; text-transform:uppercase; color:var(--rf-on-surface-variant); padding:14px 0 8px;">{{ $dateLabel }}</div>
                    @foreach ($items as $t)
                        <a href="{{ route('transaksi.detail', $t) }}" style="display:flex; align-items:flex-start; gap:14px; padding:13px 0; border-bottom:1px solid var(--rf-outline-variant); color:inherit; text-decoration:none;">
                            <div style="flex:1; min-width:0;">
                                <div style="font-weight:600; font-size:14px;">{{ $t->note ?? $t->category }}</div>
                                <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">
                                    {{ $t->room?->name ?? ($t->type === 'pemasukan' ? 'Belum dialirkan' : '') }}
                                    @if ($t->category) · {{ $t->category }} @endif
                                    @if ($t->account) · {{ $t->account->name }} @endif
                                </div>
                            </div>
                            <div style="text-align:right; flex-shrink:0; font-weight:700; font-size:15px; {{ $t->type === 'pemasukan' ? 'color:#275c3f;' : '' }}">
                                {{ $t->type === 'pengeluaran' ? '−' : ($t->type === 'pemasukan' ? '+' : '') }} {{ Rupiah::format($t->amount_minor) }}
                            </div>
                        </a>
                    @endforeach
                @empty
                    <p style="padding:20px 0; color:var(--rf-on-surface-variant); font-size:14px;">Belum ada transaksi.</p>
                @endforelse
            </div>
        </div>

        <div style="flex:1 1 320px; min-width:300px;">
            <div class="rf-card">
                <h2 class="rf-serif" style="font-size:19px; margin-bottom:16px;">Catat transaksi</h2>
                <div style="display:flex; gap:8px; margin-bottom:18px;">
                    <button type="button" class="rf-btn tonal" style="flex:1; padding:8px; font-size:13px;" disabled>Pengeluaran</button>
                </div>
                <div class="rf-field">
                    <label>Ruang</label>
                    <select disabled>
                        @foreach ($rooms as $room)
                            <option>{{ $room->name }}</option>
                        @endforeach
                    </select>
                </div>
                <div class="rf-field"><label>Nominal</label><input type="text" placeholder="Rp 0" disabled></div>
                <div class="rf-field"><label>Catatan</label><input type="text" placeholder="Belanja pasar" disabled></div>
                <button type="button" class="rf-btn primary" style="width:100%;" disabled>Simpan</button>
                <p style="font-size:12px; color:var(--rf-outline); margin-top:12px;">Formulir ini tampilan saja (scaffold). Pencatatan sungguhan menyusul.</p>
            </div>
        </div>

    </div>
</div>
