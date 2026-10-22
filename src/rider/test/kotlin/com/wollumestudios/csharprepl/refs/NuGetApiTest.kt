package com.wollumestudios.csharprepl.refs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NuGetApiTest {
    @Test
    fun `search response is parsed, missing fields tolerated, description on one line`() {
        val json = """{"totalHits":2,"data":[
            {"id":"Newtonsoft.Json","version":"13.0.3","totalDownloads":4200000000,"description":"Json.NET is a\n  popular   JSON framework","verified":true},
            {"version":"1.0.0"},
            {"id":"Bare"}]}"""
        assertEquals(
            listOf(NuGetApi.Hit("Newtonsoft.Json", "13.0.3", 4_200_000_000, "Json.NET is a popular JSON framework"), NuGetApi.Hit("Bare", "", 0, "")),
            NuGetApi.parseSearch(json),
        )
    }

    @Test
    fun `search without data array is empty`() = assertEquals(emptyList<NuGetApi.Hit>(), NuGetApi.parseSearch("""{"totalHits":0}"""))

    @Test
    fun `versions are parsed`() = assertEquals(listOf("1.0.0", "1.1.0-beta"), NuGetApi.parseVersions("""{"versions":["1.0.0","1.1.0-beta"]}"""))

    @Test
    fun `versions sort newest first by semver, stable above its prereleases`() {
        val all = listOf("1.0.0", "2.0.0-beta.2", "2.0.0", "2.0.0-beta.10", "2.0.0-alpha", "10.0.0", "1.10.0", "1.9.0", "2.0.0-beta")
        assertEquals(
            listOf("10.0.0", "2.0.0", "2.0.0-beta.10", "2.0.0-beta.2", "2.0.0-beta", "2.0.0-alpha", "1.10.0", "1.9.0", "1.0.0"),
            NuGetApi.sortVersions(all, prerelease = true),
        )
        assertEquals(listOf("10.0.0", "2.0.0", "1.10.0", "1.9.0", "1.0.0"), NuGetApi.sortVersions(all, prerelease = false))
    }

    @Test
    fun `shorter and longer version cores compare numerically, build metadata is ignored`() {
        assertTrue(NuGetApi.compareVersions("1.2.3.4", "1.2.3") > 0)
        assertEquals(0, NuGetApi.compareVersions("1.2", "1.2.0"))
        assertEquals(0, NuGetApi.compareVersions("1.0.0+abc", "1.0.0"))
        assertTrue(NuGetApi.isPrerelease("1.0.0-rc.1"))
        assertTrue(!NuGetApi.isPrerelease("1.0.0+meta-data"))
    }

    @Test
    fun `urls are encoded and ids lower-cased`() {
        assertTrue(NuGetApi.searchUrl("serilog sinks&x").contains("q=serilog+sinks%26x&take=20&prerelease=false&semVerLevel=2.0.0"))
        assertEquals("https://api.nuget.org/v3-flatcontainer/newtonsoft.json/index.json", NuGetApi.versionsUrl("Newtonsoft.Json"))
    }

    @Test
    fun `download counts are abbreviated`() {
        assertEquals("999", NuGetApi.formatDownloads(999))
        assertEquals("1.5K", NuGetApi.formatDownloads(1500))
        assertEquals("12.3M", NuGetApi.formatDownloads(12_300_000))
        assertEquals("4.2B", NuGetApi.formatDownloads(4_200_000_000))
    }
}
