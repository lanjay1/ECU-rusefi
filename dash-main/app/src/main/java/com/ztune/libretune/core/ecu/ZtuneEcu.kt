package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.EcuType
import com.ztune.libretune.core.tune.ByteOrderWriter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * ZTune-ECU Motor 1-Silinder (ESP32-S3) — antarmuka ECU.
 *
 * Wire protocol: frame biner dengan payload JSON.
 *   0x55 0xAA | len(2B LE) | type(1B) | seq(1B) | payload(JSON) | crc16(2B LE)
 *   CRC-16/CCITT-FALSE atas payload saja.
 *
 * Perintah (sesuai firmware ZtuneProtocol.h):
 *   0x01 PING_INFO · 0x02 READ_TUNE · 0x03 WRITE_TUNE · 0x04 READ_REALTIME
 *   0x05 TOOTH_LOG · 0x06 DATALOG_CTL · 0x07 REBOOT_SAFE · 0x08 CALIBRATE_TDC
 *   0x09 KNOCK_DUMP · 0x0A QS_CALIBRATE · 0x0B BENCH_ACTUATOR
 *
 * Tune: JSON <-> "virtual page" 0 lewat [ZtuneTuneJsonBridge] (key = nama constant
 * di ztune.ini). Realtime: JSON singkat -> blob biner sesuai [OutputChannels]
 * lewat [realtimeJsonToBytes], supaya RealtimeDecoder bekerja tanpa diubah.
 *
 * Kontrak transport: receive() kembali segera setelah ada byte; array kosong =
 * belum ada data (bukan error). Timeout total per perintah dijaga di sini.
 */
class ZtuneEcu : EcuInterface {

    override val ecuType: EcuType = EcuType.ZTUNE

    override var definition: EcuDefinition? = null
        private set

    override var isConnected: Boolean = false
        private set

    /** Versi firmware hasil handshake PING_INFO ("ZTune MAJOR.MINOR.PATCH"). */
    var fwVersion: ZtuneProtocolVersion? = null
        private set

    /** false = sesi read-only karena minor firmware berbeda dari INI. */
    var isWriteAllowed: Boolean = false
        private set

    private var transport: EcuTransport? = null
    private val ioMutex = Mutex()

    private val _realtimeUpdates = MutableSharedFlow<RealtimeUpdate>(extraBufferCapacity = 64)
    override val realtimeUpdates: SharedFlow<RealtimeUpdate> = _realtimeUpdates.asSharedFlow()

    private var nextSeq: Int = 0

    // Buffer RX untuk parser frame. HANYA disentuh di dalam ioMutex.
    private val rxBuf = ByteArrayOutputStream()

    // Konstanta frame (harus sama dengan firmware)
    private val PREAMBLE0: Int = 0x55
    private val PREAMBLE1: Int = 0xAA
    private val CRC16_INIT: Int = 0xFFFF
    private val HEADER_LEN = 6        // 0x55 0xAA + len(2) + type(1) + seq(1)
    private val TAIL_LEN = 2          // crc16(2)
    private val MAX_PAYLOAD = 40960
    private val RECV_TIMEOUT_MS = 2000L
    private val LONG_TIMEOUT_MS = 6000L
    private val RECV_CHUNK = 4096

    // ------------------------------------------------------------------ connect
    override suspend fun connect(transport: EcuTransport, definition: EcuDefinition): Result<Unit> {
        this.transport = transport
        this.definition = definition
        return try {
            if (!transport.isConnected()) transport.connect()

            // Handshake dicoba 2 kali: respons pertama kadang hilang saat ESP32
            // baru menerima koneksi atau buffer TCP belum bersih.
            val infoResp = ioMutex.withLock {
                rxBuf.reset()
                sendCommand(CMD_PING_INFO, ByteArray(0))
                    ?: run {
                        rxBuf.reset()
                        sendCommand(CMD_PING_INFO, ByteArray(0))
                    }
            } ?: return Result.failure(
                ProtocolException("tidak ada respons PING_INFO (2 percobaan, ${RECV_TIMEOUT_MS} ms)")
            )

            val info = String(infoResp, Charsets.UTF_8)
            val fw = ZtuneProtocolVersion.parse(info)
                ?: return Result.failure(
                    ProtocolException("signature mismatch: respons tidak mengandung versi ZTune: $info")
                )
            val expected = ZtuneProtocolVersion.parse(definition.signature)
            if (expected == null || fw.major != expected.major) {
                fwVersion = fw
                transport.disconnect()
                return Result.failure(
                    ProtocolException(
                        "inkompatibel: firmware $fw vs app ${expected ?: definition.signature} — " +
                            "protocol major berbeda, koneksi ditolak"
                    )
                )
            }
            fwVersion = fw
            isWriteAllowed = (fw.minor == expected.minor)
            isConnected = true
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            isConnected = false
            Result.failure(e)
        }
    }

    override suspend fun disconnect() {
        isConnected = false
        // Tutup transport DULU (membuka blokir receive yang sedang menunggu),
        // baru bersihkan buffer di bawah ioMutex.
        runCatching { transport?.disconnect() }
        ioMutex.withLock { rxBuf.reset() }
    }

    override suspend fun querySignature(): Result<String> {
        return try {
            val resp = ioMutex.withLock { sendCommand(CMD_PING_INFO, ByteArray(0)) }
                ?: return Result.failure(ProtocolException("tidak ada respons"))
            val json = String(resp, Charsets.UTF_8)
            val match = Regex("\"fw\"\\s*:\\s*\"([^\"]+)\"").find(json)
            Result.success(match?.groupValues?.get(1) ?: json)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- baca/tulis blok (virtual page 0 <-> JSON tune) ----

    override suspend fun readBlock(page: Int, offset: Int, length: Int): Result<ByteArray> {
        if (page != 0) {
            return Result.failure(IllegalArgumentException("ZTune hanya punya 1 page (indeks 0), diminta page $page"))
        }
        val def = definition
            ?: return Result.failure(IllegalStateException("Belum ada EcuDefinition — connect() dulu"))
        return try {
            val tuneJsonBytes = ioMutex.withLock { sendCommand(CMD_READ_TUNE, ByteArray(0)) }
                ?: return Result.failure(ProtocolException("tidak ada respons READ_TUNE"))
            val json = JSONObject(String(tuneJsonBytes, Charsets.UTF_8))

            // Encode seluruh tune ke buffer sepanjang (offset+length), lalu potong
            // slice [offset, offset+length). Pemanggilan normal: offset=0 dan
            // length = ukuran page penuh (TuneManager.loadFromEcu).
            val bufferSize = offset + length
            val pageBytes = ZtuneTuneJsonBridge.jsonToPageBytes(def, page, bufferSize, json)
            val slice = pageBytes.copyOfRange(offset.coerceAtLeast(0), minOf(offset + length, pageBytes.size))
            Result.success(slice)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun writeBlock(page: Int, offset: Int, data: ByteArray): Result<Unit> {
        if (page != 0) {
            return Result.failure(IllegalArgumentException("ZTune hanya punya 1 page (indeks 0), diminta page $page"))
        }
        val def = definition
            ?: return Result.failure(IllegalStateException("Belum ada EcuDefinition — connect() dulu"))
        if (!isWriteAllowed) {
            return Result.failure(
                ProtocolException(
                    "WRITE diblokir: versi firmware ${fwVersion ?: "tidak dikenal"} tidak kompatibel " +
                        "dengan definisi aplikasi (${def.signature}). Baca dulu; jangan menulis."
                )
            )
        }
        return try {
            // Decode HANYA byte yang berubah menjadi JSON parsial, lalu kirim
            // sebagai WRITE_TUNE (partial update).
            val partialJson = ZtuneTuneJsonBridge.pageBytesToPartialJson(def, page, offset, data)
            val payload = partialJson.toString().toByteArray(Charsets.UTF_8)
            if (payload.size > MAX_PAYLOAD) {
                return Result.failure(
                    ProtocolException(
                        "WRITE_TUNE payload ${payload.size}B melebihi MAX_PAYLOAD $MAX_PAYLOAD B — " +
                            "kurangi ukuran window write"
                    )
                )
            }
            ioMutex.withLock { sendCommand(CMD_WRITE_TUNE, payload) }
                ?: return Result.failure(ProtocolException("tidak ada respons WRITE_TUNE"))
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun burnPage(page: Int): Result<Unit> {
        // ZTune auto-burn ke NVS saat WRITE_TUNE diterima (lihat CommsTask.h).
        return Result.success(Unit)
    }

    override suspend fun readRealtimeData(): Result<ByteArray> {
        return try {
            val resp = ioMutex.withLock { sendCommand(CMD_READ_REALTIME, ByteArray(0)) }
                ?: return Result.failure(ProtocolException("tidak ada respons READ_REALTIME"))
            val def = definition
                ?: return Result.failure(IllegalStateException("Belum ada EcuDefinition — connect() dulu"))
            val bytes = realtimeJsonToBytes(def, resp)
            _realtimeUpdates.tryEmit(RealtimeUpdate(rawData = bytes))
            Result.success(bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendControllerCommand(
        name: String,
        commandTemplate: String,
        value: Int
    ): Result<ByteArray> {
        return try {
            val (cmd, payload) = when {
                commandTemplate.contains("calibrateTdc") ->
                    CMD_CALIBRATE_TDC to "{\"mark\":\"tdc\"}".toByteArray()
                commandTemplate.contains("knockDump") ->
                    CMD_KNOCK_DUMP to ByteArray(0)
                commandTemplate.contains("qsCalibrate") ->
                    CMD_QS_CALIBRATE to "{\"calibrate\":\"baseline\"}".toByteArray()
                commandTemplate.contains("rebootSafe") ->
                    CMD_REBOOT_SAFE to ByteArray(0)
                commandTemplate.contains("datalogStart") ->
                    CMD_DATALOG_CTL to "{\"start\":1}".toByteArray()
                commandTemplate.contains("datalogStop") ->
                    CMD_DATALOG_CTL to "{\"stop\":1}".toByteArray()
                commandTemplate.contains("datalogDump") ->
                    CMD_DATALOG_CTL to "{\"dump\":1}".toByteArray()
                commandTemplate.contains("datalogClear") ->
                    CMD_DATALOG_CTL to "{\"clear\":1}".toByteArray()
                commandTemplate.contains("benchVva") ->
                    CMD_BENCH_ACTUATOR to "{\"act\":\"vva\",\"on\":${if (value != 0) 1 else 0}}".toByteArray()
                commandTemplate.contains("benchEwp") ->
                    CMD_BENCH_ACTUATOR to "{\"act\":\"ewp\",\"on\":${if (value != 0) 1 else 0}}".toByteArray()
                commandTemplate.contains("benchValvetronic") ->
                    CMD_BENCH_ACTUATOR to "{\"act\":\"valvetronic\",\"on\":${if (value != 0) 1 else 0}}".toByteArray()
                commandTemplate.contains("benchQsCut") ->
                    CMD_BENCH_ACTUATOR to "{\"act\":\"qs_cut\",\"ms\":$value}".toByteArray()
                commandTemplate.contains("toothLog") ->
                    CMD_TOOTH_LOG to ByteArray(0)
                else -> return Result.failure(IllegalArgumentException("perintah tidak dikenal: $commandTemplate"))
            }
            val resp = ioMutex.withLock { sendCommand(cmd, payload) }
                ?: return Result.failure(ProtocolException("tidak ada respons perintah $cmd"))
            Result.success(resp)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun startStreaming() {
        // ZTune mem-push realtime periodik sesuai telemRateHz. Tidak perlu start.
    }

    override suspend fun stopStreaming() {
        // ZTune tidak punya stop eksplisit.
    }

    override suspend fun commReset(): Result<Unit> {
        // Bersihkan buffer RX untuk resync parser. Di bawah ioMutex supaya tidak
        // menabrak parser yang sedang berjalan.
        ioMutex.withLock { rxBuf.reset() }
        return Result.success(Unit)
    }

    // ================================================================
    // Realtime: JSON firmware -> blob biner [OutputChannels]
    // ================================================================

    /**
     * Peta kunci JSON realtime firmware (singkat) -> nama channel di
     * [OutputChannels] ztune.ini. VERIFIKASI terhadap firmware, terutama:
     * vvMode, st, fl, o2t, tpsd, rev.
     */
    private val realtimeKeyMap: Map<String, String> = mapOf(
        "rpm" to "rpm", "tps" to "tps", "clt" to "clt", "iat" to "iat",
        "o2" to "o2Afr", "afrT" to "afrTarget", "pw" to "pulseWidth", "adv" to "advance",
        "dw" to "dwellMs", "idle" to "idleDuty", "vb" to "vbatt",
        "kn" to "knockDb", "knc" to "knockCount", "knr" to "knockRetard",
        "knf" to "knockFreqHz", "knthr" to "knockThreshold", "knflr" to "knockFloor",
        "qsTrig" to "qsTriggerCount", "qsCut" to "qsCutActive",
        "vva" to "vvaActive", "vvMode" to "vvActive",
        "ewp" to "ewpDuty", "cmp" to "cmpSynced",
        "tpsd" to "tpsdot", "o2t" to "o2Trim", "rev" to "revCount",
        "st" to "startState", "fl" to "flags"
    )

    /**
     * Ubah JSON realtime firmware menjadi blob biner sesuai layout [OutputChannels]
     * supaya RealtimeDecoder bekerja tanpa diubah. Nilai di luar range tipe
     * di-clamp (data live, bukan penulisan ke ECU).
     */
    private fun realtimeJsonToBytes(def: EcuDefinition, payload: ByteArray): ByteArray {
        val json = JSONObject(String(payload, Charsets.UTF_8))
        val channels = def.outputChannels.values.filter { it.expression == null }
        val size = channels.maxOfOrNull { it.offset + it.dataType.byteSize } ?: 0
        val out = ByteArray(size)
        for ((jsonKey, channelName) in realtimeKeyMap) {
            val ch = def.outputChannels[channelName] ?: continue
            if (!json.has(jsonKey) || json.isNull(jsonKey)) continue
            val v = when (val any = json.get(jsonKey)) {
                is Number -> any.toDouble()
                is Boolean -> if (any) 1.0 else 0.0
                else -> continue
            }
            if (ch.scale == 0.0 || v.isNaN() || v.isInfinite()) continue
            val raw = ((v - ch.translate) / ch.scale)
                .coerceIn(ch.dataType.rawMin, ch.dataType.rawMax)
            val writer = ByteOrderWriter(ch.dataType.byteSize, def.endianness)
            writer.writeValue(ch.dataType, raw)
            val enc = writer.toByteArray()
            if (ch.offset >= 0 && ch.offset + enc.size <= out.size) {
                System.arraycopy(enc, 0, out, ch.offset, enc.size)
            }
        }
        return out
    }

    private fun emitRealtime(payload: ByteArray) {
        val def = definition ?: return
        runCatching { realtimeJsonToBytes(def, payload) }
            .onSuccess { _realtimeUpdates.tryEmit(RealtimeUpdate(rawData = it)) }
    }

    // ================================================================
    // Internal: kirim frame perintah, tunggu respons dengan timeout
    // ================================================================

    private fun timeoutFor(type: Int): Long = when (type) {
        CMD_READ_TUNE, CMD_WRITE_TUNE, CMD_DATALOG_CTL, CMD_KNOCK_DUMP, CMD_TOOTH_LOG -> LONG_TIMEOUT_MS
        else -> RECV_TIMEOUT_MS
    }

    /**
     * Kirim satu frame perintah dan tunggu respons ber-seq sama.
     * Return: payload bila CRC valid, null bila timeout.
     *
     * CALLER WAJIB memegang ioMutex.
     */
    private suspend fun sendCommand(type: Int, payload: ByteArray): ByteArray? {
        val t = transport ?: throw IllegalStateException("not connected")
        val seq = (nextSeq++ and 0xFF)
        t.send(buildFrame(type.toByte(), seq.toByte(), payload))
        return withTimeoutOrNull(timeoutFor(type)) {
            receiveFrameMatching(seq, type)
        }
    }

    /**
     * Baca dari transport, akumulasi ke rxBuf, cari frame utuh ber-CRC valid.
     * Frame realtime yang di-push firmware TIDAK PERNAH dianggap jawaban perintah
     * lain (meski seq kebetulan sama); frame itu diteruskan ke realtimeUpdates.
     * Timeout total dijaga oleh withTimeoutOrNull di [sendCommand].
     */
    private suspend fun receiveFrameMatching(expectedSeq: Int, requestType: Int): ByteArray {
        val t = transport ?: throw IllegalStateException("not connected")
        while (true) {
            val parsed = tryParseFrame()
            if (parsed != null) {
                val (seq, type, payload) = parsed
                val isRealtimeFrame = type == CMD_READ_REALTIME.toByte()
                val isPush = isRealtimeFrame && requestType != CMD_READ_REALTIME
                if (!isPush && seq == expectedSeq.toByte()) return payload
                if (isRealtimeFrame) emitRealtime(payload)
                continue
            }

            val chunk = t.receive(RECV_CHUNK)
            if (chunk.isEmpty()) {
                // Belum ada data. Jeda singkat agar tidak memutar CPU bila
                // transport mengembalikan kosong seketika; batas waktu total
                // dijaga withTimeoutOrNull.
                delay(2)
                continue
            }
            rxBuf.write(chunk)
        }
    }

    /**
     * Cek apakah rxBuf berisi frame utuh + CRC valid.
     * Return (seq, type, payload), atau null bila belum utuh / CRC salah.
     */
    private fun tryParseFrame(): Triple<Byte, Byte, ByteArray>? {
        val buf = rxBuf.toByteArray()
        if (buf.size < HEADER_LEN) return null

        // Cari preamble 0x55 0xAA
        var idx = 0
        while (idx < buf.size - 1) {
            if ((buf[idx].toInt() and 0xFF) == PREAMBLE0 &&
                (buf[idx + 1].toInt() and 0xFF) == PREAMBLE1) break
            idx++
        }
        if (idx >= buf.size - 1) {
            // Preamble tidak ada — buang semua kecuali byte terakhir (bisa jadi preamble parsial)
            if (buf.size > 1) {
                rxBuf.reset()
                rxBuf.write(buf, buf.size - 1, 1)
            }
            return null
        }

        // Buang byte sebelum preamble
        if (idx > 0) {
            val remaining = buf.copyOfRange(idx, buf.size)
            rxBuf.reset()
            rxBuf.write(remaining)
        }

        val hdr = rxBuf.toByteArray()
        if (hdr.size < HEADER_LEN) return null

        val len = (hdr[2].toInt() and 0xFF) or ((hdr[3].toInt() and 0xFF) shl 8)
        if (len > MAX_PAYLOAD) {
            // Frame terlalu besar = korup; buang preamble dan cari lagi
            rxBuf.reset()
            rxBuf.write(hdr, 2, hdr.size - 2)
            return null
        }

        val totalLen = HEADER_LEN + len + TAIL_LEN
        if (hdr.size < totalLen) return null

        val type = hdr[4]
        val seq = hdr[5]
        val payload = hdr.copyOfRange(HEADER_LEN, HEADER_LEN + len)
        val crcLo = hdr[HEADER_LEN + len].toInt() and 0xFF
        val crcHi = hdr[HEADER_LEN + len + 1].toInt() and 0xFF
        val crcReceived = crcLo or (crcHi shl 8)

        if (crc16(payload) != crcReceived) {
            // CRC salah — buang preamble frame ini, cari berikutnya
            rxBuf.reset()
            rxBuf.write(hdr, 2, hdr.size - 2)
            return null
        }

        // Frame valid — buang dari buffer
        rxBuf.reset()
        if (hdr.size > totalLen) {
            rxBuf.write(hdr, totalLen, hdr.size - totalLen)
        }
        return Triple(seq, type, payload)
    }

    /** Bangun satu frame — `internal` untuk pengujian golden-vector. */
    internal fun buildFrame(type: Byte, seq: Byte, payload: ByteArray): ByteArray {
        val len = payload.size
        val crc = crc16(payload)
        val frame = ByteArray(HEADER_LEN + len + TAIL_LEN)
        frame[0] = PREAMBLE0.toByte()
        frame[1] = PREAMBLE1.toByte()
        frame[2] = (len and 0xFF).toByte()
        frame[3] = ((len shr 8) and 0xFF).toByte()
        frame[4] = type
        frame[5] = seq
        System.arraycopy(payload, 0, frame, HEADER_LEN, len)
        frame[HEADER_LEN + len] = (crc and 0xFF).toByte()
        frame[HEADER_LEN + len + 1] = ((crc shr 8) and 0xFF).toByte()
        return frame
    }

    /** CRC-16/CCITT-FALSE (init 0xFFFF, poly 0x1021, tanpa refleksi) atas payload. */
    internal fun crc16(data: ByteArray): Int {
        var crc = CRC16_INIT
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            for (k in 0 until 8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else (crc shl 1)
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    companion object {
        const val CMD_PING_INFO       = 0x01
        const val CMD_READ_TUNE       = 0x02
        const val CMD_WRITE_TUNE      = 0x03
        const val CMD_READ_REALTIME   = 0x04
        const val CMD_TOOTH_LOG       = 0x05
        const val CMD_DATALOG_CTL     = 0x06
        const val CMD_REBOOT_SAFE     = 0x07
        const val CMD_CALIBRATE_TDC   = 0x08
        const val CMD_KNOCK_DUMP      = 0x09
        const val CMD_QS_CALIBRATE    = 0x0A
        const val CMD_BENCH_ACTUATOR  = 0x0B
    }
}