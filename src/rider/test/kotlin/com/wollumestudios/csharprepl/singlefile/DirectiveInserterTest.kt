package com.wollumestudios.csharprepl.singlefile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DirectiveInserterTest {
    private val project = "#:project ../Lib/Lib.csproj"

    @Test
    fun `inserts after the existing directives`() = assertEquals(
        "#!/usr/bin/env dotnet\n#:package A@1\n#:project ../Lib/Lib.csproj\nConsole.WriteLine();",
        DirectiveInserter.insert("#!/usr/bin/env dotnet\n#:package A@1\nConsole.WriteLine();", project),
    )

    @Test
    fun `inserts at the top without shebang or directives`() =
        assertEquals("#:project x.csproj\nConsole.WriteLine();", DirectiveInserter.insert("Console.WriteLine();", "#:project x.csproj"))

    @Test
    fun `inserts after the shebang when there are no directives`() =
        assertEquals("#!/usr/bin/env dotnet\n$project\n\nConsole.WriteLine();", DirectiveInserter.insert("#!/usr/bin/env dotnet\n\nConsole.WriteLine();", project))

    @Test
    fun `does not duplicate a directive`() {
        val text = "#:project ../Lib/Lib.csproj\nConsole.WriteLine();"
        assertEquals(text, DirectiveInserter.insert(text, project))
    }

    @Test
    fun `keeps CRLF line endings`() =
        assertEquals("#:package A@1\r\n$project\r\nx();", DirectiveInserter.insert("#:package A@1\r\nx();", project))

    @Test
    fun `empty file`() = assertEquals("$project\n", DirectiveInserter.insert("", project))

    @Test
    fun `directives make a file a single-file app even inside a project`() = assertTrue(DirectiveInserter.isSingleFileApp("#:package A@1\n...", true))

    @Test
    fun `a file outside any project is a single-file app`() = assertTrue(DirectiveInserter.isSingleFileApp("class C {}", false))

    @Test
    fun `a plain file in a project is not`() = assertFalse(DirectiveInserter.isSingleFileApp("class C {}", true))

    @Test
    fun `project directive is relative with forward slashes`(@TempDir dir: File) {
        val app = File(dir, "scratch/app.cs").also { it.parentFile.mkdirs(); it.writeText("") }
        val lib = File(dir, "src/Lib/Lib.csproj").also { it.parentFile.mkdirs(); it.writeText("") }
        assertEquals("#:project ../src/Lib/Lib.csproj", DirectiveInserter.projectDirective(app, lib))
    }

    @Test
    fun `a file belongs to the solution when its nearest csproj is a solution project`(@TempDir dir: File) {
        val proj = File(dir, "App/App.csproj").also { it.parentFile.mkdirs(); it.writeText("") }
        val inside = File(dir, "App/sub/x.cs").also { it.parentFile.mkdirs(); it.writeText("") }
        val outside = File(dir, "scratch/y.cs").also { it.parentFile.mkdirs(); it.writeText("") }
        val stray = File(dir, "Other/Other.csproj").also { it.parentFile.mkdirs(); it.writeText("") }
        val strayCs = File(dir, "Other/z.cs").also { it.writeText("") }
        assertTrue(DirectiveInserter.inSolutionProject(inside, listOf(proj)))
        assertFalse(DirectiveInserter.inSolutionProject(outside, listOf(proj)))
        assertFalse(DirectiveInserter.inSolutionProject(strayCs, listOf(proj))) // nearest csproj is not in the solution
        assertTrue(DirectiveInserter.inSolutionProject(strayCs, listOf(proj, stray)))
    }

    @Test
    fun `project directive falls back to an absolute path across drives`(@TempDir dir: File) {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val app = File(dir, "app.cs").also { it.writeText("") }
        val otherDrive = if (dir.toPath().root.toString().startsWith("Z", true)) "Y" else "Z"
        assertEquals("#:project $otherDrive:/x/Lib.csproj", DirectiveInserter.projectDirective(app, File("$otherDrive:\\x\\Lib.csproj")))
    }

    @Test
    fun `nearest project is found above the file, or null`(@TempDir dir: File) {
        val proj = File(dir, "A/A.csproj").also { it.parentFile.mkdirs(); it.writeText("") }
        val deep = File(dir, "A/b/c/x.cs").also { it.parentFile.mkdirs(); it.writeText("") }
        assertEquals(proj.canonicalFile, DirectiveInserter.nearestProject(deep)?.canonicalFile)
        assertEquals(null, DirectiveInserter.nearestProject(File(dir, "none/y.cs").also { it.parentFile.mkdirs() }))
    }
}
