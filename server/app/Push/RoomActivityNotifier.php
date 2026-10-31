<?php

namespace App\Push;

use App\Models\PushToken;
use App\Models\RoomMember;
use Illuminate\Support\Facades\Log;
use Throwable;

/**
 * Kabar generik "ada catatan baru" untuk anggota LAIN ruang bersama setelah push sync (Tahap 9/13).
 * Pencatat (akun yang mendorong baris) tidak pernah diberi kabar, termasuk perangkatnya yang lain;
 * perangkat itu mendapatkannya lewat pull biasa.
 *
 * Isi kabar hanya SyncNotificationPayload::newRecord(roomId): tanpa nominal, nama, catatan, atau
 * siapa pencatatnya. Satu kabar per ruang per push, berapa pun banyak barisnya.
 */
class RoomActivityNotifier
{
    public function __construct(private readonly FcmSender $sender) {}

    /** @param  list<string>  $roomIds  ruang yang menerima baris BARU pada push ini */
    public function notifyNewRecords(int $authorUserId, array $roomIds): void
    {
        foreach (array_values(array_unique($roomIds)) as $roomId) {
            $payload = SyncNotificationPayload::newRecord($roomId);

            $penerima = RoomMember::query()
                ->where('room_id', $roomId)
                ->where('user_id', '!=', $authorUserId)
                ->pluck('user_id');
            if ($penerima->isEmpty()) {
                continue;
            }

            $tokens = PushToken::query()->whereIn('user_id', $penerima)->get(['id', 'token']);
            foreach ($tokens as $token) {
                try {
                    $hasil = $this->sender->send($token->token, $payload);
                } catch (Throwable $e) {
                    // Jangan biarkan galat kirim mengganggu apa pun; tidak mencatat token atau pesannya.
                    Log::warning('fcm: kiriman gagal dengan pengecualian', ['exception' => $e::class]);

                    continue;
                }

                if ($hasil === FcmSendResult::InvalidToken) {
                    PushToken::query()->whereKey($token->id)->delete();
                } elseif ($hasil === FcmSendResult::NotConfigured) {
                    return; // FCM belum dikonfigurasi: tidak ada gunanya mencoba token lain.
                }
            }
        }
    }
}
