{{--
    Kotak ikon ruang berwarna slot (.rf-roomic). Hiasan: nama ruang selalu ditulis di sebelahnya.

    <x-rf.room-icon :room="$room" />                 slot dan ikon dari model Room
    <x-rf.room-icon :slot="2" icon="diri" />          tanpa model

    room: App\Models\Room (opsional). slot: 1..8 → c1..c8; null, < 1, atau > 8 → cn (netral).
    icon: kunci ikon ruang (kolom rooms.icon): memberi → heart, diri → user, keluarga → people,
          lainnya → grid. slot/icon yang diisi menimpa nilai dari room.
    Catatan: "slot" dibaca dari $attributes karena $slot di Blade adalah isi komponen.
--}}
@props(['room' => null, 'icon' => null])
@php
    $colorSlot = $attributes->get('slot') ?? $room?->slot;
    $colorSlot = is_numeric($colorSlot) ? (int) $colorSlot : null;
    $colorClass = $colorSlot !== null && $colorSlot >= 1 && $colorSlot <= 8 ? 'c'.$colorSlot : 'cn';
    $iconName = match ($icon ?? $room?->icon) {
        'memberi' => 'heart',
        'diri' => 'user',
        'keluarga' => 'people',
        default => 'grid',
    };
@endphp
<span {{ $attributes->except('slot')->class(['rf-roomic', $colorClass])->merge(['aria-hidden' => 'true']) }}><x-rf.icon :name="$iconName" /></span>
