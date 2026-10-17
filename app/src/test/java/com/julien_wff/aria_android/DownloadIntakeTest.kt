package com.julien_wff.aria_android

import org.junit.Assert.*
import org.junit.Test

class DownloadIntakeTest {
    @Test fun findsWebLinkInClipboardAndUsesDecodedPathName() {
        val draft = clipboardDraft("Here is the file: <https://example.org/files/my%20report+v2.pdf?token=secret#section>")!!
        assertEquals("https://example.org/files/my%20report+v2.pdf?token=secret#section", draft.url)
        assertEquals("my report+v2.pdf", draft.fileName)
        assertEquals("archive.zip", clipboardDraft(" https://example.org/archive.zip ")!!.fileName)
    }

    @Test fun unsupportedOrMissingClipboardLinksDoNotPrefill() {
        for (text in listOf(null, "", "ordinary text", "ftp://example.org/file", "https://", "https://user:password@example.org/file")) {
            assertNull(text, clipboardDraft(text))
        }
    }

    @Test fun pathlessOrUnsafeFilenamesUseDefaultWithoutQueryOrFragment() {
        for (url in listOf("https://example.org", "https://example.org/path/", "https://example.org/?filename=report.pdf",
            "https://example.org/%2E%2E", "https://example.org/a%2Fb", "https://example.org/a%5Cb", "https://example.org/a%00b")) {
            assertEquals(url, "download", suggestedName(url))
        }
        assertEquals("file.tar.gz", suggestedName("https://example.org/file.tar.gz?download=1#file.exe"))
    }
}
