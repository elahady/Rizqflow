<?php

namespace App\Support;

use App\Models\User;
use Illuminate\Support\Carbon;

/**
 * Proyeksi cashflow (Pro, web-only): kemunculan ke depan dari transaksi berulang (RecurringRule)
 * dan tagihan terjadwal (Bill) sampai $days hari, dimulai dari total saldo semua akun sekarang.
 * Utang-piutang tidak diikutkan (sesuai judul task: hanya transaksi berulang dan tagihan).
 * Aturan/tagihan yang tertahan (akun/ruang/kategorinya terarsip) tidak diproyeksikan — konsisten
 * dengan RecurringRunner dan Tagihan yang juga menahannya, bukan memaksa catat.
 */
class CashflowProjector
{
    private const MAX_OCCURRENCES_PER_SOURCE = 200;

    /**
     * @return array{startBalance: int, events: list<array{date: string, source: string, label: string, amount_minor: int, balance_minor: int}>, dailyLabels: list<string>, dailyBalances: list<int>}
     */
    public static function project(User $user, int $days): array
    {
        $today = Carbon::today();
        $end = $today->copy()->addDays($days);
        $startBalance = (int) $user->accounts()->sum('balance_minor');

        $events = [];

        foreach ($user->recurringRules()->where('active', true)->get() as $rule) {
            if ($rule->isFinished() || ! $rule->isUsable()) {
                continue;
            }

            // Jatuh tempo yang sudah lewat (belum sempat dikejar RecurringRunner) diproyeksikan hari ini,
            // supaya tetap masuk jendela proyeksi ke depan.
            $due = $rule->next_due->greaterThan($today) ? $rule->next_due->copy() : $today->copy();
            $steps = 0;
            while ($due->lessThanOrEqualTo($end) && ! ($rule->end_date !== null && $due->greaterThan($rule->end_date)) && $steps < self::MAX_OCCURRENCES_PER_SOURCE) {
                $label = $rule->note ?: ($rule->type === 'pemasukan' ? ($rule->source ?: 'Pemasukan') : ($rule->category?->name ?? 'Pengeluaran'));
                $events[] = [
                    'date' => $due->toDateString(),
                    'source' => 'berulang',
                    'label' => $label,
                    'amount_minor' => $rule->type === 'pemasukan' ? $rule->amount_minor : -$rule->amount_minor,
                ];
                $due = RecurrenceSchedule::next($rule->start_date, $rule->frequency, $due);
                $steps++;
            }
        }

        foreach ($user->bills()->where('active', true)->get() as $bill) {
            if ($bill->isFinished() || ! $bill->isUsable()) {
                continue;
            }

            $due = $bill->next_due->greaterThan($today) ? $bill->next_due->copy() : $today->copy();
            $remainingInstallments = $bill->total_installments !== null ? $bill->total_installments - $bill->paid_count : null;
            $steps = 0;
            while ($due->lessThanOrEqualTo($end) && ($remainingInstallments === null || $steps < $remainingInstallments) && $steps < self::MAX_OCCURRENCES_PER_SOURCE) {
                $events[] = [
                    'date' => $due->toDateString(),
                    'source' => 'tagihan',
                    'label' => $bill->name,
                    'amount_minor' => -$bill->amount_minor,
                ];

                if ($bill->frequency === null) {
                    break;
                }

                $due = RecurrenceSchedule::next($bill->start_date, $bill->frequency, $due);
                $steps++;
            }
        }

        usort($events, fn ($a, $b) => $a['date'] <=> $b['date']);

        $running = $startBalance;
        foreach ($events as &$event) {
            $running += $event['amount_minor'];
            $event['balance_minor'] = $running;
        }
        unset($event);

        $dailyLabels = [];
        $dailyBalances = [];
        $eventIndex = 0;
        $balance = $startBalance;
        for ($i = 0; $i <= $days; $i++) {
            $day = $today->copy()->addDays($i);
            while ($eventIndex < count($events) && $events[$eventIndex]['date'] === $day->toDateString()) {
                $balance = $events[$eventIndex]['balance_minor'];
                $eventIndex++;
            }
            $dailyLabels[] = $day->translatedFormat('j M');
            $dailyBalances[] = $balance;
        }

        return [
            'startBalance' => $startBalance,
            'events' => $events,
            'dailyLabels' => $dailyLabels,
            'dailyBalances' => $dailyBalances,
        ];
    }
}
