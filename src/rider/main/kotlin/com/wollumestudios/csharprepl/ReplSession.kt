package com.wollumestudios.csharprepl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.wollumestudios.csharprepl.host.AddReferenceResponse
import com.wollumestudios.csharprepl.host.AnalyzeResponse
import com.wollumestudios.csharprepl.host.ExpandResponse
import com.wollumestudios.csharprepl.host.HostLocator
import com.wollumestudios.csharprepl.host.HostProcess
import com.wollumestudios.csharprepl.host.ReferenceInfo
import com.wollumestudios.csharprepl.host.RestoreScriptResponse
import com.wollumestudios.csharprepl.host.RunResponse
import com.wollumestudios.csharprepl.host.Var
import com.wollumestudios.csharprepl.host.VariablesResponse
import com.wollumestudios.csharprepl.refs.ProjectChangeListener
import com.wollumestudios.csharprepl.settings.ReplSettings
import java.io.File
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

/** A referenced project as the change watcher sees it: its directory and ProjectReference closure, and when it was (re)built. */
data class WatchedProject(val name: String, val dirs: List<String>, val since: Long)

/** Short name for messages: the project name, the DLL file name, or "Package version". */
val ReferenceInfo.displayName: String
    get() = if (kind == "nuget") "$pathOrName $version" else File(pathOrName).let { if (kind == "project") it.nameWithoutExtension else it.name }

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

    private val entries = mutableListOf<HistoryEntry>()
    val history: List<HistoryEntry> get() = entries
    /** Bumped by [reset]: the view clears its history display when it changes. */
    var resets = 0
        private set
    /** True while Enter waits for `analyze`: actions that read or change pending/input are disabled meanwhile. */
    var analyzing = false
        private set
    /** Re-added in this order on every host start. Change it through [addReference] / [removeReference]. */
    val references = mutableListOf<ReferenceInfo>()
    private val projectDirs = mutableMapOf<ReferenceInfo, WatchedProject>()
    /** Snapshot of the referenced projects for the change watcher, which reads it off the EDT. */
    @Volatile var watched: List<WatchedProject> = emptyList()
        private set
    /** The referenced project the banner reports as changed, or null (no banner). */
    var changedProject: String? = null
        private set
    /** "Ignore for this session": no banner until the next [reset]. */
    var ignoreUntilReset = false
    var pending = ""
        private set
    var variables: List<Var> = emptyList()
        private set
    val running get() = inFlight.isNotEmpty()

    init {
        ProjectChangeListener.watchBuilds(project, this)
    }

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
     * [decide] (runs, or moves the line to pending), and completes on the EDT with what the input field must do, or
     * with null when the answer came too late (pending code or the session changed meanwhile: nothing ran).
     */
    fun enter(shift: Boolean, input: String): CompletableFuture<EnterAction?> {
        val result = CompletableFuture<EnterAction?>()
        if (analyzing) return result.apply { complete(null) }
        setAnalyzing(true)
        try {
            start()
            val autoRun = ReplSettings.state.autoRun
            val atKey = snapshot()
            fun apply(analysis: AnalyzeResponse) {
                try {
                    val action = if (isStale(atKey, snapshot())) null else decide(autoRun, shift, atKey.pending, input, analysis)
                    when (action) {
                        is EnterAction.Run -> submit(action.code)
                        is EnterAction.ToPending -> setPending(combine(atKey.pending, action.line))
                        EnterAction.NewLine, null -> {}
                    }
                    result.complete(action)
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                } finally {
                    setAnalyzing(false)
                }
            }
            val unknown = AnalyzeResponse(false, null) // Shift+Enter doesn't need an answer; a dead host answers nothing
            if (shift) apply(unknown)
            else request("analyze", mapOf("code" to combine(atKey.pending, input))).whenComplete { json, _ ->
                onEdt { apply(json?.let { gson.fromJson(it, AnalyzeResponse::class.java) } ?: unknown) }
            }
        } catch (e: Throwable) {
            setAnalyzing(false)
            result.completeExceptionally(e)
        }
        return result
    }

    private fun snapshot() = EnterSnapshot(pending, generation, resets)

    private fun setAnalyzing(value: Boolean) {
        analyzing = value
        changed()
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
        inFlight.forEach { entries += HistoryEntry(it, "stopped") }
        restart(RestartMode.Empty)
        append(HistoryEntry.note("Stopped — session restarted, variables lost"))
    }

    /** A fresh session: history, pending code and variables cleared, references kept. */
    fun reset() {
        entries.clear()
        resets++
        ignoreUntilReset = false
        setPending("")
        restart(RestartMode.Empty)
    }

    /**
     * Replaces the host, re-adding [references]. [RestartMode.Replay] re-runs the history's successful commands;
     * [RestartMode.Restore] runs the old host's restore script (type declarations and variables as code). Both run visibly.
     */
    fun restart(mode: RestartMode) {
        changedProject = null // the new host rebuilds the references
        changed()
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
                        if (!r.notReproducible.isNullOrEmpty()) notify(project, "Not restored: ${r.notReproducible.joinToString()}", NotificationType.WARNING)
                    }
                }
            }
        }
    }

    /** Builds or copies [ref] into the running session (a note shows meanwhile); on success later hosts re-add it too. */
    fun addReference(ref: ReferenceInfo) {
        if (ref in references) return append(HistoryEntry.note("${ref.displayName} is already referenced"))
        start()
        val gen = generation
        append(HistoryEntry.note(if (ref.kind == "dll") "Referencing ${ref.displayName}…" else "Building ${ref.displayName}…"))
        request("addReference", referenceFields(ref)).whenComplete { json, error ->
            onEdt {
                when {
                    gen != generation -> append(HistoryEntry.note("${ref.displayName} not referenced: the session restarted meanwhile"))
                    json == null -> append(HistoryEntry.note("${ref.displayName} not referenced: ${(error?.cause ?: error)?.message}"))
                    ref in references -> {} // dropped twice while building
                    else -> {
                        references += ref
                        watch(ref, json)
                        append(HistoryEntry.note("Referenced ${ref.displayName}"))
                    }
                }
            }
        }
    }

    /** Drops [ref] and restarts the host without it (variables are lost; the caller asked the user). */
    fun removeReference(ref: ReferenceInfo) {
        if (!references.remove(ref)) return
        projectDirs.remove(ref)
        watched = projectDirs.values.toList()
        restart(RestartMode.Empty)
        append(HistoryEntry.note("Removed ${ref.displayName}; session restarted, variables lost"))
    }

    /** From the change watcher: shows the banner for [name] unless one is up (one per burst) or changes are ignored. */
    fun projectChanged(name: String) {
        if (changedProject != null || ignoreUntilReset || ReplSettings.state.ignoreChangesForever || watched.none { it.name == name }) return
        changedProject = name
        changed()
    }

    /** Ignore: hides the banner until the next change. */
    fun dismissChange() {
        if (changedProject == null) return
        changedProject = null
        changed()
    }

    private fun watch(ref: ReferenceInfo, json: JsonObject) {
        if (ref.kind != "project") return
        val dirs = gson.fromJson(json, AddReferenceResponse::class.java).sourceDirs.orEmpty()
        projectDirs[ref] = WatchedProject(ref.displayName, dirs, System.currentTimeMillis())
        watched = projectDirs.values.toList()
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
        } ?: return
        references.forEach { ref -> // queued in order, before any run
            request("addReference", referenceFields(ref)).whenComplete { json, error ->
                onEdt {
                    if (gen != generation || ref !in references) return@onEdt
                    if (json != null) watch(ref, json) // rebuilt now: later builds compare against this
                    else append(HistoryEntry.note("Reference ${ref.displayName} not re-added: ${(error?.cause ?: error)?.message}"))
                }
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
        entries += entry
        changed()
    }

    private fun changed() = listeners.forEach { it() }

    private fun onEdt(action: () -> Unit) =
        ApplicationManager.getApplication().invokeLater(action, ModalityState.defaultModalityState(), project.disposed)

    companion object {
        private val LOG = logger<ReplSession>()

        fun getInstance(project: Project) = project.service<ReplSession>()

        fun notify(project: Project, text: String, type: NotificationType = NotificationType.INFORMATION) =
            NotificationGroupManager.getInstance().getNotificationGroup("CSharpRepl").createNotification(text, type).notify(project)

        fun referenceFields(ref: ReferenceInfo): Map<String, Any?> = when (ref.kind) {
            "nuget" -> mapOf("kind" to ref.kind, "name" to ref.pathOrName, "version" to ref.version)
            else -> mapOf("kind" to ref.kind, "path" to ref.pathOrName)
        }
    }
}
