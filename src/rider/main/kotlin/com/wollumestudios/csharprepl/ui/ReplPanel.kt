package com.wollumestudios.csharprepl.ui

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
import com.intellij.openapi.editor.impl.EditorImpl
import com.wollumestudios.csharprepl.refs.ReferenceDrop
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.impl.ActionButton
import com.intellij.testFramework.LightVirtualFile
import java.util.concurrent.CompletableFuture
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.EditorTextField
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

    private val csharp = FileTypeManager.getInstance().getFileTypeByExtension("cs")
    private val syntax = csharpHighlighter(project)
    private val drop = ReferenceDrop(project)
    private val banner = ChangeBanner()

    private val history = (EditorFactory.getInstance().createViewer(EditorFactory.getInstance().createDocument(""), project) as EditorEx).apply {
        UndoUtil.disableUndoFor(document)
        (this as? EditorImpl)?.setDropHandler(drop)
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
    private var renderedResets = 0
    private var shownVariables: List<Var>? = null

    val input: EditorTextField = object : EditorTextField(EditorFactory.getInstance().createDocument(""), project, csharp, false, false) {
        override fun createEditor(): EditorEx = super.createEditor().apply {
            highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(syntax, colorsScheme)
            (this as? EditorImpl)?.setDropHandler(drop)
            settings.isLineNumbersShown = false
            settings.additionalLinesCount = 0
            setVerticalScrollbarVisible(true)
        }
    }.apply { setFontInheritedFromLAF(false) }
    private val inputHistory = InputHistory { session.history.filter { it.status != "note" }.map { it.code } }

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
        val clear = ActionManager.getInstance().getAction("CSharpRepl.ClearPending")
        add(ActionButton(clear, clear.templatePresentation.clone(), "CSharpReplPending", ActionToolbar.NAVBAR_MINIMUM_BUTTON_SIZE), BorderLayout.EAST)
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
        setContent(JPanel(BorderLayout()).apply {
            add(banner, BorderLayout.NORTH) // the banner slot
            add(splitter, BorderLayout.CENTER)
        })
        transferHandler = drop

        val group = ActionManager.getInstance().getAction("CSharpRepl.Toolbar") as ActionGroup
        toolbar = ActionManager.getInstance().createActionToolbar("CSharpReplToolbar", group, true).also { it.targetComponent = this }.component

        // Registered on the input component, so they win over the editor's own Enter/Escape (keymap shortcuts if the user set any, else the defaults).
        bindOnInput("CSharpRepl.Enter", "ENTER")
        bindOnInput("CSharpRepl.ShiftEnter", "shift ENTER")
        bindOnInput("CSharpRepl.ClearInput", "ESCAPE")
        onInput("UP", { !session.analyzing && caretLine() == 0 }) { inputHistory.up(input.text)?.let(::setInput) }
        onInput("DOWN", { !session.analyzing && caretLine() == lastLine() }) { inputHistory.down()?.let(::setInput) }

        Disposer.register(parent) { EditorFactory.getInstance().releaseEditor(history) }
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(ReplSettings.TOPIC, ReplSettingsListener { applySettings() })
        session.addListener(parent, ::sync)
        applySettings()
        sync()
        session.start()
    }

    /** The toolbar's Run: pending + input now, as is (Shift+Enter in manual mode). */
    fun runNow() {
        if (session.analyzing) return
        val code = combine(session.pending, input.text)
        clearInput()
        session.submit(code)
    }

    /** Enter / Shift+Enter in the input ([ReplSession.enter] decides). */
    fun enter(shift: Boolean) {
        val text = input.text
        if (session.analyzing || (!shift && text.isBlank())) return
        val editor = input.editor as? EditorEx
        editor?.isViewer = true // the host answers `analyze` in milliseconds; keystrokes in between must not edit text that is about to run
        var future: CompletableFuture<EnterAction?>? = null
        try {
            future = session.enter(shift, text)
        } finally {
            if (future == null) editor?.isViewer = false
        }
        future.whenComplete { action, _ -> // completes on the EDT
            editor?.isViewer = false
            when (action) {
                is EnterAction.Run, is EnterAction.ToPending -> clearInput()
                EnterAction.NewLine -> editor?.let { WriteCommandAction.runWriteCommandAction(project) { EditorModificationUtil.insertStringAtCaret(it, "\n") } }
                null -> {} // stale or failed: nothing ran, the input stays as typed
            }
        }
    }

    fun clearInput() {
        input.text = ""
        inputHistory.reset()
    }

    private fun setInput(text: String) {
        input.text = text
        input.editor?.caretModel?.moveToOffset(text.length)
    }

    private fun caretLine() = input.editor?.caretModel?.logicalPosition?.line ?: 0

    private fun lastLine() = maxOf(0, input.document.lineCount - 1)

    private fun bindOnInput(actionId: String, defaultKeys: String) {
        val action = ActionManager.getInstance().getAction(actionId)
        val keymap = KeymapUtil.getActiveKeymapShortcuts(actionId)
        action.registerCustomShortcutSet(if (keymap.shortcuts.isNotEmpty()) keymap else CustomShortcutSet.fromString(defaultKeys), input)
    }

    private fun onInput(keys: String, enabled: () -> Boolean = { true }, run: () -> Unit) =
        object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) { e.presentation.isEnabled = enabled() }
            override fun actionPerformed(e: AnActionEvent) = run()
        }.registerCustomShortcutSet(CustomShortcutSet.fromString(keys), input)

    private fun applySettings() {
        splitter.orientation = ReplSettings.state.variablesPosition == VariablesPosition.BELOW
        updateHint()
    }

    private fun updateHint() {
        hint.text = when {
            session.analyzing -> "Analyzing…"
            ReplSettings.state.autoRun -> "Enter: run (adds missing ;) · Shift+Enter: new line"
            else -> "Enter: new line until the command is complete · Shift+Enter: run now"
        }
    }

    /** Brings the view up to date with the session: appends new history entries (all of it after a reset). */
    private fun sync() {
        val entries = session.history
        if (session.resets != renderedResets) {
            renderedResets = session.resets
            history.markupModel.removeAllHighlighters()
            runWriteAction { history.document.setText("") }
            rendered = 0
            inputHistory.reset()
        }
        if (entries.size > rendered) {
            runWriteAction { entries.subList(rendered, entries.size).forEach(::render) }
            rendered = entries.size
            history.caretModel.moveToOffset(history.document.textLength)
            history.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        }
        pendingText.text = session.pending
        pendingBox.isVisible = session.pending.isNotEmpty()
        banner.show(session.changedProject)
        if (session.variables !== shownVariables) {
            shownVariables = session.variables
            variables.show(session.variables)
        }
        updateHint()
        ActivityTracker.getInstance().inc() // Run ↔ Stop, enablement while analyzing
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
        /**
         * C# lexer highlighting from Rider's frontend file type (com.jetbrains.rider.languages.fileTypes.csharp.CSharpFileType),
         * looked up by extension so there is no compile dependency on the Rider module. Fallback: the TextMate C# bundle when
         * the TextMate plugin is installed (looked up by name, so that dependency stays optional too), else plain text.
         */
        fun csharpHighlighter(project: Project): SyntaxHighlighter {
            val types = FileTypeManager.getInstance()
            SyntaxHighlighterFactory.getSyntaxHighlighter(types.getFileTypeByExtension("cs"), project, null)
                ?.takeUnless { it is PlainSyntaxHighlighter }?.let { return it }
            types.findFileTypeByName("textmate")?.let { tm -> SyntaxHighlighterFactory.getSyntaxHighlighter(tm, project, LightVirtualFile("repl.cs")) }
                ?.takeUnless { it is PlainSyntaxHighlighter }?.let { return it }
            return PlainSyntaxHighlighter()
        }

        fun of(project: Project): ReplPanel? =
            ToolWindowManager.getInstance(project).getToolWindow(ReplToolWindowFactory.ID)?.contentManager?.getContent(0)?.component as? ReplPanel
    }
}
