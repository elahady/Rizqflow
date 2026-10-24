<?php

namespace Tests\Feature\Api;

use App\Auth\ApiTokenService;
use App\Models\User;
use Illuminate\Foundation\Testing\DatabaseMigrations;
use Illuminate\Support\Facades\DB;
use Laravel\Sanctum\NewAccessToken;
use PDOException;
use Tests\TestCase;

/**
 * `ApiTokenService::issueForDevice` mengulang transaksi yang dibatalkan karena deadlock.
 *
 * Memakai DatabaseMigrations, BUKAN RefreshDatabase: RefreshDatabase membungkus tes dalam satu
 * transaksi, dan Laravel tidak mengulang transaksi bersarang (melempar DeadlockException ke luar).
 * Di produksi `issueForDevice` dipanggil di luar transaksi lain, jadi yang diuji di sini adalah jalur
 * produksinya.
 *
 * KETERBATASAN: deadlock InnoDB sungguhan (kode 1213) tidak bisa ditimbulkan di SQLite. Tes ini hanya
 * membuktikan bahwa kegagalan yang BERBENTUK deadlock (pesan MySQL) diulang dan akhirnya berhasil, dan
 * bahwa yang gagal terus berhenti setelah percobaan habis; bukan bahwa MySQL tak lagi menemui deadlock.
 */
class ApiTokenDeadlockRetryTest extends TestCase
{
    use DatabaseMigrations;

    public const DEADLOCK = 'SQLSTATE[40001]: Serialization failure: 1213 Deadlock found when trying to get lock; try restarting transaction';

    /** Layanan yang gagal "deadlock" pada $failures penerbitan pertama, lalu normal. */
    private function flakyService(int $failures): object
    {
        return new class($failures) extends ApiTokenService
        {
            public int $calls = 0;

            public function __construct(private int $failures) {}

            public function issue(User $user, string $name, array $abilities = ['*']): NewAccessToken
            {
                $this->calls++;
                if ($this->calls <= $this->failures) {
                    throw new PDOException(ApiTokenDeadlockRetryTest::DEADLOCK);
                }

                return parent::issue($user, $name, $abilities);
            }
        };
    }

    public function test_a_deadlock_is_retried_and_the_login_succeeds(): void
    {
        $user = User::factory()->create();
        $old = $user->createToken('android:hp', ['*'], now()->addDay());
        $service = $this->flakyService(2);

        $new = $service->issueForDevice($user, 'android:hp');

        $this->assertSame(3, $service->calls);
        $this->assertInstanceOf(NewAccessToken::class, $new);
        // Percobaan yang gagal dibatalkan seluruhnya: tepat satu token tersisa, yang baru.
        $this->assertSame(1, $user->tokens()->count());
        $this->assertNotSame($old->accessToken->getKey(), $user->tokens()->sole()->getKey());
        $this->assertSame(0, DB::transactionLevel());
    }

    public function test_the_retry_gives_up_after_three_attempts_and_keeps_the_old_token(): void
    {
        $user = User::factory()->create();
        $old = $user->createToken('android:hp', ['*'], now()->addDay());
        $service = $this->flakyService(99);

        try {
            $service->issueForDevice($user, 'android:hp');
            $this->fail('Setelah percobaan habis, kegagalan seharusnya merambat.');
        } catch (PDOException $e) {
            $this->assertStringContainsString('Deadlock', $e->getMessage());
        }

        $this->assertSame(ApiTokenService::TRANSACTION_ATTEMPTS, $service->calls);
        $this->assertSame(3, ApiTokenService::TRANSACTION_ATTEMPTS);
        $this->assertSame([$old->accessToken->getKey()], $user->tokens()->pluck('id')->all());
    }

    public function test_a_failure_that_is_not_a_deadlock_is_not_retried(): void
    {
        $user = User::factory()->create();
        $service = new class extends ApiTokenService
        {
            public int $calls = 0;

            public function issue(User $user, string $name, array $abilities = ['*']): NewAccessToken
            {
                $this->calls++;
                throw new PDOException('kesalahan biasa');
            }
        };

        try {
            $service->issueForDevice($user, 'android:hp');
            $this->fail('Kegagalan seharusnya merambat.');
        } catch (PDOException) {
        }

        $this->assertSame(1, $service->calls);
    }
}
