using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Scripting;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Microsoft.CodeAnalysis.Scripting;

namespace CSharpRepl.Host;

/// <summary>[Start, Start+Length) of the code is an identifier of [Kind] (see <see cref="Classifier.Kind"/>).</summary>
public sealed record SemanticSpan(int Start, int Length, string Kind);

public sealed record ClassifyResponse(IReadOnlyList<SemanticSpan> Spans);

/// <summary>
/// Semantic classes of the identifiers in a submission (method, extension method, property, local, parameter, type, …),
/// from the semantic model of the code compiled as the next submission after [state]. Compiles only, never runs code.
/// </summary>
public static class Classifier
{
    public static IReadOnlyList<SemanticSpan> Classify(ScriptState<object>? state, ScriptOptions options, string code)
    {
        var script = state == null ? CSharpScript.Create(code, options) : state.Script.ContinueWith(code, options);
        var tree = script.GetCompilation().SyntaxTrees.Single();
        var model = script.GetCompilation().GetSemanticModel(tree);
        var spans = new List<SemanticSpan>();
        foreach (var token in tree.GetRoot().DescendantTokens())
        {
            if (!token.IsKind(SyntaxKind.IdentifierToken) || token.Parent is not { } parent) continue;
            if (parent is IdentifierNameSyntax { IsVar: true }) continue; // `var` stays a keyword
            var declared = parent is not SimpleNameSyntax;
            var symbol = declared
                ? model.GetDeclaredSymbol(parent)
                : model.GetSymbolInfo(parent).Symbol ?? model.GetSymbolInfo(parent).CandidateSymbols.FirstOrDefault();
            if (symbol == null || Kind(symbol) is not { } kind) continue;
            // Rider colors method declarations and calls separately.
            if (declared && kind is "method" or "staticMethod" or "extensionMethod") kind += "Declaration";
            spans.Add(new SemanticSpan(token.SpanStart, token.Span.Length, kind));
        }
        return spans;
    }

    /// <summary>
    /// namespace, class, staticClass, record, struct, recordStruct, interface, enum, delegate, typeParameter, method,
    /// staticMethod, extensionMethod (calls; the declarations get Classify's "…Declaration" kinds), localFunction, property, staticProperty, field, staticField, constant, enumMember,
    /// event, local, parameter; null for anything else (unresolved names, labels, `dynamic`).
    /// A variable of an earlier submission (a field of the script class) is a local, as it is written.
    /// </summary>
    public static string? Kind(ISymbol s) => s switch
    {
        IAliasSymbol a => Kind(a.Target),
        INamespaceSymbol => "namespace",
        ITypeParameterSymbol => "typeParameter",
        INamedTypeSymbol { TypeKind: TypeKind.Error } => null,
        INamedTypeSymbol t => t.TypeKind switch
        {
            TypeKind.Interface => "interface",
            TypeKind.Enum => "enum",
            TypeKind.Delegate => "delegate",
            TypeKind.Struct => t.IsRecord ? "recordStruct" : "struct",
            TypeKind.Class => t.IsRecord ? "record" : t.IsStatic ? "staticClass" : "class",
            _ => null,
        },
        IMethodSymbol { MethodKind: MethodKind.Constructor or MethodKind.StaticConstructor or MethodKind.Destructor } m => Kind(m.ContainingType),
        IMethodSymbol { MethodKind: MethodKind.ReducedExtension } or IMethodSymbol { IsExtensionMethod: true } => "extensionMethod",
        IMethodSymbol { MethodKind: MethodKind.LocalFunction } => "localFunction",
        IMethodSymbol m => m.IsStatic && m.ContainingType is not { IsScriptClass: true } ? "staticMethod" : "method",
        IFieldSymbol { ContainingType.TypeKind: TypeKind.Enum } => "enumMember",
        IFieldSymbol { IsConst: true } => "constant",
        IFieldSymbol { ContainingType.IsScriptClass: true } => "local",
        IFieldSymbol f => f.IsStatic ? "staticField" : "field",
        IPropertySymbol p => p.IsStatic ? "staticProperty" : "property",
        IEventSymbol => "event",
        ILocalSymbol { IsConst: true } => "constant",
        ILocalSymbol or IRangeVariableSymbol => "local",
        IParameterSymbol => "parameter",
        _ => null,
    };
}
