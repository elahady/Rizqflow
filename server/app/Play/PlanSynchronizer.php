<?php

namespace App\Play;

use App\Models\PlayPurchase;
use App\Models\User;
use App\Support\Plan;
use Carbon\CarbonInterface;
use Illuminate\Support\Facades\DB;

/**
 * Satu-satunya tempat yang menulis `users.plans` untuk jalur pembelian Play. Dipanggil setelah
 * tiap perubahan pada baris `play_purchases` (klaim, notifikasi RTDN, play:reconcile).
 *
 * Aturan: untuk tiap paket yang punya baris pembelian, paket ada di `users.plans` bila ADA baris
 * yang berhak sekarang. Paket yang tidak punya baris pembelian sama sekali dibiarkan apa adanya,
 * jadi akun uji, seed, atau tinker tidak terhapus.
 *
 * Hanya paket yang disebut pemanggil yang dinilai ulang, yaitu paket yang barisnya BARU SAJA
 * diverifikasi ke Play pada panggilan itu. Paket lain tidak disentuh walau barisnya tampak basi
 * menurut data tersimpan: baris yang tidak ditanyakan ke Play, atau yang Play-nya gagal, tidak
 * boleh mencabut apa pun hanya karena baris lain di akun yang sama berubah (kegagalan menghubungi
 * Play tidak pernah mengubah paket). Paket yang dipilih dinilai dari data tersimpan (status dan
 * expires_at) pada waktu sekarang.
 */
class PlanSynchronizer
{
    /**
     * @param  list<Plan>  $plans paket yang barisnya baru diverifikasi; kosong berarti tidak melakukan apa pun
     */
    public function sync(User $user, array $plans, ?CarbonInterface $now = null): void
    {
        if ($plans === []) {
            return;
        }

        $now ??= now();
        $recompute = array_map(fn (Plan $plan) => $plan->value, $plans);

        $fresh = DB::transaction(function () use ($user, $now, $recompute) {
            // Kunci baris pengguna supaya dua sinkronisasi yang berbalapan tidak saling menimpa
            // (diabaikan SQLite, berlaku di MySQL produksi).
            $locked = User::query()->lockForUpdate()->find($user->id);
            if ($locked === null) {
                return null; // akun sudah dihapus
            }

            $purchases = PlayPurchase::where('user_id', $locked->id)->get()
                ->groupBy(fn (PlayPurchase $purchase) => $purchase->plan->value);

            $current = $locked->plans ?? [];
            foreach (Plan::cases() as $plan) {
                if (! in_array($plan->value, $recompute, true)) {
                    continue; // tanpa verifikasi baru pada panggilan ini: dibiarkan apa adanya
                }

                $owned = $purchases->get($plan->value);
                if ($owned === null || $owned->isEmpty()) {
                    continue; // tanpa baris pembelian: dibiarkan apa adanya
                }

                $entitled = $owned->contains(fn (PlayPurchase $purchase) => $purchase->entitles($now));
                $current = $entitled
                    ? array_merge($current, [$plan->value])
                    : array_filter($current, fn (string $value) => $value !== $plan->value);
            }

            $normalized = self::normalize($current);
            if ($normalized !== self::normalize($locked->plans ?? [])) {
                $locked->forceFill(['plans' => $normalized])->save();
            }

            return $locked;
        });

        // Samakan instance pemanggil dengan yang tersimpan.
        if ($fresh !== null) {
            $user->setRawAttributes($fresh->getAttributes(), true);
        }
    }

    /**
     * Paket akun ini menurut instance yang diberikan: urutan tetap (pro lalu sync), tanpa duplikat.
     *
     * @return list<string>
     */
    public static function plansOf(User $user): array
    {
        return self::normalize($user->plans ?? []);
    }

    /**
     * @param  array<int|string, string>  $plans
     * @return list<string>
     */
    private static function normalize(array $plans): array
    {
        return array_values(array_filter(
            array_map(fn (Plan $plan) => $plan->value, Plan::cases()),
            fn (string $value) => in_array($value, $plans, true),
        ));
    }
}
