<?php

namespace App\Support\SmartInput;

/** Akun pilihan Catat, sebatas yang dibaca parser: nama (dicocokkan dengan kalimat) dan jenis (kata "tunai"). */
final class SmartAccount
{
    /** Jenis akun web (`accounts.type`) yang dituju kata "tunai", "cash", dan "kas"; padanan AccountKind.CASH. */
    public const CASH = 'tunai';

    /** @param string $type 'tunai' | 'bank' | 'e_wallet', sama dengan kolom accounts.type */
    public function __construct(
        public readonly int $id,
        public readonly string $name,
        public readonly string $type,
    ) {}
}
