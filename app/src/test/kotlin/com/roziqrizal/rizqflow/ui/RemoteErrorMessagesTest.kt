package com.roziqrizal.rizqflow.ui

import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.AutoSyncReport
import com.roziqrizal.rizqflow.domain.sync.EnableRecoveryResult
import com.roziqrizal.rizqflow.domain.sync.RecoverKeyResult
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import com.roziqrizal.rizqflow.domain.sync.ServerErrorCodes
import com.roziqrizal.rizqflow.ui.autosync.ManualSyncFeedback
import com.roziqrizal.rizqflow.ui.autosync.feedback
import com.roziqrizal.rizqflow.ui.paywall.opensSyncPaywall
import com.roziqrizal.rizqflow.ui.recovery.enableFailureMessage
import com.roziqrizal.rizqflow.ui.recovery.recoverFailureMessage
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pesan layar untuk galat server per kode (Tahap 9): 429 selalu "terlalu sering" (bukan "gagal"), kode keanggotaan ruang
 * punya penjelasan lembut sendiri, hanya `sync_required` yang membuka paywall, dan teks server mentah tidak pernah
 * dipakai. Fungsi murni — tanpa Context.
 */
class RemoteErrorMessagesTest {
    private fun rejected(code: String?) = RemoteError.Rejected("TEKS MENTAH SERVER", code)

    @Test
    fun `429 tanpa jeda memakai kalimat tanpa angka`() {
        assertEquals(UiMessage(R.string.remote_rate_limited), RemoteError.Unexpected(429).familyRoomMessage(R.string.family_sync_failed))
        assertEquals(UiMessage(R.string.remote_rate_limited), rateLimitedMessage(null))
        assertEquals(UiMessage(R.string.remote_rate_limited), rateLimitedMessage(0))
    }

    @Test
    fun `429 dengan Retry-After menyebut detik di bawah semenit dan menit dibulatkan ke atas`() {
        assertEquals(UiMessage(R.string.remote_rate_limited_seconds, 1), rateLimitedMessage(1))
        assertEquals(UiMessage(R.string.remote_rate_limited_seconds, 45), RemoteError.Unexpected(429, 45).familyRoomMessage(R.string.family_sync_failed))
        assertEquals(UiMessage(R.string.remote_rate_limited_seconds, 59), rateLimitedMessage(59))
        assertEquals(UiMessage(R.string.remote_rate_limited_minutes, 1), rateLimitedMessage(60))
        // Dibulatkan ke atas: tidak menjanjikan lebih cepat dari yang diminta server.
        assertEquals(UiMessage(R.string.remote_rate_limited_minutes, 2), rateLimitedMessage(61))
        assertEquals(UiMessage(R.string.remote_rate_limited_minutes, 2), rateLimitedMessage(120))
        assertEquals(UiMessage(R.string.remote_rate_limited_minutes, 60), rateLimitedMessage(3_600))
    }

    @Test
    fun `5xx dan 503 dengan Retry-After bukan pesan terlalu sering`() {
        assertEquals(UiMessage(R.string.family_sync_failed), RemoteError.Unexpected(503, 30).familyRoomMessage(R.string.family_sync_failed))
        assertEquals(UiMessage(R.string.family_sync_failed), RemoteError.Unexpected(500).familyRoomMessage(R.string.family_sync_failed))
    }

    @Test
    fun `setiap kode keanggotaan ruang punya pesan lembut sendiri`() {
        fun message(code: String) = rejected(code).familyRoomMessage(R.string.family_sync_failed)

        assertEquals(UiMessage(R.string.family_err_not_member), message(ServerErrorCodes.NOT_ROOM_MEMBER))
        assertEquals(UiMessage(R.string.family_err_not_owner), message(ServerErrorCodes.NOT_ROOM_OWNER))
        assertEquals(UiMessage(R.string.family_err_owner_cannot_leave), message(ServerErrorCodes.OWNER_CANNOT_LEAVE))
        assertEquals(UiMessage(R.string.family_err_owner_cannot_be_removed), message(ServerErrorCodes.OWNER_CANNOT_BE_REMOVED))
        assertEquals(UiMessage(R.string.family_err_already_owner), message(ServerErrorCodes.ALREADY_OWNER))
        assertEquals(UiMessage(R.string.family_err_member_not_found), message(ServerErrorCodes.MEMBER_NOT_FOUND))
        assertEquals(UiMessage(R.string.family_err_invalid_room), message(ServerErrorCodes.INVALID_ROOM_ID))
    }

    @Test
    fun `kode tak dikenal, tanpa kode, jaringan, dan sesi jatuh ke pesan milik aksinya, tidak ke teks server`() {
        val fallback = R.string.family_remove_failed
        assertEquals(UiMessage(fallback), rejected("kode_baru").familyRoomMessage(fallback))
        assertEquals(UiMessage(fallback), rejected(null).familyRoomMessage(fallback))
        assertEquals(UiMessage(fallback), rejected("validation_failed").familyRoomMessage(fallback))
        assertEquals(UiMessage(fallback), RemoteError.Unreachable.familyRoomMessage(fallback))
        assertEquals(UiMessage(fallback), RemoteError.Unexpected(404).familyRoomMessage(fallback))
        // Hasilnya selalu string aplikasi (res), tidak pernah membawa teks dari server.
        assertEquals(null, rejected("kode_baru").familyRoomMessage(fallback).number)
    }

    @Test
    fun `hanya sync_required dan 403 tanpa kode yang membuka paywall, kode keanggotaan tidak`() {
        assertTrue(RemoteError.SyncRequired.opensSyncPaywall())
        for (code in ServerErrorCodes.REJECTION_CODES + ServerErrorCodes.RATE_LIMITED + "forbidden") {
            assertFalse(rejected(code).opensSyncPaywall(), code)
        }
        assertFalse(RemoteError.Unexpected(429, 10).opensSyncPaywall())
    }

    @Test
    fun `tombol sinkron manual memberi pesan terlalu sering untuk 429, bukan gagal generik`() {
        assertEquals(
            ManualSyncFeedback.Message(R.string.remote_rate_limited),
            AutoSyncReport.Failed(RemoteError.Unexpected(429, 30)).feedback(),
        )
        assertEquals(
            ManualSyncFeedback.Message(R.string.remote_rate_limited),
            AutoSyncReport.Failed(RemoteError.Unexpected(429)).feedback(),
        )
        // Yang lain tidak berubah.
        assertEquals(ManualSyncFeedback.Message(R.string.sync_manual_failed), AutoSyncReport.Failed(RemoteError.Unexpected(503, 30)).feedback())
        assertEquals(ManualSyncFeedback.OpenSyncPaywall, AutoSyncReport.Failed(RemoteError.SyncRequired).feedback())
    }

    @Test
    fun `layar kode pemulihan juga memberi pesan terlalu sering untuk 429`() {
        assertEquals(
            R.string.remote_rate_limited,
            enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Unexpected(429, 20)), sessionEnded = false),
        )
        assertEquals(
            R.string.remote_rate_limited,
            recoverFailureMessage(RecoverKeyResult.Failed(RemoteError.Unexpected(429)), sessionEnded = false),
        )
        assertEquals(
            R.string.recovery_err_generic,
            enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Unexpected(500)), sessionEnded = false),
        )
    }

    @Test
    fun `semua string baru ada di bahasa Indonesia dan Inggris`() {
        val ids = listOf(
            "remote_rate_limited", "remote_rate_limited_seconds", "remote_rate_limited_minutes", "msg_rate_limited",
            "family_err_not_member", "family_err_not_owner", "family_err_owner_cannot_leave",
            "family_err_owner_cannot_be_removed", "family_err_member_not_found", "family_err_invalid_room",
            "family_leave_action", "family_leave_confirm_title", "family_leave_confirm_body", "family_leave_confirm_action",
            "family_leave_done", "family_leave_failed", "family_owner_cannot_leave_note", "family_left_info",
        )
        for (dir in listOf("values", "values-en")) {
            val names = stringNames(File(resDir(), "$dir/strings.xml"))
            for (id in ids) assertTrue(id in names, "$id hilang di $dir")
        }
    }

    private fun resDir(): File =
        listOf("src/main/res", "app/src/main/res").map(::File).first { it.isDirectory }

    private fun stringNames(file: File): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
    }
}
