using System.Collections.Immutable;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.Loader;
using System.Text.RegularExpressions;
using Microsoft.CodeAnalysis;
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

    public IReadOnlyList<VarInfo> GetVariables()
    {
        var vars = (_state?.Variables ?? ImmutableArray<ScriptVariable>.Empty)
            .GroupBy(v => v.Name)
            .Select(g => g.Last())
            .Select(v => Describe(v.Name, v.Type, v.Value))
            .ToList();
        if (_lastException is { } ex) vars.Add(Describe("$exception", ex.GetType(), ex));
        return vars;
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
