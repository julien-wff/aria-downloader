package com.julien_wff.aria_android.aria2

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Metadata is bounded and validated before libaria2 can create payload files. */
internal object ProtocolMetadata {
    const val MAX_BYTES = 8 * 1024 * 1024
    const val MAX_FILES = 10_000
    fun safePath(path: String): String {
        require(path.isNotBlank() && path.length <= 2048 && !path.startsWith('/') &&
            path.none { it == '\\' || it == ':' || it.isISOControl() } &&
            !path.substringBefore('/').startsWith(".source.") &&
            path.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.length <= 240 }) {
            "Metadata contains an unsafe file path."
        }
        return path
    }
    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    fun torrent(file: File): List<EngineFile> {
        require(file.length() in 1..MAX_BYTES.toLong()) { "Torrent metadata is too large or empty." }
        val bytes = file.readBytes()
        var pos = 0
        var nodes = 0
        fun parse(depth: Int): Any {
            require(depth < 64 && ++nodes <= 200_000 && pos < bytes.size) { "Invalid torrent metadata." }
            return when (bytes[pos].toInt().toChar()) {
                'd' -> {
                    pos++; val map = linkedMapOf<String, Any>()
                    while (pos < bytes.size && bytes[pos].toInt().toChar() != 'e') {
                        val key = utf8(parse(depth + 1) as ByteArray)
                        require(key !in map) { "Duplicate torrent metadata key." }
                        map[key] = parse(depth + 1)
                    }
                    require(pos < bytes.size); pos++; map
                }
                'l' -> {
                    pos++; val list = mutableListOf<Any>()
                    while (pos < bytes.size && bytes[pos].toInt().toChar() != 'e') list += parse(depth + 1)
                    require(pos < bytes.size); pos++; list
                }
                'i' -> {
                    pos++; val start = pos
                    while (pos < bytes.size && bytes[pos].toInt().toChar() != 'e') pos++
                    require(pos < bytes.size); val value = String(bytes, start, pos - start, Charsets.US_ASCII).toLong()
                    pos++; value
                }
                in '0'..'9' -> {
                    val start = pos
                    while (pos < bytes.size && bytes[pos].toInt().toChar().isDigit()) pos++
                    require(pos < bytes.size && bytes[pos++].toInt().toChar() == ':')
                    val count = String(bytes, start, pos - start - 1, Charsets.US_ASCII).toInt()
                    require(count >= 0 && count <= bytes.size - pos)
                    bytes.copyOfRange(pos, pos + count).also { pos += count }
                }
                else -> error("Invalid torrent metadata.")
            }
        }
        val root = parse(0) as Map<*, *>
        require(pos == bytes.size)
        val info = root["info"] as? Map<*, *> ?: error("Missing torrent information.")
        require(info["pieces"] is ByteArray) { "This torrent format is not supported by the bundled engine." }
        fun name(map: Map<*, *>, key: String) = utf8((map["$key.utf-8"] ?: map[key]) as ByteArray)
        val top = safePath(name(info, "name")); require('/' !in top)
        require((info["attr"] as? ByteArray)?.let(::utf8)?.contains('l') != true) { "Torrent symlinks are not supported." }
        val files = info["files"] as? List<*>
        val result = if (files == null) listOf(EngineFile(1, top, (info["length"] as Long))) else {
            require(files.size in 1..MAX_FILES)
            files.mapIndexed { index, entry ->
                val data = entry as Map<*, *>
                require((data["attr"] as? ByteArray)?.let(::utf8)?.contains('l') != true) { "Torrent symlinks are not supported." }
                val parts = (data["path.utf-8"] ?: data["path"]) as List<*>
                val path = parts.joinToString("/") { part ->
                    utf8(part as ByteArray).also { require('/' !in it) }
                }
                EngineFile(index + 1, safePath("$top/$path"), data["length"] as Long)
            }
        }
        validate(result)
        return result
    }
    fun metalink(file: File): List<EngineFile> {
        require(file.length() in 1..MAX_BYTES.toLong()) { "Metalink metadata is too large or empty." }
        val text = utf8(file.readBytes()).removePrefix("\uFEFF")
        require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "XML declarations of entities are not supported." }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IllegalArgumentException("External XML entities are not supported.") }
        }
        val doc = builder.parse(text.byteInputStream())
        require(doc.documentElement.localName == "metalink") { "A Metalink document is required." }
        val entries = doc.getElementsByTagNameNS("*", "file")
        require(entries.length in 1..MAX_FILES)
        val result = (0 until entries.length).map { index ->
            val element = entries.item(index) as Element
            fun children(tag: String) = element.getElementsByTagNameNS("*", tag)
            val urls = children("url").let { list -> (0 until list.length).mapNotNull { n ->
                val resource = list.item(n) as Element
                val url = resource.textContent.trim()
                val declaredType = resource.getAttribute("type").lowercase()
                val uri = runCatching { URI(url) }.getOrNull()
                url.takeIf { declaredType in setOf("", "http", "https") && uri?.scheme?.lowercase() in setOf("http", "https") && !uri?.host.isNullOrEmpty() &&
                    uri?.rawUserInfo == null && url.none(Char::isISOControl) }
            }.distinct() }
            // Piece hashes describe individual chunks, never the whole payload.
            val hashes = children("hash").let { list -> (0 until list.length).map { list.item(it) as Element }
                .filter { hash -> hash.parentNode == element ||
                    (hash.parentNode as? Element)?.let { parent -> parent.localName == "verification" && parent.parentNode == element } == true } }
            val checksum = hashes.firstNotNullOfOrNull { hash ->
                val type = hash.getAttribute("type").lowercase().replace("sha256", "sha-256").replace("sha512", "sha-512")
                val length = mapOf("sha-1" to 40, "sha-256" to 64, "sha-512" to 128)[type]
                val value = hash.textContent.trim().lowercase()
                if (length != null && value.length == length && value.all { it in "0123456789abcdef" }) "$type=$value" else null
            }
            require((hashes.isEmpty() && children("pieces").length == 0) || checksum != null) { "Metalink has no supported valid file checksum." }
            val size = children("size").item(0)?.textContent?.trim()?.toLong() ?: 0L
            EngineFile(index + 1, safePath(element.getAttribute("name")), size, mirrors = urls, checksum = checksum,
                supported = urls.isNotEmpty())
        }
        validate(result)
        return result
    }
    private fun validate(files: List<EngineFile>) {
        require(files.all { it.length >= 0 })
        // The atomic Room journal is Base64 encoded. Budget modified-UTF paths plus
        // per-file fields and MediaStore URI headroom so it stays below CursorWindow limits.
        require(files.sumOf { it.path.length.toLong() * 3 + 256 } <= 1_000_000) {
            "Metadata file list is too large to save safely."
        }
        val paths = files.map { safePath(it.path) }
        require(paths.distinct().size == paths.size) { "Metadata contains duplicate file paths." }
        val pathSet = paths.toSet()
        require(paths.none { "$it.aria2" in pathSet }) { "Metadata paths conflict with native checkpoint files." }
        val occupiedFiles = pathSet + paths.map { "$it.aria2" }
        require(paths.none { path ->
            path.indices.any { index -> path[index] == '/' && path.substring(0, index) in occupiedFiles }
        }) { "Metadata contains conflicting file paths." }
        files.fold(0L) { sum, file -> Math.addExact(sum, file.length) }
    }
}
