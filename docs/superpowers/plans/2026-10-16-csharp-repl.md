# C# REPL for Rider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Rider 2026.3 plugin that replaces the removed C# Interactive window: a REPL tool window with auto-run, visible pending code, a variables table, project/DLL/NuGet references, export to .NET 10 single-file apps, and change banners for referenced projects.

**Architecture:** Frontend-only Kotlin plugin (no ReSharper backend). It drives an out-of-process .NET host (`CSharpRepl.Host`, Roslyn scripting) over JSON lines on stdin/stdout. All C# logic (completeness, auto-`;`, value formatting, dump code, export rewriting, reference building) lives in the host and is unit-tested with xUnit. The frontend holds UI, history, settings and file watching.

**Tech Stack:** Kotlin 2.4.21, Gradle 9.1.0, IntelliJ Platform Gradle Plugin 2.19.0, Rider 2026.3 (build 263); .NET 8 host (`RollForward=LatestMajor`) with `Microsoft.CodeAnalysis.CSharp.Scripting` 4.14.x; xUnit; Gson (bundled with the platform).

**Spec:** `docs/superpowers/specs/2026-10-16-csharp-repl-design.md`

## Global Constraints

- Plugin id `com.wollumestudios.csharprepl`, display name **C# REPL**, Kotlin package `com.wollumestudios.csharprepl`, tool window id `C# REPL`, settings page *Settings › Tools › C# REPL*.
- Repo: `wollume-studios/rider-csharp-repl`, public, GPL-3.0 (`LICENSE` = full GPL-3.0 text). Repo root = `D:\Rider Projects 2\CSharpIneractive`.
- `sinceBuild = 263`, no `untilBuild`. `ProductVersion`: `2026.3` if it resolves from Maven, else `2026.3-EAP6-SNAPSHOT` (already in the Gradle cache), with `useInstaller = false`.
- Plugin version must parse as .NET `System.Version` (≤ 4 numeric parts), as in LamaRider.
- Build files are copied from `D:\Rider Projects 2\LamaRider` (`settings.gradle.kts`, `gradle/`, `gradlew*`, `build.gradle.kts`, `.gitattributes`, `.gitignore`, `.github/workflows/publish.yml`), minus everything rd/protocol/backend related.
- The host targets `net8.0`, `RollForward=LatestMajor`. Accepted references: `net8.0`+ and `netstandard2.x`. Rejected with a message: .NET Framework, newer than the installed runtime, `-windows` desktop TFMs.
- Every user-facing feature is an IntelliJ action (Find Action, keymap, UI tests without focus).
- Frontend callbacks from the host reader thread use `invokeLater` before touching Swing.
- Never kill the user's `rider64.exe`. Stop sandboxes by command line containing `.intellijPlatform\sandbox`, using PowerShell `Stop-Process`.

## Review Focus

1. **Values that are expensive or hostile to display** (an infinite `IEnumerable`, a `ToString()` that throws or returns 1 MB). Expected: the variables table never enumerates non-`ICollection` enumerables for the value column, truncates to 1000 chars, and shows `{ExceptionType: message}` for a throwing getter or `ToString`. Test in Task 2.
2. **Host dies mid-session** (`Environment.Exit(0)`, `StackOverflowException`). Expected: the history shows "Host exited (code N), session restarted", the host restarts with the same references, and the window stays usable. Test in Task 6.
3. **Referenced project rebuilt while the REPL holds it.** Expected: Rider's own build of the project still succeeds, because the host loads a private snapshot copy and never the project's `bin/`. Test in Task 3.
4. **Strings with quotes, newlines, backslashes, emoji, and `\0`** pass through the JSON protocol and come out as valid C# literals in the dump. Test in Task 4.
5. **`dotnet` missing or a referenced project failing to build.** Expected: a clear error containing the last 20 lines of build output, no hang, and the session stays alive. Test in Task 3.

---

## File structure

```
CSharpRepl.sln
src/host/CSharpRepl.Host/            .NET host (net8.0 exe)
  Program.cs                         JSON-lines loop, request dispatch (run on a single execution queue; other requests answered while a run is in progress)
  Protocol.cs                        request/response records
  ReplEngine.cs                      ScriptState chain, run, submissions log, variables, handles
  InputAnalyzer.cs                   completeness + auto-; rule
  ValueFormatter.cs                  display string, type name, children
  ReferenceBuilder.cs                dotnet build of project / nuget temp project into snapshot dirs
  ValueToCode.cs                     C# literal / initializer generation (dump, restore)
  SessionExporter.cs                 history -> compiling file-based app
  Directives.cs                      #:project / #:package lines for references
src/host/CSharpRepl.Host.Tests/      xUnit
src/rider/main/kotlin/com/wollumestudios/csharprepl/
  host/HostProcess.kt                process lifecycle, JSON lines, request futures, exit detection
  host/Protocol.kt                   Kotlin data classes mirroring Protocol.cs
  ReplSession.kt                     project service: host, history, pending, references, restart flows
  InputLogic.kt                      pure Enter/Shift+Enter decision
  ui/ReplToolWindowFactory.kt
  ui/ReplPanel.kt                    history, pending box, input, hint, split with variables, banner slot
  ui/VariablesView.kt                tree table with lazy expansion
  ui/ChangeBanner.kt                 Rebuild ▾ / Ignore ▾ banner
  refs/ReferenceDrop.kt              TransferHandler for the tool window
  refs/ProjectChangeWatcher.kt       BulkFileListener on referenced project dirs
  singlefile/DirectiveInserter.kt    pure: insert #:project line
  singlefile/SingleFileDrop.kt       editor drop handler for single-file apps
  singlefile/SingleFileActions.kt    New Single-File App, Export, Dump
  actions/ReplActions.kt             Run, ToggleAutoRun, Reset, Stop, AddReference, ReferenceInRepl, Settings gear
  settings/ReplSettings.kt           app-level PersistentStateComponent
  settings/ReplConfigurable.kt
src/rider/main/resources/META-INF/plugin.xml
src/rider/test/kotlin/...            plain JUnit (no platform), as in LamaRider's unitTest task
src/rider/uiTest/kotlin/...          Starter + Driver smoke test
```

## Protocol (Tasks 1–6 rely on these names)

One JSON object per line. Request `{"id":<int>,"cmd":"<name>",...}`. Response `{"id":<int>,"ok":true,...}` or `{"id":<int>,"ok":false,"error":"<text>"}`. The host writes the protocol to the stream from `Console.OpenStandardOutput()` and redirects `Console.Out`/`Console.Error` to a per-run capture writer.

| cmd | request fields | response fields |
|---|---|---|
| `analyze` | `code` | `complete: bool`, `fixed: string?` (code with `;` appended when the auto-`;` rule applies) |
| `run` | `code` | `status: "ok"\|"threw"\|"compileError"`, `output: string`, `result: string?` (display of the trailing expression; null for void or no expression), `diagnostics: string[]`, `exception: string?` (`Type: Message`) |
| `variables` | — | `variables: Var[]` (last declaration wins per name; `$exception` last when the most recent run threw) |
| `expand` | `handle: int` | `children: Var[]` |
| `addReference` | `kind: "project"\|"dll"\|"nuget"`, `path?`, `name?`, `version?` | `assemblies: string[]`, `sourceDirs: string[]` (projects: the project's directory and its ProjectReference closure) |
| `dump` | `filePath` | `text` (directives + `var` lines), `notReproducible: string[]` |
| `restoreScript` | — | `code` (type declarations from successful submissions, then the `var` lines), `notReproducible: string[]` |
| `export` | `filePath`, `excludeThrown: bool` | `text` |

`Var = {name, type, value, handle: int?}`. `handle` is non-null when the value has children.

---

### Task 1: Repo, host skeleton, run/analyze/variables

**Files:**
- Create: `CSharpRepl.sln`, `src/host/CSharpRepl.Host/{CSharpRepl.Host.csproj,Program.cs,Protocol.cs,ReplEngine.cs,InputAnalyzer.cs}`, `src/host/CSharpRepl.Host.Tests/{CSharpRepl.Host.Tests.csproj,InputAnalyzerTests.cs,ReplEngineTests.cs,ProtocolLoopTests.cs}`, `LICENSE`, `.gitignore`, `.gitattributes`, `README.md` (short stub)
- Test: `src/host/CSharpRepl.Host.Tests/*`

**Interfaces:**
- Produces: `InputAnalyzer.Analyze(string code) -> (bool Complete, string? Fixed)`; `ReplEngine.RunAsync(string code) -> Task<RunResult>`; `ReplEngine.GetVariables() -> IReadOnlyList<VarInfo>`; `ReplEngine.Submissions -> IReadOnlyList<Submission(string Code, SubmissionStatus Status)>`; `Program.RunLoop(TextReader input, Stream output, ReplEngine engine) -> Task`.

- [ ] **Step 1: `git init`, copy `.gitattributes`/`.gitignore` from LamaRider (drop rd lines, add `.superpowers/`), add GPL-3.0 `LICENSE`. Commit the spec, plan and these files.**
- [ ] **Step 2: Write failing tests**

```csharp
[Theory]
[InlineData("var r = 0", false, "var r = 0;")]
[InlineData("var r = 0;", true, null)]
[InlineData("1 + 1", true, null)]                       // trailing expression is a complete submission
[InlineData("Console.WriteLine(1)", true, null)]
[InlineData("for (var i = 0; i < 3; i++)", false, null)] // no empty body
[InlineData("while (true)", false, null)]
[InlineData("if (x)", false, null)]
[InlineData("void F() {", false, null)]
[InlineData("using System.Text", false, "using System.Text;")]
[InlineData("return", false, "return;")]
public void Analyze(string code, bool complete, string? fixedCode) { ... }

[Fact] async Task Variables_KeepValuesAcrossRuns()       // "var r = 0;" then "r++;" -> r == "1", type "int"
[Fact] async Task Redeclaration_LastWins()               // "var s = 1;" "var s = \"x\";" -> one s, type string, value "\"x\""
[Fact] async Task Throw_SetsExceptionVariable()          // "int.Parse(\"x\")" -> status threw, $exception type FormatException
[Fact] async Task CompileError_ReportsDiagnostics()      // "var = ;" -> compileError, diagnostics non-empty, state unchanged
[Fact] async Task ConsoleOutput_IsCaptured()             // "Console.WriteLine(\"hi\");" -> output "hi\n" (normalize \r\n)
[Fact] async Task Result_IsDisplayed()                   // "DateTime.Parse(\"2026-10-16\").ToString(\"dd.MM\")" -> result "\"16.10\""
[Fact] async Task Loop_AnswersAnalyzeWhileRunIsBusy()    // run "Thread.Sleep(2000)" then analyze: analyze response arrives first
[Fact] async Task Loop_UnicodeRoundTrip()                // run "var s = \"ä\\\"\\n😀\";" ; variables value == "\"ä\\\"\\n😀\""
```

- [ ] **Step 3: Run `dotnet test CSharpRepl.sln`. Expected: build fails (types missing).**
- [ ] **Step 4: Implement.**
  - `InputAnalyzer`: `SyntaxFactory.IsCompleteSubmission(SyntaxFactory.ParseSyntaxTree(code, ScriptOptions))`. If incomplete, parse `code + ";"`. Return `Fixed` only if that tree is complete, has no errors, and the `;` token at the end belongs to one of `ExpressionStatement, LocalDeclarationStatement, FieldDeclaration, ReturnStatement, ThrowStatement, BreakStatement, ContinueStatement, UsingDirective, YieldReturnStatement`. Script-mode top-level `var x = 0` parses as a `FieldDeclaration`.
  - `ReplEngine`: chain `ScriptState` (first run `CSharpScript.RunAsync`, then `state.ContinueWithAsync`) with `catchException: _ => true`. Default imports: `System, System.Collections.Generic, System.IO, System.Linq, System.Text, System.Threading.Tasks`. Keep `Submissions` with status. Variables: `state.Variables` grouped by name, last wins.
  - `Program`: one `Channel<Request>` for `run` (sequential), everything else answered immediately on the reader loop. Responses go through a lock on the protocol writer.
- [ ] **Step 5: Run `dotnet test`. Expected: all pass.**
- [ ] **Step 6: Commit `feat(host): run, analyze, variables over JSON lines`.**

### Task 2: Value display and expansion

**Files:**
- Create: `src/host/CSharpRepl.Host/ValueFormatter.cs`, `src/host/CSharpRepl.Host.Tests/ValueFormatterTests.cs`
- Modify: `ReplEngine.cs` (handles table, `Expand(int handle)`), `Program.cs` (`expand`)

**Interfaces:**
- Produces: `ValueFormatter.Display(object? v) -> string`; `ValueFormatter.TypeName(Type t) -> string` (C# keywords and generic syntax, e.g. `List<int>`); `ValueFormatter.Children(object v) -> IEnumerable<(string Name, object? Value, Type Type)>`; `ReplEngine.Expand(int handle) -> IReadOnlyList<VarInfo>`.

- [ ] **Step 1: Write failing tests**

```csharp
Display(null) == "null"; Display("a\"b") == "\"a\\\"b\""; Display('x') == "'x'"; Display(1.5m) == "1.5";
Display(new List<int>{1,2}) == "Count = 2";
Display(InfiniteEnumerable()) == "{IEnumerable<int>}";        // never enumerated (assert the generator was not advanced)
Display(new Throwing()) == "{InvalidOperationException: boom}";  // ToString throws
Display(new string('x', 5000)).Length <= 1002;                  // truncated with "…"
TypeName(typeof(Dictionary<string, List<int?>>)) == "Dictionary<string, List<int?>>";
Children(new { A = 1 }) contains ("A", 1);
Children(new ThrowingGetter()) value for P displays "{InvalidOperationException: boom}";
Children(new[]{1,2,3}) == [ "[0]", "[1]", "[2]" ]; Children of a 500-item list yields 100 items + "…" entry;
Children(new FormatException("m", new Exception("inner"))) contains "Message", "StackTrace", "InnerException";
```

- [ ] **Step 2: Run, verify they fail.**
- [ ] **Step 3: Implement.** Display rules: null, string/char literal style, primitives and `decimal` invariant culture, `ICollection` gives `Count = n`, another `IEnumerable` (not string) gives `{TypeName}`, otherwise `ToString()` (type name when `ToString` is the default `object.ToString()`). Truncate at 1000 chars. Children: public instance fields and properties without index parameters, then for `IEnumerable` the first 100 items as `[i]`. Getter exceptions are shown as values. The handle table maps `int` to `object` and is cleared on every `run`.
- [ ] **Step 4: Run `dotnet test`. Expected: pass.**
- [ ] **Step 5: Commit `feat(host): debugger-like value display and expansion`.**

### Task 3: References (project, DLL, NuGet)

**Files:**
- Create: `src/host/CSharpRepl.Host/ReferenceBuilder.cs`, `src/host/CSharpRepl.Host.Tests/ReferenceBuilderTests.cs`, `src/host/CSharpRepl.Host.Tests/Fixtures/SampleLib/SampleLib.csproj` (net8.0, references `Humanizer.Core` 2.14.1, class `SampleLib.Greeter { public static string Hi(string n) => $"Hi {n}"; }`), `Fixtures/WinLib/WinLib.csproj` (`net8.0-windows`, `UseWindowsForms`), `Fixtures/Broken/Broken.csproj` (compile error)
- Modify: `ReplEngine.cs` (`AddReferences(IEnumerable<string> assemblies)`: `ScriptOptions.AddReferences` + `AssemblyLoadContext.Default.Resolving` probing all snapshot dirs), `Program.cs` (`addReference`)

**Interfaces:**
- Produces: `ReferenceBuilder.BuildProject(string csprojPath) -> Task<BuiltReference(string[] Assemblies, string[] SourceDirs, string SnapshotDir)>`; `ReferenceBuilder.BuildNuGet(string name, string version) -> Task<BuiltReference>`; `ReferenceBuilder.Dll(string path) -> BuiltReference`; throws `ReferenceException(string message)`.

- [ ] **Step 1: Write failing tests**

```csharp
[Fact] async Task Project_LoadsAndRuns()           // addReference SampleLib; run "SampleLib.Greeter.Hi(\"x\")" -> result "\"Hi x\""
[Fact] async Task Project_TransitiveNuGetLoads()   // run "Humanizer.StringHumanizeExtensions.Humanize(\"some_text\")" works
[Fact] async Task Project_DoesNotLockBin()          // after load, `dotnet build SampleLib.csproj` (normal bin) exits 0
[Fact] async Task NuGet_LoadsPackage()              // BuildNuGet("Humanizer.Core","2.14.1") then run Humanize -> ok
[Fact] async Task WindowsTfm_Rejected()             // BuildProject(WinLib) throws ReferenceException containing "net8.0-windows"
[Fact] async Task BuildFailure_ReportsTail()        // Broken: ReferenceException message contains "error CS" and ≤ 20 output lines
[Fact] async Task Dll_Reference()                   // Dll(path to built SampleLib.dll copy) works
[Fact] void SourceDirs_IncludeProjectReferences()   // a fixture with a ProjectReference returns both directories
```

- [ ] **Step 2: Run, verify they fail.**
- [ ] **Step 3: Implement.**
  - TFM check first: `dotnet msbuild <csproj> -getProperty:TargetFramework -getProperty:TargetFrameworks`. Reject `net4*`, `*-windows*`, and a major version above `Environment.Version.Major`. For multi-targeting, pick the highest acceptable one (`-p:TargetFramework=`).
  - Build: `dotnet build <csproj> -c Debug -p:OutDir=<snapshot>\ -p:CopyLocalLockFileAssemblies=true -getProperty:TargetPath -getItem:ProjectReference`. Snapshot = `%LOCALAPPDATA%\CSharpRepl\refs\<name>-<yyyyMMddHHmmssfff>\`; snapshot dirs older than 1 day are deleted at host start.
  - NuGet: generate `%LOCALAPPDATA%\CSharpRepl\nuget\<name>-<version>\Ref.csproj` (`net{Environment.Version.Major}.0`, the `PackageReference`), then build it the same way. Assemblies = every `*.dll` in the snapshot except `Ref.dll`.
  - Process failure, or a missing `dotnet`, becomes a `ReferenceException` with the last 20 lines of combined output.
  - In `ReplEngine.RunAsync`, a submission line `#r "nuget: Name, Version"` is handled before Roslyn sees it: call `BuildNuGet`, add the references, and remove the line.
- [ ] **Step 4: Run `dotnet test`. Expected: pass (needs network for NuGet).**
- [ ] **Step 5: Commit `feat(host): project, DLL and NuGet references via snapshot builds`.**

### Task 4: Dump and restore code

**Files:**
- Create: `src/host/CSharpRepl.Host/ValueToCode.cs`, `src/host/CSharpRepl.Host/Directives.cs`, `src/host/CSharpRepl.Host.Tests/ValueToCodeTests.cs`
- Modify: `ReplEngine.cs` (references list kept as `IReadOnlyList<ReferenceInfo(string Kind, string PathOrName, string? Version)>`), `Program.cs` (`dump`, `restoreScript`)

**Interfaces:**
- Produces: `ValueToCode.TryToCode(object? v, Type declared, out string code) -> bool` (max depth 5, cycle detection by reference); `Directives.For(IEnumerable<ReferenceInfo>, string filePath) -> string` (`#:project <relative path>`, `#:package Name@Version`, a DLL as the comment `// reference DLL manually: <path>`); `ReplEngine.Dump(string filePath) -> (string Text, string[] NotReproducible)`; `ReplEngine.RestoreScript() -> (string Code, string[] NotReproducible)`.

- [ ] **Step 1: Write failing tests**

```csharp
Code(0) == "0"; Code(1.5) == "1.5"; Code(1.5m) == "1.5m"; Code(2L) == "2L"; Code(float.NaN) == "float.NaN";
Code("q\"\\\n\0😀") == "\"q\\\"\\\\\\n\\0😀\"";
Code(new DateTime(2026,10,16,1,2,3,DateTimeKind.Utc)) == "new DateTime(2026, 10, 16, 1, 2, 3, 0, DateTimeKind.Utc)";
Code(Guid.Empty) == "new Guid(\"00000000-0000-0000-0000-000000000000\")";
Code(DayOfWeek.Monday) == "DayOfWeek.Monday";
Code(new List<int>{1,2}) == "new List<int> { 1, 2 }";
Code(new Dictionary<string,int>{["a"]=1}) == "new Dictionary<string, int> { [\"a\"] = 1 }";
Code(new Poco { A = 1, B = "x" }) == "new Poco { A = 1, B = \"x\" }";
Code(new Rec(1, "x")) == "new Rec(1, \"x\")";              // positional record
TryToCode(new MemoryStream(), ...) == false;
TryToCode(cyclicNode, ...) == false;
[Fact] async Task Dump_CompilesAndReproduces()  // session: ints, strings (incl. Review Focus 4 string), list, poco declared in REPL? (no: poco from SampleLib), a stream
                                                 // -> dump text compiled with CSharpScript (directives stripped) yields equal values; stream line is "// s: MemoryStream = ... (not reproducible)"
[Fact] async Task RestoreScript_IncludesReplTypes() // "class P { public int X {get;set;} }" "var p = new P { X = 3 };" -> code contains the class, then "var p = new P { X = 3 };"; running it in a fresh engine gives p.X == 3
```

- [ ] **Step 2: Run, verify they fail.**
- [ ] **Step 3: Implement.** Types are named by their C# names (Task 2 `TypeName`), fully qualified when the namespace isn't in the default imports. REPL-declared types are emitted by their simple name (the script wrapper class prefix `Submission#N+` is stripped). Dump text = `#!/usr/bin/env dotnet`, directives, a blank line, the `var` lines. Restore code = `TypeDeclarationSyntax` / `DelegateDeclaration` / `EnumDeclaration` nodes from successful submissions, then the `var` lines.
- [ ] **Step 4: Run `dotnet test`. Expected: pass.**
- [ ] **Step 5: Commit `feat(host): dump variables to code and restore script`.**

### Task 5: Export session to a compiling single-file app

**Files:**
- Create: `src/host/CSharpRepl.Host/SessionExporter.cs`, `src/host/CSharpRepl.Host.Tests/SessionExporterTests.cs`
- Modify: `Program.cs` (`export`)

**Interfaces:**
- Consumes: `ReplEngine.Submissions`, `Directives.For`.
- Produces: `SessionExporter.Export(IReadOnlyList<Submission> subs, bool excludeThrown, string directives) -> string`.

- [ ] **Step 1: Write failing tests**

```csharp
[Fact] void BareExpression_BecomesWriteLine()        // "1 + 1" -> contains "Console.WriteLine(1 + 1);"
[Fact] void VoidCallExpression_StaysStatement()      // "Console.WriteLine(1)" -> "Console.WriteLine(1);" (no double wrap)
[Fact] void Redeclaration_Renamed()                  // "var s = 1;" "var s = \"x\";" "s.Length" -> "var s2 = \"x\";" and "Console.WriteLine(s2.Length);"
[Fact] void Types_MovedToEnd()                       // "class P {}" "var p = new P();" -> P declared after all statements
[Fact] void CompileErrors_AlwaysDropped()
[Fact] void Thrown_DroppedOnlyWhenRequested()
[Fact] void UsingDirectives_Hoisted()                // "using System.Text;" mid-session -> at the top after directives
[Fact] void Exported_Compiles()                      // for each case above: compile the output with CSharpCompilation (OutputKind.ConsoleApplication, top-level statements, references = trusted platform assemblies) -> no errors
```

- [ ] **Step 2: Run, verify they fail.**
- [ ] **Step 3: Implement.** Parse every submission in script mode. Collect `using` directives (deduplicated), statements and member declarations. A submission's trailing expression, the script result, becomes `Console.WriteLine(expr);` unless its type is `void` (decided with a semantic model over the script compilation chain). Renaming: keep a `name → count` map; a later declaration of an existing name becomes `name{n}`, and identifier references to it in later submissions are rewritten with a `CSharpSyntaxRewriter` (identifier-name match on the top-level variable, a known ceiling: `// ponytail:` comment).
- [ ] **Step 4: Run `dotnet test`. Expected: pass.**
- [ ] **Step 5: Commit `feat(host): export session as a compiling file-based app`.**

### Task 6: Plugin scaffold and host client

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat` (from LamaRider, without `:protocol`, rdgen, `compileDotNet`, backend DLL copying, or the `riderModel` artifact), `src/rider/main/resources/META-INF/plugin.xml`, `src/rider/main/resources/META-INF/pluginIcon.svg`, `src/rider/main/kotlin/com/wollumestudios/csharprepl/host/{HostProcess.kt,Protocol.kt}`, `src/rider/test/kotlin/com/wollumestudios/csharprepl/host/HostProcessTest.kt`
- Test: `HostProcessTest.kt` (plain JUnit, starts the real published host)

**Interfaces:**
- Produces: `class HostProcess(hostDll: Path, onExit: (Int) -> Unit) : Disposable` with `fun request(cmd: String, fields: Map<String, Any?>): CompletableFuture<JsonObject>`, `fun kill()`; Kotlin data classes `RunResponse`, `Var`, `AnalyzeResponse`, `ReferenceInfo(kind: String, pathOrName: String, version: String?)` mirroring the protocol table; `HostLocator.hostDll(): Path` (the plugin's `host/CSharpRepl.Host.dll`, or the system property `csharprepl.host` in tests).

- [ ] **Step 1: Write failing test** `HostProcessTest`: start the host, `request("run", code="var r = 0;")`, then `variables` contains `r`. A second test runs `Environment.Exit(3)` and asserts `onExit(3)` is called within 5 s and the pending future completes exceptionally.
- [ ] **Step 2: Gradle task `publishHost`**: `dotnet publish src/host/CSharpRepl.Host -c Release -o build/host`. `PrepareSandboxTask` copies `build/host` into `<plugin>/host`. `unitTest` depends on `publishHost` and passes `-Dcsharprepl.host=build/host/CSharpRepl.Host.dll`.
- [ ] **Step 3: Run `./gradlew unitTest`. Expected: FAIL (classes missing).**
- [ ] **Step 4: Implement `HostProcess`**: `GeneralCommandLine("dotnet", hostDll)` with UTF-8 charset, a reader thread parsing lines with Gson, `ConcurrentHashMap<Int, CompletableFuture>`, stderr appended to `idea.log` at debug level. On exit it fails all pending futures and calls `onExit`.
- [ ] **Step 5: `plugin.xml`**: `<depends>com.intellij.modules.rider</depends>`, id/name per Global Constraints, `require-restart="true"`.
- [ ] **Step 6: Run `./gradlew unitTest buildPlugin`. Expected: tests pass, `build/distributions/rider-csharp-repl-0.1.0.zip` contains `host/CSharpRepl.Host.dll`.**
- [ ] **Step 7: Commit `feat: plugin scaffold and host client`.**

### Task 7: REPL tool window

**Files:**
- Create: `InputLogic.kt`, `ReplSession.kt`, `ui/ReplToolWindowFactory.kt`, `ui/ReplPanel.kt`, `ui/VariablesView.kt`, `actions/ReplActions.kt`, `settings/ReplSettings.kt`, `settings/ReplConfigurable.kt`, `src/rider/test/kotlin/.../InputLogicTest.kt`
- Modify: `plugin.xml` (tool window anchor `bottom`, `applicationService` settings, `projectService` session, actions, `projectConfigurable parentId="tools"`)

**Interfaces:**
- Consumes: `HostProcess`, protocol classes.
- Produces: `sealed interface EnterAction { data class Run(val code: String); object NewLine; data class ToPending(val line: String) }`; `fun decide(autoRun: Boolean, shift: Boolean, pending: String, input: String, analysis: AnalyzeResponse): EnterAction`; `class ReplSession(project): Disposable` with `history: List<HistoryEntry(code, status, output, result, diagnostics, exception)>`, `references: List<ReferenceInfo>`, `fun submit(code)`, `fun reset()`, `fun restart(replay: RestartMode)` where `enum RestartMode { Empty, Replay, Restore }`, and a listener `onChanged`; `ReplSettings.state` with `autoRun: Boolean = true`, `variablesPosition: Right|Below = Right`, `ignoreChangesForever: Boolean = false`.

- [ ] **Step 1: Write failing `InputLogicTest`** (the spec's table):

```kotlin
decide(auto=true,  shift=false, "", "var r = 0", analyze(complete=false, fixed="var r = 0;")) == Run("var r = 0;")
decide(auto=true,  shift=false, "", "for (;;)",  analyze(false, null)) == NewLine
decide(auto=true,  shift=true,  "", "var r = 0", any) == NewLine
decide(auto=false, shift=false, "", "var r = 0", analyze(false, "var r = 0;")) == ToPending("var r = 0")
decide(auto=false, shift=false, "var r = 0", "var c = 0;", analyze(code="var r = 0\nvar c = 0;", complete=true)) == Run("var r = 0\nvar c = 0;")
decide(auto=false, shift=true,  "var r = 0", "x", any) == Run("var r = 0\nx")
```

  `analyze` is always called with `pending + "\n" + input` (or just `input` when pending is empty).
- [ ] **Step 2: Run `./gradlew unitTest`. Expected: FAIL.**
- [ ] **Step 3: Implement `InputLogic.decide`. Run tests. Expected: PASS.**
- [ ] **Step 4: Implement the UI** per the spec's layout A.
  - History: a read-only `EditorEx` with `CSharpFileType` (`com.jetbrains.rider.languages.fileTypes.csharp.CSharpFileType`, which uses the frontend `CSharpSyntaxHighlighter`), appended per entry. Results go in as comment-free plain lines, errors as red `RangeHighlighter`s, and `[inspect ›]` is a hyperlink that selects `$exception`.
  - Pending box: a yellow `JBLabel`/editor, hidden when empty, with a clear button.
  - Input: an `EditorTextField` (C# file type, one line minimum, growing). Enter/Shift+Enter come through `DumbAwareAction`s registered on the input component, and Up/Down at the boundary lines walk the history. Escape clears the input.
  - Hint line text: auto mode `Enter: run (adds missing ;) · Shift+Enter: new line`; manual mode `Enter: new line until the command is complete · Shift+Enter: run now`.
  - `VariablesView`: `TreeTable` (`ListTreeTableModelOnColumns`), columns Name/Value/Type, children loaded on expand through `expand`, refreshed after each run.
  - A `JBSplitter` is horizontal or vertical depending on `variablesPosition`, and rebuilt when the setting changes.
  - Toolbar actions: Run (turns into Stop while running; Stop = `kill` + restart with references, history note "Stopped — session restarted, variables lost"), ToggleAutoRun, Reset, To single-file app ▾ (Task 9), Dump variables (Task 9), + Reference (Task 8), gear.
  - Host exit (Review Focus 2): append "Host exited (code N), session restarted" and restart with references.
- [ ] **Step 5: Run `./gradlew runIde -PopenSolution=<any sln>` manually. Check: `var r = 0` + Enter runs in auto mode and `r` shows in the table; in manual mode the pending box shows `var r = 0`; `int.Parse("x")` shows `$exception`, which expands into Message and StackTrace.**
- [ ] **Step 6: Commit `feat: REPL tool window with auto-run, pending box and variables`.**

### Task 8: References UI, change banner, rebuild flows

**Files:**
- Create: `refs/ReferenceDrop.kt`, `refs/ProjectChangeWatcher.kt`, `ui/ChangeBanner.kt`, `src/rider/test/kotlin/.../ProjectChangeWatcherTest.kt`
- Modify: `ReplSession.kt` (`addReference(ReferenceInfo)`, `restart(mode)`), `actions/ReplActions.kt` (`AddReferenceAction`, `ReferenceInReplAction` in `SolutionExplorerPopupMenu` and `ProjectViewPopupMenu`), `plugin.xml`

**Interfaces:**
- Consumes: `addReference` (returns `sourceDirs`), `restoreScript`, `ReplSession.restart(RestartMode)`.
- Produces: `ProjectChangeWatcher.isRelevant(changedPath: String, sourceDirs: List<String>): Boolean` (pure; ignores `bin/`, `obj/`, `.idea/`); `ReplSession.ignoreUntilReset: Boolean`.

- [ ] **Step 1: Failing test `ProjectChangeWatcherTest`**: `isRelevant("C:/s/Lib/A.cs", ["C:/s/Lib"]) == true`, `isRelevant("C:/s/Lib/obj/x.cs", …) == false`, `isRelevant("C:/s/Other/A.cs", …) == false`, `isRelevant("c:\\s\\lib\\A.cs", ["C:/s/Lib"]) == true` (Windows, case and slash insensitive).
- [ ] **Step 2: Run, fail. Implement. Run, pass.**
- [ ] **Step 3: `ReferenceInReplAction`**: a project from `CommonDataKeys.VIRTUAL_FILE` (the `.csproj` file). If a Solution Explorer project node doesn't provide it, inspect the `DataContext` keys (log them once at info level) and use Rider's project model entity's file path. Discover the exact API by reflection on `intellij.rider.*.jar` per LamaRider's lesson. Don't guess.
- [ ] **Step 4: `ReferenceDrop`**: a `TransferHandler` on the panel and both editors, accepting `FileCopyPasteUtil.getFileList(transferable)` entries ending in `.csproj` or `.dll`. Otherwise it tries the same project-node resolution as Step 3. Other drops show the notification "Drop a project or DLL to reference it". *+ Reference* opens a popup: solution projects (Rider frontend project model), *DLL…* (file chooser), *NuGet package…* (`Name` + `Version` input dialog).
- [ ] **Step 5: Change banner**: `ProjectChangeWatcher` is a `BulkFileListener` (`VFS_CHANGES`) plus `ProjectTaskListener.finished` for builds. On a relevant change, unless `ignoreChangesForever` or `ignoreUntilReset` is set, it shows `ChangeBanner` in the panel's banner slot:
  > ⚠ `<ProjectName>` changed since it was referenced. **[Rebuild ▾]** (Rebuild · Rebuild & replay · Rebuild & restore variables) **[Ignore ▾]** (Ignore · Ignore for this session · Ignore forever)

  These are split buttons (`JBOptionButton`). Restore asks the old host for `restoreScript` *before* killing it, then submits that code visibly, and reports `notReproducible` in a notification. Replay submits the history entries with status `ok`, in order.
- [ ] **Step 5b: Gear menu** lists the referenced projects, DLLs and packages, each with *Remove*. Removing one asks "Removing a reference resets the session. Reset now?" and on yes runs `restart(Empty)` without that reference.
- [ ] **Step 6: Manual check in `runIde` with a sample solution (`samples/SampleSolution`: a class library `Greeter` and a sln; add it).** Drag the project onto the window, run `Greeter.Hi("x")`, edit `Greeter.cs` and save, the banner appears, *Rebuild & restore variables* keeps the variables, *Ignore ▾ › Ignore forever* toggles the setting.
- [ ] **Step 7: Commit `feat: references by drag and drop, change banner and rebuild flows`.**

### Task 9: Single-file app actions

**Files:**
- Create: `singlefile/DirectiveInserter.kt`, `singlefile/SingleFileDrop.kt`, `singlefile/SingleFileActions.kt`, `src/rider/test/kotlin/.../DirectiveInserterTest.kt`
- Modify: `plugin.xml` (`NewSingleFileAppAction` in `NewGroup` and the gear menu, `ExportToSingleFileAction` with a "Exclude commands that threw" toggle in its dropdown group, `DumpVariablesAction`, `customPasteProvider`/`editorFactoryListener` drop hook)

**Interfaces:**
- Consumes: `export`, `dump`.
- Produces: `DirectiveInserter.insert(text: String, directive: String): String`; `DirectiveInserter.isSingleFileApp(text: String, inSolutionProject: Boolean): Boolean`.

- [ ] **Step 1: Failing `DirectiveInserterTest`**:

```kotlin
insert("#!/usr/bin/env dotnet\n#:package A@1\nConsole.WriteLine();", "#:project ../Lib/Lib.csproj") ==
  "#!/usr/bin/env dotnet\n#:package A@1\n#:project ../Lib/Lib.csproj\nConsole.WriteLine();"
insert("Console.WriteLine();", "#:project x.csproj") == "#:project x.csproj\nConsole.WriteLine();"
insert(text already containing the same directive, same) == text           // no duplicate
isSingleFileApp("#:package A@1\n...", inSolutionProject = true) == true
isSingleFileApp("class C {}", inSolutionProject = false) == true
isSingleFileApp("class C {}", inSolutionProject = true) == false
```

- [ ] **Step 2: Run, fail. Implement. Run, pass.**
- [ ] **Step 3: Actions.** *New C# Single-File App* creates `<name>.cs` containing `#!/usr/bin/env dotnet\n\nConsole.WriteLine("Hello");\n` in the chosen directory. *To single-file app* and *Dump variables* use a save dialog defaulting to `<solution dir>/scratch/<session|variables>-<yyyyMMdd-HHmm>.cs`, call `export`/`dump` with that path, write the file, and open it. The drop on an editor of a single-file app inserts `#:project <path relative to the file, forward slashes>` through `WriteCommandAction`. Other editors keep their default drop behavior.
- [ ] **Step 4: Manual check in `runIde`.** Export a session containing a thrown command, with and without the exclude option. The opened file runs with Rider's Run button, or with `dotnet run file.cs` (verifies the non-goal assumption; if Rider can't run it, add a `RunSingleFileAction` calling `dotnet run <file>` in a Run tool window).
- [ ] **Step 5: Commit `feat: single-file app export, dump and project directives`.**

### Task 10: Publishing, docs, UI smoke test, GitHub

**Files:**
- Create: `.github/workflows/publish.yml` (from LamaRider; `dotnet test CSharpRepl.sln` and `./gradlew publishPlugin`), `.github/workflows/ci.yml` (`dotnet test` + `./gradlew unitTest buildPlugin` on windows-latest), `docs/plugin-description.html`, `CHANGELOG.md` (`## 0.1.0` with the feature list), `README.md` (features, build, install, `publishLocal`), `src/rider/uiTest/kotlin/.../ReplSmokeUiTest.kt`
- Modify: `build.gradle.kts` (`publishLocal` to `~/.csharprepl/plugin-repo`, `uiTest` source set and task as in LamaRider, using the env var `CSHARPREPL_RIDER` with default `%LOCALAPPDATA%\Programs\Rider`)

- [ ] **Step 1: `ReplSmokeUiTest`**: open `samples/SampleSolution/SampleSolution.sln`, invoke the action that shows the tool window, set the input to `var r = 0`, invoke the Enter action, and assert that the variables table has a row `r | 0 | int`.
- [ ] **Step 2: Run `./gradlew uiTest`. Expected: PASS (Windows, local Rider 263.6800.37).**
- [ ] **Step 3: Run `dotnet test CSharpRepl.sln` and `./gradlew unitTest buildPlugin publishLocal`. Expected: all green, and the printed repository URL.**
- [ ] **Step 4: Commit `chore: CI, publishing, docs, UI smoke test`.**
- [ ] **Step 5: Create and push the repo**: `gh repo create wollume-studios/rider-csharp-repl --public --source . --push --description "C# REPL tool window for JetBrains Rider (replacement for C# Interactive)"`. `LICENSE` already holds GPL-3.0. Expected: the repo URL is printed, `main` is pushed and CI starts.
