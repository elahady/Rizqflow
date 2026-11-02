package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.auth.runSuspend
import com.roziqrizal.rizqflow.domain.ledger.Account
import com.roziqrizal.rizqflow.domain.ledger.Category
import com.roziqrizal.rizqflow.domain.ledger.InMemoryLedger
import com.roziqrizal.rizqflow.domain.ledger.MoneyTransaction
import com.roziqrizal.rizqflow.domain.ledger.Room
import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.AccountKind
import com.roziqrizal.rizqflow.domain.model.CategoryId
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.model.RoomKind
import com.roziqrizal.rizqflow.domain.model.TransactionId
import com.roziqrizal.rizqflow.domain.model.TransactionKind
import com.roziqrizal.rizqflow.domain.model.TransactionOrigin
import com.roziqrizal.rizqflow.domain.money.Money
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/*
 * key_grant ujung ke ujung di JVM (sync-desain.md bagian F langkah 4-5): pemilik merotasi kunci ruang dan menerbitkan
 * grant per anggota yang tetap; anggota itu mengimpor kunci baru lewat grant dan membaca data baru; anggota yang
 * dikeluarkan tidak mendapat grant dan tidak bisa membaca. Mesin SyncCoordinator sungguhan, AES-GCM JDK sungguhan, server
 * palsu di memori. Hanya terbukti di JVM: belum di perangkat, Tink/Keystore asli, atau server hidup.
 */

/** Keyset ala Tink: kumpulan kunci berid dengan satu primary; ciphertext diawali id kunci. Impor MENGGABUNG seperti RoomKeysetMerger. */
private class KeysetRoomKeys(firstKeyId: Int? = null, firstKey: ByteArray? = null) : RoomKeyProvider {
    val keys = linkedMapOf<Int, ByteArray>()
    var primary = 0

    init {
        if (firstKeyId != null && firstKey != null) {
            keys[firstKeyId] = firstKey
            primary = firstKeyId
        }
    }

    private val aead = object : SyncAead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            primary.toString().padStart(4, '0').toByteArray() + JdkAesGcmAead(keys.getValue(primary)).encrypt(plaintext, associatedData)

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            val id = String(ciphertext.copyOfRange(0, 4)).toIntOrNull() ?: throw GeneralSecurityException("id kunci rusak")
            val key = keys[id] ?: throw GeneralSecurityException("kunci $id tidak ada di keyset ini")
            return JdkAesGcmAead(key).decrypt(ciphertext.copyOfRange(4, ciphertext.size), associatedData)
        }
    }

    override fun getOrCreateRoomKey(roomId: RoomId): SyncAead = aead

    override fun exportRoomKey(roomId: RoomId): ByteArray =
        "$primary|${keys.entries.joinToString(",") { "${it.key}:${it.value.joinToString("") { b -> "%02x".format(b) }}" }}".toByteArray()

    override fun importRoomKey(roomId: RoomId, exportedRoomKey: ByteArray) {
        val (primaryText, list) = String(exportedRoomKey).split("|", limit = 2)
        for (entry in list.split(",")) {
            val (id, hex) = entry.split(":")
            keys.getOrPut(id.toInt()) { hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }
        }
        primary = primaryText.toInt()
    }

    override fun rotateRoomKey(roomId: RoomId): SyncAead {
        val newId = (keys.keys.maxOrNull() ?: 0) + 1
        keys[newId] = ByteArray(32).also(SecureRandom()::nextBytes)
        primary = newId
        return aead
    }
}

private class TestToken : ApiTokenStore {
    override fun read() = "token-1"
    override fun expiresAt(): Instant? = null
    override fun save(token: String, expiresAt: Instant?) = true
    override fun clear() = Unit
}

private class TestCursors : SyncCursorStore {
    val map = mutableMapOf<String, Long>()
    override fun read(roomId: String) = map[roomId] ?: 0L
    override fun save(roomId: String, cursor: Long) { map[roomId] = cursor }
}

private class NoLegacyCodec : TransactionSyncCodec {
    override fun encode(transaction: MoneyTransaction, accountName: String, categoryName: String?): ByteArray = error("tidak dipakai")
    override fun decode(bytes: ByteArray): DecodedSyncTransaction = error("tidak dipakai")
}

/** Hanya rahasia SENDIRI yang dipakai penerima; sisi pemilik tidak lewat store ini di tes. */
private class OwnSecretOnly(private val own: ByteArray?) : MemberSecretStore {
    override fun saveOwnSecret(roomId: RoomId, memberSecret: ByteArray) = error("tidak dipakai")
    override fun readOwnSecret(roomId: RoomId): ByteArray? = own
    override fun recordIssuedSecret(roomId: RoomId, memberSecret: ByteArray): String = error("tidak dipakai")
    override fun bindIssuedSecretToMember(roomId: RoomId, issuedSecretId: String, memberId: String) = error("tidak dipakai")
    override fun issuedSecretFor(roomId: RoomId, memberId: String): ByteArray? = error("tidak dipakai")
    override fun issuedSecrets(roomId: RoomId): List<IssuedMemberSecret> = error("tidak dipakai")
}

class KeyGrantSyncTest {
    private val room = RoomId("keluarga")
    private val accountId = AccountId("acc-1")
    private val categoryId = CategoryId("cat-keluarga")
    private val keyOne = ByteArray(32) { it.toByte() }
    private val secretStays = ByteArray(MEMBER_SECRET_LENGTH) { (it + 10).toByte() }
    private val secretRemoved = ByteArray(MEMBER_SECRET_LENGTH) { (it + 50).toByte() }

    private inner class Device(val deviceId: String, val server: FakeSyncServer, val keys: KeysetRoomKeys, ownSecret: ByteArray?) {
        val ledger = InMemoryLedger(nowMillis = { 9_000L })
        val issues = IssueBookSyncStore()
        val engine = SyncCoordinator(
            ledger, ledger, ledger, keys, server, NoLegacyCodec(), TestToken(), TestCursors(),
            SyncClock(deviceNowMillis = { 0L }), issues,
            memberSecrets = OwnSecretOnly(ownSecret),
        )

        init {
            ledger.accountRows[accountId] = Account(accountId, "Dompet", AccountKind.CASH, Money.zero())
            ledger.roomRows[room] = Room(room, "Keluarga", RoomKind.MENCUKUPI, "home", 1, sortOrder = 0)
            ledger.categoryRows[categoryId] = Category(categoryId, room, "Belanja")
            ledger.sharedRoomIds += room
        }

        suspend fun record(id: String) {
            ledger.save(
                MoneyTransaction(
                    id = TransactionId(id), kind = TransactionKind.EXPENSE, amount = Money(25_000), accountId = accountId, roomId = room,
                    categoryId = categoryId, occurredOn = LocalDate.of(2026, 11, 3), note = null, origin = TransactionOrigin.MANUAL,
                    createdAtMillis = 1_000L, updatedAtMillis = 1_000L,
                ),
            )
        }

        suspend fun sync(): SyncResult.Success = engine.syncNow(room, deviceId) as SyncResult.Success

        fun has(id: String) = ledger.transactionRows.containsKey(TransactionId(id))
    }

    private fun device(name: String, server: FakeSyncServer, ownSecret: ByteArray? = null) =
        Device(name, server, KeysetRoomKeys(1, keyOne), ownSecret)

    @Test
    fun a_remaining_member_gets_the_new_key_through_a_grant_and_the_removed_one_does_not() = runSuspend {
        val server = FakeSyncServer()
        val owner = device("hp-pemilik", server)
        val stays = device("hp-tetap", server, secretStays)
        val removed = device("hp-keluar", server, secretRemoved)
        owner.record("tx-lama")
        owner.sync()
        stays.sync()
        removed.sync()
        assertTrue(stays.has("tx-lama") && removed.has("tx-lama"), "sebelum rotasi semua membaca")

        // Pemilik mengeluarkan satu anggota: rotasi, grant HANYA untuk yang tetap, lalu data baru.
        owner.keys.rotateRoomKey(room)
        val published = owner.engine.publishKeyGrants(room, owner.deviceId, listOf(secretStays))
        assertEquals(RemoteResult.Success(1), published)
        owner.record("tx-baru")
        owner.sync()

        stays.sync()
        assertTrue(stays.has("tx-baru"), "anggota yang tetap membaca data baru lewat grant")
        assertEquals(2, stays.keys.keys.size, "kunci lama tetap ada, kunci baru ikut")
        assertEquals(owner.keys.primary, stays.keys.primary)
        assertTrue(stays.issues.book.isEmpty(), "tidak ada masalah tercatat pada anggota yang menerima grant")

        removed.sync()
        assertTrue(!removed.has("tx-baru"), "anggota yang dikeluarkan tidak bisa membaca data baru")
        assertEquals(1, removed.keys.keys.size, "tidak ada kunci baru masuk")
        assertEquals(listOf(SyncIssueKind.DEFERRED_KEY_MISSING), removed.issues.book.map { it.kind }, "menunggu kunci, bukan dianggap rusak")
    }

    @Test
    fun rows_that_arrive_before_the_grant_are_deferred_then_applied_in_the_same_pull() = runSuspend {
        val server = FakeSyncServer()
        val owner = device("hp-pemilik", server)
        val stays = device("hp-tetap", server, secretStays)
        owner.keys.rotateRoomKey(room)
        owner.record("tx-baru")
        owner.sync() // baris berkunci baru MENDAHULUI grant di log
        owner.engine.publishKeyGrants(room, owner.deviceId, listOf(secretStays))

        val result = stays.sync()

        assertTrue(stays.has("tx-baru"))
        assertEquals(1, result.applied)
        assertEquals(0, result.skipped)
        assertTrue(stays.issues.book.isEmpty(), "penundaan terselesaikan, tidak ada sisa di buku masalah")
    }

    @Test
    fun a_grant_addressed_to_someone_else_is_ignored_silently() = runSuspend {
        val server = FakeSyncServer()
        val owner = device("hp-pemilik", server)
        val stays = device("hp-tetap", server, secretStays)
        val other = device("hp-lain", server, secretRemoved)
        owner.keys.rotateRoomKey(room)
        owner.engine.publishKeyGrants(room, owner.deviceId, listOf(secretStays))

        other.sync()

        assertEquals(1, other.keys.keys.size)
        assertTrue(other.issues.book.isEmpty(), "grant orang lain bukan masalah")
        stays.sync()
        assertEquals(2, stays.keys.keys.size)
    }

    @Test
    fun a_grant_for_this_device_that_cannot_be_opened_is_recorded_and_changes_no_key() = runSuspend {
        val server = FakeSyncServer()
        val stays = device("hp-tetap", server, secretStays)
        val rusak = KeyGrant.seal(secretStays, room, "1|1:00".toByteArray())
        server.stored += SyncLogEntryDownload(
            1, room.value, KeyGrant.RECORD_TYPE, rusak.recordId, "hp-pemilik",
            Base64.getEncoder().encodeToString(rusak.frame.copyOf(rusak.frame.size - 1)), false,
            Instant.ofEpochMilli(5_000).toString(), 5_000, "pemilik",
        )

        stays.sync()

        assertEquals(listOf(SyncIssueKind.TAMPERED_OR_CORRUPT), stays.issues.book.map { it.kind })
        assertEquals(1, stays.keys.keys.size)
        assertNull(stays.keys.keys[2])
    }

    @Test
    fun a_device_without_its_own_secret_keeps_the_old_behaviour_for_undecryptable_rows() = runSuspend {
        val server = FakeSyncServer()
        val owner = device("hp-pemilik", server)
        val oldQr = device("hp-qr-lama", server, ownSecret = null)
        owner.keys.rotateRoomKey(room)
        owner.record("tx-baru")
        owner.sync()

        oldQr.sync()

        assertEquals(listOf(SyncIssueKind.TAMPERED_OR_CORRUPT), oldQr.issues.book.map { it.kind })
    }

    @Test
    fun publishing_nothing_sends_nothing_and_a_failing_server_is_reported_not_thrown() = runSuspend {
        val server = FakeSyncServer()
        val owner = device("hp-pemilik", server)

        assertEquals(RemoteResult.Success(0), owner.engine.publishKeyGrants(room, owner.deviceId, emptyList()))
        assertTrue(server.pushSizes.isEmpty())

        server.failWith = RemoteError.Unreachable
        assertEquals(RemoteResult.Failure(RemoteError.Unreachable), owner.engine.publishKeyGrants(room, owner.deviceId, listOf(secretStays)))
    }

    @Test
    fun republishing_the_same_keyset_is_idempotent_on_the_server() = runSuspend {
        val server = FakeSyncServer()
        val owner = device("hp-pemilik", server)
        owner.keys.rotateRoomKey(room)

        owner.engine.publishKeyGrants(room, owner.deviceId, listOf(secretStays))
        val first = server.stored.single { it.recordType == KeyGrant.RECORD_TYPE }

        assertEquals(KeyGrant.recordId(KeyGrant.tag(secretStays, room), KeyGrant.epochOf(owner.keys.exportRoomKey(room))), first.recordId)
        assertNotNull(first.payload)
        assertContentEquals(
            KeyGrant.open(secretStays, room, first.recordId, Base64.getDecoder().decode(first.payload)),
            owner.keys.exportRoomKey(room),
        )
    }
}
