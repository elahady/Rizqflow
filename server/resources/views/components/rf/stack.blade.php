{{--
    Bar alokasi bertumpuk (.rf-stack) seperti hero Denah di Main.dc.html, opsional dengan legenda.

    <x-rf.stack :segments="[
        ['slot' => 1, 'bp' => 1000, 'label' => 'Memberi'],
        ['slot' => 2, 'bp' => 3000, 'label' => 'Diri'],
        ['slot' => 3, 'bp' => 6000, 'label' => 'Keluarga'],
    ]" label="Pembagian rezeki" legend />

    segments: list ['slot' => int|null, 'bp' => int (basis point, 10000 = 100%), 'label' => string].
      Aturan docs/design/README.md (warna ruang): segmen selalu diurutkan menurut slot, dan slot netral
      (null, < 1, atau > 8) digabung jadi satu segmen "Ruang lain" di ujung. Segmen 0 bp tidak digambar
      di bar tetapi tetap disebut di aria-label dan legenda. Total < 10000 menyisakan ruang kosong di kanan.
    label: awalan aria-label (default "Pembagian"); hasilnya mis. "Pembagian rezeki: Memberi 10 persen,
      Diri 30 persen, Keluarga 60 persen". legend: bool, tampilkan legenda titik + "Memberi 10%".
--}}
@props(['segments' => [], 'label' => 'Pembagian', 'legend' => false])
@php
    $core = [];
    $otherBp = 0;
    $hasOther = false;
    foreach ($segments as $segment) {
        $slotValue = $segment['slot'] ?? null;
        $slotValue = is_numeric($slotValue) ? (int) $slotValue : null;
        $bp = max(0, (int) ($segment['bp'] ?? 0));
        if ($slotValue !== null && $slotValue >= 1 && $slotValue <= 8) {
            $core[] = ['class' => 'c'.$slotValue, 'slot' => $slotValue, 'bp' => $bp, 'label' => (string) ($segment['label'] ?? '')];
        } else {
            $hasOther = true;
            $otherBp += $bp;
        }
    }
    usort($core, fn (array $a, array $b) => $a['slot'] <=> $b['slot']);
    if ($hasOther) {
        $core[] = ['class' => 'cn', 'slot' => null, 'bp' => $otherBp, 'label' => 'Ruang lain'];
    }

    // Basis point → "10" / "2,5" / "12,34" (tanpa float).
    $percentText = function (int $bp): string {
        $whole = intdiv($bp, 100);
        $fraction = $bp % 100;

        return $fraction === 0 ? (string) $whole : $whole.','.rtrim(str_pad((string) $fraction, 2, '0', STR_PAD_LEFT), '0');
    };
    $ariaLabel = $label.': '.implode(', ', array_map(fn (array $s) => $s['label'].' '.$percentText($s['bp']).' persen', $core));
@endphp
<div {{ $attributes->class(['rf-stack-wrap']) }}>
    <div class="rf-stack" role="img" aria-label="{{ $ariaLabel }}">@foreach ($core as $s)@if ($s['bp'] > 0)<i class="{{ $s['class'] }}" style="width: {{ intdiv($s['bp'], 100) }}.{{ str_pad((string) ($s['bp'] % 100), 2, '0', STR_PAD_LEFT) }}%"></i>@endif @endforeach</div>
    @if ($legend)
        <div class="rf-legend" aria-hidden="true">
            @foreach ($core as $s)
                <span><i class="rf-dot {{ $s['class'] }}"></i>{{ $s['label'] }} {{ $percentText($s['bp']) }}%</span>
            @endforeach
        </div>
    @endif
</div>
