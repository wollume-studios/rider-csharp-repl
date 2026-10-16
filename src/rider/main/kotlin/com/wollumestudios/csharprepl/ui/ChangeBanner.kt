package com.wollumestudios.csharprepl.ui

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.JPanel

/**
 * "⚠ `MyLib` changed since it was referenced." with the split buttons Rebuild ▾ and Ignore ▾. Each choice runs the
 * matching action (CSharpRepl.Rebuild…, CSharpRepl.Ignore…), so the banner does exactly what Find Action does.
 */
class ChangeBanner : JPanel(BorderLayout()) {
    private val label = JBLabel()

    init {
        background = JBUI.CurrentTheme.Banner.WARNING_BACKGROUND
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBUI.CurrentTheme.Banner.WARNING_BORDER_COLOR, 0, 0, 1, 0), JBUI.Borders.empty(4, 8))
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(JBOptionButton(run("Rebuild", "CSharpRepl.Rebuild"), arrayOf(
                run("Rebuild & replay", "CSharpRepl.RebuildReplay"),
                run("Rebuild & restore variables", "CSharpRepl.RebuildRestore"))))
            add(JBOptionButton(run("Ignore", "CSharpRepl.Ignore"), arrayOf(
                run("Ignore for this session", "CSharpRepl.IgnoreForSession"),
                run("Ignore forever", "CSharpRepl.IgnoreChangesForever"))))
        }
        add(label, BorderLayout.WEST)
        add(buttons, BorderLayout.CENTER)
        isVisible = false
    }

    /** Shows the banner for [project], or hides it when null. */
    fun show(project: String?) {
        if (project != null) label.text = "<html>⚠ <code>${StringUtil.escapeXmlEntities(project)}</code> changed since it was referenced.</html>"
        isVisible = project != null
    }

    private fun run(text: String, actionId: String) = object : AbstractAction(text) {
        override fun actionPerformed(e: ActionEvent) {
            val action = ActionManager.getInstance().getAction(actionId)
            ActionManager.getInstance().tryToExecute(action, null, this@ChangeBanner, "CSharpReplBanner", true)
        }
    }
}
