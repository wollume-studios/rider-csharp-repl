package com.wollumestudios.csharprepl

import com.wollumestudios.csharprepl.host.AnalyzeResponse

sealed interface EnterAction {
    data class Run(val code: String) : EnterAction
    data object NewLine : EnterAction
    data class ToPending(val line: String) : EnterAction
}

/** The code `analyze` gets and a run submits: pending and input joined by a newline. */
fun combine(pending: String, input: String) = if (pending.isEmpty()) input else "$pending\n$input"

/** The spec's Enter / Shift+Enter table. [analysis] is the host's answer for [combine] (pending, input); ignored for Shift+Enter. */
fun decide(autoRun: Boolean, shift: Boolean, pending: String, input: String, analysis: AnalyzeResponse): EnterAction {
    val code = combine(pending, input)
    return when {
        autoRun && shift -> EnterAction.NewLine
        autoRun -> when {
            analysis.complete -> EnterAction.Run(code)
            analysis.fixed != null -> EnterAction.Run(analysis.fixed)
            else -> EnterAction.NewLine
        }
        shift || analysis.complete -> EnterAction.Run(code)
        else -> EnterAction.ToPending(input)
    }
}

/** What an Enter's answer depends on, taken at the keypress and again when `analyze` answers. */
data class EnterSnapshot(val pending: String, val generation: Int, val resets: Int)

/** The answer is dropped (nothing runs) when pending code was cleared or changed, or the session restarted or reset meanwhile. */
fun isStale(atKey: EnterSnapshot, now: EnterSnapshot) = atKey != now

/**
 * Rebuild & replay: the successful user commands of the current host's session, which starts at [sessionStart] in
 * [history]. Earlier hosts' commands live on in the replays that followed them; the restore script is not a user command.
 */
fun replayCommands(history: List<HistoryEntry>, sessionStart: Int): List<String> =
    history.drop(sessionStart).filter { it.status == "ok" && !it.restore }.map { it.code }

/** Up/Down through submitted commands ([entries], oldest first); the text being edited comes back after the newest. */
class InputHistory(private val entries: () -> List<String>) {
    private var index = -1 // steps back from the newest; -1 = the draft
    private var draft = ""

    /** The older command, or null at the oldest one. */
    fun up(current: String): String? {
        val e = entries()
        index = minOf(index, e.size - 1) // the history may have shrunk (reset) while browsing
        if (index + 1 >= e.size) return null
        if (index == -1) draft = current
        index++
        return e[e.size - 1 - index]
    }

    /** The newer command, the draft after the newest, or null when already on the draft. */
    fun down(): String? {
        if (index == -1) return null
        val e = entries()
        index = minOf(index - 1, e.size - 1) // the history may have shrunk (reset) while browsing
        return if (index == -1) draft else e[e.size - 1 - index]
    }

    fun reset() {
        index = -1
    }
}
