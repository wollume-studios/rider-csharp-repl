package com.wollumestudios.csharprepl.singlefile

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.settings.ReplSettings
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture

object SingleFile {
    const val TEMPLATE = "#!/usr/bin/env dotnet\n\nConsole.WriteLine(\"Hello\");\n"

    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

    /** Save dialog in `<solution dir>/scratch/` (created on save) named `<prefix>-<yyyyMMdd-HHmm>.cs`; null when cancelled. */
    fun chooseTarget(project: Project, prefix: String, title: String): File? {
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor(title, "Save as a single-file app", "cs"), project)
        val base = project.basePath?.let { Path.of(it, "scratch") }
        return dialog.save(base, "$prefix-${LocalDateTime.now().format(STAMP)}.cs")?.file
    }

    /** Writes [text] to [file] through the VFS (creating its folder), then opens it. Call on the EDT. */
    fun saveAndOpen(project: Project, file: File, text: String) {
        val vf = WriteCommandAction.writeCommandAction(project).withName("Create ${file.name}").compute<VirtualFile, IOException> {
            val dir = VfsUtil.createDirectoryIfMissing(file.parentFile.path) ?: throw IOException("Cannot create ${file.parentFile}")
            val created = dir.findChild(file.name) ?: dir.createChildData(this, file.name)
            VfsUtil.saveText(created, text)
            created
        }
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

    /** Runs [done] on the EDT when the host answered; failures (host down, IO) become a notification. */
    fun <T> whenAnswered(project: Project, what: String, answer: CompletableFuture<T>, done: (T) -> Unit) {
        answer.whenComplete { r, error ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    if (r == null) ReplSession.notify(project, "$what failed: ${(error?.cause ?: error)?.message}", NotificationType.ERROR) else done(r)
                } catch (e: IOException) {
                    ReplSession.notify(project, "$what failed: ${e.message}", NotificationType.ERROR)
                }
            }, ModalityState.defaultModalityState(), project.disposed)
        }
    }
}

/** File › New, the project views' New menu and the ⚙ menu: asks for a name and creates `<name>.cs` in the selected folder. */
class NewSingleFileAppAction : DumbAwareAction("C# Single-File App", "Create a .cs file that runs with dotnet run", AllIcons.FileTypes.Text) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dir = targetDir(e) ?: return
        val name = Messages.showInputDialog(project, "File name:", "New C# Single-File App", null, "App", object : InputValidator {
            override fun checkInput(inputString: String?) =
                !inputString.isNullOrBlank() && inputString.none { it in "\\/:*?\"<>|" } && !File(dir, fileName(inputString)).exists()
            override fun canClose(inputString: String?) = checkInput(inputString)
        }) ?: return
        try {
            SingleFile.saveAndOpen(project, File(dir, fileName(name)), SingleFile.TEMPLATE)
        } catch (ex: IOException) {
            ReplSession.notify(project, "Could not create ${fileName(name)}: ${ex.message}", NotificationType.ERROR)
        }
    }

    private fun fileName(input: String) = input.trim().let { if (it.endsWith(".cs", true)) it else "$it.cs" }

    /** The selected folder (or the selected file's folder), else the solution folder. */
    private fun targetDir(e: AnActionEvent): File? {
        val selected = e.getData(CommonDataKeys.VIRTUAL_FILE)?.takeIf { it.isInLocalFileSystem }?.let { if (it.isDirectory) it else it.parent }
        return (selected?.path ?: e.project?.basePath)?.let(::File)
    }
}

/**
 * To single-file app ▾: the button exports with the current toggle; the arrow opens the dropdown with the toggle.
 * A popup group that is also performed as an action (isPerformGroup): ActionButton shows the arrow and runs [actionPerformed] on click.
 */
class ExportToSingleFileGroup : DefaultActionGroup(), DumbAware {
    init {
        isPopup = true
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isPerformGroup = true
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = SingleFile.chooseTarget(project, "session", "Save Session as Single-File App") ?: return
        val exclude = ReplSettings.state.exportExcludeThrown
        SingleFile.whenAnswered(project, "Export", ReplSession.getInstance(project).export(file.path, exclude)) { r ->
            SingleFile.saveAndOpen(project, file, r.text)
            if (!r.warnings.isNullOrEmpty()) ReplSession.notify(project, "Exported with warnings: " + r.warnings.joinToString("; "), NotificationType.WARNING)
        }
    }
}

class ExcludeThrownAction : DumbAwareToggleAction("Exclude Commands That Threw", "Leave the commands that threw an exception out of the exported file", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = ReplSettings.state.exportExcludeThrown
    override fun setSelected(e: AnActionEvent, state: Boolean) {
        ReplSettings.state.exportExcludeThrown = state
        ReplSettings.changed()
    }
}

/** Dump variables: a single-file app with `var name = <literal>;` for every variable. */
class DumpVariablesAction : DumbAwareAction("Dump Variables", "Save the variables as code in a single-file app", AllIcons.Actions.Dump) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = SingleFile.chooseTarget(project, "variables", "Save Variables as Single-File App") ?: return
        SingleFile.whenAnswered(project, "Dump", ReplSession.getInstance(project).dump(file.path)) { r ->
            SingleFile.saveAndOpen(project, file, r.text)
            if (!r.notReproducible.isNullOrEmpty()) ReplSession.notify(project, "Not reproducible: " + r.notReproducible.joinToString(", "), NotificationType.WARNING)
        }
    }
}
