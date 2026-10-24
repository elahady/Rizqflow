@php
    use App\Support\Period;
    use App\Support\Rupiah;

    $dayNames = [1 => 'Sen', 2 => 'Sel', 3 => 'Rab', 4 => 'Kam', 5 => 'Jum', 6 => 'Sab', 7 => 'Min'];
@endphp

@push('styles')
<style>
    /* Transaksi berulang: baris aturan dalam satu kartu. */
    .br-row { display: flex; align-items: center; gap: 10px 14px; flex-wrap: wrap; padding: 16px 0; border-bottom: 1px solid var(--rf-surface-container-high); }
    .rf-card-head + .br-row { padding-top: 0; }
    .br-row:last-child { border-bottom: 0; padding-bottom: 0; }
    .br-row.dim { opacity: 0.72; }
    .br-main { flex: 1 1 220px; min-width: 0; }
    .br-name { font-weight: 700; }
    .br-warn { margin-top: 4px; display: flex; align-items: flex-start; gap: 6px; font-size: 12.5px; color: var(--rf-on-surface-variant); }
    .br-warn .ic { width: 15px; height: 15px; margin-top: 1px; }
    .br-in { color: var(--rf-income); }
    .br-actions { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
@endpush

<div class="rf-page">

    <x-rf.page-head sub="Uang" title="Jadwal" lead="Tagihan dan transaksi berulang dalam satu agenda.">
        <x-slot:actions>
            @if (! $showForm)
                <button type="button" wire:click="startAdd" class="rf-btn primary sm"><x-rf.icon name="plus" />Aturan baru</button>
            @endif
        </x-slot:actions>
    </x-rf.page-head>

    <x-jadwal.tabs active="berulang" />

    @if ($showForm)
        <form wire:submit="save" class="rf-card">
            <div class="rf-card-head"><h2 class="rf-h2">{{ $editingId ? 'Ubah aturan' : 'Aturan baru' }}</h2></div>

            @if (! $editingId)
                <div class="rf-seg" role="group" aria-label="Jenis aturan" style="margin-bottom:16px">
                    <button type="button" wire:click="$set('type', 'pengeluaran')" @class(['rf-seg-btn', 'on' => $type === 'pengeluaran']) aria-pressed="{{ $type === 'pengeluaran' ? 'true' : 'false' }}">Pengeluaran</button>
                    <button type="button" wire:click="$set('type', 'pemasukan')" @class(['rf-seg-btn', 'on' => $type === 'pemasukan']) aria-pressed="{{ $type === 'pemasukan' ? 'true' : 'false' }}">Pemasukan</button>
                </div>
            @endif

            <div class="rf-form-grid">
                <div class="rf-field">
                    <label for="rec-amount">Nominal</label>
                    <input type="number" id="rec-amount" class="rf-input" wire:model="amount" min="1" autofocus>
                </div>

                @if ($type === 'pengeluaran')
                    <div class="rf-field">
                        <label for="rec-room">Ruang</label>
                        <select id="rec-room" class="rf-input" wire:model.live="roomId">
                            @foreach ($rooms as $room)
                                <option value="{{ $room->id }}">{{ $room->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    <div class="rf-field">
                        <label for="rec-category">Kategori</label>
                        <select id="rec-category" class="rf-input" wire:model="categoryId">
                            @foreach ($categories as $category)
                                <option value="{{ $category->id }}">{{ $category->name }}</option>
                            @endforeach
                        </select>
                    </div>
                @else
                    <div class="rf-field">
                        <label for="rec-source">Sumber</label>
                        <input type="text" id="rec-source" class="rf-input" wire:model="source" maxlength="40" placeholder="Gaji, Usaha, Lainnya">
                    </div>
                @endif

                <div class="rf-field">
                    <label for="rec-account">Akun</label>
                    <select id="rec-account" class="rf-input" wire:model="accountId">
                        @foreach ($accounts as $account)
                            <option value="{{ $account->id }}">{{ $account->name }}</option>
                        @endforeach
                    </select>
                </div>

                <div class="rf-field">
                    <label for="rec-frequency">Frekuensi</label>
                    <select id="rec-frequency" class="rf-input" wire:model="frequency">
                        <option value="harian">Harian</option>
                        <option value="mingguan">Mingguan</option>
                        <option value="bulanan">Bulanan</option>
                    </select>
                </div>

                <div class="rf-field">
                    <label for="rec-start">Tanggal mulai</label>
                    <input type="date" id="rec-start" class="rf-input" wire:model="startDate">
                </div>
                <div class="rf-field">
                    <label for="rec-end">Tanggal akhir (opsional)</label>
                    <input type="date" id="rec-end" class="rf-input" wire:model="endDate">
                </div>

                <div class="rf-field full">
                    <label for="rec-note">Catatan</label>
                    <input type="text" id="rec-note" class="rf-input" wire:model="note" maxlength="200">
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

    @if ($rules->isEmpty())
        @if (! $showForm)
            <section class="rf-card">
                <div class="rf-empty">
                    <x-rf.icon name="refresh" />
                    <b>Belum ada aturan berulang</b>
                    <p>Cocok untuk gaji bulanan, tagihan, atau tabungan rutin. Kemunculannya dicatat otomatis saat jatuh tempo.</p>
                    <div class="rf-actions"><button type="button" wire:click="startAdd" class="rf-btn primary sm"><x-rf.icon name="plus" />Aturan baru</button></div>
                </div>
            </section>
        @endif
    @else
        <section class="rf-card" aria-labelledby="br-judul">
            <div class="rf-card-head"><h2 class="rf-h2" id="br-judul">Transaksi berulang</h2><span class="rf-sub">Dicatat otomatis saat jatuh tempo</span></div>

            @foreach ($rules as $rule)
                @php
                    $frequencyLabel = ['harian' => 'Harian', 'mingguan' => 'Mingguan', 'bulanan' => 'Bulanan'][$rule->frequency] ?? $rule->frequency;
                    $tujuan = $rule->type === 'pengeluaran' ? ($rule->room?->name.' · '.$rule->category?->name) : ($rule->source ?: 'Pemasukan');
                    $inactive = ! $rule->active || $rule->isFinished();
                @endphp
                <div @class(['br-row', 'dim' => $inactive]) wire:key="rule-{{ $rule->id }}">
                    <div class="rf-datebox" aria-hidden="true"><div class="d1">{{ $dayNames[$rule->next_due->dayOfWeekIso] }}</div><div class="d2">{{ $rule->next_due->day }}</div></div>
                    <div class="br-main">
                        <div class="br-name">{{ $rule->note ?: $tujuan }}</div>
                        <div class="rf-sub">{{ $tujuan }} · {{ $rule->account->name }} · {{ $frequencyLabel }}</div>
                        @if (! $rule->isUsable())
                            <div class="br-warn"><x-rf.icon name="warn" />Tertahan — akun, ruang, atau kategorinya sudah diarsipkan.</div>
                        @elseif ($rule->isFinished())
                            <div class="rf-sub" style="margin-top:4px">Selesai {{ $rule->end_date->day }} {{ Period::label($rule->end_date) }}</div>
                        @elseif (! $rule->active)
                            <div class="rf-sub" style="margin-top:4px">Dijeda</div>
                        @else
                            <div class="rf-sub" style="margin-top:4px">Berikutnya {{ $rule->next_due->day }} {{ Period::label($rule->next_due) }}</div>
                        @endif
                    </div>
                    <div @class(['rf-money', 'br-in' => $rule->type === 'pemasukan']) style="text-align:right">
                        {{ Rupiah::signed($rule->amount_minor, $rule->type === 'pemasukan' ? 'pemasukan' : 'pengeluaran') }}
                    </div>
                    <div class="br-actions">
                        <button type="button" wire:click="startEdit({{ $rule->id }})" class="rf-btn tonal sm">Ubah</button>
                        @if ($rule->active)
                            <button type="button" wire:click="pause({{ $rule->id }})" class="rf-btn ghost sm">Jeda</button>
                        @else
                            <button type="button" wire:click="resume({{ $rule->id }})" class="rf-btn ghost sm">Lanjutkan</button>
                        @endif
                        <button type="button" wire:click="delete({{ $rule->id }})" wire:confirm="Hapus aturan ini? Transaksi yang sudah tercatat tetap ada." class="rf-btn ghost sm">Hapus</button>
                    </div>
                </div>
            @endforeach
        </section>
    @endif

</div>
