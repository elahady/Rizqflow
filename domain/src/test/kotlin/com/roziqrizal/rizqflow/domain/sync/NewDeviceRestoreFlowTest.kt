package com.roziqrizal.rizqflow.domain.sync

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FlowPurity(var pure: Boolean) : WorkspacePurityChecker {
    override suspend fun isPure(): Boolean = pure
}

private class FlowReplacer(private val log: MutableList<String>) : WorkspaceReplacer {
    var wipedAllWith: LocalBackupHandle? = null

    override suspend fun wipeOnboardingAndResetCursor() {
        log += "wipe-onboarding"
    }

    override suspend fun wipeAllAndResetCursor(backup: LocalBackupHandle) {
        log += "wipe-all"
        wipedAllWith = backup
    }
}

/** Alur perangkat baru: otomatis ganti, Gabung, dan Pulihkan dengan salinan otomatis (keputusan pemilik #11). */
class NewDeviceRestoreFlowTest {
    private val log = mutableListOf<String>()
    private val purity = FlowPurity(pure = true)
    private val replacer = FlowReplacer(log)
    private val handle = LocalBackupHandle("/data/rizqflow.db.prerestore", 4096, 1L)
    private var backupResult: LocalBackupHandle? = handle
    private var syncOutcome: PersonalSyncResult = PersonalSyncResult.Success(pushed = 0, applied = 3, skipped = 0, pushGated = true)

    private val advisorUnused = NewDeviceRecoveryAdvisor(
        RecoveryKeyService(
            object : AccountKeyProvider {
                override fun getOrCreateAccountKey(): SyncAead = error("tidak dipakai")
                override fun exportAccountKeyForRecovery(): ByteArray = error("tidak dipakai")
                override fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray) = error("tidak dipakai")
            },
            object : RecoveryKeyRemote {
                override suspend fun put(apiToken: String, wrappedKey: ByteArray) = error("tidak dipakai")
                override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> = error("tidak dipakai")
                override suspend fun delete(apiToken: String) = error("tidak dipakai")
            },
            object : ApiTokenStore {
                override fun read(): String? = null
                override fun expiresAt(): java.time.Instant? = null
                override fun save(token: String, expiresAt: java.time.Instant?) = true
                override fun clear() = Unit
            },
            object : RecoveryStatusStore {
                override fun read(): RecoveryStatus = RecoveryStatus.NotConfigured
                override fun markPending(serverUpdatedAt: String?) = Unit
                override fun markConfigured(at: java.time.Instant, serverUpdatedAt: String?) = Unit
                override fun clear() = Unit
            },
        ),
        purity,
    )

    private val flow = NewDeviceRestoreFlow(
        advisor = advisorUnused,
        purity = purity,
        replacer = replacer,
        backup = { log += "backup"; backupResult },
        sync = { log += "sync"; syncOutcome },
    )

    @Test
    fun `otomatis ganti membuang onboarding lalu menarik data server`() = runBlocking {
        val result = flow.autoReplace()

        assertIs<NewDeviceRestoreResult.Synced>(result)
        assertEquals(listOf("wipe-onboarding", "sync"), log)
    }

    @Test
    fun `otomatis ganti berhenti tanpa menghapus bila ruang kerja sudah tidak murni`() = runBlocking {
        purity.pure = false

        val result = flow.autoReplace()

        assertSame(NewDeviceRestoreResult.NoLongerPure, result)
        assertTrue(log.isEmpty(), "tidak boleh ada hapus atau sync: $log")
    }

    @Test
    fun `gabung tidak menghapus apa pun dan hanya menjalankan sync`() = runBlocking {
        purity.pure = false

        val result = flow.join()

        assertIs<NewDeviceRestoreResult.Synced>(result)
        assertEquals(listOf("sync"), log)
    }

    @Test
    fun `persiapan pulihkan hanya membuat salinan, tidak menghapus`() = runBlocking {
        val prepared = flow.prepareOverwrite()

        assertEquals(OverwritePreparation.Ready(handle), prepared)
        assertEquals(listOf("backup"), log)
    }

    @Test
    fun `salinan gagal dibuat - pulihkan berhenti tanpa menghapus`() = runBlocking {
        backupResult = null

        val prepared = flow.prepareOverwrite()

        assertSame(OverwritePreparation.BackupFailed, prepared)
        assertEquals(listOf("backup"), log)
    }

    @Test
    fun `pulihkan setelah konfirmasi menghapus dengan bukti salinan lalu menarik data server`() = runBlocking {
        val ready = assertIs<OverwritePreparation.Ready>(flow.prepareOverwrite())

        val result = flow.overwrite(ready)

        assertIs<NewDeviceRestoreResult.Synced>(result)
        assertEquals(listOf("backup", "wipe-all", "sync"), log)
        assertSame(handle, replacer.wipedAllWith)
    }

    @Test
    fun `tarik dari server gagal setelah hapus dilaporkan dan bisa diulang`() = runBlocking {
        syncOutcome = PersonalSyncResult.Failed(RemoteError.Unreachable)
        val ready = assertIs<OverwritePreparation.Ready>(flow.prepareOverwrite())

        val failed = flow.overwrite(ready)
        syncOutcome = PersonalSyncResult.Success(pushed = 0, applied = 5, skipped = 0, pushGated = false)
        val retried = flow.retrySync()

        assertIs<NewDeviceRestoreResult.SyncFailed>(failed)
        assertIs<NewDeviceRestoreResult.Synced>(retried)
        assertEquals(listOf("backup", "wipe-all", "sync", "sync"), log)
    }

    @Test
    fun `belum masuk server dilaporkan sebagai gagal tarik, bukan sukses`() = runBlocking {
        syncOutcome = PersonalSyncResult.NotSignedIn

        val result = flow.join()

        assertIs<NewDeviceRestoreResult.SyncFailed>(result)
        assertEquals(listOf("sync"), log)
    }
}
