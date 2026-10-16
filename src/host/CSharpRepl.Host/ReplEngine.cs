using System.Collections.Immutable;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Scripting;
using Microsoft.CodeAnalysis.Scripting;

namespace CSharpRepl.Host;

public enum SubmissionStatus { Ok, Threw, CompileError }

public sealed record Submission(string Code, SubmissionStatus Status);

public sealed record RunResult(SubmissionStatus Status, string Output, string? Result, string[] Diagnostics, string? Exception);

public sealed record VarInfo(string Name, string Type, string Value, int? Handle);

public sealed class ReplEngine
{
    // Default references: the shared framework's own assemblies (the scripting default set is too small on .NET Core).
    private static readonly ScriptOptions Options = ScriptOptions.Default
        .WithReferences(FrameworkReferences())
        .WithImports("System", "System.Collections.Generic", "System.IO", "System.Linq", "System.Text", "System.Threading.Tasks");

    private readonly SemaphoreSlim _gate = new(1, 1); // Console redirection is process-wide: one run at a time
    private readonly List<Submission> _submissions = new();
    private ScriptState<object>? _state;
    private Exception? _lastException;
    private readonly Dictionary<int, object> _handles = new(); // expandable values from the last variables/expand answers; cleared on every run
    private int _nextHandle;

    private static IEnumerable<MetadataReference> FrameworkReferences() =>
        Directory.EnumerateFiles(RuntimeEnvironment.GetRuntimeDirectory(), "*.dll")
            .Where(f => Path.GetFileName(f) is var n && (n.StartsWith("System.") || n is "netstandard.dll" or "Microsoft.CSharp.dll" or "mscorlib.dll"))
            .Where(f => { try { AssemblyName.GetAssemblyName(f); return true; } catch (BadImageFormatException) { return false; } })
            .Select(f => MetadataReference.CreateFromFile(f));

    public IReadOnlyList<Submission> Submissions { get { lock (_submissions) return _submissions.ToArray(); } }

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
            ScriptState<object> next;
            try
            {
                next = _state == null
                    ? await CSharpScript.Create(code, Options).RunAsync(null, _ => true)
                    : await _state.ContinueWithAsync(code, null, _ => true);
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
