@php use App\Support\Rupiah; @endphp

@push('styles')
<style>
    /* Halaman Zakat (prefix zk-). */
    .zk-col { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
    .zk-chip-pro { margin-left: 4px; }
    .zk-meter { margin-top: 22px; }
    .zk-meter-note { margin-top: 14px; }
    .zk-labels { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 12px 16px; margin-top: 12px; }
    .zk-labels > div { min-width: 0; }
    @media (max-width: 560px) { .zk-labels > div { flex: 1 1 100%; text-align: left !important; } }
    .zk-labels b, .zk-lbl-strong { color: var(--rf-on-background); }
    .zk-field + .zk-field { margin-top: 14px; }
    .zk-amount { font-weight: 700; font-size: 20px; font-variant-numeric: tabular-nums; }
    .zk-pay-btn { width: 100%; margin-top: 16px; }
    .zk-edit { margin-top: 16px; padding: 16px; border-radius: 14px; background: var(--rf-surface-container-low); }
    .zk-edit .rf-form-grid { margin-top: 12px; }
    .zk-scroll { position: relative; } /* sr-only absolut di dalam tabel harus terpotong oleh area gulir */
    .zk-table { min-width: 760px; }
    .zk-stats { grid-template-columns: repeat(auto-fit, minmax(min(200px, 100%), 1fr)); }
    .zk-stats .rf-stat .v { font-size: 19px; white-space: nowrap; }
    .zk-year-chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .zk-archived-row { display: flex; align-items: center; gap: 14px; padding: 10px 0; border-top: 1px solid var(--rf-outline-variant); }
    .zk-archived-row .name { flex: 1 1 auto; min-width: 0; font-size: 14px; color: var(--rf-on-surface-variant); }
    .zk-copy { display: inline-flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .zk-readout { align-items: flex-start; margin-top: 16px; }
    .zk-readout .ic { margin-top: 2px; flex: none; }
</style>
@endpush

<div class="rf-page">

    <x-rf.page-head :sub="'Modul Memberi · '.$dateLabel" title="Zakat" lead="Bantuan hitung nisab dan haul, bukan fatwa.">
        @if ($profiles->isNotEmpty() && ! $editingProfile)
            <x-slot:actions>
                @foreach ($profiles as $p)
                    <button type="button" wire:click="selectProfile({{ $p->id }})" wire:key="chip-{{ $p->id }}" class="rf-chip {{ $profile?->id === $p->id ? 'on' : '' }}" @if ($profile?->id === $p->id) aria-pressed="true" @endif>{{ $p->name }}</button>
                @endforeach
                <button type="button" wire:click="startAddProfile" class="rf-chip">+ Profil @unless ($canAddProfile)<span class="rf-tagpro zk-chip-pro">PRO</span>@endunless</button>
            </x-slot:actions>
        @endif
    </x-rf.page-head>

    @if ($profileError && ! $editingProfile)
        <x-rf.banner tone="warn" role="alert">{{ $profileError }}</x-rf.banner>
    @endif

    @if ($archivedProfiles->isNotEmpty() && ! $editingProfile)
        <details class="rf-card">
            <summary class="rf-h3" style="cursor: pointer;">Profil diarsipkan ({{ $archivedProfiles->count() }})</summary>
            <div style="margin-top: 12px;">
                @foreach ($archivedProfiles as $p)
                    <div class="zk-archived-row" wire:key="arsip-{{ $p->id }}">
                        <span class="name">{{ $p->name }}</span>
                        <button type="button" wire:click="restoreProfile({{ $p->id }})" class="rf-btn tonal sm">Pulihkan</button>
                    </div>
                @endforeach
            </div>
        </details>
    @endif

    @if (! $profile && ! $editingProfile)
        <section class="rf-card">
            <div class="rf-empty">
                <x-rf.icon name="heart" />
                <b>Belum ada profil harta</b>
                <p>Tambahkan profil untuk mulai memantau nisab dan haul zakatmu.</p>
                <div class="rf-actions">
                    <button type="button" wire:click="startAddProfile" class="rf-btn primary"><x-rf.icon name="plus" />Profil harta</button>
                </div>
            </div>
        </section>
    @endif

    @if ($editingProfile)
        <form wire:submit="saveProfile" class="rf-card">
            <h2 class="rf-h2" style="margin-bottom: 14px;">{{ $editingProfileId ? 'Ubah profil harta' : 'Profil harta baru' }}</h2>
            <div class="rf-form-grid">
                <div class="rf-field full">
                    <label for="zakat-name">Nama profil</label>
                    <input class="rf-input" type="text" id="zakat-name" wire:model="name" maxlength="40" autofocus>
                </div>
                <div class="rf-field">
                    <label for="zakat-wealth">Harta bersih (Rp)</label>
                    <input class="rf-input" type="number" id="zakat-wealth" wire:model="netWealth" min="0">
                    @if ($piutangSuggestion > 0 && (string) $piutangSuggestion !== $netWealth)
                        <div class="rf-row" style="gap: 8px; flex-wrap: wrap; margin-top: 6px">
                            <span class="rf-sub">Dari Utang-piutang: {{ Rupiah::format($piutangSuggestion) }} (sisa piutang yang diperkirakan kembali)</span>
                            <button type="button" class="rf-btn ghost sm" wire:click="usePiutangSuggestion">Pakai</button>
                        </div>
                    @endif
                </div>
                <div class="rf-field">
                    <label for="zakat-nisab">Nisab hari ini (Rp)</label>
                    <input class="rf-input" type="number" id="zakat-nisab" wire:model="nisab" min="0">
                </div>
                <div class="rf-field">
                    <label for="zakat-haul-started">Mulai haul (label, opsional)</label>
                    <input class="rf-input" type="text" id="zakat-haul-started" wire:model="haulStartedLabel" maxlength="40" placeholder="1 Muharram 1448">
                </div>
                <div class="rf-field">
                    <label for="zakat-haul-due">Jatuh tempo (label, opsional)</label>
                    <input class="rf-input" type="text" id="zakat-haul-due" wire:model="haulDueLabel" maxlength="40" placeholder="1 Muharram 1449">
                </div>
                <div class="rf-field">
                    <label for="zakat-haul-day">Hari ke- (opsional)</label>
                    <input class="rf-input" type="number" id="zakat-haul-day" wire:model="haulDay" min="0">
                </div>
                <div class="rf-field">
                    <label for="zakat-haul-total">Dari total hari (opsional)</label>
                    <input class="rf-input" type="number" id="zakat-haul-total" wire:model="haulTotalDays" min="1">
                </div>
            </div>
            <p class="rf-hint" style="margin-top: 12px;"><x-rf.icon name="info" />Haul di sini masih isian manual, perhitungan otomatis dari kalender Hijriyah menyusul.</p>
            @if ($profileError)
                <p class="rf-error" style="margin-top: 12px;" role="alert"><x-rf.icon name="alert" />{{ $profileError }}</p>
            @endif
            <div class="rf-actions" style="margin-top: 16px;">
                <button type="submit" class="rf-btn primary">Simpan</button>
                <button type="button" wire:click="cancelEditProfile" class="rf-btn ghost">Batal</button>
            </div>
        </form>
    @endif

    @if ($profile && ! $editingProfile)
        @php
            $wealth = (int) $profile->net_wealth_minor;
            $nisab = (int) $profile->nisab_minor;
            $hasNisab = $nisab > 0;
            $above = $profile->isAboveNisab();
            $diff = $wealth - $nisab;
            $diffPercent = $hasNisab ? intdiv(abs($diff) * 100 + intdiv($nisab, 2), $nisab) : null;

            // Skala meter dibulatkan ke atas: 13/10 dari yang terbesar (harta atau nisab), supaya kedua penanda selalu di dalam.
            $scale = intdiv(max($wealth, $nisab) * 13, 10);
            $wealthBp = $scale > 0 ? intdiv($wealth * 10000, $scale) : 0;
            $nisabBp = $scale > 0 ? intdiv($nisab * 10000, $scale) : 0;
            $css = fn (int $bp): string => intdiv($bp, 100).'.'.sprintf('%02d', $bp % 100).'%';
            $nisabAnchorRight = $nisabBp > 50;
            $wealthAnchorRight = $wealthBp > 50;

            $hasHaul = $profile->haul_day !== null && $profile->haul_total_days;
            $haulDay = (int) $profile->haul_day;
            $haulTotal = (int) $profile->haul_total_days;
            $haulBp = $hasHaul ? min(intdiv($haulDay * 10000, $haulTotal), 10000) : 0;
            $haulLeft = $haulTotal - $haulDay;

            $suggested = (int) $profile->suggestedZakatMinor();
            $payAmount = max((int) $zakatAmount, 0);
        @endphp

        <div class="rf-split">
            <div class="a zk-col">

                <section class="rf-card">
                    <div class="rf-card-head">
                        <h2 class="rf-h2">Harta dan nisab</h2>
                        @if (! $hasNisab)
                            <x-rf.pill tone="neutral" icon="info">Nisab belum diisi</x-rf.pill>
                        @elseif ($above)
                            <x-rf.pill tone="good">Di atas nisab</x-rf.pill>
                        @else
                            <x-rf.pill tone="neutral" icon="info">Di bawah nisab</x-rf.pill>
                        @endif
                    </div>

                    <div class="rf-stats zk-stats">
                        <div class="rf-stat"><div class="l">Harta bersih</div><div class="v">{{ Rupiah::format($wealth) }}</div><div class="d">Diisi manual di profil {{ $profile->name }}</div></div>
                        <div class="rf-stat"><div class="l">Nisab hari ini</div><div class="v">{{ Rupiah::format($nisab) }}</div><div class="d">Diisi manual</div></div>
                        <div class="rf-stat">
                            <div class="l">Selisih</div>
                            <div class="v">{{ Rupiah::signed($diff, $diff >= 0 ? 'pemasukan' : 'pengeluaran') }}</div>
                            <div class="d">
                                @if (! $hasNisab)
                                    Isi nisab untuk membandingkan
                                @elseif ($diff > 0)
                                    <x-rf.icon name="arrow-up" />{{ $diffPercent }}% di atas nisab
                                @elseif ($diff < 0)
                                    <x-rf.icon name="arrow-down" />{{ $diffPercent }}% di bawah nisab
                                @else
                                    Tepat di nisab
                                @endif
                            </div>
                        </div>
                    </div>

                    @if ($hasNisab && $scale > 0)
                        <div class="zk-meter">
                            <div class="rf-meter" role="img" aria-label="Harta {{ Rupiah::compact($wealth) }}; nisab {{ Rupiah::compact($nisab) }}; skala sampai {{ Rupiah::compact($scale) }}"><i style="width: {{ $css($wealthBp) }}"></i><b style="left: {{ $css($nisabBp) }}"></b></div>
                            <div class="rf-mlabels">
                                <span style="left: {{ $css($nisabBp) }}; top: 0; {{ $nisabAnchorRight ? 'transform: translateX(-100%); text-align: right; padding-right: 8px;' : 'transform: none; text-align: left; padding-left: 8px;' }}">Nisab {{ Rupiah::compact($nisab) }}</span>
                                <span style="left: {{ $css($wealthBp) }}; top: 19px; {{ $wealthAnchorRight ? 'transform: translateX(-100%); text-align: right; padding-right: 8px;' : 'transform: none; text-align: left; padding-left: 8px;' }}"><b class="zk-lbl-strong">Harta {{ Rupiah::compact($wealth) }}</b></span>
                            </div>
                        </div>
                    @endif

                    <p class="rf-sub zk-meter-note">Nisab diisi manual dan tidak diperbarui otomatis. Ubah angkanya lewat form profil harta.</p>
                    <div class="rf-actions" style="margin-top: 12px;">
                        <button type="button" wire:click="startEditProfile" class="rf-btn tonal sm"><x-rf.icon name="pencil" />Ubah profil harta</button>
                        <button type="button" wire:click="archiveProfile({{ $profile->id }})" wire:confirm="Arsipkan profil {{ $profile->name }}?" class="rf-btn ghost sm">Arsipkan</button>
                    </div>
                </section>

                <section class="rf-card">
                    <div class="rf-card-head">
                        <h2 class="rf-h2">Haul</h2>
                        @if (! $hasHaul)
                            <x-rf.pill tone="neutral" icon="clock">Haul belum diisi</x-rf.pill>
                        @elseif ($haulLeft > 0)
                            <x-rf.pill tone="info" icon="clock">{{ $haulLeft }} hari lagi</x-rf.pill>
                        @else
                            <x-rf.pill tone="good">Haul genap</x-rf.pill>
                        @endif
                    </div>

                    @if ($hasHaul)
                        <div class="rf-meter" role="img" aria-label="Haul hari ke-{{ $haulDay }} dari {{ $haulTotal }}"><i style="width: {{ $css($haulBp) }}"></i><b style="left: {{ $css($haulBp) }}"></b></div>
                    @endif

                    <div class="rf-sub zk-labels">
                        <div><b class="zk-lbl-strong">Mulai</b><br>{{ $profile->haul_started_label ?: 'Belum diisi' }}</div>
                        @if ($hasHaul)
                            <div style="text-align: center;"><b class="zk-lbl-strong">Hari ini</b><br>Hari ke-{{ $haulDay }} dari {{ $haulTotal }}</div>
                        @endif
                        <div style="text-align: right;"><b class="zk-lbl-strong">Jatuh tempo</b><br>{{ $profile->haul_due_label ?: 'Belum diisi' }}</div>
                    </div>

                    <p class="rf-sub" style="margin-top: 14px;">
                        @unless ($hasHaul)
                            Isi hari ke- dan total hari lewat form profil harta untuk melihat meter haul.
                        @endunless
                        Tanggal Hijriyah di sini adalah label yang kamu isi sendiri; web belum menghitungnya otomatis.
                    </p>
                </section>

            </div>

            <div class="b zk-col">

                <section class="rf-card">
                    <h2 class="rf-h2" style="margin-bottom: 12px;">Tunaikan zakat</h2>
                    <form wire:submit="payZakat">
                        <div class="rf-field zk-field">
                            <label for="zakat-amount">Nominal (bawaan 2,5% × harta bersih)</label>
                            <input class="rf-input zk-amount" type="number" id="zakat-amount" wire:model.live.debounce.400ms="zakatAmount" min="1" placeholder="{{ $suggested }}">
                        </div>
                        <div class="rf-field zk-field">
                            <label for="zakat-account">Akun sumber</label>
                            <select class="rf-input" id="zakat-account" wire:model="zakatAccountId">
                                <option value="">Pilih akun</option>
                                @foreach ($accounts as $account)
                                    <option value="{{ $account->id }}">{{ $account->name }}</option>
                                @endforeach
                            </select>
                        </div>
                        <div class="rf-field zk-field">
                            <label for="zakat-institution">Lembaga penerima (opsional)</label>
                            <input class="rf-input" type="text" id="zakat-institution" wire:model="zakatInstitution" maxlength="100" placeholder="Mis. BAZNAS atau LAZ resmi">
                        </div>
                        <div class="rf-field zk-field">
                            <label for="zakat-receipt">No. bukti setor (opsional)</label>
                            <input class="rf-input" type="text" id="zakat-receipt" wire:model="zakatReceipt" maxlength="60" placeholder="Dari lembaga, bila ada">
                        </div>
                        @if ($payError)
                            <p class="rf-error" style="margin-top: 12px;" role="alert"><x-rf.icon name="alert" />{{ $payError }}</p>
                        @endif
                        <button type="submit" class="rf-btn primary zk-pay-btn">Tunaikan {{ Rupiah::format($payAmount) }}</button>
                    </form>
                    @if ($payNotice)
                        <x-rf.banner tone="good" role="status" style="margin-top: 12px;">{{ $payNotice }}</x-rf.banner>
                    @endif
                    <p class="rf-sub" style="margin-top: 12px;">Dicatat sebagai pengeluaran di Memberi › Zakat mal, lalu haul baru dimulai. Nominal boleh diubah. Lembaga dan nomor bukti masuk rekap zakat tahunan di bawah.</p>
                </section>

                <section class="rf-card">
                    <h2 class="rf-h3" style="margin-bottom: 8px;">Asumsi yang dipakai</h2>
                    <p class="rf-sub">Nisab 85 g emas, tarif 2,5%, haul 1 tahun Hijriyah. Ini nilai bawaan sementara; verifikasi dengan rujukan kitab masih menjadi keputusan pemilik aplikasi.</p>
                </section>

            </div>
        </div>
    @endif

    @if (! $editingProfile)
        <section class="rf-card" id="rekap">
            <div class="rf-card-head">
                <div class="rf-row" style="gap: 10px; flex-wrap: nowrap;"><h2 class="rf-h2">Rekap zakat tahunan</h2><x-rf.pill tone="good" icon="">Baru</x-rf.pill></div>
                <div class="zk-year-chips" role="group" aria-label="Pilih tahun rekap">
                    @foreach ($rekapYears as $y)
                        <button type="button" wire:click="selectYear({{ $y }})" wire:key="tahun-{{ $y }}" class="rf-chip {{ $y === $rekapYear ? 'on' : '' }}" @if ($y === $rekapYear) aria-pressed="true" @endif>{{ $y }}</button>
                    @endforeach
                </div>
            </div>
            <p class="rf-sub" style="margin: -6px 0 12px;">Daftar zakat yang kamu catat dalam setahun, siap dipakai saat mengisi SPT Tahunan. Lembaga penerima dan nomor bukti setor kamu lengkapi sendiri di sini.</p>

            <div class="rf-scroll zk-scroll">
                <table class="rf-table zk-table">
                    <thead>
                        <tr><th>Tanggal</th><th>Jenis zakat</th><th>Lembaga penerima</th><th>No. bukti setor</th><th class="num">Nominal</th><th style="width: 110px;"><span class="rf-sr-only">Aksi</span></th></tr>
                    </thead>
                    <tbody>
                        @forelse ($rekapRows as $row)
                            <tr wire:key="rekap-{{ $row->id }}" @class(['sel' => $editingRekapId === $row->id])>
                                <td class="rf-sub" style="white-space: nowrap;">{{ $row->occurred_on->locale('id')->translatedFormat('j M Y') }}</td>
                                <td style="font-weight: 700;">{{ $row->category }}</td>
                                <td>@if ($row->recipient_institution){{ $row->recipient_institution }}@else<span class="rf-sub">Belum diisi</span>@endif</td>
                                <td>@if ($row->receipt_number){{ $row->receipt_number }}@else<span class="rf-sub">Belum diisi</span>@endif</td>
                                <td class="num"><span class="rf-money">{{ Rupiah::format($row->amount_minor) }}</span></td>
                                <td><button type="button" wire:click="startEditRekap({{ $row->id }})" class="rf-btn ghost sm">{{ $row->recipient_institution && $row->receipt_number ? 'Ubah' : 'Lengkapi' }}</button></td>
                            </tr>
                        @empty
                            <tr><td colspan="6" class="rf-sub">Belum ada zakat yang dicatat pada {{ $rekapYear }}. Zakat yang kamu tunaikan lewat halaman ini muncul di sini.</td></tr>
                        @endforelse
                    </tbody>
                    <tfoot>
                        <tr><td colspan="4">Total zakat {{ $rekapYear }}</td><td class="num">{{ Rupiah::format($rekapTotal) }}</td><td></td></tr>
                    </tfoot>
                </table>
            </div>

            @if ($editingRekapId)
                @php $editingRow = $rekapRows->firstWhere('id', $editingRekapId); @endphp
                @if ($editingRow)
                    <form wire:submit="saveRekap" class="zk-edit">
                        <div class="rf-h3">Lengkapi bukti setor · {{ $editingRow->occurred_on->locale('id')->translatedFormat('j M Y') }} · {{ Rupiah::format($editingRow->amount_minor) }}</div>
                        <div class="rf-form-grid">
                            <div class="rf-field">
                                <label for="rekap-institution">Lembaga penerima</label>
                                <input class="rf-input" type="text" id="rekap-institution" wire:model="rekapInstitution" maxlength="100" placeholder="Mis. BAZNAS atau LAZ resmi">
                            </div>
                            <div class="rf-field">
                                <label for="rekap-receipt">No. bukti setor</label>
                                <input class="rf-input" type="text" id="rekap-receipt" wire:model="rekapReceipt" maxlength="60" placeholder="Dari lembaga, bila ada">
                            </div>
                        </div>
                        @if ($rekapError)
                            <p class="rf-error" style="margin-top: 12px;" role="alert"><x-rf.icon name="alert" />{{ $rekapError }}</p>
                        @endif
                        <div class="rf-actions" style="margin-top: 14px;">
                            <button type="submit" class="rf-btn primary sm">Simpan</button>
                            <button type="button" wire:click="cancelEditRekap" class="rf-btn ghost sm">Batal</button>
                        </div>
                    </form>
                @endif
            @elseif ($rekapError)
                <p class="rf-error" style="margin-top: 12px;" role="alert"><x-rf.icon name="alert" />{{ $rekapError }}</p>
            @endif

            <div class="rf-row" style="gap: 10px; margin-top: 16px;">
                <a class="rf-btn tonal sm" href="{{ route('zakat.rekap.pdf', ['year' => $rekapYear]) }}"><x-rf.icon name="download" />Unduh rekap (PDF)</a>
                <div class="zk-copy" x-data="{ copied: false, failed: false }">
                    <button type="button" class="rf-btn ghost sm" data-csv="{{ $rekapCsv }}"
                        x-on:click="(navigator.clipboard ? navigator.clipboard.writeText($el.dataset.csv) : Promise.reject()).then(() => { copied = true; failed = false; setTimeout(() => copied = false, 2500) }).catch(() => { failed = true; copied = false })">Salin sebagai CSV</button>
                    <span class="rf-sub" x-show="copied" x-cloak role="status">CSV tersalin.</span>
                    <a class="rf-link" x-show="failed" x-cloak href="{{ route('zakat.rekap.csv', ['year' => $rekapYear]) }}">Tidak bisa menyalin. Unduh CSV</a>
                </div>
            </div>

            <div class="rf-readout zk-readout">
                <x-rf.icon name="info" />
                <span>Zakat yang dibayar lewat BAZNAS atau LAZ resmi dapat menjadi pengurang penghasilan kena pajak bila bukti setornya dilampirkan ke SPT (UU 23/2011 Pasal 22). Ketentuan rinci, termasuk jenis zakat dan dasar pengurangnya, perlu dicek ke aturan DJP terbaru atau konsultan pajak; Rizqflow bukan nasihat pajak. Bukti setor tidak disimpan di server, hanya daftar di atas.</span>
            </div>
        </section>
    @endif

</div>
