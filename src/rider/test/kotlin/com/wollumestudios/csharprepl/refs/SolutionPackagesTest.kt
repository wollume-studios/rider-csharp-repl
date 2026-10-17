package com.wollumestudios.csharprepl.refs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SolutionPackagesTest {
    @TempDir lateinit var dir: Path

    private fun write(rel: String, text: String) = dir.resolve(rel).toFile().apply { parentFile.mkdirs(); writeText(text) }
    private fun csproj(rel: String, items: String) = write(rel, """<Project Sdk="Microsoft.NET.Sdk"><ItemGroup>$items</ItemGroup></Project>""")

    @Test
    fun `plain csproj lists explicit packages`() {
        val p = csproj("A/A.csproj", """<PackageReference Include="Serilog" Version="3.1.1" /><PackageReference Include="Humanizer"><Version>2.14.1</Version></PackageReference>""")
        assertEquals(
            listOf(SolutionPackage("Humanizer", listOf(PackageUse("2.14.1", listOf("A")))), SolutionPackage("Serilog", listOf(PackageUse("3.1.1", listOf("A"))))),
            SolutionPackages.scan(listOf(p)),
        )
    }

    @Test
    fun `old style xmlns and Update-only items are handled`() {
        val p = write("A/A.csproj", """<Project xmlns="http://schemas.microsoft.com/developer/msbuild/2003"><ItemGroup><PackageReference Include="X" Version="1.0.0"/><PackageReference Update="Microsoft.NETCore.App" Version="9.9.9"/></ItemGroup></Project>""")
        assertEquals(listOf("X"), SolutionPackages.scan(listOf(p)).map { it.id })
    }

    @Test
    fun `central package management takes the version from the nearest Directory Packages props`() {
        write("Directory.Packages.props", """<Project><ItemGroup><PackageVersion Include="Serilog" Version="1.0.0"/></ItemGroup></Project>""")
        write("src/Directory.Packages.props", """<Project><ItemGroup><PackageVersion Include="serilog" Version="3.1.1"/></ItemGroup></Project>""")
        val deep = csproj("src/A/A.csproj", """<PackageReference Include="Serilog" />""")
        val top = csproj("B/B.csproj", """<PackageReference Include="Serilog" />""")
        val uses = SolutionPackages.scan(listOf(deep, top)).single().uses
        assertEquals(listOf(PackageUse("3.1.1", listOf("A")), PackageUse("1.0.0", listOf("B"))), uses)
    }

    @Test
    fun `VersionOverride wins over the central version`() {
        write("Directory.Packages.props", """<Project><ItemGroup><PackageVersion Include="Serilog" Version="1.0.0"/></ItemGroup></Project>""")
        val p = csproj("A/A.csproj", """<PackageReference Include="Serilog" VersionOverride="2.0.0" />""")
        assertEquals(listOf(PackageUse("2.0.0", listOf("A"))), SolutionPackages.scan(listOf(p)).single().uses)
    }

    @Test
    fun `a reference without any version is ignored`() {
        val p = csproj("A/A.csproj", """<PackageReference Include="Orphan" />""")
        assertTrue(SolutionPackages.scan(listOf(p)).isEmpty())
    }

    @Test
    fun `duplicates across projects merge by id and list each version with its projects`() {
        val a = csproj("A/A.csproj", """<PackageReference Include="Serilog" Version="3.1.1" />""")
        val b = csproj("B/B.csproj", """<PackageReference Include="serilog" Version="3.1.1" /><PackageReference Include="Moq" Version="4.20.0" />""")
        val c = csproj("C/C.csproj", """<PackageReference Include="Serilog" Version="2.0.0" />""")
        val pkgs = SolutionPackages.scan(listOf(a, b, c, a))
        assertEquals(listOf("Moq", "Serilog"), pkgs.map { it.id })
        val serilog = pkgs[1]
        assertEquals(listOf(PackageUse("3.1.1", listOf("A", "B")), PackageUse("2.0.0", listOf("C"))), serilog.uses)
        assertEquals("3.1.1", serilog.preferredVersion)
    }

    @Test
    fun `property versions are marked and a literal version is preferred`() {
        val a = csproj("A/A.csproj", """<PackageReference Include="X" Version="${'$'}(XVersion)" />""")
        val b = csproj("B/B.csproj", """<PackageReference Include="X" Version="1.2.3" />""")
        val onlyProp = SolutionPackages.scan(listOf(a)).single()
        assertTrue(onlyProp.uses.single().isProperty)
        assertNull(onlyProp.preferredVersion)
        val both = SolutionPackages.scan(listOf(a, b)).single()
        assertEquals("1.2.3", both.preferredVersion)
        assertFalse(both.uses.last().isProperty)
    }

    @Test
    fun `a malformed or doctype file is skipped`() {
        val bad = write("Bad/Bad.csproj", "<Project><ItemGroup><PackageReference Include=")
        val xxe = write("Xxe/Xxe.csproj", """<!DOCTYPE Project [<!ENTITY e "x">]><Project><PackageReference Include="Y" Version="1"/></Project>""")
        val good = csproj("A/A.csproj", """<PackageReference Include="Ok" Version="1.0.0" />""")
        assertEquals(listOf("Ok"), SolutionPackages.scan(listOf(bad, xxe, good, File(dir.toFile(), "Missing.csproj"))).map { it.id })
    }

    @Test
    fun `fallback project search skips build output`() {
        csproj("A/A.csproj", "")
        csproj("A/obj/Gen.csproj", "")
        csproj("B/Sub/B.CSPROJ", "")
        write("A/readme.txt", "")
        assertEquals(listOf("A.csproj", "B.CSPROJ"), SolutionPackages.findProjectFiles(dir.toFile()).map { it.name })
    }
}
