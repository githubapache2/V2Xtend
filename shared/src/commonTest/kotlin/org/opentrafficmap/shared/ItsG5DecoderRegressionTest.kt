package org.opentrafficmap.shared

import org.opentrafficmap.receiver.ItsG5Decoder

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Ground-truth regression for the ITS-G5 decoder (M2).
 *
 * Dataset 1: 210 real field frames from fahrt-20260807-172421.pcap
 *            (208 CAM + 2 DENM) — the DENM envelope candidates fix lives here.
 * Dataset 2: 9 firmware replay frames (replay_data.h).
 *
 * Match criterion mirrors the historical kotlinc harness: msgType equal and
 * latitude within 1e-4 degrees of the tshark ground truth.
 */
class ItsG5DecoderRegressionTest {

    @Test
    fun fieldDrive_210packets_matchGroundTruth() {
        val rows = VerifyFixtures.rows.filter { it.dataset == "1" }
        assertEquals(210, rows.size, "dataset 1 must contain 210 frames")

        var ok = 0
        val mismatches = mutableListOf<String>()
        for (row in rows) {
            val r = ItsG5Decoder.decodeFull(hexToBytes(row.payloadHex))
            val lat = r.latLon?.first
            val match = r.msgType.name == row.gtMsgType &&
                lat != null && row.gtLat != null &&
                abs(lat - row.gtLat) < 0.0001
            if (match) ok++ else mismatches.add(
                "frame=${row.frameNum} GT=${row.gtMsgType}/${row.gtLat} " +
                    "GOT=${r.msgType.name}/$lat",
            )
        }

        if (mismatches.isNotEmpty()) {
            fail("dataset1 $ok/210 — mismatches:\n" + mismatches.joinToString("\n"))
        }
        assertEquals(210, ok)
    }

    @Test
    fun replay_9packets_matchGroundTruth() {
        val rows = VerifyFixtures.rows.filter { it.dataset == "2" }
        assertEquals(9, rows.size, "dataset 2 must contain 9 replay frames")

        var ok = 0
        val mismatches = mutableListOf<String>()
        for (row in rows) {
            val r = ItsG5Decoder.decodeFull(hexToBytes(row.payloadHex))
            val lat = r.latLon?.first
            val match = r.msgType.name == row.gtMsgType &&
                lat != null && row.gtLat != null &&
                abs(lat - row.gtLat) < 0.0001
            if (match) ok++ else mismatches.add(
                "replay#${row.frameNum} GT=${row.gtMsgType}/${row.gtLat} " +
                    "GOT=${r.msgType.name}/$lat",
            )
        }

        if (mismatches.isNotEmpty()) {
            fail("dataset2 $ok/9 — mismatches:\n" + mismatches.joinToString("\n"))
        }
        assertEquals(9, ok)
    }

    @Test
    fun denmEnvelopeFix_bothFieldDenmsDecode() {
        val denms = VerifyFixtures.rows.filter { it.dataset == "1" && it.gtMsgType == "DENM" }
        assertEquals(2, denms.size)
        for (row in denms) {
            val r = ItsG5Decoder.decodeFull(hexToBytes(row.payloadHex))
            assertEquals(ItsG5Decoder.MsgType.DENM, r.msgType, "frame ${row.frameNum}")
            assertTrue(r.latLon != null, "frame ${row.frameNum} missing lat/lon")
            assertTrue(abs(r.latLon!!.first - row.gtLat!!) < 0.0001)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < out.size) {
            val hi = hex[i * 2].digitToInt(16)
            val lo = hex[i * 2 + 1].digitToInt(16)
            out[i] = ((hi shl 4) + lo).toByte()
            i++
        }
        return out
    }
}
