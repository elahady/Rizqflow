@php
    use App\Support\Rupiah;

    $statusLabel = ['terlambat' => 'Terlambat', 'hari_ini' => 'Hari ini', 'segera' => 'Segera', 'berjalan' => 'Berjalan', 'lunas' => 'Lunas'];
    $statusTone = ['terlambat' => 'warn', 'hari_ini' => 'warn', 'segera' => 'warn', 'berjalan' => 'neutral', 'lunas' => 'good'];
    $statusIcon = ['terlambat' => 'warn', 'hari_ini' => 'clock', 'segera' => 'clock', 'berjalan' => 'clock', 'lunas' => 'check'];
@endphp

@push('styles')
<style>
    .up-item { border-bottom: 1px solid var(--rf-surface-container-high); }
    .up-item:last-child { border-bottom: 0; }
    .up-main { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 14px 0; cursor: pointer; }
    .up-main:focus-visible { outline: 2px solid var(--rf-primary); outline-offset: 2px; border-radius: 8px; }
    .up-who { flex: 1 1 200px; min-width: 0; }
    .up-meta { font-size: 12.5px; color: var(--rf-on-surface-variant); margin-top: 2px; }
    .up-amt { text-align: right; min-width: 120px; font-variant-numeric: tabular-nums; }
    .up-amt b { display: block; font-size: 15px; }
    .up-amt small { font-size: 11.5px; color: var(--rf-on-surface-variant); }
    .up-panel { padding: 0 0 16px; display: flex; flex-direction: column; gap: 14px; }
    .up-box { padding: 14px 16px; border-radius: 14px; background: var(--rf-surface-container-low); display: flex; flex-direction: column; gap: 12px; }
    .up-dir { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 16px; }
    .up-dir .rf-btn { justify-content: center; min-width: 0; white-space: normal; text-align: center; }
    .up-sw { display: flex; align-items: center; min-height: 40px; }
    .up-label { font-size: 12px; font-weight: 800; letter-spacing: 0.03em; text-transform: uppercase; color: var(--rf-on-surface-variant); margin-bottom: 4px; }
    .up-pay { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; font-size: 13px; }
    @media (max-width: 600px) {
        .up-dir { grid-template-columns: 1fr; }
        .up-amt { text-align: left; min-width: 0; flex: 1 1 100%; }
    }
</style>
@endpush

<div class="rf-page">
    <x-rf.page-head sub="Saldo akun, di luar jatah ruang" title="Utang-piutang"
        lead="Uang yang kamu pinjamkan atau pinjam. Saldo akun bergeser di sini tanpa memakan jatah ruang mana pun.">
        <x-slot:actions>
            @if (! $showForm)
                <button type="button" wire:click="startAdd" class="rf-btn primary sm"><x-rf.icon name="plus" />Catat baru</button>
            @endif
        </x-slot:actions>
    </x-rf.page-head>

    <div class="rf-stats">
        <div class="rf-stat">
            <div class="l">Sisa piutang</div>
            <div class="v">{{ Rupiah::format($receivable) }}</div>
            <div class="d">Yang masih harus kembali kepadamu</div>
        </div>
        <div class="rf-stat">
            <div class="l">Sisa utang</div>
            <div class="v">{{ Rupiah::format($payable) }}</div>
            <div class="d">Yang masih harus kamu bayar</div>
        </div>
    </div>

    @if ($showForm)
        <form wire:submit="save" class="rf-card">
            <div class="rf-card-head"><h2 class="rf-h2">Catat utang-piutang baru</h2></div>

            <div class="up-dir">
                <button type="button" wire:click="$set('direction', 'piutang')" aria-pressed="{{ $direction === 'piutang' ? 'true' : 'false' }}" class="rf-btn {{ $direction === 'piutang' ? 'primary' : 'tonal' }}">Piutang (saya meminjamkan)</button>
                <button type="button" wire:click="$set('direction', 'utang')" aria-pressed="{{ $direction === 'utang' ? 'true' : 'false' }}" class="rf-btn {{ $direction === 'utang' ? 'primary' : 'tonal' }}">Utang (saya meminjam)</button>
            </div>

            <div class="rf-form-grid">
                <div class="rf-field">
                    <label for="debt-party">Nama pihak</label>
                    <input type="text" id="debt-party" class="rf-input" wire:model="party" maxlength="40" autofocus>
                </div>
                <div class="rf-field">
                    <label for="debt-amount">Nominal</label>
                    <input type="number" id="debt-amount" class="rf-input" wire:model="amount" min="1">
                </div>
                <div class="rf-field">
                    <label for="debt-start">Tanggal pinjam</label>
                    <input type="date" id="debt-start" class="rf-input" wire:model="startDate">
                </div>
                <div class="rf-field">
                    <label for="debt-due">Jatuh tempo (opsional)</label>
                    <input type="date" id="debt-due" class="rf-input" wire:model="dueDate">
                </div>

                <div class="up-sw">
                    <label class="rf-switch">
                        <input type="checkbox" wire:model.live="recordMovement">
                        <span class="rf-switch-track" aria-hidden="true"></span>
                        <span>Ubah saldo akun sekarang</span>
                    </label>
                </div>

                @if ($recordMovement)
                    <div class="rf-field">
                        <label for="debt-account">Akun</label>
                        <select id="debt-account" class="rf-input" wire:model="accountId">
                            @foreach ($accounts as $account)
                                <option value="{{ $account->id }}">{{ $account->name }}</option>
                            @endforeach
                        </select>
                    </div>
                @endif

                @if ($direction === 'piutang')
                    <div class="up-sw">
                        <label class="rf-switch">
                            <input type="checkbox" wire:model="collectible">
                            <span class="rf-switch-track" aria-hidden="true"></span>
                            <span>Diperkirakan kembali</span>
                        </label>
                    </div>
                @endif

                <div class="rf-field full">
                    <label for="debt-note">Catatan</label>
                    <input type="text" id="debt-note" class="rf-input" wire:model="note" maxlength="200">
                </div>
            </div>

            @if ($error)
                <div class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $error }}</div>
            @endif

            <div class="rf-actions" style="margin-top:16px">
                <button type="submit" class="rf-btn primary">Simpan</button>
                <button type="button" wire:click="cancelForm" class="rf-btn ghost">Batal</button>
            </div>
        </form>
    @endif

    @if ($debts->isEmpty())
        @if (! $showForm)
            <section class="rf-card">
                <div class="rf-empty">
                    <x-rf.icon name="swap" />
                    <b>Belum ada utang-piutang.</b>
                    <p>Saldo akun bergeser di sini tanpa memakan jatah ruang.</p>
                    <div class="rf-actions"><button type="button" wire:click="startAdd" class="rf-btn tonal sm"><x-rf.icon name="plus" />Catat baru</button></div>
                </div>
            </section>
        @endif
    @else
        <section class="rf-card">
            <div class="rf-card-head"><h2 class="rf-h2">Daftar</h2></div>

            @foreach ($debts as $debt)
                @php $status = $debt->statusOn($today); @endphp
                <div class="up-item" wire:key="debt-{{ $debt->id }}">
                    <div class="up-main" role="button" tabindex="0" aria-expanded="{{ $expandedId === $debt->id ? 'true' : 'false' }}"
                        wire:click="toggleDetail({{ $debt->id }})" wire:keydown.enter="toggleDetail({{ $debt->id }})">
                        <div class="up-who">
                            <b>{{ $debt->party }}</b>
                            <div class="up-meta">
                                {{ $debt->direction === 'piutang' ? 'Piutang' : 'Utang' }} · {{ $debt->due_date ? $debt->due_date->locale('id')->translatedFormat('j F Y') : 'Tanpa jatuh tempo' }}
                            </div>
                        </div>
                        <x-rf.pill :tone="$statusTone[$status]" :icon="$statusIcon[$status]">{{ $statusLabel[$status] }}</x-rf.pill>
                        <div class="up-amt">
                            <b>{{ Rupiah::format($debt->outstandingMinor()) }}</b>
                            <small>dari {{ Rupiah::format($debt->principal_minor) }}</small>
                        </div>
                        @if (! $debt->isSettled())
                            <button type="button" wire:click.stop="startRepay({{ $debt->id }})" class="rf-btn primary sm">{{ $debt->direction === 'piutang' ? 'Terima' : 'Bayar' }}</button>
                        @endif
                    </div>

                    @if ($payingId === $debt->id)
                        <div class="up-panel">
                            <form wire:submit="repay" class="up-box">
                                <div class="rf-form-grid">
                                    <div class="rf-field">
                                        <label for="pay-amount-{{ $debt->id }}">Nominal</label>
                                        <input type="number" id="pay-amount-{{ $debt->id }}" class="rf-input" wire:model="payAmount" min="1" autofocus>
                                    </div>
                                    <div class="up-sw">
                                        <label class="rf-switch">
                                            <input type="checkbox" wire:model.live="payUpdateBalance">
                                            <span class="rf-switch-track" aria-hidden="true"></span>
                                            <span>Ubah saldo akun</span>
                                        </label>
                                    </div>
                                    @if ($payUpdateBalance)
                                        <div class="rf-field">
                                            <label for="pay-account-{{ $debt->id }}">Akun</label>
                                            <select id="pay-account-{{ $debt->id }}" class="rf-input" wire:model="payAccountId">
                                                @foreach ($accounts as $account)
                                                    <option value="{{ $account->id }}">{{ $account->name }}</option>
                                                @endforeach
                                            </select>
                                        </div>
                                    @endif
                                </div>
                                @if ($payError)
                                    <div class="rf-error" role="alert"><x-rf.icon name="warn" />{{ $payError }}</div>
                                @endif
                                <div class="rf-actions">
                                    <button type="submit" class="rf-btn primary sm">{{ $debt->direction === 'piutang' ? 'Konfirmasi terima' : 'Konfirmasi bayar' }}</button>
                                    <button type="button" wire:click="cancelRepay" class="rf-btn ghost sm">Batal</button>
                                </div>
                            </form>
                        </div>
                    @endif

                    @if ($expandedId === $debt->id)
                        <div class="up-panel">
                            <div class="rf-sub">
                                Akun: {{ $debt->account?->name ?? 'Saldo akun tidak diubah' }}
                                @if ($debt->direction === 'piutang') · {{ $debt->collectible ? 'Diperkirakan kembali' : 'Diperkirakan tidak kembali' }} @endif
                                @if ($debt->note) · {{ $debt->note }} @endif
                            </div>

                            @if ($editingId === $debt->id)
                                <form wire:submit="updateDebt" class="up-box">
                                    <div class="rf-form-grid">
                                        <div class="rf-field">
                                            <label for="edit-party-{{ $debt->id }}">Nama pihak</label>
                                            <input type="text" id="edit-party-{{ $debt->id }}" class="rf-input" wire:model="editParty" maxlength="40">
                                        </div>
                                        <div class="rf-field">
                                            <label for="edit-due-{{ $debt->id }}">Jatuh tempo (opsional)</label>
                                            <input type="date" id="edit-due-{{ $debt->id }}" class="rf-input" wire:model="editDueDate">
                                        </div>
                                        @if ($debt->direction === 'piutang')
                                            <div class="up-sw">
                                                <label class="rf-switch">
                                                    <input type="checkbox" wire:model="editCollectible">
                                                    <span class="rf-switch-track" aria-hidden="true"></span>
                                                    <span>Diperkirakan kembali</span>
                                                </label>
                                            </div>
                                        @endif
                                        <div class="rf-field full">
                                            <label for="edit-note-{{ $debt->id }}">Catatan</label>
                                            <input type="text" id="edit-note-{{ $debt->id }}" class="rf-input" wire:model="editNote" maxlength="200">
                                        </div>
                                    </div>
                                    @if ($editError)
                                        <div class="rf-error" role="alert"><x-rf.icon name="warn" />{{ $editError }}</div>
                                    @endif
                                    <div class="rf-actions">
                                        <button type="submit" class="rf-btn primary sm">Simpan</button>
                                        <button type="button" wire:click="cancelEditDebt" class="rf-btn ghost sm">Batal</button>
                                    </div>
                                </form>
                            @else
                                <div class="rf-actions">
                                    <button type="button" wire:click="startEditDebt({{ $debt->id }})" class="rf-btn ghost sm"><x-rf.icon name="pencil" />Ubah</button>
                                    <button type="button" wire:click="deleteDebt({{ $debt->id }})" wire:confirm="Hapus utang-piutang ini? Saldo akun akan kembali seperti sebelum dicatat." class="rf-btn ghost sm"><x-rf.icon name="trash" />Hapus</button>
                                </div>
                            @endif

                            <div>
                                <div class="up-label">Riwayat pelunasan</div>
                                @forelse ($debt->payments as $payment)
                                    <div class="rf-rowline up-pay" wire:key="pay-{{ $payment->id }}">
                                        <span class="rf-grow">{{ $payment->paid_on->locale('id')->translatedFormat('j M Y') }} · {{ Rupiah::format($payment->amount_minor) }} @if ($payment->account) · {{ $payment->account->name }} @endif</span>
                                        <button type="button" wire:click="deletePayment({{ $payment->id }})" wire:confirm="Hapus pelunasan ini? Saldo akun akan kembali." class="rf-btn ghost sm"><x-rf.icon name="trash" />Hapus</button>
                                    </div>
                                @empty
                                    <p class="rf-sub">Belum ada pelunasan.</p>
                                @endforelse
                            </div>
                        </div>
                    @endif
                </div>
            @endforeach
        </section>
    @endif
</div>
