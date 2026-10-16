package com.wollumestudios.csharprepl.host

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class HostProcessTest {
    private class Exit {
        val code = AtomicInteger(-1)
        val killed = AtomicBoolean()
        val latch = CountDownLatch(1)
        val callback: (Int, Boolean) -> Unit = { c, k -> code.set(c); killed.set(k); latch.countDown() }
    }

    private fun HostProcess.warmUp() = request("variables", emptyMap()).get(60, TimeUnit.SECONDS)

    @Test
    fun `run declares a variable and variables lists it`() {
        HostProcess(HostLocator.hostDll(), onExit = { _, _ -> }).use { host ->
            val run = host.request("run", mapOf("code" to "var r = 0;")).get(60, TimeUnit.SECONDS)
            assertTrue(run.get("ok").asBoolean)
            assertEquals("ok", run.get("status").asString)
            val vars = host.request("variables", emptyMap()).get(10, TimeUnit.SECONDS)
            val names = vars.getAsJsonArray("variables").map { (it as JsonObject).get("name").asString }
            assertTrue("r" in names, "variables: $names")
        }
    }

    @Test
    fun `the host runs in the working directory with the located dotnet and answers runs with addedReferences`() {
        val dir = Files.createTempDirectory("csharprepl-wd").toRealPath()
        HostProcess(HostLocator.hostDll(), onExit = { _, _ -> }, workingDir = dir).use { host ->
            // Forward slashes: the result is shown as a C# string literal, where backslashes would be escaped.
            val code = "(System.IO.Directory.GetCurrentDirectory() + \"|\" + Environment.GetEnvironmentVariable(\"CSHARPREPL_DOTNET\")).Replace('\\\\', '/')"
            val response = Gson().fromJson(host.request("run", mapOf("code" to code)).get(60, TimeUnit.SECONDS), RunResponse::class.java)
            assertEquals("\"$dir|${DotnetLocator.dotnet}\"".replace('\\', '/'), response.result)
            assertEquals(emptyList<ReferenceInfo>(), response.addedReferences)
        }
    }

    @Test
    fun `host exit fails pending requests and reports the exit code`() {
        val exit = Exit()
        HostProcess(HostLocator.hostDll(), exit.callback).use { host ->
            host.warmUp()
            val pending: CompletableFuture<JsonObject> = host.request("run", mapOf("code" to "Environment.Exit(3);"))
            assertTrue(exit.latch.await(10, TimeUnit.SECONDS), "onExit not called")
            assertEquals(3, exit.code.get())
            assertFalse(exit.killed.get(), "a crash is not a kill")
            assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `kill is reported as killed`() {
        val exit = Exit()
        val host = HostProcess(HostLocator.hostDll(), exit.callback)
        host.warmUp()
        host.kill()
        assertTrue(exit.latch.await(10, TimeUnit.SECONDS), "onExit not called")
        assertTrue(exit.killed.get())
    }

    @Test
    fun `a grandchild that inherits the host's pipes does not block exit handling`() {
        val exit = Exit()
        // Started without redirection, so the child inherits the host's stdout/stderr handles and keeps them open after the host exits.
        val child = if (System.getProperty("os.name").startsWith("Windows")) """"ping", "-n 30 127.0.0.1"""" else """"sleep", "30""""
        HostProcess(HostLocator.hostDll(), exit.callback).use { host ->
            host.warmUp()
            val pending = host.request("run", mapOf("code" to "System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo($child) { UseShellExecute = false }); Environment.Exit(4);"))
            assertTrue(exit.latch.await(10, TimeUnit.SECONDS), "onExit not called (blocked on a grandchild's pipe?)")
            assertEquals(4, exit.code.get())
            assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
        }
    }
}
