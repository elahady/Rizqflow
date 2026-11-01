package com.roziqrizal.rizqflow.domain.sync

/**
 * `code` stabil di badan galat JSON server (`{"message", "code"}`; docs/server-sync.md bagian "Kode galat JSON dan
 * batas laju"). Klien bercabang pada kode ini, tidak pada teks `message`.
 */
object ServerErrorCodes {
    /** 403: butuh langganan Sync. Satu-satunya kode 403 yang membuka paywall Sync (selain 403 tanpa kode, server lama). */
    const val SYNC_REQUIRED = "sync_required"

    /** 403 pada `members`, atau 404 pada `leave`: pemanggil bukan anggota ruang itu. */
    const val NOT_ROOM_MEMBER = "not_room_member"

    /** 403 pada `remove`: hanya pemilik ruang yang boleh mengeluarkan anggota. */
    const val NOT_ROOM_OWNER = "not_room_owner"

    /** 404 pada `remove`: anggota yang dituju tidak ada di ruang itu. */
    const val MEMBER_NOT_FOUND = "member_not_found"

    /** 422 pada `remove`: pemilik ruang tidak bisa dikeluarkan. */
    const val OWNER_CANNOT_BE_REMOVED = "owner_cannot_be_removed"

    /** 422 pada `leave`: pemilik ruang tidak bisa keluar (belum ada pindah kepemilikan). */
    const val OWNER_CANNOT_LEAVE = "owner_cannot_leave"

    /** 422: id ruang tidak sah (1 sampai 100 karakter). */
    const val INVALID_ROOM_ID = "invalid_room_id"

    /** 429: batas laju; badan galat membawa `retry_after` (detik) dan responsnya header `Retry-After`. */
    const val RATE_LIMITED = "rate_limited"

    /**
     * Kode yang dipetakan ke [RemoteError.Rejected] APAPUN status HTTP-nya, termasuk 404 (yang tanpa daftar ini tetap
     * [RemoteError.Unexpected], karena 404 polos berarti rute belum ada atau proxy yang menjawab). 403 dan 422 selalu
     * [RemoteError.Rejected] (403 hanya bila kodenya bukan [SYNC_REQUIRED]).
     */
    val REJECTION_CODES: Set<String> = setOf(
        NOT_ROOM_MEMBER,
        NOT_ROOM_OWNER,
        MEMBER_NOT_FOUND,
        OWNER_CANNOT_BE_REMOVED,
        OWNER_CANNOT_LEAVE,
        INVALID_ROOM_ID,
    )
}

/** HTTP 429 dari server (batas laju). Bukan galat jaringan dan bukan "gagal" generik: tampilkan pesan lembut. */
fun RemoteError.isRateLimited(): Boolean = this is RemoteError.Unexpected && httpCode == 429

/** Jeda yang diminta server untuk 429 (detik), null bila tidak dikirim atau bukan 429. */
fun RemoteError.rateLimitRetryAfterSeconds(): Long? =
    if (isRateLimited()) (this as RemoteError.Unexpected).retryAfterSeconds else null

/** `code` galat server bila galat ini [RemoteError.Rejected] yang membawanya, selain itu null. */
fun RemoteError.rejectionCode(): String? = (this as? RemoteError.Rejected)?.code
