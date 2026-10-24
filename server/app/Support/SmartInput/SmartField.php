<?php

namespace App\Support\SmartInput;

/**
 * Bagian isian yang benar-benar dikenali dari kalimat (bukan diisi bawaan); dipakai layar untuk
 * menandai yang "terbaca otomatis" dan yang perlu diperiksa. Porting SmartField di
 * domain/.../smartinput/SmartInput.kt.
 */
enum SmartField: string
{
    case Kind = 'kind';
    case Amount = 'amount';
    case Account = 'account';
    case ToAccount = 'to_account';
    case Category = 'category';
    case Room = 'room';
    case Date = 'date';
    case Source = 'source';
}
