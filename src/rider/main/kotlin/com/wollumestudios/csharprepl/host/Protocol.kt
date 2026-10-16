package com.wollumestudios.csharprepl.host

// Mirrors src/host/CSharpRepl.Host/Protocol.cs. Decode a response with Gson: gson.fromJson(json, RunResponse::class.java).
// Gson leaves absent fields null/0, so a missing list is null at runtime despite the Kotlin type; the host always sends them.

/** `run`: [status] is "ok", "threw" or "compileError". */
data class RunResponse(
    val status: String,
    val output: String,
    val result: String?,
    val diagnostics: List<String>,
    val exception: String?,
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
