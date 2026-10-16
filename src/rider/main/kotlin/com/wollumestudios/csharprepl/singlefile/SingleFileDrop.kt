package com.wollumestudios.csharprepl.singlefile

import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.FileDropEvent
import com.intellij.openapi.editor.FileDropHandler
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.vfs.VirtualFile
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.refs.ReferenceDrop
import com.wollumestudios.csharprepl.refs.RiderProjectModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * Registered as `fileDropHandler`: Rider's FileDropManager asks these in turn and the first one that returns true wins
 * (the default handler opens the dropped files). Projects dropped onto a single-file app's editor become `#:project`
 * directives; every other drop and every other editor gets false and is left alone.
 */
class SingleFileProjectDrop : FileDropHandler {
    override suspend fun handleDrop(e: FileDropEvent): Boolean = withContext(Dispatchers.EDT) {
        try {
            val editor = e.editor ?: return@withContext false
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return@withContext false
            val projects = e.files.mapNotNull { ReferenceDrop.referenceFor(it) }.filter { it.kind == "project" }.map { File(it.pathOrName) }
            if (projects.isEmpty() || !SingleFileApps.isSingleFileApp(e.project, file, editor.document.text)) return@withContext false
            if (!SingleFileApps.addProjectDirectives(e.project, editor.document, file, projects))
                ReplSession.notify(e.project, "${file.name} is read-only", NotificationType.WARNING)
            true // handled: the drop must not open the .csproj as well
        } catch (ex: Exception) {
            LOG.warn("C# REPL: single-file drop failed", ex)
            false
        }
    }

    private companion object {
        val LOG = logger<SingleFileProjectDrop>()
    }
}
