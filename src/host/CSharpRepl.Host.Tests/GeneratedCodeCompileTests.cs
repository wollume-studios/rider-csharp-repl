using System.Collections;
using System.Dynamic;
using CSharpRepl.Host;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

// Dictionaries that implement IDictionary<string,int> only explicitly; the subclasses expose different public surfaces.
public abstract class ExplicitDict : IDictionary<string, int>
{
    protected readonly Dictionary<string, int> D = new();
    int IDictionary<string, int>.this[string key] { get => D[key]; set => D[key] = value; }
    ICollection<string> IDictionary<string, int>.Keys => D.Keys;
    ICollection<int> IDictionary<string, int>.Values => D.Values;
    int ICollection<KeyValuePair<string, int>>.Count => D.Count;
    bool ICollection<KeyValuePair<string, int>>.IsReadOnly => false;
    void IDictionary<string, int>.Add(string key, int value) => D.Add(key, value);
    void ICollection<KeyValuePair<string, int>>.Add(KeyValuePair<string, int> item) => D.Add(item.Key, item.Value);
    void ICollection<KeyValuePair<string, int>>.Clear() => D.Clear();
    bool ICollection<KeyValuePair<string, int>>.Contains(KeyValuePair<string, int> item) => D.Contains(item);
    bool IDictionary<string, int>.ContainsKey(string key) => D.ContainsKey(key);
    void ICollection<KeyValuePair<string, int>>.CopyTo(KeyValuePair<string, int>[] array, int arrayIndex) => ((ICollection<KeyValuePair<string, int>>)D).CopyTo(array, arrayIndex);
    bool IDictionary<string, int>.Remove(string key) => D.Remove(key);
    bool ICollection<KeyValuePair<string, int>>.Remove(KeyValuePair<string, int> item) => ((ICollection<KeyValuePair<string, int>>)D).Remove(item);
    bool IDictionary<string, int>.TryGetValue(string key, out int value) => D.TryGetValue(key, out value);
    IEnumerator<KeyValuePair<string, int>> IEnumerable<KeyValuePair<string, int>>.GetEnumerator() => D.GetEnumerator();
    IEnumerator IEnumerable.GetEnumerator() => D.GetEnumerator();
}

public class IndexerDict : ExplicitDict { public int this[string key] { get => D[key]; set => D[key] = value; } }

public class AddDict : ExplicitDict { public void Add(string key, int value) => D.Add(key, value); }

public class OpaqueDict : ExplicitDict;

public class Kw { public int @class { get; set; } public string? @event { get; set; } }

public record RecN(string? A, int? B);

public class GeneratedCodeCompileTests
{
    private static readonly MetadataReference[] References =
        ((string)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES")!).Split(Path.PathSeparator).Select(f => (MetadataReference)MetadataReference.CreateFromFile(f)).ToArray();

    /// <summary>Compiles the code as a file-based app's top-level program (usings as the SDK's implicit ones) and returns the errors.</summary>
    private static string[] Errors(string code)
    {
        var source = "using System; using System.Collections.Generic; using System.IO; using System.Linq; using System.Threading.Tasks;\n"
            + string.Join("\n", code.Split('\n').Where(l => !l.StartsWith("#!") && !l.StartsWith("#:")));
        return CSharpCompilation.Create("t", [CSharpSyntaxTree.ParseText(source)], References, new CSharpCompilationOptions(OutputKind.ConsoleApplication))
            .GetDiagnostics().Where(d => d.Severity == DiagnosticSeverity.Error).Select(d => d.ToString()).ToArray();
    }

    private static string Reproduced(object? value, Type? declared = null)
    {
        Assert.True(ValueToCode.TryToCode(value, declared ?? value!.GetType(), out var code), $"not reproducible: {value}");
        Assert.Empty(Errors($"var x = {code};"));
        return code;
    }

    [Fact]
    public void Collection_WithoutPublicAdd_IsNotReproducible()
    {
        Assert.False(ValueToCode.TryToCode(new LinkedList<int>([1]), typeof(LinkedList<int>), out _));
        dynamic expando = new ExpandoObject();
        expando.A = 1;
        Assert.False(ValueToCode.TryToCode((object)expando, typeof(ExpandoObject), out _));
        Assert.Equal("new HashSet<int> { 1 }", Reproduced(new HashSet<int> { 1 }));
    }

    [Fact]
    public void GenericOnlyDictionary_UsesIndexerOrAddOrIsNotReproducible()
    {
        var indexer = new IndexerDict { ["a"] = 1 };
        Assert.Equal("new IndexerDict { [\"a\"] = 1 }", Reproduced(indexer));

        var add = new AddDict();
        add.Add("a", 1);
        Assert.Equal("new AddDict { { \"a\", 1 } }", Reproduced(add));

        var opaque = (IDictionary<string, int>)new OpaqueDict();
        opaque.Add("a", 1);
        Assert.False(ValueToCode.TryToCode(opaque, typeof(OpaqueDict), out _));
    }

    [Fact]
    public void AnonymousType_NullMemberIsTyped()
    {
        Assert.Equal("new { Name = (string?)null, N = 1 }", Reproduced(new { Name = (string?)null, N = 1 }));
    }

    [Fact]
    public void Flags_WithUndefinedBits_FallBackToCast()
    {
        Assert.Equal("(Perm)(7)", Reproduced((Perm)7));
        Assert.Equal("(Perm.R | Perm.W)", Reproduced(Perm.R | Perm.W));
    }

    [Fact]
    public void KeywordNames_AreEscaped()
    {
        Assert.Equal("new Kw { @class = 1, @event = \"e\" }", Reproduced(new Kw { @class = 1, @event = "e" }));
    }

    [Fact]
    public void NullConstructorArguments_AreTyped()
    {
        Assert.Equal("new RecN((string?)null, (int?)null)", Reproduced(new RecN(null, null)));
    }

    [Fact]
    public void Collections_Over1000Elements_AreNotReproducible()
    {
        Assert.True(ValueToCode.TryToCode(Enumerable.Range(0, 1000).ToList(), typeof(List<int>), out _));
        Assert.False(ValueToCode.TryToCode(Enumerable.Range(0, 1001).ToList(), typeof(List<int>), out _));
        Assert.False(ValueToCode.TryToCode(new int[1001], typeof(int[]), out _));
        Assert.False(ValueToCode.TryToCode(Enumerable.Range(0, 1001).ToDictionary(i => i), typeof(Dictionary<int, int>), out _));
    }

    [Fact]
    public async Task Dump_CompilesAsFileBasedApp()
    {
        var engine = new ReplEngine();
        engine.AddReferences([typeof(Kw).Assembly.Location]);
        foreach (var code in new[]
        {
            "var args = 5;", "var @class = 1;", "var kw = new Kw { @class = 2 };", "var perm = (Perm)7;", "var anon = new { A = (string)null };",
            "var big = Enumerable.Range(0, 1001).ToList();", "var ll = new LinkedList<int>();",
        })
            Assert.Equal(SubmissionStatus.Ok, (await engine.RunAsync(code)).Status);

        var (text, bad) = engine.Dump(Path.Combine(Path.GetTempPath(), "app.cs"));

        Assert.Equal(["big", "ll"], bad);
        Assert.Contains("var args_ = 5;\n", text);
        Assert.Contains("var @class = 1;\n", text);
        Assert.Contains("// big: List<int> = Count = 1001 (not reproducible)\n", text);
        Assert.Empty(Errors(text));
    }
}
