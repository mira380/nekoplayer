package com.nekoplayer.app.mining

import com.nekoplayer.app.data.DictionaryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.LinkedHashMap

object AnkiGlossaryHtml {
    data class Fields(val mainDefinition: String, val glossary: String? = null)

    fun render(entries: Iterable<DictionaryEntry>): String {
        val groups = groupByDictionary(entries)
        if (groups.isEmpty()) return ""
        return buildString {
            append("<div style=\"text-align: left;\" class=\"yomitan-glossary\"><ol>")
            groups.forEach { append(renderDictionary(it)) }
            append("</ol></div>")
        }
    }

    fun renderMainDefinition(entry: DictionaryEntry): String = render(listOf(entry))

    // With a Glossary field, keep the selected entry in Definition and all matches in Glossary.
    fun buildFields(
        matchedEntries: List<DictionaryEntry>,
        mainEntry: DictionaryEntry,
        separateGlossary: Boolean,
    ): Fields {
        val all = render(matchedEntries)
        return if (separateGlossary) Fields(renderMainDefinition(mainEntry), all)
        else Fields(all)
    }

    private data class DictionaryGroup(val name: String?, val entries: MutableList<DictionaryEntry> = mutableListOf())

    private fun groupByDictionary(entries: Iterable<DictionaryEntry>): List<DictionaryGroup> {
        val groups = LinkedHashMap<String, DictionaryGroup>()
        val seen = HashSet<String>()
        for (entry in entries) {
            if (entry.glossary.isEmpty()) continue
            val name = entry.dictionaryName.trim().takeIf(String::isNotEmpty)
            val key = name ?: "#${entry.dictionaryId}"
            val identity = "$key${entry.expression.trim()}${entry.reading.trim()}"
            if (!seen.add(identity)) continue
            groups.getOrPut(key) { DictionaryGroup(name) }.entries += entry
        }
        return groups.values.toList()
    }

    private fun renderDictionary(group: DictionaryGroup): String = buildString {
        val name = group.name
        if (name != null) append("<li data-dictionary=\"${escapeAttribute(name)}\">") else append("<li>")
        val labels = buildList {
            val seen = HashSet<String>()
            group.entries.forEach { entry ->
                (entry.definitionTags.orEmpty() + entry.termTags.orEmpty()).forEach { tag ->
                    val value = tag.trim()
                    if (value.isNotEmpty() && seen.add(value)) add(value)
                }
            }
            if (name != null) add(name)
        }
        if (labels.isNotEmpty()) append("<i>(${labels.joinToString(", ", transform = ::htmlText)})</i>")
        group.entries.forEach { entry ->
            append("<span class=\"structured-content\">")
            append(renderNodes(entry.glossary))
            append("</span>")
        }
        append("</li>")
    }

    private data class Node(
        val type: String,
        val text: String?,
        val data: Map<String, Any?>,
        val style: Map<String, Any?>,
        val children: List<Node>,
    )

    private fun node(value: Any?): Node = when (value) {
        is Node -> value
        is String -> Node("text", value, emptyMap(), emptyMap(), emptyList())
        is Number, is Boolean -> Node("text", value.toString(), emptyMap(), emptyMap(), emptyList())
        is List<*> -> Node("container", null, emptyMap(), emptyMap(), value.map(::node))
        is JSONArray -> Node("container", null, emptyMap(), emptyMap(), (0 until value.length()).map { node(value.opt(it)) })
        else -> {
            val map = asMap(value)
            val content = map["content"] ?: map["children"]
            val rawText = (map["text"] as? String) ?: (content as? String)
            val childrenValue = content?.takeUnless { it is String }
            Node(
                type = ((map["type"] as? String) ?: (map["tag"] as? String) ?: "text").trim().ifEmpty { "text" }.lowercase(),
                text = rawText,
                data = asMap(map["data"]),
                style = asMap(map["style"]),
                children = children(childrenValue),
            )
        }
    }

    private fun children(value: Any?): List<Node> = when (value) {
        is List<*> -> value.map(::node)
        is JSONArray -> (0 until value.length()).map { node(value.opt(it)) }
        is Map<*, *>, is JSONObject -> listOf(node(value))
        else -> emptyList()
    }

    private fun renderNodes(values: List<Any?>): String = values.joinToString(separator = "") { renderNode(node(it)) }

    private fun renderNode(value: Node): String = when (value.type) {
        "br" -> "<br />"
        "ruby" -> renderRuby(value)
        "a" -> renderLink(value)
        // Dictionary images aren't copied into Anki, so keep their alt text instead.
        "img" -> htmlText(value.text.orEmpty())
        "text" -> htmlText(value.text.orEmpty())
        "structured-content" -> renderNodes(childrenWithText(value))
        else -> renderElement(value, htmlTag(value.type))
    }

    private fun renderElement(value: Node, tag: String): String = buildString {
        append('<').append(tag).append(attributes(value, tag)).append('>')
        append(renderNodes(childrenWithText(value)))
        append("</").append(tag).append('>')
    }

    private fun childrenWithText(value: Node): List<Any?> = buildList {
        value.text?.takeIf { it.trim().isNotEmpty() }?.let { add(it) }
        addAll(value.children)
    }

    private val allowedTags = setOf(
        "blockquote", "code", "dd", "details", "div", "dl", "dt", "li", "ol", "p", "pre",
        "rp", "rt", "section", "span", "summary", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "ul",
    )

    private fun htmlTag(type: String): String = type.takeIf(allowedTags::contains) ?: "span"

    private fun renderRuby(value: Node): String {
        val base = StringBuilder()
        val readings = mutableListOf<String>()
        fun walk(current: Node) {
            when (current.type) {
                "rt" -> plainText(current).trim().takeIf(String::isNotEmpty)?.let(readings::add)
                "rp" -> Unit
                "text" -> base.append(htmlText(current.text.orEmpty()))
                else -> base.append(renderElement(current, htmlTag(current.type)))
            }
        }
        childrenWithText(value).forEach { walk(node(it)) }
        val reading = readings.joinToString("")
        return if (reading.isEmpty()) base.toString()
        else "<ruby class=\"gloss-sc-ruby\">$base<rt class=\"gloss-sc-rt\">${htmlText(reading)}</rt></ruby>"
    }

    private fun renderLink(value: Node): String {
        val href = absoluteUrl(value.data["href"] ?: value.data["url"])
        val body = renderNodes(childrenWithText(value))
        if (href == null) return "<span class=\"gloss-link\">$body</span>"
        return "<a class=\"gloss-link\" data-external=\"true\" href=\"${escapeAttribute(href)}\"><span class=\"gloss-link-text\">$body</span></a>"
    }

    private fun absoluteUrl(value: Any?): String? {
        val raw = value?.toString()?.trim().orEmpty()
        return raw.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) }
    }

    private fun attributes(value: Node, tag: String): String = buildString {
        append(" class=\"gloss-sc-").append(tag).append('"')
        value.data.forEach { (key, raw) ->
            val attr = dataAttribute(key) ?: return@forEach
            val attrValue = raw.scalarString() ?: return@forEach
            append(' ').append(attr).append("=\"").append(escapeAttribute(attrValue)).append('"')
        }
        append(styleAttribute(value.style))
    }

    private fun dataAttribute(key: String): String? {
        val name = key.trim()
        if (!DATA_KEY.matches(name)) return null
        return "data-sc-${name.lowercase()}"
    }

    private val cssProperties = linkedMapOf(
        "backgroundColor" to "background-color", "borderRadius" to "border-radius", "clipPath" to "clip-path",
        "color" to "color", "fontFamily" to "font-family", "fontSize" to "font-size", "fontStyle" to "font-style",
        "fontWeight" to "font-weight", "letterSpacing" to "letter-spacing", "lineHeight" to "line-height",
        "textAlign" to "text-align", "textDecoration" to "text-decoration", "verticalAlign" to "vertical-align",
        "whiteSpace" to "white-space", "wordBreak" to "word-break",
    )

    private fun styleAttribute(style: Map<String, Any?>): String {
        if (style.isEmpty()) return ""
        val declarations = mutableListOf<String>()
        cssProperties.forEach { (source, css) ->
            val text = style[source].scalarString()?.trim() ?: return@forEach
            if (text.isNotEmpty() && ';' !in text && '"' !in text) declarations += "$css:$text"
        }
        val marker = style["listStyleType"] as? String
        if (!marker.isNullOrBlank() && marker != "none") {
            val text = marker.trim().replace(Regex("^[\"']|[\"']$"), "")
            if (text.isNotEmpty() && ';' !in text && '\'' !in text) {
                declarations += "list-style-type:'${escapeAttribute(text)}'"
            }
        }
        return if (declarations.isEmpty()) ""
        else " style=\"${escapeAttribute(declarations.joinToString(";"))}\""
    }

    private fun plainText(value: Node): String = buildString {
        value.text?.let { append(stripTags(it)) }
        value.children.forEach { append(plainText(it)) }
    }

    private fun htmlText(value: String): String {
        val text = stripTags(
            value.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&"),
        )
        return if ('\n' !in text) escapeText(text) else text.split('\n').joinToString("<br />", transform = ::escapeText)
    }

    private fun stripTags(value: String): String = value
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]*>"), "")

    private fun escapeText(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun escapeAttribute(value: String): String = escapeText(value).replace("\"", "&quot;")

    private fun asMap(value: Any?): Map<String, Any?> = when (value) {
        is JSONObject -> LinkedHashMap<String, Any?>().apply {
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                put(key, value.opt(key).takeUnless { it === JSONObject.NULL })
            }
        }
        is Map<*, *> -> LinkedHashMap<String, Any?>().apply {
            value.forEach { (key, item) -> if (key != null) put(key.toString(), item) }
        }
        else -> emptyMap()
    }

    private fun Any?.scalarString(): String? = when (this) {
        is String, is Number -> toString()
        else -> null
    }

    private val DATA_KEY = Regex("^[A-Za-z][A-Za-z0-9]*$")
}
