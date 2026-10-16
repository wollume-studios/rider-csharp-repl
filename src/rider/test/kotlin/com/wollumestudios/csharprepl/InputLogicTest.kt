package com.wollumestudios.csharprepl

import com.wollumestudios.csharprepl.host.AnalyzeResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The spec's Enter / Shift+Enter table. */
class InputLogicTest {
    private fun analyze(complete: Boolean, fixed: String?) = AnalyzeResponse(complete, fixed)
    private val any = analyze(false, null)

    @Test
    fun `auto Enter appends the missing semicolon and runs`() =
        assertEquals(EnterAction.Run("var r = 0;"), decide(true, false, "", "var r = 0", analyze(false, "var r = 0;")))

    @Test
    fun `auto Enter on an incomplete statement inserts a new line`() =
        assertEquals(EnterAction.NewLine, decide(true, false, "", "for (;;)", analyze(false, null)))

    @Test
    fun `auto Enter on complete input runs it`() =
        assertEquals(EnterAction.Run("1 + 1"), decide(true, false, "", "1 + 1", analyze(true, null)))

    @Test
    fun `auto Shift+Enter inserts a new line`() =
        assertEquals(EnterAction.NewLine, decide(true, true, "", "var r = 0", any))

    @Test
    fun `manual Enter on incomplete input moves it to pending`() =
        assertEquals(EnterAction.ToPending("var r = 0"), decide(false, false, "", "var r = 0", analyze(false, "var r = 0;")))

    @Test
    fun `manual Enter runs pending plus input once complete`() =
        assertEquals(EnterAction.Run("var r = 0\nvar c = 0;"), decide(false, false, "var r = 0", "var c = 0;", analyze(true, null)))

    @Test
    fun `manual Shift+Enter runs pending plus input as is`() =
        assertEquals(EnterAction.Run("var r = 0\nx"), decide(false, true, "var r = 0", "x", any))

    @Test
    fun `analyzed code joins pending and input with a newline`() {
        assertEquals("var r = 0\nvar c = 0;", combine("var r = 0", "var c = 0;"))
        assertEquals("x", combine("", "x"))
    }

    @Test
    fun `history walks back, stops at the oldest and returns the draft`() {
        val h = InputHistory { listOf("a", "b") }
        assertEquals("b", h.up("draft"))
        assertEquals("a", h.up("b"))
        assertEquals(null, h.up("a"))
        assertEquals("b", h.down())
        assertEquals("draft", h.down())
        assertEquals(null, h.down())
    }
}
