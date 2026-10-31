package com.roziqrizal.rizqflow.domain.sync

/** Status tampilan satu [SyncIssue] untuk "Riwayat sinkron". */
enum class SyncIssueStatus {
    /** Masih dicoba ulang otomatis di akhir sync dan sync berikutnya. */
    RETRYING,

    /**
     * Rujukan yang ditunggu tidak kunjung tiba setelah [SyncIssuePolicy.MAX_ATTEMPTS] percobaan atau
     * [SyncIssuePolicy.MAX_AGE_MILLIS] (sync-desain.md bagian B "Referensi belum tiba"). Baris TETAP tersimpan
     * (dan tetap dicoba ulang, karena mencoba tidak merugikan dan rujukan bisa saja tiba); penanda ini hanya
     * untuk ditampilkan jujur ke pengguna.
     */
    PERMANENTLY_FAILED,

    /** Jenis masalah lain (bukan rujukan tertunda): ditampilkan apa adanya, tanpa aturan gagal permanen. */
    OTHER,
}

/**
 * Aturan "gagal permanen" untuk `sync_issue(DEFERRED_MISSING_REF)`: 30 hari ATAU 20 percobaan, mana yang lebih
 * dulu (sync-desain.md bagian B). Murni, tanpa jam sistem: waktu masuk sebagai parameter.
 */
object SyncIssuePolicy {
    const val MAX_ATTEMPTS = 20
    const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun status(issue: SyncIssue, nowMillis: Long): SyncIssueStatus {
        if (issue.kind != SyncIssueKind.DEFERRED_MISSING_REF) return SyncIssueStatus.OTHER
        val tooMany = issue.attempts >= MAX_ATTEMPTS
        val tooOld = nowMillis - issue.createdAtMillis >= MAX_AGE_MILLIS
        return if (tooMany || tooOld) SyncIssueStatus.PERMANENTLY_FAILED else SyncIssueStatus.RETRYING
    }

    fun isPermanentlyFailed(issue: SyncIssue, nowMillis: Long): Boolean = status(issue, nowMillis) == SyncIssueStatus.PERMANENTLY_FAILED

    /** Ringkasan untuk layar "Riwayat sinkron": jumlah per status dan daftar yang gagal permanen (terlama dulu). */
    fun summarize(issues: List<SyncIssue>, nowMillis: Long): SyncIssueSummary {
        val byStatus = issues.groupBy { status(it, nowMillis) }
        return SyncIssueSummary(
            retrying = byStatus[SyncIssueStatus.RETRYING].orEmpty().size,
            permanentlyFailed = byStatus[SyncIssueStatus.PERMANENTLY_FAILED].orEmpty().sortedWith(compareBy({ it.createdAtMillis }, { it.id })),
            other = byStatus[SyncIssueStatus.OTHER].orEmpty().size,
        )
    }
}

data class SyncIssueSummary(
    val retrying: Int,
    val permanentlyFailed: List<SyncIssue>,
    val other: Int,
)
