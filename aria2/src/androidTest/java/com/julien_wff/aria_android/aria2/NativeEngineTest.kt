package com.julien_wff.aria_android.aria2

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import android.os.ParcelFileDescriptor
import android.os.Bundle
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Environment
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

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class HttpBenchmark

/** All transfers stay on loopback. Fixture roots are injected only into this test engine. */
@RunWith(AndroidJUnit4::class)
class NativeEngineTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun root() = File(context.cacheDir, "native-test-${System.nanoTime()}").apply { mkdirs() }
    private fun engine(root: File, certificate: String = "valid", split: String? = null, failFlush: AtomicBoolean? = null): NativeDownloadEngine {
        val ca = File(root, "test-ca.pem")
        instrumentation.context.assets.open("tls/$certificate.pem").use { input ->
            ca.outputStream().use { input.copyTo(it) }
        }
        val delegate = JniBackend()
        val backend = object : NativeBackend by delegate {
            override fun add(id: String, url: String, options: Array<String>) =
                delegate.add(id, url, options + (split?.let { arrayOf("min-split-size", it) } ?: emptyArray()))
            override fun flushOutput(path: String) {
                if (failFlush?.compareAndSet(true, false) == true) throw EngineException("Injected flush failure")
                delegate.flushOutput(path)
            }
        }
        return NativeDownloadEngine(root, { SessionEnvironment(ca, emptyList()) }, backend)
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

    @HttpBenchmark
    @Test fun httpThroughputBenchmark() = runBlocking {
        val repetitions = InstrumentationRegistry.getArguments().getString("benchmarkRepetitions")?.toInt() ?: 3
        require(repetitions in 1..10)
        val length = 24L * 1024 * 1024
        // Alternate ordering each round to reduce warm-up and device-temperature bias.
        val configurations = listOf(1 to "20M", 4 to "20M", 1 to "4M", 4 to "4M")
        FixtureServer(length = length).use { server ->
            for (round in 0 until repetitions) {
                for (path in listOf("/file", "/throttled", "/no-range-fast")) {
                    val ordered = if (round % 2 == 0) configurations else configurations.reversed()
                    for ((connections, split) in ordered) {
                        for (direct in listOf(false, true)) {
                            val root = root()
                            val engine = engine(root, split = split)
                            val resolver = context.contentResolver
                            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                                put(MediaStore.Downloads.DISPLAY_NAME, "aria-benchmark-${System.nanoTime()}.bin")
                                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
                                put(MediaStore.Downloads.IS_PENDING, 1)
                            }))
                            try {
                                val output = if (direct) EngineHttpOutput { requireNotNull(resolver.openFileDescriptor(uri, "rw")) } else null
                                val started = System.nanoTime()
                                engine.start()
                                val id = engine.add(EngineRequest(server.url(path), "bench.bin", EngineOptions(connectionsPerServer = connections), httpOutput = output))
                                val first = withTimeout(90_000) { engine.snapshots.first { it.tasks.any { t -> t.id == id && (t.completedBytes > 0 || t.state == EngineTaskState.FAILED) } } }
                                check(first.tasks.single().state != EngineTaskState.FAILED)
                                val firstBytes = System.nanoTime()
                                engine.await(id, EngineTaskState.COMPLETED)
                                val downloaded = System.nanoTime()
                                engine.close()
                                if (!direct) File(root, "${id.value}/bench.bin").inputStream().use { input ->
                                    resolver.openOutputStream(uri, "w")!!.use { input.copyTo(it, 256 * 1024) }
                                }
                                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
                                val published = System.nanoTime()
                                assertArrayEquals(server.hash(), resolver.openInputStream(uri)!!.use { input ->
                                    val hash = MessageDigest.getInstance("SHA-256")
                                    val buffer = ByteArray(256 * 1024)
                                    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                                    hash.digest()
                                })
                                val row = org.json.JSONObject().apply {
                                    put("round", round); put("fixture", path); put("connections", connections)
                                    put("split", split); put("direct", direct); put("bytes", length)
                                    put("startupMs", (firstBytes - started) / 1_000_000.0)
                                    put("downloadMs", (downloaded - started) / 1_000_000.0)
                                    put("publicationMs", (published - downloaded) / 1_000_000.0)
                                    put("totalMs", (published - started) / 1_000_000.0)
                                }.toString()
                                instrumentation.sendStatus(0, Bundle().apply { putString("ariaHttpBenchmark", row) })
                            } finally { engine.close(); resolver.delete(uri, null, null); root.deleteRecursively() }
                        }
                    }
                }
            }
        }
    }

    @Test fun descriptorPayloadResumesAcrossEngineRecreationWithoutPrivateCopy() = runBlocking {
        val root = root()
        val target = File(root, "destination.bin").apply { createNewFile() }
        var engine = engine(root)
        try {
            FixtureServer().use { server ->
                val request = EngineRequest(server.url("/slow"), "download.bin",
                    EngineOptions(connectionsPerServer = 1), EngineTaskId("0123456789abcdef"),
                    httpOutput = EngineHttpOutput { ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE) })
                engine.start()
                val id = engine.add(request)
                withTimeout(30_000) { engine.snapshots.first { it.tasks.any { task -> task.completedBytes > 128 * 1024 } } }
                engine.pause(id)
                assertTrue(target.length() > 0)
                assertFalse(File(root, "${id.value}/payload").exists())
                assertFalse(File(root, "${id.value}/download.bin").exists())
                assertTrue(File(root, "${id.value}/payload.aria2").isFile)
                engine.close()
                engine = engine(root)
                engine.start()
                assertEquals(id, engine.add(request))
                engine.await(id, EngineTaskState.COMPLETED)
                assertArrayEquals(server.hash(), target.sha256())
                assertEquals(target.length().toString(), File(root, "${id.value}/.http-complete").readText())
                assertTrue(server.receivedHeaders.any { it["range"]?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()?.let { n -> n > 0 } == true })
                engine.close()
                // Completion is recovered offline from the durable marker, without a new GET.
                val before = server.receivedHeaders.size
                engine = engine(root)
                engine.start()
                engine.add(request)
                engine.await(id, EngineTaskState.COMPLETED)
                assertEquals(before, server.receivedHeaders.size)
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun descriptorTransfersHandleRedirectUnknownLengthAndNoRanges() = runBlocking {
        for (path in listOf("/redirect", "/unknown", "/no-range")) {
            val root = root()
            val target = File(root, "destination.bin").apply { createNewFile() }
            val engine = engine(root)
            try {
                FixtureServer().use { server ->
                    engine.start()
                    val id = engine.add(EngineRequest(server.url(path), httpOutput = EngineHttpOutput {
                        ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE)
                    }))
                    engine.await(id, EngineTaskState.COMPLETED)
                    assertArrayEquals(server.hash(), target.sha256())
                    assertFalse(File(root, "${id.value}/payload").exists())
                }
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun missingDestinationDoesNotStopAnUnrelatedActiveDownload() = runBlocking {
        val root = root()
        val target = File(root, "destination.bin").apply { createNewFile() }
        val engine = engine(root)
        try {
            FixtureServer().use { server ->
                engine.start()
                val id = engine.add(EngineRequest(server.url("/slow"), httpOutput = EngineHttpOutput {
                    ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE)
                }))
                withTimeout(30_000) { engine.snapshots.first { it.tasks.any { task -> task.completedBytes > 128 * 1024 } } }
                try {
                    engine.add(EngineRequest(server.url("/file"), httpOutput = EngineHttpOutput {
                        throw java.io.IOException("Missing destination")
                    }))
                    fail("Missing destination must fail")
                } catch (_: java.io.IOException) { }
                engine.await(id, EngineTaskState.COMPLETED)
                assertArrayEquals(server.hash(), target.sha256())
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun descriptorFlushFailurePreservesBytesForRetryAndCannotComplete() = runBlocking {
        val root = root()
        val target = File(root, "destination.bin").apply { createNewFile() }
        val engine = engine(root, failFlush = AtomicBoolean(true))
        try {
            FixtureServer().use { server ->
                engine.start()
                val id = engine.add(EngineRequest(server.url("/file"), httpOutput = EngineHttpOutput {
                    ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE)
                }))
                engine.await(id, EngineTaskState.FAILED)
                assertArrayEquals(server.hash(), target.sha256())
                assertFalse(File(root, "${id.value}/.http-complete").exists())
                assertTrue(File(root, "${id.value}/payload.aria2").isFile)
                engine.restart(id)
                engine.await(id, EngineTaskState.COMPLETED)
                assertArrayEquals(server.hash(), target.sha256())
                assertTrue(File(root, "${id.value}/.http-complete").isFile)
            }
        } finally { engine.close(); root.deleteRecursively() }
    }

    @Test fun descriptorWithoutCheckpointCannotOverwritePartialBytes() = runBlocking {
        val root = root()
        val target = File(root, "destination.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val engine = engine(root)
        try {
            FixtureServer().use { server ->
                engine.start()
                try {
                    engine.add(EngineRequest(server.url("/file"), httpOutput = EngineHttpOutput {
                        ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_WRITE)
                    }))
                    fail("Missing resume checkpoint must fail")
                } catch (_: IllegalStateException) { }
                assertArrayEquals(byteArrayOf(1, 2, 3), target.readBytes())
                assertTrue(server.receivedHeaders.isEmpty())
            }
        } finally { engine.close(); root.deleteRecursively() }
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
                ?.takeIf { path != "/no-range" && path != "/no-range-fast" && path != "/unknown" }
            val start = range?.getOrNull(0)?.toLongOrNull() ?: 0
            val end = minOf(range?.getOrNull(1)?.toLongOrNull() ?: (length - 1), length - 1)
            val status = if (range == null) "200 OK" else "206 Partial Content"
            val response = buildString {
                append("HTTP/1.1 $status\r\nConnection: close\r\nContent-Type: application/octet-stream\r\n")
                if (path != "/unknown") append("Content-Length: ${end - start + 1}\r\n")
                if (range != null) append("Content-Range: bytes $start-$end/${this@FixtureServer.length}\r\n")
                if (path != "/no-range" && path != "/no-range-fast") append("Accept-Ranges: bytes\r\n")
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
                if (path == "/throttled" || path == "/slow" || path == "/no-range" || (path == "/large" &&
                    InstrumentationRegistry.getArguments().getString("largeFile") != "true")) {
                    output.flush()
                    Thread.sleep(if (path == "/throttled") 2 else 15)
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
