package com.wollumestudios.csharprepl.host

import java.nio.file.Path
import java.nio.file.Paths

object HostLocator {
    /** The system property `csharprepl.host` (tests, runIde overrides) or `<plugin>/host/CSharpRepl.Host.dll`; the plugin jar lives in `<plugin>/lib/`. */
    fun hostDll(): Path {
        System.getProperty("csharprepl.host")?.let { return Paths.get(it) }
        val jar = Paths.get(HostLocator::class.java.protectionDomain.codeSource.location.toURI())
        return jar.parent.parent.resolve("host").resolve("CSharpRepl.Host.dll")
    }
}
