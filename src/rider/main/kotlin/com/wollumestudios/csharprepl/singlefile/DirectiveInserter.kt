package com.wollumestudios.csharprepl.singlefile

import com.wollumestudios.csharprepl.refs.ProjectChangeWatcher
import java.io.File
import java.nio.file.Path

/** Pure text helpers for file-based apps (`#!` shebang and `#:` directives at the top of a `.cs` file). */
object DirectiveInserter {
    /** Adds [directive] after the shebang and the existing `#:` directives at the top; a file that already has it is returned unchanged. */
    fun insert(text: String, directive: String): String {
        val nl = if ("\r\n" in text) "\r\n" else "\n"
        val lines = text.split(nl)
        if (lines.any { it.trim() == directive }) return text
        var i = if (lines.first().startsWith("#!")) 1 else 0
        while (i < lines.size && lines[i].startsWith("#:")) i++
        return (lines.take(i) + directive + lines.drop(i)).joinToString(nl)
    }

    /** A file with `#:` directives, or one that belongs to no solution project. */
    fun isSingleFileApp(text: String, inSolutionProject: Boolean) = !inSolutionProject || hasDirectives(text)

    fun hasDirectives(text: String) = text.lineSequence().any { it.startsWith("#:") }

    /**
     * SDK-style projects compile every `.cs` below their folder: a file belongs to the solution when the nearest folder
     * above it that holds a `.csproj` holds one of [projectFiles].
     */
    fun inSolutionProject(file: File, projectFiles: Collection<File>): Boolean {
        val projects = projectFiles.map { ProjectChangeWatcher.canonical(it.path) }.toSet()
        var dir: File? = File(ProjectChangeWatcher.canonical(file.path)).parentFile
        while (dir != null) {
            val csprojs = dir.listFiles { f -> f.extension.equals("csproj", true) }.orEmpty()
            if (csprojs.isNotEmpty()) return csprojs.any { ProjectChangeWatcher.canonical(it.path) in projects }
            dir = dir.parentFile
        }
        return false
    }

    /** `#:project` line for [csproj] as seen from the folder of [file]: relative, forward slashes (absolute across drives). */
    fun projectDirective(file: File, csproj: File): String {
        val target = Path.of(csproj.path)
        val path = try {
            Path.of(ProjectChangeWatcher.canonical(file.path)).parent.relativize(target)
        } catch (e: IllegalArgumentException) { // other drive
            target
        }
        return "#:project " + path.toString().replace('\\', '/')
    }
}
