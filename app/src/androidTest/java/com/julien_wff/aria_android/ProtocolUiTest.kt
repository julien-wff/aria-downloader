package com.julien_wff.aria_android

import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.julien_wff.aria_android.downloads.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProtocolUiTest {
    @get:Rule(order = 0) val visibility = TestActivityVisibilityRule()
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sharedMetadataWaitsForReviewAndSelectionSurvivesActivityRecreation() {
        val context = compose.activity
        val resolver = context.contentResolver
        val controller = DownloadRuntime.controller(context)
        compose.waitUntil(10_000) { controller.loaded.value }
        compose.runOnUiThread { androidx.lifecycle.ViewModelProvider(context)[DownloadsViewModel::class.java].askedNotificationPermission = true }
        val server = PayloadServer()
        val before = controller.tasks.value.map { it.id }.toSet()
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "aria-ui-${System.nanoTime()}.meta4")
            put(MediaStore.Downloads.MIME_TYPE, "application/metalink4+xml")
            put(MediaStore.Downloads.IS_PENDING, 1)
        })!!
        var added: String? = null
        try {
            resolver.openOutputStream(uri)!!.use { it.write("""<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="bundle/a.bin"><size>100</size><url>${server.url("/a.bin")}</url></file><file name="bundle/b.bin"><size>200</size><url>${server.url("/b.bin")}</url></file></metalink>""".toByteArray()) }
            compose.runOnUiThread {
                context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_SEND)
                    .setType("application/metalink4+xml").putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Shared metalink file · Review before starting").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, controller.tasks.value.map { it.id }.toSet())
            compose.onNodeWithText("Review files").performClick()
            compose.waitUntil(30_000) { controller.tasks.value.any { it.id !in before && it.state.status == DownloadStatus.AWAITING_SELECTION } }
            added = controller.tasks.value.single { it.id !in before }.id
            compose.onNodeWithText("Shared metalink").performClick()
            compose.onNodeWithText("Clear selection").performScrollTo().performClick()
            compose.onNodeWithText("Start selected files").assertIsNotEnabled()
            compose.onNodeWithText("bundle/b.bin").performScrollTo().performClick()
            compose.onNodeWithText("1 selected ·", substring = true).assertExists()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Start selected files").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("1 selected ·", substring = true).assertExists()
            assertEquals(DownloadStatus.AWAITING_SELECTION, controller.tasks.value.single { it.id == added }.state.status)
            assertTrue(controller.tasks.value.single { it.id == added }.files.none { it.publishedUri != null })
            assertTrue(server.requests.isEmpty())
            compose.onNodeWithText("Start selected files").performScrollTo().performClick()
            compose.waitUntil(60_000) { controller.tasks.value.single { it.id == added }.state.status == DownloadStatus.COMPLETED }
            val completed = controller.tasks.value.single { it.id == added }
            assertEquals(listOf(false, true), completed.files.map { it.selected })
            assertNull(completed.files.first().publishedUri)
            val saved = completed.files.last()
            assertTrue(saved.published)
            assertArrayEquals(server.payload, resolver.openInputStream(android.net.Uri.parse(saved.publishedUri))!!.use { it.readBytes() })
            assertFalse(server.requests.any { it == "/a.bin" })
        } finally {
            added?.let { kotlinx.coroutines.runBlocking { DownloadFiles(context, controller).delete(it, true) } }
            resolver.delete(uri, null, null)
            server.close()
        }
    }

    private class PayloadServer : java.io.Closeable {
        val payload = ByteArray(200) { it.toByte() }
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val executor = java.util.concurrent.Executors.newCachedThreadPool()
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}$path"
        init {
            executor.execute {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    executor.execute {
                        try { client.use {
                            it.soTimeout = 10_000
                            val reader = it.getInputStream().bufferedReader()
                            val request = reader.readLine() ?: return@use
                            val path = request.split(' ')[1]
                            requests += path
                            while (!reader.readLine().isNullOrEmpty()) { }
                            it.getOutputStream().apply {
                                write("HTTP/1.1 200 OK\r\nContent-Length: 200\r\nConnection: close\r\n\r\n".toByteArray())
                                if (!request.startsWith("HEAD ")) write(payload)
                            }
                        } } catch (_: Exception) { }
                    }
                }
            }
        }
        override fun close() { socket.close(); executor.shutdownNow() }
    }
}
