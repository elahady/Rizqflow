<?php

namespace App\Livewire;

use App\Support\Period;
use Illuminate\Contracts\View\View;
use Livewire\Attributes\Url;
use Livewire\Component;

/**
 * Analisa › Aliran (diagram Sankey rezeki → ruang → pos, docs/design/web-mockup/project/Analisa.dc.html),
 * menggantikan hub kartu Laporan di route 'laporan'. Stub fase 1: tab ke halaman analisa yang sudah ada
 * dan contoh pemakaian pemilih periode (?bulan=Y-m) di bilah atas.
 */
class Aliran extends Component
{
    #[Url]
    public ?string $bulan = null;

    public function render(): View
    {
        $month = Period::fromQuery($this->bulan);

        return view('livewire.aliran', ['month' => $month])
            ->layout('components.layout', ['title' => 'Analisa — Rizqflow', 'period' => $month]);
    }
}
