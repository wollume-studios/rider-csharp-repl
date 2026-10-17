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
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.CommandProcessor
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
import javax.swing.JComponent
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
    /** The entries as rendered (compared by identity with the session's) and where each starts in the document. */
    private val shown = mutableListOf<HistoryEntry>()
    private val starts = mutableListOf<Int>()
    /** Where [render] inserts next. */
    private var cursor = 0
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

        // At least three lines high, so the input is an obvious place to type and not a one-line strip above the hint.
        override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().apply {
            height = maxOf(height, (editor?.lineHeight ?: JBUI.scale(18)) * 3 + insets.top + insets.bottom)
        }
    }.apply {
        setFontInheritedFromLAF(false)
        setPlaceholder("Type C# here")
        setShowPlaceholderWhenFocused(true)
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.empty(2, 4))
    }
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
        // Drops anywhere in the window reference projects/DLLs; the editors take them through their drop handler instead.
        drop.installOn(this, pendingBox, pendingText, variables, variables.table, variables.table.parent as JComponent)

        val group = ActionManager.getInstance().getAction("CSharpRepl.Toolbar") as ActionGroup
        toolbar = ActionManager.getInstance().createActionToolbar("CSharpReplToolbar", group, true).also { it.targetComponent = this }.component

        // Registered on the input component, so they win over the editor's own Enter/Escape (keymap shortcuts if the user set any, else the defaults).
        bindOnInput("CSharpRepl.Enter", "ENTER")
        bindOnInput("CSharpRepl.ShiftEnter", "shift ENTER")
        bindOnInput("CSharpRepl.ClearInput", "ESCAPE")
        onInput("UP", { !session.analyzing && caretLine() == 0 }) { inputHistory.up(input.text)?.let(::setInput) }
        onInput("DOWN", { !session.analyzing && caretLine() == lastLine() }) { inputHistory.down()?.let(::setInput) }

        forwardHistoryFocusToInput()
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

    /**
     * Like a console: a click into the read-only history (without selecting text) or typing there moves focus to the
     * input. Typed characters follow, because AWT redelivers key events queued behind a focus request to the new owner.
     */
    private fun forwardHistoryFocusToInput() {
        val target = { input.editor?.contentComponent }
        history.contentComponent.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseReleased(e: java.awt.event.MouseEvent) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(e) && !history.selectionModel.hasSelection()) target()?.requestFocus()
            }
        })
        history.contentComponent.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                val printable = e.keyChar != java.awt.event.KeyEvent.CHAR_UNDEFINED && !Character.isISOControl(e.keyChar)
                if (printable && !e.isControlDown && !e.isAltDown && !e.isMetaDown) {
                    input.editor?.caretModel?.moveToOffset(input.document.textLength)
                    target()?.requestFocus()
                }
            }
        })
    }

    private fun caretLine() = input.editor?.caretModel?.logicalPosition?.line ?: 0

    private fun lastLine() = maxOf(0, input.document.lineCount - 1)

    private fun bindOnInput(actionId: String, defaultKeys: String) {
        val action = ActionUtil.wrap(actionId) // a wrapper: shortcuts of the registered (global) action must not be changed here
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
            writeHistory { history.document.setText("") }
            shown.clear()
            starts.clear()
            inputHistory.reset()
        }
        val changedEntries = shown.indices.filter { entries[it] !== shown[it] } // a "running" entry got its answer
        if (changedEntries.isNotEmpty() || entries.size > shown.size) {
            writeHistory {
                changedEntries.forEach { rerender(it, entries[it]) }
                for (i in shown.size until entries.size) {
                    starts += history.document.textLength
                    cursor = history.document.textLength
                    render(entries[i])
                    shown += entries[i]
                }
            }
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

    /** Replaces entry [i]'s text (and its highlighters and links) with [e]'s, shifting the later entries' starts. */
    private fun rerender(i: Int, e: HistoryEntry) {
        val start = starts[i]
        val end = starts.getOrNull(i + 1) ?: history.document.textLength
        history.markupModel.allHighlighters.filter { it.startOffset >= start && it.endOffset <= end }.forEach(history.markupModel::removeHighlighter)
        history.document.deleteString(start, end)
        cursor = start
        render(e)
        val delta = (cursor - start) - (end - start)
        for (j in i + 1 until starts.size) starts[j] += delta
        shown[i] = e
    }

    /** Inserts [e] at [cursor] (the end, or an entry's place when it is re-rendered). */
    private fun render(e: HistoryEntry) {
        if (e.status == "note") return append(e.output + "\n", ConsoleViewContentType.SYSTEM_OUTPUT_KEY)
        append("> ", ConsoleViewContentType.SYSTEM_OUTPUT_KEY)
        appendCode(e.code.replace("\n", "\n  ") + "\n")
        if (e.status == "running") append("  (running…)\n", ConsoleViewContentType.SYSTEM_OUTPUT_KEY)
        if (e.output.isNotEmpty()) append(indent(e.output.trimEnd('\n', '\r')) + "\n", null)
        e.result?.let { appendCode(indent(it) + "\n") }
        e.diagnostics.forEach { append(indent(it) + "\n", ConsoleViewContentType.ERROR_OUTPUT_KEY) }
        e.exception?.let {
            append(indent(it), ConsoleViewContentType.ERROR_OUTPUT_KEY)
            if (e.status == "threw") {
                val start = cursor + 3
                append("   [inspect ›]", null)
                EditorHyperlinkSupport.get(history).createHyperlink(start, cursor, null, HyperlinkInfo { variables.select("\$exception") })
            }
            append("\n", null)
        }
    }

    /** History document changes: outside a command the platform rejects them (IncorrectOperationException); undo is off for it anyway. */
    private fun writeHistory(block: () -> Unit) = CommandProcessor.getInstance().runUndoTransparentAction { runWriteAction(block) }

    private fun indent(text: String) = "  " + text.replace("\n", "\n  ")

    private fun append(text: String, key: TextAttributesKey?) {
        val start = cursor
        history.document.insertString(start, text)
        cursor += text.length
        if (key != null) history.markupModel.addRangeHighlighter(key, start, start + text.length, HighlighterLayer.SYNTAX, HighlighterTargetArea.EXACT_RANGE)
    }

    /**
     * Lexes each piece of code on its own (not the whole document), so an unterminated comment or string in one entry
     * can't recolor the rest of the history.
     */
    private fun appendCode(code: String) {
        val start = cursor
        history.document.insertString(start, code)
        cursor += code.length
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

        /**
         * The window's panel, or null before the window was first shown. contentManagerIfCreated, not contentManager:
         * the latter creates the content, and actions call this from update() (Find Action, popups), where a failed or cancelled
         * update used up the platform's one call of the factory and left the window empty ("Nothing to show").
         */
        fun of(project: Project): ReplPanel? =
            ToolWindowManager.getInstance(project).getToolWindow(ReplToolWindowFactory.ID)?.contentManagerIfCreated?.getContent(0)?.component as? ReplPanel
    }
}
