package com.ztune.libretune.core.protocol

import java.util.zip.CRC32

/**
 * ZTune Protocol v2 (draf 2) — konstanta, frame, CRC, dan decoder stream.
 * Little-endian. Frame:
 *   55 AA | LEN u16 | TYPE u8 | SEQ u8 | PAYLOAD[LEN] | CRC16 u16
 *   CRC16 = CRC-16/CCITT-FALSE atas LEN, TYPE, SEQ, PAYLOAD.
 */
object ZtuneProtocol {
    const val PROTO_VERSION = 2

    const val PREAMBLE0 = 0x55
    const val PREAMBLE1 = 0xAA
    const val HEADER_LEN = 6          // 55 AA LEN(2) TYPE SEQ
    const val CRC_LEN = 2
    const val MAX_PAYLOAD = 1040
    const val PAGE_SIZE = 4096
    const val MAX_DATA = 1024         // data maksimum per READ/WRITE

    // ---- Request (0x01..0x7F). Respons sukses = request or 0x80 ----
    const val CMD_HELLO = 0x01
    const val CMD_READ = 0x02
    const val CMD_WRITE = 0x03
    const val CMD_CRC = 0x04
    const val CMD_COMMIT = 0x05
    const val CMD_REVERT = 0x06
    const val CMD_ARM = 0x07
    const val CMD_DISARM = 0x08
    const val CMD_RT_READ = 0x10
    const val CMD_RT_STREAM = 0x11
    const val CMD_ACTION = 0x20
    const val CMD_REBOOT = 0x7E

    const val RESPONSE_FLAG = 0x80
    const val TYPE_ERROR = 0xFF
    const val TYPE_PUSH_REALTIME = 0x90
    const val TYPE_PUSH_LOG = 0x91

    // ---- Kode error (payload TYPE_ERROR: [code u8][tipeAsal u8][teks UTF-8 opsional]) ----
    const val ERR_BAD_REQUEST = 1
    const val ERR_RANGE = 2
    const val ERR_NOT_ARMED = 3
    const val ERR_ENGINE_RUNNING = 4
    const val ERR_BUSY = 5
    const val ERR_CRC_MISMATCH = 6
    const val ERR_LAYOUT_MISMATCH = 7
    const val ERR_NVS_FAIL = 8
    const val ERR_UNSUPPORTED = 9
    const val ERR_LEASE_REQUIRED = 10

    // ---- ACTION id ----
    const val ACTION_VVA = 1
    const val ACTION_EWP = 2
    const val ACTION_VALVETRONIC = 3
    const val ACTION_QS_CUT = 4
    const val ACTION_INJECTOR = 5
    const val ACTION_COIL = 6
    const val ACTION_PUMP = 7
    const val ACTION_FAN = 8
    const val ACTION_CAL_TDC = 9
    const val ACTION_CAL_TPS_CLOSED = 10
    const val ACTION_CAL_TPS_WOT = 11
    const val ACTION_QS_BASELINE = 12
    const val ACTION_KNOCK_DUMP = 13

    fun errorName(code: Int): String = when (code) {
        ERR_BAD_REQUEST -> "BAD_REQUEST"
        ERR_RANGE -> "RANGE"
        ERR_NOT_ARMED -> "NOT_ARMED"
        ERR_ENGINE_RUNNING -> "ENGINE_RUNNING"
        ERR_BUSY -> "BUSY"
        ERR_CRC_MISMATCH -> "CRC_MISMATCH"
        ERR_LAYOUT_MISMATCH -> "LAYOUT_MISMATCH"
        ERR_NVS_FAIL -> "NVS_FAIL"
        ERR_UNSUPPORTED -> "UNSUPPORTED"
        ERR_LEASE_REQUIRED -> "LEASE_REQUIRED"
        else -> "ERR_$code"
    }
}

/** Satu frame yang sudah lolos CRC. */
@Suppress("ArrayInDataClass")
data class ZtuneFrame(val type: Int, val seq: Int, val payload: ByteArray) {
    val isError: Boolean get() = type == ZtuneProtocol.TYPE_ERROR
    val isPush: Boolean
        get() = type == ZtuneProtocol.TYPE_PUSH_REALTIME || type == ZtuneProtocol.TYPE_PUSH_LOG

    override fun toString(): String =
        "ZtuneFrame(type=0x${type.toString(16)}, seq=$seq, len=${payload.size})"
}

object ZtuneCrc {
    /** CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021, tanpa refleksi). */
    fun crc16(data: ByteArray, from: Int = 0, length: Int = data.size - from): Int {
        var crc = 0xFFFF
        for (i in from until from + length) {
            crc = crc xor ((data[i].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    /** CRC-32 IEEE (sama dengan zlib / java.util.zip.CRC32), hasil tak bertanda dalam Long. */
    fun crc32(data: ByteArray, from: Int = 0, length: Int = data.size - from): Long {
        val c = CRC32()
        c.update(data, from, length)
        return c.value
    }
}

object ZtuneFraming {
    /** Bangun satu frame siap kirim. */
    fun encode(type: Int, seq: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= ZtuneProtocol.MAX_PAYLOAD) {
            "payload ${payload.size} melebihi MAX_PAYLOAD ${ZtuneProtocol.MAX_PAYLOAD}"
        }
        val len = payload.size
        val out = ByteArray(ZtuneProtocol.HEADER_LEN + len + ZtuneProtocol.CRC_LEN)
        out[0] = ZtuneProtocol.PREAMBLE0.toByte()
        out[1] = ZtuneProtocol.PREAMBLE1.toByte()
        out[2] = (len and 0xFF).toByte()
        out[3] = ((len shr 8) and 0xFF).toByte()
        out[4] = type.toByte()
        out[5] = seq.toByte()
        System.arraycopy(payload, 0, out, ZtuneProtocol.HEADER_LEN, len)
        val crc = ZtuneCrc.crc16(out, 2, 4 + len)
        out[ZtuneProtocol.HEADER_LEN + len] = (crc and 0xFF).toByte()
        out[ZtuneProtocol.HEADER_LEN + len + 1] = ((crc shr 8) and 0xFF).toByte()
        return out
    }
}

/**
 * Decoder stream berstatus. Transport serial TIDAK menjamin satu read = satu frame:
 * frame bisa datang terpotong, bergabung, atau bercampur sampah. Decoder ini
 * menampung byte, mencari preamble, memeriksa panjang dan CRC, dan resync
 * (membuang 1 byte) saat data rusak.
 *
 * Tidak thread-safe: panggil dari satu coroutine/loop baca.
 *
 * @param idleResetMs bila buffer berisi frame parsial dan tidak ada byte baru
 *        selama ini, buffer dibuang (frame macet).
 */
class ZtuneFrameDecoder(private val idleResetMs: Long = 200L) {

    private var buf = ByteArray(4096)
    private var size = 0
    private var lastByteMs = 0L

    var crcErrors = 0L; private set
    var resyncs = 0L; private set

    fun reset() {
        size = 0
    }

    /** Masukkan byte baru; kembalikan semua frame lengkap yang valid, berurutan. */
    fun feed(data: ByteArray, nowMs: Long): List<ZtuneFrame> {
        if (size > 0 && nowMs - lastByteMs > idleResetMs) {
            size = 0
            resyncs++
        }
        if (data.isEmpty()) return emptyList()
        lastByteMs = nowMs

        if (size + data.size > MAX_BUFFER) {
            size = 0
            resyncs++
        }
        if (size + data.size > buf.size) {
            buf = buf.copyOf(maxOf(size + data.size, buf.size * 2))
        }
        System.arraycopy(data, 0, buf, size, data.size)
        size += data.size

        val frames = ArrayList<ZtuneFrame>()
        while (true) {
            // 1. Cari preamble 55 AA
            var i = 0
            while (i < size - 1 &&
                !((buf[i].toInt() and 0xFF) == ZtuneProtocol.PREAMBLE0 &&
                    (buf[i + 1].toInt() and 0xFF) == ZtuneProtocol.PREAMBLE1)
            ) i++

            if (i >= size - 1) {
                // Tidak ada preamble utuh; simpan byte terakhir bila bisa jadi awal preamble.
                val keep = if (size > 0 && (buf[size - 1].toInt() and 0xFF) == ZtuneProtocol.PREAMBLE0) 1 else 0
                if (keep == 1) buf[0] = buf[size - 1]
                if (size - keep > 0) resyncs++
                size = keep
                break
            }
            if (i > 0) {
                shift(i)
                resyncs++
            }
            if (size < ZtuneProtocol.HEADER_LEN) break

            // 2. Panjang
            val len = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
            if (len > ZtuneProtocol.MAX_PAYLOAD) {
                shift(1)
                resyncs++
                continue
            }
            val total = ZtuneProtocol.HEADER_LEN + len + ZtuneProtocol.CRC_LEN
            if (size < total) break    // frame belum utuh

            // 3. CRC
            val crcRx = (buf[ZtuneProtocol.HEADER_LEN + len].toInt() and 0xFF) or
                ((buf[ZtuneProtocol.HEADER_LEN + len + 1].toInt() and 0xFF) shl 8)
            val crcCalc = ZtuneCrc.crc16(buf, 2, 4 + len)
            if (crcRx != crcCalc) {
                crcErrors++
                shift(1)
                continue
            }

            // 4. Frame valid
            frames += ZtuneFrame(
                type = buf[4].toInt() and 0xFF,
                seq = buf[5].toInt() and 0xFF,
                payload = buf.copyOfRange(ZtuneProtocol.HEADER_LEN, ZtuneProtocol.HEADER_LEN + len)
            )
            shift(total)
        }
        return frames
    }

    private fun shift(n: Int) {
        System.arraycopy(buf, n, buf, 0, size - n)
        size -= n
    }

    private companion object {
        const val MAX_BUFFER = 65536
    }
}