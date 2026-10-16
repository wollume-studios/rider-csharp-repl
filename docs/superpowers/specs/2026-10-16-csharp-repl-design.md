# C# REPL for Rider — design

Date: 2026-10-16 · Owner: Daniel Kuon · Status: draft

## Intent

Rider removes its built-in **C# Interactive** tool window in 2026.3. This plugin replaces it and fixes what made the
original annoying. Daniel uses C# Interactive for small things: checking a `ToString()`, parsing, a short loop. For
anything bigger, the plugin hands off to a .NET 10 single-file app (`app.cs`) that runs as a whole.

The original's worst problem: a command missing `;` gets silently merged with the next one. `var r = 0` followed by
`var c = 0;` runs as one submission and fails, and nothing on screen shows that this will happen. This plugin never
runs code you can't see.

## Goals

1. A REPL tool window that works like Rider's C# Interactive, with these improvements:
   - **Auto-run mode** (toggle, remembered): Enter runs the command, adding a missing `;` when that makes it complete.
   - **Visible pending code** in manual mode: a box shows exactly what the next run executes.
   - **Shift+Enter flips the mode for the current keystroke**: in auto mode it inserts a new line, in manual mode it
     runs now.
   - **Variables table** like the debugger's Variables view, showing every declared variable with its current value
     and type. Rows expand into fields and properties. The last exception appears as `$exception` and can be expanded
     (Message, StackTrace, InnerException, Data, and so on).
   - **Syntax highlighting** in the input and in the history.
   - **Project references by drag and drop**: dropping a project (from Solution Explorer or Explorer) onto the window
     adds a reference to it.
   - **Reset session** command.
2. **To single-file app**: writes the session history as a `.cs` file-based app and opens it. An option leaves out the
   commands that threw.
3. **Dump variables**: creates a new single-file app with only `var` declarations holding the current values.
4. **Single-file app support**: a *New C# Single-File App* action, and dropping a project onto a single-file app's
   editor inserts a `#:project <relative path>` directive.
5. **Change detection for referenced projects**: a banner offers a rebuild (details below).

## Non-goals (v1)

- Running single-file apps from the plugin. Rider 2026.3 runs them with its normal Run button. *(builder: verify;
  if Rider can't, a simple Run action calling `dotnet run app.cs` is in scope.)*
- Debugging REPL code with breakpoints.
- A NuGet package browser with search. NuGet *is* in v1 (Daniel's decision), as a simple resolver:
  `#r "nuget: Name, Version"` and *+ Reference › NuGet package* (name + version) generate a temp csproj with the
  `PackageReference`, run `dotnet restore`/`build`, and load the resulting managed DLLs. Native-library special cases
  are out of scope.
- Rider versions before 2026.3. F#.

## Anti-goals

- Running anything the user cannot see on screen before it runs.
- A REPL crash, stuck loop or `Environment.Exit` taking Rider down.

## UI

### Tool window "C# REPL"

Approved mockup: `.superpowers/brainstorm/39487-1792164576/content/layout.html`, layout **A**.

```
┌ toolbar: ▶ Run │ ↵ Auto-run on Enter (toggle) │ ⟲ Reset │ 📄 To single-file app ▾ │ ⤓ Dump variables │ + Reference │ ⚙ ┐
├ [banner area: referenced project changed …]                                                                   ┤
├──────────────── history (read-only, highlighted) ──────────┬──────── Variables ─────────────────────┤
│ > var s = "2026-10-16"                                    │ Name        │ Value          │ Type    │
│ > DateTime.Parse(s).ToString("dd.MM")                     │ ▸ s         │ "2026-10-16"   │ string  │
│   "16.10"                                                  │ ▸ $exception│ {FormatExc…}   │ …       │
│ > int.Parse("x")                                           │                                       │
│   FormatException: …   [inspect ›]                         │                                       │
│ ┌ Will run on next complete command: (manual mode only) ┐ │                                       │
│ └ var c = 0                                              ┘ │                                       │
│ input editor (multi-line, highlighted)                     │                                       │
│ hint: Enter: run (adds missing ;) · Shift+Enter: new line  │                                       │
└────────────────────────────────────────────────────────────┴───────────────────────────────────────┘
```

- The variables table sits **on the right by default**. A setting (and the ⚙ menu) moves it **below** the input,
  applied without a restart.
- History entries show the code that actually ran (including an auto-added `;`). Results are shown with their
  `ToString()`. Strings are quoted. Errors appear in red with an `[inspect ›]` link that selects `$exception` in the
  table.
- The hint line always describes what Enter and Shift+Enter do in the current mode.
- Up and Down in the input, at the first or last line, step through command history *(my call; the original does the
  same)*.

### Enter / Shift+Enter semantics

"Complete" means Roslyn's `SyntaxFactory.IsCompleteSubmission` with script parse options.

| Mode | Enter | Shift+Enter |
|---|---|---|
| **Auto** | Run the input. If it is incomplete and the *only* thing missing is the `;` ending an expression statement or local declaration, append `;` and run. Never append when that would create an empty statement body (`while (true)` + `;` would loop forever). Otherwise (open brace, `for (...)` without a body) insert a new line. | Insert a new line. |
| **Manual** | If pending + input is complete: run it. Otherwise move the input line into the **pending box** and keep waiting. | Run pending + input now, as is (errors are shown normally). |

- The pending box is visible whenever it is non-empty, so the `var r = 0` / `var c = 0;` case is obvious *before* it
  runs.
- Escape clears the input. A clear button on the pending box discards the pending code *(my call)*.
- Sessions start in the mode last used (persisted setting).

### Toolbar actions

Every feature is also an IntelliJ action (Find Action, keymap, testable without focus).

- **Run**: same as Shift+Enter in manual mode.
- **Reset**: kills the host process and starts a fresh session. Clears history, variables and pending code, and keeps
  references *(my call)*.
- **To single-file app ▾**: writes the session as `<name>.cs` and opens it. The dropdown has the checkbox "Exclude
  commands that threw". References become `#:project` / `#:package` directives (DLL references: an
  `#:property`/item equivalent if one exists, otherwise a comment telling the user what to add). **The file must compile
  and run like the session** (Daniel's decision). The host rewrites it with Roslyn: a bare result expression becomes
  `Console.WriteLine(...)`, a redeclared variable gets a new name (`s` → `s2`, with later uses updated), and types
  declared in the session move after the top-level statements. Commands that failed to compile are always left out.
  A round-trip test checks that exported sessions compile. Location:
  defaults to a scratch folder next to the solution (`<solution>/scratch/`); a save dialog lets the user change it
  *(my call)*.
- **Dump variables**: creates a new single-file app containing only `var <name> = <C# literal>;` for every current
  variable (references carried over as directives), and opens it.
  - Value-to-code: primitives, strings, chars, enums, `DateTime`/`DateTimeOffset`/`TimeSpan`/`Guid`/`decimal`, and
    nulls as literals or constructor calls; arrays, `List<T>`, dictionaries and sets as collection initializers;
    other objects with a public parameterless constructor and settable public properties as
    `new T { P = … }`, recursively (depth limit and cycle detection: once those are hit, a comment). Records with a
    primary constructor become positional `new T(…)`.
  - Anything that can't be written back as code (streams, delegates, types without a settable shape) becomes
    `// name: <Type> = <ToString()> (not reproducible)`. The file must always compile.
- **+ Reference**: a chooser with solution projects, a DLL file, or a NuGet package (name and version). Same effect as
  dropping a project.
- **⚙**: variables position, auto-run default, the "ignore changes forever" switch.

### Runtime and supported references *(my call)*

- The host runs on the newest installed .NET runtime (8 or later). There is no version setting.
- Supported references: `net8.0`+ and `netstandard2.x` projects and DLLs.
- Rejected with a clear message on reference: .NET Framework projects, projects targeting a newer runtime than the
  installed ones, and `-windows` desktop (WPF/WinForms) projects. The last can be added later with a Windows Desktop
  variant of the host.

### Project references

- Dropping a project node from Solution Explorer, or a `.csproj` file from the OS file manager, onto the tool window
  references it. Other drops are ignored with a short notification.
- Referencing builds the project first and then adds its output DLL (`TargetPath`) plus its dependency closure to the
  session (`#r`). The project's NuGet dependencies have to resolve, for example by loading from the project's output
  folder or via its `deps.json` *(builder's call how)*.
- A reference survives Reset. Referenced projects are listed in the ⚙ menu with a remove option. Removing one requires
  a reset, which the UI offers.

### Banner: referenced project changed

Shown when a source file of a referenced project changes and is saved, or after a build of it:

> ⚠ `MyLib` changed since it was referenced.  **[Rebuild ▾]**  **[Ignore ▾]**

- **Rebuild ▾**
  - *Rebuild* (button default): build, restart the host, empty session, references kept.
  - *Rebuild & replay*: build, restart, rerun the history in order, skipping commands that threw.
  - *Rebuild & restore variables*: build, restart, then run the type declarations from the history (classes and
    records declared in the REPL) followed by the generated dump declarations (same code as *Dump variables*). All of
    it is echoed into the history as it runs: the user picked this option explicitly, so this is not a hidden run
    *(my call)*. Values that can't be reproduced are listed in a notification.
  - Replay and restore skip commands that threw *and* commands that failed to compile.
- **Ignore ▾**
  - *Ignore* (button default): hide the banner until the next change.
  - *Ignore for this session*: no banner until the next Reset.
  - *Ignore forever*: stored in settings and switched back on in ⚙ / *Settings › Tools › C# REPL*.

### Single-file apps (editor side)

- *New C# Single-File App* action (File › New and the REPL ⚙ menu): creates `<name>.cs` with
  `#!/usr/bin/env dotnet` and a `Console.WriteLine` stub.
- Dropping a project onto an editor showing a `.cs` file that is a single-file app (detected: not part of any project
  in the solution, or has `#:` directives) inserts `#:project <path relative to the file>` after the existing `#:`
  directives at the top. Other `.cs` editors keep Rider's normal drop behavior.

## Architecture (builder's call where not stated)

- **Repository**: new public GitHub repo in the `wollume-studios` org, GPL-3.0. Built from the LamaRider skeleton
  (`D:\Rider Projects 2\LamaRider`, github.com/wollume-studios/LamaRider): Gradle 9.1, IntelliJ Platform Gradle Plugin
  2.19.0, Kotlin 2.4.21, `ProductVersion=2026.3-EAP6-SNAPSHOT` (or the 2026.3 release if it's out), `sinceBuild=263`,
  foojay toolchain, `publishLocal` local plugin repo, Marketplace publishing workflow.
- **Frontend (Kotlin)**: tool window, editors, drag and drop, banner, settings, actions. Try **frontend-only first**,
  without a ReSharper backend: Rider's frontend project model is enough for project nodes and paths. Add a backend
  only if something truly needs it, for example C# highlighting in a non-solution editor *(builder's call; keep it
  out if possible)*.
- **Highlighting**: input and history are editors using Rider's C# file type / highlighter. Lexer-level highlighting
  is enough; semantic highlighting and completion are not required in v1 *(my call)*.
- **Host process**: a small .NET console app (`net8.0`, `RollForward=LatestMajor`, so referenced net8–net10 projects
  load), shipped in the plugin, using `Microsoft.CodeAnalysis.CSharp.Scripting` (not dotnet/interactive, which is
  deprecated and heavier). It talks to the frontend over JSON lines on stdin/stdout and stays alive between commands.
  Requests: `run`, `isComplete`, `variables`, `expand(handle)`, `dump`, `addReference`, `cancel`. Variables come from
  `ScriptState.Variables`. Values are kept server-side with handles for lazy expansion, and long `ToString()` output
  is truncated.
- **Cancel**: a running command can be cancelled (toolbar Run turns into Stop). A command that doesn't stop is handled
  by killing and restarting the host, which leaves an empty session.
- **Console output**: `Console.Out`/`Console.Error` output written during a command appears in the history, above the
  result.
- **Building referenced projects**: `dotnet build` on the project, or Rider's build API if it's straightforward.
  Output path via `dotnet msbuild -getProperty:TargetPath`.

## LamaRider lessons to apply

- The plugin version must parse as .NET `System.Version` (at most 4 numeric parts) if a backend is ever added.
- Frontend callbacks from background threads use `invokeLater` before touching Swing. PSI and caret work goes inside
  read actions.
- Every feature is an action, so UI tests run without focus. UI tests use IntelliJ Starter + Driver against an
  installed Rider.
- Never kill the user's `rider64.exe`. Stop the sandbox by its command line (`.intellijPlatform\sandbox`) with
  PowerShell `Stop-Process`.
- Read Rider logs (`%LOCALAPPDATA%\JetBrains\Rider2026.3\log`) instead of guessing.

## Testing

- Host: a .NET test project covering complete/incomplete detection, auto-`;`, variables, exception capture,
  value-to-code dump (with a round-trip test that the generated file compiles and reproduces the values), cancel, and
  references.
- Frontend: plain JUnit for the logic (Enter/Shift+Enter decisions, history-to-file, directive insertion). One UI test
  that opens the tool window, runs `var r = 0` in auto mode, and checks the variables table.

## Left to the builder

The plugin's display name and the repo name (suggestion: **"C# REPL"**, repo `rider-csharp-repl`), the icon, the
JSON protocol shape, and the exact host packaging.
