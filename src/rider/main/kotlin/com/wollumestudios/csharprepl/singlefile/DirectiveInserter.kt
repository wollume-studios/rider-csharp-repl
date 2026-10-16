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

    fun hasDirectives(text: CharSequence) = text.lineSequence().any { it.startsWith("#:") }

    /** Cheap hint for menus: a `#!` shebang or `#:` directives. */
    fun looksLikeSingleFileApp(text: CharSequence) = text.startsWith("#!") || hasDirectives(text)

    /** The `.csproj` of the nearest folder above [file] that holds one (SDK-style projects compile every `.cs` below their folder), or null. */
    fun nearestProject(file: File): File? {
        var dir: File? = File(ProjectChangeWatcher.canonical(file.path)).parentFile
        while (dir != null) {
            dir.listFiles { f -> f.extension.equals("csproj", true) }?.minByOrNull { it.name }?.let { return it }
            dir = dir.parentFile
        }
        return null
    }

    /** A file belongs to the solution when its [nearestProject] is one of [projectFiles]. */
    fun inSolutionProject(file: File, projectFiles: Collection<File>): Boolean {
        val nearest = nearestProject(file) ?: return false
        return ProjectChangeWatcher.canonical(nearest.path) in projectFiles.map { ProjectChangeWatcher.canonical(it.path) }
    }

    /** `#:project` line for [csproj] as seen from the folder of [file]: relative, forward slashes (absolute across drives). */
    fun projectDirective(file: File, csproj: File): String {
        val target = Path.of(ProjectChangeWatcher.canonical(csproj.path))
        val path = try {
            Path.of(ProjectChangeWatcher.canonical(file.path)).parent.relativize(target)
        } catch (e: IllegalArgumentException) { // other drive
            target
        }
        return "#:project " + path.toString().replace('\\', '/')
    }
}
