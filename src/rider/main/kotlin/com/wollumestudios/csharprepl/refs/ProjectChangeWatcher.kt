package com.wollumestudios.csharprepl.refs

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rider.build.BuildHost
import com.wollumestudios.csharprepl.ReplSession
import java.io.File
import java.nio.file.Path

/**
 * A referenced project as the change watcher sees it: [path] is its canonical project file (the key), [dirs] its directory
 * and ProjectReference closure, [since] the time after which a build output counts as a change.
 */
data class WatchedProject(val path: String, val name: String, val dirs: List<String>, val since: Long)

/** Pure logic of the change watcher (no platform types, so the plain unit tests can load it). */
object ProjectChangeWatcher {
    private val ignored = setOf("bin", "obj", ".idea", ".vs")

    /** True when [changedPath] is inside one of [sourceDirs] and not under a build-output or IDE folder. Case and slash insensitive. */
    fun isRelevant(changedPath: String, sourceDirs: List<String>): Boolean {
        val path = normalize(changedPath)
        return sourceDirs.any { dir ->
            val d = normalize(dir)
            path.startsWith("$d/") && path.substring(d.length + 1).split('/').none { it in ignored }
        }
    }

    /** A build changed the project when one of its output files ([mtimes]) is newer than [since]. */
    fun builtSince(mtimes: Sequence<Long>, since: Long) = mtimes.any { it > since }

    /** Raising the banner for [path] moves its `since` to [now]: after Ignore, only newer build outputs raise it again. */
    fun raised(watched: List<WatchedProject>, path: String, now: Long) =
        watched.map { if (it.path == path) it.copy(since = now) else it }

    /** One spelling per file (real path when it exists, else absolute and normalized), so two routes to a project don't reference it twice. */
    fun canonical(path: String): String = try {
        Path.of(path).toRealPath().toString()
    } catch (e: Exception) { // missing file, or an invalid path
        runCatching { Path.of(path).toAbsolutePath().normalize().toString() }.getOrDefault(path)
    }

    // ponytail: always case-insensitive (Windows); on a case-sensitive file system this only risks an extra banner
    private fun normalize(p: String) = p.replace('\\', '/').trimEnd('/').lowercase()
}

/**
 * Tells the session when a referenced project changed: saved or externally changed files (VFS, registered in plugin.xml)
 * and finished Rider builds ([watchBuilds]). The session shows the banner (once per burst: it ignores changes while shown).
 */
class ProjectChangeListener(private val project: Project) : BulkFileListener {
    override fun after(events: List<VFileEvent>) { // EDT / write thread: string checks only
        val session = project.serviceIfCreated<ReplSession>() ?: return
        val watched = session.watched.takeIf { it.isNotEmpty() } ?: return
        val hit = watched.firstOrNull { w -> events.any { ProjectChangeWatcher.isRelevant(it.path, w.dirs) } } ?: return
        ApplicationManager.getApplication().invokeLater({ session.projectChanged(hit.path) }, project.disposed)
    }

    companion object {
        /**
         * Rider builds run in its backend: ProjectTaskListener does not fire for them, BuildHost.building does. After a build,
         * a referenced project whose bin/ got a file newer than its `since` counts as changed.
         */
        fun watchBuilds(project: Project, parent: Disposable) {
            val lifetime = LifetimeDefinition()
            Disposer.register(parent) { lifetime.terminate() }
            var wasBuilding = false
            BuildHost.getInstance(project).building.advise(lifetime) { building ->
                if (wasBuilding && !building) ApplicationManager.getApplication().executeOnPooledThread {
                    val session = project.serviceIfCreated<ReplSession>() ?: return@executeOnPooledThread
                    session.watched.firstOrNull(::builtSince)?.let { w ->
                        ApplicationManager.getApplication().invokeLater({ session.projectChanged(w.path) }, project.disposed)
                    }
                }
                wasBuilding = building
            }
        }

        // ponytail: looks in <project dir>/bin only; a custom OutputPath/ArtifactsPath is missed (the save watcher still covers source edits)
        private fun builtSince(w: WatchedProject) = w.dirs.any { dir ->
            ProjectChangeWatcher.builtSince(File(dir, "bin").walk().filter { it.isFile }.map { it.lastModified() }, w.since)
        }
    }
}
