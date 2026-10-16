package com.wollumestudios.csharprepl.host

import com.google.gson.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostProcessTest {
    @Test
    fun `run declares a variable and variables lists it`() {
        HostProcess(HostLocator.hostDll(), onExit = {}).use { host ->
            val run = host.request("run", mapOf("code" to "var r = 0;")).get(60, TimeUnit.SECONDS)
            assertTrue(run.get("ok").asBoolean)
            assertEquals("ok", run.get("status").asString)
            val vars = host.request("variables", emptyMap()).get(10, TimeUnit.SECONDS)
            val names = vars.getAsJsonArray("variables").map { (it as JsonObject).get("name").asString }
            assertTrue("r" in names, "variables: $names")
        }
    }

    @Test
    fun `host exit fails pending requests and reports the exit code`() {
        val exitCode = AtomicInteger(-1)
        val exited = CountDownLatch(1)
        HostProcess(HostLocator.hostDll(), onExit = { exitCode.set(it); exited.countDown() }).use { host ->
            val pending: CompletableFuture<JsonObject> = host.request("run", mapOf("code" to "Environment.Exit(3);"))
            assertTrue(exited.await(5, TimeUnit.SECONDS), "onExit not called")
            assertEquals(3, exitCode.get())
            assertThrows(ExecutionException::class.java) { pending.get(5, TimeUnit.SECONDS) }
        }
    }
}
