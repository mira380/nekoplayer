package com.nekoplayer.app.mining

import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.Html
import androidx.core.content.FileProvider
import java.io.File
import java.text.Normalizer

data class AnkiDroidStatus(
    val installed: Boolean,
    val apiEnabled: Boolean,
    val permissionGranted: Boolean,
    val failureReason: String? = null,
)

data class AnkiDroidDeck(val id: Long, val name: String)

data class AnkiDroidNoteType(val id: Long, val name: String)

data class AnkiDroidAttachment(
    val file: File,
    val preferredName: String,
    // Use "audio" or "image"; other MIME types are handled as audio.
    val mimeType: String,
    val destinationField: String,
    val isImage: Boolean = mimeType == "image",
)

data class AnkiDroidNote(
    val deckId: Long,
    val noteTypeId: Long,
    // Keep the provider's field order; AnkiDroid expects values in this order.
    val modelFields: List<String>,
    val fieldValues: Map<String, String>,
    val attachments: List<AnkiDroidAttachment> = emptyList(),
    val tags: List<String> = emptyList(),
)

// The Activity requests the permission and returns the result; this client never registers a launcher.
fun interface AnkiDroidPermissionRequester {
    fun request(permission: String, callback: (granted: Boolean) -> Unit)
}

class AnkiDroidException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

// Provider queries and writes block, so call them off the main thread.
// The manifest must declare com.ichi2.anki.permission.READ_WRITE_DATABASE and make com.ichi2.anki visible.
class AnkiDroidClient(
    private val activity: Activity,
    private val permissionRequester: AnkiDroidPermissionRequester,
    private val fileProviderAuthority: String = "${activity.packageName}.nekoplayer_media",
) {
    @Volatile private var permissionRequestPending = false

    fun status(): AnkiDroidStatus {
        val provider = try {
            activity.packageManager.resolveContentProvider(ANKIDROID_AUTHORITY, PackageManager.GET_META_DATA)
        } catch (_: Exception) {
            null
        }
        val installed = try {
            activity.packageManager.getApplicationInfo(ANKIDROID_PACKAGE, 0).enabled
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
        val apiEnabled = provider?.enabled == true
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            activity.checkSelfPermission(READ_WRITE_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return AnkiDroidStatus(
            installed = installed,
            apiEnabled = apiEnabled,
            permissionGranted = permissionGranted,
            failureReason = when {
                !installed -> "Install AnkiDroid before adding cards."
                !apiEnabled -> "Enable the AnkiDroid API in its settings, then retry."
                else -> null
            },
        )
    }

    // Returns false for denial or an unavailable API; setup errors use Result.failure.
    // Only one permission request can be active at a time.
    fun requestWritePermission(callback: (Result<Boolean>) -> Unit) {
        val current = status()
        if (!current.installed) {
            callback(Result.failure(AnkiDroidException("ANKIDROID_NOT_INSTALLED", current.failureReason!!)))
            return
        }
        if (!current.apiEnabled) {
            callback(Result.failure(AnkiDroidException("ANKIDROID_API_DISABLED", current.failureReason!!)))
            return
        }
        if (current.permissionGranted) {
            callback(Result.success(true))
            return
        }
        synchronized(this) {
            if (permissionRequestPending) {
                callback(Result.failure(AnkiDroidException(
                    "ANKIDROID_PERMISSION_PENDING",
                    "An AnkiDroid permission request is already in progress.",
                )))
                return
            }
            permissionRequestPending = true
        }
        try {
            permissionRequester.request(READ_WRITE_PERMISSION) { granted ->
                synchronized(this) { permissionRequestPending = false }
                callback(Result.success(granted && status().permissionGranted))
            }
        } catch (error: Exception) {
            synchronized(this) { permissionRequestPending = false }
            callback(Result.failure(AnkiDroidException(
                "ANKIDROID_PERMISSION_FAILED",
                "The AnkiDroid permission request could not be started. Try again.",
                error,
            )))
        }
    }

    fun decks(): List<AnkiDroidDeck> = providerOperation {
        val result = mutableListOf<AnkiDroidDeck>()
        val cursor = activity.contentResolver.query(
            Uri.parse("$CONTENT_URI/decks"),
            arrayOf("deck_id", "deck_name"),
            null,
            null,
            null,
        ) ?: throw IllegalStateException("AnkiDroid returned no deck list.")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow("deck_id")
            val nameColumn = it.getColumnIndexOrThrow("deck_name")
            while (it.moveToNext()) result += AnkiDroidDeck(it.getLong(idColumn), it.getString(nameColumn).orEmpty())
        }
        result
    }

    fun noteTypes(): List<AnkiDroidNoteType> = providerOperation {
        val result = mutableListOf<AnkiDroidNoteType>()
        val cursor = activity.contentResolver.query(
            Uri.parse("$CONTENT_URI/models"),
            arrayOf("_id", "name"),
            null,
            null,
            null,
        ) ?: throw IllegalStateException("AnkiDroid returned no note types.")
        cursor.use {
            val idColumn = it.getColumnIndexOrThrow("_id")
            val nameColumn = it.getColumnIndexOrThrow("name")
            while (it.moveToNext()) result += AnkiDroidNoteType(it.getLong(idColumn), it.getString(nameColumn).orEmpty())
        }
        result
    }

    fun fields(noteTypeId: Long): List<String> = providerOperation {
        if (noteTypeId <= 0) throw IllegalArgumentException("A note type id is required.")
        val cursor = activity.contentResolver.query(
            Uri.parse("$CONTENT_URI/models/$noteTypeId"),
            arrayOf("field_names"),
            null,
            null,
            null,
        ) ?: throw IllegalStateException("AnkiDroid returned no note type fields.")
        cursor.use {
            if (!it.moveToFirst()) throw IllegalArgumentException("The selected AnkiDroid note type was not found.")
            val encoded = it.getString(it.getColumnIndexOrThrow("field_names")).orEmpty()
            if (encoded.isEmpty()) emptyList() else encoded.split(FIELD_SEPARATOR)
        }
    }

    // Checks complete field values without changing AnkiDroid's duplicate-note behavior.
    fun hasWordInDeck(deckName: String, terms: Collection<String>): Boolean = providerOperation {
        val cleanDeckName = deckName.trim()
        val normalizedTerms = terms.map(::normalizeTerm).filter(String::isNotEmpty).distinct()
        if (cleanDeckName.isEmpty() || normalizedTerms.isEmpty()) return@providerOperation false

        val notesUri = Uri.parse("$CONTENT_URI/notes")
        for (term in normalizedTerms) {
            val search = "deck:\"${escapeSearchText(cleanDeckName)}\" \"${escapeSearchText(term)}\""
            val cursor = activity.contentResolver.query(
                notesUri,
                arrayOf("flds"),
                search,
                null,
                null,
            ) ?: throw IllegalStateException("AnkiDroid returned no note search results.")
            var found = false
            cursor.use {
                val fieldsColumn = it.getColumnIndexOrThrow("flds")
                while (!found && it.moveToNext()) {
                    val encodedFields = it.getString(fieldsColumn).orEmpty()
                    found = encodedFields.split(FIELD_SEPARATOR).any { field -> fieldMatchesTerm(field, term) }
                }
            }
            if (found) return@providerOperation true
        }
        false
    }

    fun createNote(note: AnkiDroidNote): Long = providerOperation {
        if (activity.packageManager.resolveContentProvider(ANKIDROID_AUTHORITY, 0) == null) {
            throw IllegalStateException("The AnkiDroid API provider is unavailable.")
        }
        if (note.deckId <= 0) throw IllegalArgumentException("An AnkiDroid deck is required.")
        if (note.noteTypeId <= 0) throw IllegalArgumentException("An AnkiDroid note type is required.")
        val orderedFields = note.modelFields
        val allFields = orderedFields.toSet()
        if (orderedFields.isEmpty() || orderedFields.size != allFields.size) {
            throw IllegalArgumentException("AnkiDroid note fields are empty or duplicated.")
        }
        if (note.fieldValues.keys.any { it !in allFields } ||
            note.attachments.any { it.destinationField !in allFields }
        ) {
            throw IllegalArgumentException("A configured field is not present in the selected note type.")
        }
        if (note.fieldValues.values.none(String::isNotBlank)) {
            throw IllegalArgumentException("The note has no usable content.")
        }

        val values = note.fieldValues.toMutableMap()
        for (attachment in note.attachments) {
            val markup = importAttachment(attachment)
            val previous = values[attachment.destinationField].orEmpty()
            values[attachment.destinationField] = if (previous.isBlank()) markup else "$previous\n$markup"
        }
        val encodedFields = orderedFields.joinToString(FIELD_SEPARATOR.toString()) { values[it].orEmpty() }
        val content = ContentValues().apply {
            put("mid", note.noteTypeId)
            put("flds", encodedFields)
            put("tags", note.tags.filter(String::isNotBlank).joinToString(" "))
        }
        val insertUri = Uri.parse("$CONTENT_URI/notes").buildUpon()
            .appendQueryParameter("deckId", note.deckId.toString())
            .build()
        val inserted = activity.contentResolver.insert(insertUri, content)
            ?: throw IllegalStateException("AnkiDroid did not insert the new card.")
        val id = inserted.lastPathSegment?.toLongOrNull()
            ?: throw IllegalStateException("AnkiDroid returned an invalid card id.")
        if (id <= 0L) throw IllegalStateException("AnkiDroid did not confirm card creation.")
        id
    }

    private fun importAttachment(attachment: AnkiDroidAttachment): String {
        val file = attachment.file.canonicalFile
        val allowedRoot = File(activity.cacheDir, MINING_CACHE_DIRECTORY).canonicalFile
        val rootPrefix = allowedRoot.path + File.separator
        if (!file.path.startsWith(rootPrefix) || !file.isFile || file.length() <= 0L) {
            throw IllegalArgumentException("An attachment is missing or outside nekoplayer's mining cache.")
        }
        if (attachment.destinationField.isBlank()) {
            throw IllegalArgumentException("Choose an AnkiDroid note field for each attachment.")
        }
        val contentUri = try {
            FileProvider.getUriForFile(activity, fileProviderAuthority, file)
        } catch (error: IllegalArgumentException) {
            throw AnkiDroidException(
                "ANKIDROID_ATTACHMENT_UNAVAILABLE",
                "The attachment is outside the configured file-sharing paths.",
                error,
            )
        }
        activity.grantUriPermission(ANKIDROID_PACKAGE, contentUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            val values = ContentValues().apply {
                put("file_uri", contentUri.toString())
                put("preferred_name", attachment.preferredName.trim().ifEmpty { file.name }.replace(' ', '_'))
            }
            val inserted = activity.contentResolver.insert(Uri.parse("$CONTENT_URI/media"), values)
                ?: throw IllegalStateException("AnkiDroid could not import captured media.")
            val name = inserted.lastPathSegment?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("AnkiDroid returned an invalid media filename.")
            return if (attachment.isImage || attachment.mimeType == "image") {
                "<img src=\"$name\" />"
            } else {
                "[sound:$name]"
            }
        } finally {
            activity.revokeUriPermission(contentUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun fieldMatchesTerm(field: String, term: String): Boolean {
        // Compare the expression itself; ruby text is only a reading annotation.
        val withoutRubyReading = field.replace(Regex("(?is)<rt\\b[^>]*>.*?</rt>"), "")
        @Suppress("DEPRECATION")
        val plainText = Html.fromHtml(withoutRubyReading).toString()
        return normalizeTerm(plainText) == term
    }

    private fun normalizeTerm(value: String): String = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
        .replace(Regex("[\\s\\u00a0]+"), "")

    private fun escapeSearchText(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

    private inline fun <T> providerOperation(operation: () -> T): T {
        val current = status()
        if (!current.installed) throw AnkiDroidException("ANKIDROID_NOT_INSTALLED", current.failureReason!!)
        if (!current.apiEnabled) throw AnkiDroidException("ANKIDROID_API_DISABLED", current.failureReason!!)
        if (!current.permissionGranted) {
            throw AnkiDroidException(
                "ANKIDROID_PERMISSION_DENIED",
                "Allow nekoplayer to access AnkiDroid, then try again.",
            )
        }
        return try {
            operation()
        } catch (error: AnkiDroidException) {
            throw error
        } catch (error: SecurityException) {
            throw AnkiDroidException(
                "ANKIDROID_PERMISSION_DENIED",
                "Allow nekoplayer to access AnkiDroid, then try again.",
                error,
            )
        } catch (error: IllegalArgumentException) {
            throw AnkiDroidException("ANKIDROID_INVALID_REQUEST", error.message ?: "The AnkiDroid request is invalid.", error)
        } catch (error: Exception) {
            val latest = status()
            when {
                !latest.installed -> throw AnkiDroidException("ANKIDROID_NOT_INSTALLED", latest.failureReason!!, error)
                !latest.apiEnabled -> throw AnkiDroidException("ANKIDROID_API_DISABLED", latest.failureReason!!, error)
                else -> throw AnkiDroidException(
                    "ANKIDROID_OPERATION_FAILED",
                    error.message?.takeIf(String::isNotBlank)
                        ?: "AnkiDroid could not complete the requested card operation.",
                    error,
                )
            }
        }
    }

    companion object {
        const val ANKIDROID_PACKAGE = "com.ichi2.anki"
        const val ANKIDROID_AUTHORITY = "com.ichi2.anki.flashcards"
        const val READ_WRITE_PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"
        private const val CONTENT_URI = "content://$ANKIDROID_AUTHORITY"
        private const val FIELD_SEPARATOR = '\u001f'
        private const val MINING_CACHE_DIRECTORY = "mining"
    }
}
