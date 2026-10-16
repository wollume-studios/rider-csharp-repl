package com.wollumestudios.csharprepl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.wollumestudios.csharprepl.host.AnalyzeResponse
import com.wollumestudios.csharprepl.host.ExpandResponse
import com.wollumestudios.csharprepl.host.HostLocator
import com.wollumestudios.csharprepl.host.HostProcess
import com.wollumestudios.csharprepl.host.ReferenceInfo
import com.wollumestudios.csharprepl.host.RestoreScriptResponse
import com.wollumestudios.csharprepl.host.RunResponse
import com.wollumestudios.csharprepl.host.Var
import com.wollumestudios.csharprepl.host.VariablesResponse
import com.wollumestudios.csharprepl.settings.ReplSettings
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One history item. [status] is the host's "ok" / "threw" / "compileError", or "error" (the host failed the run),
 * "stopped" (killed by Stop), "note" (a session message in [output], no code).
 */
data class HistoryEntry(
    val code: String,
    val status: String,
    val output: String = "",
    val result: String? = null,
    val diagnostics: List<String> = emptyList(),
    val exception: String? = null,
) {
    companion object {
        fun note(text: String) = HistoryEntry("", "note", text)
    }
}

enum class RestartMode { Empty, Replay, Restore }

/**
 * The project's REPL session: owns the host process, the history, pending code, references and the last variables.
 * State is read and changed on the EDT only; host answers hop there with [onEdt]. Listeners run on the EDT after each change.
 */
@Service(Service.Level.PROJECT)
class ReplSession(private val project: Project) : Disposable {
    private val gson = Gson()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var host: HostProcess? = null
    /** Bumped whenever the host is replaced: answers from an older host are dropped. */
    private var generation = 0
    /** The last generation whose host answered a request: a host that dies before that is not restarted in a loop. */
    @Volatile private var answeredGeneration = -1
    /** Code of runs sent to the current host and not answered yet, in order (the host runs them one by one). */
    private val inFlight = ArrayDeque<String>()

    val history = mutableListOf<HistoryEntry>()
    val references = mutableListOf<ReferenceInfo>()
    var pending = ""
        private set
    var variables: List<Var> = emptyList()
        private set
    val running get() = inFlight.isNotEmpty()

    fun addListener(parent: Disposable, onChanged: () -> Unit) {
        listeners += onChanged
        Disposer.register(parent) { listeners -= onChanged }
    }

    /** Starts the host if there is none (first use, or after it could not start). */
    fun start() {
        if (host == null) startHost()
    }

    /**
     * Enter (or Shift+Enter) with [input] in the input field: asks the host whether pending + input is complete, applies
     * [decide] (runs, or moves the line to pending), and completes on the EDT with what the input field must do.
     */
    fun enter(shift: Boolean, input: String): CompletableFuture<EnterAction> {
        start()
        val autoRun = ReplSettings.state.autoRun
        val before = pending
        val result = CompletableFuture<EnterAction>()
        fun apply(analysis: AnalyzeResponse) {
            val action = decide(autoRun, shift, before, input, analysis)
            when (action) {
                is EnterAction.Run -> submit(action.code)
                is EnterAction.ToPending -> setPending(combine(before, action.line))
                EnterAction.NewLine -> {}
            }
            result.complete(action)
        }
        val unknown = AnalyzeResponse(false, null) // Shift+Enter doesn't need an answer; a dead host answers nothing
        if (shift) apply(unknown)
        else request("analyze", mapOf("code" to combine(before, input))).whenComplete { json, _ ->
            onEdt { apply(json?.let { gson.fromJson(it, AnalyzeResponse::class.java) } ?: unknown) }
        }
        return result
    }

    /** Runs [code] as is, appends it to the history, then refreshes the variables. Clears pending. */
    fun submit(code: String) {
        if (code.isBlank()) return
        start()
        setPending("")
        val gen = generation
        if (host == null) { append(HistoryEntry(code, "error", exception = "No host process")); return }
        inFlight.addLast(code)
        changed()
        request("run", mapOf("code" to code)).whenComplete { json, error ->
            onEdt {
                if (gen != generation) return@onEdt // stopped or restarted: already reported
                inFlight.removeFirstOrNull()
                if (json != null) {
                    val r = gson.fromJson(json, RunResponse::class.java)
                    append(HistoryEntry(code, r.status, r.output, r.result, r.diagnostics, r.exception))
                } else {
                    append(HistoryEntry(code, "error", exception = (error?.cause ?: error)?.message ?: "run failed"))
                }
                refreshVariables()
            }
        }
    }

    fun clearPending() = setPending("")

    fun expand(handle: Int): CompletableFuture<List<Var>> =
        request("expand", mapOf("handle" to handle)).thenApply { gson.fromJson(it, ExpandResponse::class.java).children }

    /** Kills a run that doesn't finish: a fresh host, references kept, variables lost. */
    fun stop() {
        inFlight.forEach { history += HistoryEntry(it, "stopped") }
        restart(RestartMode.Empty)
        append(HistoryEntry.note("Stopped — session restarted, variables lost"))
    }

    /** A fresh session: history, pending code and variables cleared, references kept. */
    fun reset() {
        history.clear()
        setPending("")
        restart(RestartMode.Empty)
    }

    /**
     * Replaces the host, re-adding [references]. [RestartMode.Replay] re-runs the history's successful commands;
     * [RestartMode.Restore] runs the old host's restore script (type declarations and variables as code). Both run visibly.
     */
    fun restart(mode: RestartMode) {
        when (mode) {
            RestartMode.Empty -> replaceHost()
            RestartMode.Replay -> {
                val ok = history.filter { it.status == "ok" }.map { it.code }
                replaceHost()
                append(HistoryEntry.note("Session restarted, replaying ${ok.size} command(s)"))
                ok.forEach(::submit)
            }
            RestartMode.Restore -> {
                val gen = generation
                request("restoreScript", emptyMap()).whenComplete { json, error ->
                    onEdt {
                        if (gen != generation) return@onEdt
                        replaceHost()
                        if (json == null) { append(HistoryEntry.note("Session restarted; variables could not be restored: ${(error?.cause ?: error)?.message}")); return@onEdt }
                        val r = gson.fromJson(json, RestoreScriptResponse::class.java)
                        append(HistoryEntry.note("Session restarted, restoring variables"))
                        submit(r.code)
                        if (!r.notReproducible.isNullOrEmpty()) append(HistoryEntry.note("Not restored: ${r.notReproducible.joinToString()}"))
                    }
                }
            }
        }
    }

    override fun dispose() {
        generation++
        host?.kill()
        host = null
    }

    private fun replaceHost() {
        generation++
        inFlight.clear()
        host?.kill() // killed = true: onExit stays quiet, the caller reports
        host = null
        variables = emptyList()
        startHost()
        changed()
    }

    private fun startHost() {
        val gen = generation
        host = try {
            HostProcess(HostLocator.hostDll(), onExit = { code, killed -> if (!killed) onEdt { hostExited(gen, code) } }, log = LOG::info)
        } catch (e: IOException) {
            LOG.warn(e)
            val note = HistoryEntry.note("dotnet not found on PATH — install the .NET 8+ SDK")
            if (history.lastOrNull() != note) append(note)
            null
        }
        references.forEach { ref -> // queued in order, before any run
            request("addReference", referenceFields(ref)).whenComplete { _, error ->
                if (error != null) onEdt { if (gen == generation) append(HistoryEntry.note("Reference ${ref.pathOrName} not re-added: ${(error.cause ?: error).message}")) }
            }
        }
    }

    private fun hostExited(gen: Int, code: Int) {
        if (gen != generation) return
        if (answeredGeneration != gen) { // never came up: restarting would loop
            generation++
            inFlight.clear()
            host = null
            append(HistoryEntry.note("Host exited (code $code) before it started; it starts again with the next command (details in idea.log)"))
            return
        }
        append(HistoryEntry.note("Host exited (code $code), session restarted"))
        replaceHost()
    }

    private fun refreshVariables() {
        val gen = generation
        request("variables", emptyMap()).thenAccept { json ->
            onEdt {
                if (gen != generation) return@onEdt
                variables = gson.fromJson(json, VariablesResponse::class.java).variables
                changed()
            }
        }
    }

    private fun request(cmd: String, fields: Map<String, Any?>): CompletableFuture<JsonObject> {
        val gen = generation
        val h = host ?: return CompletableFuture.failedFuture(IllegalStateException("No host process"))
        return h.request(cmd, fields).whenComplete { json, _ -> if (json != null) answeredGeneration = gen }
    }

    private fun setPending(code: String) {
        if (pending == code) return
        pending = code
        changed()
    }

    private fun append(entry: HistoryEntry) {
        history += entry
        changed()
    }

    private fun changed() = listeners.forEach { it() }

    private fun onEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.defaultModalityState(), project.disposed)

    companion object {
        private val LOG = logger<ReplSession>()

        fun getInstance(project: Project) = project.service<ReplSession>()

        fun referenceFields(ref: ReferenceInfo): Map<String, Any?> = when (ref.kind) {
            "nuget" -> mapOf("kind" to ref.kind, "name" to ref.pathOrName, "version" to ref.version)
            else -> mapOf("kind" to ref.kind, "path" to ref.pathOrName)
        }
    }
}
