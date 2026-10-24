{{--
    Tab Jadwal: Agenda | Tagihan | Berulang. Tampil di tiga halaman (Jadwal, Tagihan, Transaksi berulang);
    angka di tab Tagihan dan Berulang = yang masih aktif (tagihan belum lunas dan tidak dijeda, aturan aktif).

    <x-jadwal.tabs active="agenda" />      active: agenda | tagihan | berulang
--}}
@props(['active' => 'agenda'])
@php
    $user = auth()->user();
    $billCount = $user->bills()->where('active', true)->get()->reject(fn ($bill) => $bill->isFinished())->count();
    $ruleCount = $user->recurringRules()->where('active', true)->get()->reject(fn ($rule) => $rule->isFinished())->count();
@endphp
<x-rf.tabs label="Jadwal" :items="[
    ['label' => 'Agenda', 'href' => route('jadwal'), 'active' => $active === 'agenda'],
    ['label' => 'Tagihan', 'href' => route('tagihan'), 'active' => $active === 'tagihan', 'count' => $billCount, 'muted' => true],
    ['label' => 'Berulang', 'href' => route('transaksi.berulang'), 'active' => $active === 'berulang', 'count' => $ruleCount, 'muted' => true],
]" />
