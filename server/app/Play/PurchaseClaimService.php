<?php

namespace App\Play;

use App\Models\PlayPurchase;
use App\Models\User;
use App\Support\Plan;
use Illuminate\Database\UniqueConstraintViolationException;

/**
 * Klaim pembelian dari aplikasi (`POST /api/purchases/claim`): aplikasi mengirim token setelah
 * masuk, server memverifikasi ke Play, mencatat baris `play_purchases`, lalu PlanSynchronizer
 * mengisi `users.plans`. Data dari klien tidak dipercaya: yang menentukan hanya jawaban Play.
 *
 * Aturan: satu token hanya bisa diklaim satu akun (yang pertama mengklaim; yang lain mendapat
 * claimed_by_other), klaim ulang oleh akun yang sama idempoten dan sekaligus menyegarkan status,
 * dan kegagalan Play tidak pernah memberi atau mencabut apa pun. Ini juga berlaku untuk rantai
 * token (lihat PlayPurchaseChainResolver): token penerus yang pendahulunya sudah diklaim akun lain
 * tidak bisa diklaim manual oleh akun ini, walau belum ada baris untuk token penerus itu sendiri.
 */
class PurchaseClaimService
{
    public function __construct(
        private readonly PlayPurchaseVerifier $verifier,
        private readonly PlanSynchronizer $plans,
    ) {}

    /**
     * @param  list<array{product_id: string, purchase_token: string}>  $items
     * @return list<ClaimStatus> sejajar dengan urutan $items
     *
     * @throws PlayNotConfigured bila verifikasi Play belum dikonfigurasi (tidak ada yang diubah)
     */
    public function claim(User $user, array $items): array
    {
        $this->verifier->ensureConfigured();

        $results = [];
        $memo = [];
        /** @var array<string, Plan> $touched paket yang barisnya baru diverifikasi dan dicatat/diperbarui di sini */
        $touched = [];

        foreach ($items as $item) {
            // Token yang sama dua kali dalam satu permintaan tidak perlu menanyai Play dua kali.
            $key = $item['product_id']."\n".PlayPurchase::hashToken($item['purchase_token']);
            $results[] = $memo[$key] ??= $this->claimOne($user, $item['product_id'], $item['purchase_token'], $touched);
        }

        // Hanya paket yang barisnya baru dijawab Play yang dinilai ulang; paket lain (mis. Sync yang
        // tidak ikut diklaim, atau yang Play-nya gagal) tidak disentuh.
        if ($touched !== []) {
            $this->plans->sync($user, array_values($touched));
        }

        return $results;
    }

    /** @param array<string, Plan> $touched diisi paket produk ini bila barisnya dicatat atau diperbarui */
    private function claimOne(User $user, string $productId, string $token, array &$touched): ClaimStatus
    {
        $product = PlayProduct::tryFrom($productId);
        if ($product === null) {
            return ClaimStatus::UnknownProduct;
        }

        $existing = PlayPurchase::findByToken($token);
        if ($existing !== null && (int) $existing->user_id !== (int) $user->id) {
            return ClaimStatus::ClaimedByOther;
        }
        if ($existing !== null && $existing->product_id !== $product->value) {
            return ClaimStatus::Inactive; // token ini milik produk lain
        }

        try {
            $verification = $this->verifier->verify($product, $token);
        } catch (PlayNotConfigured $e) {
            throw $e;
        } catch (PlayPurchaseNotFound) {
            // Token yang belum pernah kita catat dan tidak dikenal Play: tidak berhak. Untuk baris
            // yang sudah ada, jawaban "tak dikenal" ambigu, jadi diperlakukan tak terverifikasi.
            return $existing === null ? ClaimStatus::Inactive : ClaimStatus::Unverifiable;
        } catch (PlayException) {
            return ClaimStatus::Unverifiable;
        }

        if ($verification->productId !== $product->value) {
            return ClaimStatus::Inactive; // Play melaporkan produk lain dari yang diklaim
        }

        // Rantai token (Tahap 9, "Ikuti token langganan pengganti ke pemilik baris lama"): Play
        // sendiri bisa melaporkan token ini sebagai penerus token lain (linkedPurchaseToken/
        // expiredPurchaseToken). Bila pendahulunya SUDAH diklaim akun lain, token ini rantainya
        // milik akun itu juga — akun yang mengklaim lebih dulu tidak boleh mencuri rantai akun lain
        // hanya karena RTDN belum sempat menyusul (lihat PlayPurchaseChainResolver, arah sebaliknya
        // dari pengecekan ini: penerus otomatis dikaitkan ke pemilik pendahulu).
        if ($existing === null && $verification->predecessorToken !== null) {
            $predecessor = PlayPurchase::findByToken($verification->predecessorToken);
            if ($predecessor !== null && (int) $predecessor->user_id !== (int) $user->id) {
                return ClaimStatus::ClaimedByOther;
            }
        }

        if ($existing === null) {
            // Token yang sudah final (kedaluwarsa, dikembalikan dananya) tidak dicatat: tak ada
            // yang perlu dilacak, dan mencatatnya akan menghapus paket akun uji dari users.plans.
            if (! $verification->status->isFinal()) {
                $existing = $this->record($user, $product, $token, $verification, $touched);
                if ($existing === null) {
                    return ClaimStatus::ClaimedByOther; // kalah balapan dari akun lain
                }

                return $this->statusFor($verification);
            }

            return ClaimStatus::Inactive;
        }

        // Token dari klien ini sama persis dengan yang tercatat (hash cocok dan baru saja lolos Play),
        // jadi sekalian ditulis ulang: memulihkan baris yang tokennya tak terbaca setelah APP_KEY berganti.
        $existing->rewriteToken($token);
        $existing->applyVerification($verification);
        $touched[$product->plan()->value] = $product->plan();

        return $this->statusFor($verification);
    }

    /**
     * Catat klaim baru. Batasan unik pada token_hash adalah penjaga terakhir terhadap balapan:
     * bila pemenangnya akun yang sama, klaim ini idempoten; bila akun lain, kembalikan null.
     *
     * @param  array<string, Plan>  $touched diisi paket produk ini bila baris dicatat atau diperbarui
     */
    private function record(User $user, PlayProduct $product, string $token, PlayVerification $verification, array &$touched): ?PlayPurchase
    {
        try {
            $purchase = PlayPurchase::create([
                'user_id' => $user->id,
                'plan' => $product->plan(),
                'product_id' => $product->value,
                'token_hash' => PlayPurchase::hashToken($token),
                'purchase_token' => $token,
                'status' => $verification->status,
                'expires_at' => $verification->expiresAt,
                'auto_renewing' => $verification->autoRenewing,
                'acknowledged' => $verification->acknowledged,
                'last_verified_at' => now(),
            ]);
        } catch (UniqueConstraintViolationException) {
            $winner = PlayPurchase::findByToken($token);
            if ($winner === null || (int) $winner->user_id !== (int) $user->id) {
                return null;
            }

            $winner->rewriteToken($token);
            $winner->applyVerification($verification);
            $purchase = $winner;
        }

        $touched[$product->plan()->value] = $product->plan();

        return $purchase;
    }

    private function statusFor(PlayVerification $verification): ClaimStatus
    {
        if ($verification->entitles(now())) {
            return ClaimStatus::Active;
        }

        return $verification->status === PurchaseStatus::Pending ? ClaimStatus::Pending : ClaimStatus::Inactive;
    }
}
