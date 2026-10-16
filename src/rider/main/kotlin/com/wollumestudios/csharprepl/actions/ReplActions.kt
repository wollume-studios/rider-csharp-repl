package com.wollumestudios.csharprepl.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.RestartMode
import com.wollumestudios.csharprepl.displayName
import com.wollumestudios.csharprepl.host.ReferenceInfo
import com.wollumestudios.csharprepl.refs.ReferenceDrop
import com.wollumestudios.csharprepl.refs.RiderProjectModel
import java.io.File
import com.wollumestudios.csharprepl.ui.ReplToolWindowFactory
import com.wollumestudios.csharprepl.settings.ReplConfigurable
import com.wollumestudios.csharprepl.settings.ReplSettings
import com.wollumestudios.csharprepl.settings.VariablesPosition
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

/** + Reference: a popup with the solution's projects, "DLL…" and "NuGet package…". Same effect as a drop. */
class AddReferenceAction : DumbAwareAction("Add Reference…", "Reference a solution project, a DLL or a NuGet package in the C# REPL", AllIcons.General.Add) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val session = ReplSession.getInstance(project)
        // Rider's frontend project model (workspace model): every project entity of the solution, by its project file.
        val projects = RiderProjectModel.solutionProjects(project).distinct().sortedBy { it.nameWithoutExtension.lowercase() }
        val group = DefaultActionGroup().apply {
            projects.forEach { f -> add(DumbAwareAction.create(f.nameWithoutExtension) { session.addReference(ReferenceDrop.projectReference(f)) }) }
            if (projects.isNotEmpty()) addSeparator()
            add(DumbAwareAction.create("DLL…") { chooseDll(project)?.let(session::addReference) })
            add(DumbAwareAction.create("NuGet package…") { askNuGet(project)?.let(session::addReference) })
        }
        val popup = JBPopupFactory.getInstance().createActionGroupPopup("Add Reference", group, e.dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
        val anchor = e.inputEvent?.component
        if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
    }

    private fun chooseDll(project: Project): ReferenceInfo? =
        FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("dll").withTitle("Reference DLL"), project, null)
            ?.let { ReferenceDrop.dllReference(File(it.path)) }

    private fun askNuGet(project: Project): ReferenceInfo? {
        lateinit var name: JBTextField
        lateinit var version: JBTextField
        val form = panel {
            row("Name:") { name = textField().columns(COLUMNS_MEDIUM).focused().validationOnApply { if (it.text.isBlank()) error("Enter the package name") else null }.component }
            row("Version:") { version = textField().columns(COLUMNS_SHORT).validationOnApply { if (it.text.isBlank()) error("Enter the package version") else null }.component }
        }
        val dialog = object : DialogWrapper(project) {
            init {
                title = "Reference NuGet Package"
                init()
            }
            override fun createCenterPanel() = form
            override fun doValidateAll() = form.validateAll()
        }
        if (!dialog.showAndGet()) return null
        if (name.text.isBlank() || version.text.isBlank()) return null
        return ReferenceInfo("nuget", name.text.trim(), version.text.trim())
    }
}

/** Solution Explorer / Project view: "Reference in C# REPL" on project nodes only. */
class ReferenceInReplAction : DumbAwareAction("Reference in C# REPL", "Build this project and reference it in the C# REPL session", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && ReferenceDrop.projectFiles(e.dataContext).isNotEmpty()
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReferenceDrop.projectFiles(e.dataContext).forEach { ReplSession.getInstance(project).addReference(ReferenceDrop.projectReference(it)) }
        ToolWindowManager.getInstance(project).getToolWindow(ReplToolWindowFactory.ID)?.show() // "Building …" shows there
    }
}

/** ⚙ menu: one submenu per reference, each with Remove (which restarts the session after asking). */
class ReferencesGroup : ActionGroup(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT // references belong to the EDT
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val refs = e?.project?.serviceIfCreated<ReplSession>()?.references?.toList().orEmpty()
        if (refs.isEmpty()) return EMPTY_ARRAY
        return arrayOf<AnAction>(Separator.create("References")) +
            refs.map { DefaultActionGroup("${it.displayName} (${it.kind})", listOf(RemoveReferenceAction(it))).apply { isPopup = true } } +
            Separator.create()
    }
}

class RemoveReferenceAction(private val ref: ReferenceInfo) : DumbAwareAction("Remove", "Remove ${ref.displayName} from the session (restarts it)", AllIcons.General.Remove) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        if (Messages.showYesNoDialog(project, "Removing a reference resets the session. Reset now?", "Remove ${ref.displayName}", Messages.getQuestionIcon()) == Messages.YES)
            ReplSession.getInstance(project).removeReference(ref)
    }
}

/** Banner choices, also reachable without the banner (Find Action, UI tests). Rebuild = restart: the new host rebuilds every reference. */
abstract class SessionAction(text: String, description: String, private val run: (ReplSession) -> Unit) : DumbAwareAction(text, description, null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
    override fun actionPerformed(e: AnActionEvent) {
        e.session?.let(run)
    }
}

class RebuildAction : SessionAction("Rebuild References and Restart", "Rebuild the referenced projects; empty session, references kept", { it.rebuild() })
class RebuildReplayAction : SessionAction("Rebuild References and Replay", "Rebuild, restart, then rerun the successful commands in order", { it.restart(RestartMode.Replay) })
class RebuildRestoreAction : SessionAction("Rebuild References and Restore Variables", "Rebuild, restart, then run the session's type declarations and variables as code (visibly)", { it.restart(RestartMode.Restore) })
class IgnoreChangeAction : SessionAction("Ignore Referenced Project Change", "Hide the banner until the next change", { it.dismissChange() })
class IgnoreForSessionAction : SessionAction("Ignore Referenced Project Changes for This Session", "No banner until the next Reset", { it.ignoreUntilReset = true; it.dismissChange() })

/** "Ignore forever" (banner) and its switch back (⚙ menu, Settings › Tools › C# REPL). */
class IgnoreChangesForeverAction : DumbAwareToggleAction("Ignore Referenced Project Changes Forever", "Never show the banner when a referenced project changes", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = ReplSettings.state.ignoreChangesForever
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ReplSettings.state.ignoreChangesForever = state
        ReplSettings.changed()
        if (state) e.project?.serviceIfCreated<ReplSession>()?.dismissChange()
    }
}

/** ⚙ menu: the variables table below the input instead of right of the history (same as the settings page). */
class ToggleVariablesBelowAction : DumbAwareToggleAction("Variables Below Input", "Show the variables table below the input instead of right of the history", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = ReplSettings.state.variablesPosition == VariablesPosition.BELOW
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ReplSettings.state.variablesPosition = if (state) VariablesPosition.BELOW else VariablesPosition.RIGHT
        ReplSettings.changed()
    }
}

/** Enabled while the tool window exists and no Enter is waiting for `analyze`. */
abstract class InputAction(text: String, description: String) : DumbAwareAction(text, description, null) {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project?.let(ReplPanel::of) != null && e.session?.analyzing != true
    }
}
