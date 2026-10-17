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
import kotlin.time.Duration.Companion.seconds

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
        val tree = selectGreeter()
        invokeAction("CSharpRepl.ReferenceInRepl", component = tree.component)
        waitFor("Greeter built and referenced", timeout = 5.minutes) {
            session.historyText().let { h -> "Referenced Greeter" in h || "Greeter not referenced" in h }
        }
        check("Referenced Greeter" in session.historyText()) { session.historyText() }

        panel.enter("Greeter.Hi(\"x\")")
        waitFor("\"Hi x\" in the history", timeout = 1.minutes) { session.getHistory().any { it.getResult() == "\"Hi x\"" } }
    }

    /**
     * Completion while typing: after `var abc = 1;`, typing `ab` pops up a lookup with `abc`; Enter (CSharpRepl.Enter) is
     * disabled while it shows, the lookup's Enter inserts the item, Escape closes it without clearing the input.
     * Then highlighting: the input's lexer and the history's markup color a keyword, a string and a comment.
     */
    @Test
    fun `completes while typing and highlights C# in the input and the history`() = withRider("completion") {
        openToolWindow("C# REPL")
        val panelUi = ideFrame().x { byType("com.wollumestudios.csharprepl.ui.ReplPanel") }
        val panel = cast(panelUi.component, ReplPanel::class)
        val session = service(ReplSession::class, singleProject())
        panelUi.enter("var abc = 1;")
        waitFor("var abc = 1; ran", timeout = 2.minutes) { session.getHistory().any { it.getCode() == "var abc = 1;" && it.getStatus() == "ok" } }

        val editor = withContext(OnDispatcher.EDT) { panel.getInput().getEditor() } ?: fail("input has no editor")
        fun activeLookup() = withContext(OnDispatcher.EDT) { utility(LookupManagerRef::class).getActiveLookup(editor) }
        fun inputText() = withContext(OnDispatcher.EDT) { panel.getInput().getText() }
        type(editor, "ab")
        val lookup = waitNotNull("completion lookup", timeout = 1.minutes) { activeLookup() }
        val items = withContext(OnDispatcher.EDT) { lookup.getItems().map { it.getLookupString() } }
        println("lookup items: $items")
        check("abc" in items) { "abc not in the lookup: $items" }
        shot("repl-completion")

        val runs = session.getHistory().size
        check(!tryAction("CSharpRepl.Enter", editor.getContentComponent())) { "CSharpRepl.Enter enabled while the lookup shows" }
        check(!tryAction("CSharpRepl.ClearInput", editor.getContentComponent())) { "CSharpRepl.ClearInput enabled while the lookup shows" }
        Thread.sleep(1000)
        check(session.getHistory().size == runs) { "Enter ran code while the lookup showed: ${session.historyText()}" }
        check(inputText() == "ab") { "input changed while the lookup showed: ${inputText()}" }
        invokeAction("EditorChooseLookupItem", component = editor.getContentComponent()) // the lookup's Enter
        waitFor("abc inserted", timeout = 30.seconds) { inputText() == "abc" }
        check(activeLookup() == null) { "lookup still open after choosing" }

        type(editor, ".")
        waitNotNull("member lookup after abc.", timeout = 1.minutes) { activeLookup() }
        invokeAction("EditorEscape", component = editor.getContentComponent())
        waitFor("lookup closed by Escape", timeout = 30.seconds) { activeLookup() == null }
        check(inputText() == "abc.") { "Escape changed the input: ${inputText()}" }

        // Highlighting: run two commands, then leave the second one in the input too.
        panelUi.enter("int.Parse(\"1\")")
        waitFor("int.Parse ran", timeout = 1.minutes) { session.getHistory().any { it.getResult() == "1" } }
        val code = "var s = \"hi\"; // c"
        panelUi.enter(code)
        waitFor("var s ran", timeout = 1.minutes) { session.getHistory().any { it.getCode() == code && it.getStatus() == "ok" } }
        withWriteAction { panel.getInput().setText(code) }
        Thread.sleep(1500) // repaint
        shot("repl-highlighting")

        val inputColors = withContext(OnDispatcher.EDT) {
            val default = editor.getColorsScheme().getDefaultForeground().getRGB()
            val colors = mutableMapOf<Int, String>()
            val it = editor.getHighlighter().createIterator(0)
            while (!it.atEnd()) {
                it.getTextAttributes()?.getForegroundColor()?.getRGB()?.takeIf { c -> c != default }?.let { c -> colors.putIfAbsent(c, code.substring(it.getStart(), it.getEnd())) }
                it.advance()
            }
            colors
        }
        println("input colors: ${inputColors.mapKeys { Integer.toHexString(it.key) }}")
        check(inputColors.size >= 3) { "input: ${inputColors.size} non-default colors for `$code`: $inputColors" }

        val historyColors = withContext(OnDispatcher.EDT) {
            val history = panel.getHistory()
            val text = history.getDocument().getText()
            val start = text.indexOf(code)
            val default = history.getColorsScheme().getDefaultForeground().getRGB()
            history.getMarkupModel().getAllHighlighters()
                .filter { h -> h.getStartOffset() >= start && h.getEndOffset() <= start + code.length }
                .mapNotNull { h -> h.getTextAttributes(history.getColorsScheme())?.getForegroundColor()?.getRGB()?.takeIf { c -> c != default }?.let { c -> c to text.substring(h.getStartOffset(), h.getEndOffset()) } }
                .toMap()
        }
        println("history colors: ${historyColors.mapKeys { Integer.toHexString(it.key) }}")
        check(historyColors.size >= 3) { "history: ${historyColors.size} non-default colors for `$code`: $historyColors" }
    }

    /** Runs the action like a keystroke would (update, then perform if enabled); false when it was disabled. */
    private fun Driver.tryAction(id: String, component: com.intellij.driver.sdk.ui.remote.Component): Boolean = withContext(OnDispatcher.EDT) {
        val actions = service(com.intellij.driver.sdk.ActionManager::class)
        !actions.tryToExecute(actions.getAction(id) ?: fail("no action $id"), null, component, null, true).isRejected()
    }

    /** Types [text] into [editor] character by character through the editor's typing action (as the keyboard does: typed handlers, lookup). */
    private fun Driver.type(editor: EditorRef, text: String) {
        for (c in text) withContext(OnDispatcher.EDT) {
            val context = utility(DataManagerRef::class).getInstance().getDataContext(editor.getContentComponent())
            utility(TypedActionRef::class).getInstance().actionPerformed(editor, c, context)
        }
    }

    /**
     * Single-file apps, as far as the real paths go without an OS mouse:
     * a) Solution Explorer's own drag object for the Greeter node (RiderDragSource → BufferedAction, wrapped in the
     *    DnDEventImpl Transferable as DnDManagerImpl does) dropped onto the REPL (its drop handler) and onto a single-file
     *    app's editor (FileDropManager, the editors' drop path);
     * b) the toolbar's "To Single-File App ▾" split button: its popup lists both variants, each exports into a new scratch
     *    file that `dotnet run` runs;
     * c) what Rider offers to run such a file: gutter icons and run configurations from the context.
     */
    @Test
    fun `single-file apps - project drop, export split button, run`() = withRider("singlefile") {
        openToolWindow("C# REPL")
        val panelUi = ideFrame().x { byType("com.wollumestudios.csharprepl.ui.ReplPanel") }
        val session = service(ReplSession::class, singleProject())
        val project = singleProject()

        // a) Solution Explorer's drag object for the Greeter node.
        selectGreeter()
        val dragged = withContext(OnDispatcher.EDT) {
            val pane = utility(ProjectViewRef::class).getInstance(project).getCurrentProjectViewPane()
            val copy = utility(DnDActionRef::class).valueOf("COPY")
            val bean = new(RiderDragSourceRef::class, pane).startDragging(copy, java.awt.Point(10, 10))
            new(DnDEventImplRef::class, utility(DnDManagerRef::class).getInstance(), copy, bean.getAttachedObject(), java.awt.Point(10, 10))
        }
        val files = withContext(OnDispatcher.EDT) { utility(FileCopyPasteUtilRef::class).getFileList(dragged)?.map { it.getPath() } }
        println("Solution Explorer drag object: files=$files")
        check(files?.singleOrNull()?.endsWith("Greeter.csproj") == true) { "the drag object's files: $files" }
        withContext(OnDispatcher.EDT) { cast(panelUi.component, ReplPanel::class).getDrop().handleDrop(dragged, project, null) }
        waitFor("Greeter referenced by the drop", timeout = 5.minutes) { session.historyText().let { "Referenced Greeter" in it || "not referenced" in it } }
        check("Referenced Greeter" in session.historyText()) { session.historyText() }

        val app = Path.of(project.getBasePath()!!, "app.cs")
        Files.writeString(app, "#!/usr/bin/env dotnet\n\nConsole.WriteLine(Greeter.Hi(\"app\"));\n")
        val appEditor = withContext(OnDispatcher.EDT) {
            val vf = utility(LocalFileSystemRef::class).getInstance().refreshAndFindFileByPath(app.toString().replace('\\', '/')) ?: fail("no VirtualFile for $app")
            utility(FileEditorManagerRef::class).getInstance(project).openFile(vf, true)
            utility(FileEditorManagerRef::class).getInstance(project).getSelectedTextEditor() ?: fail("no editor for $app")
        }
        withContext(OnDispatcher.EDT) { service(FileDropManagerRef::class, project).scheduleDrop(dragged, appEditor, null) }
        waitFor("#:project directive in app.cs", timeout = 1.minutes) { "#:project Greeter/Greeter.csproj" in withContext(OnDispatcher.EDT) { appEditor.getDocument().getText() } }
        println("app.cs after the drop:\n" + withContext(OnDispatcher.EDT) { appEditor.getDocument().getText() })

        // b) The split button's popup, then both variants.
        panelUi.enter("var r = 41;")
        panelUi.enter("Greeter.Hi(\"x\") + (r + 1)")
        panelUi.enter("int.Parse(\"x\")")
        waitFor("three commands ran", timeout = 2.minutes) { session.getHistory().count { it.getStatus() in setOf("ok", "threw") } >= 3 }
        val split = panelUi.x { byType("com.intellij.openapi.actionSystem.SplitButtonAction\$SplitButton") }.component
        withContext(OnDispatcher.EDT) { // a click on the arrow half, as mouse events (no OS mouse)
            val c = cast(split, AwtComponentRef::class)
            for (id in listOf(501 /* MOUSE_PRESSED */, 502 /* MOUSE_RELEASED */))
                c.dispatchEvent(new(MouseEventRef::class, split, id, System.currentTimeMillis(), if (id == 501) 1024 else 0, split.width - 3, split.height / 2, 1, false, 1))
        }
        val variants = waitNotNull("the split button's popup", timeout = 30.seconds) {
            ui.xx { byType("com.intellij.openapi.actionSystem.impl.ActionMenuItem") }.list().map { cast(it.component, AbstractButtonRef::class).getText() }.takeIf { it.isNotEmpty() }
        }
        println("split button popup: $variants")
        shot("repl-export-split-button")
        withContext(OnDispatcher.EDT) { utility(MenuSelectionManagerRef::class).defaultManager().clearSelectedPath() }
        check(variants.any { it == "To Single-File App" } && variants.any { "Exclude Commands That Threw" in it }) { "split button popup: $variants" }

        fun export(actionId: String): Path {
            val before = withContext(OnDispatcher.EDT) { utility(FileEditorManagerRef::class).getInstance(project).getSelectedFiles().map { it.getPath() } }
            invokeAction(actionId, component = panelUi.component)
            val path = waitNotNull("$actionId opened a new scratch file", timeout = 1.minutes) {
                withContext(OnDispatcher.EDT) { utility(FileEditorManagerRef::class).getInstance(project).getSelectedFiles().map { it.getPath() } }
                    .firstOrNull { it !in before && Path.of(it).name.startsWith("session-") && it.endsWith(".cs") }
            }
            check(Files.exists(Path.of(path))) { "$path does not exist" }
            println("$actionId → $path:\n${Files.readString(Path.of(path))}")
            return Path.of(path)
        }
        val all = export("CSharpRepl.ExportToSingleFile")
        val ok = export("CSharpRepl.ExportToSingleFileExcludeThrown")
        check("int.Parse(\"x\")" in Files.readString(all) && "int.Parse(\"x\")" !in Files.readString(ok)) { "exclude-thrown variant still has the throwing command" }
        val (okCode, okOut) = dotnetRun(ok)
        println("dotnet run ${ok.name}: exit $okCode\n$okOut")
        check(okCode == 0 && "Hi x42" in okOut) { "dotnet run ${ok.name}: exit $okCode\n$okOut" }
        val (allCode, allOut) = dotnetRun(all)
        println("dotnet run ${all.name}: exit $allCode (the session's last command threw)\n$allOut")
        check("Hi x42" in allOut && "FormatException" in allOut) { "dotnet run ${all.name}: exit $allCode\n$allOut" }

        // c) What Rider offers to run the exported file: gutter icons on its first lines, and run configurations from the context.
        val exported = withContext(OnDispatcher.EDT) { utility(FileEditorManagerRef::class).getInstance(project).getSelectedTextEditor() ?: fail("no editor") }
        Thread.sleep(15000) // daemon / backend markup
        val gutter = withContext(OnDispatcher.EDT) {
            val g = cast(exported.getGutter(), GutterRef::class)
            (0 until 3).flatMap { line -> g.getGutterRenderers(line).map { "line $line: ${it.getTooltipText()} (${it.toString()})" } }
        }
        val configurations = withContext(OnDispatcher.EDT) {
            val context = utility(ConfigurationContextRef::class).getFromContext(utility(DataManagerRef::class).getInstance().getDataContext(exported.getContentComponent()), "EditorPopup")
            context.getConfigurationsFromContext()?.map { it.getConfiguration().getName() + " [" + it.getConfiguration().getType().getId() + "]" }.orEmpty()
        }
        println("exported file gutter: $gutter\nrun configurations from context: $configurations")
        Files.writeString(screenshots.resolve("singlefile-run-check.txt"), "gutter: $gutter\nconfigurations: $configurations\n")
        shot("single-file-gutter")
    }

    /** `dotnet run <file>` in the file's folder: exit code and output (stdout and stderr). */
    private fun dotnetRun(file: Path): Pair<Int, String> {
        val process = ProcessBuilder("dotnet", "run", file.toString()).directory(file.parent.toFile()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }

    /** Opens Solution Explorer and selects the Greeter project node (through the tree's API: robot clicks need the IDE in front). */
    private fun Driver.selectGreeter(): JTreeUiComponent {
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
        check(input.isShowing() && input.width > 0 && input.height >= 40) { "$name: input not painted or too small (${input.height}px)\n$dump" }
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
    fun getHistory(): EditorRef
    fun getDrop(): ReferenceDropRef
}

@Remote("com.intellij.openapi.editor.ex.EditorEx")
interface EditorRef {
    fun getHighlighter(): EditorHighlighterRef
    fun getMarkupModel(): MarkupModelRef
    fun getColorsScheme(): ColorsSchemeRef
    fun getContentComponent(): com.intellij.driver.sdk.ui.remote.Component
    fun getDocument(): DocumentRef
    fun getGutter(): GutterRef
}

@Remote("com.intellij.openapi.editor.Document")
interface DocumentRef {
    fun getText(): String
}

@Remote("com.intellij.openapi.editor.highlighter.EditorHighlighter")
interface EditorHighlighterRef {
    fun createIterator(start: Int): HighlighterIteratorRef
}

@Remote("com.intellij.openapi.editor.highlighter.HighlighterIterator")
interface HighlighterIteratorRef {
    fun atEnd(): Boolean
    fun advance()
    fun getStart(): Int
    fun getEnd(): Int
    fun getTextAttributes(): TextAttributesRef?
}

@Remote("com.intellij.openapi.editor.markup.TextAttributes")
interface TextAttributesRef {
    fun getForegroundColor(): ColorRef?
}

@Remote("java.awt.Color")
interface ColorRef {
    fun getRGB(): Int
}

@Remote("com.intellij.openapi.editor.colors.EditorColorsScheme")
interface ColorsSchemeRef {
    fun getDefaultForeground(): ColorRef
}

@Remote("com.intellij.openapi.editor.markup.MarkupModel")
interface MarkupModelRef {
    fun getAllHighlighters(): Array<RangeHighlighterRef>
}

@Remote("com.intellij.openapi.editor.markup.RangeHighlighter")
interface RangeHighlighterRef {
    fun getStartOffset(): Int
    fun getEndOffset(): Int
    fun getTextAttributes(scheme: ColorsSchemeRef?): TextAttributesRef?
}

@Remote("com.intellij.codeInsight.lookup.LookupManager")
interface LookupManagerRef {
    fun getActiveLookup(editor: EditorRef): LookupRef?
}

@Remote("com.intellij.codeInsight.lookup.Lookup")
interface LookupRef {
    fun getItems(): List<LookupElementRef>
}

@Remote("com.intellij.codeInsight.lookup.LookupElement")
interface LookupElementRef {
    fun getLookupString(): String
}

@Remote("com.intellij.ide.DataManager")
interface DataManagerRef {
    fun getInstance(): DataManagerRef
    fun getDataContext(component: com.intellij.driver.sdk.ui.remote.Component): DataContextRef
}

@Remote("com.intellij.openapi.actionSystem.DataContext")
interface DataContextRef

@Remote("com.intellij.openapi.editor.actionSystem.TypedAction")
interface TypedActionRef {
    fun getInstance(): TypedActionRef
    fun actionPerformed(editor: EditorRef, charTyped: Char, dataContext: DataContextRef)
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

@Remote("java.awt.Graphics")
interface GraphicsRef {
    fun dispose()
    fun create(x: Int, y: Int, width: Int, height: Int): GraphicsRef
}

@Remote("java.awt.Component")
interface AwtComponentRef {
    fun paintAll(g: GraphicsRef)
    fun dispatchEvent(e: KeyEventRef)
    fun dispatchEvent(e: MouseEventRef)
    fun getLocationOnScreen(): java.awt.Point
}

@Remote("java.awt.Window")
interface AwtWindowRef {
    fun getOwnedWindows(): Array<com.intellij.driver.sdk.ui.remote.Component>
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
interface FileRef {
    fun getPath(): String
}

@Remote("javax.swing.JTree")
interface JTree {
    fun setSelectionRow(row: Int)
}

@Remote("com.intellij.ui.EditorTextField")
interface EditorTextField {
    fun setText(text: String)
    fun getText(): String
    fun getEditor(): EditorRef?
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

@Remote("com.intellij.ide.projectView.ProjectView")
interface ProjectViewRef {
    fun getInstance(project: com.intellij.driver.sdk.Project): ProjectViewRef
    fun getCurrentProjectViewPane(): ProjectViewPaneRef
}

@Remote("com.intellij.ide.projectView.impl.AbstractProjectViewPane")
interface ProjectViewPaneRef

@Remote("com.intellij.ide.dnd.DnDAction")
interface DnDActionRef {
    fun valueOf(name: String): DnDActionRef
}

@Remote("com.jetbrains.rider.projectView.moveProviders.RiderDragSource")
interface RiderDragSourceRef {
    fun startDragging(action: DnDActionRef, point: java.awt.Point): DnDDragStartBeanRef
}

@Remote("com.intellij.ide.dnd.DnDDragStartBean")
interface DnDDragStartBeanRef {
    fun getAttachedObject(): AnyRef
}

@Remote("java.lang.Object")
interface AnyRef

@Remote("com.intellij.ide.dnd.DnDManager")
interface DnDManagerRef {
    fun getInstance(): DnDManagerRef
}

@Remote("com.intellij.ide.dnd.DnDEventImpl")
interface DnDEventImplRef

@Remote("com.intellij.ide.dnd.FileCopyPasteUtil")
interface FileCopyPasteUtilRef {
    fun getFileList(transferable: DnDEventImplRef): List<FileRef>?
}

@Remote("com.wollumestudios.csharprepl.refs.ReferenceDrop", plugin = PLUGIN)
interface ReferenceDropRef {
    fun handleDrop(t: DnDEventImplRef, project: com.intellij.driver.sdk.Project?, editorWindow: AnyRef?)
}

@Remote("com.intellij.openapi.editor.FileDropManager")
interface FileDropManagerRef {
    fun scheduleDrop(t: DnDEventImplRef, editor: EditorRef?, editorWindow: AnyRef?)
}

@Remote("com.intellij.openapi.vfs.LocalFileSystem")
interface LocalFileSystemRef {
    fun getInstance(): LocalFileSystemRef
    fun refreshAndFindFileByPath(path: String): com.intellij.driver.sdk.VirtualFile?
}

@Remote("com.intellij.openapi.fileEditor.FileEditorManager")
interface FileEditorManagerRef {
    fun getInstance(project: com.intellij.driver.sdk.Project): FileEditorManagerRef
    fun openFile(file: com.intellij.driver.sdk.VirtualFile, focusEditor: Boolean): Array<AnyRef>
    fun getSelectedTextEditor(): EditorRef?
    fun getSelectedFiles(): Array<com.intellij.driver.sdk.VirtualFile>
}

@Remote("java.awt.event.MouseEvent")
interface MouseEventRef

@Remote("javax.swing.AbstractButton")
interface AbstractButtonRef {
    fun getText(): String
}

@Remote("javax.swing.MenuSelectionManager")
interface MenuSelectionManagerRef {
    fun defaultManager(): MenuSelectionManagerRef
    fun clearSelectedPath()
}

@Remote("com.intellij.openapi.editor.ex.EditorGutterComponentEx")
interface GutterRef {
    fun getGutterRenderers(line: Int): List<GutterMarkRef>
}

@Remote("com.intellij.openapi.editor.markup.GutterMark")
interface GutterMarkRef {
    fun getTooltipText(): String?
}

@Remote("com.intellij.execution.actions.ConfigurationContext")
interface ConfigurationContextRef {
    fun getFromContext(dataContext: DataContextRef, place: String): ConfigurationContextRef
    fun getConfigurationsFromContext(): List<ConfigurationFromContextRef>?
}

@Remote("com.intellij.execution.actions.ConfigurationFromContext")
interface ConfigurationFromContextRef {
    fun getConfiguration(): RunConfigurationRef
}

@Remote("com.intellij.execution.configurations.RunConfiguration")
interface RunConfigurationRef {
    fun getName(): String
    fun getType(): ConfigurationTypeRef
}

@Remote("com.intellij.execution.configurations.ConfigurationType")
interface ConfigurationTypeRef {
    fun getId(): String
}
