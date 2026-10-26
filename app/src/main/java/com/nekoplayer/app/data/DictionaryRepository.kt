package com.nekoplayer.app.data

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.sqlite.transaction
import android.database.sqlite.SQLiteStatement
import android.net.Uri
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Calendar
import java.util.Locale
import java.text.SimpleDateFormat
import java.util.TimeZone
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

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
        private const val COMPACT_PAYLOAD_VERSION = 1
        private const val COMPRESSED_PAYLOAD_VERSION = 2
        private const val MIN_COMPRESS_PAYLOAD_CHARS = 768
        private const val MIN_COMPRESS_SAVINGS_BYTES = 48
        private const val EXACT_CHUNK_SIZE = 400
        private const val MAX_SEARCH_LIMIT = 100
        private const val MAX_EXACT_LIMIT = 1_000
        private const val INDEX_MAX_BYTES = 2L * 1024L * 1024L
        private val TERM_BANK = Regex("^term_bank_\\d+\\.json$")
        private val META_BANK = Regex("^term_meta_bank_\\d+\\.json$")
        private val KANJI_BANK = Regex("^kanji_bank_\\d+\\.json$")
        private val META_MODES = setOf("freq", "pitch", "ipa")
        private val FREQUENCY_NUMBER_PREFIX = Regex("""^\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+))""")
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
        database.transaction {
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
        }
    }

    fun deleteDictionary(id: Long) {
        database.transaction {
            database.delete("dictionary_entries", "dictionary_id=?", arrayOf(id.toString()))
            database.delete("dictionary_meta", "dictionary_id=?", arrayOf(id.toString()))
            database.delete("dictionary_catalog", "id=?", arrayOf(id.toString()))
        }
    }

    // Exact matches rank ahead of prefixes, followed by dictionary priority and source score.
    fun search(query: String, limit: Int = 50): List<DictionaryEntry> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        val safeLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)
        val prefix = escapeLike(term) + "%"
        val rows = queryEntries(
            """SELECT e.id,e.dictionary_id,e.expression,e.reading,e.score,e.entry_json,e.payload_version,d.name
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
                """SELECT e.id,e.dictionary_id,e.expression,e.reading,e.score,e.entry_json,e.payload_version,d.name,d.sort_order
                   FROM dictionary_entries e JOIN dictionary_catalog d ON d.id=e.dictionary_id
                   WHERE d.is_ready=1 AND (e.expression IN ($marks) OR e.reading IN ($marks))
                   ORDER BY d.sort_order ASC,e.score DESC,e.id ASC LIMIT ?""",
                args.toTypedArray(),
            ).use { cursor -> while (cursor.moveToNext()) {
                val entry = cursorEntry(cursor)
                val key = "${entry.dictionaryId}:${entry.id}"
                if (!found.containsKey(key)) found[key] = entry to cursor.getInt(8)
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
                if (!found.containsKey(id)) {
                    found[id] = Ranked(id, cursor.getInt(8), DictionaryTermMeta(
                        expression = cursor.getString(1), reading = cursor.getString(2),
                        mode = cursor.getString(3), sortValue = cursor.getDouble(4),
                        display = cursor.nullableString(5), detailJson = cursor.nullableString(6),
                        dictionaryName = cursor.getString(7),
                    ))
                }
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
        val id = cursor.getLong(0)
        val dictionaryId = cursor.getLong(1)
        val expression = cursor.getString(2)
        val reading = cursor.getString(3)
        val score = cursor.getInt(4)
        val payloadVersion = cursor.getInt(6)
        val payloadJson = if (payloadVersion == COMPRESSED_PAYLOAD_VERSION) {
            inflateTermPayload(cursor.getBlob(5))
        } else {
            cursor.getString(5)
        }
        val payload = JSONArray(payloadJson)
        val dictionaryName = cursor.getString(7)
        if (payloadVersion >= COMPACT_PAYLOAD_VERSION) {
            val glossary = payload.optJSONArray(4) ?: JSONArray()
            return DictionaryEntry(
                id = id,
                dictionaryId = dictionaryId,
                dictionaryName = dictionaryName,
                expression = expression,
                reading = reading,
                definitionTags = parseTags(payload.opt(0)),
                rules = parseTags(payload.opt(1)),
                score = score,
                sequence = payload.optInt(2),
                termTags = parseTags(payload.opt(3)),
                glossary = (0 until glossary.length()).map(glossary::opt),
            )
        }

        val versionThree = payload.length() >= 8 && payload.opt(6) is Number
        val glossary = if (versionThree) payload.optJSONArray(5) else JSONArray().apply {
            for (index in 5 until payload.length()) put(payload.opt(index))
        }
        return DictionaryEntry(
            id = id,
            dictionaryId = dictionaryId,
            dictionaryName = dictionaryName,
            expression = payload.optString(0),
            reading = payload.optString(1),
            definitionTags = parseTags(payload.opt(2)),
            rules = parseTags(payload.opt(3)),
            score = payload.optInt(4),
            sequence = if (versionThree) payload.optInt(6) else 0,
            termTags = if (versionThree) parseTags(payload.opt(7)) else null,
            glossary = (0 until (glossary?.length() ?: 0)).map { glossary!!.opt(it) },
        )
    }

    suspend fun compactExistingTermPayloads(): Int {
        var compactedCount = 0
        var lastId = 0L
        val update = database.compileStatement(
            "UPDATE dictionary_entries SET entry_json=?,payload_version=? WHERE id=? AND payload_version=0",
        )
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = mutableListOf<Pair<Long, String>>()
                database.rawQuery(
                    "SELECT id,entry_json FROM dictionary_entries " +
                        "WHERE id>? AND payload_version=0 ORDER BY id LIMIT ?",
                    arrayOf(lastId.toString(), BATCH_SIZE.toString()),
                ).use { cursor ->
                    while (cursor.moveToNext()) batch += cursor.getLong(0) to cursor.getString(1)
                }
                if (batch.isEmpty()) break

                database.transaction {
                    batch.forEach { (id, rawPayload) ->
                        update.clearBindings()
                        update.bindString(1, compactTermPayload(rawPayload))
                        update.bindLong(2, COMPACT_PAYLOAD_VERSION.toLong())
                        update.bindLong(3, id)
                        update.executeUpdateDelete()
                    }
                }
                compactedCount += batch.size
                lastId = batch.last().first
                yield()
            }
        } finally {
            update.close()
        }
        return compactedCount
    }

    private fun compactTermPayload(raw: String): String {
        val source = JSONArray(raw)
        val versionThree = source.length() >= 8 && source.opt(6) is Number
        val glossary = if (versionThree) {
            source.optJSONArray(5) ?: JSONArray()
        } else {
            JSONArray().apply {
                for (index in 5 until source.length()) put(source.opt(index))
            }
        }
        return JSONArray().apply {
            put(source.opt(2))
            put(source.opt(3))
            put(if (versionThree) source.opt(6) else 0)
            put(if (versionThree) source.opt(7) else JSONObject.NULL)
            put(glossary)
        }.toString()
    }

    suspend fun compressExistingTermPayloads(): Int {
        var updatedCount = 0
        var lastId = 0L
        val update = database.compileStatement(
            "UPDATE dictionary_entries SET entry_json=?,payload_version=? " +
                "WHERE id=? AND payload_version<?",
        )
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val batch = mutableListOf<Triple<Long, String, Int>>()
                database.rawQuery(
                    "SELECT id,entry_json,payload_version FROM dictionary_entries " +
                        "WHERE id>? AND payload_version<? ORDER BY id LIMIT ?",
                    arrayOf(
                        lastId.toString(),
                        COMPRESSED_PAYLOAD_VERSION.toString(),
                        BATCH_SIZE.toString(),
                    ),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        batch += Triple(cursor.getLong(0), cursor.getString(1), cursor.getInt(2))
                    }
                }
                if (batch.isEmpty()) break

                database.transaction {
                    batch.forEach { (id, storedPayload, version) ->
                        val compactPayload = if (version == 0) compactTermPayload(storedPayload) else storedPayload
                        val compressed = compressTermPayload(compactPayload)
                        if (compressed == null && version >= COMPACT_PAYLOAD_VERSION) return@forEach

                        update.clearBindings()
                        if (compressed != null) {
                            update.bindBlob(1, compressed)
                            update.bindLong(2, COMPRESSED_PAYLOAD_VERSION.toLong())
                        } else {
                            update.bindString(1, compactPayload)
                            update.bindLong(2, COMPACT_PAYLOAD_VERSION.toLong())
                        }
                        update.bindLong(3, id)
                        update.bindLong(4, COMPRESSED_PAYLOAD_VERSION.toLong())
                        updatedCount += update.executeUpdateDelete()
                    }
                }
                lastId = batch.last().first
                yield()
            }
        } finally {
            update.close()
        }
        return updatedCount
    }

    private fun compressTermPayload(payload: String): ByteArray? {
        if (payload.length < MIN_COMPRESS_PAYLOAD_CHARS) return null
        val deflater = Deflater(Deflater.BEST_SPEED)
        val output = ByteArrayOutputStream(minOf(payload.length / 2, 32 * 1024))
        return try {
            DeflaterOutputStream(output, deflater, 8 * 1024).use { compressed ->
                OutputStreamWriter(compressed, StandardCharsets.UTF_8).apply {
                    write(payload)
                    flush()
                }
            }
            output.toByteArray().takeIf {
                it.size + MIN_COMPRESS_SAVINGS_BYTES < payload.length
            }
        } finally {
            deflater.end()
        }
    }

    private fun inflateTermPayload(payload: ByteArray): String =
        InflaterInputStream(ByteArrayInputStream(payload)).bufferedReader(StandardCharsets.UTF_8).use { reader ->
            val output = StringBuilder(payload.size * 2)
            val buffer = CharArray(8 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                output.append(buffer, 0, count)
            }
            output.toString()
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
            """SELECT e.id,e.dictionary_id,e.expression,e.reading,e.score,e.entry_json,e.payload_version,d.name
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
        val statement = database.compileStatement(
            "INSERT INTO dictionary_entries(dictionary_id,expression,reading,score,entry_json,payload_version) " +
                "VALUES(?,?,?,?,?,?)",
        )
        val consumer = NativeTermBankBatchConsumer { expressions, readings, scores, rawRows ->
            require(expressions.size == readings.size && expressions.size == scores.size && expressions.size == rawRows.size) {
                "The native dictionary importer returned an incomplete batch"
            }
            database.transaction {
                expressions.indices.forEach { index ->
                    bindEntry(statement, dictionaryId, expressions[index], readings[index], scores[index], rawRows[index])
                }
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
            try {
                database.transaction {
                    pending.forEach { insertMeta(statement, dictionaryId, it) }
                }
            } finally {
                pending.clear()
            }
        }
        reader.use {
            it.beginArray()
            while (it.hasNext()) {
                val meta = readMetaRow(it) ?: continue
                pending += meta; count++; batch++
                if (batch >= BATCH_SIZE) { flush(); batch = 0 }
            }
            it.endArray()
        }
        flush(); statement.close()
        return count
    }

    private fun readMetaRow(reader: JsonReader): MetaRow? {
        if (reader.peek() != JsonToken.BEGIN_ARRAY) {
            reader.skipValue()
            return null
        }

        reader.beginArray()
        var fieldCount = 0
        val expression = if (reader.hasNext()) readMetaString(reader).also { fieldCount++ } else null
        val mode = if (reader.hasNext()) readMetaString(reader).also { fieldCount++ } else null
        val normalizedMode = mode?.trim()?.lowercase(Locale.ROOT)
        val data = if (reader.hasNext()) {
            fieldCount++
            if (normalizedMode in META_MODES) readMetaData(reader, normalizedMode.orEmpty())
            else {
                reader.skipValue()
                null
            }
        } else {
            null
        }
        val hasExactlyThreeFields = fieldCount == 3 && !reader.hasNext()
        while (reader.hasNext()) reader.skipValue()
        reader.endArray()

        if (!hasExactlyThreeFields || expression == null || mode == null) return null
        return normalizeMeta(expression, mode, data)
    }

    private fun readMetaString(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.STRING) return reader.nextString()
        reader.skipValue()
        return null
    }

    private fun readMetaData(reader: JsonReader, mode: String): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> readMetaObject(reader, mode)
        JsonToken.BEGIN_ARRAY -> if (mode == "freq") {
            reader.skipValue()
            null
        } else {
            readValue(reader)
        }
        JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> readMetaScalar(reader)
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readMetaObject(reader: JsonReader, mode: String): Map<String, Any?> {
        val values = linkedMapOf<String, Any?>()
        val detailField = when (mode) {
            "pitch" -> "pitches"
            "ipa" -> "transcriptions"
            else -> null
        }

        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            when {
                name == "reading" -> values[name] = readMetaScalarOrSkip(reader)
                mode == "freq" && name == "frequency" -> values[name] = readMetaFrequency(reader)
                mode == "freq" && (name == "value" || name == "displayValue") ->
                    values[name] = readMetaScalarOrSkip(reader)
                name == detailField -> {
                    values[name] = if (reader.peek() == JsonToken.BEGIN_ARRAY) readValue(reader) else {
                        reader.skipValue()
                        null
                    }
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return values
    }

    private fun readMetaFrequency(reader: JsonReader): Any? {
        if (reader.peek() != JsonToken.BEGIN_OBJECT) return readMetaScalarOrSkip(reader)

        val values = linkedMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            if (name == "value" || name == "displayValue") {
                values[name] = readMetaScalarOrSkip(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return values
    }

    private fun readMetaScalarOrSkip(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> readMetaScalar(reader)
        else -> {
            reader.skipValue()
            null
        }
    }

    private fun readMetaScalar(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().let { it.toLongOrNull() ?: it.toDoubleOrNull() ?: 0 }
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> {
            reader.skipValue()
            null
        }
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
            val rawText = rawValue as? String
            if (rawText != null && rawText.isBlank()) return null
            val value = when (rawValue) {
                is Number -> rawValue.toDouble()
                is String -> rawValue.toDoubleOrNull()
                    ?: FREQUENCY_NUMBER_PREFIX.find(rawValue)?.groupValues?.get(1)?.toDoubleOrNull()
                    ?: Double.MAX_VALUE
                else -> return null
            }
            val display = (payloadMap?.get("displayValue") as? String)?.takeIf(String::isNotBlank)
                ?: (objectData["displayValue"] as? String)?.takeIf(String::isNotBlank)
                ?: rawText?.takeIf { it.toDoubleOrNull() == null }
            return MetaRow(expression, reading, normalizedMode, value, display, null)
        }
        val reading = objectData["reading"] as? String ?: ""
        val arrayName = if (normalizedMode == "pitch") "pitches" else "transcriptions"
        val details = objectData[arrayName] as? List<*> ?: data as? List<*>
        return MetaRow(expression, reading, normalizedMode, 0.0, null, details?.toJsonArray()?.toString())
    }

    private fun publish(staged: List<StagedDictionary>) {
        database.transaction {
            staged.forEach { item ->
                database.delete("dictionary_meta", "dictionary_id IN (SELECT id FROM dictionary_catalog WHERE name=? AND id!=?)", arrayOf(item.name, item.id.toString()))
                database.delete("dictionary_entries", "dictionary_id IN (SELECT id FROM dictionary_catalog WHERE name=? AND id!=?)", arrayOf(item.name, item.id.toString()))
                database.delete("dictionary_catalog", "name=? AND id!=?", arrayOf(item.name, item.id.toString()))
                database.execSQL("UPDATE dictionary_catalog SET version=?,entry_count=?,is_ready=1,kind=?,imported_at=? WHERE id=?",
                    arrayOf<Any?>(item.version, item.entryCount, item.kind, nowUtc(), item.id))
            }
        }
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
        statement.clearBindings()
        statement.bindLong(1, id)
        statement.bindString(2, expression)
        statement.bindString(3, reading)
        statement.bindLong(4, score.toLong())
        val compactPayload = compactTermPayload(raw)
        val compressed = compressTermPayload(compactPayload)
        if (compressed != null) {
            statement.bindBlob(5, compressed)
            statement.bindLong(6, COMPRESSED_PAYLOAD_VERSION.toLong())
        } else {
            statement.bindString(5, compactPayload)
            statement.bindLong(6, COMPACT_PAYLOAD_VERSION.toLong())
        }
        statement.executeInsert()
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
    @android.annotation.SuppressLint("DirectCalendarInstanceUsage")
    private fun nowUtc(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Calendar.getInstance(TimeZone.getTimeZone("UTC")).time)
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
