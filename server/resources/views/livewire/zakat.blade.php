@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:28px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('denah') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Denah</a></div>

    @if (! $profile)
        <div class="rf-card">
            <p style="color:var(--rf-on-surface-variant); font-size:14px;">Belum ada profil harta. Tambahkan dari ruang Memberi untuk mulai memantau zakat.</p>
        </div>
    @else
        <div>
            <div style="font-size:13px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Ruang Memberi</div>
            <h1 class="rf-serif" style="font-size:28px;">Zakat mal</h1>
        </div>

        @php
            $hasHaul = $profile->haul_day && $profile->haul_total_days;
            $circumference = 301.59; // r=48
            $percent = $hasHaul ? min($profile->haul_day / $profile->haul_total_days, 1) : 0;
            $dash = round($circumference * $percent, 1);
            $gap = round($circumference - $dash, 1);
        @endphp

        <div class="rf-card" style="padding:32px; display:flex; flex-direction:column; align-items:center; gap:18px; text-align:center;">
            @if ($hasHaul)
                <svg width="140" height="140" viewBox="0 0 140 140" style="transform:rotate(-90deg);" aria-hidden="true">
                    <circle cx="70" cy="70" r="48" fill="none" stroke="var(--rf-surface-container-highest)" stroke-width="13"></circle>
                    <circle cx="70" cy="70" r="48" fill="none" stroke="var(--rf-room-1)" stroke-width="13" stroke-linecap="round" stroke-dasharray="{{ $dash }} {{ $gap }}"></circle>
                    <text x="70" y="64" transform="rotate(90 70 70)" text-anchor="middle" font-family="Libre Caslon Text, serif" font-size="15" fill="var(--rf-on-surface-variant)">Hari ke-</text>
                    <text x="70" y="90" transform="rotate(90 70 70)" text-anchor="middle" font-family="Manrope, sans-serif" font-size="30" font-weight="700" fill="var(--rf-on-background)">{{ $profile->haul_day }}</text>
                </svg>
                <div style="font-size:14px; color:var(--rf-on-surface-variant);">dari {{ $profile->haul_total_days }} hari haul</div>
            @endif

            <span class="rf-pill {{ $profile->isAboveNisab() ? 'good' : 'neutral' }}">
                {{ $profile->isAboveNisab() ? 'Di atas nisab' : 'Belum mencapai nisab' }}
            </span>

            <div style="display:grid; grid-template-columns:1fr 1fr; gap:24px; width:100%; max-width:480px; margin-top:12px; padding-top:20px; border-top:1px solid var(--rf-outline-variant); text-align:left;">
                @if ($profile->haul_started_label)
                    <div>
                        <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Mulai haul</div>
                        <div style="font-weight:600; font-size:14px;">{{ $profile->haul_started_label }}</div>
                    </div>
                    <div>
                        <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Jatuh tempo</div>
                        <div style="font-weight:600; font-size:14px;">{{ $profile->haul_due_label }}</div>
                    </div>
                @endif
                <div>
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Harta bersih</div>
                    <div style="font-weight:700; font-size:16px;">{{ Rupiah::format($profile->net_wealth_minor) }}</div>
                </div>
                <div>
                    <div style="font-size:12px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Nisab hari ini</div>
                    <div style="font-weight:700; font-size:16px;">{{ Rupiah::format($profile->nisab_minor) }}</div>
                </div>
            </div>
        </div>

        <div class="rf-card" style="display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap;">
            <div>
                <div style="font-weight:700; font-size:15px; margin-bottom:4px;">Tunaikan zakat</div>
                <div style="font-size:13px; color:var(--rf-on-surface-variant);">2,5% dari harta bersih — bisa diubah sebelum disimpan</div>
            </div>
            <button type="button" class="rf-btn primary" disabled>Tunaikan {{ Rupiah::format($profile->suggestedZakatMinor()) }}</button>
        </div>

        <div style="display:flex; gap:12px; align-items:flex-start; padding:4px 4px 8px; font-size:12.5px; color:var(--rf-on-surface-variant); line-height:1.6;">
            <span>⚠</span>
            <span>Harga emas, tanggal, dan nilai di atas hanya ilustrasi (data contoh). Asumsi: nisab 85 g emas, zakat 2,5%, haul 1 tahun Hijriyah. Ini alat bantu hitung, bukan fatwa — periksa kembali dengan rujukan fikih yang dipercaya.</span>
        </div>
    @endif

</div>
