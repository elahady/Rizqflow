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

    /** 403 pada `remove` dan `make-owner`: hanya pemilik ruang yang boleh mengeluarkan anggota atau memindahkan kepemilikan. */
    const val NOT_ROOM_OWNER = "not_room_owner"

    /** 404 pada `remove`: anggota yang dituju tidak ada di ruang itu. */
    const val MEMBER_NOT_FOUND = "member_not_found"

    /** 422 pada `remove`: pemilik ruang tidak bisa dikeluarkan. */
    const val OWNER_CANNOT_BE_REMOVED = "owner_cannot_be_removed"

    /** 422 pada `leave`: pemilik ruang tidak bisa keluar sebelum memindahkan kepemilikan. */
    const val OWNER_CANNOT_LEAVE = "owner_cannot_leave"

    /** 422 pada `make-owner`: anggota yang dituju sudah menjadi pemilik ruang itu (tidak ada yang berubah). */
    const val ALREADY_OWNER = "already_owner"

    /** 422: id ruang tidak sah (1 sampai 100 karakter). */
    const val INVALID_ROOM_ID = "invalid_room_id"

    /** 403 pada `join`: ruang sudah berpenghuni dan pemanggil tidak membawa `invite_hash` (QR lama tanpa rahasia, atau bekas anggota). */
    const val INVITE_REQUIRED = "invite_required"

    /** 403 pada `join`: undangan tak dikenal, sudah dipakai, atau milik ruang lain. */
    const val INVITE_INVALID = "invite_invalid"

    /** 403 pada `join`: undangan lewat 7 hari; pemilik harus menampilkan QR baru. */
    const val INVITE_EXPIRED = "invite_expired"

    /** 409 pada `invites`: hash itu sudah dipakai anggota; pakai rahasia baru. */
    const val INVITE_USED = "invite_used"

    /** 422 pada `join`: ruang sudah berisi 5 anggota. */
    const val ROOM_FULL = "room_full"

    /** 429: batas laju; badan galat membawa `retry_after` (detik) dan responsnya header `Retry-After`. */
    const val RATE_LIMITED = "rate_limited"

    /**
     * 410 pada `GET /api/sync/pull` ber-cakupan (dan pull lama): `after` di bawah watermark kompaksi cakupan itu.
     * Klien atur ulang kursor CAKUPAN ITU SAJA ke 0 dan tarik ulang (docs/server-sync.md "Kompaksi dan 410"; kontrak
     * klien butir 5) — tanpa menghapus data lokal, dan tanpa menyentuh kursor cakupan lain.
     */
    const val CURSOR_EXPIRED = "cursor_expired"

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
        ALREADY_OWNER,
        INVALID_ROOM_ID,
        INVITE_REQUIRED,
        INVITE_INVALID,
        INVITE_EXPIRED,
        INVITE_USED,
        ROOM_FULL,
        CURSOR_EXPIRED,
    )
}

/** HTTP 429 dari server (batas laju). Bukan galat jaringan dan bukan "gagal" generik: tampilkan pesan lembut. */
fun RemoteError.isRateLimited(): Boolean = this is RemoteError.Unexpected && httpCode == 429

/** Jeda yang diminta server untuk 429 (detik), null bila tidak dikirim atau bukan 429. */
fun RemoteError.rateLimitRetryAfterSeconds(): Long? =
    if (isRateLimited()) (this as RemoteError.Unexpected).retryAfterSeconds else null

/** `code` galat server bila galat ini [RemoteError.Rejected] yang membawanya, selain itu null. */
fun RemoteError.rejectionCode(): String? = (this as? RemoteError.Rejected)?.code
