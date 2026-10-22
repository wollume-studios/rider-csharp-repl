package com.wollumestudios.csharprepl

import com.wollumestudios.csharprepl.host.SemanticSpan

/**
 * The color-scheme keys Rider uses for C# identifiers (Settings › Editor › Color Scheme › C#: Types, Methods, Properties and
 * variables), by host kind ([SemanticSpan.kind]). Rider registers them, with fallbacks to the language defaults.
 */
val SEMANTIC_KEYS: Map<String, String> = mapOf(
    "namespace" to "NAMESPACE",
    "class" to "CLASS",
    "staticClass" to "STATIC_CLASS",
    "record" to "RECORD",
    "struct" to "STRUCT",
    "recordStruct" to "RECORD_STRUCT",
    "interface" to "INTERFACE",
    "enum" to "ENUM",
    "delegate" to "DELEGATE",
    "typeParameter" to "TYPE_PARAMETER",
    "method" to "METHOD_CALL",
    "methodDeclaration" to "METHOD_DECLARATION",
    "staticMethod" to "STATIC_METHOD_CALL",
    "staticMethodDeclaration" to "STATIC_METHOD_DECLARATION",
    "extensionMethod" to "EXTENSION_METHOD_CALL",
    "extensionMethodDeclaration" to "EXTENSION_METHOD_DECLARATION",
    "localFunction" to "LOCAL_FUNCTION",
    "property" to "PROPERTY",
    "staticProperty" to "STATIC_PROPERTY",
    "field" to "FIELD",
    "staticField" to "STATIC_FIELD",
    "constant" to "CONSTANT",
    "enumMember" to "CONSTANT",
    "event" to "EVENT",
    "local" to "LOCAL_VARIABLE",
    "parameter" to "PARAMETER",
).mapValues { "ReSharper.CSHARP_${it.value}_IDENTIFIER" }

/** [spans] of [code] moved to where the history shows it: every line break of the code is followed by [indent] more characters. */
fun indented(spans: List<SemanticSpan>, code: String, indent: Int): List<SemanticSpan> {
    val breaks = code.indices.filter { code[it] == '\n' }
    return spans.map { s -> s.copy(start = s.start + indent * breaks.count { it < s.start }) }
}

/** [spans] of `pending + "\n" + input` ([prefixLength] = the pending part) relative to the input; spans in the pending code are dropped. */
fun relativeToInput(spans: List<SemanticSpan>, prefixLength: Int): List<SemanticSpan> =
    spans.filter { it.start >= prefixLength }.map { it.copy(start = it.start - prefixLength) }
