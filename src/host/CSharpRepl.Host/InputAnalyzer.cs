using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;

namespace CSharpRepl.Host;

public static class InputAnalyzer
{
    private static readonly CSharpParseOptions ScriptOptions = new(LanguageVersion.Latest, kind: SourceCodeKind.Script);

    private static readonly SyntaxKind[] FixableParents =
    {
        SyntaxKind.ExpressionStatement, SyntaxKind.LocalDeclarationStatement, SyntaxKind.FieldDeclaration,
        SyntaxKind.ReturnStatement, SyntaxKind.ThrowStatement, SyntaxKind.BreakStatement,
        SyntaxKind.ContinueStatement, SyntaxKind.UsingDirective, SyntaxKind.YieldReturnStatement,
    };

    /// <summary>Complete = ready to submit. Fixed = the code with ';' appended, set only when that makes it complete (auto-';' rule).</summary>
    public static (bool Complete, string? Fixed) Analyze(string code)
    {
        if (SyntaxFactory.IsCompleteSubmission(SyntaxFactory.ParseSyntaxTree(code, ScriptOptions)))
            return (true, null);

        var fixedCode = code + ";";
        var tree = SyntaxFactory.ParseSyntaxTree(fixedCode, ScriptOptions);
        if (!SyntaxFactory.IsCompleteSubmission(tree) || tree.GetDiagnostics().Any(d => d.Severity == DiagnosticSeverity.Error))
            return (false, null);

        var last = tree.GetRoot().DescendantTokens().Last(t => t.Span.Length > 0);
        var ok = last.IsKind(SyntaxKind.SemicolonToken) && last.Parent is { } p && FixableParents.Contains(p.Kind());
        return (false, ok ? fixedCode : null);
    }
}
