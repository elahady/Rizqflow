{{--
    Titik warna ruang + "Ruang › Pos" (.rf-roomname), seperti kolom Ruang › Pos di Transaksi.dc.html.

    <x-rf.room-name :room="$t->room" :pos="$t->categoryModel" />    model Room dan Category
    <x-rf.room-name :slot="3" room="Keluarga" pos="Dapur dan belanja" />
    <x-rf.room-name :room="$room" />                                 tanpa pos: hanya nama ruang

    room: App\Models\Room atau nama ruang (string). slot: 1..8 → warna slot, selain itu netral (cn);
          bila kosong diambil dari room->slot. pos: nama pos (string) atau model dengan atribut name;
          kosong = tanpa "› Pos". Pemisah "›" disembunyikan dari pembaca layar (dibaca "pos").
    Catatan: "slot" dibaca dari $attributes karena $slot di Blade adalah isi komponen.
--}}
@props(['room' => null, 'pos' => null])
@php
    $colorSlot = $attributes->get('slot') ?? (is_object($room) ? $room->slot : null);
    $colorSlot = is_numeric($colorSlot) ? (int) $colorSlot : null;
    $colorClass = $colorSlot !== null && $colorSlot >= 1 && $colorSlot <= 8 ? 'c'.$colorSlot : 'cn';
    $roomName = is_object($room) ? (string) $room->name : (string) $room;
    $posName = is_object($pos) ? (string) $pos->name : (string) $pos;
@endphp
<span {{ $attributes->except('slot')->class(['rf-roomname']) }}><i class="rf-dot {{ $colorClass }}" aria-hidden="true"></i><span>{{ $roomName }}@if ($posName !== '') <span class="rf-sub" aria-hidden="true">›</span><span class="rf-sr-only">pos</span> {{ $posName }}@endif</span></span>
