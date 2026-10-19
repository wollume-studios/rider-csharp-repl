package com.wollumestudios.csharprepl.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.model.LockSemantics
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.expireNotifications
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.xQuery
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitNotNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The PNGs in docs/screenshots (README, plugin description), from one session on the sample solution, each cropped to the
 * REPL window, a popup, a dialog or the editor. Not part of the normal uiTest run: ./gradlew uiTest -PuiTestScreenshots.
 */
@Tag("screenshots")
class ScreenshotsUiTest : UiTestBase() {
    private val docs = Path.of(System.getProperty("csharprepl.docsScreenshots", "docs/screenshots"))

    @Test
    fun `README and plugin description screenshots`() = withRider("screenshots", prepare = { dir ->
        // Packages for the NuGet picker's "Packages in this solution" (in the local NuGet cache, so Greeter still builds offline).
        val csproj = dir.resolve("Greeter/Greeter.csproj")
        Files.writeString(csproj, Files.readString(csproj).replace("</Project>", """
            |  <ItemGroup>
            |    <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
            |    <PackageReference Include="Humanizer.Core" Version="2.14.1" />
            |    <PackageReference Include="Serilog" Version="4.2.0" />
            |  </ItemGroup>
            |
            |</Project>""".trimMargin()))
    }) {
        withContext(OnDispatcher.EDT) { cast(ideFrame().component, AwtWindowRef::class).setBounds(0, 0, 1200, 720) }
        invokeAction("HideAllWindows") // Solution Explorer, Junie, …: the REPL gets the frame
        openToolWindow("C# REPL")
        val panelUi = ideFrame().x { byType("com.wollumestudios.csharprepl.ui.ReplPanel") }
        val panel = cast(panelUi.component, ReplPanel::class)
        val session = service(ReplSession::class, singleProject())
        maximizeRepl()

        // repl-window: a realistic session, a FormatException with $exception expanded.
        for (code in listOf(
            "var greeting = \"Hello, REPL\";",
            "greeting.ToUpper()",
            "var words = greeting.Split(' ').Select(w => w.Trim(',')).ToList();",
            "foreach (var w in words) Console.WriteLine($\"{w}: {w.Length}\");",
            "int.Parse(\"41\") + 1",
            "int.Parse(\"forty-two\")",
        )) run(panelUi, session, code)
        withContext(OnDispatcher.EDT) {
            val variables = panel.getVariables()
            variables.select("\$exception")
            val tree = variables.getTable().getTree()
            tree.getSelectionRows()?.firstOrNull()?.let(tree::expandRow)
        }
        Thread.sleep(2000) // the exception's fields
        docShot("repl-window", replArea())

        // completion: the lookup after `greeting.ToL`. With the variables below the input, the lookup has room under it.
        invokeAction("CSharpRepl.ToggleVariablesBelow", component = panelUi.component)
        Thread.sleep(1500)
        val editor = withContext(OnDispatcher.EDT) { panel.getInput().getEditor() } ?: fail("input has no editor")
        withWriteAction { panel.getInput().setText("greeting") }
        withContext(OnDispatcher.EDT) { editor.getCaretModel().moveToOffset(8) }
        type(editor, ".ToL")
        val lookup = waitNotNull("completion lookup", timeout = 1.minutes) {
            withContext(OnDispatcher.EDT) { utility(LookupManagerRef::class).getActiveLookup(editor) }?.takeIf { l -> withContext(OnDispatcher.EDT) { l.getItems().any { it.getLookupString() == "ToList" } } }
        }
        Thread.sleep(1500)
        val lookupRect = rectOf(withContext(OnDispatcher.EDT) { cast(lookup, LookupImplRef::class).getComponent() })
        val inputRect = rectOf(cast(panel.getInput(), com.intellij.driver.sdk.ui.remote.Component::class))
        val window = replArea()
        val top = inputRect.y - 130
        docShot("completion", Rectangle(window.x, top, minOf(window.width, lookupRect.x + lookupRect.width + 200 - window.x), lookupRect.y + lookupRect.height + 40 - top))
        invokeAction("EditorEscape", component = editor.getContentComponent())
        invokeAction("CSharpRepl.ToggleVariablesBelow", component = panelUi.component)
        withWriteAction { panel.getInput().setText("") }

        // manual-mode: Enter on an incomplete command puts it in the pending box.
        invokeAction("CSharpRepl.ToggleAutoRun", component = panelUi.component)
        panelUi.enter("var r = 0")
        waitFor("var r = 0 pending", timeout = 30.seconds) { withContext(OnDispatcher.EDT) { panel.getInput().getText().isEmpty() } }
        withWriteAction { panel.getInput().setText("+ 1") }
        Thread.sleep(1500)
        docShot("manual-mode", replArea())
        withWriteAction { panel.getInput().setText("") }
        invokeAction("CSharpRepl.ClearPending", component = panelUi.component)
        invokeAction("CSharpRepl.ToggleAutoRun", component = panelUi.component)

        // references-banner: Greeter referenced and called, then Greeter.cs edited.
        maximizeRepl(false)
        val tree = selectGreeter()
        invokeAction("CSharpRepl.ReferenceInRepl", component = tree.component)
        waitFor("Greeter referenced", timeout = 5.minutes) { session.historyText().let { "Referenced Greeter" in it || "Greeter not referenced" in it } }
        check("Referenced Greeter" in session.historyText()) { session.historyText() }
        invokeAction("HideAllWindows")
        openToolWindow("C# REPL")
        maximizeRepl()
        run(panelUi, session, "Greeter.Hi(\"REPL\")")
        val greeter = Path.of(singleProject().getBasePath()!!, "Greeter", "Greeter.cs")
        Files.writeString(greeter, Files.readString(greeter).replace("\$\"Hi {n}\"", "\$\"Hello, {n}!\""))
        utility(LocalFileSystemRef::class).getInstance().refreshAndFindFileByPath(greeter.toString().replace('\\', '/'))
        waitFor("change banner", timeout = 2.minutes) { session.getChangedProject() != null }
        Thread.sleep(1500)
        docShot("references-banner", replArea())
        invokeAction("CSharpRepl.Ignore", component = panelUi.component)

        // nuget-picker: + Reference › NuGet package…, a search, a result selected.
        invokeAction("CSharpRepl.AddReference", component = panelUi.component, now = false)
        val nuget = waitNotNull("NuGet package… in the Add Reference popup", timeout = 30.seconds) {
            withContext(OnDispatcher.EDT) {
                utility(JBPopupFactoryRef::class).getInstance().getChildPopups(ideFrame().component).firstNotNullOfOrNull { popup ->
                    runCatching { cast(popup, ListPopupRef::class).getListStep().getValues() }.getOrNull()
                        ?.map { cast(it, ActionItemRef::class) }?.firstOrNull { it.getText().startsWith("NuGet") }?.let { popup to it.getAction() }
                }
            }
        }
        withContext(OnDispatcher.EDT) {
            nuget.first.cancel()
            val actions = service(com.intellij.driver.sdk.ActionManager::class)
            actions.tryToExecute(cast(nuget.second, com.intellij.driver.sdk.AnAction::class), null, panelUi.component, "CSharpReplScreenshots", false)
        }
        val dialog = ui.x(xQuery { byTitle("Reference NuGet Package") })
        waitFor("NuGet picker", timeout = 1.minutes) { dialog.present() }
        val lists = { dialog.xx { byType("com.intellij.ui.components.JBList") }.list() }
        waitFor("solution packages listed", timeout = 1.minutes) { lists().firstOrNull()?.let { size(it) } == 3 }
        withContext(OnDispatcher.EDT) { cast(dialog.x { byType("com.intellij.ui.components.JBTextField") }.component, JTextComponentRef::class).setText("serilog") }
        val searched = runCatching { waitFor("search results", timeout = 1.minutes) { lists().getOrNull(1)?.let { size(it) }?.let { it > 0 } == true } }.isSuccess
        if (searched) withContext(OnDispatcher.EDT) { cast(lists()[1].component, JListRef::class).setSelectedIndex(0) }
        else println("nuget.org search failed: the screenshot shows the solution's packages only")
        Thread.sleep(4000) // versions
        docShot("nuget-picker", rectOf(dialog.component))
        withContext(OnDispatcher.EDT) { utility(DialogWrapperRef::class).findInstance(dialog.component)?.doCancelAction() }
        waitFor("NuGet picker closed", timeout = 30.seconds) { !dialog.present() }

        // single-file-app: the session's export (what To Single-File App writes into a new scratch file) saved next to the
        // solution: Rider 2026.3 resolves file-based apps there, but shows a scratch file's symbols as unresolved.
        val project = singleProject()
        val app = Path.of(project.getBasePath()!!, "session.cs")
        val text = cast(session.export(app.toString(), false).get(), ExportResponseRef::class).getText()
        Files.writeString(app, text)
        withContext(OnDispatcher.EDT, LockSemantics.WRITE_ACTION) { // VFS refresh and opening need the write-intent lock
            val vf = utility(LocalFileSystemRef::class).getInstance().refreshAndFindFileByPath(app.toString().replace('\\', '/')) ?: fail("no VirtualFile for $app")
            utility(FileEditorManagerRef::class).getInstance(project).openFile(vf, true)
        }
        invokeAction("HideAllWindows")
        waitForIndicators(5.minutes) // Rider syncs a project for the file-based app, then highlights it
        Thread.sleep(10000)
        val exported = withContext(OnDispatcher.EDT) { utility(FileEditorManagerRef::class).getInstance(project).getSelectedTextEditor() } ?: fail("no editor")
        invokeAction("EditorEscape", component = exported.getContentComponent()) // an import hint at the caret
        Thread.sleep(1000)
        val area = rectOf(withContext(OnDispatcher.EDT) { exported.getComponent() })
        docShot("single-file-app", Rectangle(area.x, area.y, area.width, minOf(area.height, 330)))
    }

    /** Runs [code] (Enter in auto-run mode) and waits for its answer. */
    private fun Driver.run(panel: UiComponent, session: ReplSession, code: String) {
        // The history is changed on the EDT: read it there.
        val before = withContext(OnDispatcher.EDT) { session.getHistory().size }
        panel.enter(code)
        waitFor("`$code` ran", timeout = 3.minutes) {
            withContext(OnDispatcher.EDT) { session.getHistory().let { h -> h.size > before && h.none { it.getStatus() == "running" } } }
        }
    }

    private fun Driver.maximizeRepl(maximized: Boolean = true) {
        withContext(OnDispatcher.EDT) { utility(ToolWindowManagerRef::class).getInstance(singleProject()).setMaximized(toolWindow(), maximized) }
        Thread.sleep(1500)
    }

    private fun Driver.replArea(): Rectangle = rectOf(toolWindow().getComponent())

    /** Into docs/screenshots, cropped to [area], without the IDE's notification balloons. */
    private fun Driver.docShot(name: String, area: Rectangle) {
        expireNotifications(singleProject())
        Thread.sleep(1000)
        shot(name, area, docs)
    }

    private fun Driver.size(list: UiComponent) = withContext(OnDispatcher.EDT) { cast(list.component, JListModelRef::class).getModel().getSize() }

}
