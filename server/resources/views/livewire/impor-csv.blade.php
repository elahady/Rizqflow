@php use App\Support\Rupiah; @endphp

@push('styles')
<style>
    .ic-steps { counter-reset: ic-step; }
    .ic-step { display: flex; align-items: center; gap: 10px; }
    .ic-step::before {
        counter-increment: ic-step; content: counter(ic-step); flex: none; width: 26px; height: 26px; border-radius: 999px;
        display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 800;
        background: var(--rf-primary-fixed); color: var(--rf-on-primary-container);
    }
    .ic-scroll { max-height: 360px; overflow: auto; }
    .ic-in { color: var(--rf-income); }
    .ic-file { padding: 10px 12px; }
    .ic-scroll td:first-child { white-space: nowrap; }
    @media (max-width: 600px) { .ic-jenis { display: none; } .ic-scroll .rf-table th, .ic-scroll .rf-table td { padding-inline: 8px; } }
</style>
@endpush

<div class="rf-page ic-steps">
    <x-rf.page-head title="Impor transaksi dari CSV" lead="Unggah mutasi bank atau e-wallet, periksa pratinjaunya, lalu konfirmasi.">
        <x-slot:sub><a class="rf-link" href="{{ route('laporan') }}">← Kembali ke Laporan</a></x-slot:sub>
    </x-rf.page-head>

    @if (! $canUseReports)
        <div class="rf-upsell">
            <span class="rf-upsell-ic"><x-rf.icon name="lock" /></span>
            <div>
                <b>Impor CSV massal adalah fitur Pro.</b>
                <p>Upgrade untuk mengimpor banyak transaksi sekaligus dari mutasi bank atau e-wallet. Ekspor CSV tetap gratis.</p>
            </div>
            <a class="rf-btn tonal sm" href="{{ route('impor-ekspor') }}">Lihat impor dan ekspor</a>
        </div>
    @else
        @if ($status)
            <x-rf.banner tone="good" role="status">{{ $status }}</x-rf.banner>
        @endif

        <section class="rf-card">
            <div class="rf-card-head">
                <h2 class="rf-h2 ic-step">Unggah berkas</h2>
            </div>
            <p class="rf-sub" style="margin-bottom:14px">
                Berkas CSV tiga kolom: <b>Tanggal</b> (YYYY-MM-DD atau DD/MM/YYYY), <b>Nominal</b> (negatif = pengeluaran, positif = pemasukan), dan <b>Catatan</b> (opsional). Baris judul (header) terdeteksi otomatis dan dilewati.
            </p>
            <div class="rf-field">
                <label for="import-file">Berkas CSV</label>
                <input type="file" id="import-file" class="rf-input ic-file" wire:model="file" accept=".csv,.txt">
            </div>
            <div wire:loading wire:target="file" class="rf-hint" style="margin-top:10px"><x-rf.icon name="clock" />Membaca berkas...</div>
            @if ($error)
                <div class="rf-error" role="alert" style="margin-top:12px"><x-rf.icon name="warn" />{{ $error }}</div>
            @endif
        </section>

        @if ($rows !== [])
            <section class="rf-card">
                <div class="rf-card-head">
                    <h2 class="rf-h2 ic-step">Periksa dan pilih tujuan</h2>
                </div>

                <div class="rf-stats" style="margin-bottom:18px">
                    <div class="rf-stat">
                        <div class="l">Baris valid</div>
                        <div class="v">{{ count($rows) }}</div>
                    </div>
                    @if ($skipped > 0)
                        <div class="rf-stat">
                            <div class="l">Dilewati (tidak valid)</div>
                            <div class="v">{{ $skipped }}</div>
                        </div>
                    @endif
                    <div class="rf-stat">
                        <div class="l">Dampak saldo</div>
                        <div class="v">{{ $totalAmount >= 0 ? '+' : '−' }} {{ Rupiah::format(abs($totalAmount)) }}</div>
                    </div>
                </div>

                <div class="rf-form-grid">
                    <div class="rf-field">
                        <label for="import-account">Akun tujuan</label>
                        <select id="import-account" class="rf-input" wire:model="accountId">
                            <option value="">Pilih akun</option>
                            @foreach ($accounts as $account)
                                <option value="{{ $account->id }}">{{ $account->name }}</option>
                            @endforeach
                        </select>
                    </div>

                    @if ($hasExpenseRows)
                        <div class="rf-field">
                            <label for="import-room">Ruang (untuk baris pengeluaran)</label>
                            <select id="import-room" class="rf-input" wire:model.live="roomId">
                                <option value="">Pilih ruang</option>
                                @foreach ($rooms as $room)
                                    <option value="{{ $room->id }}">{{ $room->name }}</option>
                                @endforeach
                            </select>
                        </div>
                        <div class="rf-field">
                            <label for="import-category">Kategori</label>
                            <select id="import-category" class="rf-input" wire:model="categoryId">
                                <option value="">Pilih kategori</option>
                                @foreach ($categories as $category)
                                    <option value="{{ $category->id }}">{{ $category->name }}</option>
                                @endforeach
                            </select>
                        </div>
                    @endif
                </div>
            </section>

            <section class="rf-card">
                <div class="rf-card-head">
                    <h2 class="rf-h2 ic-step">Pratinjau</h2>
                    <span class="rf-sub">Maksimal 20 baris pertama</span>
                </div>
                <div class="ic-scroll">
                    <table class="rf-table">
                        <thead>
                            <tr>
                                <th scope="col">Tanggal</th>
                                <th scope="col">Catatan</th>
                                <th scope="col" class="ic-jenis">Jenis</th>
                                <th scope="col" class="num">Nominal</th>
                            </tr>
                        </thead>
                        <tbody>
                            @foreach (array_slice($rows, 0, 20) as $row)
                                <tr>
                                    <td>{{ $row['date'] }}</td>
                                    <td>{{ $row['note'] ?: '—' }}</td>
                                    <td class="ic-jenis">{{ $row['type'] === 'pengeluaran' ? 'Pengeluaran' : 'Pemasukan' }}</td>
                                    <td class="num @if ($row['type'] === 'pemasukan') ic-in @endif">{{ $row['type'] === 'pengeluaran' ? '−' : '+' }} {{ Rupiah::format($row['amount_minor']) }}</td>
                                </tr>
                            @endforeach
                        </tbody>
                    </table>
                </div>

                <div class="rf-actions" style="margin-top:18px">
                    <button type="button" wire:click="confirmImport" class="rf-btn primary"><x-rf.icon name="download" />Impor {{ count($rows) }} transaksi</button>
                </div>
            </section>
        @endif
    @endif
</div>
