package com.nekoplayer.app.ui

import com.nekoplayer.app.data.DictionaryEntry
import com.nekoplayer.app.data.DictionaryTermMeta
import com.nekoplayer.app.media.SubtitleScanResult
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal data class YomitanPopupColors(
    val background: Int,
    val foreground: Int,
    val secondary: Int,
    val surface: Int,
    val divider: Int,
    val accent: Int,
    val accentForeground: Int,
    val isDark: Boolean,
)

// Builds a script-free page with escaped text and a small set of safe dictionary tags and links.
internal object YomitanPopupHtml {
    private const val MAX_ENTRIES = 36
    private const val MAX_GLOSSES = 16
    private const val MAX_NODE_ITEMS = 48
    private const val MAX_NODE_DEPTH = 24
    private const val MAX_TEXT_LENGTH = 2_000
    private const val MAX_RENDERED_TEXT = 500_000

    private class RenderBudget(var remaining: Int = MAX_RENDERED_TEXT)

    fun render(
        result: SubtitleScanResult,
        metadata: List<DictionaryTermMeta>,
        subtitleContext: String,
        colors: YomitanPopupColors,
        alreadyMined: Boolean = false,
        expansion: DictionaryPopupExpansion = DictionaryPopupExpansion(),
    ): String {
        val entries = (result.entries + result.relatedEntries).take(MAX_ENTRIES)
        val primary = result.entries.firstOrNull() ?: entries.firstOrNull()
        val matchingExpressions = entries.flatMap { listOf(it.expression, it.reading) }
            .filter(String::isNotBlank).toSet() + result.surfaceText
        val matchingMetadata = matchingMetadata(metadata, matchingExpressions, primary)
        val budget = RenderBudget()

        return buildString {
            append("<!doctype html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<style>")
            append(styles(colors))
            append("</style></head><body><main>")

            if (primary != null) {
                append("<header class=\"word-header\"><div class=\"word-heading\"><div class=\"word\">")
                append(ruby(primary.expression.ifBlank { result.surfaceText }, primary.reading))
                append("</div>")
                if (primary.expression != result.surfaceText && result.surfaceText.isNotBlank()) {
                    append("<div class=\"matched\">Matched: ").append(escape(result.surfaceText)).append("</div>")
                }
                append("</div><div class=\"popup-actions\"><a class=\"mine\" href=\"nekoplayer://mine/0\" aria-label=\"")
                    .append(if (alreadyMined) "Already in Anki" else "Add to Anki").append("\">")
                append(if (alreadyMined) checkIcon(colors.foreground, colors.background) else addIcon(colors.foreground, colors.background))
                append("</a><a class=\"pronounce\" href=\"nekoplayer://audio/0\" aria-label=\"Play pronunciation\">")
                append(speakerIcon(colors.foreground)).append("</a></div></header>")
            } else {
                append("<header class=\"word-header\"><div class=\"word-heading\"><div class=\"word\">")
                append(escape(result.surfaceText)).append("</div></div></header>")
            }

            appendMetadata(this, matchingMetadata, colors)
            if (result.deinflectionReasons.isNotEmpty()) {
                append("<div class=\"form-line\">Form: ")
                append(escape(result.deinflectionReasons.distinct().joinToString(" · ")))
                append("</div>")
            }
            appendSubtitle(this, subtitleContext, result.surfaceText)

            val groups = entries.withIndex().groupBy { it.value.dictionaryName }
            groups.entries.forEachIndexed { groupIndex, (dictionaryName, groupedEntries) ->
                append("<details class=\"dictionary-group\"")
                if (expansion.isExpanded(dictionaryName, groupIndex)) append(" open")
                append("><summary><span class=\"group-name\">")
                    .append(escape(dictionaryName))
                    .append("</span></summary><div class=\"group-entries\">")
                groupedEntries.forEach { indexedEntry ->
                    appendEntry(this, indexedEntry.value, budget)
                }
                append("</div></details>")
            }
            if (entries.isEmpty()) append("<div class=\"empty\">No dictionary entries found.</div>")
            append("</main></body></html>")
        }
    }

    private fun appendEntry(out: StringBuilder, entry: DictionaryEntry, budget: RenderBudget) {
        out.append("<article class=\"entry\"><div class=\"entry-heading\">")
        out.append("<div class=\"entry-term\">")
        out.append(ruby(entry.expression, entry.reading))
        out.append("</div></div>")

        val tags = (entry.termTags.orEmpty() + entry.definitionTags.orEmpty())
            .map(String::trim).filter(String::isNotBlank).distinct()
        if (tags.isNotEmpty()) out.append("<div class=\"badges\">")
        tags.take(8).forEach { out.append("<span class=\"tag\">").append(escape(it)).append("</span>") }
        if (tags.isNotEmpty()) out.append("</div>")

        if (entry.glossary.isNotEmpty()) {
            out.append("<ol class=\"glosses\">")
            entry.glossary.take(MAX_GLOSSES).forEach { gloss ->
                val content = renderNode(gloss, budget)
                if (content.isNotBlank()) out.append("<li>").append(content).append("</li>")
            }
            out.append("</ol>")
        }
        out.append("</article>")
    }

    private data class MetadataBadge(
        val dictionaryName: String,
        val mode: String,
        val values: List<String>,
        val pitchAccents: List<PitchAccentPattern> = emptyList(),
    )

    // Prefer metadata for the selected headword so alternate readings don't crowd out pitch data.
    private fun matchingMetadata(
        metadata: List<DictionaryTermMeta>,
        matchingExpressions: Set<String>,
        primary: DictionaryEntry?,
    ): List<MetadataBadge> {
        val matching = metadata.filter {
            it.expression in matchingExpressions || it.reading in matchingExpressions
        }
        return matching.groupBy { it.dictionaryName.trim().lowercase(Locale.ROOT) to it.mode.trim().lowercase(Locale.ROOT) }
            .values.mapNotNull { rows ->
                val bestScore = rows.maxOfOrNull { metadataMatchScore(it, primary) } ?: return@mapNotNull null
                val preferred = rows.filter { metadataMatchScore(it, primary) == bestScore }
                val first = preferred.first()
                val mode = first.mode.trim().lowercase(Locale.ROOT)
                val pitchAccents = if (mode == "pitch") {
                    preferred.flatMap { row ->
                        PitchAccentGraph.parse(row.detailJson, row.reading.ifBlank { primary?.reading.orEmpty() })
                    }.distinctBy { it.reading to (it.position to it.tags) }.take(6)
                } else emptyList()
                val values = when (mode) {
                    "freq" -> preferred.asSequence().mapNotNull(::frequencyLabel).distinct().take(1).toList()
                    "pitch" -> emptyList()
                    "ipa" -> preferred.asSequence().mapNotNull { row ->
                        row.display?.takeIf(String::isNotBlank) ?: row.detailJson?.takeIf(String::isNotBlank)
                    }.distinct().take(4).toList()
                    else -> emptyList()
                }
                when {
                    mode == "pitch" && pitchAccents.isNotEmpty() ->
                        MetadataBadge(first.dictionaryName, mode, values, pitchAccents)
                    mode != "pitch" && values.isNotEmpty() -> MetadataBadge(first.dictionaryName, mode, values)
                    else -> null
                }
            }
    }

    private fun metadataMatchScore(row: DictionaryTermMeta, primary: DictionaryEntry?): Int {
        if (primary == null) return 0
        return when {
            row.expression == primary.expression && row.reading == primary.reading -> 5
            row.expression == primary.expression && row.reading.isBlank() -> 4
            row.reading.isNotBlank() && row.reading == primary.reading -> 3
            row.expression == primary.expression -> 2
            row.expression == primary.reading || row.reading == primary.expression -> 1
            else -> 0
        }
    }

    private fun frequencyLabel(row: DictionaryTermMeta): String? = row.display?.takeIf(String::isNotBlank)
        ?: row.sortValue.toString().removeSuffix(".0").takeIf(String::isNotBlank)

    private fun appendMetadata(
        out: StringBuilder,
        metadata: List<MetadataBadge>,
        colors: YomitanPopupColors,
    ) {
        if (metadata.isEmpty()) return
        val badges = metadata.filter { it.mode != "pitch" }.take(18)
        if (badges.isNotEmpty()) out.append("<div class=\"metadata\">")
        badges.forEach { row ->
            when (row.mode) {
                "freq" -> {
                    out.append("<span class=\"badge frequency\"><b>")
                        .append(escape(row.dictionaryName)).append("</b><span>")
                        .append(escape(row.values.first())).append("</span></span>")
                }
                "ipa" -> {
                    val value = row.values.joinToString(" · ")
                    if (value.isNotBlank()) out.append("<span class=\"badge ipa\"><b>")
                        .append(escape(row.dictionaryName)).append("</b><span>")
                        .append(escape(value.take(MAX_TEXT_LENGTH))).append("</span></span>")
                }
            }
        }
        if (badges.isNotEmpty()) out.append("</div>")

        val pitches = metadata.filter { it.mode == "pitch" }
            .flatMap { row -> row.pitchAccents.map { row.dictionaryName to it } }
        if (pitches.isNotEmpty()) {
            out.append("<section class=\"pitch-row\" aria-label=\"Pitch accent\">")
                .append("<div class=\"pitch-label\">Pitch accent</div><div class=\"pitch-patterns\">")
            pitches.forEach { (dictionaryName, pattern) ->
                out.append(PitchAccentGraph.render(dictionaryName, pattern, colors))
            }
            out.append("</div></section>")
        }
    }

    private fun appendSubtitle(out: StringBuilder, text: String, target: String) {
        if (text.isBlank()) return
        out.append("<div class=\"subtitle\">")
        val start = if (target.isBlank()) -1 else text.indexOf(target)
        if (start < 0) {
            out.append(escape(text.take(MAX_TEXT_LENGTH)))
        } else {
            out.append(escape(text.substring(0, start).take(MAX_TEXT_LENGTH)))
            out.append("<mark>").append(escape(target)).append("</mark>")
            out.append(escape(text.substring(start + target.length).take(MAX_TEXT_LENGTH)))
        }
        out.append("</div>")
    }

    private fun ruby(expression: String, reading: String): String {
        val term = escape(expression)
        return if (reading.isNotBlank() && reading != expression) {
            "<ruby>$term<rt>${escape(reading)}</rt></ruby>"
        } else term
    }

    private fun renderNode(value: Any?, budget: RenderBudget, depth: Int = 0): String {
        if (depth > MAX_NODE_DEPTH) return ""
        if (budget.remaining <= 0) return ""
        return when (value) {
            null, JSONObject.NULL -> ""
            is String -> renderText(value, budget)
            is Number, is Boolean -> renderText(value.toString(), budget)
            is JSONArray -> buildString {
                for (index in 0 until minOf(value.length(), MAX_NODE_ITEMS)) {
                    if (budget.remaining <= 0) break
                    append(renderNode(value.opt(index), budget, depth + 1))
                }
            }
            is List<*> -> buildString {
                value.take(MAX_NODE_ITEMS).forEach {
                    if (budget.remaining > 0) append(renderNode(it, budget, depth + 1))
                }
            }
            // Count each structured object once; Jitendex definitions can be nested several levels deep.
            is JSONObject -> renderObject(value, budget, depth)
            is Map<*, *> -> {
                val node = JSONObject()
                value.forEach { (key, item) ->
                    if (key is String) node.put(key, item ?: JSONObject.NULL)
                }
                renderObject(node, budget, depth)
            }
            else -> renderText(value.toString(), budget)
        }
    }

    private fun renderObject(value: JSONObject, budget: RenderBudget, depth: Int): String {
        val type = value.optString("type").lowercase(Locale.ROOT)
        val rawTag = value.optString("tag").lowercase(Locale.ROOT)
        val child = structuredChild(value)
        if (type == "text") return renderNode(child, budget, depth + 1)
        if (type in setOf("image", "image-block") || rawTag == "img") {
            val label = value.optString("alt").ifBlank { value.optString("title") }
            return if (label.isBlank()) "" else "<span class=\"media-note\">${renderText(label, budget)}</span>"
        }
        if (type in setOf("audio", "audio-clip") || rawTag in setOf("script", "style", "iframe", "object", "embed")) return ""
        if (rawTag == "br") return "<br>"
        if (rawTag == "hr") return "<hr>"

        val safeTags = setOf(
            "a", "abbr", "article", "b", "blockquote", "br", "caption", "code", "dd", "del", "details",
            "div", "dl", "dt", "em", "figcaption", "figure", "h1", "h2", "h3", "h4", "i", "ins", "kbd",
            "li", "mark", "ol", "p", "pre", "rp", "rt", "ruby", "s", "section", "small", "span",
            "strong", "sub", "summary", "sup", "table", "tbody", "td", "th", "thead", "tr", "u", "ul",
        )
        val content = renderNode(child, budget, depth + 1)
        val tag = rawTag.takeIf { it in safeTags }
        if (tag == null) return content
        return buildString {
            append('<').append(tag)
            appendStructuredAttributes(this, value, tag)
            append('>').append(content).append("</").append(tag).append('>')
        }
    }

    // Prefer populated content, then fall back to children or text.
    private fun structuredChild(value: JSONObject): Any? {
        for (key in listOf("content", "children", "text")) {
            if (!value.has(key) || value.isNull(key)) continue
            val candidate = value.opt(key)
            if (candidate is JSONArray && candidate.length() == 0) continue
            if (candidate is String && candidate.isEmpty()) continue
            if (candidate is List<*> && candidate.isEmpty()) continue
            return candidate
        }
        return null
    }

    // Keep harmless structured-content metadata and validated links; scripts and active content are dropped.
    private fun appendStructuredAttributes(out: StringBuilder, value: JSONObject, tag: String) {
        val data = value.optJSONObject("data")
        for (attribute in listOf("data-sc-content", "data-sc-class", "data-sc-code", "title", "lang")) {
            // Yomitan stores these values under data.content/class/code before adding the data-sc- prefix.
            val nestedName = attribute.removePrefix("data-sc-")
            val attributeValue = value.optString(attribute).takeIf(String::isNotBlank)
                ?: data?.optString(nestedName)?.takeIf(String::isNotBlank)
                ?: data?.optString(attribute)?.takeIf(String::isNotBlank)
                ?: continue
            out.append(' ').append(attribute).append("=\"").append(escape(attributeValue)).append('"')
        }
        if (tag == "a") {
            val href = value.optString("href")
            if (href.startsWith("https://", ignoreCase = true) || href.startsWith("http://", ignoreCase = true)) {
                out.append(" href=\"").append(escape(href)).append('"')
            }
        }
        if (tag == "td" || tag == "th") {
            for (attribute in listOf("colspan", "rowspan")) {
                val count = value.optInt(attribute, 0)
                if (count in 1..20) out.append(' ').append(attribute).append("=\"").append(count).append('"')
            }
        }
    }

    private fun renderText(value: String, budget: RenderBudget): String {
        if (budget.remaining <= 0) return ""
        val inputLimit = minOf(MAX_TEXT_LENGTH, budget.remaining.coerceAtLeast(1))
        val output = escape(value.take(inputLimit))
        budget.remaining = (budget.remaining - output.length).coerceAtLeast(0)
        return output
    }

    private fun styles(colors: YomitanPopupColors): String = """
        :root { color-scheme: ${if (colors.isDark) "dark" else "light"}; }
        * { box-sizing: border-box; }
        html, body { margin: 0; padding: 0; background: ${css(colors.background)}; color: ${css(colors.foreground)}; }
        body { font-family: Roboto, "Noto Sans", sans-serif; font-size: 15px; line-height: 1.45; }
        main { padding: 8px 10px 18px; overflow-wrap: anywhere; }
        .word-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 2px 0 8px; }
        .word-heading { min-width: 0; }
        .word { font-size: 40px; line-height: 1.2; font-weight: 700; }
        .popup-actions { display: flex; align-items: center; gap: 14px; flex: none; }
        .mine, .pronounce { display: inline-flex; align-items: center; justify-content: center; width: 34px; height: 34px; color: ${css(colors.foreground)}; }
        .mine svg { width: 26px; height: 26px; }
        .pronounce svg { width: 28px; height: 28px; }
        ruby { ruby-position: over; }
        rt { color: ${css(colors.secondary)}; font-size: 12px; font-weight: 500; }
        .matched, .form-line { color: ${css(colors.secondary)}; font-size: 13px; margin-top: 3px; }
        .metadata, .badges { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
        .metadata { margin: 2px 0 8px; }
        .pitch-row { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; margin: 0 0 8px; }
        .pitch-label { color: ${css(colors.secondary)}; font-size: 11px; font-weight: 600; line-height: 1.2; }
        .pitch-patterns { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 6px; max-width: 100%; }
        .badge { display: inline-flex; overflow: hidden; border: 1px solid ${css(colors.divider)}; border-radius: 5px; font-size: 12px; line-height: 1.3; }
        .badge b { padding: 3px 6px; background: #5795dc; color: #fff; }
        .badge span { padding: 3px 6px; }
        .frequency { background: ${css(colors.surface)}; border-color: #6fa7e8; color: ${css(colors.foreground)}; }
        .ipa { background: ${css(colors.surface)}; color: ${css(colors.secondary)}; }
        .ipa b { background: ${css(colors.accent)}; color: ${css(colors.accentForeground)}; }
        .tag { display: inline-block; padding: 2px 6px; border-radius: 4px; background: ${css(colors.surface)}; color: ${css(colors.secondary)}; font-size: 12px; }
        .subtitle { margin: 0 0 8px; padding: 7px 9px; border-left: 3px solid ${css(colors.accent)}; border-radius: 4px; background: ${css(colors.surface)}; color: ${css(colors.secondary)}; font-size: 13px; }
        mark { color: ${css(colors.accentForeground)}; background: ${css(colors.accent)}; border-radius: 2px; }
        .dictionary-group { border-top: 1px solid ${css(colors.divider)}; }
        .dictionary-group > summary { display: flex; align-items: center; gap: 7px; padding: 8px 0 5px; color: ${css(colors.secondary)}; cursor: pointer; list-style: none; }
        .dictionary-group > summary::-webkit-details-marker { display: none; }
        .dictionary-group > summary::before { content: "▸"; color: ${css(colors.secondary)}; font-size: 14px; }
        .dictionary-group[open] > summary::before { content: "▾"; }
        .group-name { flex: 1; font-weight: 600; }
        .group-entries { padding-left: 12px; }
        .entry { padding: 5px 0 10px; }
        .entry + .entry { border-top: 1px solid ${css(colors.divider)}; }
        .entry-heading { display: flex; align-items: baseline; gap: 8px; }
        .entry-term { flex: 1; font-size: 19px; font-weight: 650; }
        a { color: ${css(colors.accent)}; text-decoration: none; }
        .badges { margin: 4px 0 3px; }
        .glosses { margin: 3px 0 0; padding-left: 22px; }
        .glosses li { padding: 1px 0 4px 2px; }
        .glosses li::marker { color: ${css(colors.secondary)}; font-size: 13px; }
        .glosses p { margin: 2px 0; }
        .glosses ul, .glosses ol { margin: 3px 0; padding-left: 20px; }
        .glosses blockquote { margin: 4px 0; padding-left: 8px; border-left: 2px solid ${css(colors.divider)}; color: ${css(colors.secondary)}; }
        .glosses table { border-collapse: collapse; margin: 4px 0; }
        .glosses td, .glosses th { padding: 2px 5px; border: 1px solid ${css(colors.divider)}; }
        .glosses [data-sc-content="sense-group"], .glosses [data-sc-content="sense"] { display: block; }
        .glosses [data-sc-content="sense-group"] + [data-sc-content="sense-group"] { margin-top: 7px; }
        .glosses [data-sc-class="tag"] { display: inline-block; margin: 0 4px 3px 0; padding: 1px 5px; border-radius: 4px; background: ${css(colors.surface)}; color: ${css(colors.secondary)}; font-size: .78em; font-weight: 650; line-height: 1.35; vertical-align: baseline; }
        .glosses span[data-sc-code] { display: inline-block; margin: 0 4px 3px 0; padding: 1px 5px; border-radius: 4px; background: ${css(colors.surface)}; color: ${css(colors.secondary)}; font-size: .78em; font-weight: 650; line-height: 1.35; vertical-align: baseline; }
        .glosses [data-sc-class="extra-box"] { width: fit-content; max-width: 100%; margin: 5px 0; padding: 6px 8px; border-left: 2px solid ${css(colors.divider)}; border-radius: 4px; background: ${css(colors.surface)}; }
        .glosses [data-sc-class="extra-content"] { margin-left: 8px; }
        .glosses [data-sc-class="extra-label"] { color: ${css(colors.secondary)}; font-size: .82em; font-style: italic; }
        .glosses [data-sc-class="old-character"] { text-emphasis-style: dot; }
        .glosses [data-sc-content="part-of-speech-info"], .glosses [data-sc-content="forms-label"] { background: ${css(colors.secondary)}; color: ${css(colors.background)}; }
        .glosses [data-sc-content="misc-info"] { background: #8a4b42; color: #fff; }
        .glosses [data-sc-content="field-info"] { background: #7053a6; color: #fff; }
        .glosses [data-sc-content="dialect-info"] { background: #397a50; color: #fff; }
        .glosses [data-sc-content="lang-source-wasei"] { background: #b36b24; color: #fff; }
        .glosses ul[data-sc-content="glossary"] { margin: 2px 0 4px; padding-left: 19px; list-style: disc; }
        .glosses [data-sc-content="extra-info"] { margin: 4px 0 4px 8px; }
        .glosses [data-sc-content="example-sentence"] { width: fit-content; max-width: 100%; margin: 5px 0; padding: 5px 8px; border-left: 2px solid ${css(colors.divider)}; border-radius: 4px; background: ${css(colors.surface)}; }
        .glosses [data-sc-content="example-sentence-a"] { font-size: 1.04em; }
        .glosses [data-sc-content="example-sentence-b"] { color: ${css(colors.secondary)}; font-size: .84em; }
        .glosses [data-sc-content="example-keyword"] { color: ${css(colors.accent)}; }
        .glosses [data-sc-content="attribution"] { margin-top: 5px; text-align: right; font-size: .72em; }
        .glosses [data-sc-content="extra-label"], .glosses [data-sc-content="attribution-footnote"] { color: ${css(colors.secondary)}; font-size: .82em; }
        .glosses [data-sc-content="xref"], .glosses [data-sc-content="antonym"] { margin: 5px 0; padding: 4px 7px; border-left: 2px solid ${css(colors.accent)}; background: ${css(colors.surface)}; }
        .glosses [data-sc-content="registered-trademark"] { vertical-align: super; font-size: .65em; }
        .glosses a { color: ${css(colors.accent)}; text-decoration: underline; text-underline-offset: 2px; }
        .glosses [title] { text-decoration: underline dotted ${css(colors.divider)}; text-underline-offset: 2px; }
        .media-note { color: ${css(colors.secondary)}; font-size: 12px; }
        .empty { padding: 16px 0; color: ${css(colors.secondary)}; }
        hr { border: 0; border-top: 1px solid ${css(colors.divider)}; }
    """.trimIndent() + "\n" + PitchAccentGraph.styles(colors)

    private fun css(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)

    private fun addIcon(color: Int, contrast: Int): String = """
        <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
          <circle cx="12" cy="12" r="10" fill="${css(color)}"/>
          <path d="M12 7v10M7 12h10" fill="none" stroke="${css(contrast)}" stroke-linecap="round" stroke-width="2"/>
        </svg>
    """.trimIndent()

    private fun checkIcon(color: Int, contrast: Int): String = """
        <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
          <circle cx="12" cy="12" r="10" fill="${css(color)}"/>
          <path d="m7 12.5 3.2 3.2L17.5 8" fill="none" stroke="${css(contrast)}" stroke-linecap="round" stroke-linejoin="round" stroke-width="2"/>
        </svg>
    """.trimIndent()

    private fun speakerIcon(color: Int): String = """
        <svg viewBox="0 0 24 24" aria-hidden="true" xmlns="http://www.w3.org/2000/svg">
          <path fill="${css(color)}" d="M3 9v6h4l5 4V5L7 9H3z"/>
          <path fill="none" stroke="${css(color)}" stroke-linecap="round" stroke-width="2" d="M16 9a4 4 0 0 1 0 6M18.5 6.5a8 8 0 0 1 0 11"/>
        </svg>
    """.trimIndent()

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
