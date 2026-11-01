package com.roziqrizal.rizqflow.data

import com.roziqrizal.rizqflow.domain.sync.PersonalSyncResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryStatus
import com.roziqrizal.rizqflow.domain.sync.SyncEnvelope
import com.roziqrizal.rizqflow.domain.sync.SyncIssueKind
import com.roziqrizal.rizqflow.domain.sync.SyncLogEntryDownload
import com.roziqrizal.rizqflow.domain.sync.SyncScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `record_type` konstan `"r"` untuk cakupan pribadi (keputusan pemilik #7, sync-desain.md bagian C): baris baru tidak
 * lagi membocorkan jenis rekaman ke server, `record_id` tetap hash, dan baris lama yang sudah terkirim dengan nama tipe
 * di kolom luar tetap terbaca (tipe sebenarnya selalu diambil dari dalam amplop terenkripsi). Hanya terbukti di JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PersonalSyncRecordTypeTest {
    private lateinit var a: SyncFixture
    private val configured = RecoveryStatus.Configured(Instant.now())

    @Before
    fun setUp() {
        a = SyncFixture()
    }

    @After
    fun tearDown() {
        a.close()
    }

    /** Server palsu yang berisi baris persis seperti yang dikirim klien LAMA: kolom luar `record_type` = nama tipe. */
    private fun legacyCopy(source: MemoryPersonalRemote): MemoryPersonalRemote = MemoryPersonalRemote().also { legacy ->
        legacy.stored += source.stored.map { it.copy(recordType = it.innerType()) }
    }

    @Test
    fun `semua baris baru memakai record_type r dan record_id tetap hash dari tipe dan id`() = runBlocking {
        val expenseId = a.expense(20_000)
        a.income(1_000_000)
        val remote = MemoryPersonalRemote()

        val result = engineFor(a.local, remote, configured).syncNow("device-a")

        assertTrue(assertIs<PersonalSyncResult.Success>(result).pushed > 3)
        assertTrue(remote.stored.all { it.recordType == "r" }, "kolom luar konstan, jenis rekaman tidak terbaca server")
        assertTrue(remote.stored.map { it.innerType() }.toSet().containsAll(setOf("room", "account", "category", "transaction")))
        for (row in remote.stored) {
            assertEquals(SyncEnvelope.WIRE_ID_LENGTH, row.recordId.length, "record_id adalah hash 22 karakter")
            assertTrue(row.recordId != expenseId.value, "id lokal tidak boleh tampak di server")
        }
        // Hash terikat ke (tipe, id): baris transaksi memang wireId("transaction", id) dan tidak bisa ditukar tipe.
        assertTrue(remote.stored.any { it.recordId == SyncEnvelope.wireId("transaction", expenseId.value) })
    }

    @Test
    fun `baris lama dengan nama tipe di kolom luar tetap terbaca di perangkat baru`() = runBlocking {
        val expenseId = a.expense(20_000)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        val legacy = legacyCopy(remote)
        assertTrue(legacy.stored.none { it.recordType == "r" } && legacy.stored.any { it.recordType == "transaction" })

        val b = emptyLedger()
        val result = engineFor(b, legacy).syncNow("device-b")

        val success = assertIs<PersonalSyncResult.Success>(result)
        assertTrue(success.applied > 0)
        assertEquals(0, success.skipped)
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty())
        assertEquals(20_000L, b.transactions.find(expenseId)?.amount?.minor)
        assertEquals(a.local.rooms.activeRooms().map { it.id }.toSet(), b.rooms.activeRooms().map { it.id }.toSet())
    }

    @Test
    fun `server campuran baris lama dan baru dibaca sama persis`() = runBlocking {
        val expenseId = a.expense(20_000)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        // Separuh baris dikembalikan ke bentuk lama (klien lama), separuh tetap "r".
        val mixed = MemoryPersonalRemote().also { server ->
            server.stored += remote.stored.mapIndexed { index, row -> if (index % 2 == 0) row.copy(recordType = row.innerType()) else row }
        }

        val b = emptyLedger()
        val result = engineFor(b, mixed).syncNow("device-b")

        assertEquals(0, assertIs<PersonalSyncResult.Success>(result).skipped)
        assertEquals(20_000L, b.transactions.find(expenseId)?.amount?.minor)
        assertTrue(b.syncStore.issues(SyncScope.Personal).isEmpty())
    }

    @Test
    fun `suntingan atas rekaman yang dulu terkirim dengan nama tipe menang dan sampai ke perangkat lama`() = runBlocking {
        val expenseId = a.expense(20_000)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        val legacy = legacyCopy(remote)

        // Perangkat B (klien baru) menarik riwayat lama, menyunting transaksi itu, lalu mengirim dengan "r".
        val b = emptyLedger()
        val engineB = engineFor(b, legacy, configured)
        engineB.syncNow("device-b")
        val pulled = assertNotNull(b.transactions.find(expenseId))
        b.transactions.update(pulled.copy(note = "diedit di B", updatedAtMillis = pulled.updatedAtMillis + 5_000))
        engineB.syncNow("device-b")
        val edited = legacy.stored.filter { it.deviceId == "device-b" }
        assertTrue(edited.isNotEmpty() && edited.all { it.recordType == "r" })
        assertTrue(edited.any { it.recordId == SyncEnvelope.wireId("transaction", expenseId.value) }, "record_id sama dengan rantai versi lama")

        // Perangkat A menarik hasilnya: rantai tidak putus, suntingan B diterapkan.
        engineFor(a.local, legacy, configured).syncNow("device-a")

        assertEquals("diedit di B", a.local.transactions.find(expenseId)?.note)
    }

    @Test
    fun `tipe masa depan di dalam amplop r dicatat UNSUPPORTED_VERSION, nama tipe lama yang asing dilewati diam-diam`() = runBlocking {
        val remote = MemoryPersonalRemote()
        val aead = PersonalIdentityAead()
        fun future(serverId: Long, recordType: String): SyncLogEntryDownload {
            val sealed = SyncEnvelope.seal(aead, SyncScope.Personal, "jenis_masa_depan", "x-$serverId", 1, buildJsonObject { put("a", 1) }, 3_000L, false, "device-z")
            return SyncLogEntryDownload(
                serverId, null, recordType, sealed.wireId, "device-z", Base64.getEncoder().encodeToString(sealed.ciphertext),
                false, Instant.ofEpochMilli(3_000L).toString(), 3_000L, "device-z",
            )
        }
        remote.stored += future(1, "r")
        remote.stored += future(2, "jenis_masa_depan") // nama tipe lama yang tidak dikenal: tak pernah dibuka, dilewati seperti dulu

        val b = emptyLedger()
        val result = engineFor(b, remote).syncNow("device-b")

        assertEquals(1, assertIs<PersonalSyncResult.Success>(result).skipped)
        val issue = b.syncStore.issues(SyncScope.Personal).single()
        assertEquals(SyncIssueKind.UNSUPPORTED_VERSION, issue.kind)
        assertEquals(1L, issue.serverId)
    }

    @Test
    fun `payload bukan base64 dicatat dan tidak mematikan sync`() = runBlocking {
        a.expense(20_000)
        val remote = MemoryPersonalRemote()
        engineFor(a.local, remote, configured).syncNow("device-a")
        val index = remote.stored.indexOfFirst { it.innerType() == "transaction" }
        remote.stored[index] = remote.stored[index].copy(payload = "###bukan-base64###")

        val b = emptyLedger()
        val result = engineFor(b, remote).syncNow("device-b")

        val success = assertIs<PersonalSyncResult.Success>(result)
        assertEquals(1, success.skipped)
        assertTrue(b.syncStore.issues(SyncScope.Personal).any { it.kind == SyncIssueKind.TAMPERED_OR_CORRUPT })
        assertTrue(success.applied > 0, "baris lain tetap diterapkan")
    }
}
