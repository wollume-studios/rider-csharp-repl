package com.wollumestudios.csharprepl.ui

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.lookup.LookupFocusDegree
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.lookup.impl.LookupImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.ui.EditorTextField
import com.intellij.util.Alarm
import com.wollumestudios.csharprepl.CompletionSnapshot
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.autoTriggers
import com.wollumestudios.csharprepl.host.CompletionItem
import com.wollumestudios.csharprepl.lookupPrefix

/**
 * Completion in the REPL input: the host's `complete` answer shown in IntelliJ's lookup (no PSI). Typing an identifier
 * character or a member-access dot asks after [DEBOUNCE_MS]; Ctrl+Space ([CSharpRepl.Complete]) asks now. An answer for
 * an input or caret that changed meanwhile is dropped. Choosing an item replaces the prefix the host reported.
 */
class InputCompletion(private val project: Project, private val input: EditorTextField, parent: Disposable) {
    private val session = ReplSession.getInstance(project)
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, parent)

    init {
        input.document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                alarm.cancelAllRequests()
                if (event.oldLength != 0 || event.newLength != 1) return // typing inserts one character; history, paste and clear don't pop up
                val end = event.offset + 1
                if (autoTriggers(event.document.immutableCharSequence.subSequence(0, end))) alarm.addRequest({ request(explicit = false) }, DEBOUNCE_MS)
            }
        }, parent)
    }

    /** True while a lookup is showing in the input: Enter, Tab, Escape and Up/Down belong to it then. */
    val lookupShowing: Boolean get() = input.editor?.let { LookupManager.getActiveLookup(it) } != null

    /** Ctrl+Space: asks now and shows the lookup focused. */
    fun completeNow() {
        alarm.cancelAllRequests()
        request(explicit = true)
    }

    private fun snapshot(editor: Editor) = CompletionSnapshot(editor.document.text, editor.caretModel.offset)

    private fun request(explicit: Boolean) {
        val editor = input.editor ?: return
        val atRequest = snapshot(editor)
        session.complete(atRequest.text, atRequest.caret).thenAccept { r -> // on the EDT
            if (r == null || input.editor !== editor || editor.isDisposed || snapshot(editor) != atRequest) return@thenAccept
            val prefix = lookupPrefix(atRequest.text, atRequest.caret, r.start, r.length) ?: return@thenAccept
            show(editor, prefix, r.items.orEmpty(), explicit)
        }
    }

    private fun show(editor: Editor, prefix: String, items: List<CompletionItem>, explicit: Boolean) {
        LookupManager.getActiveLookup(editor)?.hideLookup(true) // a newer answer replaces the list
        if (items.isEmpty()) return
        val elements = items.map { LookupElementBuilder.create(it.label).withIcon(icon(it.kind)).withTypeText(it.detail, true).withBoldness(it.kind == "keyword") }
        val lookup = LookupManager.getInstance(project).createLookup(editor, elements.toTypedArray(), prefix, com.intellij.codeInsight.lookup.LookupArranger.DefaultArranger()) as LookupImpl
        // Semi-focused while typing (Enter/Tab accept, other characters just keep typing), focused after Ctrl+Space.
        lookup.lookupFocusDegree = if (explicit) LookupFocusDegree.FOCUSED else LookupFocusDegree.SEMI_FOCUSED
        if (!lookup.showLookup()) lookup.hideLookup(true)
    }

    private fun icon(kind: String) = when (kind) {
        "variable" -> AllIcons.Nodes.Variable
        "method" -> AllIcons.Nodes.Method
        "property" -> AllIcons.Nodes.Property
        "field" -> AllIcons.Nodes.Field
        "event" -> AllIcons.Nodes.Lambda
        "parameter" -> AllIcons.Nodes.Parameter
        "namespace" -> AllIcons.Nodes.Package
        "interface" -> AllIcons.Nodes.Interface
        "enum" -> AllIcons.Nodes.Enum
        "struct" -> AllIcons.Nodes.Static
        "delegate" -> AllIcons.Nodes.Lambda
        "class" -> AllIcons.Nodes.Class
        else -> null
    }

    companion object {
        const val DEBOUNCE_MS = 150
    }
}
