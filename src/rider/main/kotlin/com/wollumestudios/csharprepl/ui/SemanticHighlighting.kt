package com.wollumestudios.csharprepl.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.MarkupModel
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.Alarm
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.SEMANTIC_KEYS
import com.wollumestudios.csharprepl.host.SemanticSpan

/**
 * Highlighters for the host's semantic [spans] (offsets from [base]), one layer above the lexer's colors. They carry
 * Rider's own C# keys ([SEMANTIC_KEYS]), so the user's color scheme applies and a scheme change recolors them.
 */
fun addSemanticHighlighters(markup: MarkupModel, base: Int, spans: List<SemanticSpan>): List<RangeHighlighter> =
    spans.mapNotNull { s ->
        // Looked up when used, not cached: Rider registers the keys (with their fallbacks) when its backend starts.
        val key = SEMANTIC_KEYS[s.kind]?.let(TextAttributesKey::find) ?: return@mapNotNull null
        markup.addRangeHighlighter(key, base + s.start, base + s.start + s.length, HighlighterLayer.SYNTAX + 1, HighlighterTargetArea.EXACT_RANGE)
    }

/**
 * Semantic colors in the REPL input: [DEBOUNCE_MS] after an edit (or a [schedule] when the session changed) the host
 * classifies the input as the next submission; an answer for text that changed meanwhile is dropped.
 */
class InputSemanticHighlighting(project: Project, private val input: EditorTextField, parent: Disposable) {
    private val session = ReplSession.getInstance(project)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)
    private var shown = emptyList<RangeHighlighter>()

    init {
        input.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = schedule()
        }, parent)
        input.addSettingsProvider { schedule() } // EditorTextField creates a new editor (empty markup) when it is shown again
    }

    fun schedule() {
        alarm.cancelAllRequests()
        alarm.addRequest(::request, DEBOUNCE_MS)
    }

    private fun request() {
        val editor = input.editor ?: return
        val text = editor.document.text
        if (text.isBlank()) return replace(emptyList())
        session.classify(text).thenAccept { spans -> // on the EDT
            if (spans == null || input.editor !== editor || editor.isDisposed || editor.document.text != text) return@thenAccept
            replace(addSemanticHighlighters(editor.markupModel, 0, spans))
        }
    }

    private fun replace(highlighters: List<RangeHighlighter>) {
        shown.filter { it.isValid }.forEach(RangeHighlighter::dispose) // from its own editor: EditorTextField may have replaced it
        shown = highlighters
    }

    companion object {
        const val DEBOUNCE_MS = 150
    }
}
