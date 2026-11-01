package com.roziqrizal.rizqflow.backup

import android.content.Context
import com.roziqrizal.rizqflow.domain.auth.AccountStorage
import com.roziqrizal.rizqflow.workspace.AccountWorkspace
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cadangan dan pemulihan berkas database mentah satu akun (F7, S20). Bukan ekspor terstruktur
 * seperti CSV: ini salinan apa adanya berkas SQLite Room, dienkripsi terpisah lewat
 * [com.roziqrizal.rizqflow.domain.backup.BackupCrypto], supaya restore menghasilkan data yang
 * identik persis (diputuskan 2026-09-21, docs/konsep.md).
 */
object BackupFileService {

    /**
     * Isi mentah database akun yang sedang aktif, siap dienkripsi: checkpoint (lihat
     * [LocalLedger.checkpoint]) lalu salin [LocalLedger.databaseFile] apa adanya. Lokasi berkas
     * diambil dari koneksi yang sedang terbuka, bukan dihitung ulang, supaya pemanggilnya tidak
     * perlu tahu pengenal akun.
     */
    suspend fun snapshot(workspace: AccountWorkspace): ByteArray = withContext(Dispatchers.IO) {
        workspace.repositories.checkpoint()
        workspace.repositories.databaseFile.readBytes()
    }

    /**
     * Menimpa berkas database akun ini dengan [decrypted]. Pemanggil wajib menutup
     * [AccountWorkspace] akun ini lebih dulu (berkas terkunci selama koneksinya terbuka) dan
     * membuka ulang setelah ini selesai.
     */
    suspend fun overwrite(context: Context, accountId: String, decrypted: ByteArray) = withContext(Dispatchers.IO) {
        overwriteFile(databaseFile(context, accountId), decrypted)
    }

    /**
     * Penimpaan berkas yang tahan mati di tengah: isi baru ditulis lebih dulu ke berkas sementara di folder yang sama, lalu
     * dipindahkan ke tempatnya. Proses mati/ruang penuh saat menulis meninggalkan database lama UTUH (bukan separuh
     * tertimpa seperti `writeBytes` langsung); sisa berkas sementara dibuang. Berkas `-wal`/`-shm` lama menyimpan perubahan
     * tertunda milik database SEBELUM ditimpa, jadi dibuang SEBELUM berkas baru dipasang: urutan sebaliknya bisa
     * menyisakan WAL lama di atas database baru bila mati di antaranya.
     */
    internal fun overwriteFile(file: File, decrypted: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".restore.tmp")
        try {
            tmp.writeBytes(decrypted)
            File(file.path + "-wal").delete()
            File(file.path + "-shm").delete()
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    private fun databaseFile(context: Context, accountId: String): File =
        context.getDatabasePath(AccountStorage.databaseName(accountId))
}
