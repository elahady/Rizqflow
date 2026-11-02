package com.roziqrizal.rizqflow.domain.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tabel hak rilis 1 (peran-anggota.md bagian C): OWNER dan MEMBER saja, null (bukan anggota) ditolak semua. */
class RoomPermissionsTest {
    @Test
    fun `pemilik boleh mengundang, mengeluarkan, dan memindahkan kepemilikan, tetapi tidak keluar sendiri`() {
        assertTrue(RoomPermissions.canInvite(SharedRoomRole.OWNER))
        assertTrue(RoomPermissions.canRemoveMembers(SharedRoomRole.OWNER))
        assertTrue(RoomPermissions.canTransferOwnership(SharedRoomRole.OWNER))
        assertFalse(RoomPermissions.canLeave(SharedRoomRole.OWNER))
    }

    @Test
    fun `anggota pencatat hanya boleh keluar sendiri`() {
        assertFalse(RoomPermissions.canInvite(SharedRoomRole.MEMBER))
        assertFalse(RoomPermissions.canRemoveMembers(SharedRoomRole.MEMBER))
        assertFalse(RoomPermissions.canTransferOwnership(SharedRoomRole.MEMBER))
        assertTrue(RoomPermissions.canLeave(SharedRoomRole.MEMBER))
    }

    @Test
    fun `bukan anggota ruang tidak punya hak apa pun`() {
        assertFalse(RoomPermissions.canInvite(null))
        assertFalse(RoomPermissions.canRemoveMembers(null))
        assertFalse(RoomPermissions.canTransferOwnership(null))
        assertFalse(RoomPermissions.canLeave(null))
    }
}
