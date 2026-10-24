<?php

namespace App\Livewire;

use App\Models\Category;
use App\Models\ZakatProfile;
use App\Support\Feature;
use App\Support\Ledger;
use App\Support\LedgerException;
use App\Support\Rupiah;
use App\Support\ZakatRekap;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Beranda zakat + kartu haul (S14/S16) dan profil harta (S15), tunaikan zakat (S17). Profil
 * pertama selalu Gratis; profil tambahan butuh Feature::MultiZakatProfile (Pro) — satu chip per
 * profil, "+ Profil" ditolak tanpa Pro begitu sudah ada satu. Profil yang diarsipkan tidak
 * terkunci saat turun paket (datanya tetap ada), hanya disembunyikan dari daftar chip; profil
 * aktif terakhir tidak bisa diarsipkan.
 *
 * Haul (S16): web belum punya tanggal mulai haul sungguhan karena metode kalender Hijriyah belum
 * diputuskan (lihat docs/konsep.md), jadi `haul_day`/`haul_total_days`/label mulai-jatuh tempo di
 * sini murni isian manual pengguna, bukan dihitung otomatis — berbeda dari HaulTracker di :domain
 * yang menghitung dari tanggal sungguhan. `status` dihitung ulang dari nisab dan isian haul itu
 * setiap profil disimpan, supaya tidak ada kombinasi status yang tidak masuk akal.
 *
 * Tunaikan zakat dicatat lewat Ledger::recordExpense (saldo dan validasi seragam dengan halaman
 * lain), dengan lembaga penerima dan nomor bukti setor opsional. Rekap zakat tahunan di bagian
 * bawah halaman memakai App\Support\ZakatRekap, sama dengan unduhan PDF/CSV; lembaga dan nomor
 * bukti setor bisa dilengkapi belakangan, hanya untuk transaksi zakat milik pengguna.
 */
class Zakat extends Component
{
    public ?int $selectedProfileId = null;

    public bool $editingProfile = false;

    public ?int $editingProfileId = null;

    public string $name = 'Utama';

    public string $netWealth = '0';

    public string $nisab = '0';

    public string $haulStartedLabel = '';

    public string $haulDueLabel = '';

    public string $haulDay = '';

    public string $haulTotalDays = '';

    public ?string $profileError = null;

    public string $zakatAmount = '';

    public ?int $zakatAccountId = null;

    public string $zakatInstitution = '';

    public string $zakatReceipt = '';

    public ?string $payError = null;

    public ?string $payNotice = null;

    /** Tahun rekap zakat (?tahun=2025); kosong atau tidak sah = tahun berjalan. */
    #[Url(as: 'tahun')]
    public ?string $tahun = null;

    public ?int $editingRekapId = null;

    public string $rekapInstitution = '';

    public string $rekapReceipt = '';

    public ?string $rekapError = null;

    public function mount(): void
    {
        $this->selectedProfileId = $this->activeProfiles()->first()?->id;
        $this->resetPayForm();
    }

    /** Isian tunaikan zakat kembali ke bawaan: 2,5% harta bersih profil terpilih, tanpa akun/lembaga/nomor. */
    private function resetPayForm(): void
    {
        $this->zakatAmount = (string) ($this->profile()?->suggestedZakatMinor() ?? 0);
        $this->zakatAccountId = null;
        $this->zakatInstitution = '';
        $this->zakatReceipt = '';
        $this->payError = null;
        $this->payNotice = null;
    }

    private function activeProfiles()
    {
        return auth()->user()->zakatProfiles()->active()->orderBy('id')->get();
    }

    private function profile(): ?ZakatProfile
    {
        if ($this->selectedProfileId === null) {
            return null;
        }

        return auth()->user()->zakatProfiles()->find($this->selectedProfileId);
    }

    public function selectProfile(int $profileId): void
    {
        $profile = auth()->user()->zakatProfiles()->active()->find($profileId);
        if ($profile) {
            $this->selectedProfileId = $profile->id;
            $this->editingProfile = false;
            $this->resetPayForm();
        }
    }

    public function startAddProfile(): void
    {
        $this->profileError = null;

        if (auth()->user()->zakatProfiles()->count() >= 1 && ! auth()->user()->entitlements()->isEnabled(Feature::MultiZakatProfile)) {
            $this->profileError = 'Profil harta lebih dari satu adalah fitur Pro.';

            return;
        }

        $this->editingProfileId = null;
        $this->name = 'Utama';
        $this->netWealth = '0';
        $this->nisab = '0';
        $this->haulStartedLabel = '';
        $this->haulDueLabel = '';
        $this->haulDay = '';
        $this->haulTotalDays = '';
        $this->editingProfile = true;
    }

    public function startEditProfile(): void
    {
        $profile = $this->profile();
        $this->editingProfileId = $profile?->id;
        $this->name = $profile?->name ?? 'Utama';
        $this->netWealth = (string) ($profile?->net_wealth_minor ?? 0);
        $this->nisab = (string) ($profile?->nisab_minor ?? 0);
        $this->haulStartedLabel = $profile?->haul_started_label ?? '';
        $this->haulDueLabel = $profile?->haul_due_label ?? '';
        $this->haulDay = $profile?->haul_day !== null ? (string) $profile->haul_day : '';
        $this->haulTotalDays = $profile?->haul_total_days !== null ? (string) $profile->haul_total_days : '';
        $this->profileError = null;
        $this->editingProfile = true;
    }

    public function cancelEditProfile(): void
    {
        $this->editingProfile = false;
        $this->profileError = null;
    }

    public function saveProfile(): void
    {
        $this->profileError = null;
        $name = trim($this->name);

        if ($name === '') {
            $this->profileError = 'Nama profil wajib diisi.';

            return;
        }

        $netWealth = max((int) $this->netWealth, 0);
        $nisab = max((int) $this->nisab, 0);
        $haulDay = $this->haulDay !== '' ? max((int) $this->haulDay, 0) : null;
        $haulTotalDays = $this->haulTotalDays !== '' ? max((int) $this->haulTotalDays, 1) : null;

        $status = match (true) {
            $netWealth < $nisab => 'belum_nisab',
            $haulDay !== null && $haulTotalDays !== null && $haulDay >= $haulTotalDays => 'haul_genap',
            default => 'haul_berjalan',
        };

        $data = [
            'name' => $name,
            'net_wealth_minor' => $netWealth,
            'nisab_minor' => $nisab,
            'haul_started_label' => trim($this->haulStartedLabel) ?: null,
            'haul_due_label' => trim($this->haulDueLabel) ?: null,
            'haul_day' => $haulDay,
            'haul_total_days' => $haulTotalDays,
            'status' => $status,
        ];

        $profile = $this->editingProfileId ? auth()->user()->zakatProfiles()->find($this->editingProfileId) : null;

        if ($profile) {
            $profile->update($data);
        } else {
            $profile = ZakatProfile::create($data + ['user_id' => auth()->id()]);
        }

        $this->selectedProfileId = $profile->id;
        $this->editingProfile = false;
        $this->resetPayForm();
    }

    public function archiveProfile(int $profileId): void
    {
        $this->profileError = null;
        $profile = auth()->user()->zakatProfiles()->active()->find($profileId);
        if (! $profile) {
            return;
        }

        if ($this->activeProfiles()->count() <= 1) {
            $this->profileError = 'Profil harta aktif terakhir tidak bisa diarsipkan.';

            return;
        }

        $profile->update(['archived' => true]);

        if ($this->selectedProfileId === $profileId) {
            $this->selectedProfileId = $this->activeProfiles()->first()?->id;
            $this->resetPayForm();
        }
    }

    public function restoreProfile(int $profileId): void
    {
        $profile = auth()->user()->zakatProfiles()->where('archived', true)->find($profileId);
        $profile?->update(['archived' => false]);
    }

    /** Mengembalikan isian tunaikan ke bawaan (nominal 2,5% harta bersih). */
    public function startPayZakat(): void
    {
        if ($this->profile()) {
            $this->resetPayForm();
        }
    }

    public function payZakat(): void
    {
        $this->payError = null;
        $profile = $this->profile();
        if (! $profile) {
            return;
        }

        $amount = (int) $this->zakatAmount;
        if ($amount <= 0) {
            $this->payError = 'Nominal wajib lebih dari nol.';

            return;
        }

        $user = auth()->user();
        $account = $user->accounts()->where('archived', false)->find($this->zakatAccountId);
        if (! $account) {
            $this->payError = 'Pilih akun sumber.';

            return;
        }

        $category = Category::zakat()
            ->whereHas('room', fn ($q) => $q->where('user_id', $user->id))
            ->first();

        if (! $category) {
            $this->payError = 'Ruang Memberi atau kategori Zakat mal belum ada.';

            return;
        }

        // Nama profil disebut di catatan hanya bila ada lebih dari satu, sesuai docs/ui-flow.md S14.
        $note = $user->zakatProfiles()->count() > 1 ? 'Zakat mal — '.$profile->name : 'Zakat mal';

        try {
            Ledger::recordExpense($user, $amount, $category, $account, Carbon::now(), $note, [
                'recipient_institution' => $this->zakatInstitution,
                'receipt_number' => $this->zakatReceipt,
            ]);
        } catch (LedgerException $e) {
            $this->payError = $e->getMessage();

            return;
        }

        // Menunaikan zakat memulai haul baru; status mengikuti nisab sekarang (tidak berubah lewat pembayaran ini).
        $profile->update([
            'haul_started_label' => Carbon::now()->translatedFormat('j F Y'),
            'haul_due_label' => null,
            'haul_day' => 0,
            'status' => $profile->isAboveNisab() ? 'haul_berjalan' : 'belum_nisab',
        ]);

        $this->resetPayForm();
        $this->payNotice = 'Zakat '.Rupiah::format($amount).' dicatat di Memberi › Zakat mal, dan haul baru dimulai.';
    }

    // --- Rekap zakat tahunan ---

    private function rekapYear(): int
    {
        return preg_match('/^\d{4}$/', (string) $this->tahun) === 1 ? (int) $this->tahun : (int) Carbon::now()->format('Y');
    }

    public function selectYear(int $year): void
    {
        $this->tahun = (string) $year;
        $this->cancelEditRekap();
    }

    public function startEditRekap(int $transactionId): void
    {
        $transaction = ZakatRekap::query(auth()->user())->find($transactionId);
        if (! $transaction) {
            $this->rekapError = 'Transaksi zakat tidak ditemukan.';

            return;
        }

        $this->editingRekapId = $transaction->id;
        $this->rekapInstitution = $transaction->recipient_institution ?? '';
        $this->rekapReceipt = $transaction->receipt_number ?? '';
        $this->rekapError = null;
    }

    public function cancelEditRekap(): void
    {
        $this->editingRekapId = null;
        $this->rekapInstitution = '';
        $this->rekapReceipt = '';
        $this->rekapError = null;
    }

    /** Menyimpan lembaga penerima dan nomor bukti setor; hanya untuk transaksi zakat milik pengguna. */
    public function saveRekap(): void
    {
        $this->rekapError = null;
        $transaction = $this->editingRekapId ? ZakatRekap::query(auth()->user())->find($this->editingRekapId) : null;
        if (! $transaction) {
            $this->rekapError = 'Transaksi zakat tidak ditemukan.';

            return;
        }

        $institution = trim($this->rekapInstitution);
        $receipt = trim($this->rekapReceipt);

        if (mb_strlen($institution) > Ledger::RECIPIENT_MAX) {
            $this->rekapError = 'Lembaga penerima paling banyak '.Ledger::RECIPIENT_MAX.' karakter.';

            return;
        }
        if (mb_strlen($receipt) > Ledger::RECEIPT_MAX) {
            $this->rekapError = 'Nomor bukti setor paling banyak '.Ledger::RECEIPT_MAX.' karakter.';

            return;
        }

        $transaction->update([
            'recipient_institution' => $institution === '' ? null : $institution,
            'receipt_number' => $receipt === '' ? null : $receipt,
        ]);

        $this->cancelEditRekap();
    }

    public function render(): View
    {
        $user = auth()->user();
        $year = $this->rekapYear();
        $rekap = ZakatRekap::transactions($user, $year);

        return view('livewire.zakat', [
            'profiles' => $this->activeProfiles(),
            'archivedProfiles' => $user->zakatProfiles()->where('archived', true)->orderBy('id')->get(),
            'profile' => $this->profile(),
            'canAddProfile' => $user->zakatProfiles()->count() === 0 || $user->entitlements()->isEnabled(Feature::MultiZakatProfile),
            'accounts' => $user->accounts()->where('archived', false)->orderBy('sort_order')->get(),
            'dateLabel' => Carbon::now()->locale('id')->translatedFormat('l, j F Y'),
            'rekapYear' => $year,
            'rekapYears' => ZakatRekap::years($user, (int) Carbon::now()->format('Y')),
            'rekapRows' => $rekap,
            'rekapTotal' => ZakatRekap::totalMinor($rekap),
            'rekapCsv' => ZakatRekap::csv($user, $year),
        ])->layout('components.layout', ['title' => 'Zakat dan Haul — Rizqflow']);
    }
}
