package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Bahan layar "Riwayat sinkron": kuota, penilaian "tenang atau perlu perhatian", dan pembaca keadaan lokal. */
class SyncHistoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val created = 1_000_000L

    // ---- kuota

    @Test
    fun `kuota di bawah ambang masih lega, tepat 80 persen hampir penuh, tepat penuh adalah penuh`() {
        assertEquals(SyncQuotaLevel.COMFORTABLE, SyncQuotaUsage(rows = 199_999, bytes = 0, maxRows = 250_000, maxBytes = 157_286_400).level)
        assertEquals(SyncQuotaLevel.NEARING, SyncQuotaUsage(rows = 200_000, bytes = 0, maxRows = 250_000, maxBytes = 157_286_400).level)
        assertEquals(SyncQuotaLevel.NEARING, SyncQuotaUsage(rows = 249_999, bytes = 0, maxRows = 250_000, maxBytes = 157_286_400).level)
        assertEquals(SyncQuotaLevel.FULL, SyncQuotaUsage(rows = 250_000, bytes = 0, maxRows = 250_000, maxBytes = 157_286_400).level)
    }

    @Test
    fun `persen kuota tidak pernah menampilkan 100 sebelum benar-benar penuh`() {
        val almost = SyncQuotaUsage(rows = 249_999, bytes = 157_286_399, maxRows = 250_000, maxBytes = 157_286_400)
        assertEquals(99, almost.rowsPercent)
        assertEquals(99, almost.bytesPercent)
        assertEquals(100, SyncQuotaUsage(rows = 300_000, bytes = 0, maxRows = 250_000, maxBytes = 1).rowsPercent)
    }

    @Test
    fun `batas yang paling penuh menentukan tingkat kuota`() {
        // Baris longgar, byte penuh.
        assertEquals(SyncQuotaLevel.FULL, SyncQuotaUsage(rows = 10, bytes = 157_286_400, maxRows = 250_000, maxBytes = 157_286_400).level)
        // Baris longgar, byte 80 persen.
        assertEquals(SyncQuotaLevel.NEARING, SyncQuotaUsage(rows = 10, bytes = 125_829_120, maxRows = 250_000, maxBytes = 157_286_400).level)
    }

    @Test
    fun `batas yang tidak diketahui atau pemakaian nol tidak pernah dianggap penuh`() {
        assertEquals(SyncQuotaLevel.COMFORTABLE, SyncQuotaUsage(rows = 5, bytes = 5, maxRows = 0, maxBytes = 0).level)
        assertEquals(SyncQuotaLevel.COMFORTABLE, SyncQuotaUsage(rows = 0, bytes = 0, maxRows = 250_000, maxBytes = 157_286_400).level)
        assertEquals(0, SyncQuotaUsage(rows = -3, bytes = -3, maxRows = 10, maxBytes = 10).rowsPercent)
    }

    // ---- server

    @Test
    fun `riwayat server memisahkan baris perangkat ini dari perangkat lain`() {
        val mine = ServerSyncDevice("hp-a", 5, null, null, 9, true)
        val other = ServerSyncDevice("hp-b", 2, null, null, null, false)
        val history = ServerSyncHistory(listOf(other, mine), SyncQuotaUsage(0, 0, 10, 10), SyncCompactionInfo(null, 0, 0))

        assertEquals(mine, history.forDevice("hp-a"))
        assertEquals(listOf(other), history.others("hp-a"))
        assertNull(history.forDevice("hp-tak-dikenal"))
    }

    // ---- penilaian

    private fun issue(kind: SyncIssueKind, attempts: Int = 0, at: Long = created, id: Long = 1) =
        SyncIssue(SyncScope.Personal, "transaction", "t-$id", kind, serverId = id, attempts = attempts, createdAtMillis = at, id = id)

    private fun local(dirty: Int = 0, issues: List<SyncIssue> = emptyList(), now: Long = created): LocalSyncHistory {
        val rows = issues.map { SyncIssueRow(it, SyncIssuePolicy.status(it, now)) }
        return LocalSyncHistory(
            scopes = listOf(ScopeSyncStatus(SyncScope.Personal, null, dirty, 0)),
            issues = rows,
            issueTotal = rows.size,
            summary = SyncIssuePolicy.summarize(issues, now),
        )
    }

    private fun server(rows: Long = 0, maxRows: Long = 250_000) =
        ServerSyncHistory(emptyList(), SyncQuotaUsage(rows, 0, maxRows, 157_286_400), SyncCompactionInfo(null, 0, 0))

    @Test
    fun `tidak ada yang menunggu dan tidak ada masalah adalah tenang`() {
        assertEquals(SyncHealth.CALM, SyncHealthPolicy.assess(local(), server()))
        assertEquals(SyncHealth.CALM, SyncHealthPolicy.assess(local(), null), "tanpa data server, hanya keadaan lokal yang dinilai")
    }

    @Test
    fun `baris kotor atau masalah yang masih dicoba berarti menunggu, bukan perhatian`() {
        assertEquals(SyncHealth.WAITING, SyncHealthPolicy.assess(local(dirty = 3), server()))
        assertEquals(SyncHealth.WAITING, SyncHealthPolicy.assess(local(issues = listOf(issue(SyncIssueKind.DEFERRED_MISSING_REF, attempts = 2))), server()))
        assertEquals(SyncHealth.WAITING, SyncHealthPolicy.assess(local(issues = listOf(issue(SyncIssueKind.DEFERRED_KEY_MISSING))), server()))
        assertEquals(SyncHealth.WAITING, SyncHealthPolicy.assess(local(issues = listOf(issue(SyncIssueKind.DEFERRED_FK_RESTRICT))), server()))
    }

    @Test
    fun `catatan perubahan lokal yang digantikan hanya informasi`() {
        assertEquals(SyncHealth.CALM, SyncHealthPolicy.assess(local(issues = listOf(issue(SyncIssueKind.OVERWRITTEN))), server()))
    }

    @Test
    fun `gagal permanen dan jenis masalah berat membutuhkan perhatian`() {
        val permanent = issue(SyncIssueKind.DEFERRED_MISSING_REF, attempts = SyncIssuePolicy.MAX_ATTEMPTS)
        assertEquals(SyncHealth.NEEDS_ATTENTION, SyncHealthPolicy.assess(local(issues = listOf(permanent)), server()))
        assertEquals(SyncHealth.NEEDS_ATTENTION, SyncHealthPolicy.assess(local(issues = listOf(issue(SyncIssueKind.UNSUPPORTED_VERSION))), server()))
        assertEquals(SyncHealth.NEEDS_ATTENTION, SyncHealthPolicy.assess(local(issues = listOf(issue(SyncIssueKind.TAMPERED_OR_CORRUPT))), server()))
    }

    @Test
    fun `masalah rujukan yang melewati 30 hari menjadi perhatian walau percobaannya sedikit`() {
        val old = issue(SyncIssueKind.DEFERRED_MISSING_REF, attempts = 1)
        assertEquals(SyncHealth.NEEDS_ATTENTION, SyncHealthPolicy.assess(local(issues = listOf(old), now = created + 30 * day), server()))
    }

    @Test
    fun `kuota hampir penuh menunggu dan kuota penuh membutuhkan perhatian`() {
        assertEquals(SyncHealth.WAITING, SyncHealthPolicy.assess(local(), server(rows = 200_000)))
        assertEquals(SyncHealth.NEEDS_ATTENTION, SyncHealthPolicy.assess(local(), server(rows = 250_000)))
    }

    // ---- pembaca lokal

    private val roomA = RoomId("room-a")
    private val roomB = RoomId("room-b")

    private fun reader(store: FakeSyncStore, cursors: FakeCursorStore = FakeCursorStore(), now: Long = created) = LocalSyncHistoryReader(
        store = store,
        cursors = cursors,
        pendingShared = { if (it == roomA) 4 else 99 },
        roomName = { if (it == roomA) "Keluarga" else null },
        nowMillis = { now },
    )

    @Test
    fun `pembaca memuat cakupan pribadi lalu setiap ruang bersama aktif saja`() = runBlocking {
        val store = FakeSyncStore(
            dirty = mapOf(SyncScope.Personal to 7),
            shared = listOf(
                SharedRoomInfo(roomA, SharedRoomRole.OWNER, SharedRoomStatus.ACTIVE, 1),
                SharedRoomInfo(roomB, SharedRoomRole.MEMBER, SharedRoomStatus.REMOVED, 2),
            ),
        )
        store.cursors[SyncScope.Personal] = 41
        val cursors = FakeCursorStore().apply { save(roomA.value, 12) }

        val history = reader(store, cursors).read()

        assertEquals(
            listOf(
                ScopeSyncStatus(SyncScope.Personal, null, dirtyCount = 7, cursor = 41),
                ScopeSyncStatus(SyncScope.SharedRoom(roomA), "Keluarga", dirtyCount = 4, cursor = 12),
            ),
            history.scopes,
        )
        assertEquals(11, history.totalDirty)
    }

    @Test
    fun `pembaca mengurutkan gagal permanen dulu, lalu jenis lain, lalu yang masih dicoba`() = runBlocking {
        val now = created + 40 * day
        val retrying = issue(SyncIssueKind.DEFERRED_MISSING_REF, attempts = 1, at = now - day, id = 1)
        val other = issue(SyncIssueKind.UNSUPPORTED_VERSION, at = now - 2 * day, id = 2)
        val permanentNew = issue(SyncIssueKind.DEFERRED_MISSING_REF, attempts = 25, at = now - 3 * day, id = 3)
        val permanentOld = issue(SyncIssueKind.DEFERRED_MISSING_REF, attempts = 0, at = created, id = 4)
        val store = FakeSyncStore(issueList = listOf(retrying, other, permanentNew, permanentOld))

        val history = reader(store, now = now).read()

        assertEquals(listOf(4L, 3L, 2L, 1L), history.issues.map { it.issue.id })
        assertEquals(
            listOf(SyncIssueStatus.PERMANENTLY_FAILED, SyncIssueStatus.PERMANENTLY_FAILED, SyncIssueStatus.OTHER, SyncIssueStatus.RETRYING),
            history.issues.map { it.status },
        )
        assertEquals(1, history.summary.retrying)
        assertEquals(2, history.summary.permanentlyFailed.size)
        assertEquals(1, history.summary.other)
        assertEquals(4, history.issueTotal)
    }

    @Test
    fun `daftar masalah dipotong pada batas tampilan tetapi ringkasan menghitung semuanya`() = runBlocking {
        val many = (1..(SyncHistoryDisplay.MAX_ISSUE_ROWS + 10)).map { issue(SyncIssueKind.DEFERRED_MISSING_REF, at = created + it, id = it.toLong()) }

        val history = reader(FakeSyncStore(issueList = many)).read()

        assertEquals(SyncHistoryDisplay.MAX_ISSUE_ROWS, history.issues.size)
        assertEquals(SyncHistoryDisplay.MAX_ISSUE_ROWS + 10, history.issueTotal)
        assertEquals(SyncHistoryDisplay.MAX_ISSUE_ROWS + 10, history.summary.retrying)
    }

    @Test
    fun `tanpa ruang bersama dan tanpa masalah laporan hanya berisi cakupan pribadi`() = runBlocking {
        val history = reader(FakeSyncStore()).read()

        assertEquals(1, history.scopes.size)
        assertEquals(0, history.totalDirty)
        assertEquals(0, history.issueTotal)
        assertEquals(SyncHealth.CALM, SyncHealthPolicy.assess(history, null))
    }
}
