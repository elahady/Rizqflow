<?php

namespace App\Livewire;

use App\Support\MonthLedger;
use App\Support\NetWorthSnapshotter;
use App\Support\RecurringRunner;
use App\Support\Rupiah;
use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Beranda (S05 Denah) — ringkasan rezeki bulan ini, kartu ruang, dan "perlu perhatian". Semua angka
 * diturunkan dari transaksi dan potret alokasi lewat App\Support\MonthLedger (data seed/contoh
 * sampai sinkron data asli menyusul).
 */
class Denah extends Component
{
    public ?string $recurringBanner = null;

    /**
     * Tidak ada penjadwal latar belakang: kemunculan transaksi berulang (S33) dicatat di sini,
     * setiap kali Denah dibuka — padanan web untuk "setiap aplikasi dibuka" di mobile.
     */
    public function mount(): void
    {
        $user = auth()->user();
        $result = RecurringRunner::runDue($user);

        if ($result['recorded'] > 0) {
            $this->recurringBanner = $result['recorded'].' transaksi berulang dicatat.';
        } elseif ($result['blocked'] !== []) {
            $this->recurringBanner = count($result['blocked']).' aturan berulang tertahan — akun, ruang, atau kategorinya sudah diarsipkan.';
        }

        // Dicatat untuk semua user (bukan hanya Pro) supaya riwayat sudah ada begitu upgrade.
        NetWorthSnapshotter::captureIfMissing($user);
    }

    public function render(): View
    {
        $user = auth()->user();
        $ledger = MonthLedger::for($user, now());

        $attention = [];
        foreach ($ledger->attention() as $item) {
            $attention[] = match ($item['kind']) {
                MonthLedger::ATTENTION_ROOM_OVER => [
                    'text' => "Ruang {$item['room']->name} melewati jatah bulan ini",
                    'pill' => $item['room_month']->percent().'%',
                ],
                MonthLedger::ATTENTION_ROOM_NEAR => [
                    'text' => "Ruang {$item['room']->name} hampir melewati jatah bulan ini",
                    'pill' => $item['room_month']->percent().'%',
                ],
                default => [
                    'text' => Rupiah::format($item['amount_minor']).' rezeki bulan ini belum dialirkan',
                    'pill' => 'Belum dialirkan',
                ],
            };
        }

        $zakat = $user->zakatProfiles()->where('status', 'haul_berjalan')->first();
        if ($zakat && $zakat->haul_day && $zakat->haul_total_days) {
            $daysLeft = max($zakat->haul_total_days - $zakat->haul_day, 0);
            $attention[] = [
                'text' => 'Haul zakat '.$daysLeft.' hari lagi',
                'pill' => $daysLeft.' hari',
                'href' => route('zakat'),
            ];
        }

        return view('livewire.denah', [
            'recurringBanner' => $this->recurringBanner,
            'dateLabel' => now()->locale('id')->translatedFormat('l, j F Y'),
            'ledger' => $ledger,
            'rooms' => $ledger->rooms(),
            'attention' => $attention,
        ])->layout('components.layout', ['title' => 'Denah — Rizqflow']);
    }
}
