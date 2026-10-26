package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/*
 * Model dan kontrak sinkron entitas penuh, Tahap 1 "Fondasi" (docs/sync-desain.md, USULAN Claude,
 * keputusan pemilik di bagian J belum dikonfirmasi). Semuanya murni Kotlin/JVM: implementasi Room
 * ada di :data. SyncEngine lama (transaksi EXPENSE ruang bersama) tidak memakai berkas ini dan
 * tetap berjalan sampai Tahap 2 menggantikannya.
 */

/**
 * Cakupan sinkron: `a` = semua data pribadi satu pengguna (kunci akun), `r:<roomId>` = proyeksi
 * ruang bersama (kunci ruang). Bentuk teks [wire] ikut masuk AAD amplop.
 */
sealed class SyncScope(val wire: String) {
    data object Personal : SyncScope("a")

    data class SharedRoom(val roomId: RoomId) : SyncScope("r:${roomId.value}") {
        init {
            require(roomId.value.isNotEmpty() && '|' !in roomId.value) { "Id ruang tidak sah untuk cakupan sinkron" }
        }
    }

    override fun toString(): String = wire

    companion object {
        /** Kebalikan dari [wire]; null bila bukan `a` atau `r:<id>` yang sah. */
        fun parse(wire: String): SyncScope? = when {
            wire == "a" -> Personal
            wire.startsWith("r:") && wire.length > 2 && '|' !in wire -> SharedRoom(RoomId(wire.substring(2)))
            else -> null
        }
    }
}

/**
 * Jenis rekaman yang disinkronkan (sync-desain.md bagian A). [applyRank] adalah urutan penerapan
 * hasil pull (bagian B): induk lebih dulu dari anak supaya rujukan hampir selalu sudah ada;
 * yang belum ada ditunda lewat [SyncIssueKind.DEFERRED_MISSING_REF].
 */
enum class SyncRecordType(val wire: String, val applyRank: Int) {
    ROOM("room", 10),
    ACCOUNT("account", 20),
    CATEGORY("category", 30),
    ALLOCATION_CONFIG("allocation_config", 40),
    TRANSACTION("transaction", 50),
    DEBT("debt", 60),
    DEBT_PAYMENT("debt_payment", 70),
    BILL("bill", 80),
    RECURRING_RULE("recurring_rule", 90),
    ZAKAT_PROFILE("zakat_profile", 100),
    WEALTH_ITEM("wealth_item", 110),
    WEALTH_CHECK("wealth_check", 120),
    ZAKAT_PAYMENT("zakat_payment", 130),
    GOLD_PRICE("gold_price", 140),
    TRADER_PROFILE("trader_profile", 150),
    DCA_PLAN("dca_plan", 160),
    QUICK_FAVORITE("quick_favorite", 170),
    DAY_CHECK("day_check", 180),
    ;

    companion object {
        fun fromWire(wire: String): SyncRecordType? = entries.firstOrNull { it.wire == wire }
    }
}

/** Pengenal rekaman yang bukan id entitas biasa. */
object SyncRecordIds {
    /** Satu rekaman singleton: mode alokasi + persen + batas per ruang (atomik karena total wajib 100%). */
    const val ALLOCATION_CONFIG = "allocation_config"

    fun dayCheck(epochDay: Long) = "d:$epochDay"

    fun goldPrice(epochDay: Long) = "g:$epochDay"
}

/** Versi sebuah rekaman di penyimpanan lokal (satu baris `sync_record`). */
data class SyncRecordMeta(
    val scope: SyncScope,
    val type: SyncRecordType,
    val recordId: String,
    val changedAtMillis: Long,
    val deleted: Boolean,
    val dirty: Boolean,
    /** `changed_at` terakhir yang sudah dikirim atau diterima; null bila belum pernah. */
    val pushedChangedAtMillis: Long?,
    val author: String?,
    /** Perangkat penulis versi ini; null = perangkat ini sendiri (diisi pemanggil saat membandingkan). */
    val deviceId: String?,
)

/** Satu versi yang sudah dikirim ke server; dipakai [SyncStore.markPushed]. */
data class SentVersion(val type: SyncRecordType, val recordId: String, val changedAtMillis: Long)

/** Versi masuk dari hasil pull yang sudah lolos dekripsi dan pemeriksaan amplop. */
data class IncomingVersion(
    val changedAtMillis: Long,
    val deleted: Boolean,
    val deviceId: String,
    val author: String? = null,
    /** Id baris di server (urutan pull); dipakai [SyncApplyOrder] dan catatan masalah. */
    val serverId: Long? = null,
)

/** Hasil [SyncStore.applyIncoming]. */
enum class ApplyDecision {
    /** Versi masuk menang dan sudah diterapkan. */
    APPLIED,

    /** Versi masuk menang atas edit lokal yang masih kotor; edit lokal kalah dan dicatat [SyncIssueKind.OVERWRITTEN]. */
    APPLIED_OVER_LOCAL_EDIT,

    /** Echo, duplikat, atau versi lebih lama: tidak ada yang diubah. */
    IGNORED,
}

/** Peran dan status keanggotaan di ruang bersama (tanpa rahasia; sync-desain.md bagian E). */
enum class SharedRoomRole { OWNER, MEMBER }

enum class SharedRoomStatus { ACTIVE, REMOVED, LEFT }

data class SharedRoomInfo(
    val roomId: RoomId,
    val role: SharedRoomRole,
    val status: SharedRoomStatus,
    val joinedAtMillis: Long,
)

/** Jenis masalah sinkron yang dicatat di `sync_issue` (bagian E). */
enum class SyncIssueKind {
    DEFERRED_MISSING_REF,
    DEFERRED_FK_RESTRICT,
    DEFERRED_KEY_MISSING,
    UNSUPPORTED_VERSION,
    TAMPERED_OR_CORRUPT,
    OVERWRITTEN,
}

/** Satu baris masalah sinkron. [rawRow] hanya untuk diagnosis lokal; tidak pernah dikirim ke server. */
data class SyncIssue(
    val scope: SyncScope,
    val recordType: String,
    val recordId: String,
    val kind: SyncIssueKind,
    val serverId: Long? = null,
    val detail: String? = null,
    val rawRow: String? = null,
    val attempts: Int = 0,
    val createdAtMillis: Long,
    val id: Long = 0,
)

/**
 * Elemen konteks korutin "sedang menerapkan hasil pull": perubahan yang ditulis di dalamnya datang
 * dari luar, bukan edit lokal, jadi pencatat ([SyncStore] dan recorder di :data) tidak menandainya
 * kotor. Mencegah loop pull -> kotor -> push.
 */
class SyncApplying private constructor() : AbstractCoroutineContextElement(Key) {
    override fun toString() = "SyncApplying"

    companion object Key : CoroutineContext.Key<SyncApplying> {
        val INSTANCE = SyncApplying()
    }
}

/**
 * Penerapan versi masuk gagal diam-diam: [SyncStore.applyIncoming] sudah selesai tanpa galat, tetapi transaksinya
 * ternyata dibatalkan (biasanya karena penangan menelan galat repositori; lihat KDoc [SyncRecordHandler.apply]).
 * Pemanggil TIDAK boleh memajukan kursor untuk baris ini.
 */
class SyncApplyAbortedException(message: String) : IllegalStateException(message)

/** Menjalankan [block] di konteks `applying` (tidak menandai kotor). */
suspend fun <R> withSyncApplying(block: suspend () -> R): R = withContext(SyncApplying.INSTANCE) { block() }

/** True bila pemanggil sedang berada di konteks `applying`. */
suspend fun isSyncApplying(): Boolean = currentCoroutineContext()[SyncApplying.Key] != null

/**
 * Kontrak penyimpanan sinkron lokal (`sync_record`, `sync_cursor`, `sync_issue`, `shared_room`).
 * Implementasi Room ada di :data. Operasi yang disebut atomik berjalan dalam satu transaksi.
 */
interface SyncStore {
    suspend fun meta(scope: SyncScope, type: SyncRecordType, recordId: String): SyncRecordMeta?

    /**
     * Menandai rekaman kotor (ada perubahan lokal yang belum dikirim) dengan cap dari [clock]:
     * `max(jam + offset, changed_at lama + 1)`. [deleted] true = tombstone. Tidak melakukan apa-apa
     * di konteks [SyncApplying]. Mengembalikan cap yang dipakai, atau null bila dilewati.
     */
    suspend fun markDirty(scope: SyncScope, type: SyncRecordType, recordId: String, deleted: Boolean, clock: SyncClock): Long?

    /** Rekaman kotor satu cakupan, tertua dulu. */
    suspend fun dirtyRecords(scope: SyncScope, limit: Int = 200): List<SyncRecordMeta>

    /**
     * Rekaman kotor satu cakupan DAN satu tipe saja, tertua dulu (Tahap 3 sync-desain.md: setiap
     * [SyncRecordHandler.collectDirty] hanya butuh tipenya sendiri). Bawaan menyaring dari
     * [dirtyRecords] tanpa tipe; implementasi Room ([RoomSyncStore]) menimpa ini dengan query SQL
     * langsung supaya tidak terpotong duluan oleh [limit] tipe lain yang lebih tua.
     */
    suspend fun dirtyRecords(scope: SyncScope, type: SyncRecordType, limit: Int = 200): List<SyncRecordMeta> =
        dirtyRecords(scope, Int.MAX_VALUE).asSequence().filter { it.type == type }.take(limit).toList()

    suspend fun dirtyCount(scope: SyncScope): Int

    /**
     * Mencatat bahwa [sent] sudah dikirim. Penanda kotor dibersihkan HANYA untuk rekaman yang
     * `changed_at`-nya masih sama dengan yang dikirim: edit yang terjadi selama push berjalan tetap
     * kotor dan terkirim di putaran berikutnya. Mengembalikan jumlah rekaman yang dibersihkan.
     */
    suspend fun markPushed(scope: SyncScope, sent: List<SentVersion>): Int

    /**
     * Menerapkan satu versi masuk secara atomik dengan aturan urutan total ([SyncOrdering]).
     * Bila versi masuk menang, [applyBlock] dijalankan di dalam transaksi yang sama dan di konteks
     * [SyncApplying], lalu meta rekaman diperbarui (bersih, tidak kotor). Bila kalah atau sama,
     * [applyBlock] tidak dipanggil. Pelemparan di [applyBlock] membatalkan semuanya.
     *
     * **[applyBlock] tidak boleh menelan galat repositori.** Repositori menulis lewat `withTransaction` bersarang; galat
     * di dalamnya menandai transaksi LUAR gagal walau galatnya ditangkap, sehingga seluruh transaksi (termasuk meta)
     * dibatalkan diam-diam sementara pemanggilnya merasa berhasil. Biarkan galat merambat, atau lakukan pra-cek (rujukan
     * ada, FK terpenuhi) sebelum menulis dan catat `SyncIssue` DEFERRED_* tanpa menulis apa pun. Implementasi Room
     * memeriksa ulang meta setelah komit dan melempar [SyncApplyAbortedException] bila pembatalan itu terjadi
     * (hanya terdeteksi bila dipanggil di luar transaksi lain).
     * [localDeviceId] dipakai untuk versi lokal yang tidak menyimpan id perangkat.
     */
    suspend fun applyIncoming(
        scope: SyncScope,
        type: SyncRecordType,
        recordId: String,
        incoming: IncomingVersion,
        localDeviceId: String,
        applyBlock: suspend () -> Unit,
    ): ApplyDecision

    suspend fun cursor(scope: SyncScope): Long

    suspend fun setCursor(scope: SyncScope, afterId: Long)

    suspend fun recordIssue(issue: SyncIssue): Long

    /** Membuang satu masalah yang sudah berhasil diterapkan ulang (Tahap 3: percobaan ulang `DEFERRED_*` di akhir sync). */
    suspend fun resolveIssue(id: Long)

    /** Menambah hitungan percobaan ulang satu masalah yang masih gagal (dasar "riwayat sinkron gagal permanen" setelah 20 percobaan atau 30 hari). */
    suspend fun incrementIssueAttempts(id: Long)

    suspend fun issues(scope: SyncScope? = null): List<SyncIssue>

    /** Membuang masalah tertua sampai paling banyak [maxRows] baris dan lebih muda dari [maxAgeMillis]. */
    suspend fun pruneIssues(nowMillis: Long, maxRows: Int = 2000, maxAgeMillis: Long = 90L * 24 * 60 * 60 * 1000): Int

    suspend fun sharedRoom(roomId: RoomId): SharedRoomInfo?

    suspend fun sharedRooms(): List<SharedRoomInfo>

    suspend fun saveSharedRoom(info: SharedRoomInfo)
}
