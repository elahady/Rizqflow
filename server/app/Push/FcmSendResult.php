<?php

namespace App\Push;

/** Hasil satu kiriman FCM. Hanya InvalidToken yang membuat token dibuang. */
enum FcmSendResult
{
    /** FCM menerima pesan. */
    case Sent;

    /** Token sudah tidak berlaku (UNREGISTERED): aplikasi dicopot atau token diganti. Buang barisnya. */
    case InvalidToken;

    /** Galat lain (jaringan, 5xx, kuota, konfigurasi). Token dibiarkan; tidak diulang. */
    case Failed;

    /** Pengirim FCM belum dikonfigurasi (FCM_PROJECT_ID atau kunci akun layanan kosong). */
    case NotConfigured;
}
