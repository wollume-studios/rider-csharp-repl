namespace CSharpRepl.Host.Tests;

public class CompleterTests
{
    private static CompleteResponse At(ReplEngine engine, string codeWithCaret)
    {
        var position = codeWithCaret.IndexOf('|');
        return engine.Complete(codeWithCaret.Remove(position, 1), position);
    }

    private static string[] Labels(CompleteResponse r) => r.Items.Select(i => i.Label).ToArray();

    [Fact]
    public async Task VariablesOfEarlierSubmissions()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var abc = 1;");
        await engine.RunAsync("int Twice(int x) => 2 * x; class Point { public int X; }");
        var r = At(engine, "ab|");
        var abc = Assert.Single(r.Items, i => i.Label == "abc");
        Assert.Equal("variable", abc.Kind);
        Assert.Equal("int", abc.Detail);
        Assert.Equal(0, r.Start);
        Assert.Equal(2, r.Length);
        Assert.Contains("Twice", Labels(At(engine, "Tw|")));
        Assert.Contains("Point", Labels(At(engine, "new Po|")));
        Assert.Contains("X", Labels(At(engine, "new Point().|")));
    }

    [Fact]
    public void StringMembers()
    {
        var r = At(new ReplEngine(), "\"abc\".|");
        Assert.Equal(6, r.Start);
        Assert.Equal(0, r.Length);
        Assert.Contains("Length", Labels(r));
        Assert.Contains("Substring", Labels(r));
        Assert.Contains("Select", Labels(r)); // LINQ extension method via the default imports
        Assert.DoesNotContain("IsNullOrEmpty", Labels(r)); // static
        Assert.DoesNotContain("abstract", Labels(r)); // no keywords after a dot
    }

    [Fact]
    public void StaticMembersOfAType_WithPrefixSpanAndOverloads()
    {
        var r = At(new ReplEngine(), "Console.Wr|");
        Assert.Equal(8, r.Start);
        Assert.Equal(2, r.Length);
        var writeLine = Assert.Single(r.Items, i => i.Label == "WriteLine");
        Assert.Equal("method", writeLine.Kind);
        Assert.Contains("overloads", writeLine.Detail);
        Assert.All(r.Items, i => Assert.StartsWith("wr", i.Label, StringComparison.OrdinalIgnoreCase));
    }

    [Fact]
    public void NamespaceMembers()
    {
        var labels = Labels(At(new ReplEngine(), "System.Text.|"));
        Assert.Contains("StringBuilder", labels);
        Assert.Contains("RegularExpressions", labels);
        Assert.Equal("namespace", At(new ReplEngine(), "System.Text.Reg|").Items.Single(i => i.Label == "RegularExpressions").Kind);
    }

    [Fact]
    public void Keywords_AndCamelHumps_AndCap()
    {
        var engine = new ReplEngine();
        Assert.Contains("foreach", Labels(At(engine, "fore|")));
        var humps = Labels(At(engine, "Console.WL|"));
        Assert.Contains("WriteLine", humps);
        Assert.True(At(engine, "|").Items.Count <= Completer.Cap);
        Assert.Equal(-1, Completer.Rank("WriteLine", "WX"));
        Assert.Equal(2, Completer.Rank("ReadAllText", "rat"));
        Assert.Equal(0, Completer.Rank("abc", "ab"));
        Assert.Equal(1, Completer.Rank("Abc", "ab"));
    }

    [Fact]
    public void PrefixSpan_InTheMiddleOfCode()
    {
        var r = At(new ReplEngine(), "var n = Math.Ma|x(1, 2);");
        Assert.Equal(13, r.Start);
        Assert.Equal(2, r.Length);
        Assert.Contains("Max", Labels(r));
    }

    [Fact]
    public void NothingInCommentsOrStrings()
    {
        var engine = new ReplEngine();
        Assert.Empty(At(engine, "// Con|").Items);
        Assert.Empty(At(engine, "var s = \"Con|").Items);
        Assert.Empty(At(engine, "var s = \"a.|").Items);
    }

    [Fact]
    public async Task ReferencedProjectTypes()
    {
        var engine = new ReplEngine();
        await engine.AddReferenceAsync("project", Fixture("SampleLib"), null, null);
        Assert.Contains("Greeter", Labels(At(engine, "SampleLib.|")));
        Assert.Contains("Hi", Labels(At(engine, "SampleLib.Greeter.|")));
    }

    [Fact]
    public async Task NeverRunsUserCode()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var hits = 0; int Hit() => ++hits;");
        At(engine, "Hit().|");
        Assert.Equal("0", engine.GetVariables().Single(v => v.Name == "hits").Value);
    }

    internal static string Fixture(string name)
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
            if (File.Exists(Path.Combine(d.FullName, "Fixtures", name, name + ".csproj")))
                return Path.Combine(d.FullName, "Fixtures", name, name + ".csproj");
        throw new FileNotFoundException(name);
    }
}
