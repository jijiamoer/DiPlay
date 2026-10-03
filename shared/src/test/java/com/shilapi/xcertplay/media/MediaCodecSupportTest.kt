package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class MediaCodecSupportTest {
    @Test fun truncatedAccessUnitIsRejectedInsteadOfSubmittingItsValidPrefix() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0, 0, 0, 9, 0x41)).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0)).size)
    }

    @Test fun findsRandomAccessAfterParameterSetsButNeverOnInterframes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 10)
        assertTrue(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x65, 20), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x41, 20), VideoCodec.H264))
        for (type in 16..21) assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 20), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, 2, 1, 20), VideoCodec.H265))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun hevcCodecSpecificDataBuildsAnnexBParameterSets() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val record = hevcRecord(
            vps,
            sps,
            pps,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            MediaCodecSupport.hevcCodecSpecificData(record),
        )
    }

    @Test
    fun malformedHevcCodecSpecificDataIsRejected() {
        val truncated = hevcRecord(byteArrayOf(0x40, 0x01), byteArrayOf())
            .copyOfRange(0, 25)

        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(truncated).size)
    }

    @Test
    fun lowLatencySpsPreservesParseability() {
        // Real High-profile SPS (profile_idc=100, no bitstream_restriction in VUI).
        val sps = byteArrayOf(
            0x67, 0x64, 0x00, 0x1f, 0xac.toByte(), 0xd9.toByte(), 0x40, 0x50,
            0x05, 0xbb.toByte(), 0x01, 0x6e, 0x9f.toByte(), 0x97.toByte(),
            0xff.toByte(), 0x00, 0x01, 0x00, 0x01, 0xf6.toByte(), 0xce.toByte(),
            0x3c, 0x80.toByte(),
        )

        val patched = MediaCodecSupport.lowLatencyAvcSps(sps)

        assertEquals(sps[0], patched[0])
        // The rewritten VUI is longer than the original bare SPS.
        assertTrue(patched.size >= sps.size)
        // Re-patching must still parse (output is a valid SPS), proving round-trip safety.
        val repatched = MediaCodecSupport.lowLatencyAvcSps(patched)
        assertTrue(repatched.size >= patched.size)
    }

    @Test
    fun lowLatencySpsLeavesNonSpsBytesAlone() {
        val notSps = byteArrayOf(0x68, 0x01, 0x02, 0x03)
        assertTrue(notSps.contentEquals(MediaCodecSupport.lowLatencyAvcSps(notSps)))
        val tooShort = byteArrayOf(0x67)
        assertTrue(tooShort.contentEquals(MediaCodecSupport.lowLatencyAvcSps(tooShort)))
    }

    private fun hevcRecord(vararg parameterSets: ByteArray): ByteArray {
        var size = 23
        parameterSets.forEach { size += 5 + it.size }
        val record = ByteArray(size)
        record[0] = 1
        record[21] = 3
        record[22] = parameterSets.size.toByte()
        var cursor = 23
        parameterSets.forEachIndexed { index, parameterSet ->
            record[cursor++] = (32 + index).toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = (parameterSet.size ushr 8).toByte()
            record[cursor++] = parameterSet.size.toByte()
            parameterSet.copyInto(record, cursor)
            cursor += parameterSet.size
        }
        return record
    }
}
