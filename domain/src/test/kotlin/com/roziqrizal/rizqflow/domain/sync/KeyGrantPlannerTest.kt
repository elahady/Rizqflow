package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KeyGrantPlannerTest {
    private val room = RoomId("keluarga")

    private class Store(private val bound: Map<String, ByteArray>) : MemberSecretStore {
        override fun saveOwnSecret(roomId: RoomId, memberSecret: ByteArray) = error("tidak dipakai")
        override fun readOwnSecret(roomId: RoomId): ByteArray? = error("tidak dipakai")
        override fun recordIssuedSecret(roomId: RoomId, memberSecret: ByteArray): String = error("tidak dipakai")
        override fun bindIssuedSecretToMember(roomId: RoomId, issuedSecretId: String, memberId: String) = error("tidak dipakai")
        override fun issuedSecretFor(roomId: RoomId, memberId: String): ByteArray? = bound[memberId]
        override fun issuedSecrets(roomId: RoomId): List<IssuedMemberSecret> = error("tidak dipakai")
    }

    private fun member(memberId: String?) = RoomMemberInfo(name = memberId, email = "$memberId@x.test", you = false, memberId = memberId)

    @Test
    fun `anggota dengan rahasia terikat dapat otomatis, sisanya butuh QR`() {
        val siti = ByteArray(32) { 1 }
        val store = Store(mapOf("siti" to siti))

        val plan = KeyGrantPlanner.plan(room, listOf(member("siti"), member("budi"), member(null)), store)

        assertEquals(listOf("siti"), plan.covered.map { it.memberId })
        assertContentEquals(siti, plan.secrets.single())
        assertEquals(listOf("budi", null), plan.needsQr.map { it.memberId })
    }

    @Test
    fun `tanpa anggota yang tetap rencananya kosong`() {
        val plan = KeyGrantPlanner.plan(room, emptyList(), Store(emptyMap()))

        assertEquals(0, plan.secrets.size + plan.covered.size + plan.needsQr.size)
    }
}
