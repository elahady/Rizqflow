<?php

namespace App\Support\SmartInput;

/**
 * Pos (kategori) di dalam ruang. Pos sistem (Zakat mal, Tak terlacak) dan pos terarsip tidak
 * pernah ditebak dari nama, tetapi pos sistem tetap pilihan Catat yang sah.
 */
final class SmartCategory
{
    public function __construct(
        public readonly int $id,
        public readonly int $roomId,
        public readonly string $name,
        public readonly bool $isSystem = false,
        public readonly bool $archived = false,
    ) {}
}
