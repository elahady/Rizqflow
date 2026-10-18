@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:720px; margin:0 auto; width:100%;">

    <div><a href="{{ route('transaksi') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Transaksi</a></div>

    <div style="display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap;">
        <h1 class="rf-serif" style="font-size:26px;">Tagihan dan cicilan</h1>
        @if (! $showForm)
            <button type="button" wire:click="startAdd" class="rf-btn primary">+ Tagihan baru</button>
        @endif
    </div>

    @if ($showForm)
        <form wire:submit="save" class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:14px;">{{ $editingId ? 'Ubah tagihan' : 'Tagihan baru' }}</h2>

            <div class="rf-field">
                <label for="bill-name">Nama</label>
                <input type="text" id="bill-name" wire:model="name" maxlength="40" placeholder="Listrik, Cicilan motor" autofocus>
            </div>
            <div class="rf-field">
                <label for="bill-amount">Nominal</label>
                <input type="number" id="bill-amount" wire:model="amount" min="1">
            </div>
            <div class="rf-field">
                <label for="bill-room">Ruang</label>
                <select id="bill-room" wire:model.live="roomId">
                    @foreach ($rooms as $room)
                        <option value="{{ $room->id }}">{{ $room->name }}</option>
                    @endforeach
                </select>
            </div>
            <div class="rf-field">
                <label for="bill-category">Kategori</label>
                <select id="bill-category" wire:model="categoryId">
                    @foreach ($categories as $category)
                        <option value="{{ $category->id }}">{{ $category->name }}</option>
                    @endforeach
                </select>
            </div>
            <div class="rf-field">
                <label for="bill-account">Akun</label>
                <select id="bill-account" wire:model="accountId">
                    @foreach ($accounts as $account)
                        <option value="{{ $account->id }}">{{ $account->name }}</option>
                    @endforeach
                </select>
            </div>
            <div class="rf-field">
                <label for="bill-frequency">Pengulangan</label>
                <select id="bill-frequency" wire:model.live="frequency">
                    <option value="">Sekali bayar</option>
                    <option value="mingguan">Mingguan</option>
                    <option value="bulanan">Bulanan</option>
                </select>
            </div>
            <div class="rf-field">
                <label for="bill-due">Jatuh tempo berikutnya</label>
                <input type="date" id="bill-due" wire:model="dueDate">
            </div>

            @if ($frequency !== '')
                <div style="display:flex; gap:12px;">
                    <div class="rf-field" style="flex:1;">
                        <label for="bill-total">Jumlah cicilan (opsional)</label>
                        <input type="number" id="bill-total" wire:model="totalInstallments" min="1" placeholder="Kosong = tanpa batas">
                    </div>
                    <div class="rf-field" style="flex:1;">
                        <label for="bill-paid">Sudah dibayar sebelumnya</label>
                        <input type="number" id="bill-paid" wire:model="alreadyPaid" min="0">
                    </div>
                </div>
            @endif

            <div class="rf-field">
                <label for="bill-note">Catatan</label>
                <input type="text" id="bill-note" wire:model="note" maxlength="200">
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

    @php
        $statusLabel = ['terlambat' => 'Terlambat', 'hari_ini' => 'Hari ini', 'segera' => 'Segera', 'mendatang' => 'Mendatang', 'dijeda' => 'Dijeda', 'lunas' => 'Lunas'];
        $statusTone = ['terlambat' => 'warn', 'hari_ini' => 'warn', 'segera' => 'neutral', 'mendatang' => 'neutral', 'dijeda' => 'neutral', 'lunas' => 'good'];
    @endphp

    @forelse ($bills as $bill)
        @php $status = $bill->statusOn($today); @endphp
        <div class="rf-card" style="display:flex; flex-direction:column; gap:12px;">
            <div style="display:flex; align-items:center; gap:14px; flex-wrap:wrap;">
                <div style="flex:1; min-width:200px;">
                    <div style="font-weight:700; font-size:14px;">{{ $bill->name }}</div>
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">
                        {{ $bill->room->name }} · {{ $bill->category->name }} · {{ $bill->account->name }}
                        @if ($bill->total_installments)
                            · Cicilan ke-{{ min($bill->paid_count + 1, $bill->total_installments) }} dari {{ $bill->total_installments }}
                        @endif
                    </div>
                    @if (! $bill->isUsable())
                        <div style="font-size:12px; color:var(--rf-status-warning); margin-top:4px;">Akun, ruang, atau kategorinya sudah diarsipkan — Bayar nonaktif.</div>
                    @endif
                </div>
                <span class="rf-pill {{ $statusTone[$status] }}">{{ $statusLabel[$status] }}</span>
                <div style="text-align:right; font-weight:700; font-size:15px; min-width:110px;">{{ Rupiah::format($bill->amount_minor) }}</div>
            </div>
            <div style="display:flex; align-items:center; justify-content:space-between; gap:8px; flex-wrap:wrap;">
                <div style="font-size:12px; color:var(--rf-on-surface-variant);">
                    @if (! $bill->isFinished())
                        Jatuh tempo {{ $bill->next_due->translatedFormat('j F Y') }}
                    @endif
                </div>
                <div style="display:flex; gap:8px;">
                    @if (! $bill->isFinished())
                        <button type="button" wire:click="startPay({{ $bill->id }})" class="rf-btn primary" style="padding:8px 14px;" {{ ! $bill->isUsable() ? 'disabled' : '' }}>Bayar</button>
                    @endif
                    <button type="button" wire:click="startEdit({{ $bill->id }})" class="rf-btn tonal" style="padding:8px 14px;">Ubah</button>
                    @if ($bill->active)
                        <button type="button" wire:click="pause({{ $bill->id }})" class="rf-btn tonal" style="padding:8px 14px;">Jeda</button>
                    @else
                        <button type="button" wire:click="resume({{ $bill->id }})" class="rf-btn tonal" style="padding:8px 14px;">Lanjutkan</button>
                    @endif
                    <button type="button" wire:click="delete({{ $bill->id }})" wire:confirm="Hapus tagihan ini? Pengeluaran yang sudah tercatat tetap ada." class="rf-btn tonal" style="padding:8px 14px;">Hapus</button>
                </div>
            </div>

            @if ($payingId === $bill->id)
                <form wire:submit="pay" style="border-top:1px solid var(--rf-outline-variant); padding-top:14px; display:flex; gap:12px; align-items:flex-end; flex-wrap:wrap;">
                    <div class="rf-field" style="flex:1; min-width:160px; margin:0;">
                        <label for="pay-amount-{{ $bill->id }}">Nominal dibayar</label>
                        <input type="number" id="pay-amount-{{ $bill->id }}" wire:model="payAmount" min="1" autofocus>
                    </div>
                    <button type="submit" class="rf-btn primary">Konfirmasi bayar</button>
                    <button type="button" wire:click="cancelPay" class="rf-btn tonal">Batal</button>
                </form>
                @if ($payError)
                    <p class="error-text">{{ $payError }}</p>
                @endif
            @endif
        </div>
    @empty
        @if (! $showForm)
            <div class="rf-card">
                <p style="color:var(--rf-on-surface-variant); font-size:14px;">Belum ada tagihan. Pengeluaran baru tercatat saat Bayar diketuk.</p>
            </div>
        @endif
    @endforelse

</div>
