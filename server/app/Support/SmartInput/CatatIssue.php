<?php

namespace App\Support\SmartInput;

/** Masalah isian Catat yang membuat tombol simpan belum aktif (CatatIssue di ledger/Catat.kt). */
enum CatatIssue: string
{
    case NoAmount = 'no_amount';
    case NoAccount = 'no_account';
    /** Pengeluaran butuh ruang; akun yang memilih Mulai kosong belum punya. */
    case NoRoom = 'no_room';
    case NoCategory = 'no_category';
    case TransferNeedsTwoAccounts = 'transfer_needs_two_accounts';
    case SameAccount = 'same_account';
}
