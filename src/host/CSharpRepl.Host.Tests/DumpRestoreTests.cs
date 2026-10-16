namespace CSharpRepl.Host.Tests;

public class DumpRestoreTests
{
    private static string SampleLib()
    {
        for (var d = new DirectoryInfo(AppContext.BaseDirectory); d != null; d = d.Parent)
            if (File.Exists(Path.Combine(d.FullName, "CSharpRepl.Host.Tests.csproj")))
                return Path.Combine(d.FullName, "Fixtures", "SampleLib", "SampleLib.csproj");
        throw new InvalidOperationException("Fixtures directory not found");
    }

    private static async Task<ReplEngine> Run(ReplEngine engine, params string[] submissions)
    {
        foreach (var s in submissions) Assert.Equal(SubmissionStatus.Ok, (await engine.RunAsync(s)).Status);
        return engine;
    }

    private static string Strip(string text) =>
        string.Join("\n", text.Split('\n').Where(l => !l.StartsWith("#!") && !l.StartsWith("#:")));

    [Fact]
    public async Task Dump_CompilesAndReproduces()
    {
        var engine = new ReplEngine();
        await engine.AddReferenceAsync("project", SampleLib(), null, null);
        await Run(engine,
            "var i = 42;",
            "var s = \"tab\\t \\\"q\\\" \\\\ \\u2028 \\ud800 😀 \\0\";", // Review Focus 4: escapes, line separator, lone surrogate, astral char
            "var l = new List<int> { 1, 2, 3 };",
            "var p = new SampleLib.Person { Name = \"Ann\", Age = 7 };",
            "object o = 5;",
            "string n = null;",
            "var s2 = new MemoryStream();");

        var (text, bad) = engine.Dump(Path.Combine(Path.GetTempPath(), "dump-test", "app.cs"));

        Assert.StartsWith("#!/usr/bin/env dotnet\n#:project ", text);
        Assert.Contains(".csproj\n\nvar i = 42;\n", text);
        Assert.Contains("// s2: MemoryStream = MemoryStream (not reproducible)\n", text);
        Assert.Equal(["s2"], bad);
        Assert.DoesNotContain("$exception", text);

        var copy = new ReplEngine();
        await copy.AddReferenceAsync("project", SampleLib(), null, null);
        var r = await copy.RunAsync(Strip(text));
        Assert.Equal(SubmissionStatus.Ok, r.Status);
        Assert.Equal(["i", "s", "l", "p", "o", "n"], copy.GetVariables().Select(v => v.Name));
        foreach (var name in new[] { "i", "s", "l", "o", "n" })
        {
            var (a, b) = (engine.GetVariables().Single(v => v.Name == name), copy.GetVariables().Single(v => v.Name == name));
            Assert.Equal((a.Type, a.Value), (b.Type, b.Value));
        }
        Assert.Equal("\"Ann7\"", (await copy.RunAsync("p.Name + p.Age")).Result);
        Assert.Equal("true", (await copy.RunAsync("s == \"tab\\t \\\"q\\\" \\\\ \\u2028 \\ud800 😀 \\0\"")).Result);
    }

    [Fact]
    public async Task Dump_ReplDeclaredTypeIsNotReproducible()
    {
        var engine = await Run(new ReplEngine(), "class P { public int X {get;set;} }", "var p = new P { X = 3 };", "var k = 1;");
        var (text, bad) = engine.Dump(Path.Combine(Path.GetTempPath(), "app.cs"));
        Assert.Equal(["p"], bad);
        Assert.Contains("var k = 1;", text);
    }

    [Fact]
    public void Directives_ProjectIsRelative_NuGetAndDll()
    {
        var dir = Path.Combine(Path.GetTempPath(), "dump-test");
        var lib = Path.Combine(dir, "lib", "My Lib.dll");
        var text = Directives.For(
            [new("project", Path.Combine(dir, "x", "X.csproj"), null), new("nuget", "Humanizer.Core", "2.14.1"), new("dll", lib, null)],
            Path.Combine(dir, "app.cs"));
        Assert.Equal($"#:project x/X.csproj\n#:package Humanizer.Core@2.14.1\n// reference DLL manually: {lib}", text);
    }

    [Fact]
    public async Task RestoreScript_IncludesReplTypes()
    {
        var engine = await Run(new ReplEngine(),
            "using System.Text;",
            "enum E { A, B }",
            "delegate int D(int x);",
            "class P { public int X {get;set;} public E K {get;set;} }",
            "class P { public int X {get;set;} public E K {get;set;} public string Tag {get;set;} }",
            "var p = new P { X = 3, K = E.B };",
            "var m = new MemoryStream();");
        await engine.RunAsync("class Broken { int }"); // compile error: not restored

        var (code, bad) = engine.RestoreScript();
        Assert.Equal(["m"], bad);
        Assert.DoesNotContain("Broken", code);
        Assert.Equal(1, code.Split("class P").Length - 1); // redeclared type is emitted once
        Assert.Contains("var p = new P { X = 3, K = E.B, Tag = null };", code);
        Assert.True(code.IndexOf("class P", StringComparison.Ordinal) < code.IndexOf("var p =", StringComparison.Ordinal));

        var fresh = new ReplEngine();
        Assert.Equal(SubmissionStatus.Ok, (await fresh.RunAsync(code)).Status);
        Assert.Equal("3", (await fresh.RunAsync("p.X")).Result);
        Assert.Equal("B", (await fresh.RunAsync("p.K")).Result);
    }

    [Fact]
    public async Task Protocol_DumpAndRestoreScript()
    {
        var engine = await Run(new ReplEngine(), "var x = 1;");
        var input = new StringReader("{\"id\":1,\"cmd\":\"dump\",\"filePath\":\"C:/t/app.cs\"}\n{\"id\":2,\"cmd\":\"restoreScript\"}\n");
        var output = new MemoryStream();
        await Program.RunLoop(input, output, engine);
        var lines = System.Text.Encoding.UTF8.GetString(output.ToArray()).Split('\n', StringSplitOptions.RemoveEmptyEntries);
        Assert.Contains("\"text\":\"#!/usr/bin/env dotnet\\n\\nvar x = 1;\\n\"", lines[0]);
        Assert.Contains("\"notReproducible\":[]", lines[0]);
        Assert.Contains("\"code\":\"var x = 1;\"", lines[1]);
    }
}
