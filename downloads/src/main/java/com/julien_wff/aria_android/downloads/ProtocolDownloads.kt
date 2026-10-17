package com.julien_wff.aria_android.downloads

import androidx.room.TypeConverter
import android.content.Context
import android.net.Uri
import java.io.*
import java.net.URI
import java.util.Base64
import java.util.UUID

/** Protocol choice is explicit for web URLs without a recognized metadata extension. */
enum class DownloadProtocol { HTTP, TORRENT, MAGNET, METALINK }
fun downloadProtocol(source: String): DownloadProtocol = when {
    source.startsWith("magnet:", true) -> DownloadProtocol.MAGNET
    runCatching { URI(source).path.orEmpty().endsWith(".torrent", true) }.getOrDefault(false) -> DownloadProtocol.TORRENT
    runCatching { URI(source).path.orEmpty().let { it.endsWith(".metalink", true) || it.endsWith(".meta4", true) } }.getOrDefault(false) -> DownloadProtocol.METALINK
    else -> DownloadProtocol.HTTP
}

data class DownloadFile(
    val index: Int, val path: String, val size: Long, val completedBytes: Long = 0,
    val selected: Boolean = true, val supported: Boolean = true,
    val publishedUri: String? = null, val published: Boolean = false,
)

/** One durable per-file journal is committed atomically with the task transition. */
internal class ProtocolConverters {
    @TypeConverter fun encode(files: List<DownloadFile>): String {
        if (files.isEmpty()) return ""
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(files.size)
            files.forEach { f ->
                out.writeInt(f.index); out.writeUTF(f.path); out.writeLong(f.size); out.writeLong(f.completedBytes)
                out.writeBoolean(f.selected); out.writeBoolean(f.supported); out.writeUTF(f.publishedUri.orEmpty()); out.writeBoolean(f.published)
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }
    @TypeConverter fun decode(value: String): List<DownloadFile> {
        if (value.isEmpty()) return emptyList()
        return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(value))).use { input ->
            val count = input.readInt(); require(count in 1..10_000)
            List(count) { DownloadFile(input.readInt(), input.readUTF(), input.readLong(), input.readLong(),
                input.readBoolean(), input.readBoolean(), input.readUTF().ifEmpty { null }, input.readBoolean()) }
        }
    }
}

/** Copy while the sender's transient grant is valid; never pass content URIs to libaria2. */
class MetadataImports(private val context: Context) {
    fun copy(uri: Uri, mime: String?): Pair<String, DownloadProtocol> {
        require(uri.scheme == "content") { "Share a torrent or Metalink document." }
        val directory = File(context.noBackupFilesDir, "aria2/imports")
        check(directory.isDirectory || directory.mkdirs())
        val file = File(directory, UUID.randomUUID().toString())
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(8192); var total = 0
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        total += count
                        require(total <= 8 * 1024 * 1024) { "Metadata exceeds the 8 MiB limit." }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IOException("Shared metadata is unavailable")
            require(file.length() > 0) { "Shared metadata is empty." }
            val first = file.inputStream().use { it.read() }
            val kind = when {
                first == 'd'.code -> DownloadProtocol.TORRENT
                mime in setOf("application/metalink+xml", "application/metalink4+xml") || first == '<'.code || first == 0xef || first.toChar().isWhitespace() -> DownloadProtocol.METALINK
                else -> throw IllegalArgumentException("Share a supported torrent or Metalink file.")
            }
            return file.toURI().toString() to kind
        } catch (error: Exception) { file.delete(); throw error }
    }
}
