@php
    use App\Support\Rupiah;

    $isExpense = $transaction->type === 'pengeluaran';
@endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:560px; margin:0 auto; width:100%;">

    <div><a href="{{ route('transaksi') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Transaksi</a></div>

    <div class="rf-card">
        <div style="display:flex; align-items:baseline; justify-content:space-between; gap:12px; margin-bottom:18px;">
            <h1 class="rf-serif" style="font-size:22px;">{{ $isExpense ? 'Pengeluaran' : 'Pemasukan' }}</h1>
            <span style="font-weight:700; font-size:20px; {{ ! $isExpense ? 'color:#275c3f;' : '' }}">
                {{ $isExpense ? '−' : '+' }} {{ Rupiah::format($transaction->amount_minor) }}
            </span>
        </div>

        @if (! $editing)
            <div style="display:flex; flex-direction:column; gap:12px; font-size:14px;">
                @if ($isExpense)
                    <div><span style="color:var(--rf-on-surface-variant);">Ruang</span><br>{{ $transaction->room?->name ?? '—' }}</div>
                    <div><span style="color:var(--rf-on-surface-variant);">Kategori</span><br>{{ $transaction->category ?? '—' }}</div>
                @else
                    <div><span style="color:var(--rf-on-surface-variant);">Sumber</span><br>{{ $transaction->category ?? '—' }}</div>
                @endif
                <div><span style="color:var(--rf-on-surface-variant);">Akun</span><br>{{ $transaction->account?->name ?? '—' }}</div>
                <div><span style="color:var(--rf-on-surface-variant);">Tanggal</span><br>{{ $transaction->occurred_on->translatedFormat('j F Y') }}</div>
                <div><span style="color:var(--rf-on-surface-variant);">Catatan</span><br>{{ $transaction->note ?? '—' }}</div>
            </div>

            <div style="display:flex; gap:12px; margin-top:22px;">
                <button type="button" wire:click="startEdit" class="rf-btn primary">Ubah</button>
                <button type="button" wire:click="delete" wire:confirm="Hapus transaksi ini? Saldo akun akan disesuaikan." class="rf-btn tonal">Hapus</button>
            </div>
        @else
            <form wire:submit="save" style="display:flex; flex-direction:column; gap:4px;">
                <div class="rf-field">
                    <label for="tx-amount">Nominal</label>
                    <input type="number" id="tx-amount" wire:model="amount" min="1" autofocus>
                </div>

                @if ($isExpense)
                    <div class="rf-field">
                        <label for="tx-room">Ruang</label>
                        <select id="tx-room" wire:model.live="roomId">
                            @foreach ($rooms as $room)
                                <option value="{{ $room->id }}">{{ $room->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    <div class="rf-field">
                        <label for="tx-category">Kategori</label>
                        <select id="tx-category" wire:model="categoryId">
                            @foreach ($categories as $category)
                                <option value="{{ $category->id }}">{{ $category->name }}</option>
                            @endforeach
                        </select>
                    </div>
                @else
                    <div class="rf-field">
                        <label for="tx-source">Sumber</label>
                        <input type="text" id="tx-source" wire:model="source" maxlength="40" placeholder="Gaji, Usaha, Lainnya">
                    </div>
                @endif

                <div class="rf-field">
                    <label for="tx-account">Akun</label>
                    <select id="tx-account" wire:model="accountId">
                        @foreach ($accounts as $account)
                            <option value="{{ $account->id }}">{{ $account->name }}</option>
                        @endforeach
                    </select>
                </div>

                <div class="rf-field">
                    <label for="tx-date">Tanggal</label>
                    <input type="date" id="tx-date" wire:model="occurredOn">
                </div>

                <div class="rf-field">
                    <label for="tx-note">Catatan</label>
                    <input type="text" id="tx-note" wire:model="note" maxlength="200">
                </div>

                @if ($error)
                    <p class="error-text">{{ $error }}</p>
                @endif

                <div style="display:flex; gap:12px; margin-top:12px;">
                    <button type="submit" class="rf-btn primary">Simpan</button>
                    <button type="button" wire:click="cancelEdit" class="rf-btn tonal">Batal</button>
                </div>
            </form>
        @endif
    </div>

</div>
