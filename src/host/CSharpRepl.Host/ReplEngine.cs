using System.Collections.Immutable;
using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp.Scripting;
using Microsoft.CodeAnalysis.CSharp.Scripting.Hosting;
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

    private static IEnumerable<MetadataReference> FrameworkReferences() =>
        Directory.EnumerateFiles(RuntimeEnvironment.GetRuntimeDirectory(), "*.dll")
            .Where(f => Path.GetFileName(f) is var n && (n.StartsWith("System.") || n is "netstandard.dll" or "Microsoft.CSharp.dll" or "mscorlib.dll"))
            .Where(f => { try { AssemblyName.GetAssemblyName(f); return true; } catch (BadImageFormatException) { return false; } })
            .Select(f => MetadataReference.CreateFromFile(f));

    public IReadOnlyList<Submission> Submissions { get { lock (_submissions) return _submissions.ToArray(); } }

    public async Task<RunResult> RunAsync(string code)
    {
        await _gate.WaitAsync();
        var (oldOut, oldErr) = (Console.Out, Console.Error);
        var output = new StringWriter(); // Console.SetOut wraps it in a synchronized writer
        Console.SetOut(output);
        Console.SetError(output);
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
            var result = next.ReturnValue is { } v ? CSharpObjectFormatter.Instance.FormatObject(v) : null;
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
            .Select(v => new VarInfo(v.Name, TypeName(v.Type), CSharpObjectFormatter.Instance.FormatObject(v.Value), null))
            .ToList();
        if (_lastException is { } ex)
            vars.Add(new VarInfo("$exception", TypeName(ex.GetType()), CSharpObjectFormatter.Instance.FormatObject(ex), null));
        return vars;
    }

    private void Record(string code, SubmissionStatus status)
    {
        lock (_submissions) _submissions.Add(new Submission(code, status));
    }

    private static readonly Dictionary<Type, string> Aliases = new()
    {
        [typeof(bool)] = "bool", [typeof(byte)] = "byte", [typeof(sbyte)] = "sbyte", [typeof(char)] = "char",
        [typeof(decimal)] = "decimal", [typeof(double)] = "double", [typeof(float)] = "float", [typeof(int)] = "int",
        [typeof(uint)] = "uint", [typeof(long)] = "long", [typeof(ulong)] = "ulong", [typeof(short)] = "short",
        [typeof(ushort)] = "ushort", [typeof(object)] = "object", [typeof(string)] = "string", [typeof(void)] = "void",
    };

    private static string TypeName(Type t)
    {
        if (Aliases.TryGetValue(t, out var alias)) return alias;
        if (t.IsArray) return TypeName(t.GetElementType()!) + "[" + new string(',', t.GetArrayRank() - 1) + "]";
        if (!t.IsGenericType) return t.Name;
        var args = string.Join(", ", t.GetGenericArguments().Select(TypeName));
        return t.GetGenericTypeDefinition() == typeof(Nullable<>) ? args + "?" : $"{t.Name[..t.Name.IndexOf('`')]}<{args}>";
    }
}
