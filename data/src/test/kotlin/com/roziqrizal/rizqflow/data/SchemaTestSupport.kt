package com.roziqrizal.rizqflow.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** Alat bersama tes yang membangun dan memeriksa berkas SQLite dari berkas skema JSON Room yang di-commit. */
internal object SchemaTestSupport {
    const val SCHEMA_DIR = "com.roziqrizal.rizqflow.data.db.RizqflowDatabase"

    /** Membangun berkas versi [version] apa adanya dari `createSql` dan `indices` di `<version>.json`, lalu menandai user_version. */
    fun buildFromSchema(context: Context, version: Int, path: String): SQLiteDatabase {
        val schema = JSONObject(
            context.assets.open("$SCHEMA_DIR/$version.json").use { it.reader(Charsets.UTF_8).readText() },
        ).getJSONObject("database")
        val entities = schema.getJSONArray("entities")
        val raw = SQLiteDatabase.openOrCreateDatabase(path, null)
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val tableName = entity.getString("tableName")
            raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", tableName))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                raw.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tableName))
            }
        }
        raw.version = version
        return raw
    }

    /** Nama tabel data (tanpa tabel sistem SQLite/Room). */
    fun dataTables(query: (String) -> Cursor): List<String> = query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

    fun count(query: (String) -> Cursor, table: String): Long =
        query("SELECT COUNT(*) FROM `$table`").use { it.moveToFirst(); it.getLong(0) }

    fun columns(query: (String) -> Cursor, table: String): List<String> =
        query("PRAGMA table_info(`$table`)").use { c -> buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }

    /** Sidik jari isi tabel pada kolom [columns]: seluruh baris berurutan rowid, nilai demi nilai, termasuk tipe dan NULL. */
    fun fingerprint(query: (String) -> Cursor, table: String, columns: List<String>): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        query("SELECT ${columns.joinToString(",") { "`$it`" }} FROM `$table` ORDER BY rowid").use { c ->
            while (c.moveToNext()) {
                val row = StringBuilder()
                for (i in 0 until c.columnCount) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> row.append("N")
                        Cursor.FIELD_TYPE_INTEGER -> row.append("I").append(c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> row.append("F").append(c.getDouble(i))
                        Cursor.FIELD_TYPE_BLOB -> row.append("B").append(c.getBlob(i).contentToString())
                        else -> row.append("S").append(c.getString(i))
                    }
                    row.append('\u0001')
                }
                digest.update(row.append('\n').toString().toByteArray(Charsets.UTF_8))
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Gambaran struktur skema untuk dibandingkan antar-berkas: objek sqlite_master (SQL dengan spasi dirapatkan),
     * kolom, kunci asing, dan indeks tiap tabel. Urutan tidak berpengaruh.
     */
    fun structure(query: (String) -> Cursor): Map<String, String> {
        val result = sortedMapOf<String, String>()
        query("SELECT type, name, sql FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT IN ('room_master_table', 'android_metadata')").use { c ->
            while (c.moveToNext()) {
                result["${c.getString(0)}:${c.getString(1)}"] = (c.getString(2) ?: "").replace(Regex("\\s+"), " ").trim()
            }
        }
        for (table in dataTables(query)) {
            result["columns:$table"] = query("PRAGMA table_info(`$table`)").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add("${c.getString(1)}|${c.getString(2)}|nn=${c.getInt(3)}|default=${c.getString(4)}|pk=${c.getInt(5)}")
                    }
                }.joinToString(";")
            }
            result["fk:$table"] = query("PRAGMA foreign_key_list(`$table`)").use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add("${c.getString(2)}.${c.getString(3)}->${c.getString(4)}|upd=${c.getString(5)}|del=${c.getString(6)}")
                    }
                }.sorted().joinToString(";")
            }
            result["indexes:$table"] = query("PRAGMA index_list(`$table`)").use { c ->
                buildList {
                    while (c.moveToNext()) add("${c.getString(1)}|unique=${c.getInt(2)}|origin=${c.getString(3)}")
                }.sorted().joinToString(";")
            }
        }
        return result
    }
}
