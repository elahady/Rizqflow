package com.roziqrizal.rizqflow.domain.sync

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiAccountTest {
    @Test
    fun `toString tidak pernah mencetak token API`() {
        val akun = ApiAccount(apiToken = "rahasia-token-123", userId = 7, name = "Roziq", email = "roziq@example.com", expiresAt = Instant.parse("2027-01-22T10:00:00Z"))

        val teks = akun.toString()

        assertFalse(teks.contains("rahasia-token-123"))
        assertTrue(teks.contains("roziq@example.com"))
    }

    @Test
    fun `kesetaraan tetap memakai token seperti data class biasa`() {
        val a = ApiAccount("t", 1, null, "a@b.c")
        assertEquals(a, ApiAccount("t", 1, null, "a@b.c"))
        assertFalse(a == ApiAccount("lain", 1, null, "a@b.c"))
    }
}
