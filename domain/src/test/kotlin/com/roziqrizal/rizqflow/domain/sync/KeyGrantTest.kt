package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.RoomId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeyGrantTest {
    private val room = RoomId("keluarga")
    private val secret = ByteArray(MEMBER_SECRET_LENGTH) { it.toByte() }
    private val keyset = ByteArray(200) { (it * 7).toByte() }

    @Test
    fun `segel lalu buka mengembalikan keyset`() {
        val sealed = KeyGrant.seal(secret, room, keyset)

        assertContentEquals(keyset, KeyGrant.open(secret, room, sealed.recordId, sealed.frame))
    }

    @Test
    fun `record_id adalah tag 32 hex ditambah epoch 8 hex dan tag stabil per anggota dan ruang`() {
        val sealed = KeyGrant.seal(secret, room, keyset)

        assertEquals(40, sealed.recordId.length)
        assertTrue(sealed.recordId.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(KeyGrant.tag(secret, room), sealed.recordId.take(32))
        assertEquals(KeyGrant.epochOf(keyset), sealed.recordId.takeLast(8))
        assertNotEquals(KeyGrant.tag(secret, room), KeyGrant.tag(secret, RoomId("ruang-lain")))
        assertNotEquals(KeyGrant.tag(secret, room), KeyGrant.tag(ByteArray(MEMBER_SECRET_LENGTH) { 9 }, room))
    }

    @Test
    fun `epoch mengikuti isi keyset dan mengirim ulang keyset yang sama memberi record_id yang sama`() {
        val lain = keyset.copyOf().also { it[0] = (it[0] + 1).toByte() }

        assertEquals(KeyGrant.seal(secret, room, keyset).recordId, KeyGrant.seal(secret, room, keyset).recordId)
        assertNotEquals(KeyGrant.seal(secret, room, keyset).recordId, KeyGrant.seal(secret, room, lain).recordId)
    }

    @Test
    fun `nonce acak membuat bingkai berbeda untuk keyset yang sama`() {
        assertFalse(KeyGrant.seal(secret, room, keyset).frame.contentEquals(KeyGrant.seal(secret, room, keyset).frame))
    }

    @Test
    fun `rahasia lain tidak bisa membuka`() {
        val sealed = KeyGrant.seal(secret, room, keyset)

        assertFailsWith<KeyGrantException> {
            KeyGrant.open(ByteArray(MEMBER_SECRET_LENGTH) { 1 }, room, sealed.recordId, sealed.frame)
        }
    }

    @Test
    fun `grant yang dipindah ke ruang lain atau diberi record_id lain gagal dibuka`() {
        val sealed = KeyGrant.seal(secret, room, keyset)

        assertFailsWith<KeyGrantException> { KeyGrant.open(secret, RoomId("ruang-lain"), sealed.recordId, sealed.frame) }
        assertFailsWith<KeyGrantException> { KeyGrant.open(secret, room, sealed.recordId.dropLast(1) + "0", sealed.frame) }
    }

    @Test
    fun `bingkai dirusak atau terpotong ditolak`() {
        val sealed = KeyGrant.seal(secret, room, keyset)
        val rusak = sealed.frame.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        val versiLain = sealed.frame.copyOf().also { it[0] = 9 }

        assertFailsWith<KeyGrantException> { KeyGrant.open(secret, room, sealed.recordId, rusak) }
        assertFailsWith<KeyGrantException> { KeyGrant.open(secret, room, sealed.recordId, versiLain) }
        assertFailsWith<KeyGrantException> { KeyGrant.open(secret, room, sealed.recordId, sealed.frame.copyOf(10)) }
    }

    @Test
    fun `belongsTo hanya cocok untuk pemilik rahasia di ruang itu`() {
        val sealed = KeyGrant.seal(secret, room, keyset)

        assertTrue(KeyGrant.belongsTo(secret, room, sealed.recordId))
        assertFalse(KeyGrant.belongsTo(ByteArray(MEMBER_SECRET_LENGTH) { 1 }, room, sealed.recordId))
        assertFalse(KeyGrant.belongsTo(secret, RoomId("ruang-lain"), sealed.recordId))
        assertFalse(KeyGrant.belongsTo(secret, room, "terlalu-pendek"))
    }

    @Test
    fun `kunci grant tidak sama dengan hash undangan yang dikirim ke server`() {
        val grantKeyHex = MemberSecretKdf.keyGrantKey(secret).joinToString("") { "%02x".format(it) }

        assertNotEquals(MemberSecretKdf.inviteHash(secret), grantKeyHex)
        assertNotEquals(KeyGrant.tag(secret, room), MemberSecretKdf.inviteHash(secret).take(32))
    }
}
