<?php

namespace App\Livewire;

use App\Auth\AccountService;
use App\Models\PlayPurchase;
use App\Models\User;
use App\Play\PurchaseStatus;
use App\Rules\StrongPassword;
use App\Support\Feature;
use App\Support\Plan;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Facades\Auth;
use Livewire\Component;

/**
 * Profil: paket, status langganan Sync, kata sandi, dan hapus akun.
 *
 * Kata sandi: akun yang dibuat lewat Google belum punya sandi dan menetapkannya tanpa sandi lama;
 * akun yang sudah punya sandi mengubahnya dengan sandi lama (lihat [AccountService::changePassword]).
 * Tidak butuh email, jadi tetap tersedia walau pengiriman kode email dimatikan.
 *
 * Hapus akun dari web (Tahap 9, server-sync.md: syarat Google Play bila ada pembuatan akun).
 * Padanannya di Android ada di `Api\AccountController::destroy`. Ini menghapus akun SERVER saja —
 * data di ponsel pengguna tidak disentuh, sesuai prinsip "data di ponsel adalah sumber kebenaran".
 */
class Profil extends Component
{
    public string $current_password = '';

    public string $password = '';

    public string $password_confirmation = '';

    /** Pesan hasil terakhir pada bagian kata sandi: 'set' (sandi pertama) atau 'changed'. */
    public ?string $passwordSaved = null;

    public function savePassword(AccountService $accounts): void
    {
        $user = auth()->user();
        $this->passwordSaved = null;

        $this->validate([
            'password' => ['required', 'string', 'confirmed', new StrongPassword],
        ], [
            'password.required' => 'Isi kata sandi barunya.',
            'password.confirmed' => 'Konfirmasi kata sandi tidak sama.',
        ]);

        $hadPassword = $user->hasPassword();
        [$status, $wait] = $accounts->changePassword(
            $user,
            $hadPassword ? $this->current_password : null,
            $this->password,
            session()->getId(),
        );

        if ($status === AccountService::PASSWORD_THROTTLED) {
            $this->addError('current_password', 'Terlalu banyak percobaan. Coba lagi dalam '.$wait.' detik.');

            return;
        }

        if ($status === AccountService::PASSWORD_WRONG) {
            $this->addError('current_password', 'Kata sandi saat ini salah.');

            return;
        }

        $this->reset('current_password', 'password', 'password_confirmation');
        $this->resetErrorBag();
        $this->passwordSaved = $hadPassword ? 'changed' : 'set';
    }

    public function deleteAccount(): void
    {
        $user = auth()->user();

        Auth::logout();
        session()->invalidate();
        session()->regenerateToken();

        $user->tokens()->delete();
        $user->delete();

        $this->redirect(route('login'));
    }

    public function render(): View
    {
        return view('livewire.profil', [
            'sync' => $this->syncStatus(auth()->user()),
        ])->layout('components.layout', ['title' => 'Profil — Rizqflow']);
    }

    /**
     * Status langganan Sync untuk tampilan: 'aktif', 'masa_tenggang', 'berakhir', atau 'tidak_ada'.
     *
     * Hanya membaca data yang sudah ada, tanpa memanggil Google Play: gerbang aksesnya
     * `Feature::EncryptedSync` lewat `users.plans` (sama dengan Android dan halaman lain), dan
     * rinciannya dari baris `play_purchases` paket Sync yang ditulis `App\Play\PlanSynchronizer`.
     * Baris yang masih tertunda (pending) belum membuka apa pun, jadi tidak dihitung sebagai langganan.
     *
     * @return array{state: string, expires_at: ?\Carbon\CarbonInterface, renewing: bool}
     */
    private function syncStatus(User $user): array
    {
        $rows = PlayPurchase::where('user_id', $user->id)
            ->where('plan', Plan::Sync)
            ->where('status', '!=', PurchaseStatus::Pending)
            ->get();

        if (! $user->entitlements()->isEnabled(Feature::EncryptedSync)) {
            $latest = $rows->sortByDesc(fn (PlayPurchase $row) => $row->expires_at?->getTimestamp() ?? 0)->first();

            return [
                'state' => $latest === null ? 'tidak_ada' : 'berakhir',
                'expires_at' => $latest?->expires_at,
                'renewing' => false,
            ];
        }

        $now = now();
        $entitling = $rows
            ->filter(fn (PlayPurchase $row) => $row->entitles($now))
            ->sortByDesc(fn (PlayPurchase $row) => $row->expires_at?->getTimestamp() ?? 0)
            ->first();

        // users.plans memuat Sync tanpa baris pembelian: akun uji, seed, atau tinker (lihat PlanSynchronizer).
        if ($entitling === null) {
            return ['state' => 'aktif', 'expires_at' => null, 'renewing' => true];
        }

        return [
            'state' => $entitling->status === PurchaseStatus::GracePeriod ? 'masa_tenggang' : 'aktif',
            'expires_at' => $entitling->expires_at,
            'renewing' => $entitling->auto_renewing !== false,
        ];
    }
}
