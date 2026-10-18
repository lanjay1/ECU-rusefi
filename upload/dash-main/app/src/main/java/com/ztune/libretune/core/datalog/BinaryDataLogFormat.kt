package com.ztune.libretune.core.datalog

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * v1.3.9 (audit finding #19) — Binary, block-compressed datalog format
 * ("`.ztlog`"), replacing the plain/gzip-CSV interim fix.
 *
 * ## Layout
 *
 * ```
 * HEADER
 *   magic            4 bytes   "ZTLG"
 *   version          1 byte    = 1
 *   channelCount     1 byte    (max 255)
 *   for each channel:
 *     nameLength     1 byte
 *     nameUtf8       nameLength bytes
 *
 * BLOCK (repeated until EOF)
 *   blockStartMs     8 bytes   epoch-ms of this block's first sample
 *   sampleCount      2 bytes   unsigned short, samples in this block
 *   compressedLength 4 bytes   byte length of the DEFLATE payload that follows
 *   crc32            8 bytes   CRC32 of the (still-compressed) payload bytes
 *   payload          compressedLength bytes — DEFLATE of the raw block body
 *
 * RAW BLOCK BODY (before compression), per sample i in the block:
 *   deltaMs          2 bytes   unsigned short, ms since the PREVIOUS sample
 *                              (0 for sample 0 in the block)
 *   channel values   4 bytes each (Float32, NaN = missing), in header order
 * ```
 *
 * ## Why this shape (vs the CSV/gzip interim fix)
 *
 * - **Ukuran**: Float32 biner + Deflate per-blok biasanya lebih kecil dari
 *   CSV+gzip untuk data numerik berulang (tidak ada overhead teks ASCII,
 *   tidak perlu parse angka bolak-balik).
 * - **Tahan crash per-blok**: kalau proses ke-kill di tengah nulis (baterai
 *   habis, app di-force-close, dst), CSV/gzip mentah jadi TIDAK BISA DIBACA
 *   SAMA SEKALI dari titik korupsi ke depan (satu gzip stream utuh). Format
 *   ini menyimpan `sampleCount`+`compressedLength`+`crc32` DI DEPAN tiap
 *   blok — pembaca ([DataLogPlayback]) bisa berhenti tepat di blok terakhir
 *   yang lengkap & valid, dan tetap dapat SEMUA data sebelum titik crash,
 *   bukan kehilangan seluruh file.
 *
 * File berekstensi `.ztlog`. Bukan format universal (tidak bisa dibuka
 * spreadsheet langsung) — untuk itu pakai [DataLogPlayback.toCsv] atau
 * fitur "Export to CSV" di UI datalog.
 */
object BinaryDataLogFormat {
    val MAGIC = byteArrayOf('Z'.code.toByte(), 'T'.code.toByte(), 'L'.code.toByte(), 'G'.code.toByte())
    const val VERSION: Byte = 1
    const val EXTENSION = ".ztlog"

    /** Samples per block. Smaller = more crash-resilient but more per-block overhead; larger = better compression ratio. */
    const val DEFAULT_BLOCK_SIZE = 256

    const val HEADER_FIXED_SIZE = 4 + 1 + 1 // magic + version + channelCount
    const val BLOCK_HEADER_SIZE = 8 + 2 + 4 + 8 // blockStartMs + sampleCount + compressedLength + crc32

    // ------------------------------------------------------------------
    //  Encoding
    // ------------------------------------------------------------------

    /** Serialize the file header: magic, version, channel names. */
    fun encodeHeader(channelNames: List<String>): ByteArray {
        require(channelNames.size <= 255) { "Max 255 channels supported" }
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(VERSION.toInt())
        out.write(channelNames.size)
        for (name in channelNames) {
            val bytes = name.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 255) { "Channel name too long: $name" }
            out.write(bytes.size)
            out.write(bytes)
        }
        return out.toByteArray()
    }

    /**
     * One raw sample to be encoded into a block: absolute timestamp (epoch-ms)
     * plus one Float value per channel (use [Float.NaN] for "no data").
     */
    data class RawSample(val timestampMs: Long, val values: FloatArray)

    /**
     * Encode a full block (header + compressed payload) ready to append to
     * the file. [samples] must be non-empty and sorted by timestamp.
     */
    fun encodeBlock(samples: List<RawSample>): ByteArray {
        require(samples.isNotEmpty()) { "Cannot encode an empty block" }

        val body = ByteArrayOutputStream()
        var prevTs = samples[0].timestampMs
        for ((i, sample) in samples.withIndex()) {
            val delta = if (i == 0) 0L else (sample.timestampMs - prevTs).coerceIn(0L, 65535L)
            prevTs = sample.timestampMs
            body.write(delta.toInt() ushr 8)
            body.write(delta.toInt() and 0xFF)
            for (v in sample.values) {
                writeFloat(body, v)
            }
        }
        val rawBytes = body.toByteArray()

        val compressed = deflate(rawBytes)
        val crc = CRC32().apply { update(compressed) }.value

        val out = ByteArrayOutputStream()
        writeLong(out, samples[0].timestampMs)
        writeUShort(out, samples.size)
        writeInt(out, compressed.size)
        writeLong(out, crc)
        out.write(compressed)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    //  Decoding
    // ------------------------------------------------------------------

    data class BlockHeaderInfo(
        val startTimestamp: Long,
        val sampleCount: Int,
        val compressedLength: Int,
        val crc32: Long
    )

    /** Parse an 23-byte block header from [bytes] starting at [offset]. */
    fun decodeBlockHeader(bytes: ByteArray, offset: Int): BlockHeaderInfo {
        var p = offset
        val startTs = readLong(bytes, p); p += 8
        val count = readUShort(bytes, p); p += 2
        val len = readInt(bytes, p); p += 4
        val crc = readLong(bytes, p)
        return BlockHeaderInfo(startTs, count, len, crc)
    }

    data class DecodedBlock(val timestamps: LongArray, val values: Array<FloatArray>)

    /**
     * Decompress+parse a block's payload (already extracted as [compressed]).
     * @param channelCount number of float channels per sample (from file header).
     */
    fun decodeBlockPayload(
        compressed: ByteArray,
        sampleCount: Int,
        channelCount: Int,
        blockStartTimestamp: Long
    ): DecodedBlock {
        val raw = inflate(compressed, expectedSize = sampleCount * (2 + channelCount * 4))
        val timestamps = LongArray(sampleCount)
        val values = Array(sampleCount) { FloatArray(channelCount) }

        var p = 0
        var runningTs = blockStartTimestamp
        for (i in 0 until sampleCount) {
            val delta = ((raw[p].toInt() and 0xFF) shl 8) or (raw[p + 1].toInt() and 0xFF)
            p += 2
            if (i != 0) runningTs += delta
            timestamps[i] = runningTs
            for (c in 0 until channelCount) {
                values[i][c] = readFloat(raw, p)
                p += 4
            }
        }
        return DecodedBlock(timestamps, values)
    }

    /** Verify a block's CRC32 against its declared value — call before decoding to detect corruption. */
    fun verifyCrc(compressed: ByteArray, expectedCrc: Long): Boolean =
        CRC32().apply { update(compressed) }.value == expectedCrc

    // ------------------------------------------------------------------
    //  Low-level helpers
    // ------------------------------------------------------------------

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_SPEED)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2 + 16)
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(expectedSize.coerceAtLeast(64))
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val n = inflater.inflate(buf)
            if (n == 0 && inflater.needsInput()) break
            out.write(buf, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun writeUShort(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF); out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeLong(out: ByteArrayOutputStream, v: Long) {
        for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xFF).toInt())
    }

    private fun writeFloat(out: ByteArrayOutputStream, v: Float) {
        writeInt(out, java.lang.Float.floatToIntBits(v))
    }

    private fun readUShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readInt(b: ByteArray, off: Int): Int {
        return ((b[off].toInt() and 0xFF) shl 24) or
               ((b[off + 1].toInt() and 0xFF) shl 16) or
               ((b[off + 2].toInt() and 0xFF) shl 8) or
               (b[off + 3].toInt() and 0xFF)
    }

    private fun readLong(b: ByteArray, off: Int): Long {
        var result = 0L
        for (i in 0 until 8) {
            result = (result shl 8) or (b[off + i].toLong() and 0xFF)
        }
        return result
    }

    private fun readFloat(b: ByteArray, off: Int): Float =
        java.lang.Float.intBitsToFloat(readInt(b, off))
}