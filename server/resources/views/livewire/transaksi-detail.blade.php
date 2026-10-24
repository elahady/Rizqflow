@php
    use App\Support\Ledger;
    use App\Support\Rupiah;

    $isExpense = $transaction->type === 'pengeluaran';
    $unallocated = $transaction->unallocatedMinor();
    $marks = array_filter([$transaction->recurring_rule_id ? 'Berulang' : null, $transaction->bill_id ? 'Tagihan' : null]);
@endphp

@push('styles')
<style>
    /* Detail transaksi (td-): angka utama dan pasangan label-nilai. */
    .td-page { max-width: 720px; margin: 0 auto; width: 100%; }
    .td-amount { font-size: 32px; font-weight: 800; line-height: 1.15; }
    .td-grid { display: grid; gap: 18px 28px; grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr)); }
    .td-k { font-size: 12px; font-weight: 800; color: var(--rf-on-surface-variant); }
    .td-v { margin-top: 3px; font-size: 14.5px; font-weight: 600; overflow-wrap: anywhere; }
    .td-v.dim { color: var(--rf-on-surface-variant); font-weight: 500; }
    .td-pills { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
</style>
@endpush

<div class="rf-page td-page">
    <x-rf.page-head :title="$isExpense ? 'Pengeluaran' : 'Pemasukan'">
        <x-slot:sub><a class="rf-link" href="{{ route('transaksi') }}">← Kembali ke Transaksi</a></x-slot:sub>
        @if (! $editing)
            <x-slot:actions>
                <button type="button" wire:click="startEdit" class="rf-btn primary sm"><x-rf.icon name="pencil" />Ubah</button>
                <button type="button" wire:click="delete" wire:confirm="Hapus transaksi ini? Saldo akun akan disesuaikan." class="rf-btn ghost sm"><x-rf.icon name="trash" />Hapus</button>
            </x-slot:actions>
        @endif
    </x-rf.page-head>

    <section class="rf-card">
        <div class="td-k">Nominal</div>
        <div @class(['td-amount', 'rf-money', 'rf-in' => ! $isExpense])>{{ Rupiah::signed($transaction->amount_minor, $transaction->type) }}</div>
        @if ($marks || ($isExpense && $transaction->pos?->isUntracked()))
            <div class="td-pills">
                @foreach ($marks as $mark)<x-rf.pill tone="info" icon="">{{ $mark }}</x-rf.pill>@endforeach
                @if ($isExpense && $transaction->pos?->isUntracked())
                    <x-rf.pill tone="warn">Belum punya pos</x-rf.pill>
                @endif
            </div>
        @endif

        @if (! $editing)
            <div class="td-grid" style="margin-top: 22px">
                @if ($isExpense)
                    <div>
                        <div class="td-k">Ruang › Pos</div>
                        <div class="td-v">
                            @if ($transaction->room)
                                <x-rf.room-name :room="$transaction->room" :pos="$transaction->pos?->name ?? $transaction->category" />
                            @else
                                {{ $transaction->category ?? '—' }}
                            @endif
                        </div>
                    </div>
                @else
                    <div>
                        <div class="td-k">Sumber</div>
                        <div class="td-v">{{ $transaction->category ?? '—' }}</div>
                    </div>
                @endif
                <div>
                    <div class="td-k">Akun</div>
                    <div class="td-v">{{ $transaction->account?->name ?? '—' }}</div>
                </div>
                <div>
                    <div class="td-k">Tanggal</div>
                    <div class="td-v">{{ $transaction->occurred_on->locale('id')->translatedFormat('j F Y') }}</div>
                </div>
                <div>
                    <div class="td-k">Catatan</div>
                    <div @class(['td-v', 'dim' => ! $transaction->note])>{{ $transaction->note ?? '—' }}</div>
                </div>
                @if ($zakat)
                    <div>
                        <div class="td-k">Lembaga penerima</div>
                        <div @class(['td-v', 'dim' => ! $transaction->recipient_institution])>{{ $transaction->recipient_institution ?? '—' }}</div>
                    </div>
                    <div>
                        <div class="td-k">No. bukti setor</div>
                        <div @class(['td-v', 'dim' => ! $transaction->receipt_number])>{{ $transaction->receipt_number ?? '—' }}</div>
                    </div>
                @endif
            </div>
        @else
            <form wire:submit="save" style="margin-top: 22px">
                <div class="rf-form-grid">
                    <div class="rf-field">
                        <label for="tx-amount">Nominal</label>
                        <input class="rf-input" type="number" id="tx-amount" wire:model="amount" min="1" autofocus>
                    </div>

                    @if ($isExpense)
                        <div class="rf-field">
                            <label for="tx-room">Ruang</label>
                            <select class="rf-input" id="tx-room" wire:model.live="roomId">
                                @foreach ($rooms as $room)
                                    <option value="{{ $room->id }}">{{ $room->name }}</option>
                                @endforeach
                            </select>
                        </div>
                        <div class="rf-field">
                            <label for="tx-category">Kategori</label>
                            <select class="rf-input" id="tx-category" wire:model.live="categoryId">
                                @foreach ($categories as $category)
                                    <option value="{{ $category->id }}">{{ $category->name }}</option>
                                @endforeach
                            </select>
                        </div>
                    @else
                        <div class="rf-field">
                            <label for="tx-source">Sumber</label>
                            <input class="rf-input" type="text" id="tx-source" wire:model="source" maxlength="40" placeholder="Gaji, Usaha, Lainnya">
                        </div>
                    @endif

                    <div class="rf-field">
                        <label for="tx-account">Akun</label>
                        <select class="rf-input" id="tx-account" wire:model="accountId">
                            @foreach ($accounts as $account)
                                <option value="{{ $account->id }}">{{ $account->name }}</option>
                            @endforeach
                        </select>
                    </div>

                    <div class="rf-field">
                        <label for="tx-date">Tanggal</label>
                        <input class="rf-input" type="date" id="tx-date" wire:model="occurredOn">
                    </div>

                    <div class="rf-field full">
                        <label for="tx-note">Catatan</label>
                        <input class="rf-input" type="text" id="tx-note" wire:model="note" maxlength="200">
                    </div>

                    @if ($zakat)
                        <div class="rf-field">
                            <label for="tx-recipient">Lembaga penerima</label>
                            <input class="rf-input" type="text" id="tx-recipient" wire:model="recipientInstitution" maxlength="{{ Ledger::RECIPIENT_MAX }}" placeholder="mis. Baznas, masjid, atau nama penerima">
                        </div>
                        <div class="rf-field">
                            <label for="tx-receipt">No. bukti setor</label>
                            <input class="rf-input" type="text" id="tx-receipt" wire:model="receiptNumber" maxlength="{{ Ledger::RECEIPT_MAX }}">
                        </div>
                        <p class="rf-hint full"><x-rf.icon name="info" />Dipakai di rekap zakat tahunan. Boleh dikosongkan.</p>
                    @endif
                </div>

                @if ($error)
                    <x-rf.banner tone="warn" icon="alert" role="alert" style="margin-top: 16px">{{ $error }}</x-rf.banner>
                @endif

                <div class="rf-actions" style="margin-top: 20px">
                    <button type="submit" class="rf-btn primary">Simpan</button>
                    <button type="button" wire:click="cancelEdit" class="rf-btn tonal">Batal</button>
                </div>
            </form>
        @endif
    </section>

    @if (! $isExpense)
        <section class="rf-card">
            <div class="rf-card-head">
                <h2 class="rf-h3">Dialirkan ke ruang</h2>
                <span class="rf-sub">Potret saat rezeki ini dicatat</span>
            </div>

            @if ($entries->isEmpty())
                <div class="rf-empty">
                    <x-rf.icon name="info" />
                    <b>Belum dialirkan</b>
                    <p>Rezeki ini dicatat tanpa aturan alokasi, jadi seluruhnya belum masuk ke ruang mana pun.</p>
                </div>
            @else
                <div class="rf-scroll">
                    <table class="rf-table">
                        <thead>
                            <tr>
                                <th>Ruang</th>
                                <th class="num">Persen</th>
                                <th class="num">Nominal</th>
                            </tr>
                        </thead>
                        <tbody>
                            @foreach ($entries as $entry)
                                <tr>
                                    <td>@if ($entry->room)<x-rf.room-name :room="$entry->room" />@else — @endif</td>
                                    <td class="num">{{ Ledger::shareLabel([$entry->share_bp]) }}%</td>
                                    <td class="num"><span class="rf-money">{{ Rupiah::format($entry->amount_minor) }}</span></td>
                                </tr>
                            @endforeach
                            @if ($unallocated > 0)
                                <tr>
                                    <td><span class="rf-roomname"><i class="rf-dot cn" aria-hidden="true"></i>Belum dialirkan</span></td>
                                    <td class="num"></td>
                                    <td class="num"><span class="rf-money">{{ Rupiah::format($unallocated) }}</span></td>
                                </tr>
                            @endif
                        </tbody>
                    </table>
                </div>
            @endif

            <p class="rf-hint" style="margin-top: 14px"><x-rf.icon name="info" />Mengubah nominal menghitung ulang pembagian dari persentase di atas, bukan dari aturan alokasi sekarang. Mengubah aturan alokasi tidak mengubah riwayat.</p>
        </section>
    @endif
</div>
