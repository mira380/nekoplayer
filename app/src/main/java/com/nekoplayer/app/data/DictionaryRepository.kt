package com.nekoplayer.app.data

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipFile

data class ImportedDictionary(
    val id: Long,
    val name: String,
    val version: String,
    val entryCount: Int,
    val importedAt: String,
    val sortOrder: Int,
    val kind: String,
)

data class DictionaryEntry(
    val id: Long,
    val dictionaryId: Long,
    val dictionaryName: String,
    val expression: String,
    val reading: String,
    val definitionTags: List<String>?,
    val rules: List<String>?,
    val score: Int,
    val sequence: Int,
    val termTags: List<String>?,
    val glossary: List<Any?>,
    // Keep the normalized source row so renderers can use fields we don't map yet.
    val entryJson: String,
)

data class DictionaryTermMeta(
    val expression: String,
    val reading: String,
    val mode: String,
    val sortValue: Double,
    val display: String?,
    val detailJson: String?,
    val dictionaryName: String,
)

// AppDatabase creates the dictionary tables. Imports stream and batch rows, so call them from IO.
class DictionaryRepository(
    private val database: SQLiteDatabase,
    private val contentResolver: ContentResolver,
    private val importDirectory: File,
) {
    companion object {
        private const val BATCH_SIZE = 256
        private const val EXACT_CHUNK_SIZE = 400
        private const val MAX_SEARCH_LIMIT = 100
        private const val MAX_EXACT_LIMIT = 1_000
        private const val INDEX_MAX_BYTES = 2L * 1024L * 1024L
        private val TERM_BANK = Regex("^term_bank_\\d+\\.json$")
        private val META_BANK = Regex("^term_meta_bank_\\d+\\.json$")
        private val KANJI_BANK = Regex("^kanji_bank_\\d+\\.json$")
        private val META_MODES = setOf("freq", "pitch", "ipa")
    }

    private data class MetaRow(
        val expression: String,
        val reading: String,
        val mode: String,
        val sortValue: Double,
        val display: String?,
        val detailJson: String?,
    )

    private data class StagedDictionary(
        val id: Long,
        val name: String,
        var version: String = "",
        var entryCount: Int = 0,
        var metaCount: Int = 0,
        var kind: String = "term",
    )

    fun importYomitanFile(uri: Uri) {
        importArchive(uri)
    }

    fun listDictionaries(): List<ImportedDictionary> {
        val result = mutableListOf<ImportedDictionary>()
        database.rawQuery(
            "SELECT id,name,version,entry_count,imported_at,sort_order,kind " +
                "FROM dictionary_catalog WHERE is_ready=1 " +
                "ORDER BY sort_order ASC,name COLLATE NOCASE ASC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) result += ImportedDictionary(
                id = cursor.getLong(0), name = cursor.getString(1),
                version = cursor.getString(2), entryCount = cursor.getInt(3),
                importedAt = cursor.getString(4), sortOrder = cursor.getInt(5),
                kind = cursor.getString(6),
            )
        }
        return result
    }

    // Requested dictionaries come first; the rest keep their existing order.
    fun reorderDictionaries(orderedIds: List<Long>) {
        require(orderedIds.distinct().size == orderedIds.size) { "Repeated dictionary id" }
        database.beginTransaction()
        try {
            val known = mutableListOf<Long>()
            database.rawQuery("SELECT id FROM dictionary_catalog ORDER BY sort_order,name COLLATE NOCASE,id", null)
                .use { cursor -> while (cursor.moveToNext()) known += cursor.getLong(0) }
            val validRequested = orderedIds.filter { it in known }
            val order = validRequested + known.filterNot { it in validRequested }
            database.compileStatement("UPDATE dictionary_catalog SET sort_order=? WHERE id=?").use { statement ->
                order.forEachIndexed { index, id ->
                    statement.clearBindings(); statement.bindLong(1, index.toLong()); statement.bindLong(2, id)
                    statement.executeUpdateDelete()
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    fun deleteDictionary(id: Long) {
        database.beginTransaction()
        try {
            database.delete("dictionary_entries", "dictionary_id=?", arrayOf(id.toString()))
            database.delete("dictionary_meta", "dictionary_id=?", arrayOf(id.toString()))
            database.delete("dictionary_catalog", "id=?", arrayOf(id.toString()))
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    // Exact matches rank ahead of prefixes, followed by dictionary priority and source score.
    fun search(query: String, limit: Int = 50): List<DictionaryEntry> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val prefix = escapeLike(term) + "%"
        val rows = queryEntries(
            """SELECT e.id,e.dictionary_id,e.entry_json,d.name
               FROM dictionary_entries e JOIN dictionary_catalog d ON d.id=e.dictionary_id
               WHERE d.is_ready=1 AND (e.expression=? OR e.reading=? OR
                 e.expression LIKE ? ESCAPE '\' OR e.reading LIKE ? ESCAPE '\')
               ORDER BY CASE WHEN e.expression=? THEN 0 WHEN e.reading=? THEN 1
                 WHEN e.expression LIKE ? ESCAPE '\' THEN 2 ELSE 3 END,
                 d.sort_order ASC,e.score DESC,e.id ASC LIMIT ?""",
            arrayOf(term, term, prefix, prefix, term, term, prefix, safeLimit.toString()),
        )
        return if (rows.isNotEmpty()) rows else searchEmbedded(term, safeLimit)
    }

    // Dictionary priority comes before source score.
    fun lookupExactTerms(terms: Set<String>, limit: Int = 200): List<DictionaryEntry> {
        val candidates = terms.filter(String::isNotEmpty).distinct()
        if (candidates.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_EXACT_LIMIT)
        val found = LinkedHashMap<String, Pair<DictionaryEntry, Int>>()
        candidates.chunked(EXACT_CHUNK_SIZE).forEach { chunk ->
            val marks = List(chunk.size) { "?" }.joinToString(",")
            val args = chunk + chunk + safeLimit.toString()
            database.rawQuery(
                """SELECT e.id,e.dictionary_id,e.entry_json,d.name,d.sort_order
                   FROM dictionary_entries e JOIN dictionary_catalog d ON d.id=e.dictionary_id
                   WHERE d.is_ready=1 AND (e.expression IN ($marks) OR e.reading IN ($marks))
                   ORDER BY d.sort_order ASC,e.score DESC,e.id ASC LIMIT ?""",
                args.toTypedArray(),
            ).use { cursor -> while (cursor.moveToNext()) {
                val entry = cursorEntry(cursor)
                found.putIfAbsent("${entry.dictionaryId}:${entry.id}", entry to cursor.getInt(4))
            } }
        }
        return found.values.sortedWith(
            compareBy<Pair<DictionaryEntry, Int>> { it.second }
                .thenByDescending { it.first.score }.thenBy { it.first.id },
        ).take(safeLimit).map { it.first }
    }

    // Match either expression or reading, then order by priority, source rank, and row id.
    fun termMeta(expressions: Set<String>): List<DictionaryTermMeta> {
        val candidates = expressions.filter(String::isNotEmpty).distinct()
        if (candidates.isEmpty()) return emptyList()
        data class Ranked(val id: Long, val priority: Int, val row: DictionaryTermMeta)
        val found = LinkedHashMap<Long, Ranked>()
        candidates.chunked(EXACT_CHUNK_SIZE).forEach { chunk ->
            val marks = List(chunk.size) { "?" }.joinToString(",")
            database.rawQuery(
                """SELECT m.id,m.expression,m.reading,m.mode,m.sort_value,m.display,m.detail_json,
                          d.name,d.sort_order
                   FROM dictionary_meta m JOIN dictionary_catalog d ON d.id=m.dictionary_id
                   WHERE d.is_ready=1 AND (m.expression IN ($marks) OR m.reading IN ($marks))
                   ORDER BY d.sort_order ASC,m.sort_value ASC,m.id ASC""",
                (chunk + chunk).toTypedArray(),
            ).use { cursor -> while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                found.putIfAbsent(id, Ranked(id, cursor.getInt(8), DictionaryTermMeta(
                    expression = cursor.getString(1), reading = cursor.getString(2),
                    mode = cursor.getString(3), sortValue = cursor.getDouble(4),
                    display = cursor.nullableString(5), detailJson = cursor.nullableString(6),
                    dictionaryName = cursor.getString(7),
                )))
            } }
        }
        return found.values.sortedWith(compareBy<Ranked> { it.priority }.thenBy { it.row.sortValue }.thenBy { it.id })
            .map { it.row }
    }

    private fun queryEntries(sql: String, args: Array<String>): List<DictionaryEntry> {
        val result = mutableListOf<DictionaryEntry>()
        database.rawQuery(sql, args).use { cursor -> while (cursor.moveToNext()) result += cursorEntry(cursor) }
        return result
    }

    private fun cursorEntry(cursor: android.database.Cursor): DictionaryEntry {
        val id = cursor.getLong(0); val dictionaryId = cursor.getLong(1); val raw = cursor.getString(2)
        val row = JSONArray(raw)
        val versionThree = row.length() >= 8 && row.opt(6) is Number
        val glossary = if (versionThree) row.optJSONArray(5) else JSONArray().apply {
            for (i in 5 until row.length()) put(row.opt(i))
        }
        return DictionaryEntry(
            id, dictionaryId, cursor.getString(3), row.optString(0), row.optString(1),
            parseTags(row.opt(2)), parseTags(row.opt(3)), row.optInt(4),
            if (versionThree) row.optInt(6) else 0,
            if (versionThree) parseTags(row.opt(7)) else null,
            (0 until (glossary?.length() ?: 0)).map { glossary!!.opt(it) }, raw,
        )
    }

    private fun searchEmbedded(query: String, limit: Int): List<DictionaryEntry> {
        val runes = mutableListOf<Int>()
        var offset = 0
        while (offset < query.length && runes.size < 40) {
            val codePoint = query.codePointAt(offset)
            runes += codePoint
            offset += Character.charCount(codePoint)
        }
        val points = runes.toIntArray()
        if (points.size < 2) return emptyList()
        val candidates = LinkedHashSet<String>()
        loop@ for (start in points.indices) {
            val maxLength = minOf(points.size - start, 12)
            for (length in 2..maxLength) {
                val candidate = String(points, start, length)
                if (candidate.any(Char::isWhitespace)) continue
                candidates += candidate
                if (candidates.size >= 400) break@loop
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val values = candidates.toList()
        val marks = List(values.size) { "?" }.joinToString(",")
        return queryEntries(
            """SELECT e.id,e.dictionary_id,e.entry_json,d.name
               FROM dictionary_entries e JOIN dictionary_catalog d ON d.id=e.dictionary_id
               WHERE d.is_ready=1 AND (e.expression IN ($marks) OR e.reading IN ($marks))
               ORDER BY length(e.expression) DESC,e.score DESC,e.id ASC LIMIT ?""",
            (values + values + limit.toString()).toTypedArray(),
        )
    }

    private fun importArchive(uri: Uri) {
        val archiveFile = copyToTemp(uri, ".zip")
        var staged: StagedDictionary? = null
        try {
            ZipFile(archiveFile).use { zip ->
                val index = zip.entries().asSequence().firstOrNull { !it.isDirectory && basename(it.name) == "index.json" }
                    ?: throw IllegalArgumentException("The ZIP has no Yomitan index.json")
                require(index.size < 0 || index.size <= INDEX_MAX_BYTES) { "The Yomitan index is too large" }
                val metadata = zip.getInputStream(index).use { input ->
                    val text = input.readBytesBounded(INDEX_MAX_BYTES).toString(StandardCharsets.UTF_8)
                    JSONObject(text)
                }
                val name = (metadata.optString("title").ifBlank { metadata.optString("name") }).trim()
                require(name.isNotEmpty()) { "The Yomitan index has no dictionary name" }
                val version = metadata.optString("revision").ifBlank { metadata.optString("version") }
                val termBanks = zip.entries().asSequence().filter { !it.isDirectory && TERM_BANK.matches(basename(it.name)) }
                    .map { it.name }.sortedBy(::bankNumber).toList()
                val metaBanks = zip.entries().asSequence().filter { !it.isDirectory && META_BANK.matches(basename(it.name)) }
                    .map { it.name }.sortedBy(::bankNumber).toList()
                val kanji = zip.entries().asSequence().any { !it.isDirectory && KANJI_BANK.matches(basename(it.name)) }
                require(termBanks.isNotEmpty() || metaBanks.isNotEmpty()) {
                    if (kanji) "$name is a kanji dictionary; kanji dictionaries are unsupported" else "The ZIP has no term or term-meta banks"
                }
                val kind = if (termBanks.isEmpty()) "meta" else "term"
                val target = stage(name, version, kind)
                staged = target
                for (bank in termBanks) {
                    zip.getInputStream(zip.getEntry(bank)).use { stream ->
                        target.entryCount += readTermBank(stream, target.id)
                    }
                }
                require(termBanks.isEmpty() || target.entryCount > 0) { "The archive contains no usable terms" }
                for (bank in metaBanks) {
                    zip.getInputStream(zip.getEntry(bank)).use { stream ->
                        target.metaCount += readMetaBank(stream, target.id)
                    }
                }
                require(termBanks.isNotEmpty() || target.metaCount > 0) { "The archive contains no usable term-meta rows" }
                target.entryCount = if (termBanks.isEmpty()) target.metaCount else target.entryCount
                publish(listOf(target))
            }
        } catch (error: Throwable) {
            staged?.let(::discard)
            throw error
        } finally { archiveFile.delete() }
    }

    private fun readTermBank(input: InputStream, dictionaryId: Long): Int {
        val statement = database.compileStatement("INSERT INTO dictionary_entries(dictionary_id,expression,reading,score,entry_json) VALUES(?,?,?,?,?)")
        val consumer = NativeTermBankBatchConsumer { expressions, readings, scores, rawRows ->
            require(expressions.size == readings.size && expressions.size == scores.size && expressions.size == rawRows.size) {
                "The native dictionary importer returned an incomplete batch"
            }
            database.beginTransaction()
            try {
                expressions.indices.forEach { index ->
                    bindEntry(statement, dictionaryId, expressions[index], readings[index], scores[index], rawRows[index])
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
        return try {
            NativeYomitanTermBank.parseTermBank(input, consumer)
        } finally {
            statement.close()
        }
    }

    private fun readMetaBank(input: InputStream, dictionaryId: Long): Int {
        val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
        var count = 0; var batch = 0
        val statement = database.compileStatement("INSERT INTO dictionary_meta(dictionary_id,expression,reading,mode,sort_value,display,detail_json) VALUES(?,?,?,?,?,?,?)")
        val pending = mutableListOf<MetaRow>()
        fun flush() {
            if (pending.isEmpty()) return
            database.beginTransaction()
            try { pending.forEach { insertMeta(statement, dictionaryId, it) }; database.setTransactionSuccessful() }
            finally { database.endTransaction(); pending.clear() }
        }
        reader.use {
            it.beginArray()
            while (it.hasNext()) {
                val value = readValue(it) as? List<*> ?: continue
                if (value.size != 3) continue
                val expression = value[0] as? String ?: continue
                val mode = value[1] as? String ?: continue
                val meta = normalizeMeta(expression, mode, value[2]) ?: continue
                pending += meta; count++; batch++
                if (batch >= BATCH_SIZE) { flush(); batch = 0 }
            }
            it.endArray()
        }
        flush(); statement.close()
        return count
    }

    private fun normalizeMeta(expression: String, mode: String, data: Any?): MetaRow? {
        val normalizedMode = mode.trim().lowercase(Locale.ROOT)
        if (expression.isEmpty() || normalizedMode !in META_MODES) return null
        val objectData = data as? Map<*, *> ?: emptyMap<Any?, Any?>()
        if (normalizedMode == "freq") {
            val reading = objectData["reading"] as? String ?: ""
            val payload = if (objectData.containsKey("reading")) objectData["frequency"] else data
            val payloadMap = payload as? Map<*, *>
            val rawValue = if (payloadMap != null) payloadMap["value"] else payload
            val value = when (rawValue) { is Number -> rawValue.toDouble(); is String -> rawValue.toDoubleOrNull(); else -> null } ?: return null
            return MetaRow(expression, reading, normalizedMode, value, payloadMap?.get("displayValue") as? String, null)
        }
        val reading = objectData["reading"] as? String ?: ""
        val arrayName = if (normalizedMode == "pitch") "pitches" else "transcriptions"
        val details = objectData[arrayName] as? List<*> ?: data as? List<*>
        return MetaRow(expression, reading, normalizedMode, 0.0, null, details?.toJsonArray()?.toString())
    }

    private fun publish(staged: List<StagedDictionary>) {
        database.beginTransaction()
        try {
            staged.forEach { item ->
                database.delete("dictionary_meta", "dictionary_id IN (SELECT id FROM dictionary_catalog WHERE name=? AND id!=?)", arrayOf(item.name, item.id.toString()))
                database.delete("dictionary_entries", "dictionary_id IN (SELECT id FROM dictionary_catalog WHERE name=? AND id!=?)", arrayOf(item.name, item.id.toString()))
                database.delete("dictionary_catalog", "name=? AND id!=?", arrayOf(item.name, item.id.toString()))
                database.execSQL("UPDATE dictionary_catalog SET version=?,entry_count=?,is_ready=1,kind=?,imported_at=? WHERE id=?",
                    arrayOf<Any?>(item.version, item.entryCount, item.kind, nowUtc(), item.id))
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
    }

    private fun stage(name: String, version: String, kind: String): StagedDictionary {
        val existing = database.rawQuery("SELECT sort_order FROM dictionary_catalog WHERE name=? LIMIT 1", arrayOf(name)).use { if (it.moveToFirst()) it.getInt(0) else null }
        val next = existing ?: database.rawQuery("SELECT COALESCE(MAX(sort_order),-1)+1 FROM dictionary_catalog", null).use { it.moveToFirst(); it.getInt(0) }
        val id = database.insertOrThrow("dictionary_catalog", null, android.content.ContentValues().apply {
            put("name", name); put("version", version); put("imported_at", nowUtc()); put("entry_count", 0)
            put("is_ready", 0); put("sort_order", next); put("kind", kind)
        })
        return StagedDictionary(id, name, version, kind = kind)
    }

    private fun discard(item: StagedDictionary) {
        database.delete("dictionary_entries", "dictionary_id=?", arrayOf(item.id.toString()))
        database.delete("dictionary_meta", "dictionary_id=?", arrayOf(item.id.toString()))
        database.delete("dictionary_catalog", "id=?", arrayOf(item.id.toString()))
    }

    private fun bindEntry(statement: SQLiteStatement, id: Long, expression: String, reading: String, score: Int, raw: String) {
        statement.clearBindings(); statement.bindLong(1, id); statement.bindString(2, expression); statement.bindString(3, reading)
        statement.bindLong(4, score.toLong()); statement.bindString(5, raw); statement.executeInsert()
    }
    private fun insertMeta(statement: SQLiteStatement, id: Long, meta: MetaRow) {
        statement.clearBindings(); statement.bindLong(1, id); statement.bindString(2, meta.expression); statement.bindString(3, meta.reading)
        statement.bindString(4, meta.mode); statement.bindDouble(5, meta.sortValue)
        if (meta.display == null) statement.bindNull(6) else statement.bindString(6, meta.display)
        if (meta.detailJson == null) statement.bindNull(7) else statement.bindString(7, meta.detailJson)
        statement.executeInsert()
    }

    private fun copyToTemp(uri: Uri, suffix: String): File {
        importDirectory.mkdirs()
        val file = File.createTempFile("yomitan-", suffix, importDirectory)
        try {
            val input = contentResolver.openInputStream(uri) ?: error("The selected file is unavailable")
            input.use { source -> file.outputStream().use { target -> source.copyTo(target, 64 * 1024) } }
            return file
        } catch (error: Throwable) { file.delete(); throw error }
    }

    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            val values = mutableListOf<Any?>()
            while (reader.hasNext()) values += readValue(reader)
            reader.endArray()
            values
        }
        JsonToken.BEGIN_OBJECT -> {
            reader.beginObject()
            val values = LinkedHashMap<String, Any?>()
            while (reader.hasNext()) values[reader.nextName()] = readValue(reader)
            reader.endObject()
            values
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull() ?: 0 }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> throw IllegalArgumentException("Malformed JSON value")
    }

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    private fun basename(path: String) = path.substringAfterLast('/')
    private fun bankNumber(path: String) = Regex("\\d+").find(basename(path))?.value?.toIntOrNull() ?: 0
    private fun nowUtc() = java.time.Instant.now().toString()
    private fun android.database.Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)
    private fun parseTags(value: Any?): List<String>? = when (value) {
        is String -> value.split(Regex("\\s+")).filter(String::isNotEmpty).ifEmpty { null }
        is List<*> -> value.filterIsInstance<String>().ifEmpty { null }
        else -> null
    }
    private fun List<*>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(jsonValue(it)) } }
    private fun jsonValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> JSONObject().also { obj -> value.forEach { (k, v) -> obj.put(k.toString(), jsonValue(v)) } }
        is List<*> -> value.toJsonArray()
        else -> value ?: JSONObject.NULL
    }
    private fun InputStream.readBytesBounded(max: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0L
        while (true) { val read = read(buffer); if (read < 0) break; total += read; require(total <= max) { "The Yomitan index is too large" }; out.write(buffer, 0, read) }
        return out.toByteArray()
    }
}
