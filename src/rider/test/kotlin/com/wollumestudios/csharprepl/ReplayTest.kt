package com.wollumestudios.csharprepl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Rebuild & replay: what [replayCommands] picks from the history, as ReplSession runs it (the new host's session starts at the end of the history). */
class ReplayTest {
    private fun ok(code: String) = HistoryEntry(code, "ok")

    /** One Rebuild & replay: the note, then the picked commands run (and succeed) in the new host. */
    private fun replay(history: List<HistoryEntry>, sessionStart: Int): Pair<List<HistoryEntry>, Int> {
        val commands = replayCommands(history, sessionStart)
        return history + HistoryEntry.note("Session restarted, replaying ${commands.size} command(s)") + commands.map(::ok) to history.size
    }

    @Test
    fun `two consecutive replays run each original command once`() {
        val original = listOf(ok("var a = 1;"), HistoryEntry("bad", "compileError"), HistoryEntry("throw null;", "threw"), ok("a++;"))
        val (first, start1) = replay(original, 0)
        val (second, start2) = replay(first, start1)
        assertEquals(listOf("var a = 1;", "a++;"), replayCommands(first, start1))
        assertEquals(listOf("var a = 1;", "a++;"), replayCommands(second, start2))
    }

    @Test
    fun `a replay after a restore does not rerun the restore script`() {
        // Restore: "Restoring variables…", new host, then the restore script runs; the user goes on in that host.
        val beforeRestore = listOf(ok("var a = 1;"), HistoryEntry.note("Restoring variables…"))
        val restored = beforeRestore + HistoryEntry.note("Session restarted, running the restore script") +
            HistoryEntry("var a = 1;", "ok", restore = true) + ok("a += 2;")
        assertEquals(listOf("a += 2;"), replayCommands(restored, beforeRestore.size))
    }

    @Test
    fun `commands of earlier hosts are not replayed`() {
        val history = listOf(ok("var old = 1;"), HistoryEntry.note("Stopped — session restarted, variables lost"), ok("var b = 2;"))
        assertEquals(listOf("var b = 2;"), replayCommands(history, 2))
    }
}
