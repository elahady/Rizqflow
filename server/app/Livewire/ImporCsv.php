<?php

namespace App\Livewire;

use App\Models\Category;
use App\Support\CsvTransactionImporter;
use App\Support\Feature;
use App\Support\Ledger;
use App\Support\LedgerException;
use Illuminate\Contracts\View\View;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use Livewire\Component;
use Livewire\WithFileUploads;

/**
 * Impor transaksi massal dari CSV mutasi bank/e-wallet (Pro, web-only). Parsingnya murni di
 * App\Support\CsvTransactionImporter (lihat dokblok di sana untuk format yang didukung dan
 * batasannya); komponen ini cuma alur unggah — pratinjau — konfirmasi, lalu menulis transaksi.
 */
class ImporCsv extends Component
{
    use WithFileUploads;

    public $file;

    /** @var list<array{date: string, amount_minor: int, type: string, note: ?string}> */
    public array $rows = [];

    public int $skipped = 0;

    public ?int $accountId = null;

    public ?int $roomId = null;

    public ?int $categoryId = null;

    public ?string $error = null;

    public ?string $status = null;

    public function updatedFile(): void
    {
        $this->error = null;
        $this->status = null;

        if (! in_array($this->file->getClientOriginalExtension(), ['csv', 'txt'], true)) {
            $this->error = 'Berkas harus CSV (.csv atau .txt).';
            $this->reset(['file', 'rows', 'skipped']);

            return;
        }

        $result = CsvTransactionImporter::parse(file_get_contents($this->file->getRealPath()));
        $this->rows = $result['rows'];
        $this->skipped = $result['skipped'];

        if ($this->rows === []) {
            $this->error = 'Tidak ada baris yang bisa diurai dari berkas ini.';
        }
    }

    public function updatedRoomId(): void
    {
        $this->categoryId = null;
    }

    public function confirmImport(): void
    {
        $this->error = null;
        $user = auth()->user();

        if ($this->rows === []) {
            $this->error = 'Unggah berkas CSV dulu.';

            return;
        }

        $account = $user->accounts()->where('archived', false)->find($this->accountId);
        if (! $account) {
            $this->error = 'Pilih akun tujuan.';

            return;
        }

        $hasExpense = collect($this->rows)->contains(fn ($row) => $row['type'] === 'pengeluaran');
        $room = $hasExpense ? $user->rooms()->active()->find($this->roomId) : null;
        $category = $room ? Category::where('room_id', $room->id)->where('archived', false)->find($this->categoryId) : null;

        if ($hasExpense && (! $room || ! $category)) {
            $this->error = 'Pilih ruang dan kategori untuk baris pengeluaran.';

            return;
        }

        // Lewat Ledger: pemasukan ikut dialirkan ke ruang (potret alokasi) dan saldo akun bergerak.
        // Satu transaksi database: satu baris yang ditolak membatalkan seluruh impor.
        try {
            DB::transaction(function () use ($user, $account, $category) {
                foreach ($this->rows as $row) {
                    $date = Carbon::parse($row['date']);
                    if ($row['type'] === 'pengeluaran') {
                        Ledger::recordExpense($user, $row['amount_minor'], $category, $account, $date, $row['note']);
                    } else {
                        Ledger::recordIncome($user, $row['amount_minor'], $account, $date, null, $row['note']);
                    }
                }
            });
        } catch (LedgerException $e) {
            $this->error = 'Impor dibatalkan: '.$e->getMessage();

            return;
        }
        $count = count($this->rows);
        $this->status = "{$count} transaksi berhasil diimpor ke {$account->name}.";
        $this->reset(['file', 'rows', 'skipped', 'accountId', 'roomId', 'categoryId']);
    }

    public function render(): View
    {
        $user = auth()->user();
        $canUseReports = $user->entitlements()->isEnabled(Feature::ReportsAndInsights);

        $rooms = $user->rooms()->active()->orderBy('sort_order')->get();
        $categories = $this->roomId ? ($user->rooms()->find($this->roomId)?->categories()->where('archived', false)->orderBy('sort_order')->get() ?? collect()) : collect();

        return view('livewire.impor-csv', [
            'canUseReports' => $canUseReports,
            'accounts' => $user->accounts()->where('archived', false)->orderBy('sort_order')->get(),
            'rooms' => $rooms,
            'categories' => $categories,
            'hasExpenseRows' => collect($this->rows)->contains(fn ($row) => $row['type'] === 'pengeluaran'),
            'totalAmount' => collect($this->rows)->sum(fn ($row) => $row['type'] === 'pengeluaran' ? -$row['amount_minor'] : $row['amount_minor']),
        ])->layout('components.layout', ['title' => 'Impor CSV — Rizqflow']);
    }
}
