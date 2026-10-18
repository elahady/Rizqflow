@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:720px; margin:0 auto; width:100%;">

    <div><a href="{{ route('transaksi') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Transaksi</a></div>

    <div style="display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap;">
        <h1 class="rf-serif" style="font-size:26px;">Transaksi berulang</h1>
        @if (! $showForm)
            <button type="button" wire:click="startAdd" class="rf-btn primary">+ Aturan baru</button>
        @endif
    </div>

    @if ($showForm)
        <form wire:submit="save" class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:14px;">{{ $editingId ? 'Ubah aturan' : 'Aturan baru' }}</h2>

            @if (! $editingId)
                <div style="display:flex; gap:8px; margin-bottom:14px;">
                    <button type="button" wire:click="$set('type', 'pengeluaran')" class="rf-btn {{ $type === 'pengeluaran' ? 'primary' : 'tonal' }}" style="flex:1;">Pengeluaran</button>
                    <button type="button" wire:click="$set('type', 'pemasukan')" class="rf-btn {{ $type === 'pemasukan' ? 'primary' : 'tonal' }}" style="flex:1;">Pemasukan</button>
                </div>
            @endif

            <div class="rf-field">
                <label for="rec-amount">Nominal</label>
                <input type="number" id="rec-amount" wire:model="amount" min="1" autofocus>
            </div>

            @if ($type === 'pengeluaran')
                <div class="rf-field">
                    <label for="rec-room">Ruang</label>
                    <select id="rec-room" wire:model.live="roomId">
                        @foreach ($rooms as $room)
                            <option value="{{ $room->id }}">{{ $room->name }}</option>
                        @endforeach
                    </select>
                </div>
                <div class="rf-field">
                    <label for="rec-category">Kategori</label>
                    <select id="rec-category" wire:model="categoryId">
                        @foreach ($categories as $category)
                            <option value="{{ $category->id }}">{{ $category->name }}</option>
                        @endforeach
                    </select>
                </div>
            @else
                <div class="rf-field">
                    <label for="rec-source">Sumber</label>
                    <input type="text" id="rec-source" wire:model="source" maxlength="40" placeholder="Gaji, Usaha, Lainnya">
                </div>
            @endif

            <div class="rf-field">
                <label for="rec-account">Akun</label>
                <select id="rec-account" wire:model="accountId">
                    @foreach ($accounts as $account)
                        <option value="{{ $account->id }}">{{ $account->name }}</option>
                    @endforeach
                </select>
            </div>

            <div class="rf-field">
                <label for="rec-frequency">Frekuensi</label>
                <select id="rec-frequency" wire:model="frequency">
                    <option value="harian">Harian</option>
                    <option value="mingguan">Mingguan</option>
                    <option value="bulanan">Bulanan</option>
                </select>
            </div>

            <div style="display:flex; gap:12px;">
                <div class="rf-field" style="flex:1;">
                    <label for="rec-start">Tanggal mulai</label>
                    <input type="date" id="rec-start" wire:model="startDate">
                </div>
                <div class="rf-field" style="flex:1;">
                    <label for="rec-end">Tanggal akhir (opsional)</label>
                    <input type="date" id="rec-end" wire:model="endDate">
                </div>
            </div>

            <div class="rf-field">
                <label for="rec-note">Catatan</label>
                <input type="text" id="rec-note" wire:model="note" maxlength="200">
            </div>

            @if ($error)
                <p class="error-text">{{ $error }}</p>
            @endif

            <div style="display:flex; gap:12px; margin-top:12px;">
                <button type="submit" class="rf-btn primary">Simpan</button>
                <button type="button" wire:click="cancelForm" class="rf-btn tonal">Batal</button>
            </div>
        </form>
    @endif

    @forelse ($rules as $rule)
        @php
            $frequencyLabel = ['harian' => 'Harian', 'mingguan' => 'Mingguan', 'bulanan' => 'Bulanan'][$rule->frequency] ?? $rule->frequency;
            $tujuan = $rule->type === 'pengeluaran' ? ($rule->room?->name.' · '.$rule->category?->name) : ($rule->source ?: 'Pemasukan');
        @endphp
        <div class="rf-card" style="display:flex; align-items:center; gap:14px; flex-wrap:wrap; {{ ! $rule->active || $rule->isFinished() ? 'opacity:0.6;' : '' }}">
            <div style="flex:1; min-width:200px;">
                <div style="font-weight:700; font-size:14px;">{{ $rule->note ?: $tujuan }}</div>
                <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">
                    {{ $tujuan }} · {{ $rule->account->name }} · {{ $frequencyLabel }}
                </div>
                @if (! $rule->isUsable())
                    <div style="font-size:12px; color:var(--rf-status-warning); margin-top:4px;">Tertahan — akun, ruang, atau kategorinya sudah diarsipkan.</div>
                @elseif ($rule->isFinished())
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:4px;">Selesai {{ $rule->end_date->translatedFormat('j F Y') }}</div>
                @elseif (! $rule->active)
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:4px;">Dijeda</div>
                @else
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:4px;">Berikutnya {{ $rule->next_due->translatedFormat('j F Y') }}</div>
                @endif
            </div>
            <div style="text-align:right; font-weight:700; font-size:15px; {{ $rule->type === 'pemasukan' ? 'color:#275c3f;' : '' }}">
                {{ $rule->type === 'pengeluaran' ? '−' : '+' }} {{ Rupiah::format($rule->amount_minor) }}
            </div>
            <div style="display:flex; gap:8px;">
                <button type="button" wire:click="startEdit({{ $rule->id }})" class="rf-btn tonal" style="padding:8px 14px;">Ubah</button>
                @if ($rule->active)
                    <button type="button" wire:click="pause({{ $rule->id }})" class="rf-btn tonal" style="padding:8px 14px;">Jeda</button>
                @else
                    <button type="button" wire:click="resume({{ $rule->id }})" class="rf-btn tonal" style="padding:8px 14px;">Lanjutkan</button>
                @endif
                <button type="button" wire:click="delete({{ $rule->id }})" wire:confirm="Hapus aturan ini? Transaksi yang sudah tercatat tetap ada." class="rf-btn tonal" style="padding:8px 14px;">Hapus</button>
            </div>
        </div>
    @empty
        @if (! $showForm)
            <div class="rf-card">
                <p style="color:var(--rf-on-surface-variant); font-size:14px;">Belum ada aturan berulang. Cocok untuk gaji bulanan, tagihan, atau tabungan rutin.</p>
            </div>
        @endif
    @endforelse

</div>
