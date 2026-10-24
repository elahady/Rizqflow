<?php

use App\Support\AllocationEngine;
use App\Support\Entitlements;
use App\Support\Feature;
use App\Support\Plan;
use Illuminate\Database\Migrations\Migration;
use Illuminate\Support\Facades\DB;

return new class extends Migration
{
    /**
     * Isi potret alokasi untuk pemasukan yang dicatat sebelum tabel allocation_entries ada.
     *
     * Ini **pendekatan** untuk data scaffold web: aturan saat pemasukan itu dicatat tidak pernah
     * tersimpan, jadi pemasukan lama dialirkan dengan aturan pengguna **sekarang**, sama seperti yang
     * akan dilakukan App\Support\Ledger::recordIncome hari ini. Mode lanjutan (Pro) hanya dipakai bila
     * `users.allocation_mode` = 'lanjutan' dan paketnya membuka fitur itu (lewat Entitlements, sama
     * seperti Ledger::allocationMode); selain itu mode persentase: ruang aktif dengan
     * `allocation_percent` > 0 urut `sort_order`, basis point = persen × 100. Mode lanjutan memakai
     * allocateWaterfall dengan `rooms.allocation_cap_minor`, lalu disimpan sebagai persentase lewat
     * impliedShares (docs/model-data.md, "Mode aturan alokasi").
     *
     * Pemasukan yang sudah punya potret tidak disentuh, jadi migrasi aman dijalankan ulang. Aturan
     * yang tidak sah (total persen di atas 100) dilewati: pemasukannya tetap "belum dialirkan".
     */
    public function up(): void
    {
        $users = DB::table('users')->select('id', 'allocation_mode', 'plans')->orderBy('id')->get();

        foreach ($users as $user) {
            $incomes = DB::table('transactions')
                ->where('user_id', $user->id)
                ->where('type', 'pemasukan')
                ->whereNotExists(fn ($q) => $q->select(DB::raw(1))
                    ->from('allocation_entries')
                    ->whereColumn('allocation_entries.transaction_id', 'transactions.id'))
                ->orderBy('id')
                ->get(['id', 'amount_minor']);

            if ($incomes->isEmpty()) {
                continue;
            }

            $rooms = DB::table('rooms')
                ->where('user_id', $user->id)
                ->where('archived', false)
                ->orderBy('sort_order')
                ->orderBy('id')
                ->get(['id', 'allocation_percent', 'allocation_cap_minor']);

            $advanced = $user->allocation_mode === 'lanjutan' && $this->entitlements($user->plans)->isEnabled(Feature::AdvancedAllocationRules);

            $rules = $rooms
                ->filter(fn ($room) => (int) $room->allocation_percent > 0)
                ->map(fn ($room) => ['room_id' => (int) $room->id, 'share_bp' => (int) $room->allocation_percent * 100])
                ->values()
                ->all();

            if (! $advanced && array_sum(array_column($rules, 'share_bp')) > AllocationEngine::FULL_BP) {
                continue;
            }

            $caps = $rooms
                ->map(fn ($room) => ['room_id' => (int) $room->id, 'cap_minor' => $room->allocation_cap_minor !== null ? (int) $room->allocation_cap_minor : null])
                ->values()
                ->all();

            $now = now();
            foreach ($incomes as $income) {
                $amount = (int) $income->amount_minor;
                if ($amount <= 0 || $amount > AllocationEngine::MAX_AMOUNT_MINOR) {
                    continue;
                }

                $rows = [];
                if ($advanced) {
                    $waterfall = AllocationEngine::allocateWaterfall($amount, $caps);
                    $implied = AllocationEngine::impliedShares($amount, $waterfall['shares']);
                    foreach ($waterfall['shares'] as $i => $share) {
                        if ($implied[$i]['share_bp'] > 0 || $share['amount_minor'] > 0) {
                            $rows[] = $this->row((int) $income->id, $share['room_id'], $implied[$i]['share_bp'], $share['amount_minor'], $now);
                        }
                    }
                } else {
                    foreach (AllocationEngine::allocate($amount, $rules)['shares'] as $share) {
                        $rows[] = $this->row((int) $income->id, $share['room_id'], $share['share_bp'], $share['amount_minor'], $now);
                    }
                }

                if ($rows !== []) {
                    DB::table('allocation_entries')->insert($rows);
                }
            }
        }
    }

    /**
     * Tidak dibalik: potret hasil backfill tidak bisa dibedakan dari potret yang dicatat Ledger.
     * Membatalkan migrasi pembuat tabel (create_allocation_entries_table) menghapus semuanya.
     */
    public function down(): void
    {
        //
    }

    private function entitlements(?string $plansJson): Entitlements
    {
        $values = json_decode($plansJson ?? '[]', true);
        $plans = array_values(array_filter(array_map(
            fn ($value) => is_string($value) ? Plan::tryFrom($value) : null,
            is_array($values) ? $values : [],
        )));

        return new Entitlements($plans);
    }

    private function row(int $transactionId, int $roomId, int $shareBp, int $amountMinor, $now): array
    {
        return [
            'transaction_id' => $transactionId,
            'room_id' => $roomId,
            'share_bp' => $shareBp,
            'amount_minor' => $amountMinor,
            'created_at' => $now,
            'updated_at' => $now,
        ];
    }
};
