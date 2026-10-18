@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:760px; margin:0 auto; width:100%;">

    <div><a href="{{ route('transaksi') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Transaksi</a></div>

    <div style="display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap;">
        <h1 class="rf-serif" style="font-size:26px;">Utang-piutang</h1>
        @if (! $showForm)
            <button type="button" wire:click="startAdd" class="rf-btn primary">+ Catat baru</button>
        @endif
    </div>

    <div style="display:flex; gap:16px; flex-wrap:wrap;">
        <div class="rf-card" style="flex:1; min-width:200px;">
            <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Sisa piutang</div>
            <div style="font-weight:700; font-size:20px;">{{ Rupiah::format($receivable) }}</div>
        </div>
        <div class="rf-card" style="flex:1; min-width:200px;">
            <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Sisa utang</div>
            <div style="font-weight:700; font-size:20px;">{{ Rupiah::format($payable) }}</div>
        </div>
    </div>

    @if ($showForm)
        <form wire:submit="save" class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:14px;">Catat utang-piutang baru</h2>

            <div style="display:flex; gap:8px; margin-bottom:14px;">
                <button type="button" wire:click="$set('direction', 'piutang')" class="rf-btn {{ $direction === 'piutang' ? 'primary' : 'tonal' }}" style="flex:1;">Piutang (saya meminjamkan)</button>
                <button type="button" wire:click="$set('direction', 'utang')" class="rf-btn {{ $direction === 'utang' ? 'primary' : 'tonal' }}" style="flex:1;">Utang (saya meminjam)</button>
            </div>

            <div class="rf-field">
                <label for="debt-party">Nama pihak</label>
                <input type="text" id="debt-party" wire:model="party" maxlength="40" autofocus>
            </div>
            <div class="rf-field">
                <label for="debt-amount">Nominal</label>
                <input type="number" id="debt-amount" wire:model="amount" min="1">
            </div>
            <div style="display:flex; gap:12px;">
                <div class="rf-field" style="flex:1;">
                    <label for="debt-start">Tanggal pinjam</label>
                    <input type="date" id="debt-start" wire:model="startDate">
                </div>
                <div class="rf-field" style="flex:1;">
                    <label for="debt-due">Jatuh tempo (opsional)</label>
                    <input type="date" id="debt-due" wire:model="dueDate">
                </div>
            </div>

            <label style="display:flex; align-items:center; gap:10px; font-size:13.5px; margin:6px 0;">
                <input type="checkbox" wire:model.live="recordMovement"> Ubah saldo akun sekarang
            </label>

            @if ($recordMovement)
                <div class="rf-field">
                    <label for="debt-account">Akun</label>
                    <select id="debt-account" wire:model="accountId">
                        @foreach ($accounts as $account)
                            <option value="{{ $account->id }}">{{ $account->name }}</option>
                        @endforeach
                    </select>
                </div>
            @endif

            @if ($direction === 'piutang')
                <label style="display:flex; align-items:center; gap:10px; font-size:13.5px; margin:6px 0;">
                    <input type="checkbox" wire:model="collectible"> Diperkirakan kembali
                </label>
            @endif

            <div class="rf-field">
                <label for="debt-note">Catatan</label>
                <input type="text" id="debt-note" wire:model="note" maxlength="200">
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
        $statusLabel = ['terlambat' => 'Terlambat', 'hari_ini' => 'Hari ini', 'segera' => 'Segera', 'berjalan' => 'Berjalan', 'lunas' => 'Lunas'];
        $statusTone = ['terlambat' => 'warn', 'hari_ini' => 'warn', 'segera' => 'neutral', 'berjalan' => 'neutral', 'lunas' => 'good'];
    @endphp

    @forelse ($debts as $debt)
        @php $status = $debt->statusOn($today); @endphp
        <div class="rf-card" style="display:flex; flex-direction:column; gap:12px;">
            <div style="display:flex; align-items:center; gap:14px; flex-wrap:wrap; cursor:pointer;" wire:click="toggleDetail({{ $debt->id }})">
                <div style="flex:1; min-width:200px;">
                    <div style="font-weight:700; font-size:14px;">{{ $debt->party }}</div>
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-top:2px;">
                        {{ $debt->direction === 'piutang' ? 'Piutang' : 'Utang' }} · {{ $debt->due_date ? $debt->due_date->translatedFormat('j F Y') : 'Tanpa jatuh tempo' }}
                    </div>
                </div>
                <span class="rf-pill {{ $statusTone[$status] }}">{{ $statusLabel[$status] }}</span>
                <div style="text-align:right; min-width:120px;">
                    <div style="font-weight:700; font-size:15px;">{{ Rupiah::format($debt->outstandingMinor()) }}</div>
                    <div style="font-size:11px; color:var(--rf-on-surface-variant);">dari {{ Rupiah::format($debt->principal_minor) }}</div>
                </div>
                @if (! $debt->isSettled())
                    <button type="button" wire:click.stop="startRepay({{ $debt->id }})" class="rf-btn primary" style="padding:8px 14px;">{{ $debt->direction === 'piutang' ? 'Terima' : 'Bayar' }}</button>
                @endif
            </div>

            @if ($payingId === $debt->id)
                <form wire:submit="repay" style="border-top:1px solid var(--rf-outline-variant); padding-top:14px; display:flex; flex-direction:column; gap:12px;">
                    <div class="rf-field" style="margin:0;">
                        <label for="pay-amount-{{ $debt->id }}">Nominal</label>
                        <input type="number" id="pay-amount-{{ $debt->id }}" wire:model="payAmount" min="1" autofocus>
                    </div>
                    <label style="display:flex; align-items:center; gap:10px; font-size:13.5px;">
                        <input type="checkbox" wire:model.live="payUpdateBalance"> Ubah saldo akun
                    </label>
                    @if ($payUpdateBalance)
                        <div class="rf-field" style="margin:0;">
                            <label for="pay-account-{{ $debt->id }}">Akun</label>
                            <select id="pay-account-{{ $debt->id }}" wire:model="payAccountId">
                                @foreach ($accounts as $account)
                                    <option value="{{ $account->id }}">{{ $account->name }}</option>
                                @endforeach
                            </select>
                        </div>
                    @endif
                    @if ($payError)
                        <p class="error-text">{{ $payError }}</p>
                    @endif
                    <div style="display:flex; gap:12px;">
                        <button type="submit" class="rf-btn primary">{{ $debt->direction === 'piutang' ? 'Konfirmasi terima' : 'Konfirmasi bayar' }}</button>
                        <button type="button" wire:click="cancelRepay" class="rf-btn tonal">Batal</button>
                    </div>
                </form>
            @endif

            @if ($expandedId === $debt->id)
                <div style="border-top:1px solid var(--rf-outline-variant); padding-top:14px; display:flex; flex-direction:column; gap:14px;">
                    <div style="font-size:13px; color:var(--rf-on-surface-variant);">
                        Akun: {{ $debt->account?->name ?? 'Saldo akun tidak diubah' }}
                        @if ($debt->direction === 'piutang') · {{ $debt->collectible ? 'Diperkirakan kembali' : 'Diperkirakan tidak kembali' }} @endif
                        @if ($debt->note) · {{ $debt->note }} @endif
                    </div>

                    @if ($editingId === $debt->id)
                        <form wire:submit="updateDebt" style="display:flex; flex-direction:column; gap:4px;">
                            <div class="rf-field">
                                <label for="edit-party-{{ $debt->id }}">Nama pihak</label>
                                <input type="text" id="edit-party-{{ $debt->id }}" wire:model="editParty" maxlength="40">
                            </div>
                            <div class="rf-field">
                                <label for="edit-due-{{ $debt->id }}">Jatuh tempo (opsional)</label>
                                <input type="date" id="edit-due-{{ $debt->id }}" wire:model="editDueDate">
                            </div>
                            @if ($debt->direction === 'piutang')
                                <label style="display:flex; align-items:center; gap:10px; font-size:13.5px; margin:6px 0;">
                                    <input type="checkbox" wire:model="editCollectible"> Diperkirakan kembali
                                </label>
                            @endif
                            <div class="rf-field">
                                <label for="edit-note-{{ $debt->id }}">Catatan</label>
                                <input type="text" id="edit-note-{{ $debt->id }}" wire:model="editNote" maxlength="200">
                            </div>
                            @if ($editError)
                                <p class="error-text">{{ $editError }}</p>
                            @endif
                            <div style="display:flex; gap:12px; margin-top:8px;">
                                <button type="submit" class="rf-btn primary">Simpan</button>
                                <button type="button" wire:click="cancelEditDebt" class="rf-btn tonal">Batal</button>
                            </div>
                        </form>
                    @else
                        <div style="display:flex; gap:10px;">
                            <button type="button" wire:click="startEditDebt({{ $debt->id }})" class="rf-btn tonal" style="padding:8px 14px;">Ubah</button>
                            <button type="button" wire:click="deleteDebt({{ $debt->id }})" wire:confirm="Hapus utang-piutang ini? Saldo akun akan kembali seperti sebelum dicatat." class="rf-btn tonal" style="padding:8px 14px;">Hapus</button>
                        </div>
                    @endif

                    <div>
                        <div style="font-size:12px; font-weight:700; text-transform:uppercase; letter-spacing:0.03em; color:var(--rf-on-surface-variant); margin-bottom:8px;">Riwayat pelunasan</div>
                        @forelse ($debt->payments as $payment)
                            <div style="display:flex; align-items:center; justify-content:space-between; gap:10px; padding:8px 0; border-bottom:1px solid var(--rf-outline-variant); font-size:13px;">
                                <span>{{ $payment->paid_on->translatedFormat('j M Y') }} · {{ Rupiah::format($payment->amount_minor) }} @if ($payment->account) · {{ $payment->account->name }} @endif</span>
                                <button type="button" wire:click="deletePayment({{ $payment->id }})" wire:confirm="Hapus pelunasan ini? Saldo akun akan kembali." class="rf-btn tonal" style="padding:6px 10px; font-size:12px;">Hapus</button>
                            </div>
                        @empty
                            <p style="font-size:13px; color:var(--rf-on-surface-variant);">Belum ada pelunasan.</p>
                        @endforelse
                    </div>
                </div>
            @endif
        </div>
    @empty
        @if (! $showForm)
            <div class="rf-card">
                <p style="color:var(--rf-on-surface-variant); font-size:14px;">Belum ada utang-piutang. Saldo akun bergeser di sini tanpa memakan jatah ruang.</p>
            </div>
        @endif
    @endforelse

</div>
