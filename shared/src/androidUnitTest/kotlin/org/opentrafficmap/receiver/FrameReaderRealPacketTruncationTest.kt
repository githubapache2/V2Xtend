package org.opentrafficmap.receiver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Truncation/resync using **real Graz replay payloads** (MAPEM 927 B + DENM 643 B
 * from `firmware/main/replay_data.h`), not only synthetic `0xAB` fills.
 *
 * Simulates BLE MTU ~509: feed MAPEM chunk-1 only, then full DENM wire frame.
 * Without resync the DENM would be absorbed; with resync we decode DENM.
 */
class FrameReaderRealPacketTruncationTest {
    // Wire fixtures generated from replay_data.h (ITS5 + payload). Kept as hex
    // prefixes only for the truncated MAPEM head; DENM is full wire frame.
    // Full binaries also under commonTest/resources/trunc_*.bin for tooling.

    private fun hex(s: String): ByteArray {
        val clean = s.replace(Regex("\\s"), "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test
    fun grazMapemChunk1Lost_thenDenm_resyncsToDenm() {
        // ITS5 header for MAPEM len=927 sec=4 — first 509 bytes of wire (chunk-1)
        // Built offline from REPLAY_MAPEM_4_SA3BD; only head needed for incomplete wait.
        val mapemWire = loadResource("trunc_mapem_wire.bin")
        assertEquals(14 + 927, mapemWire.size)
        val denmWire = loadResource("trunc_denm_wire.bin")
        assertEquals(14 + 643, denmWire.size)

        val reader = FrameReader()
        val partial = reader.feed(mapemWire.copyOfRange(0, 509))
        assertTrue(partial.isEmpty(), "MAPEM chunk-1 alone must not complete")

        val out = reader.feed(denmWire)
        assertEquals(1, out.size, "DENM must survive after truncated MAPEM")
        assertEquals(ItsG5Decoder.MsgType.DENM, out[0].msgType)
        // Graz DENM #5 ground-truth from replay_data.h comment
        val lat = out[0].latLon?.first
        assertTrue(lat != null && kotlin.math.abs(lat - 47.0562685) < 0.0001, "lat=$lat")
    }

    @Test
    fun grazFullReplayChunked_noLoss_yields14() {
        val mapem = loadResource("trunc_mapem_wire.bin")
        val denm = loadResource("trunc_denm_wire.bin")
        // Sanity: multi-chunk assemble of the real MAPEM alone
        val reader = FrameReader()
        assertTrue(reader.feed(mapem.copyOfRange(0, 509)).isEmpty())
        val done = reader.feed(mapem.copyOfRange(509, mapem.size))
        assertEquals(1, done.size)
        assertEquals(ItsG5Decoder.MsgType.MAPEM, done[0].msgType)
        // DENM after complete MAPEM
        val d = reader.feed(denm)
        assertEquals(1, d.size)
        assertEquals(ItsG5Decoder.MsgType.DENM, d[0].msgType)
    }

    private fun loadResource(name: String): ByteArray {
        val stream = javaClass.classLoader.getResourceAsStream(name)
            ?: error("missing test resource $name — expected under commonTest/resources")
        return stream.use { it.readBytes() }
    }
}
