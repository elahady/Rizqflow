package com.roziqrizal.rizqflow.domain.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Peran dari jawaban server dan siapa yang boleh dikeluarkan (keputusan pemilik #1, sync-desain.md bagian J). */
class RoomMemberRolesTest {
    private val owner = RoomMemberInfo("Pemilik", "a@x", you = false, owner = true, userId = 1)
    private val partner = RoomMemberInfo("Pasangan", "b@x", you = false, owner = false, userId = 2)
    private val child = RoomMemberInfo(null, "c@x", you = false, owner = false, userId = 3)

    @Test
    fun `peran sendiri diambil dari bendera owner server, bukan dari siapa yang membuka layar`() {
        assertEquals(SharedRoomRole.OWNER, listOf(owner.copy(you = true), partner).ownRole())
        assertEquals(SharedRoomRole.MEMBER, listOf(owner, partner.copy(you = true)).ownRole())
    }

    @Test
    fun `tanpa baris diri sendiri peran tidak diketahui`() {
        assertNull(listOf(owner, partner).ownRole())
        assertNull(emptyList<RoomMemberInfo>().ownRole())
    }

    @Test
    fun `server lama tanpa bendera owner tidak pernah membuat diri sendiri pemilik`() {
        assertEquals(SharedRoomRole.MEMBER, listOf(RoomMemberInfo("Saya", "s@x", you = true)).ownRole())
    }

    @Test
    fun `pemilik bisa mengeluarkan semua anggota lain kecuali dirinya`() {
        val members = listOf(owner.copy(you = true), partner, child)
        assertEquals(listOf(partner, child), members.removableMembers())
    }

    @Test
    fun `bukan pemilik tidak bisa mengeluarkan siapa pun`() {
        assertTrue(listOf(owner, partner.copy(you = true), child).removableMembers().isEmpty())
    }

    @Test
    fun `anggota tanpa user id dari server lama tidak ditawarkan untuk dikeluarkan`() {
        val members = listOf(owner.copy(you = true), partner.copy(userId = null), child)
        assertEquals(listOf(child), members.removableMembers())
    }
}
