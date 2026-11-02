package com.roziqrizal.rizqflow.data.crypto

import com.google.crypto.tink.proto.Keyset

/**
 * Menggabung dua keyset kunci ruang menjadi satu (key_grant langkah 3, sync-desain.md bagian F butir 5): gabungan
 * kunci menurut id kunci, dengan kunci PRIMARY (yang dipakai mengenkripsi berikutnya) mengikuti keyset yang
 * diimpor. Kunci milik [existing] yang tidak ada di [imported] tetap ikut sebagai kunci non-primary, jadi
 * ciphertext lama yang dienkripsi dengan kunci itu tetap terbaca (Tink mencoba semua kunci di keyset menurut id
 * di awal ciphertext). Dua keyset dengan id kunci yang sama dianggap kunci yang sama: id kunci Tink acak 32-bit,
 * dan keyset ruang ini hanya berasal dari satu garis keturunan (kunci awal pemilik dan rotasinya).
 *
 * Mengapa bukan menimpa: anggota yang menerima keyset hasil rotasi lewat `key_grant` tidak boleh kehilangan kunci
 * lama, karena baris ruang lama yang belum ditarik masih terenkripsi dengan kunci itu. Penggabungan juga membuat
 * mengimpor keyset yang sama dua kali (memindai ulang QR) tidak mengubah apa pun.
 *
 * Operasi murni atas byte keyset terserialisasi (`TinkProtoKeysetFormat.serializeKeyset`); memverifikasi bahwa
 * [imported] keyset Tink yang sah adalah tugas pemanggil (`TinkProtoKeysetFormat.parseKeyset`).
 */
internal object RoomKeysetMerger {
    fun merge(existing: ByteArray?, imported: ByteArray): ByteArray {
        if (existing == null) return imported
        val importedKeyset = Keyset.parseFrom(imported)
        val importedIds = importedKeyset.keyList.map { it.keyId }.toSet()
        val merged = Keyset.newBuilder(importedKeyset)
        // Urutan: kunci yang diimpor lebih dulu, lalu kunci lama yang tidak ada di sana. primary_key_id tetap
        // milik keyset yang diimpor (Keyset.newBuilder(prototype) menyalinnya).
        existing.let(Keyset::parseFrom).keyList
            .filter { it.keyId !in importedIds }
            .forEach(merged::addKey)
        return merged.build().toByteArray()
    }
}
