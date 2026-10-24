<?php

namespace App\Livewire;

use App\Support\Ledger;
use App\Support\LedgerException;
use App\Support\Rupiah;
use App\Support\SmartInput\SmartInputParser;
use Carbon\CarbonImmutable;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Catat rezeki masuk dan alirkan ke ruang (S06 pemasukan + S07 pratinjau alokasi, mockup
 * docs/design/web-mockup/project/Alirkan.dc.html). Kiri: form; kanan: pratinjau aliran yang
 * berubah saat nominal diketik, dihitung Ledger::previewIncome dengan aturan alokasi sekarang.
 * Simpan lewat Ledger::recordIncome (potret alokasi ikut tersimpan, saldo akun bertambah).
 *
 * Prefill dari query: ?nominal=18000000 dan ?catatan=Gaji%20Oktober.
 */
class AlirkanRezeki extends Component
{
    /** Contoh nominal pada chip: gaji bulat, dan angka yang menyisakan pembulatan. */
    public const EXAMPLES = [18_000_000, 1_234_567];

    /** Nominal sebagai teks (hanya angka yang dipakai; titik dan "Rp" diabaikan). */
    public string $amount = '';

    public string $source = '';

    public ?int $accountId = null;

    public string $date = '';

    public string $note = '';

    /** Galat dari Ledger (sudah berupa teks UI). */
    public ?string $formError = null;

    public function mount(): void
    {
        $user = auth()->user();

        $prefill = preg_replace('/\D/', '', (string) request()->query('nominal', ''));
        $this->amount = ltrim($prefill, '0') === '' ? '' : $this->formatted(ltrim($prefill, '0'));
        $this->note = mb_substr(trim((string) request()->query('catatan', '')), 0, Ledger::NOTE_MAX);
        $this->source = SmartInputParser::INCOME_SOURCES[0];
        $this->date = CarbonImmutable::today()->toDateString();

        // Akun tujuan bawaan: yang terakhir dipakai pemasukan (bila masih aktif), kalau tidak akun pertama.
        $lastAccountId = $user->transactions()->income()->orderByDesc('occurred_on')->orderByDesc('id')->value('account_id');
        $active = $user->accounts()->active()->orderBy('sort_order')->orderBy('id')->pluck('id');
        $this->accountId = $active->contains($lastAccountId) ? (int) $lastAccountId : ($active->first() !== null ? (int) $active->first() : null);
    }

    public function useAmount(int $amountMinor): void
    {
        $this->amount = $this->formatted((string) max($amountMinor, 0));
        $this->resetErrorBag('amount');
        $this->formError = null;
    }

    public function setSource(string $source): void
    {
        if (in_array($source, SmartInputParser::INCOME_SOURCES, true)) {
            $this->source = $source;
        }
    }

    public function save()
    {
        $user = auth()->user();
        $this->resetErrorBag();
        $this->formError = null;

        $amountMinor = $this->amountMinor();
        if ($amountMinor <= 0) {
            $this->addError('amount', 'Isi nominal rezeki lebih dari nol.');
        }
        if (! in_array($this->source, SmartInputParser::INCOME_SOURCES, true)) {
            $this->addError('source', 'Pilih sumber rezeki.');
        }
        $date = null;
        try {
            $date = CarbonImmutable::createFromFormat('!Y-m-d', $this->date) ?: null;
        } catch (\Throwable) {
        }
        if ($date === null || $date->format('Y-m-d') !== $this->date) {
            $this->addError('date', 'Tanggal tidak valid.');
        }
        if (mb_strlen($this->note) > Ledger::NOTE_MAX) {
            $this->addError('note', 'Catatan paling banyak '.Ledger::NOTE_MAX.' karakter.');
        }
        // Akun hanya dari milik user ini: id dari request tidak dipercaya.
        $account = $this->accountId ? $user->accounts()->active()->find($this->accountId) : null;
        if (! $account) {
            $this->addError('accountId', 'Pilih akun tujuan.');
        }
        if ($this->getErrorBag()->isNotEmpty()) {
            return null;
        }

        try {
            $transaction = Ledger::recordIncome($user, $amountMinor, $account, $date, $this->source, trim($this->note) === '' ? null : trim($this->note));
        } catch (LedgerException $e) {
            $this->formError = $e->getMessage();

            return null;
        }

        $rooms = $transaction->allocationEntries->where('amount_minor', '>', 0)->count();
        if ($rooms > 0) {
            session()->flash('status', 'Rezeki '.Rupiah::format($amountMinor).' dialirkan ke '.$rooms.' ruang.');
        } else {
            session()->flash('status', 'Rezeki '.Rupiah::format($amountMinor).' dicatat, belum dialirkan ke ruang mana pun. Atur ruang dan aturan alokasi supaya rezeki berikutnya punya tujuan.');
            session()->flash('status_tone', 'info');
        }

        return $this->redirectRoute('transaksi');
    }

    /** "18000000" menjadi "18.000.000" (teks angka, tanpa konversi ke int). */
    private function formatted(string $digits): string
    {
        return strrev(implode('.', str_split(strrev($digits), 3)));
    }

    /** Nominal dari teks isian dalam rupiah; di atas batas dikembalikan melewati batas supaya ditolak Ledger. */
    private function amountMinor(): int
    {
        $digits = ltrim(preg_replace('/\D/', '', $this->amount), '0');
        if ($digits === '') {
            return 0;
        }

        return strlen($digits) > 18 ? Ledger::MAX_AMOUNT_MINOR + 1 : (int) $digits;
    }

    public function render(): View
    {
        $user = auth()->user();
        $amountMinor = $this->amountMinor();

        $preview = null;
        $previewError = null;
        try {
            $preview = Ledger::previewIncome($user, $amountMinor);
        } catch (LedgerException $e) {
            $previewError = $e->getMessage();
        }

        $parts = [];
        if ($preview) {
            foreach ($preview['rooms'] as $share) {
                $parts[] = Rupiah::format($share['amount_minor']);
            }
        }

        return view('livewire.alirkan-rezeki', [
            'accounts' => $user->accounts()->active()->orderBy('sort_order')->orderBy('id')->get(),
            'sources' => SmartInputParser::INCOME_SOURCES,
            'amountMinor' => $amountMinor,
            'preview' => $preview,
            'previewError' => $previewError,
            'hasRule' => $preview !== null && $preview['rule_label'] !== null && $preview['rooms'] !== [],
            'equation' => $preview ? implode(' + ', $parts).($preview['unallocated_minor'] > 0 ? ' + '.Rupiah::format($preview['unallocated_minor']).' (belum dialirkan)' : '').' = '.Rupiah::format($amountMinor) : '',
            'isAdvanced' => $preview !== null && $preview['mode'] === Ledger::MODE_ADVANCED,
        ])->layout('components.layout', ['title' => 'Catat rezeki masuk — Rizqflow']);
    }
}
