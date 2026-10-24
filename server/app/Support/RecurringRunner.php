<?php

namespace App\Support;

use App\Models\RecurringRule;
use App\Models\User;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;

/**
 * Mencatat kemunculan aturan berulang (S33) yang jatuh pada hari ini atau sebelumnya. Tidak ada
 * penjadwal latar belakang di web, sama seperti mobile — dipanggil dari Denah::mount() setiap
 * halaman itu dibuka ("app dibuka"). Aman dipanggil berulang kali: begitu `next_due` sebuah aturan
 * sudah lewat hari ini, perulangannya berhenti sampai esok. Porting ringkas dari
 * RecurringService.runDue di :domain, tanpa dukungan transfer (belum ada di model data web).
 * Pencatatan lewat App\Support\Ledger, jadi pemasukan berulang ikut dialirkan ke ruang (potret
 * alokasi) dan saldo akun bergerak seperti transaksi biasa.
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

                try {
                    DB::transaction(function () use ($user, $rule) {
                        self::record($user, $rule, $rule->next_due);

                        $rule->next_due = RecurrenceSchedule::next($rule->start_date, $rule->frequency, $rule->next_due);
                        $rule->save();
                    });
                } catch (LedgerException) {
                    // Mis. pos atau akunnya bukan milik pengguna ini lagi: tertahan, bukan galat halaman.
                    $rule->refresh();
                    $blocked[] = $rule;
                    break;
                }

                $recorded++;
                $steps++;
            }
        }

        return ['recorded' => $recorded, 'blocked' => $blocked];
    }

    private static function record(User $user, RecurringRule $rule, Carbon $date): void
    {
        if ($rule->type === 'pemasukan') {
            Ledger::recordIncome($user, $rule->amount_minor, $rule->account, $date, $rule->source, $rule->note, $rule->id);

            return;
        }

        Ledger::recordExpense($user, $rule->amount_minor, $rule->category, $rule->account, $date, $rule->note, [
            'recurring_rule_id' => $rule->id,
        ]);
    }
}
