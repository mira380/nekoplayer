package com.nekoplayer.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

@Composable
internal fun YomitanGlossary(
    glossary: List<Any?>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (glossary.isEmpty()) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 7.dp),
    ) {
        glossary.forEachIndexed { index, value ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    (index + 1).toString() + ".",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(18.dp).padding(top = 2.dp),
                )
                GlossaryNode(value, compact = compact, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Suppress("ModifierParameter")
@Composable
private fun GlossaryNode(
    value: Any?,
    compact: Boolean,
    depth: Int = 0,
    modifier: Modifier = Modifier,
    marker: String? = null,
) {
    // Indentation depth tracks nested lists; dictionary wrapper elements do not add depth.
    when (value) {
        null, JSONObject.NULL -> Unit
        is JSONArray -> {
            if (isInlineGlossary(value)) {
                val text = glossaryInlineText(value)
                if (text.isNotBlank()) Text(text, modifier = modifier, style = glossaryTextStyle(compact))
            } else {
                GlossaryChildren(
                    children = (0 until value.length()).map { value.opt(it) },
                    compact = compact,
                    depth = depth,
                    modifier = modifier,
                )
            }
        }
        is List<*> -> {
            if (isInlineGlossary(value)) {
                val text = glossaryInlineText(value)
                if (text.isNotBlank()) Text(text, modifier = modifier, style = glossaryTextStyle(compact))
            } else {
                GlossaryChildren(value, compact, depth, modifier)
            }
        }
        is JSONObject -> {
            val tag = value.optString("tag").lowercase()
            val type = value.optString("type").lowercase()
            val data = value.optJSONObject("data")
            val role = data?.optString("content").orEmpty().lowercase()
            val dataClass = data?.optString("class").orEmpty().lowercase()
            val content = when {
                value.has("content") -> value.opt("content")
                value.has("children") -> value.opt("children")
                value.has("text") -> value.opt("text")
                else -> null
            }
            if (tag == "br") {
                Spacer(modifier.height(4.dp))
            } else if (tag in setOf("ul", "ol")) {
                GlossaryList(content, compact, depth, ordered = tag == "ol", modifier = modifier)
            } else if (tag == "li") {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = modifier.fillMaxWidth().padding(start = (depth * 8).coerceAtMost(24).dp),
                ) {
                    Text(
                        marker ?: "•",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(18.dp).padding(top = 1.dp),
                    )
                    if (isInlineGlossary(content)) {
                        Text(
                            glossaryInlineText(content),
                            modifier = Modifier.weight(1f),
                            style = glossaryTextStyle(compact),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    } else {
                        GlossaryNode(content, compact, depth + 1, modifier = Modifier.weight(1f))
                    }
                }
            } else if (type == "structured-content") {
                GlossaryNode(content, compact, depth, modifier = modifier)
            } else if (role == "attribution") {
                Row(
                    modifier = modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        glossaryInlineText(content),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            } else if ((role == "example-sentence") || (dataClass == "extra-box")) {
                Surface(
                    modifier = modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(5.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    GlossaryNode(
                        content,
                        compact,
                        depth,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
                    )
                }
            } else if ((role == "example-sentence-a") || (role == "example-sentence-b")) {
                if (isInlineGlossary(content)) {
                    Text(
                        glossaryInlineText(content),
                        modifier = modifier.fillMaxWidth(),
                        style = if (role == "example-sentence-a") glossaryTextStyle(compact)
                        else MaterialTheme.typography.bodySmall,
                        color = if (role == "example-sentence-a") MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    GlossaryNode(content, compact, depth, modifier = modifier)
                }
            } else if (tag in setOf("div", "p", "section", "article", "blockquote")) {
                val indent = if ((role == "extra-info") || (dataClass == "extra-content")) 8.dp else 0.dp
                val spacing = if (role == "sense-group") 5.dp else 2.dp
                Column(
                    modifier = modifier.fillMaxWidth().padding(start = indent),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    GlossaryNode(content, compact, depth)
                }
            } else if ((type in setOf("image", "image-block")) || (tag == "img")) {
                val alt = value.optString("alt").ifBlank { value.optString("title") }
                if (alt.isNotBlank()) {
                    Text(
                        alt,
                        modifier = modifier,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val text = glossaryInlineText(value)
                if (text.isNotBlank()) {
                    Text(
                        text,
                        modifier = modifier,
                        style = if (tag in setOf("h1", "h2", "h3", "strong", "b")) {
                            MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            MaterialTheme.typography.bodySmall
                        },
                        color = if (role == "extra-label") {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                } else {
                    content?.let { GlossaryNode(it, compact, depth, modifier = modifier) }
                }
            }
        }
        is String -> if (value.isNotBlank()) {
            Text(value, modifier = modifier, style = glossaryTextStyle(compact), color = MaterialTheme.colorScheme.onSurface)
        }
        is Number, is Boolean -> Text(value.toString(), modifier = modifier, style = glossaryTextStyle(compact))
        else -> Text(value.toString(), modifier = modifier, style = glossaryTextStyle(compact))
    }
}

@Composable
private fun GlossaryChildren(
    children: List<Any?>,
    compact: Boolean,
    depth: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        var index = 0
        while (index < children.size) {
            if (isInlineGlossary(children[index])) {
                val run = mutableListOf<Any?>()
                while (index < children.size && isInlineGlossary(children[index])) {
                    run += children[index]
                    index++
                }
                val text = glossaryInlineText(run)
                if (text.text.isNotBlank()) {
                    Text(
                        text,
                        modifier = Modifier.fillMaxWidth(),
                        style = glossaryTextStyle(compact),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            } else {
                GlossaryNode(children[index], compact, depth)
                index++
            }
        }
    }
}

@Composable
private fun GlossaryList(value: Any?, compact: Boolean, depth: Int, ordered: Boolean, modifier: Modifier) {
    val children = when (value) {
        is JSONArray -> (0 until value.length()).map { value.opt(it) }
        is List<*> -> value
        null -> emptyList()
        else -> listOf(value)
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        children.forEachIndexed { index, child ->
            val childMarker = if (ordered) (index + 1).toString() + "." else "•"
            if (child is JSONObject && child.optString("tag").equals("li", ignoreCase = true)) {
                GlossaryNode(
                    child,
                    compact,
                    depth,
                    modifier = Modifier.fillMaxWidth(),
                    marker = childMarker,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = (depth * 8).coerceAtMost(24).dp),
                ) {
                    Text(
                        childMarker,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(18.dp),
                    )
                    GlossaryNode(child, compact, depth, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun glossaryTextStyle(compact: Boolean) =
    if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium

private fun isInlineGlossary(value: Any?): Boolean = when (value) {
    null, JSONObject.NULL, is String, is Number, is Boolean -> true
    is JSONArray -> (0 until value.length()).all { isInlineGlossary(value.opt(it)) }
    is List<*> -> value.all(::isInlineGlossary)
    is JSONObject -> {
        val tag = value.optString("tag").lowercase()
        val type = value.optString("type").lowercase()
        val data = value.optJSONObject("data")
        val role = data?.optString("content").orEmpty().lowercase()
        val dataClass = data?.optString("class").orEmpty().lowercase()
        tag !in setOf("ul", "ol", "li", "div", "p", "section", "article", "blockquote") &&
            type !in setOf("structured-content", "image", "image-block") &&
            role !in setOf(
                "sense-group", "sense", "glossary", "extra-info", "attribution", "example-sentence",
                "example-sentence-a", "example-sentence-b",
            ) &&
            dataClass !in setOf("extra-box", "extra-content") &&
            when {
                value.has("content") -> isInlineGlossary(value.opt("content"))
                value.has("children") -> isInlineGlossary(value.opt("children"))
                else -> true
            }
    }
    else -> true
}

@Composable
private fun glossaryInlineText(value: Any?): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        appendGlossaryInline(
            this,
            value,
            colors.primary,
            colors.surfaceVariant,
            colors.onSurfaceVariant,
        )
    }
}

private fun appendGlossaryInline(
    builder: AnnotatedString.Builder,
    value: Any?,
    accent: Color,
    tagBackground: Color,
    tagForeground: Color,
) {
    when (value) {
        null, JSONObject.NULL -> Unit
        is String -> builder.append(value)
        is Number, is Boolean -> builder.append(value.toString())
        is JSONArray -> {
            for (index in 0 until value.length()) {
                appendGlossaryInline(builder, value.opt(index), accent, tagBackground, tagForeground)
            }
        }
        is List<*> -> value.forEach { appendGlossaryInline(builder, it, accent, tagBackground, tagForeground) }
        is JSONObject -> {
            val tag = value.optString("tag").lowercase()
            if (tag == "br") {
                builder.append('\n')
                return
            }
            // Compose has no ruby layout, so show the base text without appending furigana inline.
            if (tag == "rt" || tag == "rp") return
            val data = value.optJSONObject("data")
            val role = data?.optString("content").orEmpty().lowercase()
            val isTag = data?.optString("class").orEmpty().equals("tag", ignoreCase = true) ||
                data?.optString("code").orEmpty().isNotBlank()
            val style = when {
                isTag -> SpanStyle(
                    color = tagForeground,
                    background = tagBackground,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                )
                role == "example-keyword" -> SpanStyle(color = accent, fontWeight = FontWeight.Medium)
                tag in setOf("b", "strong", "th") -> SpanStyle(fontWeight = FontWeight.SemiBold)
                tag in setOf("i", "em") -> SpanStyle(fontStyle = FontStyle.Italic)
                else -> null
            }
            style?.let(builder::pushStyle)
            val child = when {
                value.has("content") -> value.opt("content")
                value.has("children") -> value.opt("children")
                value.has("text") -> value.opt("text")
                else -> null
            }
            appendGlossaryInline(builder, child, accent, tagBackground, tagForeground)
            if (style != null) builder.pop()
            if (isTag) builder.append(' ')
        }
    }
}

@Composable
internal fun DictionaryBadge(label: String) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}
