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
    public async Task ConsoleOutput_IsCaptured()
    {
        var result = await new ReplEngine().RunAsync("Console.WriteLine(\"hi\");");
        Assert.Equal("hi\n", result.Output.Replace("\r\n", "\n"));
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
