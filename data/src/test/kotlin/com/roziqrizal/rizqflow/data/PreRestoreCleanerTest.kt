package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.data.sync.PreRestoreCleaner
import com.roziqrizal.rizqflow.data.sync.PreRestoreCleaner.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pembersihan salinan `.prerestore` ([PreRestoreCleaner]), mengikuti pola `PreMigrateGuardTest`: umur minimal dan syarat
 * aman (ruang kerja kini berisi data), tidak pernah menyentuh database aktif atau salinan `.premigrate`, dan galat apa
 * pun berarti salinan dipertahankan. Berkas sungguhan di folder sementara; waktu dan kemurnian disuntikkan.
 */
class PreRestoreCleanerTest {
    private lateinit var dir: File
    private lateinit var db: File
    private lateinit var copy: File
    private val day = 24L * 60 * 60 * 1000
    private val copiedAt = 1_800_000_000_000L
    private var now = copiedAt
    private var pure = false
    private var pureFails = false
    private val logs = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("pr").toFile()
        db = File(dir, "akun.db").also { it.writeBytes(ByteArray(64) { b -> b.toByte() }) }
        copy = File(dir, "akun.db.prerestore").also {
            it.writeBytes(ByteArray(32) { 7 })
            it.setLastModified(copiedAt)
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun cleaner(minAge: Long = PreRestoreCleaner.DEFAULT_MIN_AGE_MILLIS) = PreRestoreCleaner(
        databaseFile = { db },
        isWorkspacePure = { if (pureFails) error("gagal membaca") else pure },
        minAgeMillis = minAge,
        nowMillis = { now },
        log = { logs += it },
    )

    private fun cleanUp(minAge: Long = PreRestoreCleaner.DEFAULT_MIN_AGE_MILLIS) = runBlocking { cleaner(minAge).cleanUp() }

    @Test
    fun `umur bawaan 30 hari`() {
        assertEquals(30L * day, PreRestoreCleaner.DEFAULT_MIN_AGE_MILLIS)
    }

    @Test
    fun `tanpa salinan tidak ada yang terjadi`() {
        copy.delete()

        assertEquals(Outcome.NO_COPY, cleanUp())
        assertTrue(db.isFile)
    }

    @Test
    fun `salinan yang belum cukup umur dipertahankan`() {
        now = copiedAt + 30 * day - 1

        assertEquals(Outcome.TOO_YOUNG, cleanUp())
        assertTrue(copy.isFile)
    }

    @Test
    fun `tepat cukup umur dan ruang kerja berisi data menghapus salinan`() {
        now = copiedAt + 30 * day

        assertEquals(Outcome.DELETED, cleanUp())
        assertFalse(copy.exists())
    }

    @Test
    fun `ruang kerja yang masih murni mempertahankan salinan berapa pun umurnya`() {
        pure = true
        now = copiedAt + 3650 * day

        assertEquals(Outcome.KEPT_UNSAFE, cleanUp())
        assertTrue(copy.isFile, "salinan bisa jadi satu-satunya tempat data lama")
    }

    @Test
    fun `database aktif yang hilang atau kosong mempertahankan salinan`() {
        now = copiedAt + 60 * day
        db.writeBytes(ByteArray(0))
        assertEquals(Outcome.KEPT_UNSAFE, cleanUp())
        assertTrue(copy.isFile)

        db.delete()
        assertEquals(Outcome.KEPT_UNSAFE, cleanUp())
        assertTrue(copy.isFile)
    }

    @Test
    fun `jam perangkat yang mundur dianggap belum cukup umur`() {
        now = copiedAt - 100 * day

        assertEquals(Outcome.TOO_YOUNG, cleanUp())
        assertTrue(copy.isFile)
    }

    @Test
    fun `galat membaca kemurnian mempertahankan salinan dan tidak melempar`() {
        now = copiedAt + 60 * day
        pureFails = true

        assertEquals(Outcome.KEPT_UNSAFE, cleanUp())
        assertTrue(copy.isFile)
        assertTrue(logs.single().contains("IllegalStateException"), "hanya nama kelas galat yang dicatat, tanpa isi data")
    }

    @Test
    fun `hanya berkas prerestore yang dihapus, database dan premigrate tidak disentuh`() {
        now = copiedAt + 60 * day
        val premigrate = File(dir, "akun.db.premigrate").also { it.writeBytes(ByteArray(10) { 3 }) }
        val before = db.readBytes()

        assertEquals(Outcome.DELETED, cleanUp())

        assertContentEquals(before, db.readBytes())
        assertTrue(premigrate.isFile)
    }

    @Test
    fun `salinan yang dibuat PreRestoreBackup memakai akhiran yang sama dengan pembersih`() {
        assertEquals(".prerestore", com.roziqrizal.rizqflow.data.sync.PreRestoreBackup.SUFFIX)
    }
}
