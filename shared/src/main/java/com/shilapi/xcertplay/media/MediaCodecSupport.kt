package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.ByteArrayOutputStream

/**
 * Pure byte helpers that convert the CarPlay screen/audio payloads into the
 * records Android MediaCodec and AudioTrack expect. Kept free of Android types
 * so they stay testable on the JVM.
 */
object MediaCodecSupport {
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /** Splits an AVCDecoderConfigurationRecord into raw first SPS and first PPS. */
    fun avcParameterSets(codecData: ByteArray): Pair<ByteArray, ByteArray> {
        if (codecData.size < 7) return emptySet()
        var cursor = 6
        val sps = readParameterSets(codecData, cursor, codecData[5].toInt() and 0x1f)
        cursor += sps.sumOf { it.size + 2 }
        if (cursor >= codecData.size) return emptySet()
        val pps = readParameterSets(codecData, cursor + 1, codecData[cursor].toInt() and 0xff)
        return (sps.firstOrNull() ?: ByteArray(0)) to (pps.firstOrNull() ?: ByteArray(0))
    }

    /**
     * Converts an HEVCDecoderConfigurationRecord (hvcC) into Annex B VPS/SPS/PPS CSD.
     *
     * Android video decoders expect the initialization data as NAL units with start codes,
     * not as the raw ISO-BMFF hvcC record.
     */
    fun hevcCodecSpecificData(codecData: ByteArray): ByteArray {
        if (codecData.size < HEVC_FIXED_RECORD_SIZE || codecData[0].toInt() != 1) {
            return ByteArray(0)
        }

        var cursor = HEVC_ARRAY_COUNT_OFFSET
        val arrayCount = codecData[cursor++].toInt() and 0xff
        val output = ByteArrayOutputStream()
        repeat(arrayCount) {
            if (cursor >= codecData.size) return ByteArray(0)
            cursor += 1 // array_completeness, reserved, and nal_unit_type
            if (cursor + 2 > codecData.size) return ByteArray(0)
            val nalUnitCount = readU16Be(codecData, cursor)
            cursor += 2
            repeat(nalUnitCount) {
                if (cursor + 2 > codecData.size) return ByteArray(0)
                val nalUnitLength = readU16Be(codecData, cursor)
                cursor += 2
                if (cursor + nalUnitLength > codecData.size) return ByteArray(0)
                output.write(START_CODE)
                output.write(codecData, cursor, nalUnitLength)
                cursor += nalUnitLength
            }
        }
        return output.toByteArray()
    }

    /** Converts CarPlay's length-prefixed NAL units into an Annex B byte stream. */
    fun toAnnexB(lengthPrefixed: ByteArray): ByteArray {
        if (lengthPrefixed.size >= 4 &&
            lengthPrefixed[0] == 0.toByte() &&
            lengthPrefixed[1] == 0.toByte() &&
            lengthPrefixed[2] == 0.toByte() &&
            lengthPrefixed[3] == 1.toByte()
        ) {
            return lengthPrefixed
        }

        var inputOffset = 0
        var outputSize = 0
        while (inputOffset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, inputOffset)
            inputOffset += 4
            if (length <= 0 || length > lengthPrefixed.size - inputOffset) return ByteArray(0)
            if (length > Int.MAX_VALUE - outputSize - START_CODE.size) return ByteArray(0)
            outputSize += START_CODE.size + length
            inputOffset += length
        }
        if (outputSize == 0 || inputOffset != lengthPrefixed.size) return ByteArray(0)

        val output = ByteArray(outputSize)
        var offset = 0
        var outputOffset = 0
        while (offset + 4 <= lengthPrefixed.size) {
            val length = readU32Be(lengthPrefixed, offset)
            offset += 4
            if (length <= 0 || offset + length > lengthPrefixed.size) break
            START_CODE.copyInto(output, outputOffset)
            outputOffset += START_CODE.size
            lengthPrefixed.copyInto(output, outputOffset, offset, offset + length)
            outputOffset += length
            offset += length
        }
        return output
    }

    /** IDR (AVC) or IRAP (HEVC) starts an independently decodable reference chain. */
    fun isRandomAccess(annexB: ByteArray, codec: VideoCodec): Boolean {
        var cursor = 0
        while (cursor + 3 < annexB.size) {
            val prefix = when {
                annexB[cursor] != 0.toByte() || annexB[cursor + 1] != 0.toByte() -> 0
                annexB[cursor + 2] == 1.toByte() -> 3
                annexB[cursor + 2] == 0.toByte() && annexB[cursor + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0 && cursor + prefix < annexB.size) {
                val header = annexB[cursor + prefix].toInt() and 255
                if (codec == VideoCodec.H264 && header and 31 == 5) return true
                if (codec == VideoCodec.H265 && cursor + prefix + 1 < annexB.size &&
                    (header shr 1) and 63 in 16..21) return true
                cursor += prefix
            } else cursor++
        }
        return false
    }

    /** Wraps one raw AAC-LC access unit in an MPEG-4 ADTS frame. */
    fun adtsFrame(accessUnit: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val header = ByteArray(7)
        writeAdtsHeader(header, accessUnit.size + header.size, sampleRate, channels)
        return header + accessUnit
    }

    /**
     * Writes the seven-byte ADTS header for a frame of [frameLength] bytes (header +
     * access unit) into [out]. Hot audio paths fill a reused header and put it into the
     * codec input buffer separately instead of concatenating a frame per packet.
     */
    fun writeAdtsHeader(out: ByteArray, frameLength: Int, sampleRate: Int, channels: Int) {
        val frequencyIndex = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        out[0] = 0xff.toByte()
        out[1] = 0xf1.toByte()
        out[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        out[3] = (((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte()
        out[4] = ((frameLength ushr 3) and 0xff).toByte()
        out[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        out[6] = 0xfc.toByte()
    }

    /** Extracts one RFC 3640 AAC access unit from an RTP payload. */
    fun aacAccessUnit(rtpPayload: ByteArray): ByteArray {
        if (rtpPayload.size < 4) return ByteArray(0)
        val headerBits = readU16Be(rtpPayload, 0)
        if (headerBits < 16 || headerBits % 16 != 0) return ByteArray(0)
        val headerBytes = headerBits / 8
        if (2 + headerBytes > rtpPayload.size) return ByteArray(0)
        val auSize = (readU16Be(rtpPayload, 2) shr 3) and 0x1fff
        val start = 2 + headerBytes
        val end = minOf(start + auSize, rtpPayload.size)
        return if (end <= start) ByteArray(0) else rtpPayload.copyOfRange(start, end)
    }

    /** MPEG-4 sampling frequency index used by both ADTS and AudioSpecificConfig. */
    fun aacFrequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> 3
    }

    private fun emptySet(): Pair<ByteArray, ByteArray> = ByteArray(0) to ByteArray(0)

    /**
     * Rewrites an H.264 SPS NAL so the decoder need not buffer pictures for reordering.
     * When a stream omits the VUI bitstream_restriction block, some decoders allocate a
     * conservative DPB and hold decoded frames for hundreds of milliseconds before output,
     * which is felt as constant input lag regardless of resolution. Returns the original
     * bytes untouched whenever the NAL cannot be parsed safely.
     */
    fun lowLatencyAvcSps(spsNal: ByteArray): ByteArray = try {
        LowDelaySps.patch(spsNal)
    } catch (error: Exception) {
        spsNal
    }

    private fun readParameterSets(source: ByteArray, offset: Int, count: Int): List<ByteArray> {
        val sets = ArrayList<ByteArray>(count)
        var cursor = offset
        var index = 0
        while (index < count && cursor + 2 <= source.size) {
            index++
            val length = readU16Be(source, cursor)
            cursor += 2
            if (cursor + length > source.size) break
            sets.add(source.copyOfRange(cursor, cursor + length))
            cursor += length
        }
        return sets
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private const val HEVC_FIXED_RECORD_SIZE = 23
    private const val HEVC_ARRAY_COUNT_OFFSET = 22
}

/**
 * Bit-level SPS (seq_parameter_set) rewriter for H.264. Copies the header verbatim up
 * to vui_parameters_present_flag, then writes a VUI that forces
 * bitstream_restriction_flag=1, max_num_reorder_frames=0, and
 * max_dec_frame_buffering=max_num_ref_frames — the "no reordering, no extra latency"
 * contract. Any parse surprise aborts and the caller keeps the untouched NAL.
 */
private object LowDelaySps {
    fun patch(spsNal: ByteArray): ByteArray {
        require(spsNal.size >= 4 && spsNal[0].toInt() and 0x1f == 7) { "not an SPS NAL" }
        val reader = ExpReader(deEmphasis(spsNal.copyOfRange(1, spsNal.size)))
        val writer = ExpWriter()
        val c = Copier(reader, writer)

        c.bits(24) // profile_idc + constraint flags + level_idc
        val profileIdc = spsNal[1].toInt() and 0xff
        c.ue() // seq_parameter_set_id
        var chromaFormatIdc = 1
        if (profileIdc in HIGH_PROFILES) {
            chromaFormatIdc = c.ue()
            if (chromaFormatIdc == 3) c.bit() // separate_colour_plane_flag
            c.ue() // bit_depth_luma_minus8
            c.ue() // bit_depth_chroma_minus8
            c.bit() // qpprime_y_zero_transform_bypass_flag
            if (c.bit() == 1) { // seq_scaling_matrix_present_flag
                val lists = if (chromaFormatIdc == 3) 12 else 8
                for (i in 0 until lists) {
                    if (c.bit() == 1) copyScalingList(c, if (i < 6) 16 else 64)
                }
            }
        }
        c.ue() // log2_max_frame_num_minus4
        val picOrderCntType = c.ue()
        if (picOrderCntType == 0) c.ue() else if (picOrderCntType == 1) {
            c.bit() // delta_pic_order_always_zero_flag
            c.se() // offset_for_non_ref_pic
            c.se() // offset_for_top_to_bottom_field
            repeat(c.ue()) { c.se() } // offset_for_ref_frame[i]
        }
        val maxRefFrames = c.ue()
        c.bit() // gaps_in_frame_num_value_allowed_flag
        c.ue() // pic_width_in_mbs_minus1
        c.ue() // pic_height_in_map_units_minus1
        if (c.bit() == 0) c.bit() // frame_mbs_only_flag, then mb_adaptive_frame_field_flag
        c.bit() // direct_8x8_inference_flag
        if (c.bit() == 1) { // frame_cropping_flag
            c.ue(); c.ue(); c.ue(); c.ue()
        }

        writer.bit(1) // vui_parameters_present_flag — always write our own VUI
        if (reader.bit() == 1) {
            copyVui(reader, writer, maxRefFrames)
        } else {
            writeEmptyVuiHead(writer)
            writeRestrictionBlock(writer, maxRefFrames)
        }
        writer.rbspTrailing()

        val rbsp = writer.toByteArray()
        return byteArrayOf(spsNal[0]) + emphasize(rbsp)
    }

    /**
     * Copies the leading VUI fields verbatim, consumes whatever restriction block the
     * original had, and emits the low-delay block in its place.
     */
    private fun copyVui(reader: ExpReader, writer: ExpWriter, maxRefFrames: Int) {
        val c = Copier(reader, writer)
        if (c.bit() == 1) { // aspect_ratio_info_present_flag
            val idc = c.bits(8)
            if (idc == 255) c.bits(32) // sar_width + sar_height
        }
        if (c.bit() == 1) c.bit() // overscan_info_present_flag -> overscan_appropriate_flag
        if (c.bit() == 1) { // video_signal_type_present_flag
            c.bits(4) // video_format + video_full_range_flag
            if (c.bit() == 1) c.bits(24) // colour_description_present_flag -> primaries etc.
        }
        if (c.bit() == 1) { // chroma_loc_info_present_flag
            c.ue(); c.ue()
        }
        if (c.bit() == 1) c.bits(33) // timing_info_present_flag -> num_units/time_scale/fixed
        var hrdPresent = false
        if (c.bit() == 1) { copyHrd(c); hrdPresent = true } // nal_hrd_parameters_present_flag
        if (c.bit() == 1) { copyHrd(c); hrdPresent = true } // vcl_hrd_parameters_present_flag
        if (hrdPresent) c.bit() // low_delay_hrd_flag
        c.bit() // pic_struct_present_flag
        if (reader.bit() == 1) { // consume the original restriction fields
            reader.bit(); repeat(6) { reader.ue() }
        }
        writeRestrictionBlock(writer, maxRefFrames)
    }

    private fun writeEmptyVuiHead(writer: ExpWriter) {
        writer.bit(0) // aspect_ratio_info_present_flag
        writer.bit(0) // overscan_info_present_flag
        writer.bit(0) // video_signal_type_present_flag
        writer.bit(0) // chroma_loc_info_present_flag
        writer.bit(0) // timing_info_present_flag
        writer.bit(0) // nal_hrd_parameters_present_flag
        writer.bit(0) // vcl_hrd_parameters_present_flag
        // (no HRD -> no low_delay_hrd_flag)
        writer.bit(0) // pic_struct_present_flag
    }

    private fun writeRestrictionBlock(writer: ExpWriter, maxRefFrames: Int) {
        writer.bit(1) // bitstream_restriction_flag
        writer.bit(1) // motion_vectors_over_pic_boundaries_flag
        writer.ue(0) // max_bytes_per_pic_denom
        writer.ue(0) // max_bits_per_mb_denom
        writer.ue(0) // log2_max_mv_length_horizontal
        writer.ue(0) // log2_max_mv_length_vertical
        writer.ue(0) // max_num_reorder_frames — the point of the exercise
        writer.ue(maxRefFrames.coerceAtLeast(1)) // max_dec_frame_buffering
    }

    private fun copyHrd(c: Copier) {
        val cpbCount = c.ue() + 1
        c.bits(8) // bit_rate_scale + cpb_size_scale
        repeat(cpbCount) { c.ue(); c.ue(); c.bit() }
        c.bits(20) // the five delay/offset lengths
    }

    private fun copyScalingList(c: Copier, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (i in 0 until size) {
            if (nextScale != 0) {
                val delta = c.se()
                nextScale = (lastScale + delta + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    private fun deEmphasis(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            if (zeros >= 2 && byte.toInt() and 0xff == 3) { zeros = 0; continue }
            zeros = if (byte.toInt() == 0) zeros + 1 else 0
            out.write(byte.toInt())
        }
        return out.toByteArray()
    }

    private fun emphasize(rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(rbsp.size + rbsp.size / 4)
        var zeros = 0
        for (byte in rbsp) {
            val value = byte.toInt() and 0xff
            if (zeros >= 2 && value <= 3) { out.write(3); zeros = 0 }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private val HIGH_PROFILES = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    /** Reads Exp-Golomb and plain bits, echoing them into a writer verbatim. */
    private class Copier(private val reader: ExpReader, private val writer: ExpWriter) {
        fun bit(): Int = reader.bit().also(writer::bit)
        fun bits(n: Int): Int = reader.bits(n).also { writer.bits(it, n) }
        fun ue(): Int = reader.ue().also(writer::ue)
        fun se(): Int = reader.se().also(writer::se)
    }

    private class ExpReader(private val data: ByteArray) {
        private var pos = 0
        fun bit(): Int {
            check(pos < data.size * 8) { "SPS bitstream overrun" }
            val value = (data[pos / 8].toInt() shr (7 - pos % 8)) and 1
            pos++
            return value
        }
        fun bits(n: Int): Int { var v = 0; repeat(n) { v = (v shl 1) or bit() }; return v }
        fun ue(): Int {
            var leadingZeros = 0
            while (bit() == 0) leadingZeros++
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + bits(leadingZeros)
        }
        fun se(): Int {
            val ue = ue()
            return if (ue and 1 == 0) -(ue / 2) else (ue + 1) / 2
        }
    }

    private class ExpWriter {
        private val out = ByteArrayOutputStream(64)
        private var acc = 0
        private var pos = 0
        fun bit(v: Int) {
            acc = (acc shl 1) or (v and 1)
            pos++
            if (pos % 8 == 0) { out.write(acc); acc = 0 }
        }
        fun bits(value: Int, n: Int) { for (i in n - 1 downTo 0) bit((value shr i) and 1) }
        fun ue(value: Int) {
            val codeNum = value + 1
            val infoBits = Integer.toBinaryString(codeNum).length - 1
            repeat(infoBits) { bit(0) }
            bits(codeNum, infoBits + 1)
        }
        fun se(value: Int) = ue(if (value <= 0) -2 * value else 2 * value - 1)
        fun rbspTrailing() { bit(1); while (pos % 8 != 0) bit(0) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
