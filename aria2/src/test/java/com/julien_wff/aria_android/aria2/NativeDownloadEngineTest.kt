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
        var opens = 0
        var shutdowns = 0
        var complete = false
        private fun owner() { owners += Thread.currentThread().threadId() }
        override fun open(options: Array<String>) { owner(); opens++; states.clear() }
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
            if (complete) states.values.forEach { it[0] = 3 }
            Thread.sleep(2)
        }
        override fun snapshot(id: String): LongArray { owner(); return states.getValue(id).clone() }
        override fun shutdown() { owner(); shutdowns++ }
    }

    private fun engine(root: File, native: FakeNative) = NativeDownloadEngine(
        root, { SessionEnvironment(File(root, "ca.pem"), listOf("192.0.2.53")) }, native,
    )

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
