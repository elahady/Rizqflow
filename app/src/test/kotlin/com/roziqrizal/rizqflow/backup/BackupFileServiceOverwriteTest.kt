package com.roziqrizal.rizqflow.backup

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Penimpaan berkas database oleh pemulihan (juga dipakai pengembalian `.prerestore`): atomik terhadap gagal menulis,
 * dan berkas `-wal`/`-shm` lama dibuang. Berkas sungguhan di folder sementara.
 */
class BackupFileServiceOverwriteTest {
    private lateinit var dir: File
    private lateinit var db: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ow").toFile()
        db = File(dir, "akun.db").also { it.writeBytes(ByteArray(64) { 1 }) }
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `isi baru menggantikan database dan berkas sementara tidak tersisa`() {
        val fresh = ByteArray(128) { 9 }

        BackupFileService.overwriteFile(db, fresh)

        assertContentEquals(fresh, db.readBytes())
        assertFalse(File(db.path + ".restore.tmp").exists())
    }

    @Test
    fun `berkas wal dan shm milik database lama dibuang`() {
        File(db.path + "-wal").writeBytes(ByteArray(8) { 5 })
        File(db.path + "-shm").writeBytes(ByteArray(8) { 5 })

        BackupFileService.overwriteFile(db, ByteArray(16) { 2 })

        assertFalse(File(db.path + "-wal").exists())
        assertFalse(File(db.path + "-shm").exists())
    }

    @Test
    fun `gagal menulis meninggalkan database lama utuh, tidak separuh tertimpa`() {
        val before = db.readBytes()
        // Berkas sementara tidak bisa ditulis: ada DIREKTORI dengan nama yang sama.
        assertTrue(File(db.path + ".restore.tmp").mkdir())

        assertFailsWith<java.io.IOException> { BackupFileService.overwriteFile(db, ByteArray(128) { 9 }) }

        assertContentEquals(before, db.readBytes())
    }

    @Test
    fun `folder tujuan yang belum ada dibuat`() {
        val nested = File(dir, "baru/akun.db")

        BackupFileService.overwriteFile(nested, ByteArray(4) { 3 })

        assertContentEquals(ByteArray(4) { 3 }, nested.readBytes())
    }
}
