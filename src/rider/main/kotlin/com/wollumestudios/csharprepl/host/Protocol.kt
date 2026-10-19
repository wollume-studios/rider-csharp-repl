package com.wollumestudios.csharprepl.host

// Mirrors src/host/CSharpRepl.Host/Protocol.cs. Decode a response with Gson: gson.fromJson(json, RunResponse::class.java).
// Gson leaves absent fields null/0, so a missing list is null at runtime despite the Kotlin type; the host always sends them.

/**
 * `run`: [status] is "ok", "threw" or "compileError"; [addedReferences]: what the run's `#r "nuget: ..."` lines added;
 * [spans]: the code's identifiers classified against the session before the run.
 */
data class RunResponse(
    val status: String,
    val output: String,
    val result: String?,
    val diagnostics: List<String>,
    val exception: String?,
    val addedReferences: List<ReferenceInfo>,
    val spans: List<SemanticSpan>,
)

/** [handle] is non-null when the value has children (`expand`). */
data class Var(val name: String, val type: String, val value: String, val handle: Int?)

data class VariablesResponse(val variables: List<Var>)

data class ExpandResponse(val children: List<Var>)

data class AnalyzeResponse(val complete: Boolean, val fixed: String?)

/** `addReference` request side: [kind] is "project", "dll" or "nuget"; [pathOrName] a path or a package name. */
data class ReferenceInfo(val kind: String, val pathOrName: String, val version: String?)

data class AddReferenceResponse(val assemblies: List<String>, val sourceDirs: List<String>)

data class DumpResponse(val text: String, val notReproducible: List<String>)

data class RestoreScriptResponse(val code: String, val notReproducible: List<String>)

data class ExportResponse(val text: String, val warnings: List<String>)

/** `complete` item; [kind]: "variable", "method", "property", "field", "event", "parameter", "namespace", "class", "struct", "interface", "enum", "delegate", "keyword" or "other". */
data class CompletionItem(val label: String, val kind: String, val detail: String)

/** `complete`: an item replaces [start, start + [length]) of the code, the identifier prefix before the caret. */
data class CompleteResponse(val start: Int, val length: Int, val items: List<CompletionItem>)

/**
 * [start, start + [length]) is an identifier of [kind]: "namespace", "class", "staticClass", "record", "struct",
 * "recordStruct", "interface", "enum", "delegate", "typeParameter", "method", "staticMethod", "extensionMethod" (calls; the
 * declarations: "methodDeclaration", "staticMethodDeclaration", "extensionMethodDeclaration"),
 * "localFunction", "property", "staticProperty", "field", "staticField", "constant", "enumMember", "event", "local", "parameter".
 */
data class SemanticSpan(val start: Int, val length: Int, val kind: String)

/** `classify`: the code's identifiers as the next submission. */
data class ClassifyResponse(val spans: List<SemanticSpan>)
