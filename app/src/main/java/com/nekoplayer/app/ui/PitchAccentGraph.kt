package com.nekoplayer.app.ui

import com.nekoplayer.app.data.DictionaryTermMeta
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class PitchAccentPattern(
    val reading: String,
    val position: Int,
    val morae: List<String>,
    val tags: List<String>,
)

internal object PitchAccentGraph {
    private const val LOW_Y = 22
    private const val HIGH_Y = 7
    private const val MORA_STEP = 24
    private const val START_X = 12

    private val smallKana = setOf(
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ゃ', 'ゅ', 'ょ', 'ゎ', 'ゕ', 'ゖ',
        'ァ', 'ィ', 'ゥ', 'ェ', 'ォ', 'ャ', 'ュ', 'ョ', 'ヮ', 'ヵ', 'ヶ',
        'ｧ', 'ｨ', 'ｩ', 'ｪ', 'ｫ', 'ｬ', 'ｭ', 'ｮ',
    )

    fun parse(detailJson: String?, reading: String): List<PitchAccentPattern> {
        if (detailJson.isNullOrBlank() || reading.isBlank()) return emptyList()
        val morae = splitMorae(reading.trim())
        if (morae.isEmpty()) return emptyList()

        return runCatching {
            val parsed = org.json.JSONTokener(detailJson).nextValue()
            val pitches = when (parsed) {
                is JSONArray -> parsed
                is JSONObject -> parsed.optJSONArray("pitches")
                    ?: parsed.optJSONArray("pitch")
                    ?: JSONArray()
                else -> JSONArray()
            }
            (0 until pitches.length()).mapNotNull { index ->
                val item = pitches.optJSONObject(index) ?: return@mapNotNull null
                val position = parsePosition(item.opt("position")) ?: return@mapNotNull null
                if (position !in 0..morae.size) return@mapNotNull null
                val tags = readTags(item.opt("tags"))
                PitchAccentPattern(reading.trim(), position, morae, tags)
            }.distinctBy { it.reading to (it.position to it.tags) }
        }.getOrDefault(emptyList())
    }

    fun renderAnkiField(
        metadata: List<DictionaryTermMeta>,
        expression: String,
        reading: String,
    ): String {
        val patterns = metadata.asSequence()
            .filter { it.mode.equals("pitch", ignoreCase = true) }
            .filter { it.expression == expression || it.reading == reading }
            .flatMap { row ->
                parse(row.detailJson, row.reading.ifBlank { reading }).asSequence()
                    .map { row.dictionaryName to it }
            }
            .distinctBy { (dictionary, pattern) ->
                "$dictionary\u0000${pattern.reading}\u0000${pattern.position}\u0000${pattern.tags.joinToString()}"
            }
            .toList()
        if (patterns.isEmpty()) return ""

        val colors = YomitanPopupColors(
            background = 0xFFF4F1FA.toInt(),
            foreground = 0xFF27232D.toInt(),
            secondary = 0xFF625D6B.toInt(),
            surface = 0xFFFFFFFF.toInt(),
            divider = 0xFFD8D2E0.toInt(),
            accent = 0xFF7255B5.toInt(),
            accentForeground = 0xFFFFFFFF.toInt(),
            isDark = false,
        )
        return buildString {
            append("<style>").append(styles(colors)).append("</style>")
            append("<div style=\"display:flex;flex-wrap:wrap;align-items:flex-start;gap:6px;\">")
            patterns.forEach { (dictionaryName, pattern) ->
                append(render(dictionaryName, pattern, colors))
            }
            append("</div>")
        }
    }

    @Composable
    fun Card(dictionaryName: String, pattern: PitchAccentPattern) {
        if (pattern.morae.isEmpty()) return
        val colors = MaterialTheme.colorScheme
        val language = LocalNekoLanguage.current
        val levels = highLevels(pattern)
        val graphWidth = (START_X + (pattern.morae.lastIndex * MORA_STEP) + 14).dp
        val description = buildString {
            append(pattern.reading).append("、")
                .append(localizeUiText("Pitch accent", language)).append(pattern.position)
            append("、").append(localizeUiText(positionName(pattern.position, pattern.morae.size), language))
            if (pattern.tags.isNotEmpty()) append(", ").append(pattern.tags.joinToString(", "))
        }

        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(7.dp),
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        dictionaryName,
                        modifier = Modifier.widthIn(max = 150.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        pattern.position.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurface,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (pattern.tags.isNotEmpty()) {
                    Text(
                        pattern.tags.take(3).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Canvas(
                    Modifier.width(graphWidth)
                        .height(30.dp)
                        .semantics { contentDescription = description },
                ) {
                    val step = MORA_STEP.dp.toPx()
                    val startX = START_X.dp.toPx()
                    val highY = HIGH_Y.dp.toPx()
                    val lowY = LOW_Y.dp.toPx()
                    val lastX = startX + pattern.morae.lastIndex * step
                    val endX = lastX + 12.dp.toPx()
                    val endHigh = pattern.position == 0
                    val path = Path().apply {
                        moveTo(startX, if (levels.first()) highY else lowY)
                        for (index in 1 until levels.size) {
                            val x = startX + index * step
                            val transitionX = x - step / 2f
                            lineTo(transitionX, if (levels[index - 1]) highY else lowY)
                            lineTo(transitionX, if (levels[index]) highY else lowY)
                            lineTo(x, if (levels[index]) highY else lowY)
                        }
                        val lastY = if (levels.last()) highY else lowY
                        val endY = if (endHigh) highY else lowY
                        lineTo(lastX + 5.dp.toPx(), lastY)
                        if (lastY != endY) lineTo(lastX + 5.dp.toPx(), endY)
                        lineTo(endX, endY)
                    }
                    drawPath(
                        path = path,
                        color = colors.primary,
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                    )
                    levels.forEachIndexed { index, high ->
                        val x = startX + index * step
                        val y = if (high) highY else lowY
                        drawCircle(
                            color = if (high) colors.primary else colors.surface,
                            radius = 3.4.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(x, y),
                        )
                        drawCircle(
                            color = colors.primary,
                            radius = 3.4.dp.toPx(),
                            center = androidx.compose.ui.geometry.Offset(x, y),
                            style = Stroke(width = 1.8.dp.toPx()),
                        )
                    }
                }
                Row {
                    pattern.morae.forEachIndexed { index, mora ->
                        Text(
                            mora,
                            modifier = Modifier.width(MORA_STEP.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (levels[index]) colors.onSurface else colors.onSurfaceVariant,
                            fontWeight = if (levels[index]) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }

    fun render(
        dictionaryName: String,
        pattern: PitchAccentPattern,
        colors: YomitanPopupColors,
        language: String = "English",
    ): String {
        if (pattern.morae.isEmpty()) return ""
        val highLevels = highLevels(pattern)
        val endHigh = pattern.position == 0
        val lastX = START_X + (pattern.morae.lastIndex * MORA_STEP)
        val endX = lastX + 12
        val width = endX + 2
        val accessibleLabel = buildString {
            append(pattern.reading).append("、")
                .append(localizeUiText("Pitch accent", language)).append(pattern.position)
            append("、").append(localizeUiText(positionName(pattern.position, pattern.morae.size), language))
            if (pattern.tags.isNotEmpty()) append(", ").append(pattern.tags.joinToString(", "))
        }

        return buildString {
            append("<div class=\"pitch-accent\" role=\"img\" aria-label=\"")
                .append(escape(accessibleLabel)).append("\">")
            append("<div class=\"pitch-heading\"><span class=\"pitch-source\">")
                .append(escape(dictionaryName)).append("</span><span class=\"pitch-position\">")
                .append(pattern.position).append("</span>")
            pattern.tags.take(3).forEach { tag ->
                append("<span class=\"pitch-tag\">").append(escape(tag)).append("</span>")
            }
            append("</div><div class=\"pitch-visual\">")
            append("<svg width=\"").append(width).append("\" height=\"30\" viewBox=\"0 0 ")
                .append(width).append(" 30\" xmlns=\"http://www.w3.org/2000/svg\" aria-hidden=\"true\">")
            append("<path d=\"").append(path(highLevels, endHigh, lastX, endX))
                .append("\" fill=\"none\" stroke=\"").append(css(colors.accent))
                .append("\" stroke-linecap=\"round\" stroke-linejoin=\"round\" stroke-width=\"2\"/>")
            highLevels.forEachIndexed { index, high ->
                val x = START_X + index * MORA_STEP
                val y = if (high) HIGH_Y else LOW_Y
                append("<circle cx=\"").append(x).append("\" cy=\"").append(y)
                    .append("\" r=\"3.4\" fill=\"")
                    .append(if (high) css(colors.accent) else css(colors.surface))
                    .append("\" stroke=\"").append(css(colors.accent)).append("\" stroke-width=\"1.8\"/>")
            }
            append("</svg><div class=\"pitch-morae\" aria-hidden=\"true\">")
            pattern.morae.forEachIndexed { index, mora ->
                append("<span class=\"").append(if (highLevels[index]) "high" else "low")
                    .append("\">").append(escape(mora)).append("</span>")
            }
            append("</div></div></div>")
        }
    }

    fun styles(colors: YomitanPopupColors): String = """
        .pitch-accent { display: inline-flex; flex-direction: column; gap: 2px; min-width: 64px; max-width: 100%; padding: 4px 6px; border: 1px solid ${css(colors.divider)}; border-radius: 6px; background: ${css(colors.surface)}; vertical-align: top; }
        .pitch-heading { display: flex; flex-wrap: wrap; align-items: baseline; gap: 4px; color: ${css(colors.secondary)}; font-size: 11px; line-height: 1.2; }
        .pitch-source { font-weight: 600; }
        .pitch-position { color: ${css(colors.foreground)}; font-weight: 700; font-variant-numeric: tabular-nums; }
        .pitch-tag { padding: 1px 4px; border-radius: 3px; background: ${css(colors.background)}; }
        .pitch-visual { max-width: 100%; overflow-x: auto; overflow-y: hidden; }
        .pitch-visual svg { display: block; height: 30px; }
        .pitch-morae { display: flex; width: max-content; min-width: 100%; }
        .pitch-morae span { flex: none; width: ${MORA_STEP}px; color: ${css(colors.secondary)}; font-size: 12px; line-height: 1.2; text-align: center; }
        .pitch-morae .high { color: ${css(colors.foreground)}; font-weight: 700; }
    """.trimIndent()

    private fun highLevels(pattern: PitchAccentPattern): List<Boolean> = pattern.morae.indices.map { index ->
        when (pattern.position) {
            0 -> index > 0
            1 -> index == 0
            else -> index in 1 until pattern.position
        }
    }

    private fun path(highLevels: List<Boolean>, endHigh: Boolean, lastX: Int, endX: Int): String = buildString {
        val firstY = if (highLevels.first()) HIGH_Y else LOW_Y
        append("M ").append(START_X).append(' ').append(firstY)
        for (index in 1 until highLevels.size) {
            val x = START_X + index * MORA_STEP
            val transitionX = x - MORA_STEP / 2
            val y = if (highLevels[index]) HIGH_Y else LOW_Y
            append(" H ").append(transitionX).append(" V ").append(y).append(" H ").append(x)
        }
        val endY = if (endHigh) HIGH_Y else LOW_Y
        val lastY = if (highLevels.last()) HIGH_Y else LOW_Y
        append(" H ").append(lastX + 5)
        if (lastY != endY) append(" V ").append(endY)
        append(" H ").append(endX)
    }

    private fun splitMorae(reading: String): List<String> {
        val result = mutableListOf<String>()
        reading.forEach { character ->
            val category = Character.getType(character)
            val combiningMark = category == Character.NON_SPACING_MARK.toInt() ||
                category == Character.COMBINING_SPACING_MARK.toInt() ||
                category == Character.ENCLOSING_MARK.toInt()
            if ((combiningMark || (character in smallKana && result.isNotEmpty())) && result.isNotEmpty()) {
                result[result.lastIndex] += character
            } else {
                result += character.toString()
            }
        }
        return result
    }

    private fun parsePosition(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it.toDouble() == value.toDouble() }
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun readTags(value: Any?): List<String> {
        val array = value as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val tag = array.opt(index)) {
                is String -> tag.trim().takeIf(String::isNotEmpty)
                is JSONObject -> tag.optString("name").ifBlank { tag.optString("tag") }
                    .trim().takeIf(String::isNotEmpty)
                else -> null
            }
        }.distinct()
    }

    private fun positionName(position: Int, moraCount: Int): String = when {
        position == 0 -> "heiban"
        position == 1 -> "atamadaka"
        position == moraCount -> "odaka"
        else -> "nakadaka"
    }

    private fun css(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(when (char) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> char.toString()
            })
        }
    }
}
