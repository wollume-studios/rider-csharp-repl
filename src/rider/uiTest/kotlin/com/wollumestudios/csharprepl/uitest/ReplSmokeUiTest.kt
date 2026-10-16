package com.wollumestudios.csharprepl.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
import com.intellij.driver.sdk.ui.components.common.popups.searchEverywherePopup
import com.intellij.driver.sdk.ui.ui
import com.intellij.driver.sdk.ui.components.elements.JTableUiComponent
import com.intellij.driver.sdk.ui.components.elements.JTreeUiComponent
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.waitNotNull
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.ide.IDETestContext
import com.intellij.ide.starter.ide.installer.ExistingIdeInstaller
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.ProjectInfoSpec
import com.intellij.ide.starter.runner.Starter
import com.intellij.platform.testFramework.teamCity.TeamCityReporter
import com.intellij.tools.ide.starter.product.rider.Rider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes

/**
 * End-to-end smoke test in a real Rider: the built plugin with its host, on a fresh copy of samples/SampleSolution.
 * Everything goes through actions and IDE APIs (no OS mouse/keyboard), so it runs without focus.
 * Run: ./gradlew uiTest (Rider from CSHARPREPL_RIDER, else %LOCALAPPDATA%\Programs\Rider).
 */
class ReplSmokeUiTest {
    init {
        // IDE exceptions fail the test instead of going to TeamCity.
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String, message: String, details: String, linkToLogs: String?,
                        kind: TeamCityReporter.SyntheticTestKind, isIgnored: Boolean, metadata: List<TeamCityReporter.TestMetadata>,
                    ) = fail { "$testName fails: $message.\n$details" }
                }
            }
        }
    }

    @Test
    fun `runs code, shows variables and references a solution project`() = withRider("smoke") {
        openToolWindow("C# REPL")
        val panel = ideFrame().x { byType("com.wollumestudios.csharprepl.ui.ReplPanel") }
        val session = service(ReplSession::class, singleProject())

        // 1. Auto-run: Enter runs `var r = 0` (adding the ;), the variables table shows it.
        panel.enter("var r = 0")
        val variables = panel.x(JTableUiComponent::class.java) { byType("com.intellij.ui.treeStructure.treetable.TreeTable") }
        // The first host start and Roslyn's warmup take a few seconds.
        waitFor("r | 0 | int in the variables table", timeout = 2.minutes) {
            variables.content().values.any { row -> row.values.toList().let { it.size >= 3 && it.takeLast(2) == listOf("0", "int") && nameCell(it[0]) == "r" } }
                .also { if (!it) println("variables: ${variables.content()}; history: ${session.historyText()}") }
        }

        // 2. Solution Explorer › Greeter › Reference in C# REPL (Rider's project model gives the .csproj), then call into it.
        openToolWindow("Project")
        val tree = ideFrame().x(JTreeUiComponent::class.java) { byType("com.jetbrains.rider.projectView.views.impl.SolutionViewTree") }
        val greeter = waitNotNull("Greeter in Solution Explorer", timeout = 5.minutes) {
            val paths = tree.collectExpandedPaths()
            paths.firstOrNull { it.path.lastOrNull()?.startsWith("Greeter") == true }
                ?: null.also { println("Solution Explorer: $paths"); paths.filter { it.path.size == 1 }.forEach { tree.fixture.expandRow(it.row) } }
        }
        waitFor("Greeter selected", timeout = 1.minutes) {
            // Through the tree's API, not the fixture's robot click: that needs the test IDE in front (it fails while another window has focus).
            withContext(OnDispatcher.EDT) { cast(tree.component, JTree::class).setSelectionRow(greeter.row) }
            val selected = tree.collectSelectedPaths().map { it.path }
            println("Solution Explorer: row ${greeter.row} ${greeter.path}; selected: $selected")
            selected.singleOrNull()?.lastOrNull()?.startsWith("Greeter") == true
        }
        invokeAction("CSharpRepl.ReferenceInRepl", component = tree.component)
        waitFor("Greeter built and referenced", timeout = 5.minutes) {
            session.historyText().let { h -> "Referenced Greeter" in h || "Greeter not referenced" in h }
        }
        check("Referenced Greeter" in session.historyText()) { session.historyText() }

        panel.enter("Greeter.Hi(\"x\")")
        waitFor("\"Hi x\" in the history", timeout = 1.minutes) { session.getHistory().any { it.getResult() == "\"Hi x\"" } }
    }

    /** Types [code] into the REPL input and presses Enter (the action the input binds to Enter). */
    private fun UiComponent.enter(code: String) {
        val panel = driver.cast(component, ReplPanel::class)
        driver.withWriteAction { panel.getInput().setText(code) }
        driver.invokeAction("CSharpRepl.Enter", component = component)
    }

    /** The Name cell's text without the tree's expander (anything before the name, e.g. an arrow or +/-). */
    private fun nameCell(text: String) = text.trim().replace(Regex("""^[^\p{L}\p{N}_$]+"""), "").trim()

    private fun ReplSession.historyText() =
        getHistory().joinToString("\n") { "[${it.getStatus()}] ${it.getCode()} ${it.getOutput()} ${it.getResult() ?: ""} ${it.getException() ?: ""}" }

    /**
     * The window was open when the IDE closed, so the next start restores it visible (workspace window_info
     * visible="true") and creates its content while the project is still opening.
     */
    @Test
    fun `window restored visible at startup shows toolbar, input and variables`() {
        val context = riderContext("restore")
        context.runRider {
            openToolWindow("C# REPL")
            checkReplWindow("repl-first-open")
        }
        context.runRider {
            checkReplWindow("repl-restored")
            // Hidden and shown again (the stripe button's toggle): still the same content.
            val window = toolWindow()
            withContext(OnDispatcher.EDT) { window.hide() }
            withContext(OnDispatcher.EDT) { window.show() }
            checkReplWindow("repl-reshown")
        }
    }

    /**
     * The user's path (actionSummary.xml: CSharpRepl.Toolbar, then ActivateC#REPLToolWindow): Find Action "C# REPL",
     * choose the toolbar group (also called "C# REPL") before the window was ever opened, then open the window.
     * Choosing the group shows its actions in a popup, and their update() asked for the window's content manager, which
     * created the content there: a cancelled update left the window empty ("Nothing to show") for the rest of the session.
     */
    @Test
    fun `window opened after choosing the C# REPL group in Find Action`() = withRider("findaction") {
        invokeAction("GotoAction", now = false)
        val popup = ui.searchEverywherePopup()
        withContext(OnDispatcher.EDT) { cast(popup.searchField.component, JTextComponentRef::class).setText("C# REPL") }
        // The group's row: text "C# REPL" without the tool window's "Activate C# REPL window" tooltip.
        val group = waitNotNull("C# REPL group listed", timeout = 1.minutes) {
            popup.resultsList.rawItems.map { it.toString() }.indexOfFirst { "text=C# REPL," in it && "toolTip=null" in it }.takeIf { it >= 0 }
        }
        withContext(OnDispatcher.EDT) {
            cast(popup.resultsList.component, JListRef::class).setSelectedIndex(group)
            val field = popup.searchField.component
            cast(field, AwtComponentRef::class).dispatchEvent(new(KeyEventRef::class, field, 401 /* KEY_PRESSED */, System.currentTimeMillis(), 0, 10 /* VK_ENTER */, '\n'))
        }
        Thread.sleep(5000) // the group's popup and its actions' updates
        val created = withContext(OnDispatcher.EDT) { toolWindow().getContentManagerIfCreated()?.getContentCount() }
        check(created == null) { "action updates created the REPL window's content ($created contents) before it was opened" }
        withContext(OnDispatcher.EDT) { // the group's popup: left open, it keeps the IDE from exiting
            utility(JBPopupFactoryRef::class).getInstance().getChildPopups(ideFrame().component).forEach { it.cancel() }
        }
        openToolWindow("C# REPL")
        checkReplWindow("repl-after-find-action")
    }

    /** Dumps and screenshots the REPL window, then checks what is painted: the toolbar's buttons, the input, the variables table. */
    private fun Driver.checkReplWindow(name: String) {
        Thread.sleep(3000) // layout and the toolbar's first update
        val window = toolWindow()
        val dump = withContext(OnDispatcher.EDT) {
            buildString {
                appendLine("visible=${window.isVisible()} contents=${window.getContentManager().getContentCount()}")
                dump(window.getComponent(), 0, this)
            }
        }
        Files.createDirectories(screenshots)
        Files.writeString(screenshots.resolve("$name.txt"), dump)
        println("$name:\n$dump")
        shot(name)
        check(window.getContentManager().getContentCount() == 1) { "$name: no REPL content\n$dump" }
        val panel = ideFrame().x { byType("com.wollumestudios.csharprepl.ui.ReplPanel") }
        val input = cast(cast(panel.component, ReplPanel::class).getInput(), com.intellij.driver.sdk.ui.remote.Component::class)
        check(input.isShowing() && input.width > 0 && input.height > 0) { "$name: input not painted\n$dump" }
        val buttons = panel.xx { byType("com.intellij.openapi.actionSystem.impl.ActionButton") }.list().filter { it.component.isShowing() && it.component.width > 0 }
        check(buttons.size >= 3) { "$name: toolbar has ${buttons.size} visible buttons\n$dump" }
        val table = panel.x { byType("com.intellij.ui.treeStructure.treetable.TreeTable") }.component
        check(table.isShowing() && table.width > 0) { "$name: variables table not painted\n$dump" }
    }

    private fun Driver.toolWindow(): ToolWindowRef =
        utility(ToolWindowManagerRef::class).getInstance(singleProject()).getToolWindow("C# REPL") ?: fail("No C# REPL tool window")

    private fun Driver.dump(c: com.intellij.driver.sdk.ui.remote.Component, depth: Int, out: StringBuilder) {
        out.append("  ".repeat(depth)).append(c.getClass().getName()).append(" [${c.x},${c.y} ${c.width}x${c.height}]")
            .append(if (c.isShowing()) " showing" else " NOT showing").append('\n')
        val container = cast(c, AwtContainer::class)
        if (depth < 12 && !c.getClass().getName().startsWith("com.intellij.openapi.editor.impl.")) // editors: deep and not ours
            for (i in 0 until container.getComponentCount()) dump(container.getComponent(i), depth + 1, out)
    }

    /**
     * The IDE frame as Swing paints it, rendered in the IDE into an image: unlike a Robot screen capture it works while
     * the test IDE is behind other windows or on another monitor.
     */
    private fun Driver.shot(name: String) {
        val frame = ideFrame().component
        Files.createDirectories(screenshots)
        val file = screenshots.resolve("$name.png").toAbsolutePath().toString()
        withContext(OnDispatcher.EDT) {
            val image = new(BufferedImageRef::class, frame.width, frame.height, 1 /* TYPE_INT_RGB */)
            val g = image.createGraphics()
            cast(frame, AwtComponentRef::class).paintAll(g)
            g.dispose()
            utility(ImageIORef::class).write(image, "png", new(FileRef::class, file))
        }
        println("Screenshot $name: $file")
    }

    private val screenshots: Path = Path.of(System.getProperty("csharprepl.screenshots", "build/screenshots"))

    private fun withRider(name: String, test: Driver.() -> Unit) = riderContext(name).runRider(test)

    private fun riderContext(name: String): IDETestContext {
        val solution = sampleCopy().resolve("SampleSolution.sln")
        val context = Starter.newContext(name, TestCase(rider(), SolutionProject(solution)))
        PluginConfigurator(context).installPluginFromDir(Path.of(System.getProperty("csharprepl.plugin")))
        // Fresh temp copy of the sample each run: without this, Rider asks to trust it (a dialog on the user's screen).
        context.applyVMOptionsPatch { addSystemProperty("idea.trust.all.projects", true) }
        return context
    }

    private fun IDETestContext.runRider(test: Driver.() -> Unit) {
        // A cold Rider start (first run, busy machine) alone can take ~10 minutes.
        runIdeWithDriver(runTimeout = 45.minutes).useDriverAndCloseIde {
            waitForIndicators(30.minutes)
            test()
        }
    }

    private fun rider(): IdeInfo {
        val path = System.getProperty("csharprepl.rider")?.let(Path::of)
            ?: System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Programs", "Rider") }
            ?: fail("Set CSHARPREPL_RIDER to a Rider installation")
        if (!path.exists()) fail("No Rider at $path; set CSHARPREPL_RIDER")
        return IdeInfo.Rider.copy(getInstaller = { ExistingIdeInstaller(path) })
    }

    /** The sample without build output, so every run starts from the same sources and the repo stays clean. */
    private fun sampleCopy(): Path {
        val source = Path.of(System.getProperty("csharprepl.sample"))
        val target = createTempDirectory("csharprepl-ui-").resolve(source.name)
        Files.walk(source).use { paths ->
            paths.filter { p -> source.relativize(p).none { it.name in setOf("bin", "obj", ".idea") } }.forEach { p ->
                val dst = target.resolve(source.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(dst) else Files.copy(p, dst)
            }
        }
        return target
    }
}

/** Opens a solution file: given its folder, Rider asks which solution to open (a modal dialog the test can't answer). */
private class SolutionProject(private val solution: Path) : ProjectInfoSpec {
    override val isReusable = true
    override val downloadTimeout = 1.minutes
    override val configureProjectBeforeUse: (IDETestContext) -> Unit = {}
    override fun downloadAndUnpackProject(): Path = solution
}

private const val PLUGIN = "com.wollumestudios.csharprepl"

@Remote("com.wollumestudios.csharprepl.ui.ReplPanel", plugin = PLUGIN)
interface ReplPanel {
    fun getInput(): EditorTextField
}

@Remote("com.intellij.openapi.wm.ToolWindowManager")
interface ToolWindowManagerRef {
    fun getInstance(project: com.intellij.driver.sdk.Project): ToolWindowManagerRef
    fun getToolWindow(id: String): ToolWindowRef?
}

@Remote("com.intellij.openapi.wm.ToolWindow")
interface ToolWindowRef {
    fun isVisible(): Boolean
    fun show()
    fun hide()
    fun getComponent(): com.intellij.driver.sdk.ui.remote.Component
    fun getContentManager(): ContentManagerRef
    fun getContentManagerIfCreated(): ContentManagerRef?
}

@Remote("com.intellij.ui.content.ContentManager")
interface ContentManagerRef {
    fun getContentCount(): Int
}

@Remote("java.awt.Container")
interface AwtContainer {
    fun getComponentCount(): Int
    fun getComponent(n: Int): com.intellij.driver.sdk.ui.remote.Component
}

@Remote("java.awt.image.BufferedImage")
interface BufferedImageRef {
    fun createGraphics(): GraphicsRef
}

@Remote("java.awt.Graphics2D")
interface GraphicsRef {
    fun dispose()
}

@Remote("java.awt.Component")
interface AwtComponentRef {
    fun paintAll(g: GraphicsRef)
    fun dispatchEvent(e: KeyEventRef)
}

@Remote("javax.imageio.ImageIO")
interface ImageIORef {
    fun write(image: BufferedImageRef, format: String, output: FileRef): Boolean
}

@Remote("javax.swing.text.JTextComponent")
interface JTextComponentRef {
    fun setText(text: String)
}

@Remote("com.intellij.openapi.ui.popup.JBPopupFactory")
interface JBPopupFactoryRef {
    fun getInstance(): JBPopupFactoryRef
    fun getChildPopups(parent: com.intellij.driver.sdk.ui.remote.Component): List<JBPopupRef>
}

@Remote("com.intellij.openapi.ui.popup.JBPopup")
interface JBPopupRef {
    fun cancel()
}

@Remote("javax.swing.JList")
interface JListRef {
    fun setSelectedIndex(index: Int)
}

@Remote("java.awt.event.KeyEvent")
interface KeyEventRef

@Remote("java.io.File")
interface FileRef

@Remote("javax.swing.JTree")
interface JTree {
    fun setSelectionRow(row: Int)
}

@Remote("com.intellij.ui.EditorTextField")
interface EditorTextField {
    fun setText(text: String)
}

@Remote("com.wollumestudios.csharprepl.ReplSession", plugin = PLUGIN)
interface ReplSession {
    fun getHistory(): List<HistoryEntry>
}

@Remote("com.wollumestudios.csharprepl.HistoryEntry", plugin = PLUGIN)
interface HistoryEntry {
    fun getCode(): String
    fun getStatus(): String
    fun getOutput(): String
    fun getResult(): String?
    fun getException(): String?
}
