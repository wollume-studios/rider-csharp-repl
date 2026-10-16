package com.wollumestudios.csharprepl.refs

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorDropHandler
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.jetbrains.rider.projectView.ProjectModelDataKeys
import com.jetbrains.rider.projectView.workspace.findProjects
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.isProject
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.host.ReferenceInfo
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.InputEvent
import java.io.File
import javax.swing.JComponent
import javax.swing.TransferHandler

/**
 * Drops onto the tool window: `.csproj` → project reference, `.dll` → DLL reference. Files from the OS file manager and
 * Solution Explorer nodes both arrive as a file list: Rider's drag object (BufferedAction, a TransferableWrapper) lists
 * each node's ProjectModelEntity file, which is the `.csproj` for a project node. Other drops get a notification.
 *
 * As a TransferHandler ([installOn]) it keeps the component's own copy/drag export ([export]); as the editors'
 * EditorDropHandler it only sees file drops (the editors keep their TransferHandler for text).
 */
class ReferenceDrop(private val project: Project, private val export: TransferHandler? = null) : TransferHandler(), EditorDropHandler {
    /** Makes drops on [components] reference projects/DLLs, keeping their copy/drag-out behavior. */
    fun installOn(vararg components: JComponent) = components.forEach { it.transferHandler = ReferenceDrop(project, it.transferHandler) }

    override fun canImport(support: TransferSupport) = support.isDrop // any drop: unsupported ones get the notification

    override fun importData(support: TransferSupport): Boolean {
        if (!support.isDrop) return false
        drop(support.transferable)
        return true
    }

    override fun getSourceActions(c: JComponent) = export?.getSourceActions(c) ?: NONE
    override fun exportAsDrag(comp: JComponent, e: InputEvent, action: Int) { export?.exportAsDrag(comp, e, action) }
    override fun exportToClipboard(comp: JComponent, clip: Clipboard, action: Int) { export?.exportToClipboard(comp, clip, action) }

    override fun canHandleDrop(flavors: Array<out DataFlavor>) = FileCopyPasteUtil.isFileListFlavorAvailable(flavors)

    override fun handleDrop(t: Transferable, project: Project?, editorWindow: EditorWindow?) = drop(t)

    private fun drop(t: Transferable) {
        val refs = FileCopyPasteUtil.getFileList(t).orEmpty().mapNotNull(::referenceFor)
        if (refs.isEmpty()) ReplSession.notify(project, "Drop a project or DLL to reference it")
        else refs.forEach(ReplSession.getInstance(project)::addReference)
    }

    companion object {
        private val LOG = logger<ReferenceDrop>()
        @Volatile private var keysLogged = false

        fun projectReference(file: File) = ReferenceInfo("project", ProjectChangeWatcher.canonical(file.path), null)

        fun dllReference(file: File) = ReferenceInfo("dll", ProjectChangeWatcher.canonical(file.path), null)

        /** A `.csproj`, a `.dll`, or a folder with exactly one `.csproj` (a project folder); else null. Paths are canonical. */
        fun referenceFor(file: File): ReferenceInfo? {
            val csproj = if (file.isDirectory) file.listFiles { f -> f.extension.equals("csproj", true) }?.singleOrNull() else file
            return when (csproj?.extension?.lowercase()) {
                "csproj" -> projectReference(csproj)
                "dll" -> dllReference(csproj)
                else -> null
            }
        }

        /**
         * The `.csproj` files of the selected project nodes: VIRTUAL_FILE(_ARRAY) when it is the project file, else Rider's
         * project model ([RiderProjectModel]). When a node the model calls a project yields no `.csproj`, the data keys
         * present are logged once (idea.log) to see what such a node offers.
         */
        fun projectFiles(dc: DataContext): List<File> {
            val vfs = (dc.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: dc.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }).orEmpty()
                .filter { it.extension.equals("csproj", true) }.map { File(it.path) }
            val model = RiderProjectModel.selection(dc)
            val files = (vfs + model.projectFiles).distinctBy { ProjectChangeWatcher.canonical(it.path) }
            if (files.isEmpty() && model.projectNodes > 0 && !keysLogged) {
                keysLogged = true
                LOG.info("C# REPL: a project node without a .csproj; data keys: " + DataKey.allKeys().filter { dc.getData(it) != null }.joinToString { it.name })
            }
            return files
        }
    }
}

/**
 * Rider's frontend project model (module intellij.rider.rdclient.dotnet). Loading it from this plugin is not verified
 * in a running IDE: a LinkageError (NoClassDefFoundError, …) is logged once and treated as "nothing found", so callers
 * fall back to plain `.csproj` files and `update()` never throws.
 */
object RiderProjectModel {
    class Selection(val projectNodes: Int, val projectFiles: List<File>)

    private val LOG = logger<RiderProjectModel>()
    @Volatile private var failed = false

    /** The selected nodes the model calls projects, and their `.csproj` files. */
    fun selection(dc: DataContext): Selection = guarded(Selection(0, emptyList())) { Impl.selection(dc) }

    /** The solution's `.csproj` files. */
    fun solutionProjects(project: Project): List<File> = guarded(emptyList()) { Impl.solutionProjects(project) }

    /** False once loading the model failed: then [solutionProjects] is empty because nothing is known, not because there are none. */
    val available get() = !failed

    private inline fun <T> guarded(fallback: T, block: () -> T): T {
        if (failed) return fallback
        return try {
            block()
        } catch (e: LinkageError) {
            failed = true
            LOG.warn("C# REPL: Rider's project model is not available; using .csproj files only", e)
            fallback
        }
    }

    /** Only this class touches the Rider types, so a class-loading failure surfaces inside [guarded]. */
    private object Impl {
        fun selection(dc: DataContext): Selection {
            val projects = dc.getData(ProjectModelDataKeys.PROJECT_MODEL_ENTITY_ARRAY).orEmpty().filter { it.isProject() }
            return Selection(projects.size, projects.mapNotNull { it.getFile() }.filter { it.extension.equals("csproj", true) })
        }

        fun solutionProjects(project: Project) =
            WorkspaceModel.getInstance(project).findProjects().mapNotNull { it.getFile() }.filter { it.extension.equals("csproj", true) }
    }
}
