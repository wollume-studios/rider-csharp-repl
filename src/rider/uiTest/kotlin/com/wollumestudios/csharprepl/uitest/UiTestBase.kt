package com.wollumestudios.csharprepl.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.model.LockSemantics
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
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A real Rider with the built plugin on a fresh copy of samples/SampleSolution ([withRider]) and the helpers the UI tests
 * share. Everything goes through actions and IDE APIs (no OS mouse/keyboard), so the tests run without focus.
 */
abstract class UiTestBase {
    init {
        // IDE exceptions fail the test instead of going to TeamCity.
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String, message: String, details: String, linkToLogs: String?,
                        kind: TeamCityReporter.SyntheticTestKind, isIgnored: Boolean, metadata: List<TeamCityReporter.TestMetadata>,
                    ) {
                        // Rider's own ReSharper backend errors (this plugin has no backend), e.g. a project reload racing a file-based app being opened.
                        if ("BackendException" in message + details && "com.wollumestudios" !in details) return println("Ignored Rider backend error in $testName: $message")
                        fail { "$testName fails: $message.\n$details" }
                    }
                }
            }
        }
    }

    /** Opens Solution Explorer and selects the Greeter project node (through the tree's API: robot clicks need the IDE in front). */
    protected fun Driver.selectGreeter(): JTreeUiComponent {
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
        return tree
    }

    /** Types [code] into the REPL input and presses Enter (the action the input binds to Enter). */
    protected fun UiComponent.enter(code: String) {
        val panel = driver.cast(component, ReplPanel::class)
        driver.withWriteAction { panel.getInput().setText(code) }
        driver.invokeAction("CSharpRepl.Enter", component = component)
    }

    /** The Name cell's text without the tree's expander (anything before the name, e.g. an arrow or +/-). */
    protected fun nameCell(text: String) = text.trim().replace(Regex("""^[^\p{L}\p{N}_$]+"""), "").trim()

    protected fun ReplSession.historyText() =
        getHistory().joinToString("\n") { "[${it.getStatus()}] ${it.getCode()} ${it.getOutput()} ${it.getResult() ?: ""} ${it.getException() ?: ""}" }


    /**
     * The IDE frame as Swing paints it, rendered in the IDE into an image: unlike a Robot screen capture it works while
     * the test IDE is behind other windows or on another monitor.
     */
    protected fun Driver.shot(name: String, area: Rectangle? = null, dir: Path = screenshots) {
        val frame = ideFrame().component
        Files.createDirectories(dir)
        val file = dir.resolve("$name.png").toAbsolutePath().toString()
        withContext(OnDispatcher.EDT) {
            val image = new(BufferedImageRef::class, frame.width, frame.height, 1 /* TYPE_INT_RGB */)
            val g = image.createGraphics()
            cast(frame, AwtComponentRef::class).paintAll(g)
            // Popups (completion lookup, menus) that left the frame's layered pane are windows of their own.
            val origin = cast(frame, AwtComponentRef::class).getLocationOnScreen()
            for (w in cast(frame, AwtWindowRef::class).getOwnedWindows()) {
                val window = cast(w, AwtComponentRef::class)
                if (!w.isShowing() || w.width <= 0) continue
                val at = window.getLocationOnScreen()
                val wg = g.create(at.x - origin.x, at.y - origin.y, w.width, w.height)
                window.paintAll(wg)
                wg.dispose()
            }
            g.dispose()
            val crop = area?.intersection(Rectangle(0, 0, frame.width, frame.height))
            utility(ImageIORef::class).write(crop?.let { image.getSubimage(it.x, it.y, it.width, it.height) } ?: image, "png", new(FileRef::class, file))
        }
        println("Screenshot $name: $file")
    }

    /** Types [text] into [editor] character by character through the editor's typing action (as the keyboard does: typed handlers, lookup). */
    protected fun Driver.type(editor: EditorRef, text: String) {
        for (c in text) withContext(OnDispatcher.EDT) {
            val context = utility(DataManagerRef::class).getInstance().getDataContext(editor.getContentComponent())
            utility(TypedActionRef::class).getInstance().actionPerformed(editor, c, context)
        }
    }

    protected fun Driver.toolWindow(): ToolWindowRef =
        utility(ToolWindowManagerRef::class).getInstance(singleProject()).getToolWindow("C# REPL") ?: fail("No C# REPL tool window")

    /** [c]'s bounds in the IDE frame's coordinates (what [shot] crops to); popups are windows of their own, so this works for them too. */
    protected fun Driver.rectOf(c: com.intellij.driver.sdk.ui.remote.Component): Rectangle {
        val origin = cast(ideFrame().component, AwtComponentRef::class).getLocationOnScreen()
        val at = cast(c, AwtComponentRef::class).getLocationOnScreen()
        return Rectangle(at.x - origin.x, at.y - origin.y, c.width, c.height)
    }

    protected val screenshots: Path = Path.of(System.getProperty("csharprepl.screenshots", "build/screenshots"))

    /** [prepare] changes the sample copy before Rider opens it. */
    protected fun withRider(name: String, prepare: (Path) -> Unit = {}, test: Driver.() -> Unit) = riderContext(name, prepare).runRider(test)

    protected fun riderContext(name: String, prepare: (Path) -> Unit = {}): IDETestContext {
        val solution = sampleCopy().also(prepare).resolve("SampleSolution.sln")
        val context = Starter.newContext(name, TestCase(rider(), SolutionProject(solution)))
        PluginConfigurator(context).installPluginFromDir(Path.of(System.getProperty("csharprepl.plugin")))
        // Fresh temp copy of the sample each run: without this, Rider asks to trust it (a dialog on the user's screen).
        context.applyVMOptionsPatch { addSystemProperty("idea.trust.all.projects", true) }
        return context
    }

    protected fun IDETestContext.runRider(test: Driver.() -> Unit) {
        // A cold Rider start (first run, busy machine) alone can take ~10 minutes.
        runIdeWithDriver(runTimeout = 45.minutes).useDriverAndCloseIde {
            waitForIndicators(30.minutes)
            test()
        }
    }

    protected fun rider(): IdeInfo {
        val path = System.getProperty("csharprepl.rider")?.let(Path::of)
            ?: System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Programs", "Rider") }
            ?: fail("Set CSHARPREPL_RIDER to a Rider installation")
        if (!path.exists()) fail("No Rider at $path; set CSHARPREPL_RIDER")
        return IdeInfo.Rider.copy(getInstaller = { ExistingIdeInstaller(path) })
    }

    /** The sample without build output, so every run starts from the same sources and the repo stays clean. */
    protected fun sampleCopy(): Path {
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
