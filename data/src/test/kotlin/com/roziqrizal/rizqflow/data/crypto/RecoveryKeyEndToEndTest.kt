package com.roziqrizal.rizqflow.data.crypto

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.roziqrizal.rizqflow.data.sync.SharedPrefsRecoveryStatusStore
import com.roziqrizal.rizqflow.domain.sync.ApiTokenStore
import com.roziqrizal.rizqflow.domain.sync.EnableRecoveryResult
import com.roziqrizal.rizqflow.domain.sync.RECOVERY_KEY_VERSION
import com.roziqrizal.rizqflow.domain.sync.RecoverKeyResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyLookup
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyReceipt
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyRemote
import com.roziqrizal.rizqflow.domain.sync.RecoveryKeyService
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.RemoteResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

private class InMemoryRecoveryRemote : RecoveryKeyRemote {
    var stored: RecoveryKeyLookup.Found? = null

    override suspend fun put(apiToken: String, wrappedKey: ByteArray): RemoteResult<RecoveryKeyReceipt> {
        stored = RecoveryKeyLookup.Found(wrappedKey.copyOf(), RECOVERY_KEY_VERSION, "2026-10-25T00:00:00Z")
        return RemoteResult.Success(RecoveryKeyReceipt(RECOVERY_KEY_VERSION, "2026-10-25T00:00:00Z"))
    }

    override suspend fun get(apiToken: String): RemoteResult<RecoveryKeyLookup> =
        RemoteResult.Success(stored ?: RecoveryKeyLookup.NotFound)

    override suspend fun delete(apiToken: String): RemoteResult<Unit> {
        stored = null
        return RemoteResult.Success(Unit)
    }
}

private class StaticToken : ApiTokenStore {
    override fun read() = "token"
    override fun expiresAt(): Instant? = null
    override fun save(token: String, expiresAt: Instant?) = true
    override fun clear() = Unit
}

/**
 * RecoveryKeyService dengan kripto SUNGGUHAN (RecoveryCode, BackupCrypto, Tink) dan penyimpanan nyata, hanya
 * server yang palsu. Dua perangkat = dua `accountId` provider. Keystore asli tidak teruji di Robolectric
 * (Tink jatuh ke cleartext); yang dibuktikan di sini adalah alur dan logikanya.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecoveryKeyEndToEndTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext<Context>()
    private val remote = InMemoryRecoveryRemote()
    private val now = Instant.parse("2026-10-25T10:00:00Z")

    private fun device(name: String): Triple<AndroidKeystoreAccountKeyProvider, SharedPrefsRecoveryStatusStore, RecoveryKeyService> {
        val keys = AndroidKeystoreAccountKeyProvider(context, accountId = name)
        val status = SharedPrefsRecoveryStatusStore(context, name)
        return Triple(keys, status, RecoveryKeyService(keys, remote, StaticToken(), status, clock = { now }))
    }

    @Test
    fun `atur kode di A, pulihkan di B, lalu B dan A saling membaca data`() = runBlocking<Unit> {
        val (keysA, statusA, serviceA) = device("perangkat-a")
        val (keysB, statusB, serviceB) = device("perangkat-b")
        val cipherA = keysA.getOrCreateAccountKey().encrypt("dompet A".toByteArray(), "akun".toByteArray())
        val cipherBOld = keysB.getOrCreateAccountKey().encrypt("kunci lama B".toByteArray())

        val display = assertIs<EnableRecoveryResult.Ready>(serviceA.enableRecovery()).display
        assertEquals(RecoveryStatus.PendingConfirmation("2026-10-25T00:00:00Z"), statusA.read())
        val challenge = display.newChallenge(Random(7))
        assertEquals(true, serviceA.confirm(challenge, challenge.positions.associateWith { display.words[it - 1] }))
        assertEquals(RecoveryStatus.Configured(now, "2026-10-25T00:00:00Z"), statusA.read())

        assertEquals(RecoverKeyResult.Recovered, serviceB.recoverKey(display.words))

        assertEquals(RecoveryStatus.Configured(now, "2026-10-25T00:00:00Z"), statusB.read())
        assertContentEquals("dompet A".toByteArray(), keysB.getOrCreateAccountKey().decrypt(cipherA, "akun".toByteArray()))
        val cipherB = keysB.getOrCreateAccountKey().encrypt("balasan B".toByteArray())
        assertContentEquals("balasan B".toByteArray(), keysA.getOrCreateAccountKey().decrypt(cipherB))
        assertFailsWith<Exception>("kunci lama B tidak lagi aktif setelah kebijakan menimpa") {
            keysB.getOrCreateAccountKey().decrypt(cipherBOld)
        }
    }

    @Test
    fun `kode salah di perangkat B tidak mengubah kunci B sedikit pun`() = runBlocking<Unit> {
        val (_, _, serviceA) = device("perangkat-a")
        val (keysB, statusB, serviceB) = device("perangkat-b")
        val cipherBOld = keysB.getOrCreateAccountKey().encrypt("kunci lama B".toByteArray())
        assertIs<EnableRecoveryResult.Ready>(serviceA.enableRecovery())
        val otherCode = com.roziqrizal.rizqflow.domain.sync.RecoveryCode.generate().words

        assertEquals(RecoverKeyResult.WrongCode, serviceB.recoverKey(otherCode))

        assertContentEquals("kunci lama B".toByteArray(), keysB.getOrCreateAccountKey().decrypt(cipherBOld))
        assertEquals(RecoveryStatus.NotConfigured, statusB.read())
    }
}
