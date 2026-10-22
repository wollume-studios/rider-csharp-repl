using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Scripting;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Scripting;

namespace CSharpRepl.Host;

public sealed record CompletionItem(string Label, string Kind, string Detail);

/// <summary>[Start, Start+Length) is the identifier prefix before the caret that an item replaces.</summary>
public sealed record CompleteResponse(int Start, int Length, IReadOnlyList<CompletionItem> Items);

/// <summary>
/// Completion from the semantic model of the would-be next submission (compiled, never run): its LookupSymbols sees the
/// session's variables, methods and types, the default imports and every referenced assembly.
/// </summary>
public static class Completer
{
    public const int Cap = 200;

    private static readonly string[] Keywords =
    [
        "abstract", "as", "async", "await", "base", "bool", "break", "byte", "case", "catch", "char", "checked", "class",
        "const", "continue", "decimal", "default", "delegate", "do", "double", "dynamic", "else", "enum", "event", "explicit",
        "extern", "false", "finally", "fixed", "float", "for", "foreach", "goto", "if", "implicit", "in", "int", "interface",
        "internal", "is", "lock", "long", "nameof", "namespace", "new", "null", "object", "operator", "out", "override",
        "params", "private", "protected", "public", "readonly", "record", "ref", "return", "sbyte", "sealed", "short",
        "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", "try", "typeof", "uint",
        "ulong", "unchecked", "unsafe", "ushort", "using", "var", "virtual", "void", "volatile", "when", "while", "yield",
    ];

    private static readonly SymbolDisplayFormat Signature = SymbolDisplayFormat.MinimallyQualifiedFormat
        .WithMemberOptions(SymbolDisplayMemberOptions.IncludeParameters | SymbolDisplayMemberOptions.IncludeType)
        .WithParameterOptions(SymbolDisplayParameterOptions.IncludeType | SymbolDisplayParameterOptions.IncludeName);

    public static CompleteResponse Complete(ScriptState<object>? state, ScriptOptions options, string code, int position)
    {
        position = Math.Clamp(position, 0, code.Length);
        var start = position;
        while (start > 0 && (char.IsLetterOrDigit(code[start - 1]) || code[start - 1] == '_')) start--;
        var prefix = code[start..position];
        if (start < position && char.IsDigit(code[start])) return new CompleteResponse(start, 0, []); // a number literal

        var script = state == null ? CSharpScript.Create(code, options) : state.Script.ContinueWith(code, options);
        var compilation = script.GetCompilation();
        var tree = compilation.SyntaxTrees.Single();
        var model = compilation.GetSemanticModel(tree);
        var root = tree.GetRoot();

        if (InCommentOrString(root, Math.Max(0, start - 1))) return new CompleteResponse(start, position - start, []);
        var dot = start;
        while (dot > 0 && char.IsWhiteSpace(code[dot - 1])) dot--;
        IEnumerable<ISymbol> symbols;
        var keywords = false;
        if (dot > 0 && code[dot - 1] == '.')
        {
            var left = root.FindToken(dot - 1).Parent switch
            {
                MemberAccessExpressionSyntax m => m.Expression,
                QualifiedNameSyntax q => q.Left,
                _ => null,
            };
            if (left == null) return new CompleteResponse(start, position - start, []); // `?.` and other dots: not handled
            symbols = Members(model, left, position);
        }
        else
        {
            symbols = model.LookupSymbols(position);
            keywords = true;
        }

        var items = symbols
            .Where(s => s.CanBeReferencedByName && !s.IsImplicitlyDeclared)
            .GroupBy(s => s.Name)
            .Select(g => Item(g.Key, g.ToList()));
        if (keywords) items = items.Concat(Keywords.Select(k => new CompletionItem(k, "keyword", "")));
        var list = items.GroupBy(i => i.Label).Select(g => g.First()).ToList(); // a keyword named like a type alias (string) appears once

        var ranked = list.Select(i => (Item: i, Rank: Rank(i.Label, prefix))).Where(t => t.Rank >= 0)
            .OrderBy(t => t.Rank).ThenBy(t => t.Item.Label, StringComparer.OrdinalIgnoreCase).ThenBy(t => t.Item.Label, StringComparer.Ordinal)
            .Take(Cap).Select(t => t.Item).ToList();
        return new CompleteResponse(start, position - start, ranked);
    }

    /// <summary>Members after `left.`: a namespace's namespaces and types, a type name's static members, else the value's instance members (extension methods included).</summary>
    private static IEnumerable<ISymbol> Members(SemanticModel model, ExpressionSyntax left, int position)
    {
        var symbol = model.GetSymbolInfo(left).Symbol;
        if (symbol is INamespaceSymbol ns) return model.LookupNamespacesAndTypes(position, ns);
        if (symbol is ITypeSymbol named) return model.LookupStaticMembers(position, named);
        var type = model.GetTypeInfo(left).Type;
        if (type == null || type.TypeKind == TypeKind.Error) return [];
        return model.LookupSymbols(position, type, includeReducedExtensionMethods: true)            .Where(s => s is not ITypeSymbol && (!s.IsStatic || s is IMethodSymbol { MethodKind: MethodKind.ReducedExtension }));
    }

    /// <summary>In a comment, a string or char literal (where [offset] is the character before the identifier): no completion there.</summary>
    private static bool InCommentOrString(SyntaxNode root, int offset)
    {
        var token = root.FindToken(offset);
        if (token.Span.Contains(offset) && (token.IsKind(SyntaxKind.StringLiteralToken) || token.IsKind(SyntaxKind.CharacterLiteralToken)
                || token.IsKind(SyntaxKind.InterpolatedStringTextToken) || token.IsKind(SyntaxKind.SingleLineRawStringLiteralToken)
                || token.IsKind(SyntaxKind.MultiLineRawStringLiteralToken) || token.IsKind(SyntaxKind.Utf8StringLiteralToken)))
            return true;
        var trivia = root.FindTrivia(offset);
        return trivia.Span.Contains(offset) && (trivia.IsKind(SyntaxKind.SingleLineCommentTrivia) || trivia.IsKind(SyntaxKind.MultiLineCommentTrivia)
            || trivia.IsKind(SyntaxKind.SingleLineDocumentationCommentTrivia) || trivia.IsKind(SyntaxKind.MultiLineDocumentationCommentTrivia));
    }

    private static CompletionItem Item(string name, List<ISymbol> overloads)
    {
        var first = overloads[0];
        var detail = first switch
        {
            IMethodSymbol m => m.ToDisplayString(Signature),
            ILocalSymbol l => l.Type.ToDisplayString(SymbolDisplayFormat.MinimallyQualifiedFormat),
            IFieldSymbol f => f.Type.ToDisplayString(SymbolDisplayFormat.MinimallyQualifiedFormat),
            IPropertySymbol p => p.Type.ToDisplayString(SymbolDisplayFormat.MinimallyQualifiedFormat),
            IEventSymbol e => e.Type.ToDisplayString(SymbolDisplayFormat.MinimallyQualifiedFormat),
            IParameterSymbol p => p.Type.ToDisplayString(SymbolDisplayFormat.MinimallyQualifiedFormat),
            INamedTypeSymbol t => t.ContainingNamespace is { IsGlobalNamespace: false } n ? n.ToDisplayString() : "",
            _ => "",
        };
        if (overloads.Count > 1) detail += $" (+{overloads.Count - 1} overload{(overloads.Count > 2 ? "s" : "")})";
        return new CompletionItem(name, Kind(first), detail);
    }

    private static string Kind(ISymbol s) => s switch
    {
        IFieldSymbol { ContainingType.IsScriptClass: true } => "variable", // a variable of an earlier submission
        ILocalSymbol => "variable",
        IFieldSymbol => "field",
        IPropertySymbol => "property",
        IMethodSymbol => "method",
        IEventSymbol => "event",
        IParameterSymbol => "parameter",
        INamespaceSymbol => "namespace",
        INamedTypeSymbol t => t.TypeKind switch
        {
            TypeKind.Interface => "interface",
            TypeKind.Struct => "struct",
            TypeKind.Enum => "enum",
            TypeKind.Delegate => "delegate",
            _ => "class",
        },
        _ => "other",
    };

    /// <summary>0: case-sensitive prefix, 1: case-insensitive prefix, 2: camel humps (`WL` → WriteLine), -1: no match.</summary>
    public static int Rank(string label, string prefix)
    {
        if (label.StartsWith(prefix, StringComparison.Ordinal)) return 0;
        if (label.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return 1;
        return CamelHumps(label, 0, prefix, 0) ? 2 : -1;
    }

    /// <summary>Each pattern character continues the current hump or starts a later one (an upper-case letter, or a character after '_').</summary>
    private static bool CamelHumps(string label, int li, string pattern, int pi)
    {
        if (pi == pattern.Length) return true;
        if (li >= label.Length) return false;
        if (char.ToUpperInvariant(label[li]) == char.ToUpperInvariant(pattern[pi]) && (pi > 0 || li == 0) && CamelHumps(label, li + 1, pattern, pi + 1)) return true;
        for (var j = li + 1; j < label.Length; j++)
            if ((char.IsUpper(label[j]) || label[j - 1] == '_') && char.ToUpperInvariant(label[j]) == char.ToUpperInvariant(pattern[pi]) && CamelHumps(label, j + 1, pattern, pi + 1))
                return true;
        return false;
    }
}
