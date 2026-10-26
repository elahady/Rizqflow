<?php

namespace App\Livewire;

use App\Auth\AccountService;
use App\Models\PlayPurchase;
use App\Models\User;
use App\Play\PurchaseStatus;
use App\Rules\StrongPassword;
use App\Support\Feature;
use App\Support\Plan;
use App\Support\ThemePalette;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Facades\Auth;
use Laravel\Sanctum\PersonalAccessToken;
use Livewire\Component;

/**
 * Profil: paket, status langganan Sync, perangkat yang masuk, kata sandi, dan hapus akun.
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

    /** Pesan hasil terakhir pada bagian perangkat yang masuk (cabut satu atau semua). */
    public ?string $deviceNotice = null;

    /** Kata sandi untuk konfirmasi hapus akun (akun yang punya sandi). */
    public string $deletePassword = '';

    /** Ketikan konfirmasi hapus akun (akun tanpa sandi): harus persis [DELETE_WORD]. */
    public string $deleteWord = '';

    public const DELETE_WORD = 'HAPUS';

    /** Pesan penolakan bila palet Pro dipilih tanpa paket yang membukanya (lihat [choosePalette]). */
    public ?string $paletteError = null;

    /**
     * Cabut satu perangkat: hapus baris token Sanctum milik akun ini. Padanan `POST /api/auth/logout`
     * untuk perangkat lain, lewat sesi web. Id dicari lewat relasi akun, jadi id milik akun lain tidak
     * pernah kena.
     */
    public function revokeDevice(int $tokenId): void
    {
        $this->deviceNotice = null;

        $revoked = auth()->user()->tokens()->whereKey($tokenId)->delete();

        $this->deviceNotice = $revoked > 0
            ? 'Perangkat sudah dicabut dan harus masuk lagi.'
            : 'Perangkat itu sudah tidak masuk.';
    }

    /** Keluar dari semua perangkat: setara `DELETE /api/auth/tokens`. Sesi web di peramban ini tidak ikut dicabut. */
    public function revokeAllDevices(): void
    {
        $this->deviceNotice = null;

        auth()->user()->tokens()->delete();

        $this->deviceNotice = 'Semua perangkat Android sudah dikeluarkan dari akun ini.';
    }

    /**
     * Simpan palet warna aksen ke akun (per akun, bukan localStorage, supaya ikut ke perangkat lain).
     * Palet Pro ditolak tanpa paket; pilihan Sage selalu boleh. Yang disimpan cuma pilihan, bukan
     * palet efektif, jadi turun paket tidak menghapusnya (lihat ThemePalette::effective).
     */
    public function choosePalette(string $palette): void
    {
        $this->paletteError = null;

        $choice = ThemePalette::tryFrom($palette);
        if ($choice === null) {
            return;
        }

        $user = auth()->user();
        if ($choice->isPro() && ! $user->entitlements()->isEnabled(Feature::Themes)) {
            $this->paletteError = 'Palet '.$choice->label().' adalah fitur Pro.';

            return;
        }

        $user->update(['theme_palette' => $choice->value]);

        // Halaman ini sudah berjalan; atribut <html> tidak ikut ter-morph Livewire, jadi diganti langsung.
        $this->js("document.documentElement.setAttribute('data-palette', '".$user->effectiveThemePalette()->value."')");
    }

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

    /**
     * Hapus akun: akun yang punya sandi wajib mengisi sandi yang benar (dicek lewat
     * [AccountService::verifyPassword], dengan throttle yang sama dengan ganti sandi); akun tanpa sandi
     * (masuk lewat Google saja) wajib mengetik HAPUS persis. Tombol di sisi tampilan juga menunggu
     * teks itu, tetapi server tetap memeriksa.
     */
    public function deleteAccount(AccountService $accounts): void
    {
        $user = auth()->user();
        $this->resetErrorBag();

        if ($user->hasPassword()) {
            $this->validate([
                'deletePassword' => ['required', 'string'],
            ], [
                'deletePassword.required' => 'Isi kata sandimu untuk menghapus akun.',
            ]);

            [$status, $wait] = $accounts->verifyPassword($user, $this->deletePassword);

            if ($status === AccountService::PASSWORD_THROTTLED) {
                $this->addError('deletePassword', 'Terlalu banyak percobaan. Coba lagi dalam '.$wait.' detik.');

                return;
            }

            if ($status === AccountService::PASSWORD_WRONG) {
                $this->reset('deletePassword');
                $this->addError('deletePassword', 'Kata sandi salah.');

                return;
            }
        } elseif ($this->deleteWord !== self::DELETE_WORD) {
            $this->addError('deleteWord', 'Ketik HAPUS dengan huruf kapital semua untuk konfirmasi.');

            return;
        }

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
            'devices' => $this->devices(auth()->user()),
        ])->layout('components.layout', ['title' => 'Profil — Rizqflow']);
    }

    /**
     * Perangkat yang masuk ke akun ini, satu baris per token API (Android: `android:<device_id>`).
     *
     * Isi token TIDAK pernah dibaca: kolom `token` (hash) tidak diambil, dan `device_id` tidak ditampilkan
     * (acak, tak ramah dibaca). Label memakai nama perangkat dari klien (`device_name`) bila ada; bila tidak,
     * "Android" untuk token berprefiks `android:` dan "Perangkat lain" untuk sisanya.
     *
     * @return list<array{id: int, label: string, created_at: ?\Carbon\CarbonInterface, last_used_at: ?\Carbon\CarbonInterface, expires_at: ?\Carbon\CarbonInterface}>
     */
    private function devices(User $user): array
    {
        return $user->tokens()
            ->orderByRaw('COALESCE(last_used_at, created_at) desc')
            ->orderByDesc('id')
            ->get(['id', 'name', 'device_name', 'created_at', 'last_used_at', 'expires_at'])
            ->map(fn (PersonalAccessToken $token) => [
                'id' => $token->id,
                'label' => $token->device_name ?: (str_starts_with($token->name, 'android:') ? 'Android' : 'Perangkat lain'),
                'created_at' => $token->created_at,
                'last_used_at' => $token->last_used_at,
                'expires_at' => $token->expires_at,
            ])
            ->values()
            ->all();
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
