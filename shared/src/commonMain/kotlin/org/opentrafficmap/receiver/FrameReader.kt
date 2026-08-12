package org.opentrafficmap.receiver

/**
 * Resync parser for the ESP-side wire format:
 *
 *     magic[4]  = "ITS5"
 *     sec       u32 LE
 *     usec      u32 LE
 *     len       u16 LE
 *     payload   <len> bytes
 *
 * Drop everything that's not a magic-prefixed frame (ROM bootloader text, stray
 * log bytes, etc.). Same logic as bridge/its_g5_bridge.py:FrameReader.
 */
class FrameReader {
    private val buf = ArrayDeque<Byte>()
    private var seq = 0L

    /** Feed raw bytes from the serial port. Returns any complete frames found. */
    fun feed(chunk: ByteArray, len: Int = chunk.size): List<Frame> {
        for (i in 0 until len) buf.addLast(chunk[i])
        val out = mutableListOf<Frame>()
        while (true) {
            val mIdx = findMagic()
            if (mIdx < 0) {
                // Keep up to 3 trailing bytes (magic could span the next read)
                while (buf.size > 3) buf.removeFirst()
                return out
            }
            // Drop pre-magic noise
            repeat(mIdx) { buf.removeFirst() }
            if (buf.size < HEADER_LEN) return out
            val hdr = ByteArray(HEADER_LEN)
            run {
                val it = buf.iterator()
                for (j in 0 until HEADER_LEN) hdr[j] = it.next()
            }
            val sec = readLeU32(hdr, 4)
            val usec = readLeU32(hdr, 8)
            val plen = readLeU16(hdr, 12)

            if (plen > MAX_PAYLOAD) {
                // implausible: drop this magic and resync
                repeat(4) { buf.removeFirst() }
                continue
            }
            if (buf.size < HEADER_LEN + plen) return out
            // Consume header + payload
            repeat(HEADER_LEN) { buf.removeFirst() }
            val payload = ByteArray(plen)
            for (j in 0 until plen) payload[j] = buf.removeFirst()

            val d = ItsG5Decoder.decodeFull(payload)
            out += Frame(
                seq         = ++seq,
                sec         = sec,
                usec        = usec,
                payload     = payload,
                etherType   = d.etherType,
                msgType     = d.msgType,
                stationId   = d.stationId,
                stationType = d.stationType,
                latLon      = d.latLon,
                headingDeg  = d.headingDeg,
                speedMps    = d.speedMps,
                spatPhase   = d.spatPhase,
                denmCause   = d.denmCause,
                secured     = d.secured,
            )
        }
    }

    private fun findMagic(): Int {
        // Linear scan; small buffers make this fine.
        val n = buf.size
        if (n < 4) return -1
        val arr = buf.iterator()
        var b0 = 0; var b1 = 0; var b2 = 0
        var i = 0
        while (arr.hasNext()) {
            val b3 = arr.next().toInt() and 0xFF
            if (i >= 3 && b0 == 0x49 && b1 == 0x54 && b2 == 0x53 && b3 == 0x35) {
                return i - 3
            }
            b0 = b1; b1 = b2; b2 = b3
            i++
        }
        return -1
    }


    private fun readLeU32(p: ByteArray, off: Int): Long {
        val v = (p[off].toInt() and 0xFF) or
            ((p[off + 1].toInt() and 0xFF) shl 8) or
            ((p[off + 2].toInt() and 0xFF) shl 16) or
            ((p[off + 3].toInt() and 0xFF) shl 24)
        return v.toLong() and 0xFFFFFFFFL
    }

    private fun readLeU16(p: ByteArray, off: Int): Int =
        (p[off].toInt() and 0xFF) or ((p[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        const val HEADER_LEN = 14
        const val MAX_PAYLOAD = 4096
    }
}
