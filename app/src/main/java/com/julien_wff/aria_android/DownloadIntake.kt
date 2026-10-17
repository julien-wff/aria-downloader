package com.julien_wff.aria_android

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

/** Accept supported web links only; query strings and fragments never become filenames. */
internal fun clipboardDraft(text: String?): DownloadDraft? {
    if (text.isNullOrBlank()) return null
    fun valid(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null
    }.getOrDefault(false)
    val trimmed = text.trim()
    val url = if (valid(trimmed)) trimmed else Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
        .findAll(trimmed).map { it.value.trimEnd('.', ',', ';', ')', ']', '}') }.firstOrNull(::valid)
        ?: return null
    return DownloadDraft(url, suggestedName(url))
}
