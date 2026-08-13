package org.opentrafficmap.receiver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameReaderTruncationTest {
    private fun its5(payload: ByteArray, sec: Int = 1, usec: Int = 0): ByteArray {
        val out = ByteArray(14 + payload.size)
        out[0] = 'I'.code.toByte(); out[1] = 'T'.code.toByte()
        out[2] = 'S'.code.toByte(); out[3] = '5'.code.toByte()
        fun u32(o: Int, v: Int) {
            out[o] = (v and 0xff).toByte()
            out[o + 1] = ((v shr 8) and 0xff).toByte()
            out[o + 2] = ((v shr 16) and 0xff).toByte()
            out[o + 3] = ((v shr 24) and 0xff).toByte()
        }
        fun u16(o: Int, v: Int) {
            out[o] = (v and 0xff).toByte()
            out[o + 1] = ((v shr 8) and 0xff).toByte()
        }
        u32(4, sec); u32(8, usec); u16(12, payload.size)
        payload.copyInto(out, 14)
        return out
    }

    @Test
    fun truncatedFrame_resyncsWhenNextChunkStartsWithMagic() {
        val reader = FrameReader()
        val big = its5(ByteArray(600) { 0xAB.toByte() }, sec = 10) // needs 2 BLE chunks typically
        val denmLike = its5(ByteArray(100) { 0xCD.toByte() }, sec = 11)

        // Chunk-1 only of the big frame (incomplete)
        val partial = reader.feed(big.copyOfRange(0, 200))
        assertTrue(partial.isEmpty())

        // Next notify is a fresh ITS5 frame — must NOT be absorbed as payload
        val recovered = reader.feed(denmLike)
        assertEquals(1, recovered.size)
        assertEquals(11, recovered[0].sec.toInt())
        assertEquals(100, recovered[0].payload.size)
    }

    @Test
    fun normalMultiChunk_stillAssembles() {
        val reader = FrameReader()
        val frame = its5(ByteArray(100) { 1 }, sec = 5)
        assertTrue(reader.feed(frame.copyOfRange(0, 40)).isEmpty())
        val done = reader.feed(frame.copyOfRange(40, frame.size))
        assertEquals(1, done.size)
        assertEquals(5, done[0].sec.toInt())
    }
}
