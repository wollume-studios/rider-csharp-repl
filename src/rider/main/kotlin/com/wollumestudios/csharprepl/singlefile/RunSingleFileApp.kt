package com.wollumestudios.csharprepl.singlefile

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.wollumestudios.csharprepl.ReplSession
import com.wollumestudios.csharprepl.host.DotnetLocator
import java.nio.charset.StandardCharsets

/**
 * Editor menu and gutter (first line): `dotnet run <file>` for a single-file app (`#!` or `#:` at the top) in the Run
 * tool window, in the file's folder. Rider 2026.3 offers no run gutter or configuration for such a file outside the solution.
 */
class RunSingleFileAppAction : DumbAwareAction("Run Single-File App", "dotnet run this file-based app in the Run tool window", AllIcons.Actions.Execute) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val text = e.getData(CommonDataKeys.EDITOR)?.document?.charsSequence
        e.presentation.isEnabledAndVisible = e.project != null && file != null && text != null && isCandidate(file, text)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        run(project, file)
    }

    companion object {
        /** Cheap (menus, gutter): a local `.cs` file starting with `#!` or holding `#:` directives. */
        fun isCandidate(file: VirtualFile, text: CharSequence) =
            file.isInLocalFileSystem && file.extension.equals("cs", true) && DirectiveInserter.looksLikeSingleFileApp(text)

        fun run(project: Project, file: VirtualFile) {
            val dotnet = DotnetLocator.dotnet
                ?: return ReplSession.notify(project, "dotnet not found (PATH, DOTNET_ROOT, default install locations)", NotificationType.ERROR)
            FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)
            val command = GeneralCommandLine(dotnet.toString(), "run", file.path)
                .withWorkingDirectory(file.parent.toNioPath())
                .withCharset(StandardCharsets.UTF_8)
                .withEnvironment("DOTNET_CLI_UI_LANGUAGE", "en") // build messages in the Run window the same on every machine
            val handler = ColoredProcessHandler(command)
            ProcessTerminatedListener.attach(handler)
            RunContentExecutor(project, handler).withTitle(file.name).withRerun { run(project, file) }.withActivateToolWindow(true).run()
        }
    }
}

/** The gutter's ▶ on the first line of a single-file app, offering [RunSingleFileAppAction]. */
class RunSingleFileAppLineMarker : RunLineMarkerContributor() {
    override fun getInfo(element: PsiElement): Info? {
        if (element.firstChild != null || element.textRange.startOffset != 0) return null // the file's first leaf only
        val file = element.containingFile?.virtualFile ?: return null
        if (!RunSingleFileAppAction.isCandidate(file, element.containingFile.viewProvider.contents)) return null
        val action = ActionManager.getInstance().getAction("CSharpRepl.RunSingleFileApp") ?: return null
        return Info(AllIcons.Actions.Execute, arrayOf(action)) { "Run ${file.name}" }
    }
}
