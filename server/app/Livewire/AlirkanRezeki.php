<?php

namespace App\Livewire;

use Illuminate\Contracts\View\View;
use Livewire\Component;

/**
 * Catat rezeki masuk dan alirkan ke ruang (mockup docs/design/web-mockup/project/Alirkan.dc.html).
 * Stub fase 1 desain web baru: route dan kerangka saja; isinya dikerjakan di fase berikutnya.
 */
class AlirkanRezeki extends Component
{
    public function render(): View
    {
        return view('livewire.alirkan-rezeki')
            ->layout('components.layout', ['title' => 'Catat rezeki masuk — Rizqflow']);
    }
}
