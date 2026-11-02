package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IssuedSecretBinderTest {
    private val room = RoomId("keluarga")

    private class InMemoryStore : MemberSecretStore {
        private class Row(val id: String, var memberId: String?, val secret: ByteArray)

        private val rows = mutableMapOf<RoomId, MutableList<Row>>()

        override fun saveOwnSecret(roomId: RoomId, memberSecret: ByteArray) = error("tidak dipakai")

        override fun readOwnSecret(roomId: RoomId): ByteArray? = error("tidak dipakai")

        override fun recordIssuedSecret(roomId: RoomId, memberSecret: ByteArray): String {
            val id = UUID.randomUUID().toString()
            rows.getOrPut(roomId) { mutableListOf() } += Row(id, null, memberSecret)
            return id
        }

        override fun bindIssuedSecretToMember(roomId: RoomId, issuedSecretId: String, memberId: String) {
            rows[roomId]?.firstOrNull { it.id == issuedSecretId }?.memberId = memberId
        }

        override fun issuedSecretFor(roomId: RoomId, memberId: String): ByteArray? =
            rows[roomId]?.lastOrNull { it.memberId == memberId }?.secret

        override fun issuedSecrets(roomId: RoomId): List<IssuedMemberSecret> =
            rows[roomId].orEmpty().map { IssuedMemberSecret(it.id, it.memberId, it.secret) }
    }

    private fun secret(seed: Int) = ByteArray(MEMBER_SECRET_LENGTH) { (it + seed).toByte() }

    private fun member(memberId: String?, inviteHash: String?, you: Boolean = false, owner: Boolean = false) =
        RoomMemberInfo(name = memberId, email = "$memberId@contoh.test", you = you, owner = owner, memberId = memberId, inviteHash = inviteHash)

    @Test
    fun `mengikat rahasia ke anggota yang hash undangannya cocok`() {
        val store = InMemoryStore()
        val sitiSecret = secret(1)
        val budiSecret = secret(2)
        store.recordIssuedSecret(room, sitiSecret)
        store.recordIssuedSecret(room, budiSecret)
        val members = listOf(
            member("pemilik", inviteHash = null, you = true, owner = true),
            member("siti", MemberSecretKdf.inviteHash(sitiSecret)),
            member("budi", MemberSecretKdf.inviteHash(budiSecret)),
        )

        val bound = IssuedSecretBinder(store).bind(room, members)

        assertEquals(2, bound)
        assertContentEquals(sitiSecret, store.issuedSecretFor(room, "siti"))
        assertContentEquals(budiSecret, store.issuedSecretFor(room, "budi"))
        assertNull(store.issuedSecretFor(room, "pemilik"))
    }

    @Test
    fun `undangan yang belum dipakai tetap tidak terikat`() {
        val store = InMemoryStore()
        store.recordIssuedSecret(room, secret(1))
        val members = listOf(member("pemilik", inviteHash = null, you = true, owner = true))

        assertEquals(0, IssuedSecretBinder(store).bind(room, members))
        assertNull(store.issuedSecrets(room).single().memberId)
    }

    @Test
    fun `idempoten dan tidak menimpa rahasia yang sudah terikat`() {
        val store = InMemoryStore()
        val sitiSecret = secret(1)
        store.recordIssuedSecret(room, sitiSecret)
        val members = listOf(member("siti", MemberSecretKdf.inviteHash(sitiSecret)))
        val binder = IssuedSecretBinder(store)

        assertEquals(1, binder.bind(room, members))
        assertEquals(0, binder.bind(room, members))
        // Hash yang sama dilaporkan untuk anggota lain tidak mengubah ikatan yang sudah ada.
        assertEquals(0, binder.bind(room, listOf(member("orang-lain", MemberSecretKdf.inviteHash(sitiSecret)))))
        assertContentEquals(sitiSecret, store.issuedSecretFor(room, "siti"))
        assertNull(store.issuedSecretFor(room, "orang-lain"))
    }

    @Test
    fun `hash huruf besar tetap cocok dan anggota tanpa member_id atau hash dilewati`() {
        val store = InMemoryStore()
        val secret = secret(1)
        store.recordIssuedSecret(room, secret)
        val hash = MemberSecretKdf.inviteHash(secret)
        val tanpaMemberId = member(memberId = null, inviteHash = hash)

        assertEquals(0, IssuedSecretBinder(store).bind(room, listOf(tanpaMemberId, member("siti", inviteHash = null))))
        assertEquals(1, IssuedSecretBinder(store).bind(room, listOf(member("siti", hash.uppercase()))))
    }

    @Test
    fun `ruang lain tidak tersentuh`() {
        val store = InMemoryStore()
        val secret = secret(1)
        store.recordIssuedSecret(RoomId("ruang-lain"), secret)

        assertEquals(0, IssuedSecretBinder(store).bind(room, listOf(member("siti", MemberSecretKdf.inviteHash(secret)))))
    }
}
