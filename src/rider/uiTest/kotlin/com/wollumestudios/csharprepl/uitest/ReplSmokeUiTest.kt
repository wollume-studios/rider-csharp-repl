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
class ReplSmokeUiTest : UiTestBase() {

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
            val colors = mutableMapOf<String, Int>() // token → non-default foreground
            val it = editor.getHighlighter().createIterator(0)
            while (!it.atEnd()) {
                it.getTextAttributes()?.getForegroundColor()?.getRGB()?.takeIf { c -> c != default }?.let { c -> colors[code.substring(it.getStart(), it.getEnd())] = c }
                it.advance()
            }
            colors
        }
        println("input colors: ${inputColors.mapValues { Integer.toHexString(it.value) }}")
        checkColored("input", code, inputColors)

        val historyColors = withContext(OnDispatcher.EDT) {
            val history = panel.getHistory()
            val text = history.getDocument().getText()
            val start = text.indexOf(code)
            val default = history.getColorsScheme().getDefaultForeground().getRGB()
            history.getMarkupModel().getAllHighlighters()
                .filter { h -> h.getStartOffset() >= start && h.getEndOffset() <= start + code.length }
                .mapNotNull { h -> h.getTextAttributes(history.getColorsScheme())?.getForegroundColor()?.getRGB()?.takeIf { c -> c != default }?.let { c -> text.substring(h.getStartOffset(), h.getEndOffset()) to c } }
                .toMap()
        }
        println("history colors: ${historyColors.mapValues { Integer.toHexString(it.value) }}")
        checkColored("history", code, historyColors)

        // Semantic colors with Rider's C# keys: method calls, an extension method, a lambda parameter, a local, a type.
        panelUi.enter("int Twice(int n) => n * 2;")
        waitFor("Twice declared", timeout = 1.minutes) { session.getHistory().any { it.getCode().startsWith("int Twice") && it.getStatus() == "ok" } }
        val semantic = "var list = new List<int>{1,2}; list.Select(x => x * 2).Sum() + Twice(1)"
        panelUi.enter(semantic)
        waitFor("the semantic sample ran", timeout = 1.minutes) { session.getHistory().any { it.getCode() == semantic && it.getResult() == "8" } }
        val history = withContext(OnDispatcher.EDT) { panel.getHistory() }
        val historyStart = withContext(OnDispatcher.EDT) { history.getDocument().getText().lastIndexOf(semantic) }
        checkSemantic("history", semantic, semanticColors(history, historyStart, semantic))
        withWriteAction { panel.getInput().setText(semantic) }
        val inputSemantic = waitNotNull("semantic colors in the input", timeout = 1.minutes) {
            semanticColors(editor, 0, semantic).takeIf { it.keys.containsAll(listOf("Select", "Sum", "x", "Twice")) }
        }
        checkSemantic("input", semantic, inputSemantic)
        Thread.sleep(1000) // repaint
        shot("repl-semantic", rectOf(panelUi.component))
    }

    /** Token → (key, foreground) of the highlighters with Rider's C# keys inside [code], which starts at [start] in [editor]. */
    private fun Driver.semanticColors(editor: EditorRef, start: Int, code: String): Map<String, List<Pair<String, Int?>>> = withContext(OnDispatcher.EDT) {
        val text = editor.getDocument().getText()
        editor.getMarkupModel().getAllHighlighters()
            .filter { h -> h.getStartOffset() >= start && h.getEndOffset() <= start + code.length && h.getTextAttributesKey()?.getExternalName()?.startsWith("ReSharper.CSHARP_") == true }
            .groupBy({ h -> text.substring(h.getStartOffset(), h.getEndOffset()) }) { h -> h.getTextAttributesKey()!!.getExternalName() to h.getTextAttributes(editor.getColorsScheme())?.getForegroundColor()?.getRGB() }
    }

    /** Select and Sum (extension method calls), Twice (a method call) and both `x` (the lambda parameter) carry Rider's keys and a color that is not plain text's. */
    private fun Driver.checkSemantic(where: String, code: String, colors: Map<String, List<Pair<String, Int?>>>) {
        println("$where semantic colors: ${colors.mapValues { (_, v) -> v.map { (k, c) -> k.removePrefix("ReSharper.CSHARP_") + "=" + c?.let(Integer::toHexString) } }}")
        val plain = withContext(OnDispatcher.EDT) { utility(EditorColorsManagerRef::class).getInstance().getGlobalScheme().getDefaultForeground().getRGB() }
        val expected = mapOf("Select" to "EXTENSION_METHOD_CALL", "Sum" to "EXTENSION_METHOD_CALL", "Twice" to "METHOD_CALL", "x" to "PARAMETER", "list" to "LOCAL_VARIABLE", "List" to "CLASS")
        for ((token, key) in expected) {
            val got = colors[token] ?: fail("$where: `$token` has no semantic highlighter in `$code`: $colors")
            check(got.all { it.first == "ReSharper.CSHARP_${key}_IDENTIFIER" }) { "$where: `$token` has ${got.map { it.first }}, expected $key" }
            if (token != "list" && token != "List") check(got.all { it.second != null && it.second != plain }) { "$where: `$token` is plain text's color: $got" }
        }
        check(colors.getValue("x").size == 2) { "$where: both x of the lambda: ${colors["x"]}" }
    }

    /** The keyword `var`, the string and the comment of `var s = "hi"; // c` each have a non-default color, three different ones. */
    private fun checkColored(where: String, code: String, colors: Map<String, Int>) {
        val wanted = listOf("var", "\"hi\"", "// c").map { token -> colors[token] ?: fail("$where: `$token` has the default color in `$code`; colored: $colors") }
        check(wanted.distinct().size == 3) { "$where: keyword, string and comment share colors: $colors" }
    }

    /** Runs the action like a keystroke would (update, then perform if enabled); false when it was disabled. */
    private fun Driver.tryAction(id: String, component: com.intellij.driver.sdk.ui.remote.Component): Boolean = withContext(OnDispatcher.EDT) {
        val actions = service(com.intellij.driver.sdk.ActionManager::class)
        !actions.tryToExecute(actions.getAction(id) ?: fail("no action $id"), null, component, null, true).isRejected()
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
        val appEditor = withContext(OnDispatcher.EDT, LockSemantics.WRITE_ACTION) { // VFS refresh and opening need the write-intent lock
            val vf = utility(LocalFileSystemRef::class).getInstance().refreshAndFindFileByPath(app.toString().replace('\\', '/')) ?: fail("no VirtualFile for $app")
            utility(FileEditorManagerRef::class).getInstance(project).openFile(vf, true)
            utility(FileEditorManagerRef::class).getInstance(project).getSelectedTextEditor() ?: fail("no editor for $app")
        }
        withContext(OnDispatcher.EDT) { service(FileDropManagerRef::class, project).scheduleDrop(dragged, appEditor, null) }
        waitFor("#:project directive in app.cs", timeout = 1.minutes) { "#:project Greeter/Greeter.csproj" in withContext(OnDispatcher.EDT) { appEditor.getDocument().getText() } }
        println("app.cs after the drop:\n" + withContext(OnDispatcher.EDT) { appEditor.getDocument().getText() })

        // b) The split button's popup, then both variants.
        for (code in listOf("var r = 41;", "Greeter.Hi(\"x\") + (r + 1)", "int.Parse(\"x\")")) {
            panelUi.enter(code)
            waitFor("$code ran", timeout = 2.minutes) { session.getHistory().any { it.getCode() == code && it.getStatus() in setOf("ok", "threw") } }
        }
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

        // c) What Rider offers to run a file-based app: gutter icons on its lines, and run configurations from the context;
        //    for app.cs in the solution folder and for the exported scratch file.
        val exported = withContext(OnDispatcher.EDT) { utility(FileEditorManagerRef::class).getInstance(project).getSelectedTextEditor() ?: fail("no editor") }
        fun describe(editor: EditorRef) = withContext(OnDispatcher.EDT) {
            val g = editor.getGutter()
            (0 until 10).flatMap { line -> g.getGutterRenderers(line).map { m ->
                val click = m.getClickAction()
                val icon = m.getIcon()?.let { i -> runCatching { cast(i, IconPathRef::class).getOriginalPath() }.getOrNull() ?: i.getClass().getName() }
                "line ${line + 1}: ${m.getClass().getName().substringAfterLast('.')} icon=$icon tooltip=${m.getTooltipText()} click=${click?.getTemplateText()} (${click?.getClass()?.getName()})"
            } }
        }
        // Rider's backend marks (BackendGutterMarkWrapper: build, run) come after it synced a project for the file; watch for 3 minutes.
        val seen = linkedSetOf<String>()
        runCatching { waitFor("Rider's gutter marks on the scratch file", timeout = 3.minutes) { describe(exported).filter { "BackendGutterMark" in it }.also { seen += it }.isNotEmpty() && seen.size > 1 } }
        println("Rider's own gutter marks seen on the scratch file: $seen")
        Thread.sleep(3000) // repaint
        shot("single-file-gutter-scratch")
        withContext(OnDispatcher.EDT, LockSemantics.WRITE_ACTION) { utility(FileEditorManagerRef::class).getInstance(project).openFile(appEditor.getVirtualFile(), true) }
        Thread.sleep(3000)
        shot("single-file-gutter-app")
        val report = buildString {
            for ((name, editor) in listOf("app.cs (solution folder)" to appEditor, "${ok.name} (scratch file)" to exported)) {
                val configurations = withContext(OnDispatcher.EDT, LockSemantics.READ_ACTION) { // producers read PSI
                    val context = utility(ConfigurationContextRef::class).getFromContext(utility(DataManagerRef::class).getInstance().getDataContext(editor.getContentComponent()), "EditorPopup")
                    context.getConfigurationsFromContext()?.map { it.getConfiguration().getName() + " [" + it.getConfiguration().getType().getId() + "]" }.orEmpty()
                }
                appendLine("$name: gutter=${describe(editor)} configurations=$configurations")
            }
            appendLine("Rider's marks seen on the scratch file while it synced: $seen")
        }
        println("run check:\n$report")
        Files.writeString(screenshots.resolve("singlefile-run-check.txt"), report)

        // Run Single-File App (editor menu / our gutter ▶) on the scratch file: dotnet run in the Run tool window.
        withContext(OnDispatcher.EDT, LockSemantics.WRITE_ACTION) { utility(FileEditorManagerRef::class).getInstance(project).openFile(exported.getVirtualFile(), true) }
        invokeAction("CSharpRepl.RunSingleFileApp", component = exported.getContentComponent())
        val run = waitNotNull("${ok.name} ran in the Run tool window", timeout = 3.minutes) {
            withContext(OnDispatcher.EDT) {
                utility(RunContentManagerRef::class).getInstance(project).getAllDescriptors()
                    .firstOrNull { it.getDisplayName() == ok.name && it.getProcessHandler()?.isProcessTerminated() == true }
                    ?.let { d -> cast(d.getExecutionConsole(), ConsoleViewRef::class).let { c -> c.flushDeferredText(); d.getProcessHandler()!!.getExitCode() to c.getText() } }
            }
        }
        println("Run Single-File App: exit ${run.first}\n${run.second}")
        Thread.sleep(1000)
        shot("single-file-run")
        check(run.first == 0 && "Hi x42" in run.second) { "Run Single-File App: exit ${run.first}\n${run.second}" }
    }

    /** `dotnet run <file>` in the file's folder: exit code and output (stdout and stderr). */
    private fun dotnetRun(file: Path): Pair<Int, String> {
        val process = ProcessBuilder("dotnet", "run", file.toString()).directory(file.parent.toFile()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        return process.waitFor() to out
    }

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

    private fun Driver.dump(c: com.intellij.driver.sdk.ui.remote.Component, depth: Int, out: StringBuilder) {
        out.append("  ".repeat(depth)).append(c.getClass().getName()).append(" [${c.x},${c.y} ${c.width}x${c.height}]")
            .append(if (c.isShowing()) " showing" else " NOT showing").append('\n')
        val container = cast(c, AwtContainer::class)
        if (depth < 12 && !c.getClass().getName().startsWith("com.intellij.openapi.editor.impl.")) // editors: deep and not ours
            for (i in 0 until container.getComponentCount()) dump(container.getComponent(i), depth + 1, out)
    }
}

private const val PLUGIN = "com.wollumestudios.csharprepl"

@Remote("com.wollumestudios.csharprepl.ui.ReplPanel", plugin = PLUGIN)
interface ReplPanel {
    fun getInput(): EditorTextField
    fun getHistory(): EditorRef
    fun getDrop(): ReferenceDropRef
    fun getVariables(): VariablesViewRef
}

@Remote("com.wollumestudios.csharprepl.ui.VariablesView", plugin = PLUGIN)
interface VariablesViewRef {
    fun select(name: String)
    fun getTable(): TreeTableRef
}

@Remote("com.intellij.ui.treeStructure.treetable.TreeTable")
interface TreeTableRef {
    fun getTree(): JTree
}

@Remote("com.intellij.openapi.editor.ex.EditorEx")
interface EditorRef {
    fun getHighlighter(): EditorHighlighterRef
    fun getMarkupModel(): MarkupModelRef
    fun getColorsScheme(): ColorsSchemeRef
    fun getContentComponent(): com.intellij.driver.sdk.ui.remote.Component
    fun getDocument(): DocumentRef
    fun getCaretModel(): CaretModelRef
    fun getComponent(): com.intellij.driver.sdk.ui.remote.Component
    fun getGutter(): GutterRef
    fun getVirtualFile(): com.intellij.driver.sdk.VirtualFile
}

@Remote("com.intellij.openapi.editor.CaretModel")
interface CaretModelRef {
    fun moveToOffset(offset: Int)
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

@Remote("com.intellij.openapi.editor.colors.TextAttributesKey")
interface TextAttributesKeyRef {
    fun getExternalName(): String
}

@Remote("com.intellij.openapi.editor.colors.EditorColorsManager")
interface EditorColorsManagerRef {
    fun getInstance(): EditorColorsManagerRef
    fun getGlobalScheme(): ColorsSchemeRef
}

@Remote("com.intellij.openapi.editor.markup.MarkupModel")
interface MarkupModelRef {
    fun getAllHighlighters(): Array<RangeHighlighterRef>
}

@Remote("com.intellij.openapi.editor.markup.RangeHighlighter")
interface RangeHighlighterRef {
    fun getStartOffset(): Int
    fun getEndOffset(): Int
    fun getTextAttributesKey(): TextAttributesKeyRef?
    fun getTextAttributes(scheme: ColorsSchemeRef?): TextAttributesRef?
}

@Remote("com.intellij.codeInsight.lookup.LookupManager")
interface LookupManagerRef {
    fun getActiveLookup(editor: EditorRef): LookupRef?
}

@Remote("com.intellij.codeInsight.lookup.Lookup")
interface LookupRef {
    fun getItems(): List<LookupElementRef>
    fun getComponent(): com.intellij.driver.sdk.ui.remote.Component
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
    fun setMaximized(window: ToolWindowRef, maximized: Boolean)
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
    fun getSubimage(x: Int, y: Int, w: Int, h: Int): BufferedImageRef
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
    fun setBounds(x: Int, y: Int, width: Int, height: Int)
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

@Remote("com.intellij.openapi.ui.popup.ListPopup")
interface ListPopupRef {
    fun getListStep(): ListPopupStepRef
}

@Remote("com.intellij.openapi.ui.popup.ListPopupStep")
interface ListPopupStepRef {
    fun getValues(): List<AnyRef>
}

@Remote("com.intellij.ui.popup.PopupFactoryImpl\$ActionItem")
interface ActionItemRef {
    fun getText(): String
    fun getAction(): AnActionRef
}

@Remote("com.intellij.openapi.ui.DialogWrapper")
interface DialogWrapperRef {
    fun findInstance(c: com.intellij.driver.sdk.ui.remote.Component): DialogWrapperRef?
    fun doCancelAction()
}

@Remote("javax.swing.JList")
interface JListModelRef {
    fun getModel(): ListModelRef
}

@Remote("javax.swing.ListModel")
interface ListModelRef {
    fun getSize(): Int
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
    fun getSelectionRows(): IntArray?
    fun expandRow(row: Int)
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
    fun getChangedProject(): String?
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

@Remote("com.jetbrains.rider.projectView.moveProviders.RiderDragSource", plugin = "com.intellij/intellij.rider") // a product module of the core plugin
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

@Remote("com.intellij.openapi.editor.markup.GutterIconRenderer")
interface GutterMarkRef {
    fun getTooltipText(): String?
    fun getClass(): ClassRef
    fun getClickAction(): AnActionRef?
    fun getIcon(): IconRef?
}

@Remote("javax.swing.Icon")
interface IconRef {
    fun getClass(): ClassRef
}

@Remote("com.intellij.ui.icons.IconPathProvider")
interface IconPathRef {
    fun getOriginalPath(): String?
}

@Remote("com.intellij.openapi.actionSystem.AnAction")
interface AnActionRef {
    fun getTemplateText(): String?
    fun getClass(): ClassRef
}

@Remote("java.lang.Class")
interface ClassRef {
    fun getName(): String
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

@Remote("com.intellij.execution.ui.RunContentManager")
interface RunContentManagerRef {
    fun getInstance(project: com.intellij.driver.sdk.Project): RunContentManagerRef
    fun getAllDescriptors(): List<RunContentDescriptorRef>
}

@Remote("com.intellij.execution.ui.RunContentDescriptor")
interface RunContentDescriptorRef {
    fun getDisplayName(): String
    fun getProcessHandler(): ProcessHandlerRef?
    fun getExecutionConsole(): AnyRef
}

@Remote("com.intellij.execution.process.ProcessHandler")
interface ProcessHandlerRef {
    fun isProcessTerminated(): Boolean
    fun getExitCode(): Int?
}

@Remote("com.intellij.execution.impl.ConsoleViewImpl")
interface ConsoleViewRef {
    fun flushDeferredText()
    fun getText(): String
}
