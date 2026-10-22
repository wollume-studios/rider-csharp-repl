namespace CSharpRepl.Host.Tests;

public class ClassifierTests
{
    /// <summary>The kind of every occurrence of [word] (as a whole identifier) in [code].</summary>
    private static string?[] KindsOf(IReadOnlyList<SemanticSpan> spans, string code, string word)
    {
        var kinds = new List<string?>();
        for (var i = code.IndexOf(word, StringComparison.Ordinal); i >= 0; i = code.IndexOf(word, i + 1, StringComparison.Ordinal))
        {
            var before = i > 0 ? code[i - 1] : ' ';
            var after = i + word.Length < code.Length ? code[i + word.Length] : ' ';
            if (char.IsLetterOrDigit(before) || before == '_' || char.IsLetterOrDigit(after) || after == '_') continue;
            kinds.Add(spans.SingleOrDefault(s => s.Start == i && s.Length == word.Length)?.Kind);
        }
        return kinds.ToArray();
    }

    private static string? KindOf(ReplEngine engine, string code, string word) => Assert.Single(KindsOf(engine.Classify(code), code, word).Distinct());

    [Fact]
    public void MethodsPropertiesTypesAndNamespaces()
    {
        var engine = new ReplEngine();
        const string code = "var list = new List<int> { 1, 2 }; var n = list.Select(x => x * 2).Sum() + \"ab\".Trim().Length; Console.WriteLine(n); var sb = new System.Text.StringBuilder();";
        Assert.Equal("extensionMethod", KindOf(engine, code, "Select"));
        Assert.Equal("extensionMethod", KindOf(engine, code, "Sum"));
        Assert.Equal("method", KindOf(engine, code, "Trim"));
        Assert.Equal("staticMethod", KindOf(engine, code, "WriteLine"));
        Assert.Equal("property", KindOf(engine, code, "Length"));
        Assert.Equal("parameter", KindOf(engine, code, "x")); // the lambda parameter, declared and used
        Assert.Equal("class", KindOf(engine, code, "List"));
        Assert.Equal("staticClass", KindOf(engine, code, "Console"));
        Assert.Equal("class", KindOf(engine, code, "StringBuilder"));
        Assert.Equal("namespace", KindOf(engine, code, "System"));
        Assert.Equal("namespace", KindOf(engine, code, "Text"));
        Assert.Equal("local", KindOf(engine, code, "list")); // a top-level variable: declared and used
        Assert.Null(KindOf(engine, code, "var")); // stays a keyword
    }

    [Fact]
    public void LocalsParametersAndDeclarations()
    {
        var engine = new ReplEngine();
        const string code = "int Twice(int value) { var doubled = value * 2; return doubled; } enum Color { Red } const int Max = 3; var c = Color.Red; var t = Twice(Max);";
        Assert.Equal(["methodDeclaration", "method"], KindsOf(engine.Classify(code), code, "Twice"));
        Assert.Equal("parameter", KindOf(engine, code, "value"));
        Assert.Equal("local", KindOf(engine, code, "doubled"));
        Assert.Equal("enum", KindOf(engine, code, "Color"));
        Assert.Equal("enumMember", KindOf(engine, code, "Red"));
        Assert.Equal("constant", KindOf(engine, code, "Max"));
    }

    [Fact]
    public async Task VariablesAndMethodsOfEarlierSubmissions()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var abc = 1; int Twice(int x) => 2 * x; class Point { public int X; public static int Count; }");
        const string code = "Twice(abc) + new Point().X + Point.Count";
        Assert.Equal("local", KindOf(engine, code, "abc"));
        Assert.Equal("method", KindOf(engine, code, "Twice"));
        Assert.Equal("class", KindOf(engine, code, "Point"));
        Assert.Equal("field", KindOf(engine, code, "X"));
        Assert.Equal("staticField", KindOf(engine, code, "Count"));
        Assert.Null(KindOf(engine, "nothing + 1", "nothing")); // unresolved: no class
    }

    [Fact]
    public async Task RunAnswersTheSpansOfTheCodeAsItRan()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var abc = 1;");
        const string code = "var abc = \"redeclared\"; abc.Length";
        var r = await engine.RunAsync(code);
        Assert.Equal(["local", "local"], KindsOf(r.Spans, code, "abc"));
        Assert.Equal(["property"], KindsOf(r.Spans, code, "Length")); // abc is the string declared in this very submission
    }

    [Fact]
    public async Task ReferencedProjectTypes()
    {
        var engine = new ReplEngine();
        await engine.AddReferenceAsync("project", CompleterTests.Fixture("SampleLib"), null, null);
        const string code = "SampleLib.Greeter.Hi(new SampleLib.Person { Name = \"x\" }.Name)";
        Assert.Equal("namespace", KindOf(engine, code, "SampleLib"));
        Assert.Equal("staticClass", KindOf(engine, code, "Greeter"));
        Assert.Equal("staticMethod", KindOf(engine, code, "Hi"));
        Assert.Equal("class", KindOf(engine, code, "Person"));
        Assert.Equal("property", KindOf(engine, code, "Name"));
    }

    [Fact]
    public async Task NeverRunsUserCode()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var hits = 0; int Hit() => ++hits;");
        engine.Classify("Hit(); Hit();");
        Assert.Equal("0", engine.GetVariables().Single(v => v.Name == "hits").Value);
    }
}
