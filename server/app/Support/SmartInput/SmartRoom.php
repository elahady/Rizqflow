<?php

namespace App\Support\SmartInput;

/** Ruang pilihan Catat. Parser hanya membaca nama; tipe ikut dibawa untuk tampilan pemanggil. */
final class SmartRoom
{
    /** @param string $type 'menunaikan' | 'menumbuhkan' | 'mencukupi', sama dengan kolom rooms.type */
    public function __construct(
        public readonly int $id,
        public readonly string $name,
        public readonly string $type,
    ) {}
}
