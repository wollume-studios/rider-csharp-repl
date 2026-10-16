using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.CSharp.Scripting;
using Microsoft.CodeAnalysis.Scripting;

namespace CSharpRepl.Host;

/// <summary>Rewrites the session's submissions into one .NET 10 file-based app: statements in order, types at the end, usings hoisted.
/// Whatever it knows won't compile or won't behave like the session is reported as a warning (also written as `// WARNING:` lines below the directives).</summary>
public static class SessionExporter
{
    public static (string Text, string[] Warnings) Export(IReadOnlyList<Submission> subs, bool excludeThrown, string directives, ScriptOptions? options = null)
    {
        options ??= ReplEngine.BaseOptions;
        var warnings = new List<string>();

        // Every submission that compiled is part of the chain (a submission that threw still declared things); only the kept ones are written out.
        var chain = new List<(Submission Sub, Compilation Compilation)>();
        Script? script = null;
        foreach (var sub in subs.Where(s => s.Status != SubmissionStatus.CompileError))
        {
            script = script == null ? CSharpScript.Create(sub.Code, options) : script.ContinueWith(sub.Code, options);
            chain.Add((sub, script.GetCompilation()));
        }
        (CompilationUnitSyntax Root, SemanticModel Model) Parsed((Submission Sub, Compilation Compilation) c)
        {
            var tree = c.Compilation.SyntaxTrees.Last(); // files pulled in by #load come first
            return (tree.GetCompilationUnitRoot(), c.Compilation.GetSemanticModel(tree));
        }
        bool Dropped((Submission Sub, Compilation Compilation) c) => excludeThrown && c.Sub.Status == SubmissionStatus.Threw;
        var kept = chain.Where(c => !Dropped(c)).Select(Parsed).ToList();
        var dropped = chain.Where(Dropped).Select(Parsed).ToList();

        // A dropped submission's declarations are gone; kept submissions that still use them won't compile.
        var gone = new Dictionary<ISymbol, string>(SymbolEqualityComparer.Default);
        foreach (var (root, model) in dropped)
            foreach (var (name, symbol) in Declarations(root, model)) gone[symbol] = name;
        if (gone.Count > 0)
        {
            var missing = kept.SelectMany(k => k.Root.DescendantNodes().OfType<SimpleNameSyntax>().Select(n => k.Model.GetSymbolInfo(n).Symbol?.OriginalDefinition))
                .Where(s => s != null && gone.ContainsKey(s)).Select(s => gone[s!]).Distinct().ToList();
            if (missing.Count > 0) warnings.Add($"submissions that threw were left out, but later ones use what they declared: {string.Join(", ", missing)}");
        }

        // Pass 1: a declaration whose name is taken gets name2, name3, ... Later references bind to the right symbol, so the semantic model tells which ones to rewrite.
        // A name that a nested scope (for/foreach/catch variable, block local, query variable) uses anywhere in the session is taken as well: as a top-level local it would be CS0136.
        // So the top-level symbol is renamed away; the nested locals stay as typed. (Lambda and local-function scopes may shadow, so those are not counted.)
        var nested = kept.SelectMany(k => NestedNames(k.Root, k.Model)).ToHashSet();
        var renames = new Dictionary<ISymbol, string>(SymbolEqualityComparer.Default);
        var used = new HashSet<string> { "args" }; // the implicit `args` of top-level statements
        var counts = new Dictionary<string, int>();
        foreach (var (root, model) in kept)
            foreach (var (name, symbol) in Declarations(root, model))
            {
                bool Taken(string n) => used.Contains(n) || (symbol is not INamedTypeSymbol && nested.Contains(n));
                if (!Taken(name)) { used.Add(name); continue; }
                int n = counts.GetValueOrDefault(name, 1);
                string fresh;
                do fresh = name + ++n; while (Taken(fresh));
                used.Add(fresh);
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
            foreach (var d in root.DescendantNodes(descendIntoTrivia: true).OfType<DirectiveTriviaSyntax>())
            {
                if (d is ReferenceDirectiveTriviaSyntax r) warnings.Add($"reference DLL manually: {OneLine(r.File.ValueText)}");
                else if (d is LoadDirectiveTriviaSyntax l) warnings.Add($"#load is not supported, add the file's code by hand: {OneLine(l.File.ValueText)}");
            }
            // A type is moved out of the top-level program, where it can no longer see the program's variables and local functions.
            foreach (var type in root.DescendantNodes().OfType<BaseTypeDeclarationSyntax>())
                foreach (var name in type.DescendantNodes().OfType<SimpleNameSyntax>())
                    if (model.GetSymbolInfo(name).Symbol is (IFieldSymbol or IMethodSymbol) and { ContainingType.IsScriptClass: true } s)
                        warnings.Add($"type {type.Identifier.Text} uses top-level {s.Name}, which a type can't see in an exported app");

            var rewriter = new Rewriter(model, renames, TrailingExpression(root), warnings);
            foreach (var member in rewriter.Visit(root).ChildNodes().OfType<MemberDeclarationSyntax>())
            {
                var text = member switch
                {
                    GlobalStatementSyntax { Statement: EmptyStatementSyntax } when rewriter.DroppedResult != null => "// " + OneLine(rewriter.DroppedResult),
                    GlobalStatementSyntax g => g.ToFullString(),
                    FieldDeclarationSyntax f => AsLocal(f).ToFullString(),
                    MethodDeclarationSyntax m => AsLocalFunction(m).ToFullString(),
                    _ => null,
                };
                if (text != null) statements.Add(text.Trim());
                else if (member is BaseTypeDeclarationSyntax or DelegateDeclarationSyntax or BaseNamespaceDeclarationSyntax) types.Add(member.ToFullString().Trim());
                // ponytail: properties, events, indexers and operators typed at top level have no file-based-app form; they are left out (with a warning)
                else
                {
                    var first = OneLine(member.ToString().Split('\n')[0].Trim());
                    statements.Add($"// not exported ({member.Kind()}): {first}");
                    warnings.Add($"not exported, no file-based-app form: {first}");
                }
            }
        }

        var distinct = warnings.Distinct().ToArray();
        var header = "#!/usr/bin/env dotnet\n" + (directives.Length > 0 ? directives.TrimEnd('\n') + "\n" : "")
            + string.Concat(distinct.Select(w => "// WARNING: " + OneLine(w) + "\n")) + "\n";
        var body = new[] { string.Join("\n", usings), string.Join("\n", statements), string.Join("\n\n", types) }.Where(b => b.Length > 0);
        return (header + string.Join("\n\n", body) + "\n", distinct);
    }

    // Any C# line terminator would end a `//` comment.
    private static string OneLine(string s) => System.Text.RegularExpressions.Regex.Replace(s, "[\r\n\x85\x2028\x2029]", " ");

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

    /// <summary>Names declared by statements nested in the top-level scope, which would clash with a top-level local of the same name.</summary>
    private static IEnumerable<string> NestedNames(CompilationUnitSyntax root, SemanticModel model)
    {
        foreach (var node in root.DescendantNodes(n => n is not (BaseTypeDeclarationSyntax or MethodDeclarationSyntax or AnonymousFunctionExpressionSyntax or LocalFunctionStatementSyntax)))
        {
            var id = node switch
            {
                VariableDeclaratorSyntax { Parent.Parent: not FieldDeclarationSyntax } d => d.Identifier,
                ForEachStatementSyntax f => f.Identifier,
                CatchDeclarationSyntax c => c.Identifier,
                SingleVariableDesignationSyntax d when model.GetDeclaredSymbol(d) is not IFieldSymbol => d.Identifier,
                LocalFunctionStatementSyntax l => l.Identifier,
                FromClauseSyntax q => q.Identifier,
                LetClauseSyntax q => q.Identifier,
                JoinClauseSyntax q => q.Identifier,
                JoinIntoClauseSyntax q => q.Identifier,
                QueryContinuationSyntax q => q.Identifier,
                _ => default,
            };
            if (id.ValueText is { Length: > 0 } name) yield return name;
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

    private sealed class Rewriter(SemanticModel model, Dictionary<ISymbol, string> renames, ExpressionStatementSyntax? result, List<string> warnings) : CSharpSyntaxRewriter
    {
        /// <summary>Set when the submission's result had no type (`null`, a lambda): there is nothing to print.</summary>
        public string? DroppedResult { get; private set; }

        public override SyntaxNode? VisitExpressionStatement(ExpressionStatementSyntax node)
        {
            var visited = (ExpressionStatementSyntax)base.VisitExpressionStatement(node)!;
            if (node != result) return visited;
            var type = model.GetTypeInfo(node.Expression).Type;
            if (type == null)
            {
                DroppedResult = $"result not printed, the expression has no type: {visited.Expression.ToString().Trim()}";
                warnings.Add(DroppedResult);
                return SyntaxFactory.EmptyStatement();
            }
            return IsVoid(node.Expression, type)
                ? visited.WithSemicolonToken(SyntaxFactory.Token(SyntaxKind.SemicolonToken))
                : SyntaxFactory.ParseStatement($"Console.WriteLine({visited.Expression.ToString().Trim()});");
        }

        private static bool IsVoid(ExpressionSyntax e, ITypeSymbol type) =>
            // ponytail: with an unresolvable type (e.g. a call into a reference the exporter can't see) only calls count as statements; anything else is wrapped
            type.TypeKind == TypeKind.Error ? e is InvocationExpressionSyntax : type.SpecialType == SpecialType.System_Void;

        public override SyntaxNode? VisitReturnStatement(ReturnStatementSyntax node)
        {
            // The top-level program's `return x;` would have to be an int; it also ends the program, which the session never did.
            var owner = node.Ancestors().FirstOrDefault(n => n is AnonymousFunctionExpressionSyntax or LocalFunctionStatementSyntax or MemberDeclarationSyntax);
            if (owner is not (null or GlobalStatementSyntax)) return base.VisitReturnStatement(node);
            warnings.Add("a top-level return ends the program early (exported as a plain return)");
            return SyntaxFactory.ReturnStatement().WithLeadingTrivia(node.GetLeadingTrivia()).WithTrailingTrivia(node.GetTrailingTrivia());
        }

        // `#r "x.dll"` and `#load` are script-only; as directives in the middle of a file they would not compile.
        public override SyntaxTrivia VisitTrivia(SyntaxTrivia trivia) =>
            trivia.Kind() is SyntaxKind.ReferenceDirectiveTrivia or SyntaxKind.LoadDirectiveTrivia ? default : trivia;

        public override SyntaxToken VisitToken(SyntaxToken token)
        {
            if (token.IsKind(SyntaxKind.IdentifierToken) && token.Parent is { } p)
            {
                var symbol = p switch
                {
                    IdentifierNameSyntax or GenericNameSyntax => model.GetSymbolInfo(p).Symbol,
                    VariableDeclaratorSyntax or SingleVariableDesignationSyntax or MethodDeclarationSyntax or BaseTypeDeclarationSyntax or DelegateDeclarationSyntax => model.GetDeclaredSymbol(p),
                    ConstructorDeclarationSyntax or DestructorDeclarationSyntax => model.GetDeclaredSymbol(p)?.ContainingType,
                    _ => null,
                };
                // The name check keeps `var` (whose symbol is the inferred type) and member names that merely resemble a renamed symbol out.
                if (symbol != null && symbol.Name == token.ValueText && renames.TryGetValue(symbol.OriginalDefinition, out var fresh))
                    token = SyntaxFactory.Identifier(token.LeadingTrivia, fresh, token.TrailingTrivia);
            }
            return base.VisitToken(token);
        }
    }
}
