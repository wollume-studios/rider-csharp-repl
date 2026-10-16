using System.Collections.Immutable;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.Loader;
using System.Text.RegularExpressions;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.CSharp.Scripting;
using Microsoft.CodeAnalysis.Scripting;

namespace CSharpRepl.Host;

public enum SubmissionStatus { Ok, Threw, CompileError }

public sealed record Submission(string Code, SubmissionStatus Status);

public sealed record RunResult(SubmissionStatus Status, string Output, string? Result, string[] Diagnostics, string? Exception);

public sealed record VarInfo(string Name, string Type, string Value, int? Handle);

public sealed record ReferenceInfo(string Kind, string PathOrName, string? Version);

public sealed partial class ReplEngine
{
    private static readonly string[] FrameworkFiles = Directory.EnumerateFiles(RuntimeEnvironment.GetRuntimeDirectory(), "*.dll")
        .Where(f => Path.GetFileName(f) is var n && (n.StartsWith("System.") || n is "netstandard.dll" or "Microsoft.CSharp.dll" or "mscorlib.dll"))
        .Where(ReferenceBuilder.IsManaged)
        .ToArray();

    private static readonly HashSet<string> FrameworkNames =
        FrameworkFiles.Select(f => Path.GetFileNameWithoutExtension(f)).ToHashSet(StringComparer.OrdinalIgnoreCase);

    // Directories of added references; a reference's own dependencies (e.g. a NuGet package's) are resolved from here.
    private static readonly List<string> ProbeDirs = new();

    static ReplEngine() => AssemblyLoadContext.Default.Resolving += (ctx, name) =>
    {
        string[] dirs;
        lock (ProbeDirs) dirs = ProbeDirs.ToArray();
        foreach (var dir in dirs)
            if (Path.Combine(dir, name.Name + ".dll") is var p && File.Exists(p)) return ctx.LoadFromAssemblyPath(p);
        return null;
    };

    // Default references: the shared framework's own assemblies (the scripting default set is too small on .NET Core).
    private ScriptOptions _options = ScriptOptions.Default
        .WithReferences(FrameworkFiles.Select(f => MetadataReference.CreateFromFile(f)))
        .WithImports("System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Text", "System.Threading.Tasks");

    private readonly List<ReferenceInfo> _references = new();

    [GeneratedRegex("""^[ \t]*#r[ \t]+"nuget:[ \t]*([^,"]+?)[ \t]*,[ \t]*([^"]+?)[ \t]*"[ \t]*\r?$""", RegexOptions.Multiline)]
    private static partial Regex NuGetDirective();

    private readonly SemaphoreSlim _gate = new(1, 1); // Console redirection is process-wide: one run at a time
    private readonly List<Submission> _submissions = new();
    private ScriptState<object>? _state;
    private Exception? _lastException;
    private readonly Dictionary<int, object> _handles = new(); // expandable values from the last variables/expand answers; cleared on every run
    private int _nextHandle;

    public IReadOnlyList<ReferenceInfo> References { get { lock (_references) return _references.ToArray(); } }

    public IReadOnlyList<Submission> Submissions { get { lock (_submissions) return _submissions.ToArray(); } }

    /// <summary>Builds (project/nuget) or locates (dll) the reference and adds it to the session. Callers must not run this concurrently with a submission.</summary>
    public async Task<BuiltReference> AddReferenceAsync(string kind, string? path, string? name, string? version)
    {
        var (built, info) = kind switch
        {
            "project" => (await ReferenceBuilder.BuildProject(Required(path, "path")), new ReferenceInfo(kind, Path.GetFullPath(path!), null)),
            "dll" => (ReferenceBuilder.Dll(Required(path, "path")), new ReferenceInfo(kind, Path.GetFullPath(path!), null)),
            "nuget" => (await ReferenceBuilder.BuildNuGet(Required(name, "name"), Required(version, "version")), new ReferenceInfo(kind, name!, version)),
            _ => throw new ReferenceException($"unknown reference kind: {kind}"),
        };
        AddReferences(built.Assemblies);
        lock (_references) if (!_references.Contains(info)) _references.Add(info);
        return built;
    }

    private static string Required(string? value, string field) =>
        string.IsNullOrWhiteSpace(value) ? throw new ReferenceException($"{field} required") : value;

    /// <summary>Adds assemblies to later submissions; ones that share a name with a framework assembly are skipped (the host's own runtime wins).</summary>
    public void AddReferences(IEnumerable<string> assemblies)
    {
        var files = assemblies.Where(a => !FrameworkNames.Contains(Path.GetFileNameWithoutExtension(a))).ToArray();
        lock (ProbeDirs) ProbeDirs.AddRange(files.Select(f => Path.GetDirectoryName(f)!).Distinct().Except(ProbeDirs).ToArray());
        _options = _options.AddReferences(files.Select(f => MetadataReference.CreateFromFile(f)));
    }

    public async Task<RunResult> RunAsync(string code)
    {
        await _gate.WaitAsync();
        lock (_handles) _handles.Clear();
        var (oldOut, oldErr) = (Console.Out, Console.Error);
        var output = new StringWriter();
        var sync = TextWriter.Synchronized(output); // Out and Error share one lock so interleaved writes stay whole
        Console.SetOut(sync);
        Console.SetError(sync);
        try
        {
            // #r "nuget: Name, Version" is ours, not Roslyn's: build the package, then hand Roslyn the code without the line (kept blank so diagnostics keep their line numbers).
            foreach (Match m in NuGetDirective().Matches(code))
            {
                try { await AddReferenceAsync("nuget", null, m.Groups[1].Value, m.Groups[2].Value); }
                catch (ReferenceException e) { return new RunResult(SubmissionStatus.CompileError, "", null, [e.Message], null); }
            }
            code = NuGetDirective().Replace(code, "");
            if (string.IsNullOrWhiteSpace(code)) return new RunResult(SubmissionStatus.Ok, "", null, [], null);

            ScriptState<object> next;
            try
            {
                next = _state == null
                    ? await CSharpScript.Create(code, _options).RunAsync(null, _ => true)
                    : await _state.ContinueWithAsync(code, _options, _ => true);
            }
            catch (CompilationErrorException e)
            {
                _lastException = null;
                Record(code, SubmissionStatus.CompileError);
                return new RunResult(SubmissionStatus.CompileError, output.ToString(), null,
                    e.Diagnostics.Select(d => d.ToString()).ToArray(), null);
            }

            _state = next;
            _lastException = next.Exception;
            if (next.Exception is { } ex)
            {
                Record(code, SubmissionStatus.Threw);
                return new RunResult(SubmissionStatus.Threw, output.ToString(), null, [], $"{ex.GetType().Name}: {ex.Message}");
            }

            Record(code, SubmissionStatus.Ok);
            // ponytail: a trailing expression that evaluates to null is indistinguishable from no expression
            var result = next.ReturnValue is { } v ? ValueFormatter.Display(v) : null;
            return new RunResult(SubmissionStatus.Ok, output.ToString(), result, [], null);
        }
        finally
        {
            Console.SetOut(oldOut);
            Console.SetError(oldErr);
            _gate.Release();
        }
    }

    private IEnumerable<(string Name, Type Type, object? Value)> Declared() =>
        (_state?.Variables ?? ImmutableArray<ScriptVariable>.Empty)
            .GroupBy(v => v.Name)
            .Select(g => g.Last())
            .Select(v => (v.Name, v.Type, (object?)v.Value));

    public IReadOnlyList<VarInfo> GetVariables()
    {
        var vars = Declared().Select(v => Describe(v.Name, v.Type, v.Value)).ToList();
        if (_lastException is { } ex) vars.Add(Describe("$exception", ex.GetType(), ex));
        return vars;
    }

    /// <summary>A file-based app that declares the session's variables (`#!`, directives, then `var x = ...;`). Values that can't be written as code become comments. Reads live values: call it on the sequential worker.</summary>
    public (string Text, string[] NotReproducible) Dump(string filePath)
    {
        var (lines, bad) = VariableLines(replTypes: false);
        var directives = Directives.For(References, filePath);
        return ("#!/usr/bin/env dotnet\n" + (directives.Length > 0 ? directives + "\n" : "") + "\n" + string.Concat(lines.Select(l => l + "\n")), bad);
    }

    /// <summary>Script for a fresh session: the usings and type declarations of the successful submissions, then the `var` lines.</summary>
    public (string Code, string[] NotReproducible) RestoreScript()
    {
        var usings = new List<string>();
        var types = new List<(string Key, string Text)>();
        // A submission that threw still declared its types; only compile errors leave nothing behind.
        foreach (var submission in Submissions.Where(s => s.Status != SubmissionStatus.CompileError))
        {
            var root = CSharpSyntaxTree.ParseText(submission.Code, new CSharpParseOptions(kind: SourceCodeKind.Script)).GetCompilationUnitRoot();
            foreach (var u in root.Usings) if (!usings.Contains(u.ToString())) usings.Add(u.ToString());
            foreach (var m in root.Members)
            {
                var key = m switch
                {
                    TypeDeclarationSyntax t => $"{t.Identifier.Text}`{t.Arity}",
                    EnumDeclarationSyntax e => e.Identifier.Text + "`0",
                    DelegateDeclarationSyntax d => $"{d.Identifier.Text}`{d.Arity}",
                    _ => null,
                };
                if (key == null) continue;
                types.RemoveAll(t => t.Key == key); // a redeclared type replaces the earlier one
                types.Add((key, m.ToFullString().Trim()));
            }
        }
        var (lines, bad) = VariableLines(replTypes: true);
        return (string.Join("\n", usings.Concat(types.Select(t => t.Text)).Concat(lines)), bad);
    }

    private (List<string> Lines, string[] NotReproducible) VariableLines(bool replTypes)
    {
        var lines = new List<string>();
        var bad = new List<string>();
        foreach (var (name, type, value) in Declared())
        {
            // In a dump the variables become top-level statements, where `args` already exists (CS0136), so it is renamed.
            var declared = ValueToCode.Ident(!replTypes && name == "args" ? "args_" : name);
            if (ValueToCode.TryToCode(value, type, out var code, replTypes)) lines.Add($"var {declared} = {code};");
            else
            {
                bad.Add(name);
                // The display can span lines (a custom ToString); anything after a line break would stop being a comment.
                var shown = Regex.Replace(ValueFormatter.Display(value), "[\r\n\u0085\u2028\u2029]+", " ");
                lines.Add($"// {name}: {ValueFormatter.TypeName(type)} = {shown} (not reproducible)");
            }
        }
        return (lines, bad.ToArray());
    }

    public IReadOnlyList<VarInfo> Expand(int handle)
    {
        object? target;
        lock (_handles)
            if (!_handles.TryGetValue(handle, out target)) throw new ArgumentException($"unknown handle {handle}");
        return ValueFormatter.Children(target).Select(c => Describe(c.Name, c.Type, c.Value)).ToList();
    }

    private VarInfo Describe(string name, Type type, object? value)
    {
        int? handle = null;
        if (ValueFormatter.HasChildren(value))
            lock (_handles) { handle = ++_nextHandle; _handles[handle.Value] = value!; }
        return new VarInfo(name, value is Note ? "" : ValueFormatter.TypeName(type), ValueFormatter.Display(value), handle);
    }

    private void Record(string code, SubmissionStatus status)
    {
        lock (_submissions) _submissions.Add(new Submission(code, status));
    }
}
