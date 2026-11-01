package com.roziqrizal.rizqflow.domain.sync

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Berkas SQLite sintetis: kepala 100 byte yang sah (ukuran halaman, versi skema), isi sisanya nol. */
private fun sqliteBytes(pages: Int = 2, pageSize: Int = 4096, version: Int = 9): ByteArray {
    val bytes = ByteArray(pages * pageSize)
    "SQLite format 3\u0000".encodeToByteArray().copyInto(bytes)
    val raw = if (pageSize == 65_536) 1 else pageSize
    bytes[16] = (raw shr 8).toByte()
    bytes[17] = raw.toByte()
    bytes[60] = (version shr 24).toByte()
    bytes[61] = (version shr 16).toByte()
    bytes[62] = (version shr 8).toByte()
    bytes[63] = version.toByte()
    return bytes
}

/** Penyimpanan salinan palsu: mencatat urutan panggilan dan bisa diatur gagal di tiap langkah. */
private class FakeCopyStore(var content: ByteArray? = sqliteBytes(), var modified: Long = 1_700_000_000_000L) : PreRestoreCopyStore {
    val calls = mutableListOf<String>()
    var locked = false
    var lockHeldByOther = false
    var readReturnsNull = false
    var markUsedFails = false
    var used = false
    var headerOverride: ByteArray? = null

    override suspend fun stat(): PreRestoreCopyStat? {
        calls += "stat"
        val c = content ?: return null
        return PreRestoreCopyStat(c.size.toLong(), modified, headerOverride ?: c.copyOf(minOf(c.size, SqliteDatabaseCheck.HEADER_SIZE)))
    }

    override suspend fun readAll(): ByteArray? {
        calls += "read"
        return if (readReturnsNull) null else content?.copyOf()
    }

    override fun tryLock(): Boolean {
        calls += "lock"
        if (lockHeldByOther || locked) return false
        locked = true
        return true
    }

    override fun unlock() {
        calls += "unlock"
        locked = false
    }

    override suspend fun markUsed(): Boolean {
        calls += "markUsed"
        if (markUsedFails || content == null) return false
        used = true
        return true
    }

    override suspend fun unmarkUsed() {
        calls += "unmarkUsed"
        used = false
    }
}

private class FakeEmergency(private val store: FakeCopyStore, var fails: Boolean = false) : LocalSafetyBackup {
    var taken = 0

    override suspend fun take(): LocalBackupHandle? {
        store.calls += "emergency"
        if (fails) return null
        taken++
        return LocalBackupHandle("/data/akun.db.prerevert", 8192, 1_800_000_000_000L)
    }
}

private class Rig(content: ByteArray? = sqliteBytes(), maxVersion: Int = 9) {
    val store = FakeCopyStore(content)
    val emergency = FakeEmergency(store)
    val flow = PreRestoreRevertFlow(store, emergency, maxSchemaVersion = { maxVersion })
    var applied: ByteArray? = null
    var applyThrows: Throwable? = null

    suspend fun apply(bytes: ByteArray) {
        store.calls += "apply"
        applyThrows?.let { throw it }
        applied = bytes
    }
}

/**
 * Pengembalian ke salinan `.prerestore` (`PreRestoreRevertFlow`): kelayakan, urutan langkah, dan galat di tengah yang tidak
 * boleh merusak data (tidak ada yang berubah sebelum `apply`; salinan dan kunci selalu dipulihkan). Tanpa berkas nyata.
 */
class PreRestoreRevertFlowTest {
    // ---- Kelayakan ----

    @Test
    fun `tanpa salinan tidak layak dan menu tidak tampil`() = runBlocking<Unit> {
        val r = Rig(content = null)

        assertEquals(RevertEligibility.NoCopy, r.flow.eligibility())
    }

    @Test
    fun `salinan SQLite yang utuh layak dan melaporkan ukuran dan waktunya`() = runBlocking<Unit> {
        val r = Rig()

        val e = assertIs<RevertEligibility.Available>(r.flow.eligibility())
        assertEquals(8192L, e.copy.sizeBytes)
        assertEquals(1_700_000_000_000L, e.copy.createdAtMillis)
        assertTrue(r.store.calls.none { it == "lock" }, "memeriksa kelayakan tidak mengambil kunci")
    }

    @Test
    fun `salinan dari skema lebih lama layak karena Room memigrasinya`() = runBlocking<Unit> {
        val r = Rig(content = sqliteBytes(version = 3), maxVersion = 9)

        assertIs<RevertEligibility.Available>(r.flow.eligibility())
    }

    @Test
    fun `salinan dari skema lebih baru dari aplikasi tidak layak supaya aplikasi tidak macet saat membukanya`() = runBlocking<Unit> {
        val r = Rig(content = sqliteBytes(version = 10), maxVersion = 9)

        assertEquals(RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.SCHEMA_TOO_NEW), r.flow.eligibility())
    }

    @Test
    fun `berkas yang bukan SQLite, terpotong, atau tanpa versi skema tidak layak`() = runBlocking<Unit> {
        assertEquals(
            RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.NOT_SQLITE),
            Rig(content = ByteArray(8192) { 7 }).flow.eligibility(),
        )
        assertEquals(
            RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.NOT_SQLITE),
            Rig(content = ByteArray(40) { 1 }).flow.eligibility(),
            "lebih pendek dari kepala 100 byte",
        )
        assertEquals(
            RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.TRUNCATED),
            Rig(content = sqliteBytes().copyOf(4096 + 1000)).flow.eligibility(),
            "ukuran bukan kelipatan halaman",
        )
        assertEquals(
            RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.EMPTY_SCHEMA),
            Rig(content = sqliteBytes(version = 0)).flow.eligibility(),
            "database yang belum pernah dibuka Room",
        )
    }

    @Test
    fun `ukuran halaman yang tidak sah ditolak dan 65536 dikenali lewat nilai 1`() = runBlocking<Unit> {
        val badPage = sqliteBytes().also { it[16] = 0x03; it[17] = 0x00 } // 768, bukan pangkat dua
        assertEquals(RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.NOT_SQLITE), Rig(content = badPage).flow.eligibility())

        assertIs<RevertEligibility.Available>(Rig(content = sqliteBytes(pages = 1, pageSize = 65_536)).flow.eligibility())
    }

    @Test
    fun `kepala yang tak terbaca dianggap bukan SQLite`() = runBlocking<Unit> {
        val r = Rig()
        r.store.headerOverride = null
        val stat = PreRestoreCopyStat(8192, 1L, header = null)

        assertEquals(SqliteDatabaseCheck.Problem.NOT_SQLITE, SqliteDatabaseCheck.check(stat.header, stat.sizeBytes, 9))
    }

    // ---- Urutan langkah ----

    @Test
    fun `urutan penuh - kunci, periksa, baca, salinan darurat, konfirmasi, tandai terpakai, timpa, lepas kunci`() = runBlocking<Unit> {
        val original = sqliteBytes()
        val r = Rig(content = original)

        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())
        assertEquals(listOf("lock", "stat", "read", "emergency"), r.store.calls, "belum ada yang diubah sebelum konfirmasi")
        assertTrue(r.store.locked, "kunci dipegang selama menunggu konfirmasi")
        assertEquals("/data/akun.db.prerevert", ready.emergencyBackup.location)

        r.store.calls.clear()
        val result = r.flow.revert(ready) { r.apply(it) }

        assertEquals(RevertResult.Reverted(ready.emergencyBackup), result)
        assertEquals(listOf("markUsed", "apply", "unlock"), r.store.calls)
        assertContentEquals(original, r.applied, "yang ditimpakan persis isi salinan")
        assertTrue(!r.store.locked)
        assertTrue(r.store.used)
    }

    @Test
    fun `salinan darurat diambil SEBELUM salinan ditandai terpakai atau ditimpakan`() = runBlocking<Unit> {
        val r = Rig()
        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())
        r.flow.revert(ready) { r.apply(it) }

        val calls = r.store.calls
        assertTrue(calls.indexOf("emergency") < calls.indexOf("markUsed"))
        assertTrue(calls.indexOf("emergency") < calls.indexOf("apply"))
        assertEquals(1, r.emergency.taken)
    }

    @Test
    fun `pembatalan setelah siap hanya melepas kunci dan tidak menyentuh salinan`() = runBlocking<Unit> {
        val r = Rig()
        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())

        r.flow.cancel(ready)
        r.flow.cancel(ready)

        assertTrue(!r.store.locked)
        assertEquals(1, r.store.calls.count { it == "unlock" }, "melepas kunci hanya sekali")
        assertTrue(r.store.calls.none { it == "markUsed" || it == "apply" })
    }

    @Test
    fun `setelah dibatalkan tidak bisa dipakai lagi`() = runBlocking<Unit> {
        val r = Rig()
        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())
        r.flow.cancel(ready)

        assertEquals(RevertResult.AlreadyUsed, r.flow.revert(ready) { r.apply(it) })
        assertNull(r.applied)
    }

    @Test
    fun `dua kali revert dengan bukti yang sama hanya menimpa sekali`() = runBlocking<Unit> {
        val r = Rig()
        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())

        assertIs<RevertResult.Reverted>(r.flow.revert(ready) { r.apply(it) })
        assertEquals(RevertResult.AlreadyUsed, r.flow.revert(ready) { r.apply(it) })

        assertEquals(1, r.store.calls.count { it == "apply" })
    }

    // ---- Galat di tengah tidak merusak data ----

    @Test
    fun `pengembalian lain yang sedang berjalan - sibuk, tidak ada yang disentuh`() = runBlocking<Unit> {
        val r = Rig()
        r.store.lockHeldByOther = true

        assertEquals(RevertPreparation.Busy, r.flow.prepare())
        assertEquals(listOf("lock"), r.store.calls, "tidak membaca, tidak membuat salinan darurat")
    }

    @Test
    fun `salinan hilang saat disiapkan melepas kunci tanpa membuat salinan darurat`() = runBlocking<Unit> {
        val r = Rig(content = null)

        assertEquals(RevertPreparation.NotEligible(RevertEligibility.NoCopy), r.flow.prepare())
        assertTrue(!r.store.locked)
        assertEquals(0, r.emergency.taken)
    }

    @Test
    fun `salinan tidak layak saat disiapkan melepas kunci tanpa membuat salinan darurat`() = runBlocking<Unit> {
        val r = Rig(content = sqliteBytes(version = 99))

        val result = assertIs<RevertPreparation.NotEligible>(r.flow.prepare())
        assertEquals(RevertEligibility.Unusable(SqliteDatabaseCheck.Problem.SCHEMA_TOO_NEW), result.why)
        assertTrue(!r.store.locked)
        assertEquals(0, r.emergency.taken)
    }

    @Test
    fun `salinan tak terbaca atau berubah ukuran di tengah jalan melepas kunci`() = runBlocking<Unit> {
        val unreadable = Rig().also { it.store.readReturnsNull = true }
        assertEquals(RevertPreparation.CopyUnreadable, unreadable.flow.prepare())
        assertTrue(!unreadable.store.locked)
        assertEquals(0, unreadable.emergency.taken)

        // stat melihat 8192 byte, tetapi isi yang terbaca lain (berkas diganti di antaranya).
        val changed = Rig()
        changed.store.headerOverride = sqliteBytes().copyOf(100)
        val shorter = sqliteBytes(pages = 1)
        val racing = object : PreRestoreCopyStore by changed.store {
            override suspend fun readAll(): ByteArray = shorter
        }
        val flow = PreRestoreRevertFlow(racing, changed.emergency, maxSchemaVersion = { 9 })
        assertEquals(RevertPreparation.CopyUnreadable, flow.prepare())
        assertEquals(0, changed.emergency.taken)
    }

    @Test
    fun `gagal membuat salinan darurat berhenti sebelum menyentuh apa pun dan melepas kunci`() = runBlocking<Unit> {
        val r = Rig()
        r.emergency.fails = true

        assertEquals(RevertPreparation.EmergencyBackupFailed, r.flow.prepare())

        assertTrue(!r.store.locked)
        assertTrue(r.store.calls.none { it == "markUsed" || it == "apply" })
        assertTrue(!r.store.used, "salinan tetap utuh")
    }

    @Test
    fun `salinan hilang antara konfirmasi dan eksekusi - tidak menimpa apa pun`() = runBlocking<Unit> {
        val r = Rig()
        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())
        r.store.markUsedFails = true

        val result = r.flow.revert(ready) { r.apply(it) }

        assertEquals(RevertResult.CopyGone, result)
        assertNull(r.applied)
        assertTrue(r.store.calls.none { it == "apply" })
        assertTrue(!r.store.locked)
        assertTrue("unmarkUsed" !in r.store.calls, "tidak pernah ditandai, jadi tidak perlu dikembalikan")
    }

    @Test
    fun `penimpa yang gagal mengembalikan penanda dan melepas kunci, salinan bisa dicoba lagi`() = runBlocking<Unit> {
        val r = Rig()
        r.applyThrows = IllegalStateException("disk penuh")
        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())

        val result = r.flow.revert(ready) { r.apply(it) }

        assertEquals(RevertResult.ApplyFailed("IllegalStateException"), result, "hanya nama kelas galat, tanpa isi")
        assertTrue(!r.store.used, "penanda terpakai dikembalikan")
        assertTrue(!r.store.locked)

        r.applyThrows = null
        val again = assertIs<RevertPreparation.Ready>(r.flow.prepare())
        assertIs<RevertResult.Reverted>(r.flow.revert(again) { r.apply(it) })
    }

    @Test
    fun `pembatalan coroutine di tengah penimpaan tetap mengembalikan penanda dan melepas kunci lalu dilempar ulang`() = runBlocking<Unit> {
        val r = Rig()
        r.applyThrows = CancellationException("dibatalkan")
        val ready = assertIs<RevertPreparation.Ready>(r.flow.prepare())

        assertFailsWith<CancellationException> { r.flow.revert(ready) { r.apply(it) } }

        assertTrue(!r.store.used)
        assertTrue(!r.store.locked)
    }
}
