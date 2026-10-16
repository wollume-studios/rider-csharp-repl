using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.CSharp.Scripting;
using Microsoft.CodeAnalysis.Scripting;

namespace CSharpRepl.Host;

/// <summary>Rewrites the session's submissions into one .NET 10 file-based app: statements in order, types at the end, usings hoisted.</summary>
public static class SessionExporter
{
    public static string Export(IReadOnlyList<Submission> subs, bool excludeThrown, string directives, ScriptOptions? options = null)
    {
        options ??= ReplEngine.BaseOptions;

        // Every submission that compiled is part of the chain (a submission that threw still declared things); only the kept ones are written out.
        var chain = new List<(Submission Sub, Compilation Compilation)>();
        Script? script = null;
        foreach (var sub in subs.Where(s => s.Status != SubmissionStatus.CompileError))
        {
            script = script == null ? CSharpScript.Create(sub.Code, options) : script.ContinueWith(sub.Code, options);
            chain.Add((sub, script.GetCompilation()));
        }
        var kept = chain.Where(c => !(excludeThrown && c.Sub.Status == SubmissionStatus.Threw)).Select(c =>
        {
            var tree = c.Compilation.SyntaxTrees.Single();
            return (Root: tree.GetCompilationUnitRoot(), Model: c.Compilation.GetSemanticModel(tree));
        }).ToList();

        // Pass 1: a declaration whose name is taken gets name2, name3, ... Later references bind to the right symbol, so the semantic model tells which ones to rewrite.
        var renames = new Dictionary<ISymbol, string>(SymbolEqualityComparer.Default);
        var used = new HashSet<string> { "args" }; // the implicit `args` of top-level statements
        var counts = new Dictionary<string, int>();
        foreach (var (root, model) in kept)
            foreach (var (name, symbol) in Declarations(root, model))
            {
                if (used.Add(name)) continue;
                int n = counts.GetValueOrDefault(name, 1);
                string fresh;
                do fresh = name + ++n; while (!used.Add(fresh));
                counts[name] = n;
                renames[symbol] = fresh;
            }

        // Pass 2: write each submission out.
        var usings = new List<string> { "using System.Text;" }; // a script imports System.Text by default, an SDK file-based app doesn't
        var statements = new List<string>();
        var types = new List<string>();
        foreach (var (root, model) in kept)
        {
            foreach (var u in root.Usings) if (!usings.Contains(u.ToString())) usings.Add(u.ToString());
            var rewriter = new Rewriter(model, renames, TrailingExpression(root));
            foreach (var member in rewriter.Visit(root).ChildNodes().OfType<MemberDeclarationSyntax>())
            {
                var text = member switch
                {
                    GlobalStatementSyntax g => g.ToFullString(),
                    FieldDeclarationSyntax f => AsLocal(f).ToFullString(),
                    MethodDeclarationSyntax m => AsLocalFunction(m).ToFullString(),
                    _ => null,
                };
                if (text != null) statements.Add(text.Trim());
                else if (member is BaseTypeDeclarationSyntax or DelegateDeclarationSyntax or BaseNamespaceDeclarationSyntax) types.Add(member.ToFullString().Trim());
                // ponytail: properties, events, indexers and operators typed at top level have no file-based-app form; they are left out
                else statements.Add($"// not exported ({member.Kind()}): {member.ToString().Split('\n')[0].Trim()}");
            }
        }

        var header = "#!/usr/bin/env dotnet\n" + (directives.Length > 0 ? directives.TrimEnd('\n') + "\n" : "") + "\n";
        var body = new[] { string.Join("\n", usings), string.Join("\n", statements), string.Join("\n\n", types) }.Where(b => b.Length > 0);
        return header + string.Join("\n\n", body) + "\n";
    }

    private static IEnumerable<(string Name, ISymbol Symbol)> Declarations(CompilationUnitSyntax root, SemanticModel model)
    {
        // Top-level variables are fields of the script class; so is an `out var x` at the top level (a deeper one is a plain local and cannot clash).
        foreach (var node in root.DescendantNodes(n => n is not (BaseTypeDeclarationSyntax or MethodDeclarationSyntax or AnonymousFunctionExpressionSyntax or LocalFunctionStatementSyntax)))
        {
            ISymbol? symbol = node switch
            {
                VariableDeclaratorSyntax { Parent.Parent: FieldDeclarationSyntax } d => model.GetDeclaredSymbol(d),
                SingleVariableDesignationSyntax d => model.GetDeclaredSymbol(d) as IFieldSymbol,
                MethodDeclarationSyntax or BaseTypeDeclarationSyntax or DelegateDeclarationSyntax => model.GetDeclaredSymbol(node),
                _ => null,
            };
            if (symbol != null) yield return (symbol.Name, symbol);
        }
    }

    /// <summary>The submission's script result: a last expression statement without `;`.</summary>
    private static ExpressionStatementSyntax? TrailingExpression(CompilationUnitSyntax root) =>
        root.Members.LastOrDefault() is GlobalStatementSyntax { Statement: ExpressionStatementSyntax { SemicolonToken.IsMissing: true } e } ? e : null;

    private static SyntaxTokenList Keep(SyntaxTokenList modifiers, params SyntaxKind[] kinds) =>
        SyntaxFactory.TokenList(modifiers.Where(m => kinds.Contains(m.Kind())));

    private static StatementSyntax AsLocal(FieldDeclarationSyntax f)
    {
        // A field is default-initialized, a local is not: `int count;` followed by `count++` must keep compiling.
        var declaration = f.Declaration.WithVariables(SyntaxFactory.SeparatedList(f.Declaration.Variables.Select(v =>
            v.Initializer != null || f.Modifiers.Any(SyntaxKind.ConstKeyword)
                ? v
                : v.WithIdentifier(v.Identifier.WithTrailingTrivia(SyntaxFactory.Space))
                    .WithInitializer(SyntaxFactory.EqualsValueClause(SyntaxFactory.LiteralExpression(SyntaxKind.DefaultLiteralExpression, SyntaxFactory.Token(SyntaxKind.DefaultKeyword)))))));
        return SyntaxFactory.LocalDeclarationStatement(Keep(f.Modifiers, SyntaxKind.ConstKeyword), declaration).WithLeadingTrivia(f.GetLeadingTrivia());
    }

    // A method typed at the REPL's top level is a local function of the program; accessibility doesn't apply to those.
    private static MethodDeclarationSyntax AsLocalFunction(MethodDeclarationSyntax m) =>
        m.WithModifiers(Keep(m.Modifiers, SyntaxKind.StaticKeyword, SyntaxKind.AsyncKeyword, SyntaxKind.UnsafeKeyword));

    private sealed class Rewriter(SemanticModel model, Dictionary<ISymbol, string> renames, ExpressionStatementSyntax? result) : CSharpSyntaxRewriter
    {
        public override SyntaxNode? VisitExpressionStatement(ExpressionStatementSyntax node)
        {
            var visited = (ExpressionStatementSyntax)base.VisitExpressionStatement(node)!;
            if (node != result) return visited;
            return IsVoid(node.Expression)
                ? visited.WithSemicolonToken(SyntaxFactory.Token(SyntaxKind.SemicolonToken))
                : SyntaxFactory.ParseStatement($"Console.WriteLine({visited.Expression.ToString().Trim()});");
        }

        private bool IsVoid(ExpressionSyntax e)
        {
            var type = model.GetTypeInfo(e).Type;
            if (type is { TypeKind: not TypeKind.Error }) return type.SpecialType == SpecialType.System_Void;
            // ponytail: with an unresolvable type (e.g. a call into a reference the exporter can't see) only calls count as statements; anything else is wrapped
            return e is InvocationExpressionSyntax;
        }

        public override SyntaxToken VisitToken(SyntaxToken token)
        {
            if (!token.IsKind(SyntaxKind.IdentifierToken) || token.Parent is not { } p) return token;
            var symbol = p switch
            {
                IdentifierNameSyntax or GenericNameSyntax => model.GetSymbolInfo(p).Symbol,
                VariableDeclaratorSyntax or SingleVariableDesignationSyntax or MethodDeclarationSyntax or BaseTypeDeclarationSyntax or DelegateDeclarationSyntax => model.GetDeclaredSymbol(p),
                ConstructorDeclarationSyntax or DestructorDeclarationSyntax => model.GetDeclaredSymbol(p)?.ContainingType,
                _ => null,
            };
            // The name check keeps `var` (whose symbol is the inferred type) and member names that merely resemble a renamed symbol out.
            return symbol != null && symbol.Name == token.ValueText && renames.TryGetValue(symbol.OriginalDefinition, out var fresh)
                ? SyntaxFactory.Identifier(token.LeadingTrivia, fresh, token.TrailingTrivia)
                : token;
        }
    }
}
