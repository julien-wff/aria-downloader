package com.julien_wff.aria_android.aria2

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProtocolMetadataTest {
    @Test fun rejectsNonAdjacentAncestorPathsAndCheckpointDirectories() {
        for (paths in listOf(listOf("a", "a-keep", "a/b"), listOf("a", "a.aria2/b"))) {
            val entries = paths.joinToString("") { path ->
                """<file name="$path"><url>https://example.org/file</url></file>"""
            }
            withMetadata("<metalink>$entries</metalink>") {
                assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.metalink(it) }
            }
        }
    }
    @Test fun pieceHashesCannotBeUsedAsWholeFileChecksums() {
        val pieceHash = "ab".repeat(32)
        val wholeHash = "cd".repeat(32)
        val pieces = """<pieces length="1024" type="sha-256"><hash type="sha-256">$pieceHash</hash></pieces>"""
        withMetadata("""<metalink><file name="a">$pieces<url>https://example.org/a</url></file></metalink>""") {
            assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.metalink(it) }
        }
        withMetadata("""<metalink><file name="a">$pieces<hash type="sha-256">$wholeHash</hash><url>https://example.org/a</url></file></metalink>""") {
            assertEquals("sha-256=$wholeHash", ProtocolMetadata.metalink(it).single().checksum)
        }
    }
    @Test fun rejectsTraversalReservedNamesAndConflictingPaths() {
        for (path in listOf("../a", "/a", "a/../b", "a//b", "C:/file", "a\\b", ".source.torrent", "a/./b", "a\u0000b")) {
            assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.safePath(path) }
        }
        assertEquals("folder/résumé-🎵.bin", ProtocolMetadata.safePath("folder/résumé-🎵.bin"))
        withMetadata("""<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="a"><url>https://example.org/a</url></file><file name="a/b"><url>https://example.org/b</url></file></metalink>""") {
            assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.metalink(it) }
        }
    }
    @Test fun rejectsPayloadPathsThatOverlapNativeResumeFiles() {
        withMetadata("""<metalink><file name="a"><url>https://example.org/a</url></file><file name="a.aria2"><url>https://example.org/b</url></file></metalink>""") {
            assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.metalink(it) }
        }
    }
    @Test fun aggregateFileJournalIsBoundedBeforeAnyPayloadCanStart() {
        val entries = (1..2000).joinToString("") { index ->
            """<file name="${"x".repeat(200)}/$index.bin"><url>https://example.org/$index</url></file>"""
        }
        withMetadata("<metalink>$entries</metalink>") {
            val error = assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.metalink(it) }
            assertEquals("Metadata file list is too large to save safely.", error.message)
        }
    }
    @Test fun metalinkFiltersUnsupportedMirrorsAndRetainsChecksumsAndLargeSizes() {
        val hash = "ab".repeat(32)
        withMetadata("""<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="folder/a"><size>5000000000</size><hash type="sha-256">$hash</hash><url>ftp://example.org/a</url><url>https://example.org/a</url><url>https://user:pass@example.org/a</url></file><file name="b"><url>ftp://example.org/b</url><url type="bittorrent">https://example.org/b.torrent</url></file></metalink>""") {
            val files = ProtocolMetadata.metalink(it)
            assertEquals(5_000_000_000L, files.first().length)
            assertEquals("sha-256=$hash", files.first().checksum)
            assertEquals(listOf("https://example.org/a"), files.first().mirrors)
            assertTrue(files.first().supported)
            assertFalse(files.last().supported)
        }
    }
    @Test fun xmlEntitiesAndInvalidChecksumsAreRejected() {
        for (text in listOf("""<!DOCTYPE metalink [<!ENTITY x SYSTEM "file:///etc/passwd">]><metalink><file name="a"><url>&x;</url></file></metalink>""",
            """<metalink><file name="a"><hash type="sha-256">bad</hash><url>https://example.org/a</url></file></metalink>""")) {
            withMetadata(text) { assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.metalink(it) } }
        }
    }
    @Test fun magnetValidationSupportsV1AndRejectsMissingOrUnsupportedHashes() {
        EngineRequest("magnet:?xt=urn:btih:${"a".repeat(40)}", kind = EngineSourceKind.MAGNET)
        for (url in listOf("magnet:?dn=file", "magnet:?xt=urn:btih=bad", "magnet:?xt=urn:btmh:1234")) {
            assertThrows(IllegalArgumentException::class.java) { EngineRequest(url, kind = EngineSourceKind.MAGNET) }
        }
    }
    @Test fun torrentParsesSafePathsAndRejectsTraversalAndUnsupportedV2() {
        fun torrent(name: String) = "d4:infod6:lengthi5000000000e4:name${name.toByteArray().size}:${name}12:piece lengthi16384e6:pieces20:${"x".repeat(20)}ee"
        withMetadata(torrent("file.bin")) {
            val file = ProtocolMetadata.torrent(it).single()
            assertEquals("file.bin", file.path)
            assertEquals(5_000_000_000L, file.length)
        }
        withMetadata(torrent("../escape")) { assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.torrent(it) } }
        withMetadata("d4:infod6:lengthi1e4:name1:aee") { assertThrows(IllegalArgumentException::class.java) { ProtocolMetadata.torrent(it) } }
    }
    private fun withMetadata(text: String, action: (java.io.File) -> Unit) {
        val file = Files.createTempFile("metadata", ".data").toFile()
        try { file.writeText(text); action(file) } finally { file.delete() }
    }
}
