package com.roziqrizal.rizqflow.domain.sync

import com.roziqrizal.rizqflow.domain.model.AccountId
import com.roziqrizal.rizqflow.domain.model.RoomId
import java.security.MessageDigest
import java.util.Base64

/**
 * Akun anggota virtual (Tahap 2 sync-desain.md bagian B/E, keputusan pemilik #0, 2026-10-26): "label
 * akun sumber + penulis", pengganti pencocokan akun lokal penerima yang membocorkan saldo. Dipakai
 * [SyncCoordinator] saat menerapkan pengeluaran anggota lain di ruang bersama dan
 * [com.roziqrizal.rizqflow.domain.ledger.AccountRepository.findOrCreateVirtualMember].
 */
object VirtualMemberAccount {
    private const val ID_PREFIX = "shared:"
    private const val ID_LENGTH = 24
    private const val DEFAULT_ACCOUNT_NAME = "Akun"

    /**
     * Id deterministik dari (roomId, author, accountName ternormalisasi): panggilan ulang dengan
     * masukan yang sama selalu menghasilkan id yang sama, sehingga [AccountRepository.findOrCreateVirtualMember]
     * idempoten tanpa tabel pencarian terpisah. Hash, bukan konkatenasi mentah: id akun tidak membocorkan
     * roomId/author secara terbaca, sejalan dengan penyamaran id lain di sync-desain.md bagian C.
     */
    fun id(roomId: RoomId, author: String, accountName: String): AccountId {
        val normalized = normalize(accountName)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("rizqflow-shared-account|${roomId.value}|$author|$normalized".toByteArray(Charsets.UTF_8))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(digest).substring(0, ID_LENGTH)
        return AccountId("$ID_PREFIX$encoded")
    }

    /**
     * Nama tampilan "label (penulis)" (bagian E). Belum ada sistem nama panggilan anggota (keputusan
     * pemilik #16, belum dikerjakan), jadi "penulis" sementara berupa cuplikan singkat [author] —
     * diganti nama panggilan asli begitu fiturnya ada, tanpa mengubah id (id tidak dipengaruhi tampilan).
     */
    fun displayName(accountName: String, author: String): String {
        val name = accountName.ifBlank { DEFAULT_ACCOUNT_NAME }
        val label = author.take(6).ifBlank { "anggota" }
        return "$name (anggota-$label)"
    }

    /** NFKC + trim + rapatkan spasi + huruf kecil (sama dengan normalisasi nama kategori, bagian B R2). */
    private fun normalize(name: String): String =
        java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC)
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase(java.util.Locale.ROOT)
}
