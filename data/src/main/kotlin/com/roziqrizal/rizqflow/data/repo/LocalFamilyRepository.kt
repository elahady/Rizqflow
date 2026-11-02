package com.roziqrizal.rizqflow.data.repo

import com.roziqrizal.rizqflow.data.db.RizqflowDatabase
import com.roziqrizal.rizqflow.data.db.SharedEnvelopeEntity
import com.roziqrizal.rizqflow.data.db.SharedMemberEntity
import com.roziqrizal.rizqflow.data.db.withTransaction
import com.roziqrizal.rizqflow.domain.ledger.EnvelopeEntry
import com.roziqrizal.rizqflow.domain.ledger.EnvelopeRepository
import com.roziqrizal.rizqflow.domain.model.RoomId
import com.roziqrizal.rizqflow.domain.sync.SharedMemberCache
import com.roziqrizal.rizqflow.domain.sync.SharedMemberRepository
import com.roziqrizal.rizqflow.domain.sync.SharedRoomRole
import com.roziqrizal.rizqflow.domain.sync.SharedRoomStatus

/**
 * Implementasi Room [SharedMemberRepository] (`shared_member`) dan [EnvelopeRepository] (`shared_envelope`) —
 * peran-anggota.md E.2/H langkah 8. Dua tabel, satu kelas: keduanya bertetangga langsung di skema (versi 13) dan
 * dibaca/ditulis bersama hampir di tempat yang sama (sheet "Atur amplop"), sama gaya dengan repositori lain yang
 * menggabungkan beberapa tabel kecil terkait (mis. `LocalZakatRepository`).
 */
class LocalFamilyRepository(private val db: RizqflowDatabase) : SharedMemberRepository, EnvelopeRepository {
    private val dao get() = db.family()

    override suspend fun replaceAll(roomId: RoomId, members: List<SharedMemberCache>) = db.withTransaction {
        val existing = dao.membersOf(roomId.value).associateBy { it.memberId }
        val incomingIds = members.mapTo(HashSet()) { it.memberId }
        for (member in members) {
            dao.upsertMember(
                SharedMemberEntity(
                    roomId = roomId.value,
                    memberId = member.memberId,
                    label = member.label,
                    role = member.role.name,
                    status = member.status.name,
                    isMe = member.isMe,
                    updatedAt = member.updatedAtMillis,
                ),
            )
        }
        // Anggota lama yang tidak lagi muncul di jawaban server (dikeluarkan ATAU keluar sendiri — members() tidak
        // membedakan keduanya) ditandai REMOVED, TIDAK dihapus (lihat KDoc SharedMemberCache.status).
        for ((memberId, row) in existing) {
            if (memberId !in incomingIds && row.status != SharedRoomStatus.REMOVED.name) {
                dao.upsertMember(row.copy(status = SharedRoomStatus.REMOVED.name))
            }
        }
    }

    override suspend fun find(roomId: RoomId, memberId: String): SharedMemberCache? =
        dao.findMember(roomId.value, memberId)?.toCache()

    override suspend fun all(roomId: RoomId): List<SharedMemberCache> =
        dao.membersOf(roomId.value).mapNotNull { it.toCache() }

    override suspend fun find(roomId: RoomId, memberId: String, fromMonth: Int): EnvelopeEntry? =
        dao.findEnvelope(roomId.value, memberId, fromMonth)?.toEntry()

    override suspend fun entries(roomId: RoomId): List<EnvelopeEntry> =
        dao.envelopesOf(roomId.value).map { it.toEntry() }

    override suspend fun upsert(roomId: RoomId, entry: EnvelopeEntry) =
        dao.upsertEnvelope(SharedEnvelopeEntity(roomId.value, entry.memberId, entry.fromMonth, entry.amount))
}

private fun SharedMemberEntity.toCache(): SharedMemberCache? {
    val parsedRole = SharedRoomRole.entries.firstOrNull { it.name == role } ?: return null
    val parsedStatus = SharedRoomStatus.entries.firstOrNull { it.name == status } ?: return null
    return SharedMemberCache(memberId, label, parsedRole, parsedStatus, isMe, updatedAt)
}

private fun SharedEnvelopeEntity.toEntry() = EnvelopeEntry(memberId, fromMonth, amount)
