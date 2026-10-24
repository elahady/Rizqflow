@php
    use App\Support\Ledger;
    use App\Support\Rupiah;

    $typeLabel = ['menunaikan' => 'Menunaikan', 'menumbuhkan' => 'Menumbuhkan', 'mencukupi' => 'Mencukupi'];
    $segments = [];
    if ($preview) {
        foreach ($preview['rooms'] as $share) {
            $segments[] = ['slot' => $share['room']->slot, 'bp' => $share['share_bp'], 'label' => $share['room']->name];
        }
    }
    $examplesText = [
        18_000_000 => 'Rp 18.000.000',
        1_234_567 => 'Rp 1.234.567 (ada sisa pembulatan)',
    ];
@endphp

<div class="rf-page">
    <x-rf.page-head title="Catat rezeki masuk" lead="Rezeki yang masuk dialirkan ke ruang sesuai aturanmu. Pembagian di kanan berubah saat kamu mengetik nominal.">
        <x-slot:sub><a class="rf-link" href="{{ route('transaksi') }}">← Kembali ke Transaksi</a></x-slot:sub>
    </x-rf.page-head>

    <div class="rf-split">

        {{-- Form --}}
        <form class="rf-card" style="flex: 5 1 400px; display: flex; flex-direction: column; gap: 20px" wire:submit="save" novalidate>

            @if ($formError)
                <x-rf.banner tone="warn" role="alert">{{ $formError }}</x-rf.banner>
            @endif

            <div class="rf-field">
                <label for="alr-nominal">Nominal rezeki</label>
                <div class="rf-input big" style="display: flex; align-items: center; gap: 10px">
                    <span style="color: var(--rf-on-surface-variant)" aria-hidden="true">Rp</span>
                    <input id="alr-nominal" type="text" inputmode="numeric" autocomplete="off" placeholder="0"
                           wire:model.live.debounce.300ms="amount"
                           @error('amount') aria-invalid="true" aria-describedby="alr-nominal-err" @enderror
                           style="flex: 1; min-width: 0; border: 0; background: transparent; outline: none; font: inherit; color: inherit"
                           aria-label="Nominal rezeki dalam rupiah">
                </div>
                @error('amount')
                    <div class="rf-error" id="alr-nominal-err"><x-rf.icon name="warn" />{{ $message }}</div>
                @enderror
                <div class="rf-row" style="gap: 8px">
                    <span class="rf-help">Contoh:</span>
                    @foreach ($examplesText as $example => $text)
                        <button class="rf-chip" type="button" wire:click="useAmount({{ $example }})">{{ $text }}</button>
                    @endforeach
                </div>
            </div>

            <div class="rf-field">
                <span class="rf-label" id="alr-sumber">Sumber</span>
                <div class="rf-row" style="gap: 8px" role="group" aria-labelledby="alr-sumber">
                    @foreach ($sources as $option)
                        <button type="button" class="rf-chip {{ $source === $option ? 'on' : '' }}" aria-pressed="{{ $source === $option ? 'true' : 'false' }}" wire:click="setSource('{{ $option }}')">
                            @if ($source === $option)<x-rf.icon name="check" />@endif{{ $option }}
                        </button>
                    @endforeach
                </div>
                @error('source')
                    <div class="rf-error"><x-rf.icon name="warn" />{{ $message }}</div>
                @enderror
            </div>

            <div class="rf-row" style="gap: 14px; align-items: flex-start">
                <div class="rf-field" style="flex: 1 1 160px">
                    <label for="alr-akun">Akun tujuan</label>
                    @if ($accounts->isEmpty())
                        <div class="rf-hint"><x-rf.icon name="info" />Belum ada akun aktif. <a class="rf-link" href="{{ route('kelola') }}">Buat akun dulu</a></div>
                    @else
                        <select id="alr-akun" class="rf-input" wire:model="accountId">
                            @foreach ($accounts as $account)
                                <option value="{{ $account->id }}">{{ $account->name }}</option>
                            @endforeach
                        </select>
                    @endif
                    @error('accountId')
                        <div class="rf-error"><x-rf.icon name="warn" />{{ $message }}</div>
                    @enderror
                </div>
                <div class="rf-field" style="flex: 1 1 160px">
                    <label for="alr-tanggal">Tanggal</label>
                    <input id="alr-tanggal" type="date" class="rf-input" wire:model="date">
                    @error('date')
                        <div class="rf-error"><x-rf.icon name="warn" />{{ $message }}</div>
                    @enderror
                </div>
            </div>

            <div class="rf-field">
                <label for="alr-catatan">Catatan</label>
                <input id="alr-catatan" type="text" class="rf-input" wire:model="note" maxlength="{{ Ledger::NOTE_MAX }}" placeholder="mis. Gaji Oktober">
                @error('note')
                    <div class="rf-error"><x-rf.icon name="warn" />{{ $message }}</div>
                @enderror
            </div>

            <div class="rf-actions" style="padding-top: 4px">
                <button type="submit" class="rf-btn primary" wire:loading.attr="disabled" wire:target="save" @disabled($accounts->isEmpty())>
                    {{ $hasRule ? 'Simpan dan alirkan' : 'Simpan rezeki' }}
                </button>
                <a class="rf-btn ghost" href="{{ route('transaksi') }}">Batal</a>
            </div>
        </form>

        {{-- Pratinjau aliran --}}
        <section class="rf-card" style="flex: 7 1 460px; display: flex; flex-direction: column; gap: 20px" aria-labelledby="alr-pratinjau">
            <div class="rf-card-head" style="margin-bottom: 0">
                <h2 class="rf-h2" id="alr-pratinjau">Pratinjau aliran</h2>
                @if ($preview && $preview['rule_label'])
                    <x-rf.pill tone="info">
                        {{ $isAdvanced ? 'Aturan '.$preview['rule_label'].' · Pro' : 'Aturan '.$preview['rule_label'] }}
                    </x-rf.pill>
                @endif
            </div>

            @if ($previewError)
                <x-rf.banner tone="warn" role="alert">
                    {{ $previewError }}
                    <a class="rf-link" href="{{ route('aturan-alokasi') }}">Buka Aturan alokasi</a>
                </x-rf.banner>
            @else
                <div>
                    <div class="rf-eyebrow">Rezeki yang dialirkan</div>
                    <div class="rf-hero-amt" style="margin-top: 6px">{{ Rupiah::format($amountMinor) }}</div>
                </div>

                @if ($hasRule)
                    <x-rf.stack :segments="$segments" label="Pembagian rezeki" />

                    <div>
                        @foreach ($preview['rooms'] as $share)
                            @php
                                $room = $share['room'];
                                $colorClass = $room->slot >= 1 && $room->slot <= 8 ? 'c'.$room->slot : 'cn';
                                $percentText = ($isAdvanced ? '≈ ' : '').Ledger::shareLabel([$share['share_bp']]).'%';
                            @endphp
                            <div class="rf-rowline {{ $colorClass }}">
                                <x-rf.room-icon :room="$room" />
                                <div class="rf-grow">
                                    <div class="rf-row" style="justify-content: space-between; flex-wrap: nowrap">
                                        <div>
                                            <b>{{ $room->name }}</b>
                                            <span class="rf-sub">{{ $typeLabel[$room->type] ?? '' }} · {{ $percentText }}@if ($isAdvanced) · {{ $share['cap_minor'] !== null ? 'batas atas '.Rupiah::format($share['cap_minor']) : 'tanpa batas atas' }}@endif</span>
                                        </div>
                                        <div class="rf-money">{{ Rupiah::format($share['amount_minor']) }}</div>
                                    </div>
                                    <x-rf.track :percent="(int) round($share['share_bp'] / 100)" style="margin-top: 8px" />
                                </div>
                            </div>
                        @endforeach
                        @if ($preview['unallocated_minor'] > 0)
                            <div class="rf-rowline">
                                <span class="rf-roomic cn" aria-hidden="true"><x-rf.icon name="clock" /></span>
                                <div class="rf-grow">
                                    <div class="rf-row" style="justify-content: space-between; flex-wrap: nowrap">
                                        <div><b>Belum dialirkan</b> <span class="rf-sub">Tidak mendapat ruang</span></div>
                                        <div class="rf-money">{{ Rupiah::format($preview['unallocated_minor']) }}</div>
                                    </div>
                                </div>
                            </div>
                        @endif
                    </div>

                    @if ($amountMinor > 0)
                        <div class="rf-readout">
                            @if ($preview['unallocated_minor'] > 0)
                                <x-rf.pill tone="info">Sebagian belum dialirkan</x-rf.pill>
                            @else
                                <x-rf.pill tone="good">Jumlah bagian = nominal</x-rf.pill>
                            @endif
                            <span>{{ $equation }}</span>
                        </div>
                    @else
                        <p class="rf-hint"><x-rf.icon name="info" />Ketik nominal untuk melihat pembagiannya.</p>
                    @endif

                    @if ($preview['rounding_minor'] > 0)
                        <div class="rf-readout" style="background: var(--pill-info-bg); color: var(--pill-info-fg)">
                            <span>Pembulatan: sisa {{ Rupiah::format($preview['rounding_minor']) }} dibagikan satu-satu ke ruang dengan pecahan terbesar (metode sisa terbesar), jadi total selalu persis nominal.</span>
                        </div>
                    @endif
                @else
                    <div class="rf-empty">
                        <x-rf.icon name="grid" />
                        <b>Belum ada aturan alokasi</b>
                        <p>Tanpa ruang dan aturan, rezeki tetap bisa dicatat, tetapi seluruhnya berstatus belum dialirkan. Atur dulu pembagiannya supaya rezeki punya tujuan.</p>
                        <div class="rf-actions">
                            <a class="rf-btn primary sm" href="{{ route('aturan-alokasi') }}">Atur aturan alokasi</a>
                        </div>
                    </div>
                @endif
            @endif

            <p class="rf-sub">
                Aturan baru hanya berlaku untuk rezeki berikutnya. Pembagian disimpan sebagai potret saat dicatat, jadi mengubah aturan nanti tidak mengubah riwayat.
                <a class="rf-link" href="{{ route('aturan-alokasi') }}">Ubah aturan</a>
            </p>
        </section>

    </div>
</div>
