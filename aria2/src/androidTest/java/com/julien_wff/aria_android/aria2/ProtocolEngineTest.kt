package com.julien_wff.aria_android.aria2

import androidx.test.platform.app.InstrumentationRegistry
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Controlled HTTP web seeds and a minimal BEP 9 metadata peer; no public swarm is required. */
class ProtocolEngineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun root() = File(context.cacheDir, "protocol-${System.nanoTime()}").apply { mkdirs() }
    private var activeRoot: File? = null
    private fun engine(root: File): NativeDownloadEngine {
        activeRoot = root
        val backend = JniBackend()
        return NativeDownloadEngine(root, { SessionEnvironment(File(root, "ca.pem"), emptyList()) }, object : NativeBackend by backend {
            override fun open(options: Array<String>) = backend.open(options + arrayOf("log", File(root, "native-debug.log").path, "log-level", "debug"))
        })
    }
    private suspend fun DownloadEngine.await(id: EngineTaskId, state: EngineTaskState): EngineTaskSnapshot = withTimeout(90_000) {
        snapshots.first { it.tasks.any { task -> task.id == id && task.state in setOf(state, EngineTaskState.FAILED) } }
            .tasks.single { it.id == id }.also { assertEquals("Engine error ${it.errorCode}: ${it.errorMessage} " +
                (if (it.state == EngineTaskState.FAILED) activeRoot?.resolve("native-debug.log")?.takeIf { f -> f.exists() }?.readLines()?.filter { line ->
                    listOf("metadata", "peer", "tracker", "handshake", "extension", "Handshake").any { word -> line.contains(word, true) }
                }?.takeLast(30)?.joinToString("\n") else ""), state, it.state) }
    }

    @Test fun torrentWebSeedWaitsForReviewAndRecoversSelectedFileFromCheckpoint() = runBlocking {
        val root = root()
        var engine = engine(root)
        HttpFixture().use { server ->
            val a = ByteArray(512 * 1024) { (it % 251).toByte() }
            val b = ByteArray(2 * 1024 * 1024) { ((it + 13) % 251).toByte() }
            server.content["/seed/bundle/a.bin"] = a
            server.content["/seed/bundle/b.bin"] = b
            server.slow = true
            server.content["/input.torrent"] = torrent(server.url("/seed/"), a, b)
            try {
                engine.start()
                val id = engine.add(EngineRequest(server.url("/input.torrent"), "Bundle", kind = EngineSourceKind.TORRENT))
                val review = engine.await(id, EngineTaskState.AWAITING_SELECTION)
                assertEquals(listOf("bundle/a.bin", "bundle/b.bin"), review.files.map { it.path })
                assertTrue(server.requests.none { it.first.startsWith("/seed/") })
                engine.selectFiles(id, listOf(2)); engine.resume(id)
                withTimeout(60_000) { engine.snapshots.first { it.tasks.any { task -> task.id == id && task.completedBytes > 64 * 1024 } } }
                engine.pause(id)
                assertTrue(File(root, id.value).walkTopDown().any { it.name.endsWith(".aria2") })
                engine.close()
                engine = engine(root)
                engine.start()
                engine.add(EngineRequest(server.url("/input.torrent"), "Bundle", taskId = id,
                    kind = EngineSourceKind.TORRENT, selectedFiles = listOf(2)))
                val completed = engine.await(id, EngineTaskState.COMPLETED)
                assertEquals(b.size.toLong(), completed.completedBytes)
                val pieces = requireNotNull(completed.pieces)
                assertEquals(pieces.count, pieces.cells.sumOf { it.total })
                assertTrue(pieces.cells.sumOf { it.completed } < pieces.count)
                assertArrayEquals(sha256(b), sha256(File(root, "${id.value}/bundle/b.bin").readBytes()))
                assertTrue(completed.files.single { it.index == 2 }.selected)
                assertFalse(completed.files.single { it.index == 1 }.selected)
                // aria2 retains this checkpoint because unselected files are unfinished.
                assertTrue(File(root, "${id.value}/bundle.aria2").exists())
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun metalinkMirrorsChecksumsAndSequentialChildrenRecoverWithoutChangingIdentity() = runBlocking {
        val root = root()
        var engine = engine(root)
        HttpFixture().use { server ->
            val data = ByteArray(1024 * 1024) { (it % 251).toByte() }
            server.content["/a"] = data; server.content["/b"] = data
            server.slow = true
            val hash = sha256(data).joinToString("") { "%02x".format(it) }
            server.content["/input.meta4"] = """<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="nested/a.bin"><size>${data.size}</size><hash type="sha-256">$hash</hash><url>${server.url("/missing")}</url><url>${server.url("/a")}</url></file><file name="b.bin"><size>${data.size}</size><hash type="sha-256">$hash</hash><url>${server.url("/b")}</url></file><file name="unsupported"><url>ftp://example.org/file</url></file></metalink>""".toByteArray()
            try {
                engine.start()
                val id = engine.add(EngineRequest(server.url("/input.meta4"), kind = EngineSourceKind.METALINK))
                val review = engine.await(id, EngineTaskState.AWAITING_SELECTION)
                assertEquals(3, review.files.size)
                assertFalse(review.files.last().supported)
                assertTrue(server.requests.none { it.first == "/a" || it.first == "/b" })
                engine.selectFiles(id, listOf(1, 2)); engine.resume(id)
                withTimeout(60_000) { engine.snapshots.first { it.tasks.any { task -> task.id == id && task.completedBytes > 64 * 1024 } } }
                engine.pause(id); engine.close()
                engine = engine(root); engine.start()
                engine.add(EngineRequest(server.url("/input.meta4"), taskId = id, kind = EngineSourceKind.METALINK, selectedFiles = listOf(1, 2)))
                val finished = engine.await(id, EngineTaskState.COMPLETED)
                assertEquals("b.bin", finished.pieces?.fileName)
                assertEquals(finished.pieces!!.count, finished.pieces!!.cells.sumOf { it.completed })
                assertArrayEquals(sha256(data), sha256(File(root, "${id.value}/nested/a.bin").readBytes()))
                assertArrayEquals(sha256(data), sha256(File(root, "${id.value}/b.bin").readBytes()))
                assertTrue(server.requests.any { it.first == "/missing" })
                assertTrue(server.requests.any { it.second?.substringAfter("bytes=")?.substringBefore('-')?.toLongOrNull()?.let { value -> value > 0 } == true })
            } finally { engine.close(); root.deleteRecursively() }
        }
    }

    @Test fun magnetResolvesMetadataFromControlledPeerWithoutStartingPayload() = runBlocking {
        val root = root(); val engine = engine(root)
        val info = bencode(mapOf("length" to 16384L, "name" to "magnet.bin", "piece length" to 16384L,
            "pieces" to MessageDigest.getInstance("SHA-1").digest(ByteArray(16384))))
        MetadataPeer(info).use { peer ->
            HttpFixture().use { tracker ->
                tracker.content["/announce"] = bencode(mapOf("interval" to 30L,
                    "peers" to byteArrayOf(127, 0, 0, 1, (peer.port shr 8).toByte(), peer.port.toByte())))
                val hash = MessageDigest.getInstance("SHA-1").digest(info).joinToString("") { "%02x".format(it) }
                val magnet = "magnet:?xt=urn:btih:$hash&tr=${URLEncoder.encode(tracker.url("/announce"), "UTF-8")}"
                try {
                    engine.start()
                    val id = engine.add(EngineRequest(magnet, kind = EngineSourceKind.MAGNET))
                    val review = engine.await(id, EngineTaskState.AWAITING_SELECTION)
                    assertEquals("magnet.bin", review.files.single().path)
                    assertFalse(File(root, "${id.value}/magnet.bin").exists())
                    engine.stop(); engine.close()
                    val restored = engine(root)
                    try {
                        restored.start()
                        restored.add(EngineRequest(magnet, taskId = id, kind = EngineSourceKind.MAGNET))
                        restored.await(id, EngineTaskState.AWAITING_SELECTION)
                        restored.remove(id)
                        assertEquals(EngineTaskState.REMOVED, restored.snapshots.value.tasks.single().state)
                    } finally { restored.close() }
                } finally { engine.close(); root.deleteRecursively() }
            }
        }
    }

    private fun torrent(seed: String, a: ByteArray, b: ByteArray): ByteArray {
        val combined = a + b
        val pieces = ByteArrayOutputStream()
        for (offset in combined.indices step 16384) pieces.write(MessageDigest.getInstance("SHA-1").digest(combined.copyOfRange(offset, minOf(offset + 16384, combined.size))))
        return bencode(mapOf("url-list" to listOf(seed), "info" to mapOf("name" to "bundle", "piece length" to 16384L,
            "pieces" to pieces.toByteArray(), "files" to listOf(mapOf("length" to a.size.toLong(), "path" to listOf("a.bin")),
                mapOf("length" to b.size.toLong(), "path" to listOf("b.bin"))))))
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun bencode(value: Any): ByteArray = when (value) {
        is String -> bencode(value.toByteArray())
        is ByteArray -> "${value.size}:".toByteArray() + value
        is Long -> "i${value}e".toByteArray()
        is List<*> -> "l".toByteArray() + value.fold(byteArrayOf()) { result, item -> result + bencode(item!!) } + "e".toByteArray()
        is Map<*, *> -> "d".toByteArray() + value.keys.map { it as String }.sorted().fold(byteArrayOf()) { result, key -> result + bencode(key) + bencode(value[key]!!) } + "e".toByteArray()
        else -> error("Unsupported fixture value")
    }
    private inner class MetadataPeer(val info: ByteArray) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        private val executor = Executors.newCachedThreadPool()
        private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
        init {
            executor.execute {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: Exception) { break }
                    clients += client
                    executor.execute {
                        try { client.use {
                            client.soTimeout = 30_000
                            val input = DataInputStream(client.getInputStream()); val output = DataOutputStream(client.getOutputStream())
                            val handshake = ByteArray(68); input.readFully(handshake)
                            handshake[25] = (handshake[25].toInt() or 0x10).toByte()
                            ByteArray(20) { (it + 65).toByte() }.copyInto(handshake, 48)
                            android.util.Log.i("ProtocolFixture", "Metadata peer handshake")
                            output.write(handshake)
                            fun message(payload: ByteArray) { output.writeInt(payload.size); output.write(payload); output.flush() }
                            message(byteArrayOf(20, 0) + bencode(mapOf("m" to mapOf("ut_metadata" to 1L), "metadata_size" to info.size.toLong())))
                            var responseId = 0
                            while (true) {
                                val size = input.readInt(); require(size in 0..65536)
                                val packet = ByteArray(size); input.readFully(packet)
                                if (size > 2 && packet[0] == 20.toByte() && packet[1] == 0.toByte()) {
                                    responseId = Regex("ut_metadatai([0-9]+)e").find(String(packet.drop(2).toByteArray()))!!.groupValues[1].toInt()
                                }
                                if (size > 2 && packet[0] == 20.toByte() && packet[1] == 1.toByte()) {
                                    message(byteArrayOf(20, responseId.toByte()) + bencode(mapOf("msg_type" to 1L, "piece" to 0L, "total_size" to info.size.toLong())) + info)
                                }
                            }
                        } } catch (_: Exception) { } finally { clients -= client }
                    }
                }
            }
        }
        override fun close() { server.close(); synchronized(clients) { clients.forEach { runCatching { it.close() } } }; executor.shutdownNow() }
    }
    private class HttpFixture : Closeable {
        val content = Collections.synchronizedMap(mutableMapOf<String, ByteArray>())
        val requests = Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        @Volatile var slow = false
        private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val clients = Collections.synchronizedSet(mutableSetOf<Socket>())
        fun url(path: String) = "http://127.0.0.1:${server.localPort}$path"
        init {
            executor.execute {
                while (!server.isClosed) {
                    val client = try { server.accept() } catch (_: Exception) { break }
                    clients += client
                    executor.execute {
                        try { client.use {
                            client.soTimeout = 30_000
                            val reader = client.getInputStream().bufferedReader()
                            val request = reader.readLine() ?: return@use
                            val path = request.split(' ')[1].substringBefore('?')
                            val headers = mutableMapOf<String, String>()
                            while (true) {
                                val line = reader.readLine() ?: return@use
                                if (line.isEmpty()) break
                                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                            }
                            requests += path to headers["range"]
                            val output = client.getOutputStream()
                            val bytes = content[path]
                            if (bytes == null) { output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); return@use }
                            val range = headers["range"]?.removePrefix("bytes=")?.split('-')
                            val start = range?.firstOrNull()?.toIntOrNull() ?: 0
                            val end = minOf(range?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex, bytes.lastIndex)
                            output.write(buildString {
                                append("HTTP/1.1 ${if (range == null) "200 OK" else "206 Partial Content"}\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
                                if (range != null) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                                append("\r\n")
                            }.toByteArray())
                            if (request.startsWith("HEAD ")) return@use
                            var offset = start
                            while (offset <= end) {
                                val count = minOf(8192, end - offset + 1)
                                output.write(bytes, offset, count); output.flush(); offset += count
                                if (slow && path != "/input.torrent" && path != "/input.meta4") Thread.sleep(10)
                            }
                        } } catch (_: Exception) { } finally { clients -= client }
                    }
                }
            }
        }
        override fun close() { server.close(); synchronized(clients) { clients.forEach { runCatching { it.close() } } }; executor.shutdownNow() }
    }
}
