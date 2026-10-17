package com.wollumestudios.csharprepl.refs

import com.google.gson.JsonParser
import java.math.BigInteger
import java.net.URLEncoder
import java.util.Locale

/**
 * nuget.org's search and flat-container JSON: URLs, response parsing, version order. No network here (see [NuGetPicker]).
 * ponytail: only nuget.org is searched; private feeds (NuGet.config sources) later via each source's service index.
 */
object NuGetApi {
    data class Hit(val id: String, val version: String, val downloads: Long, val description: String)

    fun searchUrl(query: String) =
        "https://azuresearch-usnc.nuget.org/query?q=${enc(query)}&take=20&prerelease=false&semVerLevel=2.0.0"

    fun versionsUrl(id: String) = "https://api.nuget.org/v3-flatcontainer/${enc(id.lowercase())}/index.json"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** `{"data":[{"id","version","totalDownloads","description"}]}`; entries without an id are dropped. */
    fun parseSearch(json: String): List<Hit> =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("data")?.mapNotNull { el ->
            val o = el.asJsonObject
            fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val id = str("id").ifEmpty { return@mapNotNull null }
            Hit(id, str("version"), o.get("totalDownloads")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0, oneLine(str("description")))
        }.orEmpty()

    /** `{"versions":["1.0.0",...]}` in the feed's order (ascending). */
    fun parseVersions(json: String): List<String> =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("versions")?.map { it.asString }.orEmpty()

    fun isPrerelease(v: String) = v.substringBefore('+').contains('-')

    /** Newest first by SemVer (so `2.0.0` comes before `2.0.0-beta`); prereleases only when [prerelease]. */
    fun sortVersions(versions: List<String>, prerelease: Boolean) =
        versions.distinct().filter { prerelease || !isPrerelease(it) }.sortedWith(Comparator(::compareVersions).reversed())

    /** SemVer 2 precedence (build metadata ignored). */
    fun compareVersions(a: String, b: String): Int {
        val (coreA, preA) = split(a)
        val (coreB, preB) = split(b)
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val c = coreA.getOrElse(i) { BigInteger.ZERO }.compareTo(coreB.getOrElse(i) { BigInteger.ZERO })
            if (c != 0) return c
        }
        if (preA.isEmpty() || preB.isEmpty()) return preB.size.compareTo(preA.size).coerceIn(-1, 1) // no prerelease is higher
        for (i in 0 until minOf(preA.size, preB.size)) {
            val x = preA[i]
            val y = preB[i]
            val nx = x.toBigIntegerOrNull()
            val ny = y.toBigIntegerOrNull()
            val c = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1 // numeric identifiers sort below alphanumeric ones
                ny != null -> 1
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (c != 0) return c
        }
        return preA.size.compareTo(preB.size)
    }

    private fun split(v: String): Pair<List<BigInteger>, List<String>> {
        val noMeta = v.trim().substringBefore('+')
        val core = noMeta.substringBefore('-').split('.').map { it.toBigIntegerOrNull() ?: BigInteger.ZERO }
        val pre = if ('-' in noMeta) noMeta.substringAfter('-').split('.') else emptyList()
        return core to pre
    }

    fun formatDownloads(n: Long) = when {
        n >= 1_000_000_000 -> "%.1fB".format(Locale.ROOT, n / 1e9)
        n >= 1_000_000 -> "%.1fM".format(Locale.ROOT, n / 1e6)
        n >= 1_000 -> "%.1fK".format(Locale.ROOT, n / 1e3)
        else -> n.toString()
    }

    fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}
