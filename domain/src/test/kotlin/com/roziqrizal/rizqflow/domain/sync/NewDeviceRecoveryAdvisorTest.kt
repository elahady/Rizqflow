package com.roziqrizal.rizqflow.domain.sync

import kotlinx.coroutines.runBlocking
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

private class AdvisorFakeAccountKeys : AccountKeyProvider {
    override fun getOrCreateAccountKey(): SyncAead = error("tidak dipakai di tes ini")
    override fun exportAccountKeyForRecovery(): ByteArray = error("tidak dipakai di tes ini")
    override fun importAccountKeyFromRecovery(exportedAccountKey: ByteArray) = error("tidak dipakai di tes ini")
}

private class AdvisorFakeStatusStore : RecoveryStatusStore {
    private var current: RecoveryStatus = RecoveryStatus.NotConfigured
    override fun read() = current
    override fun markPending(serverUpdatedAt: String?) { current = RecoveryStatus.PendingConfirmation(serverUpdatedAt) }
    override fun markConfigured(at: Instant, serverUpdatedAt: String?) { current = RecoveryStatus.Configured(at, serverUpdatedAt) }
    override fun clear() { current = RecoveryStatus.NotConfigured }
}

private class AdvisorFakeToken(private val token: String? = "token") : ApiTokenStore {
    override fun read() = token
    override fun expiresAt(): Instant? = null
    override fun save(token: String, expiresAt: Instant?) = true
    override fun clear() = Unit
}

/** [found] null = server tidak punya salinan (404/NotFound); bukan null = ada salinan. */
private class AdvisorFakeRecoveryRemote(private val found: RecoveryKeyLookup.Found? = null) : RecoveryKeyRemote {
    override suspend fun put(apiToken: String, wrappedKey: ByteArray) = error("tidak dipakai di tes ini")
    override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> =
        RemoteResult.Success(found ?: RecoveryKeyLookup.NotFound)
    override suspend fun delete(apiToken: String) = error("tidak dipakai di tes ini")
}

private class UnreachableRecoveryRemote : RecoveryKeyRemote {
    override suspend fun put(apiToken: String, wrappedKey: ByteArray) = error("tidak dipakai di tes ini")
    override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> = RemoteResult.Failure(RemoteError.Unreachable)
    override suspend fun delete(apiToken: String) = error("tidak dipakai di tes ini")
}

private class AdvisorFakePurity(private val pure: Boolean) : WorkspacePurityChecker {
    override suspend fun isPure(): Boolean = pure
}

/** Keputusan alur pulihkan perangkat baru (Tahap 3 sync-desain.md bagian B, keputusan pemilik #11). */
class NewDeviceRecoveryAdvisorTest {
    private fun advisor(found: RecoveryKeyLookup.Found?, pure: Boolean, remote: RecoveryKeyRemote = AdvisorFakeRecoveryRemote(found)) =
        NewDeviceRecoveryAdvisor(
            RecoveryKeyService(AdvisorFakeAccountKeys(), remote, AdvisorFakeToken(), AdvisorFakeStatusStore()),
            AdvisorFakePurity(pure),
        )

    private val anyFound = RecoveryKeyLookup.Found(ByteArray(4), RECOVERY_KEY_VERSION, "2026-10-26T00:00:00Z")

    @Test
    fun `murni dan server punya salinan - otomatis ganti`() = runBlocking {
        assertEquals(NewDeviceRecoveryOutcome.AutoReplace, advisor(anyFound, pure = true).decide())
    }

    @Test
    fun `tidak murni dan server punya salinan - tanya pengguna`() = runBlocking {
        assertEquals(NewDeviceRecoveryOutcome.AskUser, advisor(anyFound, pure = false).decide())
    }

    @Test
    fun `murni tapi server tidak punya salinan - tidak ada yang dipulihkan, walau murni`() = runBlocking {
        assertEquals(NewDeviceRecoveryOutcome.NothingToRecover, advisor(found = null, pure = true).decide())
    }

    @Test
    fun `tidak murni dan server tidak punya salinan - tidak ada yang dipulihkan`() = runBlocking {
        assertEquals(NewDeviceRecoveryOutcome.NothingToRecover, advisor(found = null, pure = false).decide())
    }

    @Test
    fun `server tidak bisa dihubungi - tidak ada yang dipulihkan, bukan otomatis ganti`() = runBlocking {
        assertEquals(
            NewDeviceRecoveryOutcome.NothingToRecover,
            advisor(found = null, pure = true, remote = UnreachableRecoveryRemote()).decide(),
            "galat jaringan harus konservatif (jangan pernah AutoReplace tanpa benar-benar memastikan ada salinan)",
        )
    }

    @Test
    fun `belum masuk - tidak ada yang dipulihkan`() = runBlocking {
        val advisor = NewDeviceRecoveryAdvisor(
            RecoveryKeyService(AdvisorFakeAccountKeys(), AdvisorFakeRecoveryRemote(anyFound), AdvisorFakeToken(token = null), AdvisorFakeStatusStore()),
            AdvisorFakePurity(pure = true),
        )
        assertEquals(NewDeviceRecoveryOutcome.NothingToRecover, advisor.decide())
    }
}
