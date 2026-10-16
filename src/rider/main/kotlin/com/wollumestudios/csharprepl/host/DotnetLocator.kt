package com.wollumestudios.csharprepl.host

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Finds the `dotnet` executable without relying on the IDE's PATH (a Rider started from the Dock or a desktop entry
 * often has none): PATH, then DOTNET_ROOT, then the default install locations. No IntelliJ classes.
 */
object DotnetLocator {
    /** Resolved once per IDE run; null when no dotnet was found. */
    val dotnet: Path? by lazy {
        find(System::getenv, Files::isRegularFile, System.getProperty("os.name").startsWith("Windows"), System.getProperty("user.home"))
    }

    fun find(env: (String) -> String?, exists: (Path) -> Boolean, windows: Boolean, home: String): Path? {
        val exe = if (windows) "dotnet.exe" else "dotnet"
        val pathDirs = env("PATH").orEmpty().split(if (windows) ';' else ':').map { it.trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        val dirs = pathDirs + listOfNotNull(env("DOTNET_ROOT")?.takeIf { it.isNotBlank() }) +
            listOf(home + File.separator + ".dotnet", "/usr/local/share/dotnet", "/usr/share/dotnet", "C:\\Program Files\\dotnet")
        return dirs.asSequence().mapNotNull { runCatching { Paths.get(it, exe) }.getOrNull() }.firstOrNull(exists)
    }
}
