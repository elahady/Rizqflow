<?php

namespace App\Livewire;

use App\Models\Account;
use App\Models\Room;
use App\Models\RoomRole;
use App\Models\Transaction;
use App\Support\Feature;
use App\Support\MonthLedger;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Livewire\Component;

/**
 * Detail ruang (S11): jatah vs terpakai, pos-pos bulan ini, transaksi terbaru, dan kartu Peran
 * (S32, Pro) untuk ruang selain Memberi. Daftar dan kelola ruang (S10, tambah/urutkan/arsipkan)
 * ada di RuangDaftar lewat route('ruang') tanpa parameter.
 */
class Ruang extends Component
{
    public Room $room;

    public bool $showRoleForm = false;

    public string $roleKind = 'trader';

    public string $capital = '';

    public string $riskPercent = '0.25';

    public string $dcaAmount = '';

    public string $dcaDay = '1';

    public ?int $dcaAccountId = null;

    public ?int $dcaCategoryId = null;

    public ?string $roleError = null;

    public function mount(Room $room): void
    {
        abort_unless($room->user_id === auth()->id(), 403);

        $this->room = $room;
    }

    public function startPickRole(string $kind): void
    {
        $this->roleError = null;

        if (! auth()->user()->entitlements()->isEnabled(Feature::RoleSystems)) {
            $this->roleError = 'Sistem per peran (Trader/Investor) adalah fitur Pro.';

            return;
        }

        $this->roleKind = $kind;
        $this->capital = '';
        $this->riskPercent = '0.25';
        $this->dcaAmount = '';
        $this->dcaDay = '1';
        $this->dcaAccountId = auth()->user()->accounts()->where('archived', false)->orderBy('sort_order')->value('id');
        $this->dcaCategoryId = $this->room->categories()->where('archived', false)->orderBy('sort_order')->value('id');
        $this->showRoleForm = true;
    }

    public function cancelRoleForm(): void
    {
        $this->showRoleForm = false;
        $this->roleError = null;
    }

    public function saveRole(): void
    {
        $this->roleError = null;

        if ($this->roleKind === 'trader') {
            $capital = (int) $this->capital;
            if ($capital <= 0) {
                $this->roleError = 'Modal harus lebih dari nol.';

                return;
            }

            $riskBp = (int) round(((float) $this->riskPercent) * 100);
            if ($riskBp < 1 || $riskBp > RoomRole::MAX_RISK_BP) {
                $this->roleError = 'Risiko per trade 0,01% sampai 50%.';

                return;
            }

            RoomRole::updateOrCreate(
                ['room_id' => $this->room->id],
                ['kind' => 'trader', 'capital_minor' => $capital, 'risk_bp' => $riskBp,
                    'dca_amount_minor' => null, 'dca_day_of_month' => null, 'dca_account_id' => null, 'dca_category_id' => null, 'dca_last_recorded' => null],
            );
        } else {
            $amount = (int) $this->dcaAmount;
            if ($amount <= 0) {
                $this->roleError = 'Nominal DCA harus lebih dari nol.';

                return;
            }

            $day = (int) $this->dcaDay;
            if ($day < 1 || $day > RoomRole::MAX_DCA_DAY) {
                $this->roleError = 'Tanggal DCA 1 sampai '.RoomRole::MAX_DCA_DAY.'.';

                return;
            }

            $account = auth()->user()->accounts()->where('archived', false)->find($this->dcaAccountId);
            if (! $account) {
                $this->roleError = 'Pilih akun sumber.';

                return;
            }

            $category = $this->room->categories()->where('archived', false)->find($this->dcaCategoryId);
            if (! $category) {
                $this->roleError = 'Pilih kategori yang sesuai dengan ruang.';

                return;
            }

            RoomRole::updateOrCreate(
                ['room_id' => $this->room->id],
                ['kind' => 'investor', 'dca_amount_minor' => $amount, 'dca_day_of_month' => $day,
                    'dca_account_id' => $account->id, 'dca_category_id' => $category->id,
                    'capital_minor' => null, 'risk_bp' => null],
            );
        }

        $this->showRoleForm = false;
    }

    public function removeRole(): void
    {
        $this->room->role?->delete();
    }

    public function recordDcaNow(): void
    {
        $role = $this->room->role;
        if (! $role || $role->kind !== 'investor') {
            return;
        }

        Transaction::create([
            'user_id' => auth()->id(),
            'room_id' => $this->room->id,
            'category_id' => $role->dca_category_id,
            'account_id' => $role->dca_account_id,
            'type' => 'pengeluaran',
            'amount_minor' => $role->dca_amount_minor,
            'category' => $role->dcaCategory->name,
            'note' => 'DCA '.$this->room->name,
            'occurred_on' => Carbon::today(),
        ]);

        Account::where('id', $role->dca_account_id)->decrement('balance_minor', $role->dca_amount_minor);

        $role->update(['dca_last_recorded' => Carbon::today()->startOfMonth()]);
    }

    public function render(): View
    {
        $transactions = $this->room->transactions()->orderByDesc('occurred_on')->limit(5)->get();
        // Jatah, terpakai, status, dan pos bulan ini diturunkan dari transaksi (MonthLedger).
        $roomMonth = MonthLedger::for(auth()->user(), now())->room($this->room);

        return view('livewire.ruang', [
            'room' => $this->room,
            'roomMonth' => $roomMonth,
            'categories' => $roomMonth->categoryBreakdown(),
            'transactions' => $transactions,
            'role' => $this->room->role,
            'today' => Carbon::today(),
            'canUseRoles' => auth()->user()->entitlements()->isEnabled(Feature::RoleSystems),
            'accounts' => auth()->user()->accounts()->where('archived', false)->orderBy('sort_order')->get(),
            'roomCategories' => $this->room->categories()->where('archived', false)->orderBy('sort_order')->get(),
        ])->layout('components.layout', ['title' => $this->room->name.' — Rizqflow']);
    }
}
