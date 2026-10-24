package com.roziqrizal.rizqflow.data.remote

import com.roziqrizal.rizqflow.data.remote.ApiBaseUrl.Reason
import com.roziqrizal.rizqflow.data.remote.ApiBaseUrl.Validation
import org.junit.Test
import kotlin.test.assertEquals

/** Pemeriksaan murni alamat API: tanpa Android, jaringan, maupun Gradle. */
class ApiBaseUrlTest {
    private fun release(raw: String?) = ApiBaseUrl.validate(raw, allowCleartext = false)

    private fun debug(raw: String?) = ApiBaseUrl.validate(raw, allowCleartext = true)

    private fun invalid(reason: Reason) = Validation.Invalid(reason)

    // --- Yang sah ---

    @Test
    fun `alamat https berakhiran garis miring sah di rilis maupun debug`() {
        val url = "https://rizqflow-api.roziqrizal.com/api/"

        assertEquals(Validation.Valid(url), release(url))
        assertEquals(Validation.Valid(url), debug(url))
    }

    @Test
    fun `alamat https dengan port dan jalur lain sah`() {
        assertEquals(Validation.Valid("https://api.contoh.id:8443/v1/api/"), release("https://api.contoh.id:8443/v1/api/"))
    }

    @Test
    fun `spasi di tepi dipangkas`() {
        assertEquals(Validation.Valid("https://api.contoh.id/api/"), release("  https://api.contoh.id/api/ \n"))
    }

    @Test
    fun `skema ditulis huruf besar tetap dikenali`() {
        assertEquals(Validation.Valid("HTTPS://api.contoh.id/api/"), release("HTTPS://api.contoh.id/api/"))
    }

    // --- Cleartext: hanya debug ---

    @Test
    fun `http sah di debug untuk emulator, LAN, dan adb reverse`() {
        assertEquals(Validation.Valid("http://10.0.2.2:8000/api/"), debug("http://10.0.2.2:8000/api/"))
        assertEquals(Validation.Valid("http://192.168.1.20:8000/api/"), debug("http://192.168.1.20:8000/api/"))
        assertEquals(Validation.Valid("http://localhost:8000/api/"), debug("http://localhost:8000/api/"))
    }

    @Test
    fun `http ditolak di rilis apa pun hostnya`() {
        assertEquals(invalid(Reason.CLEARTEXT_NOT_ALLOWED), release("http://10.0.2.2:8000/api/"))
        assertEquals(invalid(Reason.CLEARTEXT_NOT_ALLOWED), release("http://rizqflow-api.roziqrizal.com/api/"))
        assertEquals(invalid(Reason.CLEARTEXT_NOT_ALLOWED), release("HTTP://localhost/api/"))
    }

    // --- Garis miring ---

    @Test
    fun `tanpa garis miring di akhir ditolak karena Retrofit mewajibkannya`() {
        assertEquals(invalid(Reason.MISSING_TRAILING_SLASH), release("https://rizqflow-api.roziqrizal.com/api"))
        assertEquals(invalid(Reason.MISSING_TRAILING_SLASH), debug("http://10.0.2.2:8000/api"))
        assertEquals(invalid(Reason.MISSING_TRAILING_SLASH), release("https://rizqflow-api.roziqrizal.com"))
    }

    // --- Kosong, host, skema aneh ---

    @Test
    fun `kosong atau hanya spasi atau null ditolak`() {
        assertEquals(invalid(Reason.BLANK), release(null))
        assertEquals(invalid(Reason.BLANK), release(""))
        assertEquals(invalid(Reason.BLANK), debug("   \t"))
    }

    @Test
    fun `host kosong ditolak`() {
        assertEquals(invalid(Reason.MISSING_HOST), release("https:///api/"))
        assertEquals(invalid(Reason.MISSING_HOST), debug("http:///api/"))
    }

    @Test
    fun `host yang tidak terbaca ditolak apa pun alasannya`() {
        // Java mengurai "https://:8000/api/" sebagai tanpa host atau sebagai URL cacat; keduanya tidak boleh lolos.
        val result = release("https://:8000/api/")
        assertEquals(true, result is Validation.Invalid, "alamat tanpa host tidak boleh sah: $result")
    }

    @Test
    fun `skema selain http dan https ditolak`() {
        assertEquals(invalid(Reason.UNSUPPORTED_SCHEME), release("ftp://api.contoh.id/api/"))
        assertEquals(invalid(Reason.UNSUPPORTED_SCHEME), debug("file:///sdcard/api/"))
        assertEquals(invalid(Reason.UNSUPPORTED_SCHEME), debug("javascript:alert(1)//"))
        assertEquals(invalid(Reason.UNSUPPORTED_SCHEME), release("ws://api.contoh.id/api/"))
    }

    @Test
    fun `tanpa skema ditolak`() {
        assertEquals(invalid(Reason.UNSUPPORTED_SCHEME), release("rizqflow-api.roziqrizal.com/api/"))
        assertEquals(invalid(Reason.UNSUPPORTED_SCHEME), release("//rizqflow-api.roziqrizal.com/api/"))
    }

    @Test
    fun `karakter terlarang atau spasi di tengah ditolak sebagai cacat`() {
        assertEquals(invalid(Reason.MALFORMED), release("https://api contoh.id/api/"))
        assertEquals(invalid(Reason.MALFORMED), release("https://api.contoh.id/ap\"i/"))
    }

    @Test
    fun `nama pengguna atau sandi di dalam alamat ditolak`() {
        assertEquals(invalid(Reason.CREDENTIALS_IN_URL), release("https://admin:rahasia@api.contoh.id/api/"))
        assertEquals(invalid(Reason.CREDENTIALS_IN_URL), debug("http://admin@192.168.1.20:8000/api/"))
    }

    // --- resolve: tidak pernah melempar dan tidak pernah menyambung ke alamat yang tidak dimaksud ---

    @Test
    fun `resolve mengembalikan alamat sah apa adanya tanpa memanggil onInvalid`() {
        var reported: Reason? = null

        val url = ApiBaseUrl.resolve("https://api.contoh.id/api/", allowCleartext = false) { reported = it }

        assertEquals("https://api.contoh.id/api/", url)
        assertEquals(null, reported)
    }

    @Test
    fun `resolve jatuh ke alamat yang tidak pernah terjangkau dan melaporkan alasannya`() {
        var reported: Reason? = null

        val url = ApiBaseUrl.resolve("http://10.0.2.2:8000/api/", allowCleartext = false) { reported = it }

        assertEquals(ApiBaseUrl.UNREACHABLE, url)
        assertEquals(Reason.CLEARTEXT_NOT_ALLOWED, reported)
    }

    @Test
    fun `alamat pengganti sendiri sah, https, dan memakai TLD invalid yang tidak pernah diselesaikan DNS`() {
        assertEquals(Validation.Valid(ApiBaseUrl.UNREACHABLE), release(ApiBaseUrl.UNREACHABLE))
        assertEquals(true, ApiBaseUrl.UNREACHABLE.startsWith("https://"))
        assertEquals(true, java.net.URI(ApiBaseUrl.UNREACHABLE).host.endsWith(".invalid"))
    }

    @Test
    fun `retrofit menerima setiap alamat yang lolos validasi`() {
        val urls = listOf(
            "https://rizqflow-api.roziqrizal.com/api/",
            "https://api.contoh.id:8443/v1/api/",
            ApiBaseUrl.UNREACHABLE,
        )
        for (url in urls) {
            // RizqflowApiClient.create melempar IllegalArgumentException untuk alamat yang ditolak Retrofit.
            RizqflowApiClient.create(url)
        }
        RizqflowApiClient.create("http://10.0.2.2:8000/api/")
    }
}
