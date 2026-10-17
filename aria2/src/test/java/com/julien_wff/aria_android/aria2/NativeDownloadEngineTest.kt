package com.julien_wff.aria_android.aria2

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NativeDownloadEngineTest {
    private class FakeNative : NativeBackend {
        val states = linkedMapOf<String, LongArray>()
        val owners = mutableSetOf<Long>()
        val additions = mutableListOf<Map<String, String>>()
        var sessionOptions = emptyMap<String, String>()
        var opens = 0
        var shutdowns = 0
        var complete = false
        @Volatile var failId: String? = null
        private fun owner() { owners += Thread.currentThread().threadId() }
        override fun open(options: Array<String>) { owner(); opens++; states.clear(); sessionOptions = options.toList().chunked(2).associate { it[0] to it[1] } }
        override fun add(id: String, url: String, options: Array<String>) {
            owner()
            val values = options.toList().chunked(2).associate { it[0] to it[1] }
            additions += values
            states[id] = longArrayOf(if (values["pause"] == "true") 2 else 0, 4_500_000_000, 6_000_000_000, 100, 0)
        }
        override fun pause(id: String) { owner(); states.getValue(id)[0] = 2 }
        override fun resume(id: String) { owner(); states.getValue(id)[0] = 0 }
        override fun remove(id: String) { owner(); states.getValue(id)[0] = 5 }
        override fun update(id: String, options: Array<String>) { owner() }
        override fun tick() {
            owner()
            failId?.let { id -> states.getValue(id)[0] = 4; states.getValue(id)[4] = 6; failId = null }
            if (complete) states.values.forEach { it[0] = 3 }
            Thread.sleep(2)
        }
        override fun snapshot(id: String): LongArray { owner(); return states.getValue(id).clone() }
        override fun shutdown() { owner(); shutdowns++ }
    }

    private fun engine(root: File, native: FakeNative) = NativeDownloadEngine(
        root, { SessionEnvironment(File(root, "ca.pem"), listOf("192.0.2.53")) }, native,
    )

    @Test fun concurrencyConfigurationIsBoundedAndAppliedToEachNativeSession() = runBlocking {
        val root = Files.createTempDirectory("aria-concurrency-test").toFile()
        val backend = FakeNative()
        val engine = engine(root, backend)
        try {
            try { engine.configureConcurrency(7); fail("Unbounded concurrency") } catch (_: IllegalArgumentException) { }
            engine.configureConcurrency(6)
            engine.start()
            engine.add(EngineRequest("https://example.org/file"))
            assertEquals("6", backend.sessionOptions["max-concurrent-downloads"])
            try { engine.configureConcurrency(2); fail("Changed a running session") } catch (_: IllegalStateException) { }
            engine.stop()
            engine.configureConcurrency(2)
            engine.start()
            engine.resume(engine.snapshots.value.tasks.single().id)
            assertEquals("2", backend.sessionOptions["max-concurrent-downloads"])
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun restartingFailedTaskPreservesFilesOptionsAndSiblingStates() = runBlocking {
        val root = Files.createTempDirectory("aria-retry-test").toFile()
        val backend = FakeNative()
        val engine = engine(root, backend)
        try {
            engine.start()
            val id = engine.add(EngineRequest("https://example.org/file", "file.bin",
                EngineOptions(referrer = "https://example.org/page", userAgent = "Custom/1.0")))
            val running = engine.add(EngineRequest("https://example.org/other"))
            val paused = engine.add(EngineRequest("https://example.org/paused"))
            engine.pause(paused)
            val file = File(root, "${id.value}/file.bin").apply { writeText("partial") }
            val checkpoint = File(root, "${id.value}/file.bin.aria2").apply { writeText("checkpoint") }
            backend.failId = id.value
            withTimeout(5000) { engine.snapshots.first { it.tasks.any { it.id == id && it.state == EngineTaskState.FAILED } } }
            engine.restart(id)
            assertEquals("partial", file.readText())
            assertEquals("checkpoint", checkpoint.readText())
            assertEquals(EngineTaskState.PAUSED, engine.snapshots.value.tasks.first { it.id == paused }.state)
            assertTrue(engine.snapshots.value.tasks.first { it.id == running }.state in setOf(EngineTaskState.QUEUED, EngineTaskState.DOWNLOADING))
            val options = backend.additions.last { it["out"] == "file.bin" }
            assertEquals(file.parentFile!!.canonicalPath, options["dir"])
            assertEquals("Custom/1.0", options["user-agent"])
            assertEquals("1", backend.sessionOptions["max-tries"])
            assertEquals(1, backend.owners.size)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun requestHeadersSurviveCheckpointAndCanBeCleared() = runBlocking {
        val root = Files.createTempDirectory("aria-headers-test").toFile()
        val backend = FakeNative()
        val engine = engine(root, backend)
        try {
            engine.start()
            val options = EngineOptions(referrer = "https://example.org/page", userAgent = "Custom/1.0")
            val id = engine.add(EngineRequest("https://example.org/file", options = options))
            engine.checkpoint()
            assertEquals(options.referrer, backend.additions.last()["referer"])
            assertEquals(options.userAgent, backend.additions.last()["user-agent"])
            engine.updateOptions(id, EngineOptions())
            assertFalse(backend.additions.last().containsKey("referer"))
            assertFalse(backend.additions.last().containsKey("user-agent"))
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun requestHeadersAreOptionalAndValidated() {
        val defaults = EngineOptions().nativePairs().toList()
        assertFalse(defaults.contains("referer"))
        assertFalse(defaults.contains("user-agent"))
        val pairs = EngineOptions(referrer = "https://example.org/page?q=1", userAgent = "Custom/1.0")
            .nativePairs().toList().chunked(2).associate { it[0] to it[1] }
        assertEquals("https://example.org/page?q=1", pairs["referer"])
        assertEquals("Custom/1.0", pairs["user-agent"])
        for (value in listOf("ftp://example.org", "https://user:pass@example.org", "relative", "https://example.org/\r\nInjected: yes")) {
            assertThrows(IllegalArgumentException::class.java) { EngineOptions(referrer = value) }
        }
        for (value in listOf("", "Agent\r\nInjected: yes", "Agent\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { EngineOptions(userAgent = value) }
        }
    }

    @Test fun rejectsUnsafeInputsAndUnboundedOptions() {
        for (url in listOf("file:///tmp/a", "magnet:?xt=a", "https://user:secret@example.org/a", "https:/missing")) {
            assertThrows(IllegalArgumentException::class.java) { EngineRequest(url) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineRequest("https://example.org/a", taskId = EngineTaskId("../escape"))
        }
        for (name in listOf("../file", "..", "a/b", "a\\b", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { EngineRequest("https://example.org/a", name) }
        }
        assertThrows(IllegalArgumentException::class.java) { EngineOptions(connectionsPerServer = 17) }
        assertThrows(IllegalArgumentException::class.java) { EngineOptions(downloadLimitBytesPerSecond = -1) }
    }

    @Test fun pauseCheckpointAndResumeRetainIntentAndLargeCounters() = runBlocking {
        val root = Files.createTempDirectory("aria-engine-test").toFile()
        val native = FakeNative()
        val engine = engine(root, native)
        try {
            engine.start()
            engine.start()
            val id = engine.add(EngineRequest("https://example.org/file", "file.bin"))
            val progress = withTimeout(5000) {
                engine.snapshots.first { it.tasks.singleOrNull()?.totalBytes == 6_000_000_000 }.tasks.single()
            }
            assertEquals(4_500_000_000, progress.completedBytes)
            engine.updateOptions(id, EngineOptions(connectionsPerServer = 2))
            assertEquals("2", native.additions.last()["max-connection-per-server"])
            engine.pause(id)
            assertEquals(EngineTaskState.PAUSED, engine.snapshots.value.tasks.single().state)
            assertEquals(native.opens, native.shutdowns)
            engine.updateOptions(id, EngineOptions(downloadLimitBytesPerSecond = 1234))
            engine.resume(id)
            engine.checkpoint()
            assertEquals("1234", native.additions.last()["max-download-limit"])
            assertEquals("file.bin", native.additions.last()["out"])
            assertTrue(File(native.additions.last().getValue("dir")).canonicalPath.startsWith(root.canonicalPath))
            engine.stop()
            assertEquals(EngineTaskState.PAUSED, engine.snapshots.value.tasks.single().state)
            assertFalse(engine.snapshots.value.started)
            assertEquals(1, native.owners.size)
            assertEquals(native.opens, native.shutdowns)
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun completionStopsNativeWorkAndReleasesProcessOwnership() = runBlocking {
        val root = Files.createTempDirectory("aria-engine-test").toFile()
        val native = FakeNative().apply { complete = true }
        val engine = engine(root, native)
        val other = engine(File(root, "other"), FakeNative())
        try {
            engine.start()
            try { other.start(); fail("Second engine must not start") } catch (_: IllegalStateException) { }
            engine.add(EngineRequest("https://example.org/file"))
            withTimeout(5000) { engine.snapshots.first { it.tasks.singleOrNull()?.state == EngineTaskState.COMPLETED } }
            assertEquals(native.opens, native.shutdowns)
            engine.stop()
            other.start()
            other.stop()
        } finally { engine.close(); other.close(); root.deleteRecursively() }
    }

    @Test fun reconstructingEngineReusesPersistedTaskDirectoryAndControlFiles() = runBlocking {
        val root = Files.createTempDirectory("aria-recovery-test").toFile()
        val id = EngineTaskId("0123456789abcdef")
        val request = EngineRequest("https://example.org/file", "file.bin", taskId = id)
        val first = engine(root, FakeNative())
        try {
            first.start()
            assertEquals(id, first.add(request))
            File(root, "${id.value}/file.bin").writeText("partial")
            File(root, "${id.value}/file.bin.aria2").writeText("checkpoint")
        } finally { first.close() }
        val backend = FakeNative()
        val restored = engine(root, backend)
        try {
            restored.start()
            assertEquals(id, restored.add(request))
            assertEquals(File(root, id.value).canonicalPath, backend.additions.last()["dir"])
            assertEquals("partial", File(root, "${id.value}/file.bin").readText())
            assertEquals("checkpoint", File(root, "${id.value}/file.bin.aria2").readText())
        } finally { restored.close(); root.deleteRecursively() }
    }

    @Test fun removeCancelsWithoutDeletingStagedDataAndCloseRejectsCommands() = runBlocking {
        val root = Files.createTempDirectory("aria-engine-test").toFile()
        val native = FakeNative()
        val engine = engine(root, native)
        try {
            try { engine.add(EngineRequest("https://example.org/file")); fail() } catch (_: IllegalStateException) { }
            engine.start()
            val id = engine.add(EngineRequest("https://example.org/file"))
            val staged = File(root, "${id.value}/download").apply { writeText("partial") }
            engine.remove(id)
            assertEquals(EngineTaskState.REMOVED, engine.snapshots.value.tasks.single().state)
            assertEquals("partial", staged.readText())
            engine.close()
            try { engine.start(); fail() } catch (_: IllegalStateException) { }
        } finally { engine.close(); root.deleteRecursively() }
    }
}
