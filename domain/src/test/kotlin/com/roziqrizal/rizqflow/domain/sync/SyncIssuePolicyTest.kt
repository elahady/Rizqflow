package com.roziqrizal.rizqflow.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Penanda "gagal permanen" untuk rujukan tertunda: 30 hari atau 20 percobaan (sync-desain.md bagian B). */
class SyncIssuePolicyTest {
    private val day = 24L * 60 * 60 * 1000
    private val created = 1_000_000L

    private fun issue(attempts: Int, kind: SyncIssueKind = SyncIssueKind.DEFERRED_MISSING_REF, id: Long = 1, createdAt: Long = created) =
        SyncIssue(SyncScope.Personal, "transaction", "tx-$id", kind, serverId = id, attempts = attempts, createdAtMillis = createdAt, id = id)

    @Test
    fun `19 percobaan dan umur kurang dari 30 hari masih dicoba ulang`() {
        assertEquals(SyncIssueStatus.RETRYING, SyncIssuePolicy.status(issue(19), created + 30 * day - 1))
    }

    @Test
    fun `tepat 20 percobaan menjadi gagal permanen walau masih baru`() {
        assertEquals(SyncIssueStatus.PERMANENTLY_FAILED, SyncIssuePolicy.status(issue(20), created + 1))
    }

    @Test
    fun `tepat 30 hari menjadi gagal permanen walau percobaan sedikit`() {
        assertEquals(SyncIssueStatus.PERMANENTLY_FAILED, SyncIssuePolicy.status(issue(0), created + 30 * day))
    }

    @Test
    fun `jenis masalah lain tidak pernah ditandai gagal permanen`() {
        for (kind in SyncIssueKind.entries - SyncIssueKind.DEFERRED_MISSING_REF) {
            assertEquals(SyncIssueStatus.OTHER, SyncIssuePolicy.status(issue(100, kind), created + 365 * day), "$kind")
            assertFalse(SyncIssuePolicy.isPermanentlyFailed(issue(100, kind), created + 365 * day))
        }
    }

    @Test
    fun `jam perangkat mundur sebelum waktu dicatat tidak membuat gagal permanen`() {
        assertEquals(SyncIssueStatus.RETRYING, SyncIssuePolicy.status(issue(3), created - 10 * day))
    }

    @Test
    fun `ringkasan memisahkan status dan mengurutkan gagal permanen dari yang terlama`() {
        val now = created + 40 * day
        val issues = listOf(
            issue(attempts = 1, id = 1, createdAt = now - day), // masih dicoba
            issue(attempts = 25, id = 2, createdAt = now - 2 * day), // gagal: percobaan
            issue(attempts = 0, id = 3, createdAt = created), // gagal: umur, paling lama
            issue(attempts = 5, kind = SyncIssueKind.TAMPERED_OR_CORRUPT, id = 4),
        )

        val summary = SyncIssuePolicy.summarize(issues, now)

        assertEquals(1, summary.retrying)
        assertEquals(1, summary.other)
        assertEquals(listOf(3L, 2L), summary.permanentlyFailed.map { it.id })
        assertTrue(summary.permanentlyFailed.all { SyncIssuePolicy.isPermanentlyFailed(it, now) })
    }

    @Test
    fun `daftar kosong menghasilkan ringkasan kosong`() {
        assertEquals(SyncIssueSummary(0, emptyList(), 0), SyncIssuePolicy.summarize(emptyList(), created))
    }
}
