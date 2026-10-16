package com.wollumestudios.csharprepl.refs

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.EditorDropHandler
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.jetbrains.rider.projectView.ProjectModelDataKeys
import com.jetbrains.rider.projectView.workspace.getFile
import com.jetbrains.rider.projectView.workspace.isProject
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.host.ReferenceInfo
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File
import javax.swing.TransferHandler

/**
 * Drops onto the tool window: `.csproj` → project reference, `.dll` → DLL reference. Files from the OS file manager and
 * Solution Explorer nodes both arrive as a file list: Rider's drag object (BufferedAction, a TransferableWrapper) lists
 * each node's ProjectModelEntity file, which is the `.csproj` for a project node. Other drops get a notification.
 *
 * Installed as the panel's TransferHandler and as the editors' EditorDropHandler (the editors keep their own
 * TransferHandler for copy/paste and text drags; they only hand file drops to this).
 */
class ReferenceDrop(private val project: Project) : TransferHandler(), EditorDropHandler {
    override fun canImport(support: TransferSupport) = true // anything: unsupported drops get the notification

    override fun importData(support: TransferSupport): Boolean {
        if (!support.isDrop) return false
        drop(support.transferable)
        return true
    }

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

        /** A `.csproj`, a `.dll`, or a folder with exactly one `.csproj` (a project folder); else null. */
        fun referenceFor(file: File): ReferenceInfo? {
            val csproj = if (file.isDirectory) file.listFiles { f -> f.extension.equals("csproj", true) }?.singleOrNull() else file
            return when (csproj?.extension?.lowercase()) {
                "csproj" -> ReferenceInfo("project", csproj.path, null)
                "dll" -> ReferenceInfo("dll", csproj.path, null)
                else -> null
            }
        }

        /**
         * The `.csproj` files of the selected project nodes: VIRTUAL_FILE(_ARRAY) when it is the project file, else Rider's
         * project model entities (ProjectModelDataKeys.PROJECT_MODEL_ENTITY_ARRAY, isProject, getFile). When neither yields
         * one, the data keys present are logged once (idea.log) to see what the node offers.
         */
        fun projectFiles(dc: DataContext): List<File> {
            val vfs = (dc.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: dc.getData(CommonDataKeys.VIRTUAL_FILE)?.let { arrayOf(it) }).orEmpty()
                .filter { it.extension.equals("csproj", true) }.map { File(it.path) }
            val entities = dc.getData(ProjectModelDataKeys.PROJECT_MODEL_ENTITY_ARRAY).orEmpty()
                .filter { it.isProject() }.mapNotNull { it.getFile() }.filter { it.extension.equals("csproj", true) }
            val files = (vfs + entities).distinct()
            if (files.isEmpty() && dc.getData(CommonDataKeys.PROJECT) != null && !keysLogged) {
                keysLogged = true
                LOG.info("C# REPL: no project file in this context; data keys: " + DataKey.allKeys().filter { dc.getData(it) != null }.joinToString { it.name })
            }
            return files
        }
    }
}
