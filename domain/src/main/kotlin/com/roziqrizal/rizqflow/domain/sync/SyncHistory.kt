package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import java.time.Instant

/*
 * Bahan layar "Riwayat sinkron" (sync-desain.md bagian I Tahap 7): keadaan sinkron lokal, ringkasan metadata dari server
 * (`GET /api/sync/history`), dan penilaian "tenang atau perlu perhatian". Semuanya murni Kotlin/JVM dan hanya metadata:
 * tidak ada isi transaksi, nominal, nama akun, atau nama kategori di sini. Layar dan ViewModel ada di `:app`.
 */

/**
 * Riwayat sinkron dari server (`GET /api/sync/history`). Hanya METADATA: baris per perangkat, waktu push dan pull
 * terakhir, pemakaian kuota, dan kompaksi terakhir. Server tidak membedakan waktu per cakupan, jadi waktu push/pull
 * adalah milik PERANGKAT (satu `device_id` memakai satu aliran pull untuk cakupan pribadi dan semua ruang bersama).
 */
interface SyncHistoryRemote {
    suspend fun history(apiToken: String): RemoteResult<ServerSyncHistory>
}

data class ServerSyncHistory(
    val devices: List<ServerSyncDevice>,
    val quota: SyncQuotaUsage,
    val compaction: SyncCompactionInfo,
    val serverTimeMs: Long? = null,
) {
    /** Baris milik perangkat [deviceId] (pengenal pemasangan ini); null bila server belum pernah melihatnya. */
    fun forDevice(deviceId: String): ServerSyncDevice? = devices.firstOrNull { it.deviceId == deviceId }

    /** Perangkat lain di akun yang sama (tanpa [deviceId]), urutan dari server dipertahankan. */
    fun others(deviceId: String): List<ServerSyncDevice> = devices.filter { it.deviceId != deviceId }
}

/**
 * Satu perangkat di riwayat server. [rows] = jumlah baris log yang ditulis perangkat itu; [cursor] = posisi pull
 * terakhir (null bila belum pernah menarik); [active] = terlihat dalam jendela perangkat aktif server.
 */
data class ServerSyncDevice(
    val deviceId: String,
    val rows: Long,
    val lastPushAt: Instant?,
    val lastPullAt: Instant?,
    val cursor: Long?,
    val active: Boolean,
)

/** Seberapa penuh kuota akun; ambang lembut, tidak pernah memblokir apa pun di klien. */
enum class SyncQuotaLevel {
    /** Masih lega. */
    COMFORTABLE,

    /** [SyncQuotaUsage.NEARING_PERCENT] persen atau lebih di salah satu batas: ajakan lembut untuk merapikan. */
    NEARING,

    /** Salah satu batas penuh: server menolak push berikutnya dengan jawaban lembut `quota_exceeded`. */
    FULL,
}

data class SyncQuotaUsage(val rows: Long, val bytes: Long, val maxRows: Long, val maxBytes: Long) {
    /** Persen terpakai (0..100) dari batas baris; 0 bila batas tidak diketahui. */
    val rowsPercent: Int get() = percent(rows, maxRows)

    /** Persen terpakai (0..100) dari batas byte; 0 bila batas tidak diketahui. */
    val bytesPercent: Int get() = percent(bytes, maxBytes)

    val level: SyncQuotaLevel
        get() {
            val worst = maxOf(rowsPercent, bytesPercent)
            return when {
                worst >= 100 -> SyncQuotaLevel.FULL
                worst >= NEARING_PERCENT -> SyncQuotaLevel.NEARING
                else -> SyncQuotaLevel.COMFORTABLE
            }
        }

    companion object {
        const val NEARING_PERCENT = 80

        private fun percent(used: Long, max: Long): Int {
            if (max <= 0L || used <= 0L) return 0
            if (used >= max) return 100
            // Bilangan bulat murni (tanpa pecahan biner) dan dipotong ke bawah: 99 sampai benar-benar penuh.
            return (used.toBigInteger() * 100.toBigInteger() / max.toBigInteger()).toInt().coerceIn(0, 99)
        }
    }
}

/** Kompaksi terakhir di server (`sync:compact`): kapan jalan dan berapa versi/tombstone lama yang dibuang. */
data class SyncCompactionInfo(
    val lastRunAt: Instant?,
    val removedVersions: Long,
    val removedTombstones: Long,
)

/**
 * Keadaan satu cakupan di perangkat ini. [roomName] hanya untuk cakupan ruang bersama (nama ruang lokal, bukan isi
 * data); null untuk cakupan pribadi. [dirtyCount] = baris yang sudah berubah di sini tetapi belum terkirim.
 */
data class ScopeSyncStatus(
    val scope: SyncScope,
    val roomName: String?,
    val dirtyCount: Int,
    val cursor: Long,
)

/** Satu masalah sinkron beserta status tampilannya ([SyncIssuePolicy]) pada saat laporan dibuat. */
data class SyncIssueRow(val issue: SyncIssue, val status: SyncIssueStatus)

/**
 * Keadaan sinkron lokal: cakupan, daftar masalah (paling banyak [SyncHistoryDisplay.MAX_ISSUE_ROWS] baris, yang gagal
 * permanen lebih dulu), dan ringkasan seluruh masalah. [issueTotal] = jumlah semua masalah, termasuk yang tidak
 * ditampilkan.
 */
data class LocalSyncHistory(
    val scopes: List<ScopeSyncStatus>,
    val issues: List<SyncIssueRow>,
    val issueTotal: Int,
    val summary: SyncIssueSummary,
) {
    val totalDirty: Int get() = scopes.sumOf { it.dirtyCount }
}

object SyncHistoryDisplay {
    /** Batas baris masalah yang ditampilkan; sisanya cukup diringkas ("dan N lainnya"). */
    const val MAX_ISSUE_ROWS = 50
}

/**
 * Membaca keadaan sinkron lokal dari [store]. Ruang bersama dihitung dari [pendingShared] (jumlah pengeluaran yang
 * belum terkirim di ruang itu; jalur ruang bersama masih memakai penanda lama, bukan `sync_record`) dan diberi nama
 * lewat [roomName]. Cakupan pribadi memakai penanda kotor `sync_record`.
 */
class LocalSyncHistoryReader(
    private val store: SyncStore,
    private val cursors: SyncCursorStore,
    private val pendingShared: suspend (RoomId) -> Int,
    private val roomName: suspend (RoomId) -> String?,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun read(): LocalSyncHistory {
        val now = nowMillis()
        val scopes = buildList {
            add(ScopeSyncStatus(SyncScope.Personal, roomName = null, dirtyCount = store.dirtyCount(SyncScope.Personal), cursor = store.cursor(SyncScope.Personal)))
            for (shared in store.sharedRooms().filter { it.status == SharedRoomStatus.ACTIVE }) {
                add(
                    ScopeSyncStatus(
                        scope = SyncScope.SharedRoom(shared.roomId),
                        roomName = roomName(shared.roomId),
                        dirtyCount = pendingShared(shared.roomId),
                        cursor = cursors.read(shared.roomId.value),
                    ),
                )
            }
        }
        val all = store.issues(null)
        val rows = all
            .map { SyncIssueRow(it, SyncIssuePolicy.status(it, now)) }
            .sortedWith(compareBy({ rank(it.status) }, { it.issue.createdAtMillis }, { it.issue.id }))
        return LocalSyncHistory(
            scopes = scopes,
            issues = rows.take(SyncHistoryDisplay.MAX_ISSUE_ROWS),
            issueTotal = rows.size,
            summary = SyncIssuePolicy.summarize(all, now),
        )
    }

    private fun rank(status: SyncIssueStatus): Int = when (status) {
        SyncIssueStatus.PERMANENTLY_FAILED -> 0
        SyncIssueStatus.OTHER -> 1
        SyncIssueStatus.RETRYING -> 2
    }
}

/** Penilaian keseluruhan untuk spanduk status di layar: selalu ikon plus teks, tidak pernah hanya warna. */
enum class SyncHealth {
    /** Tidak ada yang menunggu dan tidak ada masalah. */
    CALM,

    /** Ada yang menunggu dikirim atau masih dicoba ulang; wajar dan akan selesai sendiri. */
    WAITING,

    /** Ada hal yang butuh perhatian pemilik: gagal permanen, versi tak dikenal, data rusak, atau kuota penuh. */
    NEEDS_ATTENTION,
}

object SyncHealthPolicy {
    /** [server] null bila server belum bisa dihubungi: penilaian lalu hanya berdasarkan keadaan lokal. */
    fun assess(local: LocalSyncHistory, server: ServerSyncHistory?): SyncHealth {
        val kinds = local.issues.map { it.issue.kind }.toSet()
        val attention = local.summary.permanentlyFailed.isNotEmpty() ||
            SyncIssueKind.UNSUPPORTED_VERSION in kinds ||
            SyncIssueKind.TAMPERED_OR_CORRUPT in kinds ||
            server?.quota?.level == SyncQuotaLevel.FULL
        if (attention) return SyncHealth.NEEDS_ATTENTION
        val waiting = local.totalDirty > 0 ||
            local.summary.retrying > 0 ||
            kinds.any { it == SyncIssueKind.DEFERRED_FK_RESTRICT || it == SyncIssueKind.DEFERRED_KEY_MISSING } ||
            server?.quota?.level == SyncQuotaLevel.NEARING
        return if (waiting) SyncHealth.WAITING else SyncHealth.CALM
    }
}
