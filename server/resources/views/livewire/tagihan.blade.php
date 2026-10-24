@php
    use App\Support\Period;
    use App\Support\Rupiah;

    $statusLabel = ['terlambat' => 'Terlambat', 'hari_ini' => 'Hari ini', 'segera' => 'Segera', 'mendatang' => 'Mendatang', 'dijeda' => 'Dijeda', 'lunas' => 'Lunas'];
    $statusTone = ['terlambat' => 'warn', 'hari_ini' => 'warn', 'segera' => 'neutral', 'mendatang' => 'neutral', 'dijeda' => 'neutral', 'lunas' => 'good'];
    $statusIcon = ['terlambat' => null, 'hari_ini' => 'clock', 'segera' => 'clock', 'mendatang' => '', 'dijeda' => '', 'lunas' => null];
    $dayNames = [1 => 'Sen', 2 => 'Sel', 3 => 'Rab', 4 => 'Kam', 5 => 'Jum', 6 => 'Sab', 7 => 'Min'];
    $roomClass = fn ($room) => $room->slot >= 1 && $room->slot <= 8 ? 'c'.$room->slot : 'cn';
@endphp

@push('styles')
<style>
    /* Tagihan: baris tagihan dalam satu kartu. */
    .tg-row { display: flex; flex-direction: column; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--rf-surface-container-high); }
    .rf-card-head + .tg-row { padding-top: 0; }
    .tg-row:last-child { border-bottom: 0; padding-bottom: 0; }
    .tg-row.dim { opacity: 0.72; }
    .tg-top { display: flex; align-items: center; gap: 10px 14px; flex-wrap: wrap; }
    .tg-main { flex: 1 1 220px; min-width: 0; }
    .tg-name { font-weight: 700; }
    .tg-warn { margin-top: 4px; display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; color: var(--rf-on-surface-variant); }
    .tg-warn .ic { width: 15px; height: 15px; margin-top: 1px; }
    .tg-bottom { display: flex; align-items: center; justify-content: space-between; gap: 8px 14px; flex-wrap: wrap; padding-left: 60px; }
    .tg-pay { display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; padding: 14px 16px; margin-left: 60px; border-radius: 14px; background: var(--rf-surface-container-low); }
    .tg-pay .rf-field { flex: 1 1 160px; }
    @media (max-width: 560px) { .tg-bottom, .tg-pay { padding-left: 0; margin-left: 0; } }
</style>
@endpush

<div class="rf-page">

    <x-rf.page-head sub="Uang" title="Jadwal" lead="Tagihan dan transaksi berulang dalam satu agenda.">
        <x-slot:actions>
            @if (! $showForm)
                <button type="button" wire:click="startAdd" class="rf-btn primary sm"><x-rf.icon name="plus" />Tagihan baru</button>
            @endif
        </x-slot:actions>
    </x-rf.page-head>

    <x-jadwal.tabs active="tagihan" />

    @if ($showForm)
        <form wire:submit="save" class="rf-card">
            <div class="rf-card-head"><h2 class="rf-h2">{{ $editingId ? 'Ubah tagihan' : 'Tagihan baru' }}</h2></div>

            <div class="rf-form-grid">
                <div class="rf-field">
                    <label for="bill-name">Nama</label>
                    <input type="text" id="bill-name" class="rf-input" wire:model="name" maxlength="40" placeholder="Listrik, Cicilan motor" autofocus>
                </div>
                <div class="rf-field">
                    <label for="bill-amount">Nominal</label>
                    <input type="number" id="bill-amount" class="rf-input" wire:model="amount" min="1">
                </div>
                <div class="rf-field">
                    <label for="bill-room">Ruang</label>
                    <select id="bill-room" class="rf-input" wire:model.live="roomId">
                        @foreach ($rooms as $room)
                            <option value="{{ $room->id }}">{{ $room->name }}</option>
                        @endforeach
                    </select>
                </div>
                <div class="rf-field">
                    <label for="bill-category">Kategori</label>
                    <select id="bill-category" class="rf-input" wire:model="categoryId">
                        @foreach ($categories as $category)
                            <option value="{{ $category->id }}">{{ $category->name }}</option>
                        @endforeach
                    </select>
                </div>
                <div class="rf-field">
                    <label for="bill-account">Akun</label>
                    <select id="bill-account" class="rf-input" wire:model="accountId">
                        @foreach ($accounts as $account)
                            <option value="{{ $account->id }}">{{ $account->name }}</option>
                        @endforeach
                    </select>
                </div>
                <div class="rf-field">
                    <label for="bill-frequency">Pengulangan</label>
                    <select id="bill-frequency" class="rf-input" wire:model.live="frequency">
                        <option value="">Sekali bayar</option>
                        <option value="mingguan">Mingguan</option>
                        <option value="bulanan">Bulanan</option>
                    </select>
                </div>
                <div class="rf-field">
                    <label for="bill-due">Jatuh tempo berikutnya</label>
                    <input type="date" id="bill-due" class="rf-input" wire:model="dueDate">
                </div>

                @if ($frequency !== '')
                    <div class="rf-field">
                        <label for="bill-total">Jumlah cicilan (opsional)</label>
                        <input type="number" id="bill-total" class="rf-input" wire:model="totalInstallments" min="1" placeholder="Kosong = tanpa batas">
                    </div>
                    <div class="rf-field">
                        <label for="bill-paid">Sudah dibayar sebelumnya</label>
                        <input type="number" id="bill-paid" class="rf-input" wire:model="alreadyPaid" min="0">
                    </div>
                @endif

                <div class="rf-field full">
                    <label for="bill-note">Catatan</label>
                    <input type="text" id="bill-note" class="rf-input" wire:model="note" maxlength="200">
                </div>
            </div>

            @if ($error)
                <p class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $error }}</p>
            @endif

            <div class="rf-actions" style="margin-top:18px">
                <button type="submit" class="rf-btn primary">Simpan</button>
                <button type="button" wire:click="cancelForm" class="rf-btn tonal">Batal</button>
            </div>
        </form>
    @endif

    @if ($bills->isEmpty())
        @if (! $showForm)
            <section class="rf-card">
                <div class="rf-empty">
                    <x-rf.icon name="card" />
                    <b>Belum ada tagihan</b>
                    <p>Pengeluaran baru tercatat saat Bayar diketuk. Cocok untuk listrik, internet, atau cicilan.</p>
                    <div class="rf-actions"><button type="button" wire:click="startAdd" class="rf-btn primary sm"><x-rf.icon name="plus" />Tagihan baru</button></div>
                </div>
            </section>
        @endif
    @else
        <section class="rf-card" aria-labelledby="tg-judul">
            <div class="rf-card-head"><h2 class="rf-h2" id="tg-judul">Tagihan dan cicilan</h2><span class="rf-sub">Dibayar manual, tidak pernah otomatis</span></div>

            @foreach ($bills as $bill)
                @php $status = $bill->statusOn($today); @endphp
                <div @class(['tg-row', 'dim' => in_array($status, ['dijeda', 'lunas'], true)]) wire:key="bill-{{ $bill->id }}">
                    <div class="tg-top">
                        @if ($bill->isFinished())
                            <div class="rf-datebox" aria-hidden="true"><div class="d1">Lunas</div><div class="d2">✓</div></div>
                        @else
                            <div class="rf-datebox" aria-hidden="true"><div class="d1">{{ $dayNames[$bill->next_due->dayOfWeekIso] }}</div><div class="d2">{{ $bill->next_due->day }}</div></div>
                        @endif
                        <div class="tg-main">
                            <div class="tg-name">{{ $bill->name }}</div>
                            <div class="rf-sub">
                                <span class="rf-roomname {{ $roomClass($bill->room) }}"><i class="rf-dot"></i>{{ $bill->room->name }}</span> › {{ $bill->category->name }} · {{ $bill->account->name }}
                                @if ($bill->total_installments)
                                    · Cicilan ke-{{ min($bill->paid_count + 1, $bill->total_installments) }} dari {{ $bill->total_installments }}
                                @endif
                            </div>
                            @if (! $bill->isUsable())
                                <div class="tg-warn"><x-rf.icon name="warn" />Akun, ruang, atau kategorinya sudah diarsipkan — Bayar nonaktif.</div>
                            @endif
                        </div>
                        <x-rf.pill :tone="$statusTone[$status]" :icon="$statusIcon[$status]">{{ $statusLabel[$status] }}</x-rf.pill>
                        <div class="rf-money" style="min-width:110px; text-align:right">{{ Rupiah::format($bill->amount_minor) }}</div>
                    </div>
                    <div class="tg-bottom">
                        <div class="rf-sub">
                            @if (! $bill->isFinished())
                                Jatuh tempo {{ $bill->next_due->day }} {{ Period::label($bill->next_due) }}
                            @endif
                        </div>
                        <div class="rf-actions" style="gap:8px">
                            @if (! $bill->isFinished())
                                <button type="button" wire:click="startPay({{ $bill->id }})" class="rf-btn primary sm" {{ ! $bill->isUsable() ? 'disabled' : '' }}>Bayar</button>
                            @endif
                            <button type="button" wire:click="startEdit({{ $bill->id }})" class="rf-btn tonal sm">Ubah</button>
                            @if ($bill->active)
                                <button type="button" wire:click="pause({{ $bill->id }})" class="rf-btn ghost sm">Jeda</button>
                            @else
                                <button type="button" wire:click="resume({{ $bill->id }})" class="rf-btn ghost sm">Lanjutkan</button>
                            @endif
                            <button type="button" wire:click="delete({{ $bill->id }})" wire:confirm="Hapus tagihan ini? Pengeluaran yang sudah tercatat tetap ada." class="rf-btn ghost sm">Hapus</button>
                        </div>
                    </div>

                    @if ($payingId === $bill->id)
                        <form wire:submit="pay" class="tg-pay">
                            <div class="rf-field">
                                <label for="pay-amount-{{ $bill->id }}">Nominal dibayar</label>
                                <input type="number" id="pay-amount-{{ $bill->id }}" class="rf-input" wire:model="payAmount" min="1" autofocus>
                            </div>
                            <button type="submit" class="rf-btn primary">Konfirmasi bayar</button>
                            <button type="button" wire:click="cancelPay" class="rf-btn tonal">Batal</button>
                        </form>
                        @if ($payError)
                            <p class="rf-error" role="alert" style="margin-left:60px"><x-rf.icon name="warn" />{{ $payError }}</p>
                        @endif
                    @endif
                </div>
            @endforeach
        </section>
    @endif

</div>
