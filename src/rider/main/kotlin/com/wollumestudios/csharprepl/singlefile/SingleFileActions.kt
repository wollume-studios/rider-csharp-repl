package com.wollumestudios.csharprepl.singlefile

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.SplitButtonAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import javax.swing.JList
import com.wollumestudios.csharprepl.refs.RiderProjectModel
import java.nio.file.Files
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
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

    /**
     * `<solution dir>/scratch/`, unless a file there would sit under a `.csproj` (the project would compile it and break its
     * build): then `<user.home>/CSharpRepl/scratch/`. Created here so the dialog opens in it; null when it can't be created.
     */
    fun scratchDir(project: Project): Path? {
        val probe = project.basePath?.let { File(it, "scratch/x.cs") }
        // Any .csproj above it globs the file, whether or not that project is in the solution.
        val dir = if (probe == null || DirectiveInserter.nearestProject(probe) != null) Path.of(System.getProperty("user.home"), "CSharpRepl", "scratch") else probe.parentFile.toPath()
        return try { Files.createDirectories(dir) } catch (e: IOException) { null }
    }

    /** Save dialog in [scratchDir], named `<prefix>-<yyyyMMdd-HHmm>.cs`; null when cancelled. */
    fun chooseTarget(project: Project, prefix: String, title: String): File? {
        val dialog = FileChooserFactory.getInstance().createSaveFileDialog(FileSaverDescriptor(title, "Save as a single-file app", "cs"), project)
        return dialog.save(scratchDir(project), "$prefix-${LocalDateTime.now().format(STAMP)}.cs")?.file
    }

    /** Writes [text] to [file] through the VFS (creating its folder), then opens it. Call on the EDT. */
    fun saveAndOpen(project: Project, file: File, text: String) {
        val vf = WriteCommandAction.writeCommandAction(project).withName("Create ${file.name}").compute<VirtualFile, IOException> {
            val dir = VfsUtil.createDirectoryIfMissing(file.parentFile.path) ?: throw IOException("Cannot create ${file.parentFile}")
            val created = dir.findChild(file.name) ?: dir.createChildData(this, file.name)
            VfsUtil.saveText(created, text)
            created
        }
        OpenFileDescriptor(project, vf).navigate(true)
    }

    /**
     * Runs [done] on the EDT, in the modality [modality] captured at click time, when the host answered. A failed or
     * empty answer (host down, no text) and IO errors become a notification.
     */
    fun <T> whenAnswered(project: Project, what: String, modality: ModalityState, answer: CompletableFuture<T>, done: (T) -> Unit) {
        answer.whenComplete { r, error ->
            ApplicationManager.getApplication().invokeLater({
                try {
                    if (r == null) ReplSession.notify(project, "$what failed: ${(error?.cause ?: error)?.message}", NotificationType.ERROR) else done(r)
                } catch (e: IOException) {
                    ReplSession.notify(project, "$what failed: ${e.message}", NotificationType.ERROR)
                }
            }, modality, project.disposed)
        }
    }

    /** Gson leaves a field the host did not send null. */
    fun missingText(project: Project, what: String, text: String?): Boolean {
        if (text != null) return false
        ReplSession.notify(project, "$what failed: the host returned no text", NotificationType.ERROR)
        return true
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

/** To single-file app: saves the session, [excludeThrown] leaving the commands that threw out. */
abstract class ExportAction(text: String, private val excludeThrown: Boolean) : DumbAwareAction(text, "Save the session as a single-file app", AllIcons.ToolbarDecorator.Export) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val modality = ModalityState.current()
        val file = SingleFile.chooseTarget(project, "session", "Save Session as Single-File App") ?: return
        SingleFile.whenAnswered(project, "Export", modality, ReplSession.getInstance(project).export(file.path, excludeThrown)) { r ->
            if (SingleFile.missingText(project, "Export", r.text)) return@whenAnswered
            SingleFile.saveAndOpen(project, file, r.text)
            if (!r.warnings.isNullOrEmpty()) ReplSession.notify(project, "Exported with warnings: " + r.warnings.joinToString("; "), NotificationType.WARNING)
        }
    }
}

class ExportToSingleFileAction : ExportAction("To Single-File App", false)
class ExportToSingleFileExcludeThrownAction : ExportAction("To Single-File App (Exclude Commands That Threw)", true)

/** Toolbar "To single-file app ▾": the button repeats the last-used variant; the arrow lists both. */
class ExportSplitButtonAction : SplitButtonAction(DefaultActionGroup(ExportToSingleFileAction(), ExportToSingleFileExcludeThrownAction()))

/** Dump variables: a single-file app with `var name = <literal>;` for every variable. */
class DumpVariablesAction : DumbAwareAction("Dump Variables", "Save the variables as code in a single-file app", AllIcons.Actions.Dump) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val modality = ModalityState.current()
        val file = SingleFile.chooseTarget(project, "variables", "Save Variables as Single-File App") ?: return
        SingleFile.whenAnswered(project, "Dump", modality, ReplSession.getInstance(project).dump(file.path)) { r ->
            if (SingleFile.missingText(project, "Dump", r.text)) return@whenAnswered
            SingleFile.saveAndOpen(project, file, r.text)
            if (!r.notReproducible.isNullOrEmpty()) ReplSession.notify(project, "Not reproducible: " + r.notReproducible.joinToString(", "), NotificationType.WARNING)
        }
    }
}

/**
 * Editor menu / Generate: inserts a `#:project` directive for a solution project (picked from a list; a `.csproj` file
 * chooser when Rider's project model has none to offer). Only on single-file-app `.cs` editors.
 */
class AddProjectDirectiveAction : DumbAwareAction("Add Project Reference Directive…", "Insert #:project for a solution project at the top of this single-file app", null) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        // Cheap on purpose (runs on every popup): `.cs` with a `#!` / `#:` line. The project check is in actionPerformed.
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null && file != null &&
            file.extension.equals("cs", true) && file.isInLocalFileSystem && DirectiveInserter.looksLikeSingleFileApp(editor.document.charsSequence)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (!SingleFileApps.isSingleFileApp(project, file, editor.document.text)) {
            ReplSession.notify(project, "${file.name} belongs to a project: add a project reference there instead", NotificationType.INFORMATION)
            return
        }
        fun insert(csproj: File) {
            if (!SingleFileApps.addProjectDirectives(project, editor.document, file, listOf(csproj)))
                ReplSession.notify(project, "${file.name} is read-only", NotificationType.WARNING)
        }
        val projects = RiderProjectModel.solutionProjects(project).distinct().sortedBy { it.nameWithoutExtension.lowercase() }
        if (projects.isEmpty()) {
            FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor("csproj").withTitle("Project to Reference"), project, null)?.let { insert(File(it.path)) }
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(projects)
            .setTitle("Add Project Reference Directive")
            .setRenderer(object : ColoredListCellRenderer<File>() {
                override fun customizeCellRenderer(list: JList<out File>, value: File?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value?.nameWithoutExtension.orEmpty())
                }
            })
            .setNamerForFiltering { it.nameWithoutExtension }
            .setItemChosenCallback { insert(it) }
            .createPopup()
            .showInBestPositionFor(editor)
    }
}
