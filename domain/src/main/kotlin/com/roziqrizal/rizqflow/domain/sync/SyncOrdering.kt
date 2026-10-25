package com.roziqrizal.rizqflow.domain.sync

/** Keputusan murni untuk satu versi masuk terhadap versi lokal (sync-desain.md bagian D). */
enum class IncomingDecision {
    /** Tidak ada versi lokal, atau versi masuk menang atas versi lokal yang sudah bersih. */
    APPLY,

    /** Versi masuk menang atas edit lokal yang belum terkirim: edit itu kalah dan harus dicatat. */
    APPLY_OVER_DIRTY_LOCAL,

    /** Echo, duplikat, atau versi lebih lama: tidak ada yang berubah. */
    IGNORE,
}

/**
 * Aturan urutan total antar versi rekaman yang sama: `(changed_at_ms, tombstone menang, deviceId
 * leksikografis terbesar)`. Total dan deterministik, jadi semua perangkat yang melihat himpunan
 * versi yang sama memilih pemenang yang sama, dalam urutan kedatangan apa pun.
 */
object SyncOrdering {
    /**
     * Membandingkan dua versi: positif bila pertama menang. [deviceId] dibandingkan sebagai teks per
     * unit UTF-16 (ordinal, tanpa locale), sama dengan urutan byte untuk id ASCII/UUID.
     */
    fun compare(
        changedAtMillis: Long,
        deleted: Boolean,
        deviceId: String,
        otherChangedAtMillis: Long,
        otherDeleted: Boolean,
        otherDeviceId: String,
    ): Int {
        val byTime = changedAtMillis.compareTo(otherChangedAtMillis)
        if (byTime != 0) return byTime
        val byDeleted = deleted.compareTo(otherDeleted) // true > false: tombstone menang
        if (byDeleted != 0) return byDeleted
        return deviceId.compareTo(otherDeviceId)
    }

    /**
     * Memutuskan nasib versi [incoming]. Versi lokal tanpa id perangkat dianggap milik [localDeviceId]
     * (perangkat ini). Versi masuk hanya menang bila STRICT lebih besar; sama berarti echo/duplikat.
     */
    fun decide(local: SyncRecordMeta?, incoming: IncomingVersion, localDeviceId: String): IncomingDecision {
        if (local == null) return IncomingDecision.APPLY
        val cmp = compare(
            incoming.changedAtMillis,
            incoming.deleted,
            incoming.deviceId,
            local.changedAtMillis,
            local.deleted,
            local.deviceId ?: localDeviceId,
        )
        return when {
            cmp <= 0 -> IncomingDecision.IGNORE
            local.dirty -> IncomingDecision.APPLY_OVER_DIRTY_LOCAL
            else -> IncomingDecision.APPLY
        }
    }
}

/** Satu baris hasil pull yang menunggu diterapkan, dipakai hanya untuk mengurutkan. */
data class PulledRef(
    val type: SyncRecordType,
    val changedAtMillis: Long,
    val deleted: Boolean,
    val serverId: Long,
)

/**
 * Urutan penerapan satu halaman pull (sync-desain.md bagian B): peringkat tipe (induk sebelum anak),
 * pada peringkat sama baris live sebelum tombstone, lalu `changed_at`, lalu id server.
 */
object SyncApplyOrder : Comparator<PulledRef> {
    override fun compare(a: PulledRef, b: PulledRef): Int {
        val byRank = a.type.applyRank.compareTo(b.type.applyRank)
        if (byRank != 0) return byRank
        val byDeleted = a.deleted.compareTo(b.deleted) // false (live) lebih dulu
        if (byDeleted != 0) return byDeleted
        val byTime = a.changedAtMillis.compareTo(b.changedAtMillis)
        if (byTime != 0) return byTime
        return a.serverId.compareTo(b.serverId)
    }
}
