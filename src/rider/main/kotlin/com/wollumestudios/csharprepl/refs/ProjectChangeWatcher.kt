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
import com.wollumestudios.csharprepl.WatchedProject
import java.io.File

/** Pure path logic (kept free of platform types so the plain unit tests can load it). */
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
        ApplicationManager.getApplication().invokeLater({ session.projectChanged(hit.name) }, project.disposed)
    }

    companion object {
        /**
         * Rider builds run in its backend: ProjectTaskListener does not fire for them, BuildHost.building does. After a build,
         * a referenced project whose bin/ got a file newer than the reference counts as changed.
         */
        fun watchBuilds(project: Project, parent: Disposable) {
            val lifetime = LifetimeDefinition()
            Disposer.register(parent) { lifetime.terminate() }
            var wasBuilding = false
            BuildHost.getInstance(project).building.advise(lifetime) { building ->
                if (wasBuilding && !building) ApplicationManager.getApplication().executeOnPooledThread {
                    val session = project.serviceIfCreated<ReplSession>() ?: return@executeOnPooledThread
                    session.watched.firstOrNull(::builtSince)?.let { w ->
                        ApplicationManager.getApplication().invokeLater({ session.projectChanged(w.name) }, project.disposed)
                    }
                }
                wasBuilding = building
            }
        }

        // ponytail: looks in <project dir>/bin only; a custom OutputPath/ArtifactsPath is missed (the save watcher still covers source edits)
        private fun builtSince(w: WatchedProject) =
            w.dirs.any { dir -> File(dir, "bin").walk().any { it.isFile && it.lastModified() > w.since } }
    }
}
