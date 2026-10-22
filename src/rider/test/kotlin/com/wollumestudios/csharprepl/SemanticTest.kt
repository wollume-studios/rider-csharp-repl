package com.wollumestudios.csharprepl

import com.wollumestudios.csharprepl.host.SemanticSpan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SemanticTest {
    @Test
    fun `every host kind has a Rider C# key`() {
        // Classifier.Kind's kinds (src/host/CSharpRepl.Host/Classifier.cs)
        val kinds = "namespace class staticClass record struct recordStruct interface enum delegate typeParameter method staticMethod " +
            "extensionMethod methodDeclaration staticMethodDeclaration extensionMethodDeclaration localFunction property staticProperty field staticField constant enumMember event local parameter"
        for (kind in kinds.split(' ')) assert(SEMANTIC_KEYS[kind]?.startsWith("ReSharper.CSHARP_") == true) { "no key for $kind" }
        assertEquals("ReSharper.CSHARP_EXTENSION_METHOD_CALL_IDENTIFIER", SEMANTIC_KEYS["extensionMethod"])
        assertEquals("ReSharper.CSHARP_LOCAL_VARIABLE_IDENTIFIER", SEMANTIC_KEYS["local"])
    }

    @Test
    fun `spans move by the history's indent after each line break`() {
        val code = "var a = 1;\nvar b = a;\n\nb.ToString()"
        val spans = listOf(SemanticSpan(4, 1, "local"), SemanticSpan(15, 1, "local"), SemanticSpan(19, 1, "local"), SemanticSpan(23, 1, "local"), SemanticSpan(25, 8, "method"))
        val moved = indented(spans, code, 2)
        val shown = code.replace("\n", "\n  ")
        assertEquals(listOf("a", "b", "a", "b", "ToString"), moved.map { shown.substring(it.start, it.start + it.length) })
    }

    @Test
    fun `spans in the pending code are dropped, the rest become relative to the input`() {
        val pending = "var list = new List<int>();"
        val input = "list.Count"
        val prefix = pending + "\n"
        val spans = listOf(SemanticSpan(4, 4, "local"), SemanticSpan(15, 4, "class"), SemanticSpan(prefix.length, 4, "local"), SemanticSpan(prefix.length + 5, 5, "property"))
        assertEquals(listOf(SemanticSpan(0, 4, "local"), SemanticSpan(5, 5, "property")), relativeToInput(spans, prefix.length))
        assertEquals("Count", input.substring(5, 10))
    }
}
