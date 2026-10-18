package com.ztune.libretune.core.protocol.ms

import com.ztune.libretune.core.ecu.EcuTransport
import com.ztune.libretune.core.util.runCatchingCancellable
import com.ztune.libretune.core.protocol.ms.AdaptiveTiming
import com.ztune.libretune.core.protocol.ms.calculateInterFrameDelay
import com.ztune.libretune.core.protocol.ms.calculateWriteChunkSize
import com.ztune.libretune.core.protocol.ms.DirtyPageTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Shared empty array to avoid repeated zero-length allocations. */
private val EMPTY_BYTE_ARRAY = ByteArray(0)

/**
 * Protocol mode selection for [MsProtocolClient].
 *
 * - [RAW]: MegaSquirt / Speeduino raw serial protocol. No framing, no CRC,
 *   no byte-stuffing. Commands are sent as plain bytes. Big-endian offset
 *   encoding (MS MCU MC9S12 is big-endian). This is what real MS1/MS2/MS3
 *   and Speeduino firmware expects over a direct serial connection.
 *
 * - [FRAMED]: TunerStudio Binary Protocol (TS-BP) with 0x5A header,
 *   0x7D byte-stuffing, and CRC-16/CCITT integrity checks. Little-endian
 *   offset encoding. Used by rusEFI and FOME (rusEFI fork). This is also
 *   the protocol TunerStudio uses to communicate with MS ECUs when the
 *   "TS over serial" mode is enabled.
 */
enum class ProtocolMode {
    RAW,
    FRAMED
}

/**
 * MegaSquirt serial protocol client.
 *
 * Supports two protocol modes via [ProtocolMode]:
 * - [ProtocolMode.RAW]: MS/Speeduino raw serial (no framing, BE offset)
 * - [ProtocolMode.FRAMED]: TS-BP with 0x5A + CRC-16 (LE offset)
 *
 * All public suspend methods are **serialized** through an internal [Mutex] so that
 * concurrent callers never interleave frames on the wire.
 *
 * @param transport The transport channel (USB serial, Bluetooth, TCP, etc.)
 * @param mode The protocol mode. Defaults to [ProtocolMode.RAW] which is correct
 *   for MegaSquirt and Speeduino. Use [ProtocolMode.FRAMED] for FOME/rusEFI
 *   TS-BP communication.
 */
class MsProtocolClient(
    private val transport: EcuTransport,
    private val mode: ProtocolMode = ProtocolMode.RAW
) {

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    /** Accumulated raw bytes received from the transport, waiting to be deframed. */
    private val receiveBuffer = mutableListOf<Byte>()

    /** Serialises all outbound + inbound I/O so frames never interleave. */
    private val ioMutex = Mutex()

    /** Adaptive timing — rolling RTT average for dynamic timeout. */
    val adaptiveTiming = AdaptiveTiming()

    /** Dirty page tracker for multi-page burn. */
    val dirtyPageTracker = DirtyPageTracker()

    /** Baud rate for inter-frame delay calculation (0 = use default settle). */
    var baudRate: Int = 115200

    /** Blocking factor from INI (default 208). Write chunks = bf - 8. */
    var blockingFactor: Int = MsConstants.DEFAULT_BLOCK_SIZE

    // ------------------------------------------------------------------
    // Companion – command bytes
    // ------------------------------------------------------------------

    companion object {
        const val CMD_QUERY_SIGNATURE: Byte = MsConstants.CMD_QUERY_SIGNATURE
        const val CMD_BLOCK_READ: Byte = MsConstants.CMD_BLOCK_READ
        const val CMD_BLOCK_WRITE: Byte = MsConstants.CMD_BLOCK_WRITE
        const val CMD_BURN: Byte = MsConstants.CMD_BURN
        const val CMD_REALTIME: Byte = MsConstants.CMD_REALTIME
        const val CMD_SINGLE_READ: Byte = MsConstants.CMD_SINGLE_READ
        const val CMD_COMM_RESET: Byte = MsConstants.CMD_COMM_RESET
        const val HEADER: Byte = MsConstants.HEADER
        const val ESCAPE: Byte = MsConstants.ESCAPE
        const val TIMEOUT_MS: Long = MsConstants.DEFAULT_TIMEOUT_MS
    }

    // ==================================================================
    // Public API
    // ==================================================================

    /**
     * Query the ECU for its signature string (e.g. "Megasquirt-Extra 3.1.x").
     *
     * Sends the 'Q' command and returns the ASCII signature with trailing
     * NUL bytes stripped.
     *
     * In RAW mode: sends raw 'Q' byte, reads [MsConstants.SIGNATURE_RESPONSE_LENGTH]
     * bytes (64), strips trailing NULs.
     * In FRAMED mode: sends framed 'Q' command, deframes response, strips NULs.
     */
    suspend fun querySignature(): Result<String> = ioMutex.withLock {
        runCatchingCancellable {
            val response = when (mode) {
                ProtocolMode.RAW -> sendAndReceiveRaw(
                    CMD_QUERY_SIGNATURE,
                    EMPTY_BYTE_ARRAY,
                    TIMEOUT_MS,
                    MsConstants.SIGNATURE_RESPONSE_LENGTH
                )
                ProtocolMode.FRAMED -> sendAndReceiveFramed(
                    CMD_QUERY_SIGNATURE,
                    EMPTY_BYTE_ARRAY,
                    TIMEOUT_MS
                )
            }
            val trimmed = response.trimTrailingNulls()
            String(trimmed, Charsets.US_ASCII)
        }
    }

    /**
     * Read a block of ECU memory.
     *
     * Block-read format: `r` + page(1) + offset(2) + count(1).
     * - RAW mode: offset is big-endian (MS MCU standard)
     * - FRAMED mode: offset is little-endian (TS-BP standard)
     *
     * The response payload contains exactly [length] data bytes.
     *
     * v2.0.0 (audit round-2, finding R2-C2) — SEBELUMNYA permintaan >
     * [MsConstants.MAX_BLOCK_SIZE] di-CLAMP diam-diam
     * (`length.coerceIn(0, MAX_BLOCK_SIZE)`): pemanggil yang meminta 16 KB
     * hanya menerima 256 byte TANPA error, sehingga TuneManager.loadFromEcu
     * membangun tune dari gambar memori yang terpotong dan jalur verify
     * burnPageSafely false-pass (hanya 256 byte pertama yang dibandingkan).
     * SEKARANG: fail-loud — pakai [readBlockChunked] untuk transfer > 256 B.
     *
     * @param page  ECU memory page index.
     * @param offset Byte offset within the page.
     * @param length Number of bytes to read (0..[MsConstants.MAX_BLOCK_SIZE]).
     * @param commandOverride If non-null, use this command byte instead of 'r'.
     */
    suspend fun readBlock(
        page: Int,
        offset: Int,
        length: Int,
        commandOverride: Byte? = null
    ): Result<ByteArray> = ioMutex.withLock {
        runCatchingCancellable {
            require(length >= 0) { "readBlock: negative length $length" }
            require(length <= MsConstants.MAX_BLOCK_SIZE) {
                "readBlock: length $length melebihi MAX_BLOCK_SIZE ${MsConstants.MAX_BLOCK_SIZE} — " +
                    "gunakan readBlockChunked() (silent clamp telah dihapus, audit round-2 R2-C2)"
            }
            val cmd = commandOverride ?: CMD_BLOCK_READ
            val clampedLength = length

            val payload = when (mode) {
                ProtocolMode.RAW -> byteArrayOf(
                    page.toByte(),
                    // Big-endian offset (MS MCU MC9S12 is big-endian)
                    ((offset ushr 8) and 0xFF).toByte(),
                    (offset and 0xFF).toByte(),
                    clampedLength.toByte()
                )
                ProtocolMode.FRAMED -> byteArrayOf(
                    page.toByte(),
                    // Little-endian offset (TS-BP standard)
                    (offset and 0xFF).toByte(),
                    ((offset ushr 8) and 0xFF).toByte(),
                    clampedLength.toByte()
                )
            }

            val response = when (mode) {
                ProtocolMode.RAW -> sendAndReceiveRaw(cmd, payload, TIMEOUT_MS, clampedLength)
                ProtocolMode.FRAMED -> sendAndReceiveFramed(cmd, payload, TIMEOUT_MS)
            }
            if (response.size < clampedLength) {
                throw ProtocolException(
                    "readBlock: expected $clampedLength bytes but got ${response.size}"
                )
            }
            response.copyOfRange(0, clampedLength)
        }
    }

    /**
     * Read an arbitrary-length block by chunking at [MsConstants.MAX_BLOCK_SIZE]
     * (v2.0.0, audit round-2 R2-C2). Setiap chunk diverifikasi panjangnya
     * persis — short read = exception, bukan byte 0x00 senyap.
     *
     * Ported pattern from LibreTune's read_memory().
     */
    suspend fun readBlockChunked(
        page: Int,
        offset: Int,
        length: Int
    ): Result<ByteArray> = ioMutex.withLock {
        runCatchingCancellable {
            require(length >= 0) { "readBlockChunked: negative length $length" }
            val out = ByteArray(length)
            var pos = 0
            while (pos < length) {
                val chunkLen = minOf(MsConstants.MAX_BLOCK_SIZE, length - pos)
                val chunk = readBlockInternal(page, offset + pos, chunkLen)
                    .getOrThrow() // propagate as exception → Result.failure
                if (chunk.size != chunkLen) {
                    throw ProtocolException(
                        "readBlockChunked: short read at offset ${offset + pos} — " +
                            "expected $chunkLen bytes, got ${chunk.size}"
                    )
                }
                System.arraycopy(chunk, 0, out, pos, chunkLen)
                pos += chunkLen
            }
            out
        }
    }

    /**
     * Write a block of data to ECU memory.
     *
     * Block-write format: `w` + page(1) + offset(2) + data…
     * - RAW mode: offset is big-endian
     * - FRAMED mode: offset is little-endian
     *
     * The response is a single-byte acknowledgment.
     *
     * @param page  ECU memory page index.
     * @param offset Byte offset within the page.
     * @param data   Bytes to write.
     * @param commandOverride If non-null, use this command byte instead of 'w'.
     */
    suspend fun writeBlock(
        page: Int,
        offset: Int,
        data: ByteArray,
        commandOverride: Byte? = null
    ): Result<Unit> = ioMutex.withLock {
        runCatchingCancellable {
            // v2.0.0 (audit round-2, R2-C3): write > MAX_BLOCK_SIZE pada MS/
            // Speeduino menyebabkan ECU RX buffer membuang ekor data secara
            // senyap lalu tetap ACK — korupsi flash tanpa jejak. Fail-loud:
            // gunakan writeBlockChunked() untuk transfer besar.
            require(data.size <= MsConstants.MAX_BLOCK_SIZE) {
                "writeBlock: ${data.size}B melebihi MAX_BLOCK_SIZE ${MsConstants.MAX_BLOCK_SIZE} — " +
                    "gunakan writeBlockChunked() (audit round-2 R2-C3)"
            }
            val cmd = commandOverride ?: CMD_BLOCK_WRITE
            val header = when (mode) {
                ProtocolMode.RAW -> byteArrayOf(
                    page.toByte(),
                    // Big-endian offset
                    ((offset ushr 8) and 0xFF).toByte(),
                    (offset and 0xFF).toByte()
                )
                ProtocolMode.FRAMED -> byteArrayOf(
                    page.toByte(),
                    // Little-endian offset
                    (offset and 0xFF).toByte(),
                    ((offset ushr 8) and 0xFF).toByte()
                )
            }
            val payload = ByteArray(header.size + data.size)
            System.arraycopy(header, 0, payload, 0, header.size)
            System.arraycopy(data, 0, payload, header.size, data.size)

            val response = when (mode) {
                ProtocolMode.RAW -> sendAndReceiveRaw(cmd, payload, TIMEOUT_MS, 1)
                ProtocolMode.FRAMED -> sendAndReceiveFramed(cmd, payload, TIMEOUT_MS)
            }
            val ack = response.firstOrNull()
                ?: throw ProtocolException("writeBlock: empty response")
            if (ack != MsConstants.RESPONSE_SUCCESS) {
                throw ProtocolException(
                    "writeBlock: ECU returned 0x${ack.toUByte().toString(16)} (expected 0x30)"
                )
            }
        }
    }

    /**
     * Internal single-shot read used by [readBlockChunked] while the ioMutex
     * is already held (mutex is NOT reentrant — public readBlock would
     * deadlock if called from readBlockChunked's withLock block).
     */
    private suspend fun readBlockInternal(
        page: Int,
        offset: Int,
        length: Int
    ): Result<ByteArray> {
        require(length in 0..MsConstants.MAX_BLOCK_SIZE) {
            "readBlockInternal: invalid length $length"
        }
        val cmd = CMD_BLOCK_READ
        val payload = when (mode) {
            ProtocolMode.RAW -> byteArrayOf(
                page.toByte(),
                ((offset ushr 8) and 0xFF).toByte(),
                (offset and 0xFF).toByte(),
                length.toByte()
            )
            ProtocolMode.FRAMED -> byteArrayOf(
                page.toByte(),
                (offset and 0xFF).toByte(),
                ((offset ushr 8) and 0xFF).toByte(),
                length.toByte()
            )
        }
        return when (mode) {
            ProtocolMode.RAW -> runCatchingCancellable {
                val response = sendAndReceiveRaw(cmd, payload, TIMEOUT_MS, length)
                if (response.size < length) {
                    throw ProtocolException(
                        "readBlock: expected $length bytes but got ${response.size}"
                    )
                }
                response.copyOfRange(0, length)
            }
            ProtocolMode.FRAMED -> runCatchingCancellable {
                val response = sendAndReceiveFramed(cmd, payload, TIMEOUT_MS)
                if (response.size < length) {
                    throw ProtocolException(
                        "readBlock: expected $length bytes but got ${response.size}"
                    )
                }
                response.copyOfRange(0, length)
            }
        }
    }

    /**
     * Burn (flash) the current page to non-volatile ECU memory.
     *
     * Sends 'B' and waits for an acknowledgment.  Some MS firmware variants
     * require a modified burn command with page + offset; that variant is
     * supported via [commandOverride].
     *
     * @param commandOverride If non-null, use this command byte instead of 'B'.
     * @param burnTimeout Custom timeout in ms (defaults to [MsConstants.BURN_TIMEOUT_MS]).
     */
    suspend fun burnPage(
        commandOverride: Byte? = null,
        burnTimeout: Long = MsConstants.BURN_TIMEOUT_MS
    ): Result<Unit> = ioMutex.withLock {
        runCatchingCancellable {
            val cmd = commandOverride ?: CMD_BURN
            val response = when (mode) {
                ProtocolMode.RAW -> sendAndReceiveRaw(cmd, EMPTY_BYTE_ARRAY, burnTimeout, 1)
                ProtocolMode.FRAMED -> sendAndReceiveFramed(cmd, EMPTY_BYTE_ARRAY, burnTimeout)
            }
            val ack = response.firstOrNull()
                ?: throw ProtocolException("burnPage: empty response")
            if (ack != MsConstants.RESPONSE_SUCCESS) {
                throw ProtocolException(
                    "burnPage: ECU returned 0x${ack.toUByte().toString(16)} (expected 0x30)"
                )
            }
        }
    }

    /**
     * Send a raw command and return the response payload.
     *
     * In RAW mode: sends `command + data` as raw bytes, reads [expectedResponseLength]
     * bytes (or reads until timeout if expectedResponseLength is 0).
     * In FRAMED mode: sends framed command, deframes response.
     *
     * @param command  Command byte.
     * @param data     Payload bytes (may be empty).
     * @param timeout  Operation timeout in milliseconds.
     * @param expectedResponseLength  (RAW mode only) Expected response byte count.
     *   If > 0, read until exactly this many bytes arrive. If 0, read until timeout.
     */
    suspend fun sendCommand(
        command: Byte,
        data: ByteArray = byteArrayOf(),
        timeout: Long = TIMEOUT_MS,
        expectedResponseLength: Int = 0
    ): Result<ByteArray> = ioMutex.withLock {
        runCatchingCancellable {
            when (mode) {
                ProtocolMode.RAW -> sendAndReceiveRaw(command, data, timeout, expectedResponseLength)
                ProtocolMode.FRAMED -> sendAndReceiveFramed(command, data, timeout)
            }
        }
    }

    /**
     * Expand and send a TunerStudio-style controller command template.
     *
     * Substitution tokens in [commandTemplate]:
     * - `%2i` → page as 2-byte (endianness depends on mode)
     * - `%2o` → offset as 2-byte (endianness depends on mode)
     * - `%2c` → count as 2-byte (endianness depends on mode)
     * - `%v`  → value as 1 byte
     * - `{varName}` → byte value from [pcVariables]
     *
     * @param commandTemplate Template string, e.g. `"r%2i%2o%2c"` or `"B%2i%2o"`.
     * @param pcVariables    Map of variable name → byte value for `{var}` substitution.
     * @param value          Integer value for `%v` substitution.
     */
    suspend fun sendControllerCommand(
        commandTemplate: String,
        pcVariables: Map<String, Byte> = emptyMap(),
        value: Int = 0
    ): Result<ByteArray> = ioMutex.withLock {
        runCatchingCancellable {
            val expanded = expandCommandTemplate(commandTemplate, pcVariables, value)
            if (expanded.isEmpty()) {
                throw ProtocolException("sendControllerCommand: template expanded to empty bytes")
            }
            val cmdByte = expanded[0]
            val payload = if (expanded.size > 1) expanded.copyOfRange(1, expanded.size) else EMPTY_BYTE_ARRAY
            when (mode) {
                ProtocolMode.RAW -> sendAndReceiveRaw(cmdByte, payload, TIMEOUT_MS, 0)
                ProtocolMode.FRAMED -> sendAndReceiveFramed(cmdByte, payload, TIMEOUT_MS)
            }
        }
    }

    /**
     * Reset the ECU communication state.
     *
     * Sends 'c' (comm reset) and clears the internal receive buffer.
     */
    suspend fun commReset(): Result<Unit> = ioMutex.withLock {
        runCatchingCancellable {
            clearBuffer()
            when (mode) {
                ProtocolMode.RAW -> {
                    // RAW mode: send raw 'c' byte, no response expected
                    transport.send(byteArrayOf(CMD_COMM_RESET))
                    clearBuffer()
                }
                ProtocolMode.FRAMED -> {
                    val framed = MsEnvelope.frame(CMD_COMM_RESET, EMPTY_BYTE_ARRAY)
                    transport.send(framed)
                    clearBuffer()
                }
            }
        }
    }

    /**
     * Feed raw received bytes through the deframer (FRAMED mode only).
     *
     * This is useful when bytes arrive from a streaming source outside
     * of a normal request/response cycle (e.g. a background data logger).
     *
     * @param data  Raw bytes received from the transport.
     * @return Zero or more complete, CRC-valid frames.
     */
    fun processIncomingData(data: ByteArray): List<FrameResult> {
        synchronized(receiveBuffer) {
            receiveBuffer.addAll(data.toList())
            return extractFrames()
        }
    }

    /** Discard all bytes currently in the receive buffer. */
    fun clearBuffer() {
        synchronized(receiveBuffer) {
            receiveBuffer.clear()
        }
    }

    /** Close the transport connection. */
    suspend fun close() {
        ioMutex.withLock {
            clearBuffer()
            transport.disconnect()
        }
    }

    // ==================================================================
    // Enhanced write methods (ported from LibreTune's connection.rs)
    // ==================================================================

    /**
     * Write a large block of data in chunks of (blockingFactor - 8) bytes.
     *
     * Ported from LibreTune's write_memory().
     * Without chunking, large writes (e.g. 263-byte VE table) get their
     * tail dropped by the ECU's RX buffer.
     */
    suspend fun writeBlockChunked(
        page: Int,
        offset: Int,
        data: ByteArray
    ): Result<Unit> = ioMutex.withLock {
        runCatchingCancellable {
            writeBlockChunkedInternal(page, offset, data)
        }
    }

    /**
     * Chunked write loop WITHOUT taking [ioMutex] — for callers that already
     * hold the lock (writeBlockVerified). v2.0.0: extracted because
     * kotlinx.coroutines Mutex is NOT reentrant; the previous
     * writeBlockVerified → writeBlockChunked nesting would deadlock
     * deterministically on the first call (audit round-2, R2-DEADLOCK).
     */
    private suspend fun writeBlockChunkedInternal(
        page: Int,
        offset: Int,
        data: ByteArray
    ) {
        val chunkSize = calculateWriteChunkSize(blockingFactor)
        var pos = 0
        while (pos < data.size) {
            val len = minOf(chunkSize, data.size - pos)
            val chunk = data.copyOfRange(pos, pos + len)

            val cmd = CMD_BLOCK_WRITE
            val header = when (mode) {
                ProtocolMode.RAW -> byteArrayOf(
                    page.toByte(),
                    ((offset + pos ushr 8) and 0xFF).toByte(),
                    ((offset + pos) and 0xFF).toByte()
                )
                ProtocolMode.FRAMED -> byteArrayOf(
                    page.toByte(),
                    ((offset + pos) and 0xFF).toByte(),
                    ((offset + pos ushr 8) and 0xFF).toByte()
                )
            }
            val payload = ByteArray(header.size + chunk.size)
            System.arraycopy(header, 0, payload, 0, header.size)
            System.arraycopy(chunk, 0, payload, header.size, chunk.size)

            val response = when (mode) {
                ProtocolMode.RAW -> sendAndReceiveRaw(cmd, payload, TIMEOUT_MS, 1)
                ProtocolMode.FRAMED -> sendAndReceiveFramed(cmd, payload, TIMEOUT_MS)
            }
            val ack = response.firstOrNull()
                ?: throw ProtocolException("writeBlockChunked: empty response at offset ${offset + pos}")
            if (ack != MsConstants.RESPONSE_SUCCESS) {
                throw ProtocolException("writeBlockChunked: NAK 0x${ack.toUByte().toString(16)} at offset ${offset + pos}")
            }
            pos += len
        }

        // Track dirty page for multi-page burn
        dirtyPageTracker.markDirty(page)
    }

    /**
     * Write data and verify by reading back (chunked both directions).
     *
     * Ported from LibreTune's write_memory_verified().
     * If verification fails, the original data is NOT restored (caller must handle).
     *
     * v2.0.0 (audit round-2):
     *  - R2-DEADLOCK: sebelumnya memanggil writeBlockChunked() di DALAM
     *    ioMutex.withLock — Mutex Kotlin non-reentrant → deadlock deterministik
     *    pada panggilan pertama. Sekarang memakai internal loop tanpa lock ganda.
     *  - R2-C2: read-back sebelumnya memakai readBlock() yang ter-clamp 256 B
     *    → false-pass untuk data > 256 B. Sekarang chunked exact-length.
     *
     * @return true if write verified successfully, false if read-back mismatch
     */
    suspend fun writeBlockVerified(
        page: Int,
        offset: Int,
        data: ByteArray
    ): Boolean = ioMutex.withLock {
        // Write (chunked, already holding ioMutex)
        val writeResult = runCatchingCancellable { writeBlockChunkedInternal(page, offset, data) }
        if (writeResult.isFailure) return@withLock false

        // Read back (chunked, exact-length)
        val readBack = runCatchingCancellable {
            val out = ByteArray(data.size)
            var pos = 0
            while (pos < data.size) {
                val chunkLen = minOf(MsConstants.MAX_BLOCK_SIZE, data.size - pos)
                val chunk = readBlockInternal(page, offset + pos, chunkLen).getOrThrow()
                if (chunk.size != chunkLen) {
                    throw ProtocolException(
                        "writeBlockVerified: short read at offset ${offset + pos} — " +
                            "expected $chunkLen bytes, got ${chunk.size}"
                    )
                }
                System.arraycopy(chunk, 0, out, pos, chunkLen)
                pos += chunkLen
            }
            out
        }.getOrNull() ?: return@withLock false

        // Compare byte-for-byte
        readBack.contentEquals(data)
    }

    /**
     * Burn all dirty pages in ascending order.
     *
     * Ported from LibreTune's send_burn_command().
     * On Speeduino, one burn command per page is required.
     * This method burns ALL dirty pages tracked by dirtyPageTracker.
     */
    suspend fun burnAllDirtyPages(): Result<Unit> = ioMutex.withLock {
        runCatchingCancellable {
            val pages = dirtyPageTracker.pagesToBurn()
            if (pages.isEmpty()) return@runCatchingCancellable

            for (page in pages) {
                // Send burn command for this page
                val cmd = CMD_BURN
                val response = when (mode) {
                    ProtocolMode.RAW -> sendAndReceiveRaw(cmd, byteArrayOf(page.toByte()), MsConstants.BURN_TIMEOUT_MS, 1)
                    ProtocolMode.FRAMED -> sendAndReceiveFramed(cmd, byteArrayOf(page.toByte()), MsConstants.BURN_TIMEOUT_MS)
                }
                // Some ECUs don't ACK burn — ignore response
            }

            // Settle delay after burn (LibreTune: BURN_SETTLE_MS = 600ms)
            delay(600)
            dirtyPageTracker.clear()
        }
    }

    // ==================================================================
    // Internal: RAW mode send/receive
    // ==================================================================

    /**
     * RAW mode send and receive.
     *
     * Sends `command + data` as raw bytes (no framing, no CRC).
     * Reads response bytes until [expectedResponseLength] bytes are received
     * (if > 0) or until [timeout] elapses (if expectedResponseLength == 0).
     *
     * @param command  Command byte (sent as first byte).
     * @param data     Payload bytes (sent after command).
     * @param timeout  Total timeout in milliseconds.
     * @param expectedResponseLength  Expected response byte count. 0 = read until timeout.
     * @return Response bytes.
     */
    private suspend fun sendAndReceiveRaw(
        command: Byte,
        data: ByteArray,
        timeout: Long,
        expectedResponseLength: Int
    ): ByteArray {
        clearBuffer()

        // Build raw packet: command + data
        val packet = ByteArray(1 + data.size)
        packet[0] = command
        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, packet, 1, data.size)
        }

        // Inter-frame delay: wait wire-time + settle before sending
        val interFrameMs = calculateInterFrameDelay(packet.size, baudRate)
        if (interFrameMs > 0) delay(interFrameMs)

        val startTime = System.currentTimeMillis()
        transport.send(packet)

        // Use adaptive timeout if timeout is the default
        val effectiveTimeout = if (timeout == TIMEOUT_MS) adaptiveTiming.currentTimeoutMs() else timeout

        return try {
            withTimeout(effectiveTimeout) {
                if (expectedResponseLength > 0) {
                    val result = ByteArray(expectedResponseLength)
                    var totalRead = 0
                    while (totalRead < expectedResponseLength) {
                        val remaining = expectedResponseLength - totalRead
                        val chunk = tryRead(remaining.coerceAtMost(512))
                        if (chunk == null || chunk.isEmpty()) continue
                        val toCopy = minOf(chunk.size, remaining)
                        System.arraycopy(chunk, 0, result, totalRead, toCopy)
                        totalRead += toCopy
                    }
                    result
                } else {
                    val accumulated = mutableListOf<Byte>()
                    val deadline = System.currentTimeMillis() + effectiveTimeout
                    while (System.currentTimeMillis() < deadline) {
                        val chunk = tryRead(512)
                        if (chunk == null || chunk.isEmpty()) {
                            if (accumulated.isNotEmpty()) break
                            continue
                        }
                        accumulated.addAll(chunk.toList())
                    }
                    accumulated.toByteArray()
                }
            }
        } catch (e: Exception) {
            adaptiveTiming.resetOnError()
            throw e
        } finally {
            // Record RTT for adaptive timing
            val rtt = System.currentTimeMillis() - startTime
            adaptiveTiming.recordResponse(rtt)
        }
    }

    // ==================================================================
    // Internal: FRAMED mode send/receive (TS-BP with 0x5A + CRC-16)
    // ==================================================================

    /**
     * FRAMED mode send and receive.
     *
     * Frames the command + data with 0x5A header + CRC-16, sends it,
     * then reads and deframes the response.
     */
    private suspend fun sendAndReceiveFramed(
        command: Byte,
        data: ByteArray,
        timeout: Long
    ): ByteArray {
        clearBuffer()

        val framed = MsEnvelope.frame(command, data)

        // Inter-frame delay
        val interFrameMs = calculateInterFrameDelay(framed.size, baudRate)
        if (interFrameMs > 0) delay(interFrameMs)

        val startTime = System.currentTimeMillis()
        transport.send(framed)

        val effectiveTimeout = if (timeout == TIMEOUT_MS) adaptiveTiming.currentTimeoutMs() else timeout

        return try {
            withTimeout(effectiveTimeout) {
                val chunkSize = 512
                while (true) {
                    val chunk = tryRead(chunkSize)
                    if (chunk == null || chunk.isEmpty()) continue

                    val frames: List<FrameResult>
                    synchronized(receiveBuffer) {
                        receiveBuffer.addAll(chunk.toList())
                        frames = extractFrames()
                    }

                    for (frame in frames) {
                        if (frame.command == command) {
                            return@withTimeout frame.payload
                        }
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                EMPTY_BYTE_ARRAY
            }
        } catch (e: Exception) {
            adaptiveTiming.resetOnError()
            throw e
        } finally {
            val rtt = System.currentTimeMillis() - startTime
            adaptiveTiming.recordResponse(rtt)
        }
    }

    // ==================================================================
    // Internal helpers
    // ==================================================================

    /**
     * Try to read [expectedLength] bytes from the transport.
     *
     * Returns `null` if the read times out or throws, in which case the
     * caller may retry or give up based on its own timeout wrapper.
     */
    private suspend fun tryRead(expectedLength: Int): ByteArray? {
        return withTimeoutOrNull(200L) {
            transport.receive(expectedLength)
        }
    }

    /**
     * Extract all complete frames currently in [receiveBuffer] (FRAMED mode only).
     *
     * **Must** be called while holding `synchronized(receiveBuffer)`.  For each
     * successfully deframed packet the consumed bytes are removed from the buffer
     * head.  Leading garbage bytes (before any 0x5A header) are also stripped.
     *
     * @return List of deframed results (may be empty).
     */
    private fun extractFrames(): List<FrameResult> {
        val results = mutableListOf<FrameResult>()

        while (receiveBuffer.isNotEmpty()) {
            val bufArray = receiveBuffer.toByteArray()
            val frame = MsEnvelope.deframe(bufArray, 0, bufArray.size)

            if (frame != null) {
                results.add(frame)
                repeat(frame.totalBytesConsumed) { receiveBuffer.removeAt(0) }
            } else {
                while (receiveBuffer.isNotEmpty() && receiveBuffer[0] != HEADER) {
                    receiveBuffer.removeAt(0)
                }
                break
            }
        }

        return results
    }

    // ------------------------------------------------------------------
    // Command template expansion (TunerStudio compatibility)
    // ------------------------------------------------------------------

    /**
     * Expand a TunerStudio-style command template into raw bytes.
     *
     * Tokens:
     * - `{varName}` → 1 byte from [pcVariables]
     * - `%2i` → page as 2-byte (endianness depends on [mode])
     * - `%2o` → offset as 2-byte (endianness depends on [mode])
     * - `%2c` → count as 2-byte (endianness depends on [mode])
     * - `%v`  → value as 1 byte (low byte of [value])
     */
    private fun expandCommandTemplate(
        template: String,
        pcVariables: Map<String, Byte>,
        value: Int
    ): ByteArray {
        val out = ArrayList<Byte>(template.length + 16)
        var i = 0

        while (i < template.length) {
            if (i + 3 <= template.length && template.substring(i, i + 3) == "%2i") {
                val v = pcVariables["page"]?.toInt() ?: 0
                when (mode) {
                    ProtocolMode.RAW -> { // big-endian
                        out.add(((v ushr 8) and 0xFF).toByte())
                        out.add((v and 0xFF).toByte())
                    }
                    ProtocolMode.FRAMED -> { // little-endian
                        out.add((v and 0xFF).toByte())
                        out.add(((v ushr 8) and 0xFF).toByte())
                    }
                }
                i += 3
            } else if (i + 3 <= template.length && template.substring(i, i + 3) == "%2o") {
                val v = pcVariables["offset"]?.toInt() ?: 0
                when (mode) {
                    ProtocolMode.RAW -> {
                        out.add(((v ushr 8) and 0xFF).toByte())
                        out.add((v and 0xFF).toByte())
                    }
                    ProtocolMode.FRAMED -> {
                        out.add((v and 0xFF).toByte())
                        out.add(((v ushr 8) and 0xFF).toByte())
                    }
                }
                i += 3
            } else if (i + 3 <= template.length && template.substring(i, i + 3) == "%2c") {
                val v = pcVariables["count"]?.toInt() ?: 0
                when (mode) {
                    ProtocolMode.RAW -> {
                        out.add(((v ushr 8) and 0xFF).toByte())
                        out.add((v and 0xFF).toByte())
                    }
                    ProtocolMode.FRAMED -> {
                        out.add((v and 0xFF).toByte())
                        out.add(((v ushr 8) and 0xFF).toByte())
                    }
                }
                i += 3
            } else if (i + 2 <= template.length && template.substring(i, i + 2) == "%v") {
                out.add((value and 0xFF).toByte())
                i += 2
            } else if (template[i] == '{') {
                val closeIdx = template.indexOf('}', i)
                if (closeIdx > i) {
                    val varName = template.substring(i + 1, closeIdx)
                    val b = pcVariables[varName]
                        ?: throw ProtocolException(
                            "sendControllerCommand: undefined pcVariable \"$varName\""
                        )
                    out.add(b)
                    i = closeIdx + 1
                } else {
                    out.add('{'.code.toByte())
                    i++
                }
            } else {
                out.add(template[i].code.toByte())
                i++
            }
        }

        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Utility extensions
    // ------------------------------------------------------------------

    /** Strip trailing 0x00 bytes from a ByteArray. */
    private fun ByteArray.trimTrailingNulls(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1] == 0.toByte()) end--
        return if (end == size) this else copyOfRange(0, end)
    }

    /** Reusable empty byte array to avoid repeated allocations. */
    private fun emptyByteArray(): ByteArray = EMPTY_BYTE_ARRAY
}

// ----------------------------------------------------------------------
// Exception types
// ----------------------------------------------------------------------

/**
 * Thrown when a protocol-level error occurs (CRC mismatch, unexpected
 * response, malformed frame, etc.).
 */
class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)