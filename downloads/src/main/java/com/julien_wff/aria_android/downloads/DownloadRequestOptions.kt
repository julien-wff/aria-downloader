package com.julien_wff.aria_android.downloads

import com.julien_wff.aria_android.aria2.EngineOptions

/** HTTP request overrides captured for one task, independent of global defaults. */
data class DownloadRequestOptions(val referrer: String? = null, val userAgent: String? = null) {
    init { EngineOptions(referrer = referrer, userAgent = userAgent) }

    companion object {
        fun fromInput(referrer: String, userAgent: String): DownloadRequestOptions {
            require(referrer.none { it.isISOControl() }) { "HTTP referrer must not contain control characters." }
            require(userAgent.none { it.isISOControl() }) { "User-Agent must not contain control characters." }
            return DownloadRequestOptions(referrer.trim().ifBlank { null }, userAgent.trim().ifBlank { null })
        }
    }
}
