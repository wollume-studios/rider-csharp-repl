package com.wollumestudios.csharprepl.singlefile

import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorDropHandler
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.impl.EditorImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.EditorWindow
import com.intellij.openapi.project.Project
import com.wollumestudios.csharprepl.refs.ReferenceDrop
import com.wollumestudios.csharprepl.refs.RiderProjectModel
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

/** Gives the editors of single-file apps (`.cs` outside the solution's projects, or with `#:` directives) a drop handler for projects. */
class SingleFileDropInstaller : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor as? EditorImpl ?: return
        val project = editor.project ?: return
        if (editor.editorKind != EditorKind.MAIN_EDITOR) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!file.extension.equals("cs", true) || !file.isInLocalFileSystem) return
        // Later: the file editor installs its own drop handler while it is built, and ours must wrap that one.
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed) return@invokeLater
            val projects = RiderProjectModel.solutionProjects(project)
            // Model not loaded: unknown, so "in a project" unless the file says otherwise with #: directives.
            val inProject = !RiderProjectModel.available || DirectiveInserter.inSolutionProject(File(file.path), projects)
            val previous = previousHandler(editor) ?: return@invokeLater // unreadable: keep the default drop
            if (DirectiveInserter.isSingleFileApp(editor.document.text, inProject))
                editor.setDropHandler(SingleFileDrop(project, editor, previous))
        }
    }

    // ponytail: EditorImpl.getDropHandler is package-private (FileEditorDropHandler is internal), so the current handler is read reflectively; if that breaks, no handler is installed.
    private fun previousHandler(editor: EditorImpl): EditorDropHandler? =
        runCatching { EditorImpl::class.java.getDeclaredField("myDropHandler").apply { isAccessible = true }.get(editor) as? EditorDropHandler }.getOrNull()

}

/** Projects dropped onto the editor become `#:project` directives; any other drop goes to [previous]. */
private class SingleFileDrop(private val project: Project, private val editor: Editor, private val previous: EditorDropHandler) : EditorDropHandler {
    override fun canHandleDrop(flavors: Array<out DataFlavor>) = previous.canHandleDrop(flavors) || FileCopyPasteUtil.isFileListFlavorAvailable(flavors)

    override fun handleDrop(t: Transferable, project: Project?, editorWindow: EditorWindow?) = handle(t) { previous.handleDrop(t, project, editorWindow) }

    override fun handleDrop(t: Transferable, project: Project?, editorWindow: EditorWindow?, dropAction: Int) =
        handle(t) { previous.handleDrop(t, project, editorWindow, dropAction) }

    private fun handle(t: Transferable, fallback: () -> Unit) {
        val projects = FileCopyPasteUtil.getFileList(t).orEmpty().mapNotNull { ReferenceDrop.referenceFor(it)?.takeIf { r -> r.kind == "project" } }
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        if (projects.isEmpty() || file == null) return fallback()
        val directives = projects.map { DirectiveInserter.projectDirective(File(file.path), File(it.pathOrName)) }
        WriteCommandAction.runWriteCommandAction(project, "Add Project Reference", null, {
            val doc = editor.document
            directives.forEach { d ->
                val old = doc.text
                val new = DirectiveInserter.insert(old, d)
                val at = old.commonPrefixWith(new).length
                if (new != old) doc.insertString(at, new.substring(at, at + new.length - old.length))
            }
        })
    }
}
