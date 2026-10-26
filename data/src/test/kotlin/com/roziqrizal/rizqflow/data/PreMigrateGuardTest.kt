package com.roziqrizal.rizqflow.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pagar migrasi (Tahap 1 sync-desain.md): salinan `.premigrate` sebelum membuka database dengan user_version di
 * bawah [PreMigrateGuard.DEFAULT_TARGET_VERSION] (versi skema `RizqflowDatabase` saat ini, 9 sejak Tahap 9 roadmap.md
 * "Sinkron penghapusan transaksi di ruang yang dibagi"), dihapus setelah 3 pembukaan sukses DAN umur 7 hari, dan
 * kegagalan menyalin tidak pernah menggagalkan pembukaan.
 * Catatan: nama tes sengaja pendek; dir sementara Robolectric memuat nama tes dan path Windows dibatasi 260 karakter.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreMigrateGuardTest {
    private lateinit var context: Context
    private val accountId = "pagar-migrasi@example.test"
    private lateinit var dbFile: File
    private val opened = mutableListOf<LocalLedger>()
    private var now = 1_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath(AccountStorage.databaseName(accountId))
        cleanup()
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        cleanup()
    }

    private fun cleanup() {
        dbFile.parentFile?.listFiles()?.filter { it.name.startsWith(dbFile.name) }?.forEach { it.delete() }
    }

    private fun premigrate(suffix: String = "") = File(dbFile.path + ".premigrate" + suffix)

    private fun stateFile() = File(dbFile.path + ".premigrate.opens")

    /** Berkas v7 sungguhan (dari 7.json) berisi satu ruang, supaya ada isi yang bisa dibandingkan. */
    private fun makeV7() {
        dbFile.parentFile?.mkdirs()
        val raw = SchemaTestSupport.buildFromSchema(context, 7, dbFile.path)
        raw.execSQL("INSERT INTO room VALUES ('r1','Keluarga','MENCUKUPI','home',1,0,0,NULL)")
        raw.close()
    }

    private fun openLedger(): LocalLedger = LocalLedger.open(context, accountId).also {
        // Room membuka database secara lazy; akses pertama memicu pagar, migrasi, dan onOpen.
        it.db.openHelper.writableDatabase
        opened += it
    }

    private fun closeLedgers() {
        opened.forEach { it.close() }
        opened.clear()
    }

    private fun guard(
        version: Int? = 7,
        copy: (File, File) -> Unit = PreMigrateGuard.Companion::copyReplacing,
        move: (File, File) -> Unit = PreMigrateGuard.Companion::moveReplacing,
        logs: MutableList<String> = mutableListOf(),
    ) = PreMigrateGuard(dbFile, readUserVersion = { version }, copyFile = copy, moveFile = move, nowMillis = { now }, log = logs::add)

    // ---- jalur produksi (LocalLedger.open)

    @Test
    fun `v7 disalin ke premigrate dan salinan tetap v7`() {
        makeV7()
        val original = dbFile.readBytes()

        openLedger()

        assertTrue(premigrate().exists(), "salinan harus ada")
        assertContentEquals(original, premigrate().readBytes(), "salinan identik dengan berkas sebelum migrasi")
        val copy = android.database.sqlite.SQLiteDatabase.openDatabase(premigrate().path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
        copy.use { assertEquals(7, it.version) }
        assertEquals(9, opened.single().db.openHelper.readableDatabase.version)
    }

    @Test
    fun `open tidak menyalin sampai akses pertama`() {
        makeV7()

        val ledger = LocalLedger.open(context, accountId).also { opened += it }
        assertFalse(premigrate().exists(), "open tidak boleh melakukan kerja berkas berat di thread pemanggil (komposisi)")

        ledger.db.openHelper.writableDatabase
        assertTrue(premigrate().exists())
    }

    @Test
    fun `salinan bertahan sebelum tujuh hari`() {
        makeV7()
        openLedger() // membuka dan memigrasi: tidak dihitung
        closeLedgers()
        assertEquals("0", stateFile().readLines().first())

        repeat(3) { openLedger(); closeLedgers() }

        assertTrue(premigrate().exists(), "pembukaan oleh worker/widget tidak boleh mempercepat penghapusan")
        assertEquals("3", stateFile().readLines().first())
    }

    @Test
    fun `database baru atau sudah versi sasaran tidak disalin`() {
        openLedger() // berkas belum ada: dibuat langsung di versi skema saat ini (9), bukan dimigrasi
        closeLedgers()
        assertFalse(premigrate().exists())
        openLedger()
        closeLedgers()
        assertFalse(premigrate().exists(), "sudah di versi sasaran, tidak butuh salinan")
    }

    @Test
    fun `gagal menyalin tidak menggagalkan pembukaan`() {
        makeV7()
        val logs = mutableListOf<String>()
        guard(copy = { _, _ -> throw IOException("gagal") }, logs = logs).beforeOpen()
        assertEquals(1, logs.size)

        // Pembukaan sungguhan tetap berhasil dan memigrasi (jalur LocalLedger.open memakai pagar bawaan).
        val ledger = openLedger()
        assertEquals(9, ledger.db.openHelper.readableDatabase.version)
        ledger.db.openHelper.readableDatabase.query("SELECT name FROM room WHERE id = 'r1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Keluarga", it.getString(0))
        }
    }

    @Test
    fun `versi sasaran pagar sama dengan versi skema database`() {
        assertEquals(PreMigrateGuard.DEFAULT_TARGET_VERSION, openLedger().db.openHelper.readableDatabase.version)
    }

    // ---- penyalinan

    @Test
    fun `salinan lama diganti salinan baru, hanya satu salinan`() {
        makeV7()
        File(premigrate().path).writeBytes(byteArrayOf(1, 2, 3)) // sisa lama
        File(premigrate("-wal").path).writeBytes(byteArrayOf(9))
        val original = dbFile.readBytes()

        guard().beforeOpen()

        assertContentEquals(original, premigrate().readBytes())
        assertFalse(premigrate("-wal").exists(), "pendamping lama yang tidak ada di sumber dibuang")
    }

    @Test
    fun `berkas wal dan shm ikut disalin bila ada`() {
        makeV7()
        File(dbFile.path + "-wal").writeBytes(ByteArray(100) { 7 })
        File(dbFile.path + "-shm").writeBytes(ByteArray(50) { 5 })
        val g = guard()

        g.beforeOpen()

        assertContentEquals(ByteArray(100) { 7 }, premigrate("-wal").readBytes())
        assertContentEquals(ByteArray(50) { 5 }, premigrate("-shm").readBytes())
        assertTrue(premigrate().exists())
    }

    @Test
    fun `gagal menyalin tidak melempar dan log tanpa data`() {
        makeV7()
        premigrate().writeBytes(byteArrayOf(4, 4, 4)) // salinan lama yang baik
        val logs = mutableListOf<String>()
        val g = guard(copy = { _, _ -> throw IOException("disk penuh ${dbFile.path} rahasia-keuangan") }, logs = logs)

        g.beforeOpen() // tidak boleh melempar

        assertContentEquals(byteArrayOf(4, 4, 4), premigrate().readBytes(), "salinan lama tidak tersentuh")
        assertFalse(File(premigrate().path + ".tmp").exists())
        assertEquals(1, logs.size)
        assertTrue("IOException" in logs.single())
        assertFalse("rahasia-keuangan" in logs.single() || dbFile.path in logs.single(), "log tanpa data atau path: ${logs.single()}")
    }

    @Test
    fun `gagal menyalin pendamping membatalkan salinan`() {
        makeV7()
        File(dbFile.path + "-wal").writeBytes(ByteArray(10))
        var calls = 0
        val g = guard(copy = { source, target -> if (++calls == 2) throw IOException("gagal di tengah") else source.copyTo(target, overwrite = true) })

        g.beforeOpen()

        assertFalse(premigrate().exists(), "tidak boleh ada salinan utama tanpa pendampingnya")
        assertTrue(dbFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") }, "berkas sementara dibersihkan")
    }

    @Test
    fun `pindah parsial bersihkan ketiganya`() {
        makeV7()
        File(dbFile.path + "-wal").writeBytes(ByteArray(10) { 1 })
        File(dbFile.path + "-shm").writeBytes(ByteArray(10) { 2 })
        premigrate().writeBytes(byteArrayOf(4, 4, 4)) // salinan lama
        premigrate("-wal").writeBytes(byteArrayOf(5))
        var moves = 0
        val logs = mutableListOf<String>()
        val g = guard(
            move = { source, target -> if (++moves == 2) throw IOException("gagal memindah") else PreMigrateGuard.moveReplacing(source, target) },
            logs = logs,
        )

        g.beforeOpen() // tidak boleh melempar

        assertEquals(2, moves, "pemindahan kedua yang gagal")
        assertFalse(premigrate().exists(), "utama baru yang sudah terpindah ikut dibersihkan")
        assertFalse(premigrate("-wal").exists())
        assertFalse(premigrate("-shm").exists())
        assertFalse(stateFile().exists())
        assertTrue(dbFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
        assertTrue(logs.any { "IOException" in it })
    }

    // ---- versi tidak terbaca

    @Test
    fun `versi tak terbaca tanpa salinan tetap disalin`() {
        makeV7()
        guard(version = null).beforeOpen()
        assertTrue(premigrate().exists())
    }

    @Test
    fun `versi tak terbaca jaga salinan utuh`() {
        makeV7()
        val g = guard()
        g.beforeOpen()
        g.onOpenSucceeded() // pembukaan yang memigrasi, tidak dihitung
        val good = premigrate().readBytes()
        val other = guard()
        other.onOpenSucceeded()
        other.onOpenSucceeded()
        assertEquals("2", stateFile().readLines().first())
        val createdAt = stateFile().readLines()[1]

        // Database sekarang tidak terbaca (mis. korup); isinya berbeda dari salinan yang baik.
        dbFile.writeBytes(ByteArray(300) { (it * 7).toByte() })
        val logs = mutableListOf<String>()
        guard(version = null, logs = logs).beforeOpen()

        assertContentEquals(good, premigrate().readBytes(), "salinan baik tidak boleh ditimpa database yang tak terbaca")
        assertEquals("2", stateFile().readLines().first(), "hitungan tidak direset")
        assertEquals(createdAt, stateFile().readLines()[1], "waktu pembuatan tidak berubah")
        assertTrue(logs.single().contains("dipertahankan"), logs.toString())
    }

    @Test
    fun `salinan kosong dianggap tidak ada saat versi tidak terbaca`() {
        makeV7()
        premigrate().writeBytes(ByteArray(0))
        guard(version = null).beforeOpen()
        assertTrue(premigrate().length() > 0, "salinan kosong diganti salinan nyata")
    }

    // ---- masa simpan: hitungan DAN umur

    @Test
    fun `hapus bila tiga buka dan umur tujuh hari`() {
        makeV7()
        val g = guard()
        g.beforeOpen()
        g.onOpenSucceeded() // pembukaan yang memigrasi tidak dihitung
        assertEquals("0", stateFile().readLines().first())

        repeat(5) { g.onOpenSucceeded() } // lima pembukaan, tetapi umur 0
        assertTrue(premigrate().exists(), "umur belum cukup walau hitungan sudah lewat")
        assertEquals("5", stateFile().readLines().first(), "hitungan tetap berjalan sambil menunggu umur")

        now += 7 * day - 1
        g.onOpenSucceeded()
        assertTrue(premigrate().exists(), "kurang satu milidetik dari tujuh hari")

        now += 1
        g.onOpenSucceeded()
        assertFalse(premigrate().exists(), "umur tercapai dan hitungan cukup")
        assertFalse(premigrate("-wal").exists())
        assertFalse(premigrate("-shm").exists())
        assertFalse(stateFile().exists())
    }

    @Test
    fun `umur cukup tapi buka belum tiga`() {
        makeV7()
        val g = guard()
        g.beforeOpen()
        g.onOpenSucceeded()
        now += 30 * day
        g.onOpenSucceeded() // 1
        g.onOpenSucceeded() // 2
        assertTrue(premigrate().exists())
        g.onOpenSucceeded() // 3
        assertFalse(premigrate().exists())
    }

    @Test
    fun `pembukaan yang memigrasi tidak dihitung`() {
        makeV7()
        val g = guard()
        g.beforeOpen()
        now += 30 * day
        g.onOpenSucceeded() // pembukaan yang memigrasi
        assertTrue(premigrate().exists())
        assertEquals("0", stateFile().readLines().first())
    }

    // ---- L1: tidak pernah menghapus berkas korup

    @Test
    fun `baca versi korup tidak menghapus berkas`() {
        dbFile.parentFile?.mkdirs()
        val corrupt = ByteArray(4096) { (it * 31 + 7).toByte() }
        dbFile.writeBytes(corrupt)

        val g = PreMigrateGuard(dbFile, nowMillis = { now }, log = {}) // pembaca versi bawaan (read-only)
        g.beforeOpen()

        assertTrue(dbFile.exists(), "pagar tidak boleh menghapus berkas korup (penangan galat bawaan Android menghapusnya)")
        assertContentEquals(corrupt, dbFile.readBytes())
        assertTrue(premigrate().exists(), "tanpa salinan sebelumnya, berkas yang tak terbaca disalin sebagai cadangan")
    }

    // ---- L2: pembukaan bersamaan

    @Test
    fun `dua buka bersamaan tidak tumpang tindih`() {
        makeV7()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val firstStarted = CountDownLatch(1)
        val slowCopy: (File, File) -> Unit = { source, target ->
            val n = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, n) }
            firstStarted.countDown()
            Thread.sleep(150)
            PreMigrateGuard.copyReplacing(source, target)
            active.decrementAndGet()
        }
        val t1 = thread { guard(copy = slowCopy).beforeOpen() }
        firstStarted.await()
        val t2 = thread { guard(copy = slowCopy).beforeOpen() }
        t1.join()
        t2.join()

        assertEquals(1, maxActive.get(), "penyalinan tidak boleh tumpang tindih")
        assertTrue(premigrate().exists())
        assertTrue(dbFile.parentFile!!.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
