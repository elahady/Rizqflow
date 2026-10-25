package com.roziqrizal.rizqflow.ui.recovery

import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.EnableRecoveryResult
import com.roziqrizal.rizqflow.domain.sync.InvalidRecoveryCodeKind
import com.roziqrizal.rizqflow.domain.sync.RecoverKeyResult
import com.roziqrizal.rizqflow.domain.sync.RecoveryCopyState
import com.roziqrizal.rizqflow.domain.sync.RemoteError
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pemetaan hasil kode pemulihan ke pesan, dan kelengkapan teks ID serta EN. */
class RecoveryMessagesTest {

    @Test
    fun `403 tanpa Sync memakai pesan lembut butuh langganan, bukan pesan gagal`() {
        val message = enableFailureMessage(EnableRecoveryResult.SyncRequired, sessionEnded = false)

        assertEquals(R.string.recovery_err_sync_required, message)
        assertNotEquals(R.string.recovery_err_generic, message)
        // Sama bila lapisan jaringan yang membawa SyncRequired lewat Failed.
        assertEquals(message, enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.SyncRequired), sessionEnded = false))
    }

    @Test
    fun `jaringan, sesi berakhir, kunci, dan galat lain punya pesan masing-masing`() {
        assertEquals(R.string.recovery_err_network, enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Unreachable), false))
        assertEquals(R.string.recovery_err_session_ended, enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Unauthorized), sessionEnded = true))
        assertEquals(R.string.recovery_err_generic, enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Unauthorized), sessionEnded = false))
        assertEquals(R.string.recovery_err_generic, enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Rejected("x")), false))
        assertEquals(R.string.recovery_err_generic, enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Unexpected(500)), false))
        assertEquals(R.string.recovery_err_key, enableFailureMessage(EnableRecoveryResult.KeyUnavailable, false))
        assertEquals(R.string.recovery_err_not_signed_in, enableFailureMessage(EnableRecoveryResult.NotSignedIn, false))
    }

    @Test
    fun `hasil yang bukan kegagalan tidak boleh dipetakan ke pesan gagal`() {
        assertFailsWith<IllegalStateException> { enableFailureMessage(EnableRecoveryResult.ExistingCopyOnServer, false) }
        assertFailsWith<IllegalStateException> { recoverFailureMessage(RecoverKeyResult.Recovered, false) }
    }

    @Test
    fun `kode salah dan salinan rusak berbagi satu pesan, tanpa membedakan lebih jauh`() {
        assertEquals(R.string.restore_err_wrong_code, recoverFailureMessage(RecoverKeyResult.WrongCode, false))
    }

    @Test
    fun `salah ketik, jumlah, dan checksum punya pesan spesifik yang berbeda`() {
        val messages = InvalidRecoveryCodeKind.entries.map { invalidCodeMessage(it) }

        assertEquals(messages.size, messages.toSet().size)
        assertEquals(R.string.restore_err_unknown, recoverFailureMessage(RecoverKeyResult.InvalidCode(InvalidRecoveryCodeKind.UNKNOWN_WORD), false))
        assertEquals(R.string.restore_err_checksum, recoverFailureMessage(RecoverKeyResult.InvalidCode(InvalidRecoveryCodeKind.BAD_CHECKSUM), false))
    }

    @Test
    fun `tidak ada salinan, versi baru, bungkusan rusak, jaringan, dan sesi berakhir saat pulihkan`() {
        assertEquals(R.string.restore_err_none, recoverFailureMessage(RecoverKeyResult.NoCopyOnServer, false))
        assertEquals(R.string.restore_err_unsupported, recoverFailureMessage(RecoverKeyResult.UnsupportedVersion, false))
        assertEquals(R.string.restore_err_unreadable, recoverFailureMessage(RecoverKeyResult.BackupUnreadable, false))
        assertEquals(R.string.restore_err_network, recoverFailureMessage(RecoverKeyResult.Failed(RemoteError.Unreachable), false))
        assertEquals(R.string.recovery_err_session_ended, recoverFailureMessage(RecoverKeyResult.Failed(RemoteError.Unauthorized), true))
        assertEquals(R.string.restore_err_generic, recoverFailureMessage(RecoverKeyResult.Failed(RemoteError.Unauthorized), false))
    }

    // ---- ID dan EN lengkap ----

    private fun stringsOf(path: String): Map<String, String> {
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i -> nodes.item(i).attributes.getNamedItem("name").nodeValue to nodes.item(i).textContent }
    }

    private val recoveryKeys: (String) -> Boolean = { it.startsWith("recovery_") || it.startsWith("restore_") || it.startsWith("menu_recovery") }

    @Test
    fun `semua teks kode pemulihan ada di ID dan EN dengan penampung format yang sama`() {
        val id = stringsOf("src/main/res/values/strings.xml").filterKeys(recoveryKeys)
        val en = stringsOf("src/main/res/values-en/strings.xml").filterKeys(recoveryKeys)

        assertTrue(id.size > 60, "teks kode pemulihan tidak terbaca: ${id.size}")
        assertEquals(id.keys, en.keys, "kunci ID dan EN harus sama")
        val placeholder = Regex("%\\d\\$[sd]")
        for ((key, text) in id) {
            assertEquals(placeholder.findAll(text).map { it.value }.toList(), placeholder.findAll(en.getValue(key)).map { it.value }.toList(), "penampung format beda di $key")
            assertTrue(en.getValue(key).isNotBlank() && text.isNotBlank(), "kosong: $key")
        }
    }

    @Test
    fun `peringatan tampil-sekali menyebut kertas, larangan screenshot, dan ketiadaan bantuan di kedua bahasa`() {
        val id = stringsOf("src/main/res/values/strings.xml")
        val en = stringsOf("src/main/res/values-en/strings.xml")

        assertTrue(id.getValue("recovery_show_warn_paper").contains("kertas"))
        assertTrue(id.getValue("recovery_show_warn_no_copy").contains("screenshot", ignoreCase = true))
        assertTrue(id.getValue("recovery_show_warn_no_help").contains("tidak bisa membantu"))
        assertTrue(en.getValue("recovery_show_warn_paper").contains("paper"))
        assertTrue(en.getValue("recovery_show_warn_no_copy").contains("screenshot", ignoreCase = true))
        assertTrue(en.getValue("recovery_show_warn_no_help").contains("cannot help"))
    }

    // ---- hasil tidak pasti, salinan tidak sama, dan 404 "server belum siap" ----

    @Test
    fun `hasil tidak bisa dipastikan dan salinan tidak sama punya pesan jujur sendiri, bukan pesan gagal umum`() {
        assertEquals(R.string.recovery_err_unverified, enableFailureMessage(EnableRecoveryResult.Unverified, false))
        assertEquals(R.string.recovery_err_mismatch, enableFailureMessage(EnableRecoveryResult.ServerCopyMismatch, false))
        assertNotEquals(R.string.recovery_err_generic, enableFailureMessage(EnableRecoveryResult.Unverified, false))
        assertNotEquals(R.string.recovery_err_network, enableFailureMessage(EnableRecoveryResult.Unverified, false))
    }

    @Test
    fun `404 tanpa penanda adalah server belum siap, berbeda dari belum ada kode dan dari galat umum`() {
        val notReady = RemoteError.Unexpected(404)

        assertEquals(R.string.recovery_err_server_not_ready, enableFailureMessage(EnableRecoveryResult.Failed(notReady), false))
        assertEquals(R.string.restore_err_server_not_ready, recoverFailureMessage(RecoverKeyResult.Failed(notReady), false))
        assertNotEquals(R.string.restore_err_none, recoverFailureMessage(RecoverKeyResult.Failed(notReady), false))
        // Galat server lain tetap pesan umum.
        assertEquals(R.string.restore_err_generic, recoverFailureMessage(RecoverKeyResult.Failed(RemoteError.Unexpected(500)), false))
        assertEquals(R.string.recovery_err_generic, enableFailureMessage(EnableRecoveryResult.Failed(RemoteError.Unexpected(500)), false))
        // "Belum ada kode" tetap hanya untuk salinan yang memang tidak ada.
        assertEquals(R.string.restore_err_none, recoverFailureMessage(RecoverKeyResult.NoCopyOnServer, false))
    }

    @Test
    fun `salinan diganti atau hilang punya kabar, selain itu diam`() {
        assertEquals(R.string.recovery_status_replaced, copyStateMessage(RecoveryCopyState.REPLACED))
        assertEquals(R.string.recovery_status_missing, copyStateMessage(RecoveryCopyState.MISSING))
        assertEquals(null, copyStateMessage(RecoveryCopyState.IN_SYNC))
        assertEquals(null, copyStateMessage(RecoveryCopyState.UNKNOWN))
    }

    // ---- teks jujur: tidak menjanjikan lebih dari kenyataan ----

    @Test
    fun `teks tidak lagi menjanjikan pemulihan data akun, dan menyebut kunci akun serta undang ulang ruang lewat QR`() {
        for ((path, qr) in listOf("src/main/res/values/strings.xml" to "QR", "src/main/res/values-en/strings.xml" to "QR")) {
            val strings = stringsOf(path)
            val promises = listOf("menu_recovery_sub_offer", "recovery_intro", "recovery_offer_snackbar", "restore_success", "recovery_done_body")
            for (key in promises) {
                val text = strings.getValue(key)
                assertFalse(text.contains("Hanya kode ini yang bisa membuka kembali data akunmu"), "$key: janji lama masih ada")
                assertFalse(text.contains("agar data akun yang disinkronkan bisa dipulihkan"), "$key: janji lama masih ada")
                assertFalse(text.contains("Only this code can reopen your account data"), "$key: janji lama masih ada")
                assertFalse(text.contains("so your synced account data can be restored"), "$key: janji lama masih ada")
            }
            // Fondasinya disebut jujur: kunci akun, dan data baru bisa dipulihkan setelah sinkron data akun ada.
            assertTrue(strings.getValue("recovery_intro").contains(if (path.contains("values-en")) "account key" else "kunci akun"))
            assertTrue(strings.getValue("menu_recovery_sub_offer").contains(if (path.contains("values-en")) "account key" else "kunci akun"))
            // Kunci ruang keluarga belum ikut: ruang bersama diundang ulang lewat QR.
            assertTrue(strings.getValue("recovery_intro").contains(qr), "$path: undang ulang lewat QR belum disebut")
            assertTrue(strings.getValue("restore_success").contains(qr), "$path: undang ulang lewat QR belum disebut")
        }
    }

    @Test
    fun `konfirmasi menimpa salinan menyebut konsekuensinya di kedua bahasa`() {
        val id = stringsOf("src/main/res/values/strings.xml").getValue("recovery_replace_confirm_body")
        val en = stringsOf("src/main/res/values-en/strings.xml").getValue("recovery_replace_confirm_body")

        assertTrue(id.contains("menggantikan salinan") && id.contains("kunci perangkat lain") && id.contains("kode lama"))
        assertTrue(en.contains("replace the copy") && en.contains("other device") && en.contains("old code"))
    }
}
