package com.roziqrizal.rizqflow.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Susunan nama perangkat yang dikirim ke server (Tahap 14): pabrikan tidak diulang, kosong jadi null, dipotong 100. */
class DeviceNameTest {
    @Test
    fun `pabrikan dan model digabung dengan huruf pertama besar`() {
        assertEquals("Google Pixel 8", format(manufacturer = "google", model = "Pixel 8"))
        assertEquals("Samsung SM-S911B", format(manufacturer = "samsung", model = "SM-S911B"))
    }

    @Test
    fun `model yang sudah diawali pabrikan tidak mengulang pabrikan`() {
        assertEquals("Samsung SM-S911B", format(manufacturer = "samsung", model = "Samsung SM-S911B"))
    }

    @Test
    fun `hanya model bila pabrikan kosong, dan hanya pabrikan bila model kosong`() {
        assertEquals("Pixel 8", format(manufacturer = " ", model = "Pixel 8"))
        assertEquals("Google", format(manufacturer = "Google", model = "  "))
    }

    @Test
    fun `tanpa pabrikan maupun model hasilnya null`() {
        assertNull(format(manufacturer = null, model = null))
        assertNull(format(manufacturer = "", model = " "))
    }

    @Test
    fun `nama dipotong sampai 100 karakter sesuai batas server`() {
        val long = "x".repeat(150)

        val result = format(manufacturer = null, model = long)

        assertEquals(DeviceName.MAX_LENGTH, result?.length)
        assertEquals("X" + "x".repeat(99), result)
    }
}
