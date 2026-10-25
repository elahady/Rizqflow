<?php

namespace Tests\Feature;

use App\Livewire\Profil;
use App\Models\PlayPurchase;
use App\Models\User;
use App\Play\PurchaseStatus;
use App\Support\Plan;
use Illuminate\Foundation\Testing\RefreshDatabase;
use Livewire\Livewire;
use Tests\TestCase;

/** Profil: status langganan Sync (Tahap 14). Hanya membaca users.plans dan play_purchases. */
class ProfilSyncStatusTest extends TestCase
{
    use RefreshDatabase;

    public function test_account_without_any_sync_purchase_shows_not_subscribed(): void
    {
        $user = User::factory()->create(['plans' => []]);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Langganan Sync')
            ->assertSee('Tidak berlangganan')
            ->assertSee('Kamu belum berlangganan Sync')
            ->assertSee('Kelola langganan lewat Google Play');
    }

    public function test_active_subscription_shows_active_with_renewal_date(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $expires = now()->addDays(30)->startOfSecond();
        $this->purchase($user, PurchaseStatus::Active, $expires, autoRenewing: true);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Aktif')
            ->assertSee('Diperpanjang otomatis oleh Google Play')
            ->assertSee($expires->locale('id')->translatedFormat('j F Y'))
            ->assertSee('Kelola langganan lewat Google Play')
            ->assertDontSee('Tidak berlangganan');
    }

    public function test_canceled_but_still_running_subscription_says_it_will_not_renew(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $expires = now()->addDays(10)->startOfSecond();
        $this->purchase($user, PurchaseStatus::Canceled, $expires, autoRenewing: false);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Aktif')
            ->assertSee('Tidak diperpanjang otomatis')
            ->assertSee($expires->locale('id')->translatedFormat('j F Y'));
    }

    public function test_grace_period_shows_masa_tenggang_with_payment_hint(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);
        $expires = now()->addDays(3)->startOfSecond();
        $this->purchase($user, PurchaseStatus::GracePeriod, $expires, autoRenewing: true);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Masa tenggang')
            ->assertSee('Pembayaran belum berhasil')
            ->assertSee('perbarui metode pembayaran di Google Play')
            ->assertSee($expires->locale('id')->translatedFormat('j F Y'))
            ->assertDontSee('Diperpanjang otomatis oleh Google Play');
    }

    public function test_expired_subscription_shows_berakhir_and_the_lock_notice(): void
    {
        $user = User::factory()->create(['plans' => []]);
        $expires = now()->subDays(5)->startOfSecond();
        $this->purchase($user, PurchaseStatus::Expired, $expires, autoRenewing: false);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Berakhir')
            ->assertSee('Langganan berakhir pada '.$expires->locale('id')->translatedFormat('j F Y'))
            ->assertSee('Fitur sync terkunci sampai berlangganan lagi')
            ->assertSee('Kelola langganan lewat Google Play');
    }

    public function test_pending_purchase_does_not_count_as_a_subscription(): void
    {
        $user = User::factory()->create(['plans' => []]);
        $this->purchase($user, PurchaseStatus::Pending, now()->addDays(30), autoRenewing: null);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Tidak berlangganan')
            ->assertDontSee('Berakhir');
    }

    public function test_sync_in_plans_without_purchase_rows_still_shows_active_without_date(): void
    {
        $user = User::factory()->create(['plans' => ['sync']]);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Aktif')
            ->assertSee('Tidak ada tanggal berakhir yang tercatat di server');
    }

    public function test_status_follows_users_plans_when_access_is_removed(): void
    {
        // Baris masih berhak, tetapi users.plans sudah dicabut: fitur terkunci, jadi tampilan tidak boleh "Aktif".
        $user = User::factory()->create(['plans' => []]);
        $this->purchase($user, PurchaseStatus::Active, now()->addDays(30), autoRenewing: true);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Berakhir')
            ->assertDontSee('Diperpanjang otomatis oleh Google Play');
    }

    public function test_pro_purchase_does_not_show_in_the_sync_status(): void
    {
        $user = User::factory()->create(['plans' => ['pro']]);
        PlayPurchase::create([
            'user_id' => $user->id,
            'plan' => Plan::Pro,
            'product_id' => 'rizqflow_pro',
            'token_hash' => PlayPurchase::hashToken('token-pro-'.$user->id),
            'purchase_token' => 'token-pro-'.$user->id,
            'status' => PurchaseStatus::Purchased,
            'last_verified_at' => now(),
        ]);

        Livewire::actingAs($user)->test(Profil::class)
            ->assertSee('Tidak berlangganan')
            ->assertDontSee('Berakhir');
    }

    /** Baris pembelian Sync seperti yang ditulis PlanSynchronizer setelah verifikasi Play. */
    private function purchase(User $user, PurchaseStatus $status, \DateTimeInterface $expires, ?bool $autoRenewing): PlayPurchase
    {
        static $n = 0;
        $token = 'token-sync-'.$user->id.'-'.(++$n);

        return PlayPurchase::create([
            'user_id' => $user->id,
            'plan' => Plan::Sync,
            'product_id' => 'rizqflow_sync',
            'token_hash' => PlayPurchase::hashToken($token),
            'purchase_token' => $token,
            'status' => $status,
            'expires_at' => $expires,
            'auto_renewing' => $autoRenewing,
            'acknowledged' => true,
            'last_verified_at' => now(),
        ]);
    }
}
