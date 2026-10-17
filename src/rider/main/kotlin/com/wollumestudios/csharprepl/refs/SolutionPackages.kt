package com.wollumestudios.csharprepl.refs

import org.w3c.dom.Element
import org.w3c.dom.NodeList
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** One version of a package used by [projects] (project names). [version] may be an MSBuild property like `$(Foo)`. */
data class PackageUse(val version: String, val projects: List<String>) {
    val isProperty get() = version.contains("$(")
}

data class SolutionPackage(val id: String, val uses: List<PackageUse>) {
    /** The literal version used by most projects; null when every version is an MSBuild property. */
    val preferredVersion get() = uses.filter { !it.isProperty }.maxByOrNull { it.projects.size }?.version
}

/**
 * The explicit packages of a set of project files: `<PackageReference Include Version>`, with the version from the nearest
 * `Directory.Packages.props` (central package management) when the reference has none, and `VersionOverride` winning.
 * Plain XML, no IDE. Implicit and transitive packages never appear in a project file. Unreadable files are skipped.
 */
object SolutionPackages {
    private const val PROPS = "Directory.Packages.props"
    private val SKIP_DIRS = setOf("bin", "obj", "node_modules", ".git", ".idea", ".vs")

    fun scan(projectFiles: List<File>): List<SolutionPackage> {
        val central = HashMap<File, Map<String, String>>() // props file -> lower-case id -> version
        val byId = LinkedHashMap<String, Pair<String, LinkedHashMap<String, LinkedHashSet<String>>>>() // lower id -> (id, version -> projects)
        for (file in projectFiles.distinctBy { it.absoluteFile.normalize() }) {
            val root = parse(file) ?: continue
            val props by lazy { nearestProps(file)?.let { p -> central.getOrPut(p) { packageVersions(p) } }.orEmpty() }
            for (ref in root.getElementsByTagName("PackageReference").elements()) {
                val id = ref.value("Include") ?: continue
                val version = ref.value("VersionOverride") ?: ref.value("Version") ?: props[id.lowercase()] ?: continue
                byId.getOrPut(id.lowercase()) { id to LinkedHashMap() }.second.getOrPut(version) { LinkedHashSet() }.add(file.nameWithoutExtension)
            }
        }
        return byId.values.map { (id, versions) -> SolutionPackage(id, versions.map { (v, p) -> PackageUse(v, p.toList()) }) }
            .sortedBy { it.id.lowercase() }
    }

    /** `*.csproj` files under [root] (not in build output or tool folders), for when Rider's project model is unavailable. */
    fun findProjectFiles(root: File, maxDepth: Int = 6): List<File> {
        val found = ArrayList<File>()
        fun walk(dir: File, depth: Int) {
            for (f in dir.listFiles().orEmpty().sortedBy { it.name }) {
                if (f.isDirectory) { if (depth < maxDepth && f.name !in SKIP_DIRS) walk(f, depth + 1) }
                else if (f.extension.equals("csproj", true)) found += f
            }
        }
        walk(root, 0)
        return found
    }

    private fun nearestProps(project: File): File? =
        generateSequence(project.absoluteFile.parentFile) { it.parentFile }.map { File(it, PROPS) }.firstOrNull { it.isFile }

    private fun packageVersions(props: File): Map<String, String> =
        parse(props)?.getElementsByTagName("PackageVersion")?.elements().orEmpty()
            .mapNotNull { e -> e.value("Include")?.let { id -> e.value("Version")?.let { id.lowercase() to it } } }.toMap()

    private fun parse(file: File): Element? = try {
        DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false // old-style projects carry an xmlns; plain tag names match either way
        }.newDocumentBuilder().apply {
            setErrorHandler(object : ErrorHandler { // no stderr noise for malformed files
                override fun warning(e: SAXParseException) {}
                override fun error(e: SAXParseException) {}
                override fun fatalError(e: SAXParseException) = throw e
            })
        }.parse(file).documentElement
    } catch (e: Exception) {
        null
    }

    private fun NodeList.elements() = (0 until length).map { item(it) as Element }

    /** Attribute or child element of that name, non-blank. */
    private fun Element.value(name: String): String? =
        (getAttribute(name).takeIf { it.isNotBlank() } ?: getElementsByTagName(name).elements().firstOrNull()?.textContent)?.trim()?.takeIf { it.isNotEmpty() }
}
