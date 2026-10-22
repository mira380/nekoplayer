package com.nekoplayer.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal const val LIBRARY_CARD_COLUMNS = 2

@android.annotation.SuppressLint("DirectDateInstantiation")
internal fun formatAiringDate(airingAtEpochSeconds: Long): String =
    SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())
        .format(Date(airingAtEpochSeconds * 1_000L))

internal fun java.io.InputStream.readBytesBounded(maxBytes: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var totalBytes = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        totalBytes += read
        require(totalBytes <= maxBytes) { "Image exceeds the size limit." }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
