package com.roziqrizal.rizqflow.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Cache anggota ruang bersama (`shared_member`) dan proyeksi amplop (`shared_envelope`) — versi 13, peran-anggota.md H langkah 8. */
@Dao
interface FamilyDao {
    @Upsert
    suspend fun upsertMember(member: SharedMemberEntity)

    @Query("SELECT * FROM shared_member WHERE room_id = :roomId")
    suspend fun membersOf(roomId: String): List<SharedMemberEntity>

    @Query("SELECT * FROM shared_member WHERE room_id = :roomId AND member_id = :memberId")
    suspend fun findMember(roomId: String, memberId: String): SharedMemberEntity?

    @Upsert
    suspend fun upsertEnvelope(envelope: SharedEnvelopeEntity)

    @Query("SELECT * FROM shared_envelope WHERE room_id = :roomId")
    suspend fun envelopesOf(roomId: String): List<SharedEnvelopeEntity>

    @Query("SELECT * FROM shared_envelope WHERE room_id = :roomId AND member_id = :memberId AND from_month = :fromMonth")
    suspend fun findEnvelope(roomId: String, memberId: String, fromMonth: Int): SharedEnvelopeEntity?
}
