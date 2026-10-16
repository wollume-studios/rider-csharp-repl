package com.wollumestudios.csharprepl.uitest

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openToolWindow
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.ui.components.UiComponent
import com.intellij.driver.sdk.ui.components.common.ideFrame
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
            tree.fixture.selectRow(greeter.row)
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

    private fun withRider(name: String, test: Driver.() -> Unit) {
        val solution = sampleCopy().resolve("SampleSolution.sln")
        val context = Starter.newContext(name, TestCase(rider(), SolutionProject(solution)))
        PluginConfigurator(context).installPluginFromDir(Path.of(System.getProperty("csharprepl.plugin")))
        // Fresh temp copy of the sample each run: without this, Rider asks to trust it (a dialog on the user's screen).
        context.applyVMOptionsPatch { addSystemProperty("idea.trust.all.projects", true) }
        // A cold Rider start (first run, busy machine) alone can take ~10 minutes.
        context.runIdeWithDriver(runTimeout = 45.minutes).useDriverAndCloseIde {
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
