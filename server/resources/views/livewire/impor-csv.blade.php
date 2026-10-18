@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:22px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('laporan') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Laporan</a></div>

    <h1 class="rf-serif" style="font-size:26px;">Impor transaksi dari CSV</h1>

    @if (! $canUseReports)
        <div class="rf-card">
            <p style="font-size:14px; color:var(--rf-on-surface-variant);">Impor CSV massal adalah fitur Pro. Upgrade untuk mengimpor banyak transaksi sekaligus dari mutasi bank atau e-wallet.</p>
        </div>
    @else
        <div class="rf-card">
            <p style="font-size:13px; color:var(--rf-on-surface-variant); margin-bottom:14px;">
                Berkas CSV tiga kolom: <strong>Tanggal</strong> (YYYY-MM-DD atau DD/MM/YYYY), <strong>Nominal</strong> (negatif = pengeluaran, positif = pemasukan), <strong>Catatan</strong> (opsional). Baris judul (header) terdeteksi otomatis dan dilewati.
            </p>
            <input type="file" wire:model="file" accept=".csv,.txt">
            @if ($error)
                <p class="error-text" style="margin-top:10px;">{{ $error }}</p>
            @endif
            @if ($status)
                <p style="color:var(--rf-status-good); font-size:13px; margin-top:10px;">{{ $status }}</p>
            @endif
        </div>

        @if ($rows !== [])
            <div class="rf-card">
                <div style="display:flex; justify-content:space-between; gap:12px; margin-bottom:14px; flex-wrap:wrap;">
                    <div>
                        <div style="font-size:12px; color:var(--rf-on-surface-variant);">Baris valid</div>
                        <div style="font-weight:700; font-size:18px;">{{ count($rows) }}</div>
                    </div>
                    @if ($skipped > 0)
                        <div>
                            <div style="font-size:12px; color:var(--rf-on-surface-variant);">Dilewati (tidak valid)</div>
                            <div style="font-weight:700; font-size:18px;">{{ $skipped }}</div>
                        </div>
                    @endif
                    <div>
                        <div style="font-size:12px; color:var(--rf-on-surface-variant);">Dampak saldo</div>
                        <div style="font-weight:700; font-size:18px; {{ $totalAmount >= 0 ? 'color:#275c3f;' : '' }}">{{ $totalAmount >= 0 ? '+' : '−' }} {{ Rupiah::format(abs($totalAmount)) }}</div>
                    </div>
                </div>

                <div class="rf-field">
                    <label for="import-account">Akun tujuan</label>
                    <select id="import-account" wire:model="accountId">
                        <option value="">Pilih akun</option>
                        @foreach ($accounts as $account)
                            <option value="{{ $account->id }}">{{ $account->name }}</option>
                        @endforeach
                    </select>
                </div>

                @if ($hasExpenseRows)
                    <div class="rf-field">
                        <label for="import-room">Ruang (untuk baris pengeluaran)</label>
                        <select id="import-room" wire:model.live="roomId">
                            <option value="">Pilih ruang</option>
                            @foreach ($rooms as $room)
                                <option value="{{ $room->id }}">{{ $room->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    <div class="rf-field">
                        <label for="import-category">Kategori</label>
                        <select id="import-category" wire:model="categoryId">
                            <option value="">Pilih kategori</option>
                            @foreach ($categories as $category)
                                <option value="{{ $category->id }}">{{ $category->name }}</option>
                            @endforeach
                        </select>
                    </div>
                @endif

                <h2 class="rf-serif" style="font-size:16px; margin:18px 0 8px;">Pratinjau (maks. 20 baris pertama)</h2>
                <div style="max-height:320px; overflow-y:auto;">
                    @foreach (array_slice($rows, 0, 20) as $row)
                        <div style="display:flex; align-items:center; justify-content:space-between; gap:12px; padding:8px 0; border-bottom:1px solid var(--rf-outline-variant); font-size:13px;">
                            <span>{{ $row['date'] }} {{ $row['note'] ? '· '.$row['note'] : '' }}</span>
                            <span style="font-weight:700; {{ $row['type'] === 'pemasukan' ? 'color:#275c3f;' : '' }}">{{ $row['type'] === 'pengeluaran' ? '−' : '+' }} {{ Rupiah::format($row['amount_minor']) }}</span>
                        </div>
                    @endforeach
                </div>

                <button type="button" wire:click="confirmImport" class="rf-btn primary" style="margin-top:16px;">Impor {{ count($rows) }} transaksi</button>
            </div>
        @endif
    @endif

</div>
