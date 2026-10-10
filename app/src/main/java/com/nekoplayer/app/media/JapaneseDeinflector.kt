// Copyright (C) 2024-2026 Yomitan Authors
// Japanese inflection rules and logic adapted under GPL-3.0-or-later.
// Upstream revision: d34832d756e05dc00945e5b7d7ebc80963299a7a
// Source: https://github.com/yomidevs/yomitan/blob/d34832d756e05dc00945e5b7d7ebc80963299a7a/ext/js/language/ja/japanese-transforms.js
// See THIRD_PARTY_NOTICES.md and LICENSE.
package com.nekoplayer.app.media

data class DeinflectedCandidate(
    val term: String,
    val conditionFlags: Set<String>,
    val reasons: List<String>,
) {
    fun matchesDictionaryRules(dictionaryRules: Iterable<String>?): Boolean {
        if (conditionFlags.isEmpty()) return true
        if (dictionaryRules == null) return false
        // Only dictionary-form conditions can validate a dictionary POS rule.
        // Intermediate forms such as -た and v1p can't validate an entry alone.
        val dictionaryFlags = conditionFlags(dictionaryRules.filter { it in dictionaryFormConditions })
        return conditionFlags.any(dictionaryFlags::contains)
    }
}

private val conditionChildren = mapOf(
    "v" to listOf("v1", "v5", "vk", "vs", "vz"),
    "v1" to listOf("v1d", "v1p"),
    "v5" to listOf("v5d", "v5s"),
    "v5s" to listOf("v5ss", "v5sp"),
)

private val dictionaryFormConditions = setOf("v1", "v5", "vk", "vs", "vz", "adj-i")

private val knownConditions = setOf(
    "v", "v1", "v1d", "v1p", "v5", "v5d", "v5s", "v5ss", "v5sp", "vk", "vs", "vz", "adj-i",
    "-ます", "-ません", "-て", "-ば", "-く", "-た", "-ん", "-なさい", "-ゃ",
)

private fun conditionFlags(conditions: Iterable<String>): Set<String> {
    val flags = HashSet<String>()
    val pending = ArrayDeque<String>().apply { addAll(conditions) }
    while (pending.isNotEmpty()) {
        val condition = pending.removeLast()
        val children = conditionChildren[condition]
        if (children == null) flags += condition else pending.addAll(children)
    }
    return flags
}

class JapaneseDeinflector {
    fun deinflect(surface: String): List<DeinflectedCandidate> {
        if (surface.isEmpty()) return emptyList()
        val states = ArrayList<TransformState>()
        states += TransformState(surface, emptySet(), emptyList(), emptySet())
        var stateIndex = 0
        while (stateIndex < states.size) {
            val state = states[stateIndex++]
            for ((ruleIndex, rule) in RULES.withIndex()) {
                if (!conditionsMatch(state.conditionFlags, rule.conditionFlagsIn)) continue
                if (!rule.matches(state.term)) continue
                val applicationKey = "$ruleIndex\u0000${state.term}"
                if (applicationKey in state.appliedRules) continue
                states += TransformState(
                    term = rule.apply(state.term),
                    conditionFlags = rule.conditionFlagsOut,
                    reasons = state.reasons + rule.transformId,
                    appliedRules = state.appliedRules + applicationKey,
                )
            }
        }
        return states.map { DeinflectedCandidate(it.term, it.conditionFlags, it.reasons) }
    }

    private data class TransformState(
        val term: String,
        val conditionFlags: Set<String>,
        val reasons: List<String>,
        val appliedRules: Set<String>,
    )

    private data class TransformRule(
        val transformId: String,
        val inflected: String,
        val deinflected: String,
        val conditionFlagsIn: Set<String>,
        val conditionFlagsOut: Set<String>,
        val wholeWord: Boolean,
    ) {
        fun matches(term: String): Boolean = if (wholeWord) term == inflected else term.endsWith(inflected)

        fun apply(term: String): String {
            if (wholeWord || inflected.isEmpty()) return deinflected
            return term.substring(0, term.length - inflected.length) + deinflected
        }
    }

    private companion object {
        val RULES: List<TransformRule> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            RULE_TABLE.lineSequence().filter(String::isNotEmpty).map { line ->
                val fields = splitRuleFields(line)
                require(fields.size == 6) { "Malformed embedded Japanese transform rule." }
                TransformRule(
                    transformId = fields[0],
                    inflected = fields[1],
                    deinflected = fields[2],
                    conditionFlagsIn = conditionFlags(parseConditions(fields[3])),
                    conditionFlagsOut = conditionFlags(parseConditions(fields[4])),
                    wholeWord = fields[5] == "whole",
                )
            }.toList()
        }

        fun splitRuleFields(line: String): List<String> {
            val result = ArrayList<String>(6)
            var start = 0
            repeat(5) {
                val tab = line.indexOf('\t', start)
                require(tab >= 0) { "Malformed embedded Japanese transform rule." }
                result += line.substring(start, tab)
                start = tab + 1
            }
            result += line.substring(start)
            return result
        }

        fun parseConditions(value: String): Set<String> {
            if (value.isEmpty()) return emptySet()
            val conditions = value.split(',').toSet()
            require(conditions.all { it in knownConditions }) { "Unknown Yomitan condition in embedded rule." }
            return conditions
        }

        // The first transform can use any rule; later ones must share a condition flag.
        fun conditionsMatch(current: Set<String>, required: Set<String>): Boolean =
            current.isEmpty() || current.any(required::contains)

        const val RULE_TABLE: String = """

-ば	ければ	い	-ば	adj-i	suffix
-ば	えば	う	-ば	v5	suffix
-ば	へば	う	-ば	v5	suffix
-ば	けば	く	-ば	v5	suffix
-ば	げば	ぐ	-ば	v5	suffix
-ば	せば	す	-ば	v5	suffix
-ば	てば	つ	-ば	v5	suffix
-ば	ねば	ぬ	-ば	v5	suffix
-ば	べば	ぶ	-ば	v5	suffix
-ば	めば	む	-ば	v5	suffix
-ば	れば	る	-ば	v1,v5,vk,vs,vz	suffix
-ば	れば		-ば	-ます	suffix
-ゃ	けりゃ	ければ	-ゃ	-ば	suffix
-ゃ	きゃ	ければ	-ゃ	-ば	suffix
-ゃ	や	えば	-ゃ	-ば	suffix
-ゃ	きゃ	けば	-ゃ	-ば	suffix
-ゃ	ぎゃ	げば	-ゃ	-ば	suffix
-ゃ	しゃ	せば	-ゃ	-ば	suffix
-ゃ	ちゃ	てば	-ゃ	-ば	suffix
-ゃ	にゃ	ねば	-ゃ	-ば	suffix
-ゃ	びゃ	べば	-ゃ	-ば	suffix
-ゃ	みゃ	めば	-ゃ	-ば	suffix
-ゃ	りゃ	れば	-ゃ	-ば	suffix
-ちゃ	ちゃ	る	v5	v1	suffix
-ちゃ	いじゃ	ぐ	v5	v5	suffix
-ちゃ	いちゃ	く	v5	v5	suffix
-ちゃ	しちゃ	す	v5	v5	suffix
-ちゃ	っちゃ	う	v5	v5	suffix
-ちゃ	っちゃ	く	v5	v5	suffix
-ちゃ	っちゃ	つ	v5	v5	suffix
-ちゃ	っちゃ	る	v5	v5	suffix
-ちゃ	んじゃ	ぬ	v5	v5	suffix
-ちゃ	んじゃ	ぶ	v5	v5	suffix
-ちゃ	んじゃ	む	v5	v5	suffix
-ちゃ	じちゃ	ずる	v5	vz	suffix
-ちゃ	しちゃ	する	v5	vs	suffix
-ちゃ	為ちゃ	為る	v5	vs	suffix
-ちゃ	きちゃ	くる	v5	vk	suffix
-ちゃ	来ちゃ	来る	v5	vk	suffix
-ちゃ	來ちゃ	來る	v5	vk	suffix
-ちゃう	ちゃう	る	v5	v1	suffix
-ちゃう	いじゃう	ぐ	v5	v5	suffix
-ちゃう	いちゃう	く	v5	v5	suffix
-ちゃう	しちゃう	す	v5	v5	suffix
-ちゃう	っちゃう	う	v5	v5	suffix
-ちゃう	っちゃう	く	v5	v5	suffix
-ちゃう	っちゃう	つ	v5	v5	suffix
-ちゃう	っちゃう	る	v5	v5	suffix
-ちゃう	んじゃう	ぬ	v5	v5	suffix
-ちゃう	んじゃう	ぶ	v5	v5	suffix
-ちゃう	んじゃう	む	v5	v5	suffix
-ちゃう	じちゃう	ずる	v5	vz	suffix
-ちゃう	しちゃう	する	v5	vs	suffix
-ちゃう	為ちゃう	為る	v5	vs	suffix
-ちゃう	きちゃう	くる	v5	vk	suffix
-ちゃう	来ちゃう	来る	v5	vk	suffix
-ちゃう	來ちゃう	來る	v5	vk	suffix
-ちまう	ちまう	る	v5	v1	suffix
-ちまう	いじまう	ぐ	v5	v5	suffix
-ちまう	いちまう	く	v5	v5	suffix
-ちまう	しちまう	す	v5	v5	suffix
-ちまう	っちまう	う	v5	v5	suffix
-ちまう	っちまう	く	v5	v5	suffix
-ちまう	っちまう	つ	v5	v5	suffix
-ちまう	っちまう	る	v5	v5	suffix
-ちまう	んじまう	ぬ	v5	v5	suffix
-ちまう	んじまう	ぶ	v5	v5	suffix
-ちまう	んじまう	む	v5	v5	suffix
-ちまう	じちまう	ずる	v5	vz	suffix
-ちまう	しちまう	する	v5	vs	suffix
-ちまう	為ちまう	為る	v5	vs	suffix
-ちまう	きちまう	くる	v5	vk	suffix
-ちまう	来ちまう	来る	v5	vk	suffix
-ちまう	來ちまう	來る	v5	vk	suffix
-しまう	てしまう	て	v5	-て	suffix
-しまう	でしまう	で	v5	-て	suffix
-なさい	なさい	る	-なさい	v1	suffix
-なさい	いなさい	う	-なさい	v5	suffix
-なさい	きなさい	く	-なさい	v5	suffix
-なさい	ぎなさい	ぐ	-なさい	v5	suffix
-なさい	しなさい	す	-なさい	v5	suffix
-なさい	ちなさい	つ	-なさい	v5	suffix
-なさい	になさい	ぬ	-なさい	v5	suffix
-なさい	びなさい	ぶ	-なさい	v5	suffix
-なさい	みなさい	む	-なさい	v5	suffix
-なさい	りなさい	る	-なさい	v5	suffix
-なさい	じなさい	ずる	-なさい	vz	suffix
-なさい	しなさい	する	-なさい	vs	suffix
-なさい	為なさい	為る	-なさい	vs	suffix
-なさい	きなさい	くる	-なさい	vk	suffix
-なさい	来なさい	来る	-なさい	vk	suffix
-なさい	來なさい	來る	-なさい	vk	suffix
-そう	そう	い		adj-i	suffix
-そう	さう	い		adj-i	suffix
-そう	そう	る		v1	suffix
-そう	さう	る		v1	suffix
-そう	いそう	う		v5	suffix
-そう	ひさう	う		v5	suffix
-そう	きそう	く		v5	suffix
-そう	きさう	く		v5	suffix
-そう	ぎそう	ぐ		v5	suffix
-そう	ぎさう	ぐ		v5	suffix
-そう	しそう	す		v5	suffix
-そう	しさう	す		v5	suffix
-そう	ちそう	つ		v5	suffix
-そう	ちさう	つ		v5	suffix
-そう	にそう	ぬ		v5	suffix
-そう	にさう	ぬ		v5	suffix
-そう	びそう	ぶ		v5	suffix
-そう	びさう	ぶ		v5	suffix
-そう	みそう	む		v5	suffix
-そう	みさう	む		v5	suffix
-そう	りそう	る		v5	suffix
-そう	りさう	る		v5	suffix
-そう	じそう	ずる		vz	suffix
-そう	じさう	ずる		vz	suffix
-そう	しそう	する		vs	suffix
-そう	しさう	する		vs	suffix
-そう	為そう	為る		vs	suffix
-そう	為さう	為る		vs	suffix
-そう	きそう	くる		vk	suffix
-そう	きさう	くる		vk	suffix
-そう	来そう	来る		vk	suffix
-そう	来さう	来る		vk	suffix
-そう	來そう	來る		vk	suffix
-そう	來さう	來る		vk	suffix
-すぎる	すぎる	い	v1	adj-i	suffix
-すぎる	すぎる	る	v1	v1	suffix
-すぎる	いすぎる	う	v1	v5	suffix
-すぎる	きすぎる	く	v1	v5	suffix
-すぎる	ぎすぎる	ぐ	v1	v5	suffix
-すぎる	しすぎる	す	v1	v5	suffix
-すぎる	ちすぎる	つ	v1	v5	suffix
-すぎる	にすぎる	ぬ	v1	v5	suffix
-すぎる	びすぎる	ぶ	v1	v5	suffix
-すぎる	みすぎる	む	v1	v5	suffix
-すぎる	りすぎる	る	v1	v5	suffix
-すぎる	じすぎる	ずる	v1	vz	suffix
-すぎる	しすぎる	する	v1	vs	suffix
-すぎる	為すぎる	為る	v1	vs	suffix
-すぎる	きすぎる	くる	v1	vk	suffix
-すぎる	来すぎる	来る	v1	vk	suffix
-すぎる	來すぎる	來る	v1	vk	suffix
-過ぎる	過ぎる	い	v1	adj-i	suffix
-過ぎる	過ぎる	る	v1	v1	suffix
-過ぎる	い過ぎる	う	v1	v5	suffix
-過ぎる	き過ぎる	く	v1	v5	suffix
-過ぎる	ぎ過ぎる	ぐ	v1	v5	suffix
-過ぎる	し過ぎる	す	v1	v5	suffix
-過ぎる	ち過ぎる	つ	v1	v5	suffix
-過ぎる	に過ぎる	ぬ	v1	v5	suffix
-過ぎる	び過ぎる	ぶ	v1	v5	suffix
-過ぎる	み過ぎる	む	v1	v5	suffix
-過ぎる	り過ぎる	る	v1	v5	suffix
-過ぎる	じ過ぎる	ずる	v1	vz	suffix
-過ぎる	し過ぎる	する	v1	vs	suffix
-過ぎる	為過ぎる	為る	v1	vs	suffix
-過ぎる	き過ぎる	くる	v1	vk	suffix
-過ぎる	来過ぎる	来る	v1	vk	suffix
-過ぎる	來過ぎる	來る	v1	vk	suffix
-たい	たい	る	adj-i	v1	suffix
-たい	いたい	う	adj-i	v5	suffix
-たい	きたい	く	adj-i	v5	suffix
-たい	ぎたい	ぐ	adj-i	v5	suffix
-たい	したい	す	adj-i	v5	suffix
-たい	ちたい	つ	adj-i	v5	suffix
-たい	にたい	ぬ	adj-i	v5	suffix
-たい	びたい	ぶ	adj-i	v5	suffix
-たい	みたい	む	adj-i	v5	suffix
-たい	りたい	る	adj-i	v5	suffix
-たい	じたい	ずる	adj-i	vz	suffix
-たい	したい	する	adj-i	vs	suffix
-たい	為たい	為る	adj-i	vs	suffix
-たい	きたい	くる	adj-i	vk	suffix
-たい	来たい	来る	adj-i	vk	suffix
-たい	來たい	來る	adj-i	vk	suffix
-たら	かったら	い		adj-i	suffix
-たら	たら	る		v1	suffix
-たら	いたら	く		v5	suffix
-たら	いだら	ぐ		v5	suffix
-たら	したら	す		v5	suffix
-たら	ったら	う		v5	suffix
-たら	ったら	つ		v5	suffix
-たら	ったら	る		v5	suffix
-たら	んだら	ぬ		v5	suffix
-たら	んだら	ぶ		v5	suffix
-たら	んだら	む		v5	suffix
-たら	じたら	ずる		vz	suffix
-たら	したら	する		vs	suffix
-たら	為たら	為る		vs	suffix
-たら	きたら	くる		vk	suffix
-たら	来たら	来る		vk	suffix
-たら	來たら	來る		vk	suffix
-たら	いったら	いく		v5	suffix
-たら	行ったら	行く		v5	suffix
-たら	逝ったら	逝く		v5	suffix
-たら	往ったら	往く		v5	suffix
-たら	こうたら	こう		v5	suffix
-たら	とうたら	とう		v5	suffix
-たら	請うたら	請う		v5	suffix
-たら	乞うたら	乞う		v5	suffix
-たら	恋うたら	恋う		v5	suffix
-たら	問うたら	問う		v5	suffix
-たら	訪うたら	訪う		v5	suffix
-たら	宣うたら	宣う		v5	suffix
-たら	曰うたら	曰う		v5	suffix
-たら	給うたら	給う		v5	suffix
-たら	賜うたら	賜う		v5	suffix
-たら	揺蕩うたら	揺蕩う		v5	suffix
-たら	のたもうたら	のたまう		v5	suffix
-たら	たもうたら	たまう		v5	suffix
-たら	たゆとうたら	たゆたう		v5	suffix
-たら	ましたら	ます		-ます	suffix
-たり	かったり	い		adj-i	suffix
-たり	たり	る		v1	suffix
-たり	いたり	く		v5	suffix
-たり	いだり	ぐ		v5	suffix
-たり	したり	す		v5	suffix
-たり	ったり	う		v5	suffix
-たり	ったり	つ		v5	suffix
-たり	ったり	る		v5	suffix
-たり	んだり	ぬ		v5	suffix
-たり	んだり	ぶ		v5	suffix
-たり	んだり	む		v5	suffix
-たり	じたり	ずる		vz	suffix
-たり	したり	する		vs	suffix
-たり	為たり	為る		vs	suffix
-たり	きたり	くる		vk	suffix
-たり	来たり	来る		vk	suffix
-たり	來たり	來る		vk	suffix
-たり	いったり	いく		v5	suffix
-たり	行ったり	行く		v5	suffix
-たり	逝ったり	逝く		v5	suffix
-たり	往ったり	往く		v5	suffix
-たり	こうたり	こう		v5	suffix
-たり	とうたり	とう		v5	suffix
-たり	請うたり	請う		v5	suffix
-たり	乞うたり	乞う		v5	suffix
-たり	恋うたり	恋う		v5	suffix
-たり	問うたり	問う		v5	suffix
-たり	訪うたり	訪う		v5	suffix
-たり	宣うたり	宣う		v5	suffix
-たり	曰うたり	曰う		v5	suffix
-たり	給うたり	給う		v5	suffix
-たり	賜うたり	賜う		v5	suffix
-たり	揺蕩うたり	揺蕩う		v5	suffix
-たり	のたもうたり	のたまう		v5	suffix
-たり	たもうたり	たまう		v5	suffix
-たり	たゆとうたり	たゆたう		v5	suffix
-て	くて	い	-て	adj-i	suffix
-て	て	る	-て	v1	suffix
-て	いて	く	-て	v5	suffix
-て	いで	ぐ	-て	v5	suffix
-て	して	す	-て	v5	suffix
-て	って	う	-て	v5	suffix
-て	って	つ	-て	v5	suffix
-て	って	る	-て	v5	suffix
-て	んで	ぬ	-て	v5	suffix
-て	んで	ぶ	-て	v5	suffix
-て	んで	む	-て	v5	suffix
-て	じて	ずる	-て	vz	suffix
-て	して	する	-て	vs	suffix
-て	為て	為る	-て	vs	suffix
-て	きて	くる	-て	vk	suffix
-て	来て	来る	-て	vk	suffix
-て	來て	來る	-て	vk	suffix
-て	いって	いく	-て	v5	suffix
-て	行って	行く	-て	v5	suffix
-て	逝って	逝く	-て	v5	suffix
-て	往って	往く	-て	v5	suffix
-て	こうて	こう	-て	v5	suffix
-て	とうて	とう	-て	v5	suffix
-て	請うて	請う	-て	v5	suffix
-て	乞うて	乞う	-て	v5	suffix
-て	恋うて	恋う	-て	v5	suffix
-て	問うて	問う	-て	v5	suffix
-て	訪うて	訪う	-て	v5	suffix
-て	宣うて	宣う	-て	v5	suffix
-て	曰うて	曰う	-て	v5	suffix
-て	給うて	給う	-て	v5	suffix
-て	賜うて	賜う	-て	v5	suffix
-て	揺蕩うて	揺蕩う	-て	v5	suffix
-て	のたもうて	のたまう	-て	v5	suffix
-て	たもうて	たまう	-て	v5	suffix
-て	たゆとうて	たゆたう	-て	v5	suffix
-て	まして	ます		-ます	suffix
-ず	ず	る		v1	suffix
-ず	かず	く		v5	suffix
-ず	がず	ぐ		v5	suffix
-ず	さず	す		v5	suffix
-ず	たず	つ		v5	suffix
-ず	なず	ぬ		v5	suffix
-ず	ばず	ぶ		v5	suffix
-ず	まず	む		v5	suffix
-ず	らず	る		v5	suffix
-ず	わず	う		v5	suffix
-ず	はず	う		v5	suffix
-ず	ぜず	ずる		vz	suffix
-ず	せず	する		vs	suffix
-ず	為ず	為る		vs	suffix
-ず	こず	くる		vk	suffix
-ず	来ず	来る		vk	suffix
-ず	來ず	來る		vk	suffix
-ぬ	ぬ	る		v1	suffix
-ぬ	かぬ	く		v5	suffix
-ぬ	がぬ	ぐ		v5	suffix
-ぬ	さぬ	す		v5	suffix
-ぬ	たぬ	つ		v5	suffix
-ぬ	なぬ	ぬ		v5	suffix
-ぬ	ばぬ	ぶ		v5	suffix
-ぬ	まぬ	む		v5	suffix
-ぬ	らぬ	る		v5	suffix
-ぬ	わぬ	う		v5	suffix
-ぬ	はぬ	う		v5	suffix
-ぬ	ぜぬ	ずる		vz	suffix
-ぬ	せぬ	する		vs	suffix
-ぬ	為ぬ	為る		vs	suffix
-ぬ	こぬ	くる		vk	suffix
-ぬ	来ぬ	来る		vk	suffix
-ぬ	來ぬ	來る		vk	suffix
-ん	ん	る	-ん	v1	suffix
-ん	かん	く	-ん	v5	suffix
-ん	がん	ぐ	-ん	v5	suffix
-ん	さん	す	-ん	v5	suffix
-ん	たん	つ	-ん	v5	suffix
-ん	なん	ぬ	-ん	v5	suffix
-ん	ばん	ぶ	-ん	v5	suffix
-ん	まん	む	-ん	v5	suffix
-ん	らん	る	-ん	v5	suffix
-ん	わん	う	-ん	v5	suffix
-ん	はん	う	-ん	v5	suffix
-ん	ぜん	ずる	-ん	vz	suffix
-ん	せん	する	-ん	vs	suffix
-ん	為ん	為る	-ん	vs	suffix
-ん	こん	くる	-ん	vk	suffix
-ん	来ん	来る	-ん	vk	suffix
-ん	來ん	來る	-ん	vk	suffix
-んばかり	んばかり	る		v1	suffix
-んばかり	かんばかり	く		v5	suffix
-んばかり	がんばかり	ぐ		v5	suffix
-んばかり	さんばかり	す		v5	suffix
-んばかり	たんばかり	つ		v5	suffix
-んばかり	なんばかり	ぬ		v5	suffix
-んばかり	ばんばかり	ぶ		v5	suffix
-んばかり	まんばかり	む		v5	suffix
-んばかり	らんばかり	る		v5	suffix
-んばかり	わんばかり	う		v5	suffix
-んばかり	はんばかり	う		v5	suffix
-んばかり	ぜんばかり	ずる		vz	suffix
-んばかり	せんばかり	する		vs	suffix
-んばかり	為んばかり	為る		vs	suffix
-んばかり	こんばかり	くる		vk	suffix
-んばかり	来んばかり	来る		vk	suffix
-んばかり	來んばかり	來る		vk	suffix
-んとする	んとする	る	vs	v1	suffix
-んとする	かんとする	く	vs	v5	suffix
-んとする	がんとする	ぐ	vs	v5	suffix
-んとする	さんとする	す	vs	v5	suffix
-んとする	たんとする	つ	vs	v5	suffix
-んとする	なんとする	ぬ	vs	v5	suffix
-んとする	ばんとする	ぶ	vs	v5	suffix
-んとする	まんとする	む	vs	v5	suffix
-んとする	らんとする	る	vs	v5	suffix
-んとする	わんとする	う	vs	v5	suffix
-んとする	はんとする	う	vs	v5	suffix
-んとする	ぜんとする	ずる	vs	vz	suffix
-んとする	せんとする	する	vs	vs	suffix
-んとする	為んとする	為る	vs	vs	suffix
-んとする	こんとする	くる	vs	vk	suffix
-んとする	来んとする	来る	vs	vk	suffix
-んとする	來んとする	來る	vs	vk	suffix
-む	む	る		v1	suffix
-む	かむ	く		v5	suffix
-む	がむ	ぐ		v5	suffix
-む	さむ	す		v5	suffix
-む	たむ	つ		v5	suffix
-む	なむ	ぬ		v5	suffix
-む	ばむ	ぶ		v5	suffix
-む	まむ	む		v5	suffix
-む	らむ	る		v5	suffix
-む	わむ	う		v5	suffix
-む	はむ	う		v5	suffix
-む	ぜむ	ずる		vz	suffix
-む	せむ	する		vs	suffix
-む	為む	為る		vs	suffix
-む	こむ	くる		vk	suffix
-む	来む	来る		vk	suffix
-む	來む	來る		vk	suffix
-ざる	ざる	る		v1	suffix
-ざる	かざる	く		v5	suffix
-ざる	がざる	ぐ		v5	suffix
-ざる	さざる	す		v5	suffix
-ざる	たざる	つ		v5	suffix
-ざる	なざる	ぬ		v5	suffix
-ざる	ばざる	ぶ		v5	suffix
-ざる	まざる	む		v5	suffix
-ざる	らざる	る		v5	suffix
-ざる	わざる	う		v5	suffix
-ざる	はざる	う		v5	suffix
-ざる	ぜざる	ずる		vz	suffix
-ざる	せざる	する		vs	suffix
-ざる	為ざる	為る		vs	suffix
-ざる	こざる	くる		vk	suffix
-ざる	来ざる	来る		vk	suffix
-ざる	來ざる	來る		vk	suffix
-ねば	ねば	る	-ば	v1	suffix
-ねば	かねば	く	-ば	v5	suffix
-ねば	がねば	ぐ	-ば	v5	suffix
-ねば	さねば	す	-ば	v5	suffix
-ねば	たねば	つ	-ば	v5	suffix
-ねば	なねば	ぬ	-ば	v5	suffix
-ねば	ばねば	ぶ	-ば	v5	suffix
-ねば	まねば	む	-ば	v5	suffix
-ねば	らねば	る	-ば	v5	suffix
-ねば	わねば	う	-ば	v5	suffix
-ねば	はねば	う	-ば	v5	suffix
-ねば	ぜねば	ずる	-ば	vz	suffix
-ねば	せねば	する	-ば	vs	suffix
-ねば	為ねば	為る	-ば	vs	suffix
-ねば	こねば	くる	-ば	vk	suffix
-ねば	来ねば	来る	-ば	vk	suffix
-ねば	來ねば	來る	-ば	vk	suffix
-く	く	い	-く	adj-i	suffix
causative	させる	る	v1	v1	suffix
causative	かせる	く	v1	v5	suffix
causative	がせる	ぐ	v1	v5	suffix
causative	させる	す	v1	v5	suffix
causative	たせる	つ	v1	v5	suffix
causative	なせる	ぬ	v1	v5	suffix
causative	ばせる	ぶ	v1	v5	suffix
causative	ませる	む	v1	v5	suffix
causative	らせる	る	v1	v5	suffix
causative	わせる	う	v1	v5	suffix
causative	はせる	う	v1	v5	suffix
causative	じさせる	ずる	v1	vz	suffix
causative	ぜさせる	ずる	v1	vz	suffix
causative	させる	する	v1	vs	suffix
causative	為せる	為る	v1	vs	suffix
causative	せさせる	する	v1	vs	suffix
causative	為させる	為る	v1	vs	suffix
causative	こさせる	くる	v1	vk	suffix
causative	来させる	来る	v1	vk	suffix
causative	來させる	來る	v1	vk	suffix
short causative	さす	る	v5ss	v1	suffix
short causative	かす	く	v5sp	v5	suffix
short causative	がす	ぐ	v5sp	v5	suffix
short causative	さす	す	v5ss	v5	suffix
short causative	たす	つ	v5sp	v5	suffix
short causative	なす	ぬ	v5sp	v5	suffix
short causative	ばす	ぶ	v5sp	v5	suffix
short causative	ます	む	v5sp	v5	suffix
short causative	らす	る	v5sp	v5	suffix
short causative	わす	う	v5sp	v5	suffix
short causative	はす	う	v5sp	v5	suffix
short causative	じさす	ずる	v5ss	vz	suffix
short causative	ぜさす	ずる	v5ss	vz	suffix
short causative	さす	する	v5ss	vs	suffix
short causative	為す	為る	v5ss	vs	suffix
short causative	こさす	くる	v5ss	vk	suffix
short causative	来さす	来る	v5ss	vk	suffix
short causative	來さす	來る	v5ss	vk	suffix
imperative	ろ	る		v1	suffix
imperative	よ	る		v1	suffix
imperative	え	う		v5	suffix
imperative	へ	う		v5	suffix
imperative	け	く		v5	suffix
imperative	げ	ぐ		v5	suffix
imperative	せ	す		v5	suffix
imperative	て	つ		v5	suffix
imperative	ね	ぬ		v5	suffix
imperative	べ	ぶ		v5	suffix
imperative	め	む		v5	suffix
imperative	れ	る		v5	suffix
imperative	じろ	ずる		vz	suffix
imperative	ぜよ	ずる		vz	suffix
imperative	しろ	する		vs	suffix
imperative	せよ	する		vs	suffix
imperative	為ろ	為る		vs	suffix
imperative	為よ	為る		vs	suffix
imperative	こい	くる		vk	suffix
imperative	来い	来る		vk	suffix
imperative	來い	來る		vk	suffix
continuative	い	いる		v1d	suffix
continuative	え	える		v1d	suffix
continuative	き	きる		v1d	suffix
continuative	ぎ	ぎる		v1d	suffix
continuative	け	ける		v1d	suffix
continuative	げ	げる		v1d	suffix
continuative	じ	じる		v1d	suffix
continuative	せ	せる		v1d	suffix
continuative	ぜ	ぜる		v1d	suffix
continuative	ち	ちる		v1d	suffix
continuative	て	てる		v1d	suffix
continuative	で	でる		v1d	suffix
continuative	に	にる		v1d	suffix
continuative	ね	ねる		v1d	suffix
continuative	ひ	ひる		v1d	suffix
continuative	び	びる		v1d	suffix
continuative	へ	へる		v1d	suffix
continuative	べ	べる		v1d	suffix
continuative	み	みる		v1d	suffix
continuative	め	める		v1d	suffix
continuative	り	りる		v1d	suffix
continuative	れ	れる		v1d	suffix
continuative	い	う		v5	suffix
continuative	ひ	う		v5	suffix
continuative	き	く		v5	suffix
continuative	ぎ	ぐ		v5	suffix
continuative	し	す		v5	suffix
continuative	ち	つ		v5	suffix
continuative	に	ぬ		v5	suffix
continuative	び	ぶ		v5	suffix
continuative	み	む		v5	suffix
continuative	り	る		v5	suffix
continuative	き	くる		vk	suffix
continuative	し	する		vs	suffix
continuative	来	来る		vk	suffix
continuative	來	來る		vk	suffix
negative	くない	い	adj-i	adj-i	suffix
negative	ない	る	adj-i	v1	suffix
negative	かない	く	adj-i	v5	suffix
negative	がない	ぐ	adj-i	v5	suffix
negative	さない	す	adj-i	v5	suffix
negative	たない	つ	adj-i	v5	suffix
negative	なない	ぬ	adj-i	v5	suffix
negative	ばない	ぶ	adj-i	v5	suffix
negative	まない	む	adj-i	v5	suffix
negative	らない	る	adj-i	v5	suffix
negative	わない	う	adj-i	v5	suffix
negative	はない	う	adj-i	v5	suffix
negative	じない	ずる	adj-i	vz	suffix
negative	しない	する	adj-i	vs	suffix
negative	為ない	為る	adj-i	vs	suffix
negative	こない	くる	adj-i	vk	suffix
negative	来ない	来る	adj-i	vk	suffix
negative	來ない	來る	adj-i	vk	suffix
negative	ません	ます	-ません	-ます	suffix
-さ	さ	い		adj-i	suffix
passive	かれる	く	v1	v5	suffix
passive	がれる	ぐ	v1	v5	suffix
passive	される	す	v1	v5d,v5sp	suffix
passive	たれる	つ	v1	v5	suffix
passive	なれる	ぬ	v1	v5	suffix
passive	ばれる	ぶ	v1	v5	suffix
passive	まれる	む	v1	v5	suffix
passive	われる	う	v1	v5	suffix
passive	はれる	う	v1	v5	suffix
passive	られる	る	v1	v5	suffix
passive	じされる	ずる	v1	vz	suffix
passive	ぜされる	ずる	v1	vz	suffix
passive	される	する	v1	vs	suffix
passive	為れる	為る	v1	vs	suffix
passive	こられる	くる	v1	vk	suffix
passive	来られる	来る	v1	vk	suffix
passive	來られる	來る	v1	vk	suffix
-た	かった	い	-た	adj-i	suffix
-た	た	る	-た	v1	suffix
-た	いた	く	-た	v5	suffix
-た	いだ	ぐ	-た	v5	suffix
-た	した	す	-た	v5	suffix
-た	った	う	-た	v5	suffix
-た	った	つ	-た	v5	suffix
-た	った	る	-た	v5	suffix
-た	んだ	ぬ	-た	v5	suffix
-た	んだ	ぶ	-た	v5	suffix
-た	んだ	む	-た	v5	suffix
-た	じた	ずる	-た	vz	suffix
-た	した	する	-た	vs	suffix
-た	為た	為る	-た	vs	suffix
-た	きた	くる	-た	vk	suffix
-た	来た	来る	-た	vk	suffix
-た	來た	來る	-た	vk	suffix
-た	いった	いく	-た	v5	suffix
-た	行った	行く	-た	v5	suffix
-た	逝った	逝く	-た	v5	suffix
-た	往った	往く	-た	v5	suffix
-た	こうた	こう	-た	v5	suffix
-た	とうた	とう	-た	v5	suffix
-た	請うた	請う	-た	v5	suffix
-た	乞うた	乞う	-た	v5	suffix
-た	恋うた	恋う	-た	v5	suffix
-た	問うた	問う	-た	v5	suffix
-た	訪うた	訪う	-た	v5	suffix
-た	宣うた	宣う	-た	v5	suffix
-た	曰うた	曰う	-た	v5	suffix
-た	給うた	給う	-た	v5	suffix
-た	賜うた	賜う	-た	v5	suffix
-た	揺蕩うた	揺蕩う	-た	v5	suffix
-た	のたもうた	のたまう	-た	v5	suffix
-た	たもうた	たまう	-た	v5	suffix
-た	たゆとうた	たゆたう	-た	v5	suffix
-た	ました	ます	-た	-ます	suffix
-た	でした		-た	-ません	suffix
-た	かった		-た	-ません,-ん	suffix
-ます	ます	る	-ます	v1	suffix
-ます	いらっしゃいます	いらっしゃる	-ます	v5d	whole
-ます	ございます	ござる	-ます	v5d	whole
-ます	なさいます	なさる	-ます	v5d	whole
-ます	くださいます	くださる	-ます	v5d	whole
-ます	下さいます	下さる	-ます	v5d	whole
-ます	おっしゃいます	おっしゃる	-ます	v5d	whole
-ます	仰います	仰る	-ます	v5d	whole
-ます	仰有います	仰有る	-ます	v5d	whole
-ます	います	う	-ます	v5d	suffix
-ます	ひます	う	-ます	v5d	suffix
-ます	きます	く	-ます	v5d	suffix
-ます	ぎます	ぐ	-ます	v5d	suffix
-ます	します	す	-ます	v5d,v5s	suffix
-ます	ちます	つ	-ます	v5d	suffix
-ます	にます	ぬ	-ます	v5d	suffix
-ます	びます	ぶ	-ます	v5d	suffix
-ます	みます	む	-ます	v5d	suffix
-ます	ります	る	-ます	v5d	suffix
-ます	じます	ずる	-ます	vz	suffix
-ます	します	する	-ます	vs	suffix
-ます	為ます	為る	-ます	vs	suffix
-ます	きます	くる	-ます	vk	suffix
-ます	来ます	来る	-ます	vk	suffix
-ます	來ます	來る	-ます	vk	suffix
-ます	くあります	い	-ます	adj-i	suffix
potential	れる	る	v1	v1,v5d	suffix
potential	える	う	v1	v5d	suffix
potential	ける	く	v1	v5d	suffix
potential	げる	ぐ	v1	v5d	suffix
potential	せる	す	v1	v5d	suffix
potential	てる	つ	v1	v5d	suffix
potential	ねる	ぬ	v1	v5d	suffix
potential	べる	ぶ	v1	v5d	suffix
potential	める	む	v1	v5d	suffix
potential	できる	する	v1	vs	suffix
potential	出来る	する	v1	vs	suffix
potential	これる	くる	v1	vk	suffix
potential	来れる	来る	v1	vk	suffix
potential	來れる	來る	v1	vk	suffix
potential or passive	られる	る	v1	v1	suffix
potential or passive	ざれる	ずる	v1	vz	suffix
potential or passive	ぜられる	ずる	v1	vz	suffix
potential or passive	せられる	する	v1	vs	suffix
potential or passive	為られる	為る	v1	vs	suffix
potential or passive	こられる	くる	v1	vk	suffix
potential or passive	来られる	来る	v1	vk	suffix
potential or passive	來られる	來る	v1	vk	suffix
volitional	よう	る		v1	suffix
volitional	やう	る		v1	suffix
volitional	おう	う		v5	suffix
volitional	はう	う		v5	suffix
volitional	こう	く		v5	suffix
volitional	かう	く		v5	suffix
volitional	ごう	ぐ		v5	suffix
volitional	がう	ぐ		v5	suffix
volitional	そう	す		v5	suffix
volitional	さう	す		v5	suffix
volitional	とう	つ		v5	suffix
volitional	たう	つ		v5	suffix
volitional	のう	ぬ		v5	suffix
volitional	なう	ぬ		v5	suffix
volitional	ぼう	ぶ		v5	suffix
volitional	ばう	ぶ		v5	suffix
volitional	もう	む		v5	suffix
volitional	まう	む		v5	suffix
volitional	ろう	る		v5	suffix
volitional	らう	る		v5	suffix
volitional	じよう	ずる		vz	suffix
volitional	じやう	ずる		vz	suffix
volitional	しよう	する		vs	suffix
volitional	しやう	する		vs	suffix
volitional	為よう	為る		vs	suffix
volitional	為やう	為る		vs	suffix
volitional	こよう	くる		vk	suffix
volitional	こやう	くる		vk	suffix
volitional	来よう	来る		vk	suffix
volitional	来やう	来る		vk	suffix
volitional	來よう	來る		vk	suffix
volitional	來やう	來る		vk	suffix
volitional	ましょう	ます		-ます	suffix
volitional	ませう	ます		-ます	suffix
volitional	かろう	い		adj-i	suffix
volitional	からう	い		adj-i	suffix
volitional slang	よっか	る		v1	suffix
volitional slang	おっか	う		v5	suffix
volitional slang	こっか	く		v5	suffix
volitional slang	ごっか	ぐ		v5	suffix
volitional slang	そっか	す		v5	suffix
volitional slang	とっか	つ		v5	suffix
volitional slang	のっか	ぬ		v5	suffix
volitional slang	ぼっか	ぶ		v5	suffix
volitional slang	もっか	む		v5	suffix
volitional slang	ろっか	る		v5	suffix
volitional slang	じよっか	ずる		vz	suffix
volitional slang	しよっか	する		vs	suffix
volitional slang	為よっか	為る		vs	suffix
volitional slang	こよっか	くる		vk	suffix
volitional slang	来よっか	来る		vk	suffix
volitional slang	來よっか	來る		vk	suffix
volitional slang	ましょっか	ます		-ます	suffix
-まい	まい			v	suffix
-まい	まい	る		v1	suffix
-まい	じまい	ずる		vz	suffix
-まい	しまい	する		vs	suffix
-まい	為まい	為る		vs	suffix
-まい	こまい	くる		vk	suffix
-まい	来まい	来る		vk	suffix
-まい	來まい	來る		vk	suffix
-まい	まい			-ます	suffix
-おく	ておく	て	v5	-て	suffix
-おく	でおく	で	v5	-て	suffix
-おく	とく	て	v5	-て	suffix
-おく	どく	で	v5	-て	suffix
-おく	ないでおく	ない	v5	adj-i	suffix
-おく	ないどく	ない	v5	adj-i	suffix
-いる	ている	て	v1	-て	suffix
-いる	てゐる	て	v1	-て	suffix
-いる	ておる	て	v5	-て	suffix
-いる	てる	て	v1p	-て	suffix
-いる	でいる	で	v1	-て	suffix
-いる	でゐる	で	v1	-て	suffix
-いる	でおる	で	v5	-て	suffix
-いる	でる	で	v1p	-て	suffix
-いる	とる	て	v5	-て	suffix
-いる	ないでいる	ない	v1	adj-i	suffix
-いる	ないでゐる	ない	v1	adj-i	suffix
-ふ	ふ	う		v5	suffix
-き	き	い		adj-i	suffix
-げ	げ	い		adj-i	suffix
-げ	気	い		adj-i	suffix
-がる	がる	い	v5	adj-i	suffix
-やがる	やがる	る	v5	v1	suffix
-やがる	いやがる	う	v5	v5	suffix
-やがる	きやがる	く	v5	v5	suffix
-やがる	ぎやがる	ぐ	v5	v5	suffix
-やがる	しやがる	す	v5	v5	suffix
-やがる	ちやがる	つ	v5	v5	suffix
-やがる	にやがる	ぬ	v5	v5	suffix
-やがる	びやがる	ぶ	v5	v5	suffix
-やがる	みやがる	む	v5	v5	suffix
-やがる	りやがる	る	v5	v5	suffix
-やがる	じやがる	ずる	v5	vz	suffix
-やがる	しやがる	する	v5	vs	suffix
-やがる	為やがる	為る	v5	vs	suffix
-やがる	きやがる	くる	v5	vk	suffix
-やがる	来やがる	来る	v5	vk	suffix
-やがる	來やがる	來る	v5	vk	suffix
-え	ねえ	ない		adj-i	suffix
-え	めえ	むい		adj-i	suffix
-え	みい	むい		adj-i	suffix
-え	ちぇえ	つい		adj-i	suffix
-え	ちい	つい		adj-i	suffix
-え	せえ	すい		adj-i	suffix
-え	ええ	いい		adj-i	suffix
-え	ええ	わい		adj-i	suffix
-え	ええ	よい		adj-i	suffix
-え	いぇえ	よい		adj-i	suffix
-え	うぇえ	わい		adj-i	suffix
-え	けえ	かい		adj-i	suffix
-え	げえ	がい		adj-i	suffix
-え	げえ	ごい		adj-i	suffix
-え	せえ	さい		adj-i	suffix
-え	めえ	まい		adj-i	suffix
-え	ぜえ	ずい		adj-i	suffix
-え	っぜえ	ずい		adj-i	suffix
-え	れえ	らい		adj-i	suffix
-え	れえ	らい		adj-i	suffix
-え	ちぇえ	ちゃい		adj-i	suffix
-え	でえ	どい		adj-i	suffix
-え	れえ	れい		adj-i	suffix
-え	べえ	ばい		adj-i	suffix
-え	てえ	たい		adj-i	suffix
-え	ねぇ	ない		adj-i	suffix
-え	めぇ	むい		adj-i	suffix
-え	みぃ	むい		adj-i	suffix
-え	ちぃ	つい		adj-i	suffix
-え	せぇ	すい		adj-i	suffix
-え	けぇ	かい		adj-i	suffix
-え	げぇ	がい		adj-i	suffix
-え	げぇ	ごい		adj-i	suffix
-え	せぇ	さい		adj-i	suffix
-え	めぇ	まい		adj-i	suffix
-え	ぜぇ	ずい		adj-i	suffix
-え	っぜぇ	ずい		adj-i	suffix
-え	れぇ	らい		adj-i	suffix
-え	でぇ	どい		adj-i	suffix
-え	れぇ	れい		adj-i	suffix
-え	べぇ	ばい		adj-i	suffix
-え	てぇ	たい		adj-i	suffix
n-slang	んなさい	りなさい		-なさい	suffix
n-slang	らんない	られない	adj-i	adj-i	suffix
n-slang	んない	らない	adj-i	adj-i	suffix
n-slang	んなきゃ	らなきゃ		-ゃ	suffix
n-slang	んなきゃ	れなきゃ		-ゃ	suffix
imperative negative slang	んな	る		v	suffix
kansai-ben negative	へん	ない		adj-i	suffix
kansai-ben negative	ひん	ない		adj-i	suffix
kansai-ben negative	せえへん	しない		adj-i	suffix
kansai-ben negative	へんかった	なかった	-た	-た	suffix
kansai-ben negative	ひんかった	なかった	-た	-た	suffix
kansai-ben negative	うてへん	ってない		adj-i	suffix
kansai-ben -て	うて	って	-て	-て	suffix
kansai-ben -て	おうて	あって	-て	-て	suffix
kansai-ben -て	こうて	かって	-て	-て	suffix
kansai-ben -て	ごうて	がって	-て	-て	suffix
kansai-ben -て	そうて	さって	-て	-て	suffix
kansai-ben -て	ぞうて	ざって	-て	-て	suffix
kansai-ben -て	とうて	たって	-て	-て	suffix
kansai-ben -て	どうて	だって	-て	-て	suffix
kansai-ben -て	のうて	なって	-て	-て	suffix
kansai-ben -て	ほうて	はって	-て	-て	suffix
kansai-ben -て	ぼうて	ばって	-て	-て	suffix
kansai-ben -て	もうて	まって	-て	-て	suffix
kansai-ben -て	ろうて	らって	-て	-て	suffix
kansai-ben -て	ようて	やって	-て	-て	suffix
kansai-ben -て	ゆうて	いって	-て	-て	suffix
kansai-ben -た	うた	った	-た	-た	suffix
kansai-ben -た	おうた	あった	-た	-た	suffix
kansai-ben -た	こうた	かった	-た	-た	suffix
kansai-ben -た	ごうた	がった	-た	-た	suffix
kansai-ben -た	そうた	さった	-た	-た	suffix
kansai-ben -た	ぞうた	ざった	-た	-た	suffix
kansai-ben -た	とうた	たった	-た	-た	suffix
kansai-ben -た	どうた	だった	-た	-た	suffix
kansai-ben -た	のうた	なった	-た	-た	suffix
kansai-ben -た	ほうた	はった	-た	-た	suffix
kansai-ben -た	ぼうた	ばった	-た	-た	suffix
kansai-ben -た	もうた	まった	-た	-た	suffix
kansai-ben -た	ろうた	らった	-た	-た	suffix
kansai-ben -た	ようた	やった	-た	-た	suffix
kansai-ben -た	ゆうた	いった	-た	-た	suffix
kansai-ben -たら	うたら	ったら			suffix
kansai-ben -たら	おうたら	あったら			suffix
kansai-ben -たら	こうたら	かったら			suffix
kansai-ben -たら	ごうたら	がったら			suffix
kansai-ben -たら	そうたら	さったら			suffix
kansai-ben -たら	ぞうたら	ざったら			suffix
kansai-ben -たら	とうたら	たったら			suffix
kansai-ben -たら	どうたら	だったら			suffix
kansai-ben -たら	のうたら	なったら			suffix
kansai-ben -たら	ほうたら	はったら			suffix
kansai-ben -たら	ぼうたら	ばったら			suffix
kansai-ben -たら	もうたら	まったら			suffix
kansai-ben -たら	ろうたら	らったら			suffix
kansai-ben -たら	ようたら	やったら			suffix
kansai-ben -たら	ゆうたら	いったら			suffix
kansai-ben -たり	うたり	ったり			suffix
kansai-ben -たり	おうたり	あったり			suffix
kansai-ben -たり	こうたり	かったり			suffix
kansai-ben -たり	ごうたり	がったり			suffix
kansai-ben -たり	そうたり	さったり			suffix
kansai-ben -たり	ぞうたり	ざったり			suffix
kansai-ben -たり	とうたり	たったり			suffix
kansai-ben -たり	どうたり	だったり			suffix
kansai-ben -たり	のうたり	なったり			suffix
kansai-ben -たり	ほうたり	はったり			suffix
kansai-ben -たり	ぼうたり	ばったり			suffix
kansai-ben -たり	もうたり	まったり			suffix
kansai-ben -たり	ろうたり	らったり			suffix
kansai-ben -たり	ようたり	やったり			suffix
kansai-ben -たり	ゆうたり	いったり			suffix
kansai-ben -く	う	く		-く	suffix
kansai-ben -く	こう	かく		-く	suffix
kansai-ben -く	ごう	がく		-く	suffix
kansai-ben -く	そう	さく		-く	suffix
kansai-ben -く	とう	たく		-く	suffix
kansai-ben -く	のう	なく		-く	suffix
kansai-ben -く	ぼう	ばく		-く	suffix
kansai-ben -く	もう	まく		-く	suffix
kansai-ben -く	ろう	らく		-く	suffix
kansai-ben -く	よう	よく		-く	suffix
kansai-ben -く	しゅう	しく		-く	suffix
kansai-ben adjective -て	うて	くて	-て	-て	suffix
kansai-ben adjective -て	こうて	かくて	-て	-て	suffix
kansai-ben adjective -て	ごうて	がくて	-て	-て	suffix
kansai-ben adjective -て	そうて	さくて	-て	-て	suffix
kansai-ben adjective -て	とうて	たくて	-て	-て	suffix
kansai-ben adjective -て	のうて	なくて	-て	-て	suffix
kansai-ben adjective -て	ぼうて	ばくて	-て	-て	suffix
kansai-ben adjective -て	もうて	まくて	-て	-て	suffix
kansai-ben adjective -て	ろうて	らくて	-て	-て	suffix
kansai-ben adjective -て	ようて	よくて	-て	-て	suffix
kansai-ben adjective -て	しゅうて	しくて	-て	-て	suffix
kansai-ben adjective negative	うない	くない	adj-i	adj-i	suffix
kansai-ben adjective negative	こうない	かくない	adj-i	adj-i	suffix
kansai-ben adjective negative	ごうない	がくない	adj-i	adj-i	suffix
kansai-ben adjective negative	そうない	さくない	adj-i	adj-i	suffix
kansai-ben adjective negative	とうない	たくない	adj-i	adj-i	suffix
kansai-ben adjective negative	のうない	なくない	adj-i	adj-i	suffix
kansai-ben adjective negative	ぼうない	ばくない	adj-i	adj-i	suffix
kansai-ben adjective negative	もうない	まくない	adj-i	adj-i	suffix
kansai-ben adjective negative	ろうない	らくない	adj-i	adj-i	suffix
kansai-ben adjective negative	ようない	よくない	adj-i	adj-i	suffix
kansai-ben adjective negative	しゅうない	しくない	adj-i	adj-i	suffix
"""
    }
}
