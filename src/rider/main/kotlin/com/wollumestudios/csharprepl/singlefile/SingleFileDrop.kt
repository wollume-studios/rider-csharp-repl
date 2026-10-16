package com.wollumestudios.csharprepl.singlefile

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.CustomFileDropHandler
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.refs.ReferenceDrop
import com.wollumestudios.csharprepl.refs.RiderProjectModel
import java.awt.datatransfer.Transferable
import java.io.File

/** What makes a `.cs` editor a single-file app, and how project directives get into it (drop and the editor action share this). */
object SingleFileApps {
    /** A local `.cs` file with `#:` directives, or outside the solution's projects. Rider's model not loaded: only `#:` files qualify. */
    fun isSingleFileApp(project: Project, file: VirtualFile, text: String): Boolean {
        if (!file.extension.equals("cs", true) || !file.isInLocalFileSystem) return false
        if (DirectiveInserter.hasDirectives(text)) return true
        val projects = RiderProjectModel.solutionProjects(project)
        val inProject = !RiderProjectModel.available || DirectiveInserter.inSolutionProject(File(file.path), projects)
        return DirectiveInserter.isSingleFileApp(text, inProject)
    }

    /** Inserts a `#:project` line per project in [document] of [file] (one undo step). False when the file is read-only. */
    fun addProjectDirectives(project: Project, document: Document, file: VirtualFile, csprojs: List<File>): Boolean {
        if (!ReadonlyStatusHandler.ensureFilesWritable(project, file)) return false
        val directives = csprojs.map { DirectiveInserter.projectDirective(File(file.path), it) }
        WriteCommandAction.runWriteCommandAction(project, "Add Project Reference", null, {
            directives.forEach { d ->
                val old = document.text
                val new = DirectiveInserter.insert(old, d)
                val at = old.commonPrefixWith(new).length
                if (new != old) document.insertString(at, new.substring(at, at + new.length - old.length))
            }
        })
        return true
    }
}

/**
 * Registered as `customFileDropHandler`: Rider's FileDropManager asks these first (on the EDT) and only opens the dropped
 * files when none handles the drop. Projects dropped onto a single-file app's editor become `#:project` directives;
 * every other drop and every other editor is left alone (canHandle is false).
 */
class SingleFileProjectDrop : CustomFileDropHandler() {
    override fun canHandle(t: Transferable, editor: Editor?): Boolean = try {
        val project = editor?.project
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        editor != null && project != null && file != null && projectFiles(t).isNotEmpty() && SingleFileApps.isSingleFileApp(project, file, editor.document.text)
    } catch (e: Throwable) {
        LOG.warn("C# REPL: single-file drop check failed", e)
        false
    }

    override fun handleDrop(t: Transferable, editor: Editor?, project: Project): Boolean = try {
        val file = editor?.let { FileDocumentManager.getInstance().getFile(it.document) }
        if (file == null) false
        else {
            if (!SingleFileApps.addProjectDirectives(project, editor.document, file, projectFiles(t)))
                ReplSession.notify(project, "${file.name} is read-only", NotificationType.WARNING)
            true // canHandle said yes: don't let the drop open the .csproj as well
        }
    } catch (e: Throwable) {
        LOG.warn("C# REPL: single-file drop failed", e)
        false
    }

    private fun projectFiles(t: Transferable) =
        FileCopyPasteUtil.getFileList(t).orEmpty().mapNotNull { ReferenceDrop.referenceFor(it) }.filter { it.kind == "project" }.map { File(it.pathOrName) }

    private companion object {
        val LOG = logger<SingleFileProjectDrop>()
    }
}
