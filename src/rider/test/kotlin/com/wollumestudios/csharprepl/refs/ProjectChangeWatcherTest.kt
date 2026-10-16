package com.wollumestudios.csharprepl.refs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProjectChangeWatcherTest {
    private val lib = listOf("C:/s/Lib")

    @Test
    fun `a source file under a referenced project is relevant`() = assertTrue(ProjectChangeWatcher.isRelevant("C:/s/Lib/A.cs", lib))

    @Test
    fun `build output and IDE folders are not`() {
        assertFalse(ProjectChangeWatcher.isRelevant("C:/s/Lib/obj/x.cs", lib))
        assertFalse(ProjectChangeWatcher.isRelevant("C:/s/Lib/bin/Debug/net8.0/Lib.dll", lib))
        assertFalse(ProjectChangeWatcher.isRelevant("C:/s/Lib/.idea/workspace.xml", lib))
    }

    @Test
    fun `files outside the referenced projects are not`() {
        assertFalse(ProjectChangeWatcher.isRelevant("C:/s/Other/A.cs", lib))
        assertFalse(ProjectChangeWatcher.isRelevant("C:/s/LibX/A.cs", lib))
    }

    @Test
    fun `paths compare case and slash insensitive`() {
        assertTrue(ProjectChangeWatcher.isRelevant("c:\\s\\lib\\A.cs", lib))
        assertTrue(ProjectChangeWatcher.isRelevant("C:/s/Lib/A.cs", listOf("C:\\s\\Lib\\")))
    }
}
