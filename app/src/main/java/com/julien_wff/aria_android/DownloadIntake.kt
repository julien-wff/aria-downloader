package com.julien_wff.aria_android

import com.julien_wff.aria_android.downloads.downloadProtocol
import com.julien_wff.aria_android.downloads.DownloadProtocol
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class DownloadDraft(val url: String, val fileName: String)

internal fun suggestedName(url: String): String = runCatching {
    val segment = URI(url).rawPath.orEmpty().substringAfterLast('/')
    val decoded = URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)
    decoded.takeIf { it.isNotBlank() && it != "." && it != ".." &&
        it.none { char -> char == '/' || char == '\\' || char.isISOControl() } }
}.getOrNull() ?: "download"

/** Accept supported web and magnet links; query strings and fragments never become filenames. */
internal fun clipboardDraft(text: String?): DownloadDraft? {
    if (text.isNullOrBlank()) return null
    fun valid(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null) ||
            (value.startsWith("magnet:?") && Regex("(?:^|&)xt=urn:btih:(?:[a-fA-F0-9]{40}|[a-zA-Z2-7]{32})(?:&|$)").containsMatchIn(value.substringAfter('?')))
    }.getOrDefault(false)
    val trimmed = text.trim()
    val url = if (valid(trimmed)) trimmed else Regex("(?:https?://|magnet:\\?)[^\\s<>\"']+", RegexOption.IGNORE_CASE)
        .findAll(trimmed).map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }.firstOrNull(::valid)
        ?: return null
    return DownloadDraft(url, if (downloadProtocol(url) == DownloadProtocol.MAGNET) "Magnet download" else suggestedName(url))
}
