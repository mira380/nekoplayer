package com.nekoplayer.app.ui

import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

val LocalNekoLanguage = staticCompositionLocalOf { "English" }

private val japaneseLabels = mapOf(
    "Library" to "ライブラリ",
    "Dictionaries" to "辞書",
    "Settings" to "設定",
    "Appearance" to "外観",
    "Dark theme" to "ダークテーマ",
    "Playback" to "再生",
    "Pause video when opening dictionary lookup" to "辞書検索を開くときに動画を一時停止",
    "Subtitles" to "字幕",
    "Font size" to "文字サイズ",
    "Bottom inset" to "下端からの位置",
    "Background opacity" to "背景の不透明度",
    "Text shadow" to "文字に影を付ける",
    "Video vignette" to "映像のビネット",
    "Off" to "なし",
    "Soft" to "ソフト",
    "Strong" to "強め",
    "Hard" to "くっきり",
    "Import dictionary" to "辞書を読み込む",
    "Import dictionaries" to "辞書を読み込む",
    "Importing…" to "読み込み中…",
    "Select a Yomitan dictionary ZIP archive" to "Yomitan形式の辞書ZIPアーカイブを選択してください",
    "No dictionaries yet." to "辞書はありません。",
    "Move up" to "上へ移動",
    "Move down" to "下へ移動",
    "Remove dictionary" to "辞書を削除",
    "AnkiDroid" to "AnkiDroid",
    "Anki" to "Anki",
    "More settings" to "その他の設定",
    "Create Anki cards. Existing notes stay unchanged." to "Ankiカードを作成します。既存のノートは変更しません。",
    "Load fields" to "フィールドを読み込む",
    "Refresh fields" to "フィールドを更新",
    "Loading…" to "読み込み中…",
    "No AnkiDroid note types found." to "AnkiDroidのノートタイプがありません。",
    "AnkiDroid access denied." to "AnkiDroidへのアクセスが拒否されました。",
    "Could not request AnkiDroid access." to "AnkiDroidへのアクセスを要求できませんでした。",
    "Could not load AnkiDroid note types." to "AnkiDroidのノートタイプを読み込めませんでした。",
    "Could not load AnkiDroid fields." to "AnkiDroidのフィールドを読み込めませんでした。",
    "Refresh AnkiDroid status" to "AnkiDroidの状態を更新",
    "Choose note type" to "ノートタイプを選択",
    "Not mapped" to "割り当てなし",
    "Waiting…" to "待機中…",
    "AnkiDroid not installed." to "AnkiDroidがインストールされていません。",
    "Enable the AnkiDroid API." to "AnkiDroid APIを有効にしてください。",
    "Allow AnkiDroid access." to "AnkiDroidへのアクセスを許可してください。",
    "Default deck" to "既定のデッキ",
    "Note type" to "ノートタイプ",
    "Fields" to "フィールド",
    "Jimaku API key" to "Jimaku APIキー",
    "API key" to "APIキー",
    "Fields refreshed" to "フィールドを更新しました",
    "Capture & Mining" to "キャプチャとカード作成",
    "Audio padding before subtitle" to "字幕前の音声余白",
    "Audio padding after subtitle" to "字幕後の音声余白",
    "Picture delay" to "画像の遅延",
    "Maximum picture width" to "画像の最大幅",
    "Maximum picture height" to "画像の最大高さ",
    "Original size" to "元のサイズ",
    "Crop black bars" to "黒帯を切り取る",
    "Play audio during capture" to "キャプチャ中に音声を再生",
    "Picture format" to "画像形式",
    "Pronunciation Audio" to "発音音声",
    "Add URL" to "URLを追加",
    "Add JSON" to "JSONを追加",
    "URL template" to "URLテンプレート",
    "Tokens: {term}, {reading}, {language}." to "変数：{term}、{reading}、{language}。",
    "Remove dictionary?" to "辞書を削除しますか？",
    "Remove" to "削除",
    "Cancel" to "キャンセル",
    "Continue watching" to "続きを再生",
    "Artwork URL" to "アートワークURL",
    "HTTPS image URL" to "HTTPS画像URL",
    "Save" to "保存",
    "Choose folder" to "フォルダーを選択",
    "Open video" to "動画を開く",
    "Recently opened" to "最近開いた動画",
    "Set image URL" to "画像URLを設定",
    "Choose local image" to "端末の画像を選択",
    "Use AniList artwork" to "AniListの画像を使う",
    "Search Japanese" to "日本語を検索",
    "No matching entries." to "一致する項目がありません。",
    "All" to "すべて",
    "Loading video…" to "動画を読み込み中…",
    "Open subtitles" to "字幕を開く",
    "Audio" to "音声",
    "Clear" to "解除",
    "Replay subtitle" to "字幕をもう一度再生",
    "Previous subtitle" to "前の字幕",
    "Next subtitle" to "次の字幕",
    "Subtitle on" to "字幕オン",
    "Subtitle off" to "字幕オフ",
    "Playback speed" to "再生速度",
    "Done" to "完了",
    "Subtitles off" to "字幕なし",
    "Subtitle tracks" to "字幕トラック",
    "Turn subtitles off" to "字幕をオフ",
    "Turn subtitles on" to "字幕をオン",
    "Reset subtitle timing" to "字幕のタイミングをリセット",
    "Related shorter matches" to "関連する短い一致",
    "Frequency" to "出現頻度",
    "Pitch" to "アクセント",
    "Pitch accent" to "ピッチアクセント",
    "Subtitle lookup" to "字幕検索",
    "Looking up dictionaries…" to "辞書を検索中…",
    "No match." to "一致する項目がありません。",
    "Expression" to "表記",
    "Reading" to "読み",
    "Definition" to "定義",
    "Glossary" to "語義",
    "Sentence" to "例文",
    "Sentence Audio" to "例文の音声",
    "Picture" to "画像",
    "Search" to "検索",
    "Choose a subtitle file" to "字幕ファイルを選択",
    "Jimaku" to "Jimaku",
    "Load subtitles" to "字幕を読み込む",
    "From Jimaku" to "Jimakuから取得",
    "Add your Jimaku API key in Settings → Subtitles to fetch subtitles automatically." to "字幕を自動取得するには、設定 → 字幕からJimaku APIキーを追加してください。",
    "Fetch for this anime" to "このアニメの字幕を取得",
    "Search another title" to "別の作品名で検索",
    "Play pronunciation" to "発音を再生",
    "Mine to Anki" to "Ankiに登録",
    "Pick the matching anime title." to "該当するアニメ作品を選択してください。",
    "Pick a subtitle file." to "字幕ファイルを選択してください。",
    "Back" to "戻る",
    "Close" to "閉じる",
)

private val dynamicJapaneseLabels = listOf(
    Regex("^Remove (.+) and its imported entries from this device\\? The source file will remain\\.$") to "${'$'}1と読み込んだ項目をこの端末から削除します。元のファイルは残ります。",
    Regex("^Custom URL (\\d+)$") to "カスタムURL ${'$'}1",
    Regex("^Custom JSON (\\d+)$") to "カスタムJSON ${'$'}1",
    Regex("^Resume at (.+)$") to "続きから再生：$1",
    Regex("^(\\d+) episodes$") to "$1話",
    Regex("^Episode (\\d+): pick a subtitle file\\.$") to "${'$'}1話の字幕ファイルを選択してください。",
    Regex("^Sub (.+)$") to "字幕 ${'$'}1",
    Regex("^Earlier by (\\d+) ms$") to "字幕を${'$'}1 ms早く表示",
    Regex("^Later by (\\d+) ms$") to "字幕を${'$'}1 ms遅く表示",
    Regex("^Remove (.+)$") to "${'$'}1を削除",
    Regex("^(.+) \\(external\\)$") to "${'$'}1（外部）",
    Regex("^Importing (\\d+) of (\\d+)$") to "辞書を読み込み中：${'$'}1/${'$'}2",
    Regex("^No \\w+ tracks found\\.$") to "トラックが見つかりません。",
)

@Composable
fun provideNekoLanguage(language: String, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNekoLanguage provides language, content = content)
}

private fun localize(text: String, language: String): String {
    if (language != "日本語") return text
    japaneseLabels[text]?.let { return it }
    for ((pattern, replacement) in dynamicJapaneseLabels) {
        if (pattern.matches(text)) return pattern.replace(text, replacement)
    }
    return text
}

@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    MaterialText(
        text = localize(text, LocalNekoLanguage.current),
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontStyle = fontStyle,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        letterSpacing = letterSpacing,
        textDecoration = textDecoration,
        textAlign = textAlign,
        lineHeight = lineHeight,
        overflow = overflow,
        softWrap = softWrap,
        maxLines = maxLines,
        minLines = minLines,
        onTextLayout = onTextLayout,
        style = style,
    )
}
