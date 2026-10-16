package com.wollumestudios.csharprepl.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.ActivityTracker
import com.intellij.execution.impl.EditorHyperlinkSupport
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoUtil
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorModificationUtil
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
import com.intellij.ui.InplaceButton
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.wollumestudios.csharprepl.HistoryEntry
import com.wollumestudios.csharprepl.EnterAction
import com.wollumestudios.csharprepl.InputHistory
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.combine
import com.wollumestudios.csharprepl.host.Var
import com.wollumestudios.csharprepl.settings.ReplSettings
import com.wollumestudios.csharprepl.settings.ReplSettingsListener
import com.wollumestudios.csharprepl.settings.VariablesPosition
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Layout A: toolbar; history (read-only, C#-highlighted) over the pending box, the input and the hint line; the
 * variables table on the right or below (setting, applied live).
 */
class ReplPanel(private val project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val session = ReplSession.getInstance(project)

    // C# lexer highlighting from Rider's frontend file type (com.jetbrains.rider.languages.fileTypes.csharp.CSharpFileType),
    // looked up by extension so there is no compile dependency on the Rider module.
    private val csharp = FileTypeManager.getInstance().getFileTypeByExtension("cs")
    private val syntax = SyntaxHighlighterFactory.getSyntaxHighlighter(csharp, project, null) ?: PlainSyntaxHighlighter()

    private val history = (EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(""), project) as EditorEx).apply {
        UndoUtil.disableUndoFor(document)
        settings.apply {
            isLineNumbersShown = false
            isFoldingOutlineShown = false
            isLineMarkerAreaShown = false
            isIndentGuidesShown = false
            isCaretRowShown = false
            isRightMarginShown = false
            isUseSoftWraps = true
            additionalLinesCount = 0
            additionalColumnsCount = 0
        }
    }
    private var rendered = 0
    private var shownVariables: List<Var>? = null

    val input: EditorTextField = object : EditorTextField(EditorFactory.getInstance().createDocument(""), project, csharp, false, false) {
        override fun createEditor(): EditorEx = super.createEditor().apply {
            highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(syntax, colorsScheme)
            settings.isLineNumbersShown = false
            settings.additionalLinesCount = 0
            setVerticalScrollbarVisible(true)
        }
    }.apply { setFontInheritedFromLAF(false) }
    private val inputHistory = InputHistory { session.history.filter { it.status != "note" }.map { it.code } }
    private var analyzing = false

    private val pendingText = JBTextArea().apply {
        isEditable = false
        isOpaque = false
        font = history.colorsScheme.getFont(EditorFontType.PLAIN)
    }
    private val pendingBox = JPanel(BorderLayout()).apply {
        background = JBColor(0xFFF4D6, 0x2E2A20)
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor(0xE0A43A, 0xE0A43A), 0, 3, 0, 0), JBUI.Borders.empty(4, 8))
        add(JBLabel("Will run on next complete command:").apply { foreground = JBColor(0x8A6D1F, 0xD6B26E) }, BorderLayout.NORTH)
        add(pendingText, BorderLayout.CENTER)
        add(InplaceButton(IconButton("Clear pending code", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { session.clearPending() }, BorderLayout.EAST)
        isVisible = false
    }
    private val hint = JBLabel().apply {
        componentStyle = UIUtil.ComponentStyle.SMALL
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 8, 4, 8)
    }

    val variables = VariablesView(session)
    private val splitter = OnePixelSplitter(false, "CSharpRepl.Splitter", 0.65f)

    init {
        val bottom = JPanel(BorderLayout()).apply {
            add(pendingBox, BorderLayout.NORTH)
            add(input, BorderLayout.CENTER)
            add(hint, BorderLayout.SOUTH)
        }
        splitter.firstComponent = JPanel(BorderLayout()).apply {
            add(history.component, BorderLayout.CENTER)
            add(bottom, BorderLayout.SOUTH)
        }
        splitter.secondComponent = variables
        setContent(splitter)

        val group = ActionManager.getInstance().getAction("CSharpRepl.Toolbar") as ActionGroup
        toolbar = ActionManager.getInstance().createActionToolbar("CSharpReplToolbar", group, true).also { it.targetComponent = this }.component

        onInput("ENTER") { enter(false) }
        onInput("shift ENTER") { enter(true) }
        onInput("ESCAPE", { input.text.isNotEmpty() }) { clearInput() }
        onInput("UP", { caretLine() == 0 }) { inputHistory.up(input.text)?.let(::setInput) }
        onInput("DOWN", { caretLine() == lastLine() }) { inputHistory.down()?.let(::setInput) }

        Disposer.register(parent) { EditorFactory.getInstance().releaseEditor(history) }
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(ReplSettings.TOPIC, ReplSettingsListener { applySettings() })
        session.addListener(parent, ::sync)
        applySettings()
        sync()
        session.start()
    }

    /** The toolbar's Run: pending + input now, as is (Shift+Enter in manual mode). */
    fun runNow() {
        val code = combine(session.pending, input.text)
        clearInput()
        session.submit(code)
    }

    private fun enter(shift: Boolean) {
        val text = input.text
        if (analyzing || (!shift && text.isBlank())) return
        analyzing = true
        val editor = input.editor as? EditorEx
        editor?.isViewer = true // the host answers `analyze` in milliseconds; keystrokes in between must not edit text that is about to run
        session.enter(shift, text).whenComplete { action, _ -> // completes on the EDT
            analyzing = false
            editor?.isViewer = false
            when (action) {
                is EnterAction.Run, is EnterAction.ToPending -> clearInput()
                EnterAction.NewLine -> editor?.let { WriteCommandAction.runWriteCommandAction(project) { EditorModificationUtil.insertStringAtCaret(it, "\n") } }
                null -> {}
            }
        }
    }

    private fun clearInput() {
        input.text = ""
        inputHistory.reset()
    }

    private fun setInput(text: String) {
        input.text = text
        input.editor?.caretModel?.moveToOffset(text.length)
    }

    private fun caretLine() = input.editor?.caretModel?.logicalPosition?.line ?: 0

    private fun lastLine() = maxOf(0, input.document.lineCount - 1)

    private fun onInput(keys: String, enabled: () -> Boolean = { true }, run: () -> Unit) =
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun actionPerformed(e: AnActionEvent) = run()
        }.registerCustomShortcutSet(CustomShortcutSet.fromString(keys), input)

    private fun applySettings() {
        val s = ReplSettings.state
        splitter.orientation = s.variablesPosition == VariablesPosition.BELOW
        hint.text = if (s.autoRun) "Enter: run (adds missing ;) · Shift+Enter: new line"
        else "Enter: new line until the command is complete · Shift+Enter: run now"
    }

    /** Brings the view up to date with the session: appends new history entries (all of it after a reset). */
    private fun sync() {
        val entries = session.history
        if (entries.size < rendered) {
            history.markupModel.removeAllHighlighters()
            runWriteAction { history.document.setText("") }
            rendered = 0
        }
        if (entries.size > rendered) {
            runWriteAction { entries.subList(rendered, entries.size).forEach(::render) }
            rendered = entries.size
            history.caretModel.moveToOffset(history.document.textLength)
            history.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        pendingText.text = session.pending
        pendingBox.isVisible = session.pending.isNotEmpty()
        if (session.variables !== shownVariables) {
            shownVariables = session.variables
            variables.show(session.variables)
        }
        ActivityTracker.getInstance().inc() // Run ↔ Stop
    }

    private fun render(e: HistoryEntry) {
        if (e.status == "note") return append(e.output + "\n", ConsoleViewContentType.SYSTEM_OUTPUT_KEY)
        append("> ", ConsoleViewContentType.SYSTEM_OUTPUT_KEY)
        appendCode(e.code.replace("\n", "\n  ") + "\n")
        if (e.output.isNotEmpty()) append(indent(e.output.trimEnd('\n', '\r')) + "\n", null)
        e.result?.let { appendCode(indent(it) + "\n") }
        e.diagnostics.forEach { append(indent(it) + "\n", ConsoleViewContentType.ERROR_OUTPUT_KEY) }
        e.exception?.let {
            append(indent(it), ConsoleViewContentType.ERROR_OUTPUT_KEY)
            if (e.status == "threw") {
                val start = history.document.textLength + 3
                append("   [inspect ›]", null)
                EditorHyperlinkSupport.get(history).createHyperlink(start, history.document.textLength, null, HyperlinkInfo { variables.select("\$exception") })
            }
            append("\n", null)
        }
    }

    private fun indent(text: String) = "  " + text.replace("\n", "\n  ")

    private fun append(text: String, key: TextAttributesKey?) {
        val start = history.document.textLength
        history.document.insertString(start, text)
        if (key != null) history.markupModel.addRangeHighlighter(key, start, start + text.length, HighlighterLayer.SYNTAX, HighlighterTargetArea.EXACT_RANGE)
    }

    /**
     * Lexes each piece of code on its own (not the whole document), so an unterminated comment or string in one entry
     * can't recolor the rest of the history.
     */
    private fun appendCode(code: String) {
        val start = history.document.textLength
        history.document.insertString(start, code)
        val lexer = syntax.highlightingLexer
        lexer.start(code)
        while (true) {
            val token = lexer.tokenType ?: break
            for (key in syntax.getTokenHighlights(token))
                history.markupModel.addRangeHighlighter(key, start + lexer.tokenStart, start + lexer.tokenEnd, HighlighterLayer.SYNTAX, HighlighterTargetArea.EXACT_RANGE)
            lexer.advance()
        }
    }

    companion object {
        fun of(project: Project): ReplPanel? =
            ToolWindowManager.getInstance(project).getToolWindow(ReplToolWindowFactory.ID)?.contentManager?.getContent(0)?.component as? ReplPanel
    }
}
