@php use App\Support\Rupiah; @endphp

<div style="display:flex; flex-direction:column; gap:28px; max-width:880px; margin:0 auto; width:100%;">

    <div><a href="{{ route('denah') }}" style="font-size:13px; font-weight:600; color:var(--rf-on-surface-variant);">← Kembali ke Denah</a></div>

    @if ($profiles->isNotEmpty() && ! $editingProfile)
        <div style="display:flex; gap:8px; flex-wrap:wrap; align-items:center;">
            @foreach ($profiles as $p)
                <button type="button" wire:click="selectProfile({{ $p->id }})" class="rf-btn {{ $profile?->id === $p->id ? 'primary' : 'tonal' }}" style="padding:8px 16px; border-radius:999px;">{{ $p->name }}</button>
            @endforeach
            <button type="button" wire:click="startAddProfile" class="rf-btn tonal" style="padding:8px 16px; border-radius:999px;">+ Profil @unless ($canAddProfile) (Pro) @endunless</button>
        </div>
        @if ($profileError)
            <p class="error-text">{{ $profileError }}</p>
        @endif
    @endif

    @if ($archivedProfiles->isNotEmpty() && ! $editingProfile)
        <details class="rf-card">
            <summary style="cursor:pointer; font-weight:700; font-size:14px;">Profil diarsipkan ({{ $archivedProfiles->count() }})</summary>
            <div style="margin-top:16px; display:flex; flex-direction:column; gap:12px;">
                @foreach ($archivedProfiles as $p)
                    <div style="display:flex; align-items:center; gap:14px; padding:10px 0; border-top:1px solid var(--rf-outline-variant);">
                        <span style="flex:1; font-size:14px; color:var(--rf-on-surface-variant);">{{ $p->name }}</span>
                        <button type="button" wire:click="restoreProfile({{ $p->id }})" class="rf-btn tonal" style="padding:8px 14px;">Pulihkan</button>
                    </div>
                @endforeach
            </div>
        </details>
    @endif

    @if (! $profile && ! $editingProfile)
        <div class="rf-card">
            <p style="color:var(--rf-on-surface-variant); font-size:14px; margin-bottom:14px;">Belum ada profil harta. Tambahkan untuk mulai memantau zakat.</p>
            <button type="button" wire:click="startAddProfile" class="rf-btn primary">+ Profil harta</button>
        </div>
    @endif

    @if ($editingProfile)
        <form wire:submit="saveProfile" class="rf-card">
            <h2 class="rf-serif" style="font-size:18px; margin-bottom:14px;">{{ $profile ? 'Ubah profil harta' : 'Profil harta baru' }}</h2>
            <div class="rf-field">
                <label for="zakat-name">Nama profil</label>
                <input type="text" id="zakat-name" wire:model="name" maxlength="40" autofocus>
            </div>
            <div class="rf-field">
                <label for="zakat-wealth">Harta bersih</label>
                <input type="number" id="zakat-wealth" wire:model="netWealth" min="0">
            </div>
            <div class="rf-field">
                <label for="zakat-nisab">Nisab hari ini</label>
                <input type="number" id="zakat-nisab" wire:model="nisab" min="0">
            </div>
            <div class="rf-field">
                <label for="zakat-haul-started">Mulai haul (label, opsional)</label>
                <input type="text" id="zakat-haul-started" wire:model="haulStartedLabel" maxlength="40" placeholder="1 Muharram 1448">
            </div>
            <div class="rf-field">
                <label for="zakat-haul-due">Jatuh tempo (label, opsional)</label>
                <input type="text" id="zakat-haul-due" wire:model="haulDueLabel" maxlength="40" placeholder="1 Muharram 1449">
            </div>
            <div style="display:flex; gap:12px;">
                <div class="rf-field" style="flex:1;">
                    <label for="zakat-haul-day">Hari ke- (opsional)</label>
                    <input type="number" id="zakat-haul-day" wire:model="haulDay" min="0">
                </div>
                <div class="rf-field" style="flex:1;">
                    <label for="zakat-haul-total">Dari total hari (opsional)</label>
                    <input type="number" id="zakat-haul-total" wire:model="haulTotalDays" min="1">
                </div>
            </div>
            <p style="font-size:12.5px; color:var(--rf-on-surface-variant); margin:4px 0 0;">Haul di sini masih isian manual — perhitungan otomatis dari kalender Hijriyah menyusul.</p>
            @if ($profileError)
                <p class="error-text">{{ $profileError }}</p>
            @endif
            <div style="display:flex; gap:12px; margin-top:12px;">
                <button type="submit" class="rf-btn primary">Simpan</button>
                <button type="button" wire:click="cancelEditProfile" class="rf-btn tonal">Batal</button>
            </div>
        </form>
    @endif

    @if ($profile && ! $editingProfile)
        <div style="display:flex; align-items:flex-end; justify-content:space-between; gap:16px; flex-wrap:wrap;">
            <div>
                <div style="font-size:13px; color:var(--rf-on-surface-variant); margin-bottom:4px;">Ruang Memberi</div>
                <h1 class="rf-serif" style="font-size:28px;">Zakat mal</h1>
            </div>
            <div style="display:flex; gap:10px;">
                <button type="button" wire:click="startEditProfile" class="rf-btn tonal">Ubah profil harta</button>
                <button type="button" wire:click="archiveProfile({{ $profile->id }})" wire:confirm="Arsipkan profil {{ $profile->name }}?" class="rf-btn tonal">Arsipkan</button>
            </div>
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

        @if (! $payingZakat)
            <div class="rf-card" style="display:flex; align-items:center; justify-content:space-between; gap:16px; flex-wrap:wrap;">
                <div>
                    <div style="font-weight:700; font-size:15px; margin-bottom:4px;">Tunaikan zakat</div>
                    <div style="font-size:13px; color:var(--rf-on-surface-variant);">2,5% dari harta bersih — bisa diubah sebelum disimpan</div>
                </div>
                <button type="button" wire:click="startPayZakat" class="rf-btn primary">Tunaikan {{ Rupiah::format($profile->suggestedZakatMinor()) }}</button>
            </div>
        @else
            <form wire:submit="payZakat" class="rf-card">
                <h2 class="rf-serif" style="font-size:18px; margin-bottom:14px;">Tunaikan zakat</h2>
                <div class="rf-field">
                    <label for="zakat-amount">Jumlah zakat</label>
                    <input type="number" id="zakat-amount" wire:model="zakatAmount" min="1" autofocus>
                </div>
                <div class="rf-field">
                    <label for="zakat-account">Akun sumber</label>
                    <select id="zakat-account" wire:model="zakatAccountId">
                        <option value="">Pilih akun</option>
                        @foreach ($accounts as $account)
                            <option value="{{ $account->id }}">{{ $account->name }}</option>
                        @endforeach
                    </select>
                </div>
                <p style="font-size:12.5px; color:var(--rf-on-surface-variant); margin:4px 0 0;">Tercatat sebagai pengeluaran kategori Zakat mal di ruang Memberi, lalu haul baru dimulai.</p>
                @if ($payError)
                    <p class="error-text">{{ $payError }}</p>
                @endif
                <div style="display:flex; gap:12px; margin-top:12px;">
                    <button type="submit" class="rf-btn primary">Konfirmasi tunaikan</button>
                    <button type="button" wire:click="cancelPayZakat" class="rf-btn tonal">Batal</button>
                </div>
            </form>
        @endif

        <div style="display:flex; gap:12px; align-items:flex-start; padding:4px 4px 8px; font-size:12.5px; color:var(--rf-on-surface-variant); line-height:1.6;">
            <span>⚠</span>
            <span>Harga emas, tanggal, dan nilai di atas hanya ilustrasi (data contoh). Asumsi: nisab 85 g emas, zakat 2,5%, haul 1 tahun Hijriyah. Ini alat bantu hitung, bukan fatwa — periksa kembali dengan rujukan fikih yang dipercaya.</span>
        </div>
    @endif

</div>
