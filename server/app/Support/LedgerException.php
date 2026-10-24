<?php

namespace App\Support;

use RuntimeException;

/**
 * Perintah App\Support\Ledger ditolak (padanan LedgerError di :domain). Pesannya (getMessage())
 * sudah berupa teks UI Bahasa Indonesia yang boleh langsung ditampilkan; `reason` adalah kode tetap
 * untuk diperiksa program (konstanta di bawah, mengikuti nama LedgerError Android).
 */
class LedgerException extends RuntimeException
{
    public const AMOUNT_NOT_POSITIVE = 'amount_not_positive';

    public const AMOUNT_TOO_LARGE = 'amount_too_large';

    public const ACCOUNT_NOT_FOUND = 'account_not_found';

    public const ACCOUNT_ARCHIVED = 'account_archived';

    public const CATEGORY_NOT_FOUND = 'category_not_found';

    public const CATEGORY_ARCHIVED = 'category_archived';

    public const ROOM_ARCHIVED = 'room_archived';

    public const NOT_AN_INCOME = 'not_an_income';

    public const NOT_AN_EXPENSE = 'not_an_expense';

    public const NOTE_TOO_LONG = 'note_too_long';

    public const SOURCE_TOO_LONG = 'source_too_long';

    public const FIELD_TOO_LONG = 'field_too_long';

    public const RULES_INVALID = 'rules_invalid';

    public const RECURRING_NOT_FOUND = 'recurring_not_found';

    public const BILL_NOT_FOUND = 'bill_not_found';

    public function __construct(public readonly string $reason, string $message)
    {
        parent::__construct($message);
    }
}
