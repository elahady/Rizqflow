package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.FilePreRestoreCopyStore
import com.roziqrizal.rizqflow.data.sync.PreRestoreBackup
import com.roziqrizal.rizqflow.data.sync.PreRestoreCleaner
import com.roziqrizal.rizqflow.data.sync.PreRestoreCleaner.Outcome
import com.roziqrizal.rizqflow.data.sync.PreRestoreFiles
import com.roziqrizal.rizqflow.data.sync.PreRestoreUseLock
import com.roziqrizal.rizqflow.domain.sync.PreRestoreRevertFlow
import com.roziqrizal.rizqflow.domain.sync.RevertEligibility
import com.roziqrizal.rizqflow.domain.sync.RevertPreparation
import com.roziqrizal.rizqflow.domain.sync.RevertResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Berkas SQLite sintetis (kepala sah, versi skema [version], isi lain [fill]). */
private fun sqliteFile(pages: Int = 2, version: Int = 9, fill: Byte = 0): ByteArray {
    val bytes = ByteArray(pages * 4096) { fill }
    "SQLite format 3\u0000".encodeToByteArray().copyInto(bytes)
    bytes[16] = 0x10 // ukuran halaman 4096
    bytes[17] = 0x00
    bytes[60] = (version shr 24).toByte()
    bytes[61] = (version shr 16).toByte()
    bytes[62] = (version shr 8).toByte()
    bytes[63] = version.toByte()
    return bytes
}

/**
 * Lapisan berkas pengembalian `.prerestore` (Tahap 7 sync-desain.md): kunci pakai, ganti nama "terpakai", salinan darurat
 * `.prerevert`, dan pembersih yang tidak boleh menghapus salinan yang sedang dipakai. Berkas sungguhan di folder sementara;
 * waktu disuntikkan, tanpa tidur nyata. Alur dirangkai dengan `PreRestoreRevertFlow` sungguhan.
 */
class PreRestoreRevertFilesTest {
    private lateinit var dir: File
    private lateinit var db: File
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L
    private var now = t0
    private val logs = mutableListOf<String>()
    private val liveDb = sqliteFile(fill = 1)
    private val prerestore = sqliteFile(fill = 2)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rv").toFile()
        db = File(dir, "akun.db").also { it.writeBytes(liveDb) }
        copyFile().writeBytes(prerestore)
        copyFile().setLastModified(t0)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun copyFile() = File(dir, "akun.db" + PreRestoreFiles.PRERESTORE)

    private fun lock() = PreRestoreUseLock(databaseFile = { db }, nowMillis = { now })

    private fun store(lock: PreRestoreUseLock = lock()) =
        FilePreRestoreCopyStore(databaseFile = { db }, lock = lock, nowMillis = { now }, log = { logs += it })

    private fun flow(lock: PreRestoreUseLock = lock(), maxVersion: Int = 9) = PreRestoreRevertFlow(
        copies = store(lock),
        emergency = PreRestoreBackup(databaseFile = { db }, checkpoint = {}, nowMillis = { now }, log = { logs += it }, suffix = PreRestoreFiles.PREREVERT),
        maxSchemaVersion = { maxVersion },
    )

    private fun cleaner(lock: PreRestoreUseLock = lock(), pure: Boolean = false) = PreRestoreCleaner(
        databaseFile = { db },
        isWorkspacePure = { pure },
        isInUse = lock::isHeld,
        nowMillis = { now },
        log = { logs += it },
    )

    // ---- Kunci pakai ----

    @Test
    fun `kunci hanya bisa dipegang satu pihak dan bisa dilepas`() {
        val a = lock()
        val b = lock()

        assertTrue(a.tryAcquire())
        assertTrue(a.isHeld())
        assertFalse(b.tryAcquire(), "pihak lain tidak bisa mengambil selagi dipegang")

        a.release()
        assertFalse(a.isHeld())
        assertTrue(b.tryAcquire())
    }

    @Test
    fun `kunci yang basi karena proses mati dianggap lepas dan bisa diambil`() {
        assertTrue(lock().tryAcquire())

        now = t0 + 59 * 60 * 1000
        assertTrue(lock().isHeld(), "belum satu jam")
        now = t0 + 61 * 60 * 1000
        assertFalse(lock().isHeld(), "lebih dari satu jam: proses pemegangnya dianggap mati")
        assertTrue(lock().tryAcquire())
    }

    @Test
    fun `jam yang melompat jauh ke arah mana pun membuat kunci basi, bukan tertahan selamanya`() {
        assertTrue(lock().tryAcquire())

        now = t0 - 10 * day
        assertFalse(lock().isHeld())
    }

    // ---- Penyimpanan salinan ----

    @Test
    fun `stat melaporkan ukuran, waktu, dan kepala berkas`() = runBlocking<Unit> {
        val stat = assertNotNull(store().stat())

        assertEquals(prerestore.size.toLong(), stat.sizeBytes)
        assertEquals(t0, stat.lastModifiedMillis)
        assertEquals(100, assertNotNull(stat.header).size)
        assertContentEquals(prerestore.copyOf(100), stat.header)
    }

    @Test
    fun `tanpa berkas salinan stat dan baca kosong`() = runBlocking<Unit> {
        copyFile().delete()

        assertNull(store().stat())
        assertNull(store().readAll())
        assertFalse(store().markUsed())
    }

    @Test
    fun `tandai terpakai mengganti nama dan memulai umur dari sekarang, kembalikan memulihkan nama dan waktunya`() = runBlocking<Unit> {
        val s = store()
        now = t0 + 5 * day

        assertTrue(s.markUsed())
        val used = File(dir, "akun.db" + PreRestoreFiles.USED)
        assertFalse(copyFile().exists())
        assertContentEquals(prerestore, used.readBytes())
        assertEquals(now, used.lastModified(), "jendela 30 hari sebelum dibersihkan dimulai saat dipakai")

        s.unmarkUsed()
        assertTrue(copyFile().isFile)
        assertFalse(used.exists())
        assertEquals(t0, copyFile().lastModified(), "waktu asli salinan dikembalikan")
        assertContentEquals(prerestore, copyFile().readBytes())
    }

    // ---- Alur sungguhan di atas berkas ----

    @Test
    fun `pengembalian berhasil - salinan darurat sama dengan data sekarang, isi prerestore diserahkan, database tidak disentuh alur`() = runBlocking<Unit> {
        val f = flow()
        assertIs<RevertEligibility.Available>(f.eligibility())

        val ready = assertIs<RevertPreparation.Ready>(f.prepare())
        val emergency = File(dir, "akun.db" + PreRestoreFiles.PREREVERT)
        assertContentEquals(liveDb, emergency.readBytes(), "keadaan SEKARANG tersimpan sebelum apa pun berubah")
        assertTrue(File(dir, "akun.db" + PreRestoreFiles.LOCK).isFile, "kunci dipegang selama menunggu konfirmasi")
        assertContentEquals(prerestore, copyFile().readBytes(), "salinan belum disentuh sebelum konfirmasi")

        var handed: ByteArray? = null
        val result = f.revert(ready) { handed = it }

        assertIs<RevertResult.Reverted>(result)
        assertContentEquals(prerestore, handed)
        assertContentEquals(liveDb, db.readBytes(), "alur ini tidak menimpa database; itu tugas penimpa yang diserahi isi")
        assertFalse(File(dir, "akun.db" + PreRestoreFiles.LOCK).exists(), "kunci dilepas")
        assertFalse(copyFile().exists(), "salinan terpakai dilepas dari menu")
        assertContentEquals(prerestore, File(dir, "akun.db" + PreRestoreFiles.USED).readBytes())
        assertEquals(RevertEligibility.NoCopy, f.eligibility(), "menu hilang setelah dipakai")
    }

    @Test
    fun `penimpa gagal - semua berkas kembali seperti semula dan bisa dicoba lagi`() = runBlocking<Unit> {
        val f = flow()
        val ready = assertIs<RevertPreparation.Ready>(f.prepare())

        val result = f.revert(ready) { throw java.io.IOException("ruang penuh") }

        assertEquals(RevertResult.ApplyFailed("IOException"), result)
        assertContentEquals(liveDb, db.readBytes())
        assertContentEquals(prerestore, copyFile().readBytes(), "salinan kembali ke namanya")
        assertEquals(t0, copyFile().lastModified())
        assertFalse(File(dir, "akun.db" + PreRestoreFiles.USED).exists())
        assertFalse(File(dir, "akun.db" + PreRestoreFiles.LOCK).exists())
        assertIs<RevertEligibility.Available>(f.eligibility())
    }

    @Test
    fun `salinan darurat gagal dibuat - tidak ada yang berubah dan kunci lepas`() = runBlocking<Unit> {
        // Folder tujuan salinan darurat dihalangi: sebuah DIREKTORI bernama sama dengan berkas .prerevert.
        File(dir, "akun.db" + PreRestoreFiles.PREREVERT).mkdir()
        File(dir, "akun.db" + PreRestoreFiles.PREREVERT + "/penghalang").writeBytes(ByteArray(1))
        val f = flow()

        assertEquals(RevertPreparation.EmergencyBackupFailed, f.prepare())

        assertContentEquals(liveDb, db.readBytes())
        assertContentEquals(prerestore, copyFile().readBytes())
        assertFalse(File(dir, "akun.db" + PreRestoreFiles.LOCK).exists())
        assertFalse(File(dir, "akun.db" + PreRestoreFiles.USED).exists())
    }

    @Test
    fun `salinan dari skema lebih baru tidak dikembalikan dan tidak ada salinan darurat yang dibuat`() = runBlocking<Unit> {
        copyFile().writeBytes(sqliteFile(version = 10))

        val f = flow(maxVersion = 9)

        assertIs<RevertEligibility.Unusable>(f.eligibility())
        assertIs<RevertPreparation.NotEligible>(f.prepare())
        assertFalse(File(dir, "akun.db" + PreRestoreFiles.PREREVERT).exists())
        assertFalse(File(dir, "akun.db" + PreRestoreFiles.LOCK).exists())
    }

    @Test
    fun `salinan terpotong tidak layak`() = runBlocking<Unit> {
        copyFile().writeBytes(prerestore.copyOf(4096 + 17))

        assertIs<RevertEligibility.Unusable>(flow().eligibility())
    }

    @Test
    fun `dua pengembalian tidak berjalan bersamaan`() = runBlocking<Unit> {
        val shared = lock()
        val first = flow(shared)
        val second = flow(shared)

        val ready = assertIs<RevertPreparation.Ready>(first.prepare())
        assertEquals(RevertPreparation.Busy, second.prepare())

        first.cancel(ready)
        assertIs<RevertPreparation.Ready>(second.prepare())
    }

    // ---- Pembersih tidak menghapus salinan yang sedang dipakai ----

    @Test
    fun `pembersih tidak menghapus salinan selagi dipakai walau sudah sangat tua dan ruang kerja berisi data`() = runBlocking<Unit> {
        val shared = lock()
        val ready = assertIs<RevertPreparation.Ready>(flow(shared).prepare())
        now = t0 + 365 * day
        // Penanda kunci dibuat saat prepare (t0); jam melompat setahun akan membuatnya basi, jadi perbarui seolah masih segar.
        File(dir, "akun.db" + PreRestoreFiles.LOCK).setLastModified(now)

        assertEquals(Outcome.IN_USE, cleaner(shared).cleanUp())
        assertTrue(copyFile().isFile, "salinan masih ada")

        flow(shared).cancel(ready)
        assertEquals(Outcome.DELETED, cleaner(shared).cleanUp(), "setelah dilepas, aturan biasa berlaku lagi")
    }

    @Test
    fun `pembersih memeriksa ulang kunci tepat sebelum menghapus`() = runBlocking<Unit> {
        now = t0 + 60 * day
        var checks = 0
        val racing = PreRestoreCleaner(
            databaseFile = { db },
            isWorkspacePure = { false },
            // Pengembalian dimulai tepat setelah pemeriksaan pertama (selama membaca kemurnian).
            isInUse = { checks++ >= 1 },
            nowMillis = { now },
            log = { logs += it },
        )

        assertEquals(Outcome.IN_USE, racing.cleanUp())
        assertTrue(copyFile().isFile)
    }

    @Test
    fun `pembersih menghapus sisa pengembalian hanya setelah 30 hari dan hanya bila ruang kerja berisi data`() = runBlocking<Unit> {
        val used = File(dir, "akun.db" + PreRestoreFiles.USED).also { it.writeBytes(prerestore); it.setLastModified(t0) }
        val prerevert = File(dir, "akun.db" + PreRestoreFiles.PREREVERT).also { it.writeBytes(liveDb); it.setLastModified(t0) }
        val premigrate = File(dir, "akun.db.premigrate").also { it.writeBytes(ByteArray(10) { 3 }) }

        now = t0 + 30 * day - 1
        assertEquals(listOf(Outcome.TOO_YOUNG, Outcome.TOO_YOUNG), cleaner().cleanUpRevertCopies())
        assertTrue(used.isFile && prerevert.isFile)

        now = t0 + 90 * day
        assertEquals(listOf(Outcome.KEPT_UNSAFE, Outcome.KEPT_UNSAFE), cleaner(pure = true).cleanUpRevertCopies(), "ruang kerja murni: dipertahankan")
        assertTrue(used.isFile && prerevert.isFile)

        assertEquals(listOf(Outcome.DELETED, Outcome.DELETED), cleaner().cleanUpRevertCopies())
        assertFalse(used.exists() || prerevert.exists())
        assertTrue(premigrate.isFile, ".premigrate tidak pernah disentuh")
        assertContentEquals(liveDb, db.readBytes(), "database aktif tidak pernah disentuh")
    }

    @Test
    fun `pembersih tidak menghapus sisa pengembalian selagi kunci dipegang`() = runBlocking<Unit> {
        val prerevert = File(dir, "akun.db" + PreRestoreFiles.PREREVERT).also { it.writeBytes(liveDb); it.setLastModified(t0) }
        now = t0 + 90 * day
        val shared = lock()
        assertTrue(shared.tryAcquire())

        assertEquals(listOf(Outcome.NO_COPY, Outcome.IN_USE), cleaner(shared).cleanUpRevertCopies())
        assertTrue(prerevert.isFile)
    }

    @Test
    fun `akhiran berkas salinan sebelum pulihkan tidak berubah`() {
        assertEquals(".prerestore", PreRestoreBackup.SUFFIX)
        assertEquals(".prerevert", PreRestoreFiles.PREREVERT)
        assertEquals(".prerestore.used", PreRestoreFiles.USED)
    }
}
