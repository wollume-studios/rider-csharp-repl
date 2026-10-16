package com.wollumestudios.csharprepl.host

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The `dotnet CSharpRepl.Host.dll` child process: one JSON object per line over stdin/stdout (UTF-8).
 * [onExit] is called once, from a background thread, after every pending request was failed. No IntelliJ classes, so it runs in plain unit tests.
 */
class HostProcess(hostDll: Path, private val onExit: (Int) -> Unit, private val log: (String) -> Unit = {}) : AutoCloseable {
    private val gson = Gson()
    private val ids = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    @Volatile private var exited = false
    private val process: Process = ProcessBuilder("dotnet", hostDll.toString()).start()
    private val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))

    init {
        val reader = Thread({ readResponses() }, "csharprepl-host-stdout").apply { isDaemon = true; start() }
        val errReader = Thread({ process.errorStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { log("host stderr: $it") } }, "csharprepl-host-stderr").apply { isDaemon = true; start() }
        Thread({
            val code = process.waitFor()
            reader.join(); errReader.join() // deliver every response that was already written before failing the rest
            exited = true
            pending.keys.toList().forEach { pending.remove(it)?.completeExceptionally(IllegalStateException("host exited with code $code")) }
            onExit(code)
        }, "csharprepl-host-exit").apply { isDaemon = true; start() }
    }

    /** Completes with the whole response object (`ok`, plus the command's fields); exceptionally when the host reports `ok:false` or exits. */
    fun request(cmd: String, fields: Map<String, Any?>): CompletableFuture<JsonObject> {
        val id = ids.incrementAndGet()
        val future = CompletableFuture<JsonObject>()
        pending[id] = future
        if (exited) { pending.remove(id); future.completeExceptionally(IllegalStateException("host has exited")); return future }
        val json = JsonObject().apply { addProperty("id", id); addProperty("cmd", cmd); fields.forEach { (k, v) -> add(k, gson.toJsonTree(v)) } }
        try {
            synchronized(stdin) { stdin.write(gson.toJson(json)); stdin.newLine(); stdin.flush() }
        } catch (e: Exception) {
            pending.remove(id)?.completeExceptionally(e)
        }
        return future
    }

    fun kill() {
        process.descendants().forEach { it.destroyForcibly() } // a running `dotnet build` must not outlive the host
        process.destroyForcibly()
    }

    override fun close() = kill()

    private fun readResponses() {
        process.inputStream.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val obj = JsonParser.parseString(line).asJsonObject
                val future = pending.remove(obj.get("id").asInt) ?: return@forEachLine
                if (obj.get("ok").asBoolean) future.complete(obj)
                else future.completeExceptionally(RuntimeException(obj.get("error")?.asString ?: "host error"))
            } catch (e: Exception) {
                log("bad host line: $line ($e)")
            }
        }
    }
}
