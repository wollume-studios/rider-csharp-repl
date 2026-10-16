namespace CSharpRepl.Host.Tests;

public class SessionExporterTests
{
    private static Submission Ok(string code) => new(code, SubmissionStatus.Ok);

    /// <summary>Exports the session and asserts that the result compiles as a file-based app.</summary>
    private static string Export(bool excludeThrown, params Submission[] subs)
    {
        var text = SessionExporter.Export(subs, excludeThrown, "");
        Assert.Empty(GeneratedCodeCompileTests.Errors(text));
        return text;
    }

    private static string Export(params string[] codes) => Export(false, codes.Select(Ok).ToArray());

    [Fact]
    public void BareExpression_BecomesWriteLine()
    {
        Assert.Contains("Console.WriteLine(1 + 1);", Export("1 + 1"));
    }

    [Fact]
    public void VoidCallExpression_StaysStatement()
    {
        var text = Export("Console.WriteLine(1)");
        Assert.Contains("Console.WriteLine(1);", text);
        Assert.DoesNotContain("Console.WriteLine(Console", text);
    }

    [Fact]
    public void ExpressionWithSemicolon_StaysAsIs()
    {
        var text = Export("var x = 1;", "x++;", "x");
        Assert.Contains("x++;", text);
        Assert.DoesNotContain("WriteLine(x++", text);
        Assert.Contains("Console.WriteLine(x);", text);
    }

    [Fact]
    public void Redeclaration_Renamed()
    {
        var text = Export("var s = 1;", "var s = \"x\";", "s.Length");
        Assert.Contains("var s2 = \"x\";", text);
        Assert.Contains("Console.WriteLine(s2.Length);", text);
    }

    [Fact]
    public void Rename_ReachesLambdasAndInterpolations_NotMembersOrNamedArguments()
    {
        var text = Export("class A { public int s; }", "var s = 1;", "var s = new A();",
            "Func<A> f = () => s;", "Console.WriteLine($\"{s.s}\");", "string.Join(separator: \",\", values: new[] { 1 });");
        Assert.Contains("var s2 = new A();", text);
        Assert.Contains("() => s2;", text);
        Assert.Contains("{s2.s}", text);
        Assert.Contains("separator:", text);
    }

    [Fact]
    public void Redeclaration_SecondAndThird_AndUserNameCollision()
    {
        var text = Export("var a = 1;", "var a = 2;", "var a2 = 3;", "var a = 4;", "a + a2");
        Assert.Contains("var a = 1;", text);
        Assert.Contains("var a2 = 2;", text);
        Assert.Contains("var a22 = 3;", text); // the user's own a2 had to move out of the way of the renamed a
        Assert.Contains("var a3 = 4;", text);
    }

    [Fact]
    public void ArgsVariable_Renamed()
    {
        Assert.Contains("var args2 = 5;", Export("var args = 5;", "args"));
    }

    [Fact]
    public void Types_MovedToEnd()
    {
        var text = Export("class P {}", "var p = new P();", "int Sq(int x) => x * x;", "Sq(3)");
        Assert.True(text.IndexOf("class P", StringComparison.Ordinal) > text.IndexOf("Console.WriteLine(Sq(3));", StringComparison.Ordinal));
        Assert.Contains("int Sq(int x) => x * x;", text);
    }

    [Fact]
    public void RedeclaredType_Renamed()
    {
        var text = Export("class P { public int V = 1; }", "var a = new P();", "class P { public int V = 2; public P() {} }", "var b = new P();", "a.V + b.V");
        Assert.Contains("class P2", text);
        Assert.Contains("var b = new P2();", text);
        Assert.Contains("var a = new P();", text);
    }

    [Fact]
    public void Members_StripScriptOnlyModifiers_AndFieldsGetDefaults()
    {
        var text = Export("public static int Twice(int x) => x * 2;", "const int K = 3;", "int count;", "count++;", "Twice(K) + count");
        Assert.DoesNotContain("public", text);
        Assert.Contains("const int K = 3;", text);
    }

    [Fact]
    public void OutVariables_AtTopLevel_Renamed()
    {
        Export("int.TryParse(\"1\", out var n);", "int.TryParse(\"2\", out var n);", "n");
    }

    [Fact]
    public void CompileErrors_AlwaysDropped()
    {
        var text = Export(true, new Submission("var x = 1;", SubmissionStatus.Ok), new Submission("var y = ;", SubmissionStatus.CompileError));
        Assert.DoesNotContain("var y", text);
        Assert.Equal(text, Export(false, new Submission("var x = 1;", SubmissionStatus.Ok), new Submission("var y = ;", SubmissionStatus.CompileError)));
    }

    [Fact]
    public void Thrown_DroppedOnlyWhenRequested()
    {
        var subs = new[] { Ok("var x = 1;"), new Submission("throw new Exception();", SubmissionStatus.Threw), Ok("var z = 2;") };
        Assert.Contains("throw new Exception();", Export(false, subs));
        var without = Export(true, subs);
        Assert.DoesNotContain("throw", without);
        Assert.Contains("var z = 2;", without);
    }

    [Fact]
    public void UsingDirectives_Hoisted()
    {
        var text = SessionExporter.Export([Ok("var x = 1;"), Ok("using System.Text;"), Ok("using System.Text;\nvar sb = new StringBuilder();")], false, "#:package A@1\n");
        Assert.Empty(GeneratedCodeCompileTests.Errors(text));
        Assert.StartsWith("#!/usr/bin/env dotnet\n#:package A@1\n\nusing System.Text;\n\nvar x = 1;", text);
        Assert.Equal(1, text.Split("using System.Text;").Length - 1);
    }

    [Fact]
    public void NoDirectives_NoExtraBlankLine()
    {
        Assert.StartsWith("#!/usr/bin/env dotnet\n\nusing System.Text;\n", SessionExporter.Export([], false, ""));
    }

    [Fact]
    public void ScriptDefaultImport_SystemText_IsEmittedEvenWithoutUsing()
    {
        Assert.Contains("using System.Text;", Export("new StringBuilder().Append(1).ToString()"));
    }

    [Fact]
    public async Task Engine_Export_UsesTheSessionsSubmissions()
    {
        var engine = new ReplEngine();
        await engine.RunAsync("var x = 1;");
        await engine.RunAsync("throw new Exception(\"boom\");");
        await engine.RunAsync("var x = 10;");
        await engine.RunAsync("x");
        var text = engine.Export(Path.Combine(Path.GetTempPath(), "app.cs"), true);
        Assert.Empty(GeneratedCodeCompileTests.Errors(text));
        Assert.Contains("var x2 = 10;", text);
        Assert.Contains("Console.WriteLine(x2);", text);
        Assert.DoesNotContain("boom", text);
    }
}
