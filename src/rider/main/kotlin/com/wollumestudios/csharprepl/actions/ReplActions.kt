package com.wollumestudios.csharprepl.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.settings.ReplConfigurable
import com.wollumestudios.csharprepl.settings.ReplSettings
import com.wollumestudios.csharprepl.ui.ReplPanel

private val AnActionEvent.session get() = project?.let(ReplSession::getInstance)

/** Run: pending code plus the input, now and as is. Hidden while a run is in flight (Stop takes its place). */
class RunAction : DumbAwareAction("Run", "Run the pending code and the input now", AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isVisible = e.session?.running != true
        e.presentation.isEnabled = e.project?.let(ReplPanel::of) != null && e.session?.analyzing != true
    }
    override fun actionPerformed(e: AnActionEvent) {
        ReplPanel.of(e.project ?: return)?.runNow()
    }
}

/** Stop: kills the host and starts an empty session (references kept). Visible while a run is in flight. */
class StopAction : DumbAwareAction("Stop", "Kill the running command; the session restarts and variables are lost", AllIcons.Actions.Suspend) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.session?.running == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.session?.stop()
    }
}

class ToggleAutoRunAction : DumbAwareToggleAction("Auto-Run on Enter", "Enter runs the command (adding a missing ;); off: Enter collects lines until the command is complete", AllIcons.Actions.Lightning) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = ReplSettings.state.autoRun
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ReplSettings.state.autoRun = state
        ReplSettings.changed()
    }
}

/** Reset: a fresh session; history, variables and pending code cleared, references kept. */
class ResetAction : DumbAwareAction("Reset", "Restart the session: clears history, variables and pending code; references are kept", AllIcons.Actions.Rollback) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.session?.reset()
    }
}

class ClearPendingAction : DumbAwareAction("Clear Pending Code", "Discard the code waiting for a complete command", AllIcons.Actions.Close) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.session?.let { it.pending.isNotEmpty() && !it.analyzing } == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.session?.clearPending()
    }
}

class SettingsAction : DumbAwareAction("C# REPL Settings…", "Settings › Tools › C# REPL", AllIcons.General.Settings) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) =
        ShowSettingsUtil.getInstance().showSettingsDialog(e.project, ReplConfigurable::class.java)
}

/** Enter in the input (bound on the input component; see ReplPanel). Auto-run: run, adding a missing ;. Manual: collect until complete. */
class EnterKeyAction : InputAction("Enter in C# REPL", "Run the input or continue it, depending on the auto-run mode") {
    override fun actionPerformed(e: AnActionEvent) {
        ReplPanel.of(e.project ?: return)?.enter(shift = false)
    }
}

/** Shift+Enter in the input. Auto-run: new line. Manual: run pending code and the input now. */
class ShiftEnterKeyAction : InputAction("Shift+Enter in C# REPL", "New line (auto-run) or run now (manual)") {
    override fun actionPerformed(e: AnActionEvent) {
        ReplPanel.of(e.project ?: return)?.enter(shift = true)
    }
}

/** Escape in the input: clears it. */
class ClearInputAction : InputAction("Clear C# REPL Input", "Clear the input field") {
    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isEnabled) e.presentation.isEnabled = ReplPanel.of(e.project!!)?.input?.text?.isNotEmpty() == true
    }
    override fun actionPerformed(e: AnActionEvent) {
        ReplPanel.of(e.project ?: return)?.clearInput()
    }
}

/** Enabled while the tool window exists and no Enter is waiting for `analyze`. */
abstract class InputAction(text: String, description: String) : DumbAwareAction(text, description, null) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let(ReplPanel::of) != null && e.session?.analyzing != true
    }
}
