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
import com.wollumestudios.csharprepl.host.DotnetLocator
import com.wollumestudios.csharprepl.host.AnalyzeResponse
import com.wollumestudios.csharprepl.host.ClassifyResponse
import com.wollumestudios.csharprepl.host.CompleteResponse
import com.wollumestudios.csharprepl.host.DumpResponse
import com.wollumestudios.csharprepl.host.ExpandResponse
import com.wollumestudios.csharprepl.host.ExportResponse
import com.wollumestudios.csharprepl.host.HostLocator
import com.wollumestudios.csharprepl.host.HostProcess
import com.wollumestudios.csharprepl.host.ReferenceInfo
import com.wollumestudios.csharprepl.host.RestoreScriptResponse
import com.wollumestudios.csharprepl.host.RunResponse
import com.wollumestudios.csharprepl.host.SemanticSpan
import com.wollumestudios.csharprepl.host.Var
import com.wollumestudios.csharprepl.host.VariablesResponse
import com.wollumestudios.csharprepl.refs.ProjectChangeListener
import com.wollumestudios.csharprepl.refs.ProjectChangeWatcher
import com.wollumestudios.csharprepl.refs.WatchedProject
import com.wollumestudios.csharprepl.settings.ReplSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One history item. [status] is the host's "ok" / "threw" / "compileError", or "running" (sent, no answer yet: the
 * entry is replaced in place when the answer comes), "error" (the host failed the run), "stopped" (killed by Stop or a
 * restart), "note" (a session message in [output], no code). [restore]: the restore script, not a user command.
 */
data class HistoryEntry(
    val code: String,
    val status: String,
    val output: String = "",
    val result: String? = null,
    val diagnostics: List<String> = emptyList(),
    val exception: String? = null,
    val restore: Boolean = false,
    /** Semantic classes of [code]'s identifiers (offsets in [code]), from the host's `run` answer. */
    val spans: List<SemanticSpan> = emptyList(),
) {
    companion object {
        fun note(text: String) = HistoryEntry("", "note", text)
    }
}

enum class RestartMode { Empty, Replay, Restore }

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
    /** The "running" history entries of runs sent to the current host and not answered yet (by identity). */
    private val inFlight = mutableListOf<HistoryEntry>()

    private val entries = mutableListOf<HistoryEntry>()
    val history: List<HistoryEntry> get() = entries
    /** Index in [history] where the current host's session starts: Replay re-runs only what that host ran. */
    private var sessionStart = 0
    /** Bumped by [reset]: the view clears its history display when it changes. */
    var resets = 0
        private set
    /** True while Enter waits for `analyze`: actions that read or change pending/input are disabled meanwhile. */
    var analyzing = false
        private set
    /** Re-added in this order on every host start. Change it through [addReference] / [removeReference]. */
    val references = mutableListOf<ReferenceInfo>()
    /** The referenced projects for the change watcher, which reads it off the EDT (immutable list, replaced on change). */
    @Volatile var watched: List<WatchedProject> = emptyList()
        private set
    /** Name of the referenced project the banner reports as changed, or null (no banner). */
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

    /**
     * Runs [code] as is: it enters the history right away as a "running" entry (nothing runs unseen), which is replaced
     * in place by the answer; then the variables are refreshed. Clears pending.
     */
    fun submit(code: String, restore: Boolean = false) {
        if (code.isBlank()) return
        start()
        setPending("")
        val gen = generation
        if (host == null) { append(HistoryEntry(code, "error", exception = "No host process", restore = restore)); return }
        val running = HistoryEntry(code, "running", restore = restore)
        inFlight += running
        append(running)
        request("run", mapOf("code" to code)).whenComplete { json, error ->
            onEdt {
                if (gen != generation) return@onEdt // stopped or restarted: the entry was already marked
                inFlight.removeIf { it === running }
                val done = if (json != null) gson.fromJson(json, RunResponse::class.java).let { r ->
                    // `#r "nuget: ..."` typed in code: later hosts re-add it and the gear lists it, as if added through the UI
                    r.addedReferences.orEmpty().forEach { if (it !in references) references += it }
                    HistoryEntry(code, r.status, r.output, r.result, r.diagnostics, r.exception, restore, r.spans.orEmpty())
                }
                else HistoryEntry(code, "error", exception = (error?.cause ?: error)?.message ?: "run failed", restore = restore)
                replace(running, done)
                refreshVariables()
            }
        }
    }

    /** Marks the unanswered runs of the host being dropped (their answers will never be shown). */
    private fun endInFlight(entry: (HistoryEntry) -> HistoryEntry) {
        inFlight.forEach { replace(it, entry(it)) }
        inFlight.clear()
    }

    private fun replace(old: HistoryEntry, new: HistoryEntry) {
        val i = entries.indexOfFirst { it === old }
        if (i < 0) return // cleared by Reset
        entries[i] = new
        changed()
    }

    fun clearPending() = setPending("")

    /**
     * Completion at [caret] in [input] with the pending code before it (the host compiles pending + input as the next
     * submission). Completes on the EDT with spans relative to [input], or null when there is no answer or it came from
     * a host that has been replaced meanwhile. Never starts a host.
     */
    fun complete(input: String, caret: Int): CompletableFuture<CompleteResponse?> {
        val result = CompletableFuture<CompleteResponse?>()
        val gen = generation
        val prefix = if (pending.isEmpty()) "" else pending + "\n"
        request("complete", mapOf("code" to prefix + input, "position" to prefix.length + caret)).whenComplete { json, _ ->
            onEdt {
                val r = json?.takeIf { gen == generation }?.let { gson.fromJson(it, CompleteResponse::class.java) }
                result.complete(r?.copy(start = r.start - prefix.length))
            }
        }
        return result
    }

    /**
     * Semantic classes of [input]'s identifiers, compiled after the pending code as the next submission. Completes on the
     * EDT with spans relative to [input] (those in the pending code dropped), or null like [complete]. Never starts a host.
     */
    fun classify(input: String): CompletableFuture<List<SemanticSpan>?> {
        val result = CompletableFuture<List<SemanticSpan>?>()
        val gen = generation
        val prefix = if (pending.isEmpty()) "" else pending + "\n"
        request("classify", mapOf("code" to prefix + input)).whenComplete { json, _ ->
            onEdt {
                val spans = json?.takeIf { gen == generation }?.let { gson.fromJson(it, ClassifyResponse::class.java).spans.orEmpty() }
                result.complete(spans?.let { relativeToInput(it, prefix.length) })
            }
        }
        return result
    }

    fun expand(handle: Int): CompletableFuture<List<Var>> =
        request("expand", mapOf("handle" to handle)).thenApply { gson.fromJson(it, ExpandResponse::class.java).children }

    /** The session as a compiling file-based app (the host's `export`); completes off the EDT. [filePath]: where it will be saved. */
    fun export(filePath: String, excludeThrown: Boolean): CompletableFuture<ExportResponse> {
        start()
        return request("export", mapOf("filePath" to filePath, "excludeThrown" to excludeThrown)).thenApply { gson.fromJson(it, ExportResponse::class.java) }
    }

    /** The current variables as `var` lines (the host's `dump`); completes off the EDT. */
    fun dump(filePath: String): CompletableFuture<DumpResponse> {
        start()
        return request("dump", mapOf("filePath" to filePath)).thenApply { gson.fromJson(it, DumpResponse::class.java) }
    }

    /** Kills a run that doesn't finish: a fresh host, references kept, variables lost. */
    fun stop() {
        restart(RestartMode.Empty) // marks the running entries "stopped"
        append(HistoryEntry.note("Stopped — session restarted, variables lost"))
    }

    /** Banner / action Rebuild: a fresh host rebuilds the references; variables are cleared, the history stays. */
    fun rebuild() {
        restart(RestartMode.Empty)
        append(HistoryEntry.note("Rebuilt references — session restarted, variables cleared"))
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
     * Replaces the host, re-adding [references]. [RestartMode.Replay] re-runs the successful commands the current host ran;
     * [RestartMode.Restore] runs the old host's restore script (type declarations and variables as code). Both run visibly.
     */
    fun restart(mode: RestartMode) {
        when (mode) {
            RestartMode.Empty -> replaceHost()
            RestartMode.Replay -> {
                val ok = replayCommands(history, sessionStart)
                replaceHost()
                append(HistoryEntry.note("Session restarted, replaying ${ok.size} command(s)"))
                ok.forEach(::submit)
            }
            RestartMode.Restore -> {
                val gen = generation
                append(HistoryEntry.note("Restoring variables…"))
                request("restoreScript", emptyMap()).whenComplete { json, error ->
                    onEdt {
                        if (gen != generation) return@onEdt
                        replaceHost()
                        if (json == null) { append(HistoryEntry.note("Session restarted; variables could not be restored: ${(error?.cause ?: error)?.message}")); return@onEdt }
                        val r = gson.fromJson(json, RestoreScriptResponse::class.java)
                        append(HistoryEntry.note("Session restarted, running the restore script"))
                        submit(r.code, restore = true) // shown in the history as it runs
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
        watched = watched.filter { it.path != ref.pathOrName }
        restart(RestartMode.Empty)
        append(HistoryEntry.note("Removed ${ref.displayName}; session restarted, variables lost"))
    }

    /**
     * From the change watcher, with the referenced project file [path]: shows the banner unless one is up (one per burst)
     * or changes are ignored. Raising it moves the project's `since` to now, so after Ignore only newer builds count.
     */
    fun projectChanged(path: String) {
        val project = watched.firstOrNull { it.path == path } ?: return // removed meanwhile
        if (changedProject != null || ignoreUntilReset || ReplSettings.state.ignoreChangesForever) return
        watched = ProjectChangeWatcher.raised(watched, path, System.currentTimeMillis())
        changedProject = project.name
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
        watched = watched.filter { it.path != ref.pathOrName } + WatchedProject(ref.pathOrName, ref.displayName, dirs, System.currentTimeMillis())
    }

    override fun dispose() {
        generation++
        host?.kill()
        host = null
    }

    private fun replaceHost() {
        generation++
        endInFlight { it.copy(status = "stopped") }
        changedProject = null // the new host rebuilds the references
        host?.kill() // killed = true: onExit stays quiet, the caller reports
        host = null
        variables = emptyList()
        startHost()
        changed()
    }

    private fun startHost() {
        val gen = generation
        sessionStart = entries.size // what the history shows so far never ran in this host (0 after a reset)
        host = try {
            HostProcess(HostLocator.hostDll(), onExit = { code, killed -> if (!killed) onEdt { hostExited(gen, code) } }, log = LOG::info,
                workingDir = workingDir(project.basePath))
        } catch (e: IOException) {
            LOG.warn(e)
            val note = HistoryEntry.note(
                if (DotnetLocator.dotnet == null) "dotnet not found (PATH, DOTNET_ROOT, default install locations) — install the .NET 8+ SDK"
                else "C# REPL host could not start: ${e.message}")
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
        endInFlight { it.copy(status = "error", exception = "Host exited (code $code)") }
        if (answeredGeneration != gen) { // never came up: restarting would loop
            generation++
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
