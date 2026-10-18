<?php

namespace App\Support;

use App\Models\Account;
use App\Models\RecurringRule;
use App\Models\Transaction;
use App\Models\User;
use Illuminate\Support\Carbon;

/**
 * Mencatat kemunculan aturan berulang (S33) yang jatuh pada hari ini atau sebelumnya. Tidak ada
 * penjadwal latar belakang di web, sama seperti mobile — dipanggil dari Denah::mount() setiap
 * halaman itu dibuka ("app dibuka"). Aman dipanggil berulang kali: begitu `next_due` sebuah aturan
 * sudah lewat hari ini, perulangannya berhenti sampai esok. Porting ringkas dari
 * RecurringService.runDue di :domain, tanpa dukungan transfer (belum ada di model data web).
 */
class RecurringRunner
{
    /** Kemunculan per aturan per panggilan; aturan harian yang lama tak dibuka menyusul bertahap. */
    private const MAX_CATCH_UP = 400;

    /** @return array{recorded: int, blocked: list<RecurringRule>} */
    public static function runDue(User $user, ?Carbon $today = null): array
    {
        $today ??= Carbon::today();
        $recorded = 0;
        $blocked = [];

        $rules = $user->recurringRules()->where('active', true)->get();

        foreach ($rules as $rule) {
            if ($rule->isFinished()) {
                continue;
            }

            $steps = 0;
            while ($steps < self::MAX_CATCH_UP && $rule->next_due->lessThanOrEqualTo($today) && ! $rule->isFinished()) {
                if (! $rule->isUsable()) {
                    $blocked[] = $rule;
                    break;
                }

                self::record($rule, $rule->next_due);
                $recorded++;

                $rule->next_due = RecurrenceSchedule::next($rule->start_date, $rule->frequency, $rule->next_due);
                $rule->save();
                $steps++;
            }
        }

        return ['recorded' => $recorded, 'blocked' => $blocked];
    }

    private static function record(RecurringRule $rule, Carbon $date): void
    {
        Transaction::create([
            'user_id' => $rule->user_id,
            'room_id' => $rule->room_id,
            'category_id' => $rule->category_id,
            'account_id' => $rule->account_id,
            'recurring_rule_id' => $rule->id,
            'type' => $rule->type,
            'amount_minor' => $rule->amount_minor,
            'category' => $rule->type === 'pengeluaran' ? $rule->category?->name : $rule->source,
            'note' => $rule->note,
            'occurred_on' => $date,
        ]);

        $sign = $rule->type === 'pengeluaran' ? -1 : 1;
        Account::where('id', $rule->account_id)->increment('balance_minor', $sign * $rule->amount_minor);
    }
}
