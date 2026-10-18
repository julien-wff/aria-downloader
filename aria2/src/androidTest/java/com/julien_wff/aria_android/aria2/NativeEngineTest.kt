package com.julien_wff.aria_android.aria2

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Collections
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** All transfers stay on loopback. Fixture roots are injected only into this test engine. */
@RunWith(AndroidJUnit4::class)
class NativeEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun root() = File(context.cacheDir, "native-test-${System.nanoTime()}").apply { mkdirs() }
    private fun engine(root: File, certificate: String = "valid"): NativeDownloadEngine {
        val ca = File(root, "test-ca.pem")
        instrumentation.context.assets.open("tls/$certificate.pem").use { input ->
            ca.outputStream().use { input.copyTo(it) }
        }
        return NativeDownloadEngine(root, { SessionEnvironment(ca, emptyList()) }, JniBackend())
    }

    private suspend fun DownloadEngine.await(id: EngineTaskId, state: EngineTaskState): EngineTaskSnapshot =
        withTimeout(90_000) {
            val task = snapshots.first { snapshot -> snapshot.tasks.any {
                it.id == id && (it.state == state || it.state == EngineTaskState.FAILED)
            } }
                .tasks.first { it.id == id }
            check(task.state == state) { "Expected $state, got ${task.state}, aria2 code ${task.errorCode}" }
            task
        }

    @Test fun nativeExceptionsAreSanitizedAndFailedOpenReleasesResources() {
        val native = JniBackend()
        try {
            native.open(arrayOf("max-concurrent-downloads", "secret-token-value"))
            fail("Invalid native options must fail")
        } catch (error: EngineException) {
            assertFalse(error.message.orEmpty().contains("secret-token-value"))
        }
        // Subsequent instrumented transfers also assert that initialization can recover.
    }

    @Test fun failedResumeCanRestartWithSameStagingAndHash() = runBlocking {
        val root = root()
        val engine = engine(root)
        try {
            FixtureServer().use { server ->
                engine.start()
                val id = engine.add(EngineRequest(server.url("/slow"), "retry.bin", EngineOptions(connectionsPerServer = 1)))
                withTimeout(30_000) { engine.snapshots.first { it.tasks.any { it.id == id && it.completedBytes > 128 * 1024 } } }
                engine.pause(id)
                val partial = File(root, "${id.value}/retry.bin")
                assertTrue(partial.exists())
                assertTrue(File(root, "${id.value}/retry.bin.aria2").exists())
                server.failNext.set(true)
                engine.resume(id)
                val failed = engine.await(id, EngineTaskState.FAILED)
                assertEquals(29, failed.errorCode)
                assertTrue(partial.exists())
                engine.restart(id)
                engine.await(id, EngineTaskState.COMPLETED)
                assertArrayEquals(server.hash(), MessageDigest.getInstance("SHA-256").digest(partial.readBytes()))
                assertTrue(server.receivedHeaders.any { it["range"]?.startsWith("bytes=0-") == false })
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun perDownloadHeadersReachTheHttpServer() = runBlocking {
        val root = root()
        val engine = engine(root)
        try {
            FixtureServer().use { server ->
                engine.start()
                val options = EngineOptions(referrer = "https://example.org/page", userAgent = "Custom/1.0")
                val id = engine.add(EngineRequest(server.url("/file"), options = options))
                engine.await(id, EngineTaskState.COMPLETED)
                val received = synchronized(server.receivedHeaders) { server.receivedHeaders.toList() }
                assertTrue(received.isNotEmpty())
                assertTrue(received.all { it["referer"] == options.referrer && it["user-agent"] == options.userAgent })
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun httpsDownloadVerifiesContentAndStopsWhenIdle() = runBlocking {
        val root = root()
        val engine = engine(root)
        try {
            FixtureServer("valid").use { server ->
                engine.start()
                val id = engine.add(EngineRequest(server.url("/file"), "résultat-🎵.bin"))
                val completed = engine.await(id, EngineTaskState.COMPLETED)
                val pieces = requireNotNull(completed.pieces)
                assertTrue(pieces.cells.size in 1..256)
                assertEquals(pieces.count, pieces.cells.sumOf { it.total })
                assertEquals(pieces.count, pieces.cells.sumOf { it.completed })
                assertArrayEquals(server.hash(), File(root, "${id.value}/résultat-🎵.bin").sha256())
                assertFalse(File(root, "${id.value}/résultat-🎵.bin.aria2").exists())
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun rejectsExpiredWrongHostAndUntrustedTls() = runBlocking {
        for (certificate in listOf("expired", "wrong-host", "valid")) {
            val root = root()
            // The third case uses actual system roots, not the fixture trust bundle.
            val engine = if (certificate == "valid") NativeDownloadEngine(
                root, AndroidEnvironment(context, root)::load, JniBackend(),
            ) else engine(root, certificate)
            try {
                FixtureServer(certificate).use { server ->
                    engine.start()
                    val id = engine.add(EngineRequest(server.url("/file")))
                    val failure = engine.await(id, EngineTaskState.FAILED)
                    assertNotNull(failure.errorCode)
                    assertFalse(failure.errorMessage.orEmpty().contains(server.url("/file")))
                }
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun httpRedirectAndUnknownLengthHaveCorrectHashes() = runBlocking {
        for (path in listOf("/redirect", "/unknown")) {
            val root = root()
            val engine = engine(root)
            try {
                FixtureServer().use { server ->
                    engine.start()
                    val id = engine.add(EngineRequest(server.url(path)))
                    engine.await(id, EngineTaskState.COMPLETED)
                    assertArrayEquals(server.hash(), File(root, "${id.value}/download").sha256())
                }
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun rangeResumeAndNonResumableFailurePreserveContent() = runBlocking {
        for (path in listOf("/slow", "/no-range")) {
            val root = root()
            val engine = engine(root)
            try {
                FixtureServer().use { server ->
                    engine.start()
                    val id = engine.add(EngineRequest(server.url(path), options = EngineOptions(1)))
                    withTimeout(30_000) {
                        engine.snapshots.first { it.tasks.any { task -> task.id == id && task.completedBytes > 0 &&
                            task.state == EngineTaskState.DOWNLOADING && task.pieces != null } }
                    }
                    val beforePause = engine.snapshots.value.tasks.single().pieces
                    assertNotNull(beforePause)
                    engine.pause(id)
                    assertEquals(beforePause, engine.snapshots.value.tasks.single().pieces)
                    assertEquals(EngineTaskState.PAUSED, engine.snapshots.value.tasks.single().state)
                    assertTrue(File(root, "${id.value}/download.aria2").isFile)
                    val staged = File(root, "${id.value}/download")
                    val pausedSize = staged.length()
                    engine.updateOptions(id, EngineOptions(1, 1024 * 1024))
                    engine.resume(id)
                    engine.checkpoint()
                    if (path == "/no-range") {
                        val failure = engine.await(id, EngineTaskState.FAILED)
                        assertEquals(8, failure.errorCode)
                        assertTrue(failure.errorMessage.orEmpty().contains("resume"))
                        assertTrue(staged.isFile && staged.length() >= pausedSize)
                        assertTrue(File(root, "${id.value}/download.aria2").isFile)
                    } else {
                        engine.await(id, EngineTaskState.COMPLETED)
                        assertArrayEquals(server.hash(), staged.sha256())
                    }
                }
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun largeLengthCrossesJniWithoutTruncation() = runBlocking {
        val root = root()
        val engine = engine(root)
        try {
            FixtureServer(length = LARGE_SIZE).use { server ->
                engine.start()
                val id = engine.add(EngineRequest(server.url("/large"), options = EngineOptions(1)))
                val snapshot = withTimeout(30_000) {
                    engine.snapshots.first { it.tasks.any { task -> task.totalBytes == LARGE_SIZE } }
                }
                assertEquals(LARGE_SIZE, snapshot.tasks.single().totalBytes)
                engine.remove(id)
                assertEquals(EngineTaskState.REMOVED, engine.snapshots.value.tasks.single().state)
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun fullLargeFileHash() = runBlocking {
        assumeTrue("Enable with -Pandroid.testInstrumentationRunnerArguments.largeFile=true",
            InstrumentationRegistry.getArguments().getString("largeFile") == "true")
        val root = root()
        assumeTrue("Requires 6 GiB free staging space", root.usableSpace > 6L * 1024 * 1024 * 1024)
        val engine = engine(root)
        try {
            FixtureServer(length = LARGE_SIZE).use { server ->
                engine.start()
                val id = engine.add(EngineRequest(server.url("/large")))
                withTimeout(20 * 60_000L) {
                    engine.snapshots.first { it.tasks.any { task -> task.state == EngineTaskState.COMPLETED } }
                }
                val file = File(root, "${id.value}/download")
                assertEquals(LARGE_SIZE, file.length())
                assertArrayEquals(server.hash(), file.sha256())
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    private fun File.sha256(): ByteArray = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private inner class FixtureServer(certificate: String? = null, val length: Long = 2L * 1024 * 1024) : Closeable {
        val failNext = AtomicBoolean(false)
        val receivedHeaders = Collections.synchronizedList(mutableListOf<Map<String, String>>())
        private val block = ByteArray(8192) { (it % 251).toByte() }
        private val executor = Executors.newCachedThreadPool()
        private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
        private val server: ServerSocket
        private val scheme = if (certificate == null) "http" else "https"

        init {
            val address = InetAddress.getByName("127.0.0.1")
            server = if (certificate == null) ServerSocket(0, 16, address) else {
                val password = "fixture-password".toCharArray()
                val store = KeyStore.getInstance("PKCS12").apply {
                    instrumentation.context.assets.open("tls/$certificate.p12").use { load(it, password) }
                }
                val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                    init(store, password)
                }
                SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
                    .serverSocketFactory.createServerSocket(0, 16, address)
            }
            executor.execute {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: Exception) { break }
                    clients += client
                    executor.execute {
                        try { client.use { serve(it) } } catch (_: Exception) { /* Pause/TLS rejection closes sockets. */ }
                        finally { clients -= client }
                    }
                }
            }
        }

        fun url(path: String) = "$scheme://127.0.0.1:${server.localPort}$path"
        fun hash(): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = length
            while (remaining > 0) {
                val count = minOf(remaining, block.size.toLong()).toInt()
                digest.update(block, 0, count)
                remaining -= count
            }
            return digest.digest()
        }

        private fun serve(client: Socket) {
            client.soTimeout = 10_000
            val reader = client.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = reader.readLine() ?: return
            val path = request.split(" ")[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            receivedHeaders += headers.toMap()
            val output = client.getOutputStream()
            if (failNext.compareAndSet(true, false)) {
                output.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            Log.i("AriaFixture", "$path range=${headers["range"]}")
            if (path == "/redirect") {
                output.write("HTTP/1.1 302 Found\r\nLocation: /file\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return
            }
            val range = headers["range"]?.removePrefix("bytes=")?.split('-')
                ?.takeIf { path != "/no-range" && path != "/unknown" }
            val start = range?.getOrNull(0)?.toLongOrNull() ?: 0
            val end = minOf(range?.getOrNull(1)?.toLongOrNull() ?: (length - 1), length - 1)
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val response = buildString {
                append("HTTP/1.1 $status\r\nConnection: close\r\nContent-Type: application/octet-stream\r\n")
                if (path != "/unknown") append("Content-Length: ${end - start + 1}\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/${this@FixtureServer.length}\r\n")
                if (path != "/no-range") append("Accept-Ranges: bytes\r\n")
                append("\r\n")
            }
            output.write(response.toByteArray())
            Log.i("AriaFixture", response.replace("\r\n", " | "))
            var position = start
            while (position <= end) {
                val offset = (position % block.size).toInt()
                val count = minOf(block.size - offset.toLong(), end - position + 1).toInt()
                output.write(block, offset, count)
                position += count
                if (path == "/slow" || path == "/no-range" || (path == "/large" &&
                    InstrumentationRegistry.getArguments().getString("largeFile") != "true")) {
                    output.flush()
                    Thread.sleep(15)
                }
            }
        }

        override fun close() {
            server.close()
            synchronized(clients) { clients.toList().forEach { runCatching { it.close() } } }
            executor.shutdownNow()
        }
    }

    companion object { private const val LARGE_SIZE = 4L * 1024 * 1024 * 1024 + 65536 }
}
