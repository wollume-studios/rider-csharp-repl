# Changelog

## Unreleased
- Code completion in the input while typing (and on Ctrl+Space): the session's variables, methods and types, members after a `.`, namespaces, types of referenced projects and packages, and keywords. Enter/Tab insert the item and never run the input while the popup shows; Escape closes it. Completion only compiles, it never runs code, and it answers while a command is still running.
- *To Single-File App* and *Dump Variables* write straight into a new scratch file (`session-<date>.cs`, `variables-<date>.cs` in Scratches and Consoles), no save dialog.

## 0.1.0
- C# REPL tool window, a replacement for Rider's C# Interactive (removed in Rider 2026.3): a persistent Roslyn scripting session in its own .NET process, so a crash, endless loop or `Environment.Exit` in REPL code never takes Rider down.
- Auto-run mode (toggle, remembered): Enter runs the command and adds a missing `;` when that completes it; Shift+Enter inserts a new line. Manual mode: Enter collects lines until the command is complete, Shift+Enter runs now.
- Pending box: in manual mode the code waiting for a complete command is always visible, with a button to discard it. Nothing runs that is not on screen.
- History with C# highlighting, console output, results and errors; Up/Down in the input step through earlier commands; Escape clears the input.
- Variables table (Name / Value / Type) like the debugger's, with expandable fields and properties; the last exception is `$exception`, reachable from the history's `[inspect ›]` link. Right of the history or below the input.
- Run / Stop (a command that does not finish is killed, the session restarts), Reset (history, variables and pending code cleared, references kept).
- References: drag a project from Solution Explorer or a `.csproj` / `.dll` from the file manager onto the window, *Reference in C# REPL* on project nodes, or *+ Reference* (solution projects, DLL, NuGet package by name and version). Projects are built first; references survive Reset and are listed in the ⚙ menu with Remove.
- Banner when a referenced project changes or is built: Rebuild (empty session), Rebuild and replay, Rebuild and restore variables; Ignore, Ignore for this session, Ignore forever.
- *To Single-File App*: the session as a .NET 10 file-based app (`#:project` / `#:package` directives, results printed, redeclared variables renamed) that compiles and runs like the session; a variant leaves out commands that threw.
- *Dump Variables*: a new single-file app with the current variables as C# code.
- *New C# Single-File App* (File › New) and *Add Project Reference Directive* (editor menu, Generate); dropping a project onto a single-file app's editor inserts `#:project`.
- Settings › Tools › C# REPL: auto-run default, variables position, ignore project changes forever.
