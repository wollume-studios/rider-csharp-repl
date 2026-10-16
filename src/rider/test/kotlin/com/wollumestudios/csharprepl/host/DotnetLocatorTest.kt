package com.wollumestudios.csharprepl.host

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

// Note: a Path is an Iterable<Path>, so `set - path` would remove its name parts; minusElement removes the path.
class DotnetLocatorTest {
    private val home = Paths.get("home", "me").toString()

    private fun find(env: Map<String, String>, files: Set<Path>, windows: Boolean = false) =
        DotnetLocator.find(env::get, files::contains, windows, home)

    @Test
    fun `PATH wins, in PATH order`() {
        val files = setOf(Paths.get("/b", "dotnet"), Paths.get("/c", "dotnet"), Paths.get("/root", "dotnet"), Paths.get("/usr/share/dotnet", "dotnet"))
        assertEquals(Paths.get("/b", "dotnet"), find(mapOf("PATH" to "/a:/b:/c", "DOTNET_ROOT" to "/root"), files))
    }

    @Test
    fun `then DOTNET_ROOT, then the user's dotnet, then the system locations`() {
        val all = setOf(Paths.get("/root", "dotnet"), Paths.get(home, ".dotnet", "dotnet"), Paths.get("/usr/local/share/dotnet", "dotnet"), Paths.get("/usr/share/dotnet", "dotnet"))
        val env = mapOf("PATH" to "/a", "DOTNET_ROOT" to "/root")
        assertEquals(Paths.get("/root", "dotnet"), find(env, all))
        assertEquals(Paths.get(home + File.separator + ".dotnet", "dotnet"), find(env, all.minusElement(Paths.get("/root", "dotnet"))))
        assertEquals(Paths.get("/usr/local/share/dotnet", "dotnet"), find(emptyMap(), all.minusElement(Paths.get("/root", "dotnet")).minusElement(Paths.get(home, ".dotnet", "dotnet"))))
        assertEquals(Paths.get("/usr/share/dotnet", "dotnet"), find(emptyMap(), setOf(Paths.get("/usr/share/dotnet", "dotnet"))))
    }

    @Test
    fun `Windows uses dotnet exe, semicolons and Program Files`() {
        assertEquals(Paths.get("D:\\tools", "dotnet.exe"),
            find(mapOf("PATH" to "C:\\x;\"D:\\tools\""), setOf(Paths.get("D:\\tools", "dotnet.exe"), Paths.get("D:\\tools", "dotnet")), windows = true))
        assertEquals(Paths.get("C:\\Program Files\\dotnet", "dotnet.exe"),
            find(mapOf("PATH" to "C:\\x"), setOf(Paths.get("C:\\Program Files\\dotnet", "dotnet.exe")), windows = true))
    }

    @Test
    fun `nothing found is null`() = assertNull(find(mapOf("PATH" to "/a"), emptySet()))
}
