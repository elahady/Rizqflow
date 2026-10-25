@php
    use App\Support\Period;
    use App\Support\Rupiah;

    $monthLabel = Period::label($month);
    $thisYear = (int) now()->format('Y');
    $selectedCount = count($selectedIds);
    $allShown = $rows->isNotEmpty() && $rows->pluck('id')->diff($selectedIds)->isEmpty();
    $hasSetup = $activeRooms->isNotEmpty() && $activeAccounts->isNotEmpty();
    $totalParts = array_filter([
        $selectedOut > 0 ? 'Keluar '.Rupiah::format($selectedOut) : null,
        $selectedIn > 0 ? 'Masuk '.Rupiah::format($selectedIn) : null,
    ]);
@endphp

@push('styles')
<style>
    /* Transaksi (tx-): filter ringkas, kaitan baris tabel, dan kaki daftar. */
    .tx-filters { padding: 12px 20px; border-top: 1px solid var(--rf-outline-variant); border-bottom: 1px solid var(--rf-outline-variant); }
    .tx-filters .rf-cmd { flex: 1 1 240px; }
    select.rf-input.tx-filter { width: auto; max-width: 100%; min-height: 36px; padding-top: 6px; padding-bottom: 6px; font-size: 13px; }
    /* .rf-sr-only memakai position:absolute; tanpa induk berposisi ia lolos dari rf-scroll dan melebarkan halaman di layar sempit. */
    .tx-table td { position: relative; }
    .tx-table tbody tr[data-href] { cursor: pointer; }
    .tx-table tbody tr[data-href]:focus-visible { outline: 2px solid var(--rf-primary); outline-offset: -2px; }
    .tx-note { color: inherit; font-weight: 700; text-decoration: none; }
    .tx-note:hover { text-decoration: underline; }
    .tx-foot { justify-content: space-between; padding: 14px 20px; border-top: 1px solid var(--rf-outline-variant); }
    .tx-foot b { color: var(--rf-on-background); }
    .tx-room-dots { display: inline-flex; gap: 3px; }
    @media (max-width: 720px) {
        .tx-filters, .tx-foot { padding-left: 16px; padding-right: 16px; }
        select.rf-input.tx-filter { flex: 1 1 140px; }
    }
</style>
@endpush

<div class="rf-page">

    <x-rf.page-head :sub="$monthLabel.' · '.$monthCount.' transaksi'" title="Transaksi">
        <x-slot:actions>
            <a class="rf-btn ghost sm" href="{{ route('laporan.impor-csv') }}"><x-rf.icon name="download" />Impor CSV</a>
            <a class="rf-btn ghost sm" href="{{ route('transaksi.ekspor') }}"><x-rf.icon name="upload" />Ekspor CSV</a>
            <a class="rf-btn tonal sm" href="{{ route('transaksi.rezeki') }}"><x-rf.icon name="plus" />Rezeki masuk</a>
        </x-slot:actions>
    </x-rf.page-head>

    @if ($notice)
        <x-rf.banner :tone="$noticeTone" role="status">{{ $notice }}</x-rf.banner>
    @endif

    <section class="rf-card" id="catat">
        <div class="rf-card-head" style="margin-bottom: 12px">
            <h2 class="rf-h3">Catat pengeluaran</h2>
            <span class="rf-sub">Tekan <span class="rf-kbd">C</span> di halaman mana pun</span>
        </div>

        @if (! $hasSetup)
            <div class="rf-empty">
                <x-rf.icon name="info" />
                <b>{{ $activeRooms->isEmpty() ? 'Belum ada ruang dan pos.' : 'Belum ada akun.' }}</b>
                <p>Pengeluaran dicatat ke sebuah pos dan akun. {{ $activeRooms->isEmpty() ? 'Buat ruang dan pos dulu.' : 'Tambah akun dulu.' }}</p>
                <div class="rf-actions">
                    <a class="rf-btn tonal sm" href="{{ $activeRooms->isEmpty() ? route('ruang') : route('kelola') }}">{{ $activeRooms->isEmpty() ? 'Atur ruang' : 'Atur akun' }}</a>
                </div>
            </div>
        @else
            <form wire:submit="save" x-data @keydown.shift.enter.prevent="$wire.save(true)">
                <div class="rf-quick">
                    <div class="rf-field" style="flex: 0 0 150px">
                        <label for="catat-tanggal">Tanggal</label>
                        <input class="rf-input" type="date" id="catat-tanggal" wire:model="quickDate">
                    </div>
                    <div class="rf-field grow2">
                        <label for="catat-catatan">Catatan (boleh bahasa natural)</label>
                        <input class="rf-input" type="text" id="catat-catatan" maxlength="200" autocomplete="off"
                               placeholder="kopi 25rb gopay" wire:model.live.debounce.400ms="quickNote"
                               @catat-fokus.window="$el.focus()"
                               x-init="if (location.hash === '#catat' || {{ $focusNote ? 'true' : 'false' }}) $el.focus()">
                    </div>
                    <div class="rf-field" style="flex: 2 1 220px">
                        <label for="catat-pos">Ruang › Pos</label>
                        <select class="rf-input" id="catat-pos" wire:model="quickCategoryId">
                            @foreach ($activeRooms as $room)
                                @php $roomPos = $categories->get($room->id, collect())->where('archived', false); @endphp
                                @if ($roomPos->isNotEmpty())
                                    <optgroup label="{{ $room->name }}">
                                        @foreach ($roomPos as $c)
                                            <option value="{{ $c->id }}">{{ $room->name }} › {{ $c->name }}</option>
                                        @endforeach
                                    </optgroup>
                                @endif
                            @endforeach
                        </select>
                    </div>
                    <div class="rf-field" style="flex: 0 0 150px">
                        <label for="catat-akun">Akun</label>
                        <select class="rf-input" id="catat-akun" wire:model="quickAccountId">
                            @foreach ($activeAccounts as $a)
                                <option value="{{ $a->id }}">{{ $a->name }}</option>
                            @endforeach
                        </select>
                    </div>
                    <div class="rf-field" style="flex: 0 0 140px">
                        <label for="catat-nominal">Nominal</label>
                        <input class="rf-input" type="text" inputmode="numeric" id="catat-nominal" placeholder="Rp 0" autocomplete="off"
                               wire:model.live.debounce.400ms="quickAmount" style="font-weight: 700; text-align: right">
                    </div>
                    <button class="rf-btn primary" type="submit">Simpan<span class="rf-kbd" style="background: transparent; color: var(--rf-on-primary); border-color: currentColor">Enter</span></button>
                </div>

                <div class="rf-parse" aria-live="polite">
                    @if ($parsedView && $parsedView['kind'] === 'pengeluaran' && ($parsedView['amount'] || $parsedView['account'] || $parsedView['pos'] || $parsedView['date']))
                        <span>Terbaca otomatis:</span>
                        @if ($parsedView['amount'])<x-rf.pill tone="info" icon="">{{ $parsedView['amount'] }}</x-rf.pill>@endif
                        @if ($parsedView['account'])<x-rf.pill tone="info" icon="">{{ $parsedView['account'] }}</x-rf.pill>@endif
                        @if ($parsedView['pos'])<x-rf.pill tone="info" icon="">{{ $parsedView['pos'] }}{{ $parsedView['from_history'] ? ' (dari catatan serupa)' : '' }}</x-rf.pill>@endif
                        @if ($parsedView['date'])<x-rf.pill tone="info" icon="">{{ $parsedView['date'] }}</x-rf.pill>@endif
                        <span>·</span>
                    @else
                        <span>Tulis seperti biasa, mis. "kopi 25rb gopay"; nominal, akun, dan pos terbaca otomatis.</span>
                        <span>·</span>
                    @endif
                    <span><span class="rf-kbd">Shift</span> + <span class="rf-kbd">Enter</span> simpan dan tambah lagi</span>
                </div>

                @if ($parsedView && $parsedView['kind'] === 'pemasukan')
                    <x-rf.banner tone="info" style="margin-top: 12px">
                        Catatan ini terbaca sebagai pemasukan{{ $parsedView['amount'] ? ' '.$parsedView['amount'] : '' }}. Pemasukan dialirkan ke ruang lewat
                        <a class="rf-link" href="{{ route('transaksi.rezeki', array_filter(['nominal' => $parsedView['amount_minor'], 'catatan' => $parsedView['note']])) }}">Catat rezeki masuk</a>,
                        bukan disimpan sebagai pengeluaran.
                    </x-rf.banner>
                @elseif ($parsedView && $parsedView['kind'] === 'transfer')
                    <x-rf.banner tone="info" style="margin-top: 12px">
                        Catatan ini terbaca sebagai transfer antar akun. Transfer belum didukung di web; catat lewat aplikasi Android.
                    </x-rf.banner>
                @endif

                {{-- Lembut (info saja): hanya untuk ruang Trader yang melewati batas risiko per trade; simpan tetap jalan. --}}
                @if ($riskWarning)
                    <x-rf.banner tone="info" style="margin-top: 12px">Melewati batas risiko {{ Rupiah::format($riskWarning['max']) }} per trade (kamu mencatat {{ Rupiah::format($riskWarning['amount']) }}). Tetap bisa disimpan.</x-rf.banner>
                @endif

                @if ($quickError)
                    <x-rf.banner tone="warn" icon="alert" role="alert" style="margin-top: 12px">{{ $quickError }}</x-rf.banner>
                @endif
            </form>
        @endif
    </section>

    <section class="rf-card" style="padding: 0; overflow: hidden" data-tx-table>
        <div style="padding: 6px 20px 0">
            <div class="rf-tabs" style="border-bottom: 0" role="tablist" aria-label="Daftar transaksi">
                <button type="button" role="tab" aria-selected="{{ $tab === 'semua' ? 'true' : 'false' }}" class="rf-tab {{ $tab === 'semua' ? 'on' : '' }}" wire:click="setTab('semua')">Semua<span class="rf-sub">{{ $monthCount }}</span></button>
                <button type="button" role="tab" aria-selected="{{ $tab === 'tak' ? 'true' : 'false' }}" class="rf-tab {{ $tab === 'tak' ? 'on' : '' }}" wire:click="setTab('tak')">Tak terlacak<span class="rf-count">{{ $untrackedCount }}</span></button>
            </div>
        </div>

        <div class="rf-row tx-filters">
            <label class="rf-cmd">
                <x-rf.icon name="search" />
                <input type="search" id="tx-cari" wire:model.live.debounce.300ms="search" placeholder="Cari catatan atau nominal" aria-label="Cari catatan atau nominal">
            </label>
            <select class="rf-input tx-filter" wire:model.live="ruang" aria-label="Filter ruang">
                <option value="">Semua ruang</option>
                @foreach ($rooms as $room)
                    <option value="{{ $room->id }}">{{ $room->name }}{{ $room->archived ? ' (diarsipkan)' : '' }}</option>
                @endforeach
            </select>
            <select class="rf-input tx-filter" wire:model.live="pos" aria-label="Filter pos">
                <option value="">Semua pos</option>
                @foreach ($filterRooms as $room)
                    @php $roomPos = $categories->get($room->id, collect()); @endphp
                    @if ($roomPos->isNotEmpty())
                        <optgroup label="{{ $room->name }}">
                            @foreach ($roomPos as $c)
                                <option value="{{ $c->id }}">{{ $c->name }}{{ $c->archived ? ' (diarsipkan)' : '' }}</option>
                            @endforeach
                        </optgroup>
                    @endif
                @endforeach
            </select>
            <select class="rf-input tx-filter" wire:model.live="akun" aria-label="Filter akun">
                <option value="">Semua akun</option>
                @foreach ($accounts as $a)
                    <option value="{{ $a->id }}">{{ $a->name }}{{ $a->archived ? ' (diarsipkan)' : '' }}</option>
                @endforeach
            </select>
            <select class="rf-input tx-filter" wire:model.live="jenis" aria-label="Filter jenis">
                <option value="">Semua jenis</option>
                <option value="pemasukan">Masuk</option>
                <option value="pengeluaran">Keluar</option>
            </select>
            @if ($hasFilter)
                <button type="button" class="rf-btn ghost sm" wire:click="resetFilters">Reset filter</button>
            @endif
        </div>

        @if ($searching)
            <div class="rf-hint" style="padding: 10px 20px 0"><x-rf.icon name="info" />Hasil pencarian di semua periode. Pilihan bulan diabaikan selama kolom cari terisi.</div>
        @endif

        @if ($selectedCount > 0)
            <div style="padding: 12px 20px">
                <div class="rf-bulk" role="region" aria-label="Aksi untuk transaksi terpilih">
                    <b>{{ $selectedCount }} dipilih</b>
                    @if ($totalParts)<span>{{ implode(' · ', $totalParts) }}</span>@endif
                    <button class="rf-btn ghost sm" type="button" wire:click="setBulkMode('pos')" aria-expanded="{{ $bulkMode === 'pos' ? 'true' : 'false' }}">Ubah ruang dan pos</button>
                    <button class="rf-btn ghost sm" type="button" wire:click="setBulkMode('akun')" aria-expanded="{{ $bulkMode === 'akun' ? 'true' : 'false' }}">Pindah akun</button>
                    <a class="rf-btn ghost sm" href="{{ route('transaksi.ekspor', ['ids' => implode(',', $selectedIds)]) }}">Ekspor pilihan (CSV)</a>
                    <button class="rf-btn ghost sm" type="button" wire:click="deleteSelected" wire:confirm="Hapus {{ $selectedCount }} transaksi terpilih? Saldo akun akan disesuaikan.">Hapus</button>
                    <button class="rf-btn ghost sm" type="button" style="margin-left: auto" wire:click="clearSelection">Batal pilih</button>

                    @if ($bulkMode === 'pos')
                        <div class="rf-row" style="flex: 1 1 100%">
                            <select class="rf-input tx-filter" wire:model="bulkCategoryId" aria-label="Ruang dan pos tujuan">
                                <option value="">Pilih ruang › pos tujuan</option>
                                @foreach ($activeRooms as $room)
                                    @php $roomPos = $categories->get($room->id, collect())->where('archived', false); @endphp
                                    @if ($roomPos->isNotEmpty())
                                        <optgroup label="{{ $room->name }}">
                                            @foreach ($roomPos as $c)
                                                <option value="{{ $c->id }}">{{ $room->name }} › {{ $c->name }}</option>
                                            @endforeach
                                        </optgroup>
                                    @endif
                                @endforeach
                            </select>
                            <button class="rf-btn primary sm" type="button" wire:click="applyBulkCategory">Terapkan</button>
                            <span class="rf-sub" style="color: inherit">Pemasukan dalam pilihan dilewati.</span>
                        </div>
                    @elseif ($bulkMode === 'akun')
                        <div class="rf-row" style="flex: 1 1 100%">
                            <select class="rf-input tx-filter" wire:model="bulkAccountId" aria-label="Akun tujuan">
                                <option value="">Pilih akun tujuan</option>
                                @foreach ($activeAccounts as $a)
                                    <option value="{{ $a->id }}">{{ $a->name }}</option>
                                @endforeach
                            </select>
                            <button class="rf-btn primary sm" type="button" wire:click="applyBulkAccount">Terapkan</button>
                        </div>
                    @endif
                </div>
            </div>
        @endif

        @if ($rows->isEmpty())
            <div class="rf-empty">
                <x-rf.icon name="list" />
                @if ($hasFilter || $tab === 'tak')
                    <b>Tidak ada transaksi yang cocok.</b>
                    <p>{{ $tab === 'tak' && ! $hasFilter ? 'Semua pengeluaran bulan ini sudah punya pos.' : 'Coba ubah atau reset filter.' }}</p>
                @else
                    <b>Belum ada transaksi di {{ $monthLabel }}.</b>
                    <p>Catat pengeluaran lewat baris di atas, atau catat rezeki yang masuk.</p>
                @endif
            </div>
        @else
            <div class="rf-scroll">
                <table class="rf-table tx-table" style="min-width: 860px">
                    <thead>
                        <tr>
                            <th style="width: 44px"><input type="checkbox" wire:click="toggleAll" @checked($allShown) aria-label="Pilih semua baris yang tampil"></th>
                            <th>Tanggal</th>
                            <th>Catatan</th>
                            <th>Ruang › Pos</th>
                            <th>Akun</th>
                            <th class="num">Nominal</th>
                        </tr>
                    </thead>
                    <tbody>
                        @foreach ($rows as $t)
                            @php
                                $isExpense = $t->type === 'pengeluaran';
                                $need = $isExpense && $t->pos?->isUntracked();
                                $isSelected = in_array($t->id, $selectedIds, true);
                                $title = $t->note ?: ($t->category ?: ($isExpense ? 'Pengeluaran' : 'Pemasukan'));
                                $marks = array_filter([$t->recurring_rule_id ? 'Berulang' : null, $t->bill_id ? 'Tagihan' : null]);
                                $entries = $isExpense ? collect() : $t->sortedAllocationEntries()->filter(fn ($e) => $e->share_bp > 0 || $e->amount_minor > 0);
                                $dateFormat = $searching || (int) $t->occurred_on->format('Y') !== $thisYear ? 'j M Y' : 'j M';
                            @endphp
                            <tr wire:key="tx-{{ $t->id }}" tabindex="-1" data-href="{{ route('transaksi.detail', $t) }}"
                                @class(['sel' => $isSelected, 'need' => $need])
                                onclick="if (!event.target.closest('a,button,input,label,select')) window.location = this.dataset.href">
                                <td><input type="checkbox" wire:click="toggleRow({{ $t->id }})" @checked($isSelected) aria-label="Pilih {{ $title }}"></td>
                                <td class="rf-sub" style="white-space: nowrap">{{ $t->occurred_on->locale('id')->translatedFormat($dateFormat) }}</td>
                                <td>
                                    <a class="tx-note" href="{{ route('transaksi.detail', $t) }}">{{ $title }}</a>
                                    @if ($marks)<div class="rf-sub">{{ implode(' · ', $marks) }}</div>@endif
                                </td>
                                <td>
                                    @if ($isExpense)
                                        <x-rf.room-name :room="$t->room ?? ''" :pos="$t->pos?->name ?? $t->category" />
                                    @else
                                        <span class="rf-roomname">
                                            @if ($entries->isNotEmpty())
                                                <span class="tx-room-dots" aria-hidden="true">
                                                    @foreach ($entries as $e)<i class="rf-dot {{ $e->room && $e->room->slot >= 1 && $e->room->slot <= 8 ? 'c'.$e->room->slot : 'cn' }}"></i>@endforeach
                                                </span>
                                                Dialirkan {{ $t->allocationLabel() ?? '' }}
                                            @else
                                                Belum dialirkan
                                            @endif
                                        </span>
                                    @endif
                                </td>
                                <td>{{ $t->account?->name ?? '—' }}</td>
                                <td class="num"><span @class(['rf-money', 'rf-in' => ! $isExpense])>{{ Rupiah::signed($t->amount_minor, $t->type) }}</span></td>
                            </tr>
                        @endforeach
                    </tbody>
                </table>
            </div>
        @endif

        <div class="rf-row tx-foot">
            <span class="rf-sub">
                Menampilkan {{ $rows->count() }} dari {{ $total }} transaksi · Keluar <b>{{ Rupiah::format($sumOut) }}</b> · Masuk <b>{{ Rupiah::format($sumIn) }}</b>
            </span>
            @if ($total > $rows->count())
                <button type="button" class="rf-btn tonal sm" wire:click="showMore">Tampilkan lebih banyak</button>
            @endif
            <span class="rf-sub">Pintasan: <span class="rf-kbd">J</span> <span class="rf-kbd">K</span> pindah baris · <span class="rf-kbd">X</span> pilih · <span class="rf-kbd">E</span> buka · <span class="rf-kbd">/</span> cari</span>
        </div>
    </section>

</div>

@push('scripts')
<script>
    // Pintasan daftar Transaksi: J/K pindah baris, X pilih, E/Enter buka, / cari. Diabaikan saat mengetik.
    (function () {
        if (window.__rfTxKeys) return;
        window.__rfTxKeys = true;
        document.addEventListener('keydown', function (e) {
            if (e.ctrlKey || e.metaKey || e.altKey) return;
            var table = document.querySelector('[data-tx-table]');
            if (!table) return;
            var t = e.target, tag = t && t.tagName;
            if (t && (t.isContentEditable || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')) return;

            if (e.key === '/') {
                var search = document.getElementById('tx-cari');
                if (search) { e.preventDefault(); search.focus(); }
                return;
            }
            if (e.shiftKey) return;

            var rows = Array.prototype.slice.call(table.querySelectorAll('tbody tr[data-href]'));
            var current = document.activeElement && document.activeElement.closest ? document.activeElement.closest('tr[data-href]') : null;
            var key = e.key.toLowerCase();

            if (key === 'j' || key === 'k') {
                if (!rows.length) return;
                var at = current ? rows.indexOf(current) : -1;
                var next = key === 'j' ? Math.min(at + 1, rows.length - 1) : Math.max(at - 1, 0);
                e.preventDefault();
                rows[next].focus();
                rows[next].scrollIntoView({ block: 'nearest' });
            } else if (key === 'x' && current) {
                var box = current.querySelector('input[type="checkbox"]');
                if (box) { e.preventDefault(); box.click(); }
            } else if ((key === 'e' || e.key === 'Enter') && current && tag !== 'BUTTON' && tag !== 'A') {
                e.preventDefault();
                window.location = current.dataset.href;
            }
        });
    })();
</script>
@endpush
