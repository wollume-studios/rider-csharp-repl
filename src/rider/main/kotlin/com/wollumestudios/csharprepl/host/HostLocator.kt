package com.wollumestudios.csharprepl.host

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import java.nio.file.Path
import java.nio.file.Paths

object HostLocator {
    /**
     * The system property `csharprepl.host` (tests, runIde overrides) or `<plugin>/host/CSharpRepl.Host.dll`. The plugin
     * directory comes from the plugin manager: the plugin class loader leaves the jar's CodeSource location null.
     */
    fun hostDll(): Path {
        System.getProperty("csharprepl.host")?.let { return Paths.get(it) }
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("com.wollumestudios.csharprepl"))
            ?: error("C# REPL plugin descriptor not found")
        return plugin.pluginPath.resolve("host").resolve("CSharpRepl.Host.dll")
    }
}
