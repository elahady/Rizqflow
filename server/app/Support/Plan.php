<?php

namespace App\Support;

/** Paket yang bisa dimiliki pengguna. Pro sekali bayar; Sync langganan (fase 2). */
enum Plan: string
{
    case Pro = 'pro';
    case Sync = 'sync';
}
