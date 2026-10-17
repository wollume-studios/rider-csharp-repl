# C# REPL for Rider

A C# REPL tool window for JetBrains Rider: the replacement for Rider's **C# Interactive** window, which Rider 2026.3
removed. Code runs in a persistent Roslyn scripting session in its own .NET process, so a crash, an endless loop or
`Environment.Exit` never takes Rider down. Unlike C# Interactive it never runs code you can't see: a command missing its
`;` is not silently merged with the next one.

Requirements: Rider 2026.3 or later and the **.NET 8+ SDK** (`dotnet`): it runs the session and builds project and NuGet
references; the .NET 10 SDK runs exported single-file apps. References must target `net8.0`+ or `netstandard2.x`.
`dotnet` is looked up on `PATH`, then in `DOTNET_ROOT`, then in the default install locations (`~/.dotnet`,
`/usr/local/share/dotnet`, `/usr/share/dotnet`, `C:\Program Files\dotnet`), so a Rider started without a shell `PATH`
finds it too. The session runs in the solution directory, so relative paths and the solution's `global.json` apply.

## Features

Open the **C# REPL** tool window (bottom bar). Every feature is also an action (Find Action, keymap).

| Feature | How |
|---|---|
| **Auto-run** | Toolbar ⚡ toggle (remembered). On: Enter runs the input and adds a missing `;` when that completes the command; Shift+Enter inserts a new line. Off (manual): Enter adds the line to the pending code until the command is complete, Shift+Enter (or ▶ Run) runs pending code and input now. The hint under the input says what Enter does. |
| **Pending box** | Manual mode: *Will run on next complete command* shows the code waiting for the rest of the command; ✕ discards it. |
| **Completion** | While typing an identifier or after a `.`, a popup lists the session's variables, methods and types, members of the expression before the dot, namespaces, referenced projects' and packages' types, and keywords. Ctrl+Space (Basic Completion's shortcut) asks at once. Enter or Tab inserts the selected item, Escape closes the popup; while it shows, Enter does not run the input and Up/Down move in the list. Nothing runs to compute it: the host only compiles the input as the next command. |
| **History** | C# highlighted commands with console output, results (`ToString()`, strings quoted) and errors in red. Up/Down at the first/last input line step through earlier commands; Escape clears the input. |
| **Variables** | Name / Value / Type like the debugger's Variables view; expand rows for fields and properties. The last exception is `$exception`: *[inspect ›]* in the history selects it. |
| **Run / Stop / Reset** | ▶ runs now; ■ Stop kills a command that doesn't finish (the session restarts, variables are lost); ⟲ Reset clears history, variables and pending code and keeps the references. |
| **References** | Drag a project from Solution Explorer, or a `.csproj` / `.dll` from the file manager, onto the window. Or right-click a project › *Reference in C# REPL*. Or toolbar **+ Reference**: a solution project, *DLL…*, or *NuGet package…* (a picker: the solution's explicit `PackageReference`s, incl. central package management, listed first; a nuget.org search; a version list with a prerelease toggle; an exact id and version can be typed; `#r "nuget: Name, Version"` works too). Projects are built first. References survive Reset; ⚙ lists them with *Remove* (which resets the session). |
| **Change banner** | When a referenced project's sources are saved or it is built: *Rebuild* (empty session), *Rebuild and Replay* (reruns the successful commands), *Rebuild and Restore Variables* (runs the session's types and variables as code, visibly); *Ignore* (until the next change), *Ignore for This Session* (until Reset), *Ignore Forever* (switch back in ⚙ or the settings). |
| **To Single-File App** | Toolbar split button: saves the session as a .NET 10 file-based app in a new scratch file (*Scratches and Consoles*, `session-<date>.cs`, no dialog) and opens it. Results become `Console.WriteLine`, redeclared variables are renamed, references become `#:project` / `#:package` directives; it compiles and runs like the session. The second variant, *Exclude Commands That Threw*, leaves those out. |
| **Dump Variables** | Toolbar: a new scratch file (`variables-<date>.cs`, no dialog), a single-file app with `var name = <value as C#>;` for every variable; values that can't be written as code become comments. |
| **New C# Single-File App** | File › New (also ⚙): `<name>.cs` with `#!/usr/bin/env dotnet` and a `Console.WriteLine`. Run it with Rider's Run button or `dotnet run <name>.cs`. |
| **Run Single-File App** | Editor menu, or ▶ on the first line of a `.cs` file starting with `#!` or holding `#:` directives: `dotnet run <file>` in the Run tool window, in the file's folder. Rider 2026.3 shows its own Run button for file-based apps in the solution folder, but none for scratch files (exports and dumps). |
| **Add Project Reference Directive** | Editor menu / Generate in a single-file app: inserts `#:project <relative path>` for a solution project. Dropping a project onto a single-file app's editor does the same. |

**Settings** (*Settings › Tools › C# REPL*, or ⚙): run on Enter (auto-run default), variables table right of the
history or below the input (applied live), ignore changes to referenced projects forever.

## Build and install

```
./gradlew buildPlugin
```

builds `build/distributions/rider-csharp-repl-<version>.zip` (it runs `dotnet publish` of the host first, so the
.NET 10 SDK pinned in `global.json` must be installed). Install it with *Settings › Plugins › ⚙ › Install Plugin from
Disk…*.

To get every local build as an update instead:

```
./gradlew publishLocal [-PlocalRepoDir=<dir>]
```

copies the zip with a time-stamped version to `~/.lamarider/plugin-repo`, the local repository it shares with LamaRider.
It updates only its own entry in `updatePlugins.xml` and keeps its three newest zips; other plugins' entries and zips
stay. Add `file:///C:/Users/<you>/.lamarider/plugin-repo/updatePlugins.xml` (the printed URL) once in
*Settings › Plugins › ⚙ › Manage Plugin Repositories…*; it serves both LamaRider and C# REPL. `./gradlew runIde -PopenSolution=samples/SampleSolution/SampleSolution.sln`
starts a sandbox Rider with the plugin.

## Tests

```
dotnet test CSharpRepl.sln   # host: completeness, auto-;, completion, variables, references, dump and export round trips
./gradlew unitTest           # frontend logic without an IDE
./gradlew uiTest             # a real Rider on samples/SampleSolution (slow; Windows)
```

`uiTest` starts the Rider in `CSHARPREPL_RIDER` (default `%LOCALAPPDATA%\Programs\Rider`) with the built plugin, runs
`var r = 0`, checks the variables table, references the sample's `Greeter` project from Solution Explorer and calls it;
checks completion (the lookup, Enter/Escape while it shows) and the input's and history's colors; drops Solution
Explorer's own drag object for `Greeter` onto the REPL and onto a single-file app; exports both split-button variants and
runs them with `dotnet run`; and runs an export with *Run Single-File App*. Screenshots go to `build/screenshots`.

## Layout

| Path | What |
|---|---|
| `src/host/CSharpRepl.Host` | The REPL host: a `net8.0` console app (Roslyn scripting) talking JSON lines on stdin/stdout |
| `src/host/CSharpRepl.Host.Tests` | Host tests |
| `src/rider/main` | The Rider plugin (Kotlin, frontend only): tool window, actions, references, single-file apps, settings |
| `src/rider/test`, `src/rider/uiTest` | Frontend unit tests, UI smoke test |
| `samples/SampleSolution` | A solution with a `Greeter` library to try references |
| `docs/plugin-description.html` | Marketplace description (copied into `plugin.xml` at build time) |

## License

[GPL-3.0](LICENSE)
