namespace CSharpRepl.Host.Tests;

public class ReplEngineTests
{
    [Fact]
    public async Task Variables_KeepValuesAcrossRuns()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var r = 0;");
        await engine.RunAsync("r++;");
        var r = Assert.Single(engine.GetVariables());
        Assert.Equal("r", r.Name);
        Assert.Equal("int", r.Type);
        Assert.Equal("1", r.Value);
    }

    [Fact]
    public async Task Redeclaration_LastWins()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var s = 1;");
        await engine.RunAsync("var s = \"x\";");
        var s = Assert.Single(engine.GetVariables());
        Assert.Equal("string", s.Type);
        Assert.Equal("\"x\"", s.Value);
    }

    [Fact]
    public async Task Throw_SetsExceptionVariable()
    {
        var engine = new ReplEngine();
        var result = await engine.RunAsync("int.Parse(\"x\")");
        Assert.Equal(SubmissionStatus.Threw, result.Status);
        Assert.StartsWith("FormatException: ", result.Exception);
        var ex = Assert.Single(engine.GetVariables());
        Assert.Equal("$exception", ex.Name);
        Assert.Equal("FormatException", ex.Type);
        Assert.Equal(SubmissionStatus.Threw, engine.Submissions[^1].Status);
    }

    [Fact]
    public async Task Throw_KeepsStateUsable()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var a = 5;");
        await engine.RunAsync("throw new System.Exception(\"boom\");");
        var result = await engine.RunAsync("a + 1");
        Assert.Equal(SubmissionStatus.Ok, result.Status);
        Assert.Equal("6", result.Result);
        Assert.DoesNotContain(engine.GetVariables(), v => v.Name == "$exception");
    }

    [Fact]
    public async Task CompileError_ReportsDiagnostics()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var r = 1;");
        var result = await engine.RunAsync("var = ;");
        Assert.Equal(SubmissionStatus.CompileError, result.Status);
        Assert.NotEmpty(result.Diagnostics);
        var r = Assert.Single(engine.GetVariables());
        Assert.Equal("1", r.Value);
        Assert.Equal(SubmissionStatus.CompileError, engine.Submissions[^1].Status);
    }

    [Fact]
    public async Task CompileError_ClearsException()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("throw new System.Exception(\"boom\");");
        Assert.Contains(engine.GetVariables(), v => v.Name == "$exception");
        await engine.RunAsync("var = ;");
        Assert.DoesNotContain(engine.GetVariables(), v => v.Name == "$exception");
    }

    [Fact]
    public async Task Variables_HaveHandlesOnlyWhenExpandable()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var n = 1; var s = \"x\"; var list = new List<int> { 1, 2 }; var o = new { A = 1 };");
        var vars = engine.GetVariables().ToDictionary(v => v.Name);
        Assert.Null(vars["n"].Handle);
        Assert.Null(vars["s"].Handle);
        Assert.Equal("Count = 2", vars["list"].Value);
        Assert.NotNull(vars["list"].Handle);

        var items = engine.Expand(vars["list"].Handle!.Value);
        Assert.Equal(["[0]", "[1]"], items.Select(i => i.Name));
        Assert.Equal("int", items[0].Type);
        Assert.Equal("1", items[0].Value);
        Assert.Null(items[0].Handle);

        var a = Assert.Single(engine.Expand(vars["o"].Handle!.Value));
        Assert.Equal(("A", "int", "1"), (a.Name, a.Type, a.Value));
    }

    [Fact]
    public async Task Expand_NestedAndEllipsisEntry()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var nested = new List<List<int>> { new() { 1 } }; var big = Enumerable.Range(0, 500).ToList();");
        var vars = engine.GetVariables().ToDictionary(v => v.Name);
        var inner = Assert.Single(engine.Expand(vars["nested"].Handle!.Value));
        Assert.Equal("List<int>", inner.Type);
        Assert.NotNull(inner.Handle);
        Assert.Equal("[0]", Assert.Single(engine.Expand(inner.Handle!.Value)).Name);

        var big = engine.Expand(vars["big"].Handle!.Value);
        Assert.Equal(101, big.Count);
        Assert.Equal(new VarInfo("…", "", "400 more", null), big[100]);
    }

    [Fact]
    public async Task Handles_AreClearedOnRun()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var list = new List<int> { 1 };");
        var handle = engine.GetVariables().Single().Handle!.Value;
        await engine.RunAsync("1");
        Assert.Throws<ArgumentException>(() => engine.Expand(handle));
    }

    [Fact]
    public async Task Exception_VariableIsExpandable()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("throw new System.Exception(\"boom\");");
        var ex = Assert.Single(engine.GetVariables());
        Assert.Equal("{Exception: boom}", ex.Value);
        Assert.Contains(engine.Expand(ex.Handle!.Value), c => c.Name == "Message" && c.Value == "\"boom\"");
    }

    [Fact]
    public async Task ConsoleOutput_IsCaptured()
    {
        var result = await new ReplEngine().RunAsync("Console.WriteLine(\"hi\");");
        Assert.Equal("hi\n", result.Output.Replace("\r\n", "\n"));
    }

    [Fact]
    public async Task ConsoleOutput_IsCappedAndCountsTheRest()
    {
        var result = await new ReplEngine().RunAsync("for (var i = 0; i < 150_000; i++) Console.Write('x');");
        Assert.Equal(new string('x', ReplEngine.OutputCap) + "… (50000 more characters)", result.Output);
    }

    [Fact]
    public async Task Run_WithoutNuGetDirective_AddsNoReferences()
    {
        Assert.Empty((await new ReplEngine().RunAsync("1")).AddedReferences);
    }

    [Fact]
    public async Task Result_IsDisplayed()
    {
        var result = await new ReplEngine().RunAsync("DateTime.Parse(\"2026-10-16\").ToString(\"dd.MM\")");
        Assert.Equal(SubmissionStatus.Ok, result.Status);
        Assert.Equal("\"16.10\"", result.Result);
    }

    [Fact]
    public async Task DefaultImports_AreAvailable()
    {
        var result = await new ReplEngine().RunAsync(
            "new List<int> { 1, 2 }.Select(x => x * 2).Sum() + new StringBuilder().Append(1).Length + Path.GetFileName(\"a/b\").Length + Task.FromResult(1).Result");
        Assert.Equal(SubmissionStatus.Ok, result.Status);
        Assert.Equal("9", result.Result);
    }
}
