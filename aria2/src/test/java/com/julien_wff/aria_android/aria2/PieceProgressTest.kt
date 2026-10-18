package com.julien_wff.aria_android.aria2

import org.junit.Assert.*
import org.junit.Test

class PieceProgressTest {
    @Test fun rejectsMalformedAndUnavailableOverview() {
        listOf(longArrayOf(), longArrayOf(1, 1, 1), longArrayOf(1, 1, 2, 1),
            longArrayOf(2, 1, 1, 1), longArrayOf(1, 0, 0, 1), longArrayOf(1, 1, -1, 1)).forEach {
            assertNull(decodePieces(it))
        }
    }
    @Test fun decodesOverviewWithoutLosingLongPieceSizes() {
        val progress = decodePieces(longArrayOf(3, 1L shl 33, 1, 2, 1, 1))!!
        assertEquals(3L, progress.count)
        assertEquals(1L shl 33, progress.pieceBytes)
        assertEquals(listOf(PieceCell(1, 2), PieceCell(1, 1)), progress.cells)
    }
}
