namespace CSharpRepl.Host.Tests;

public class InputAnalyzerTests
{
    [Theory]
    [InlineData("var r = 0", false, "var r = 0;")]
    [InlineData("var r = 0;", true, null)]
    [InlineData("1 + 1", true, null)] // trailing expression is a complete submission
    [InlineData("Console.WriteLine(1)", true, null)]
    [InlineData("for (var i = 0; i < 3; i++)", false, null)] // no empty body
    [InlineData("while (true)", false, null)]
    [InlineData("if (x)", false, null)]
    [InlineData("void F() {", false, null)]
    [InlineData("using System.Text", false, "using System.Text;")]
    [InlineData("return", false, "return;")]
    public void Analyze(string code, bool complete, string? fixedCode)
    {
        var (isComplete, fixedResult) = InputAnalyzer.Analyze(code);
        Assert.Equal(complete, isComplete);
        Assert.Equal(fixedCode, fixedResult);
    }
}
