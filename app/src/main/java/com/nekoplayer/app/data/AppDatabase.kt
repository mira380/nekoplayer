package com.nekoplayer.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.Closeable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// Keep the database format stable across app upgrades so users keep settings and dictionaries.
class AppDatabase(context: Context) : Closeable {
    val file: File = File(context.filesDir, DATABASE_NAME)
    val sql: SQLiteDatabase

    init {
        file.parentFile?.mkdirs()
        sql = SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READWRITE or
                SQLiteDatabase.CREATE_IF_NECESSARY or
                SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )
        sql.setForeignKeyConstraintsEnabled(true)
        ensureSchema()
    }

    private fun nowUtc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date())

    private fun ensureSchema() {
        sql.beginTransaction()
        try {
            sql.execSQL(
                "CREATE TABLE IF NOT EXISTS schema_migrations (" +
                    "version INTEGER NOT NULL PRIMARY KEY, name TEXT NOT NULL, applied_at TEXT NOT NULL)",
            )
            sql.execSQL(
                "CREATE TABLE IF NOT EXISTS app_settings (" +
                    "key TEXT NOT NULL PRIMARY KEY, value_json TEXT NOT NULL, updated_at TEXT NOT NULL)",
            )
            sql.delete("app_settings", "key = ?", arrayOf("mining.copyMinedSubtitlesToClipboard"))
            sql.execSQL(
                "CREATE TABLE IF NOT EXISTS dictionary_catalog (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT NOT NULL, version TEXT NOT NULL DEFAULT '', " +
                    "imported_at TEXT NOT NULL, entry_count INTEGER NOT NULL DEFAULT 0, " +
                    "is_ready INTEGER NOT NULL DEFAULT 0, sort_order INTEGER NOT NULL DEFAULT 0, " +
                    "kind TEXT NOT NULL DEFAULT 'term')",
            )
            ensureColumn("dictionary_catalog", "entry_count", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn("dictionary_catalog", "is_ready", "INTEGER NOT NULL DEFAULT 1")
            val hadPriority = hasColumn("dictionary_catalog", "sort_order")
            ensureColumn("dictionary_catalog", "sort_order", "INTEGER NOT NULL DEFAULT 0")
            ensureColumn("dictionary_catalog", "kind", "TEXT NOT NULL DEFAULT 'term'")
            if (!hadPriority) {
                sql.execSQL(
                    "UPDATE dictionary_catalog AS c SET sort_order = " +
                        "(SELECT COUNT(*) FROM dictionary_catalog AS o " +
                        "WHERE o.name COLLATE NOCASE < c.name COLLATE NOCASE " +
                        "OR (o.name COLLATE NOCASE = c.name COLLATE NOCASE AND o.id < c.id))",
                )
            }
            sql.execSQL(
                "CREATE TABLE IF NOT EXISTS dictionary_entries (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    "dictionary_id INTEGER NOT NULL, expression TEXT NOT NULL, " +
                    "reading TEXT NOT NULL DEFAULT '', score INTEGER NOT NULL DEFAULT 0, " +
                    "entry_json TEXT NOT NULL, FOREIGN KEY(dictionary_id) " +
                    "REFERENCES dictionary_catalog(id) ON DELETE CASCADE)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS dictionary_entries_expression_idx " +
                    "ON dictionary_entries(expression)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS dictionary_entries_reading_idx " +
                    "ON dictionary_entries(reading)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS dictionary_catalog_kind_idx " +
                    "ON dictionary_catalog(kind)",
            )
            sql.execSQL(
                "CREATE TABLE IF NOT EXISTS dictionary_meta (" +
                    "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, dictionary_id INTEGER NOT NULL, " +
                    "expression TEXT NOT NULL, reading TEXT NOT NULL DEFAULT '', mode TEXT NOT NULL, " +
                    "sort_value REAL NOT NULL DEFAULT 0, display TEXT, detail_json TEXT, " +
                    "FOREIGN KEY(dictionary_id) REFERENCES dictionary_catalog(id) ON DELETE CASCADE)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS dictionary_meta_expression_idx " +
                    "ON dictionary_meta(expression)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS dictionary_meta_reading_idx " +
                    "ON dictionary_meta(reading)",
            )
            sql.execSQL(
                "CREATE TABLE IF NOT EXISTS mining_drafts (" +
                    "id TEXT NOT NULL PRIMARY KEY, expression TEXT NOT NULL, reading TEXT, " +
                    "definitions_json TEXT, sentence TEXT NOT NULL, sentence_reading TEXT, " +
                    "translation TEXT, source_title TEXT, source_path TEXT, source_url TEXT, " +
                    "subtitle_track_id TEXT, subtitle_cue_id TEXT, start_ms INTEGER NOT NULL DEFAULT 0, " +
                    "end_ms INTEGER NOT NULL DEFAULT 0, screenshot_time_ms INTEGER NOT NULL DEFAULT 0, " +
                    "audio_path TEXT, screenshot_path TEXT, deck_name TEXT, note_type TEXT, " +
                    "field_values_json TEXT, tags_json TEXT, created_at TEXT NOT NULL, " +
                    "updated_at TEXT NOT NULL, export_status TEXT NOT NULL DEFAULT 'draft', " +
                    "anki_note_id INTEGER NOT NULL DEFAULT 0, last_export_error TEXT)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS mining_drafts_updated_at_idx " +
                    "ON mining_drafts(updated_at DESC)",
            )
            sql.execSQL(
                "CREATE INDEX IF NOT EXISTS mining_drafts_export_status_idx " +
                    "ON mining_drafts(export_status)",
            )
            val now = nowUtc()
            val migrationNames = listOf(
                "initial",
                "yomitan_dictionaries",
                "mining_drafts",
                "dictionary_priority",
                "dictionary_term_meta",
            )
            migrationNames.forEachIndexed { index, name ->
                val values = ContentValues().apply {
                    put("version", index + 1)
                    put("name", name)
                    put("applied_at", now)
                }
                sql.insertWithOnConflict(
                    "schema_migrations",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_IGNORE,
                )
            }
            sql.version = SCHEMA_VERSION
            sql.setTransactionSuccessful()
        } finally {
            sql.endTransaction()
        }
    }

    private fun hasColumn(table: String, column: String): Boolean {
        sql.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }

    private fun ensureColumn(table: String, column: String, declaration: String) {
        if (!hasColumn(table, column)) {
            sql.execSQL("ALTER TABLE $table ADD COLUMN $column $declaration")
        }
    }

    fun readSetting(key: String): Any? {
        require(key.isNotBlank())
        sql.rawQuery(
            "SELECT value_json FROM app_settings WHERE key = ? LIMIT 1",
            arrayOf(key),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return try {
                val value = JSONTokener(cursor.getString(0)).nextValue()
                if (value === JSONObject.NULL) null else value
            } catch (_: JSONException) {
                null
            }
        }
    }

    fun readString(key: String, fallback: String = ""): String =
        readSetting(key) as? String ?: fallback

    fun readNumber(key: String, fallback: Double = 0.0): Double = when (val value = readSetting(key)) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull() ?: fallback
        else -> fallback
    }

    fun readBoolean(key: String, fallback: Boolean = false): Boolean =
        readSetting(key) as? Boolean ?: fallback

    fun writeSetting(key: String, value: Any?) {
        require(key.isNotBlank())
        val encoded = encodeJson(value)
        val values = ContentValues().apply {
            put("key", key)
            put("value_json", encoded)
            put("updated_at", nowUtc())
        }
        sql.insertWithOnConflict(
            "app_settings",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    private fun encodeJson(value: Any?): String = when (value) {
        null -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        is JSONObject, is JSONArray -> value.toString()
        is Map<*, *> -> JSONObject(value).toString()
        is Iterable<*> -> JSONArray(value.toList()).toString()
        else -> JSONObject.quote(value.toString())
    }

    fun listRecent(): List<RecentMedia> {
        val stored = readSetting(RECENT_KEY) as? JSONArray ?: return emptyList()
        return buildList(minOf(stored.length(), RECENT_LIMIT)) {
            for (index in 0 until minOf(stored.length(), RECENT_LIMIT)) {
                val item = stored.optJSONObject(index) ?: continue
                val uri = item.optString("path").trim()
                if (uri.isEmpty()) continue
                add(
                    RecentMedia(
                        uri = uri,
                        displayName = item.optString("displayName", File(Uri.parse(uri).lastPathSegment ?: uri).name),
                        lastOpenedAt = item.optString("lastOpenedAt"),
                        positionMs = item.optLong("positionMs", 0L).coerceAtLeast(0L),
                        durationMs = item.optLong("durationMs", 0L).coerceAtLeast(0L),
                    ),
                )
            }
        }
    }

    @Synchronized
    fun recordOpened(uri: Uri, displayName: String) {
        val normalized = uri.normalizeScheme().toString()
        val item = RecentMedia(
            uri = normalized,
            displayName = displayName.ifBlank { File(uri.lastPathSegment ?: "Video").name },
            lastOpenedAt = nowUtc(),
        )
        val updated = buildList {
            add(item)
            listRecent().filterTo(this) { it.uri != normalized }
        }.take(RECENT_LIMIT)
        writeRecent(updated)
    }

    @Synchronized
    fun saveProgress(uri: String, positionMs: Long, durationMs: Long) {
        val old = listRecent()
        val existing = old.firstOrNull { it.uri == uri }
        val entry = (existing ?: RecentMedia(
            uri = uri,
            displayName = File(Uri.parse(uri).lastPathSegment ?: uri).name,
            lastOpenedAt = nowUtc(),
        )).copy(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = if (durationMs > 0L) durationMs else existing?.durationMs ?: 0L,
        )
        val updated = if (existing == null) {
            listOf(entry) + old
        } else {
            old.map { if (it.uri == uri) entry else it }
        }.take(RECENT_LIMIT)
        writeRecent(updated)
    }

    private fun writeRecent(items: List<RecentMedia>) {
        writeSetting(RECENT_KEY, JSONArray().apply { items.forEach { put(it.toJson()) } })
    }

    override fun close() {
        sql.close()
    }

    data class RecentMedia(
        val uri: String,
        val displayName: String,
        val lastOpenedAt: String,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    ) {
        fun toJson() = JSONObject().apply {
            put("path", uri)
            put("displayName", displayName)
            put("lastOpenedAt", lastOpenedAt)
            put("positionMs", positionMs)
            put("durationMs", durationMs)
        }
    }

    companion object {
        const val DATABASE_NAME = "app.db"
        const val SCHEMA_VERSION = 5
        const val RECENT_KEY = "recent_media"
        const val RECENT_LIMIT = 20
    }
}
