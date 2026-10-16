namespace CSharpRepl.Host.Tests;

public class SessionExporterTests
{
    private static Submission Ok(string code) => new(code, SubmissionStatus.Ok);

    /// <summary>Exports the session and asserts that the result compiles as a file-based app.</summary>
    private static string Export(bool excludeThrown, params Submission[] subs)
    {
        var text = SessionExporter.Export(subs, excludeThrown, "").Text;
        Assert.Empty(GeneratedCodeCompileTests.Errors(text));
        return text;
    }

    private static string Export(params string[] codes) => Export(false, codes.Select(Ok).ToArray());

    private static string[] Warnings(bool excludeThrown, params Submission[] subs) => SessionExporter.Export(subs, excludeThrown, "").Warnings;

    private static string[] Warnings(params string[] codes) => Warnings(false, codes.Select(Ok).ToArray());

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
        var text = SessionExporter.Export([Ok("var x = 1;"), Ok("using System.Text;"), Ok("using System.Text;\nvar sb = new StringBuilder();")], false, "#:package A@1\n").Text;
        Assert.Empty(GeneratedCodeCompileTests.Errors(text));
        Assert.StartsWith("#!/usr/bin/env dotnet\n#:package A@1\n\nusing System.Text;\n\nvar x = 1;", text);
        Assert.Equal(1, text.Split("using System.Text;").Length - 1);
    }

    [Fact]
    public void NoDirectives_NoExtraBlankLine()
    {
        Assert.StartsWith("#!/usr/bin/env dotnet\n\nusing System.Text;\n", SessionExporter.Export([], false, "").Text);
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
        var (text, _) = engine.Export(Path.Combine(Path.GetTempPath(), "app.cs"), true);
        Assert.Empty(GeneratedCodeCompileTests.Errors(text));
        Assert.Contains("var x2 = 10;", text);
        Assert.Contains("Console.WriteLine(x2);", text);
        Assert.DoesNotContain("boom", text);
    }

    [Fact]
    public void TypeUsingTopLevelVariableOrMethod_Warns()
    {
        var (text, warnings) = SessionExporter.Export([Ok("const int K = 3;"), Ok("static int Sq(int x) => x * x;"), // the REPL itself rejects instance access (CS0120): only const/static top-level members are visible to a type
            
            Ok("class Api { public int Get() => K; }"), Ok("class A { int M() => Sq(2); }")], false, "#:package A@1");
        Assert.Contains("type Api uses top-level K, which a type can't see in an exported app", warnings);
        Assert.Contains("type A uses top-level Sq, which a type can't see in an exported app", warnings);
        Assert.StartsWith("#!/usr/bin/env dotnet\n#:package A@1\n// WARNING: type Api uses top-level K", text);
        Assert.Contains("\n\nusing System.Text;", text);
        Assert.Empty(Warnings("class B { int F(int v) => v; }", "var b = new B();"));
    }

    [Fact]
    public void NestedScopeName_ClashingWithLaterTopLevelVariable_RenamesTheTopLevelOne()
    {
        var text = Export("for (int i = 0; i < 2; i++) { }", "var i = 5;", "i");
        Assert.Contains("for (int i = 0;", text);
        Assert.Contains("var i2 = 5;", text);
        Assert.Contains("Console.WriteLine(i2);", text);

        text = Export("foreach (var n in new[] { 1, 2 }) { }", "var n = 3;", "n");
        Assert.Contains("foreach (var n in", text);
        Assert.Contains("var n2 = 3;", text);

        text = Export("try { throw new Exception(); } catch (Exception e) { }", "var e = 1;", "e");
        Assert.Contains("catch (Exception e)", text);
        Assert.Contains("var e2 = 1;", text);
        Assert.Contains("Console.WriteLine(e2);", text);
    }

    [Fact]
    public void NestedScopeName_BeforeAndAfter_AndBlockLocalAndQuery()
    {
        Export("var x = 1;", "foreach (var x in new[] { 2 }) { }", "x"); // a later nested name clashes with an earlier top-level one
        var text = Export("{ int t = 1; }", "var t = 2;", "var q = from t2 in new[] { 1 } select t2;", "var t2 = 4;");
        Assert.Contains("var t3 = 2;", text); // t2 is a query variable too, so t skips it
        Assert.Contains("var t22 = 4;", text);
    }

    [Fact]
    public void LambdaParameter_MayShadow_NoRename()
    {
        var text = Export("var x = 1;", "var ys = new[] { 1 }.Select(x => x + 1).ToList();");
        Assert.Contains("var x = 1;", text);
        Assert.DoesNotContain("x2", text);
    }

    [Fact]
    public void ExcludedSubmissionsDeclarationsUsedLater_Warn()
    {
        var subs = new[] { Ok("var keep = 1;"), new Submission("var gone = 2; class Gone {} throw new Exception();", SubmissionStatus.Threw),
            Ok("var y = gone + keep;"), Ok("var g = new Gone();") };
        var (text, warnings) = SessionExporter.Export(subs, true, "");
        var warning = Assert.Single(warnings);
        Assert.Contains("gone", warning);
        Assert.Contains("Gone", warning);
        Assert.DoesNotContain("keep", warning);
        Assert.Contains("// WARNING: " + warning + "\n", text);
        Assert.Empty(Warnings(false, subs)); // nothing is left out, nothing to warn about
        Assert.Empty(Warnings(true, Ok("var a = 1;"), new Submission("var b = 2; throw new Exception();", SubmissionStatus.Threw), Ok("var c = a;")));
    }

    [Fact]
    public void ReferenceAndLoadDirectives_StrippedWithWarning()
    {
        // Both resolve, so the submissions compiled in the session.
        var dll = typeof(Microsoft.CodeAnalysis.Compilation).Assembly.Location.Replace('\\', '/');
        var csx = Path.Combine(Path.GetTempPath(), "export-load-test.csx").Replace('\\', '/');
        File.WriteAllText(csx, "var loaded = 1;");
        var subs = new[] { Ok($"#r \"{dll}\"\nvar x = 1;"), Ok($"#load \"{csx}\"\nx + 1") };
        var text = Export(false, subs);
        Assert.DoesNotContain("\n#r ", text);
        Assert.DoesNotContain("\n#load", text);
        var warnings = Warnings(false, subs);
        Assert.Contains("reference DLL manually: " + dll, warnings);
        Assert.Contains(warnings, w => w.StartsWith("#load") && w.Contains(csx));
    }

    [Fact]
    public void UnexportableMembers_Warn()
    {
        var text = Export("int Prop { get; set; }", "var ok = 1;");
        Assert.Contains("// not exported", text);
        Assert.Contains(Warnings("int Prop { get; set; }"), w => w.Contains("int Prop { get; set; }"));
    }

    [Fact]
    public void TrailingNullOrTypelessExpression_IsDropped()
    {
        var text = Export("var x = 1;", "null");
        Assert.DoesNotContain("WriteLine(null)", text);
        Assert.Contains("// result not printed", text);
        Assert.Contains(Warnings("null"), w => w.Contains("no type"));
    }

    [Fact]
    public void TopLevelReturn_BecomesPlainReturnWithWarning_ButNotInsideFunctions()
    {
        var text = Export("var x = 1;", "if (x > 5) return x;", "Func<int, int> f = v => { return v; };", "int G() { return 2; }", "G()");
        Assert.Contains("return;", text);
        Assert.Contains("return v;", text);
        Assert.Contains("return 2;", text);
        Assert.Contains(Warnings("var x = 1;", "if (x > 5) return x;"), w => w.Contains("return ends the program early"));
        Assert.Empty(Warnings("int G() { return 2; }", "G()"));
    }

    [Fact]
    public void NoWarnings_ForAPlainSession()
    {
        var (_, none) = new ReplEngine().Export(Path.Combine(Path.GetTempPath(), "app.cs"), false);
        Assert.Empty(none);
    }
}
