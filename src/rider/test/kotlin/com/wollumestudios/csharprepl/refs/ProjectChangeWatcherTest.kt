package com.wollumestudios.csharprepl.refs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

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

    @Test
    fun `a build output newer than since is a change, an older one is not`() {
        assertTrue(ProjectChangeWatcher.builtSince(sequenceOf(100L, 250L), since = 200))
        assertFalse(ProjectChangeWatcher.builtSince(sequenceOf(100L, 200L), since = 200))
        assertFalse(ProjectChangeWatcher.builtSince(emptySequence(), since = 0))
    }

    @Test
    fun `raising the banner moves since, so after Ignore an unchanged build does not raise it again`() {
        val watched = listOf(WatchedProject("C:\\s\\Lib\\Lib.csproj", "Lib", lib, 100), WatchedProject("C:\\s\\B\\B.csproj", "B", listOf("C:/s/B"), 100))
        val output = sequenceOf(150L) // the build that raised the banner wrote this
        assertTrue(ProjectChangeWatcher.builtSince(output, watched[0].since))
        val after = ProjectChangeWatcher.raised(watched, "C:\\s\\Lib\\Lib.csproj", now = 200)
        assertEquals(200, after[0].since)
        assertEquals(100, after[1].since) // other projects keep theirs
        assertFalse(ProjectChangeWatcher.builtSince(output, after[0].since)) // next build, nothing rewritten
        assertTrue(ProjectChangeWatcher.builtSince(output + 300L, after[0].since)) // next build rewrote the DLL
    }

    @Test
    fun `two routes to the same project file give one canonical path`(@TempDir dir: Path) {
        val csproj = Files.createDirectories(dir.resolve("Lib")).resolve("Lib.csproj").also { Files.writeString(it, "<Project/>") }
        val roundabout = dir.resolve("Lib").resolve("..").resolve("Lib").resolve("Lib.csproj").toString()
        assertEquals(ProjectChangeWatcher.canonical(csproj.toString()), ProjectChangeWatcher.canonical(roundabout))
        val missing = dir.resolve("x").resolve("..").resolve("Gone.csproj").toString()
        assertEquals(dir.resolve("Gone.csproj").toAbsolutePath().normalize().toString(), ProjectChangeWatcher.canonical(missing))
    }
}
