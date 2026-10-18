<?php

namespace App\Livewire;

use App\Models\Category;
use App\Models\Transaction;
use App\Models\ZakatProfile;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Beranda zakat + kartu haul (S14/S16) dan profil harta (S15), tunaikan zakat (S17). Profil harta
 * tunggal untuk scaffold ini — multi-profil harta (Pro) belum diimplementasikan.
 *
 * Haul (S16): web belum punya tanggal mulai haul sungguhan karena metode kalender Hijriyah belum
 * diputuskan (lihat docs/konsep.md), jadi `haul_day`/`haul_total_days`/label mulai-jatuh tempo di
 * sini murni isian manual pengguna, bukan dihitung otomatis — berbeda dari HaulTracker di :domain
 * yang menghitung dari tanggal sungguhan. `status` dihitung ulang dari nisab dan isian haul itu
 * setiap profil disimpan, supaya tidak ada kombinasi status yang tidak masuk akal.
 */
class Zakat extends Component
{
    public bool $editingProfile = false;

    public string $name = 'Utama';

    public string $netWealth = '0';

    public string $nisab = '0';

    public string $haulStartedLabel = '';

    public string $haulDueLabel = '';

    public string $haulDay = '';

    public string $haulTotalDays = '';

    public ?string $profileError = null;

    public bool $payingZakat = false;

    public string $zakatAmount = '';

    public ?int $zakatAccountId = null;

    public ?string $payError = null;

    private function profile(): ?ZakatProfile
    {
        return auth()->user()->zakatProfiles()->first();
    }

    public function startEditProfile(): void
    {
        $profile = $this->profile();
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

        $profile = $this->profile();
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

        if ($profile) {
            $profile->update($data);
        } else {
            ZakatProfile::create($data + ['user_id' => auth()->id()]);
        }

        $this->editingProfile = false;
    }

    public function startPayZakat(): void
    {
        $profile = $this->profile();
        if (! $profile) {
            return;
        }

        $this->zakatAmount = (string) $profile->suggestedZakatMinor();
        $this->zakatAccountId = null;
        $this->payError = null;
        $this->payingZakat = true;
    }

    public function cancelPayZakat(): void
    {
        $this->payingZakat = false;
        $this->payError = null;
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

        $category = Category::where('name', 'Zakat mal')->where('is_system', true)
            ->whereHas('room', fn ($q) => $q->where('user_id', $user->id))
            ->first();

        if (! $category) {
            $this->payError = 'Ruang Memberi atau kategori Zakat mal belum ada.';

            return;
        }

        Transaction::create([
            'user_id' => $user->id,
            'room_id' => $category->room_id,
            'category_id' => $category->id,
            'account_id' => $account->id,
            'type' => 'pengeluaran',
            'amount_minor' => $amount,
            'category' => $category->name,
            'note' => 'Zakat mal — '.$profile->name,
            'occurred_on' => now(),
        ]);

        $account->decrement('balance_minor', $amount);

        // Menunaikan zakat memulai haul baru; status mengikuti nisab sekarang (tidak berubah lewat pembayaran ini).
        $profile->update([
            'haul_started_label' => Carbon::now()->translatedFormat('j F Y'),
            'haul_due_label' => null,
            'haul_day' => 0,
            'status' => $profile->isAboveNisab() ? 'haul_berjalan' : 'belum_nisab',
        ]);

        $this->payingZakat = false;
    }

    public function render(): View
    {
        $user = auth()->user();

        return view('livewire.zakat', [
            'profile' => $this->profile(),
            'accounts' => $user->accounts()->where('archived', false)->orderBy('sort_order')->get(),
        ])->layout('components.layout', ['title' => 'Zakat dan Haul — Rizqflow']);
    }
}
