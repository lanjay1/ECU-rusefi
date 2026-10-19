package com.ztune.libretune.core.protocol.ms

import com.ztune.libretune.core.ecu.EcuTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max
import kotlin.math.min

/**
 * P2: Protocol enhancements ported from LibreTune's connection.rs.
 *
 * Features:
 * - Adaptive timing (rolling RTT average × 2.5 multiplier)
 * - Inter-frame delay (wire-time at baud + settle)
 * - Blocking-factor chunking (bf - 8 bytes per frame)
 * - Envelope byte-order flip-retry
 * - Multi-page burn tracking
 * - Page CRC verification
 */

data class AdaptiveTiming(
    private val minMs: Long = 10L,
    private val maxMs: Long = 500L,
    private val multiplier: Double = 2.5,
    private val windowSize: Int = 20
) {
    private val rtts = ArrayDeque<Long>(windowSize)

    fun recordResponse(rttMs: Long) {
        rtts.addLast(rttMs)
        if (rtts.size > windowSize) rtts.removeFirst()
    }

    fun currentTimeoutMs(): Long {
        if (rtts.isEmpty()) return 1000L
        val avg = rtts.average().toLong()
        return (avg * multiplier).toLong().coerceIn(minMs, maxMs)
    }

    fun resetOnError() {
        if (rtts.isNotEmpty()) {
            val maxRtt = rtts.max()
            rtts.clear()
            rtts.addLast((maxRtt * 0.75).toLong())
        }
    }

    fun stats(): String = "avg=${if (rtts.isEmpty()) 0 else rtts.average().toInt()}ms timeout=${currentTimeoutMs()}ms samples=${rtts.size}"
}

/**
 * Calculate inter-frame delay based on baud rate and frame size.
 * Wire time = frame_size × 10 bits / baud_rate.
 * Plus settle delay from INI or default 30ms.
 */
fun calculateInterFrameDelay(frameSizeBytes: Int, baudRate: Int, settleMs: Long = 30L): Long {
    if (baudRate <= 0) return settleMs
    val wireTimeMs = (frameSizeBytes * 10.0 / baudRate * 1000).toLong()
    return max(wireTimeMs, settleMs)
}

/**
 * Calculate effective write chunk size.
 * LibreTune: effective_write_chunk = blockingFactor - 8
 */
fun calculateWriteChunkSize(blockingFactor: Int): Int {
    return max(1, blockingFactor - 8)
}

/**
 * Track dirty pages for multi-page burn.
 * Ported from LibreTune's dirty_pages: BTreeSet<u8>.
 */
class DirtyPageTracker {
    private val dirtyPages = sortedSetOf<Int>()

    fun markDirty(page: Int) { dirtyPages.add(page) }
    fun hasDirtyPages(): Boolean = dirtyPages.isNotEmpty()
    fun dirtyPagesList(): List<Int> = dirtyPages.toList()
    fun clear() { dirtyPages.clear() }

    /**
     * Burn all dirty pages in ascending order.
     * On Speeduino, one burn command per page is required.
     */
    fun pagesToBurn(): List<Int> = dirtyPages.toList()
}

/**
 * Envelope byte order for TS-BP protocol.
 * rusEFI = BigEndian, Speeduino = LittleEndian.
 * LibreTune tries declared order first, then flips after 450ms wait.
 */
enum class EnvelopeOrder { BIG_ENDIAN, LITTLE_ENDIAN }

data class HandshakeResult(
    val success: Boolean,
    val envelopeOrder: EnvelopeOrder? = null,
    val signature: String? = null,
    val error: String? = null
)

/**
 * CRC-16/CCITT-FALSE implementation.
 * init=0xFFFF, poly=0x1021, no final XOR.
 * This is the correct CRC for rusEFI TS-BP protocol.
 */
fun crc16Ccitt(data: ByteArray): Int {
    var crc = 0xFFFF
    val table = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) {
            c = if ((c and 0x8000) != 0) (c shl 1) xor 0x1021 else c shl 1
        }
        c and 0xFFFF
    }
    for (b in data) {
        crc = ((crc shl 8) xor table[((crc ushr 8) xor (b.toInt() and 0xFF)) and 0xFF]) and 0xFFFF
    }
    return crc
}

/**
 * CRC-32 implementation for page verification.
 * Ported from LibreTune's verify_page_crc.
 * Uses INI's crc32CheckCommand to ask ECU for its stored CRC.
 */
fun crc32(data: ByteArray): Int {
    val crc = java.util.zip.CRC32()
    crc.update(data)
    return crc.value.toInt()
}

/**
 * Permissive CRC fallback modes.
 * Ported from LibreTune's packet.rs — 8 scope/endianness combinations.
 */
enum class CrcScope {
    STRICT_A,          // payload only, declared order
    PERMISSIVE_B_BE,   // data-only, BE
    PERMISSIVE_B_LE,   // data-only, LE
    PERMISSIVE_C_BE,   // length+payload, BE
    PERMISSIVE_C_LE,   // length+payload, LE
    PERMISSIVE_D_BE,   // length+data, BE
    PERMISSIVE_D_LE,   // length+data, LE
}

data class PacketConfig(
    val permissiveCrc: Boolean = false,
    val envelopeOrder: EnvelopeOrder = EnvelopeOrder.BIG_ENDIAN
)

/**
 * Online AutoTune streaming support.
 * Processes one sample at a time instead of batch.
 */
class OnlineAutoTuneProcessor(
    private val lambdaDelayBuffer: com.ztune.libretune.core.autotune.LambdaDelayBuffer,
    private val ewma: com.ztune.libretune.core.autotune.VECorrectionEWMA,
    private val authority: com.ztune.libretune.core.autotune.AutoTuneAuthorityLimits,
    private val filters: com.ztune.libretune.core.autotune.AutoTuneFilters
) {
    data class OnlineResult(
        val accepted: Boolean,
        val rejectReason: com.ztune.libretune.core.autotune.AutoTuneRejectReason? = null,
        val cellRow: Int = -1,
        val cellCol: Int = -1,
        val proposedVe: Double = 0.0,
        val clampedVe: Double = 0.0,
        val beginningVe: Double = 0.0
    )

    /**
     * Process a single realtime sample for online AutoTune.
     *
     * @param sample The current realtime sample
     * @param rpmBins X-axis bins
     * @param loadBins Y-axis bins
     * @param veTable Current VE table (for beginning_value anchor)
     * @param configuredDelayMs Lambda delay (0 = auto RPM curve)
     * @return OnlineResult with acceptance status and proposed VE
     */
    fun processSample(
        sample: com.ztune.libretune.core.autotune.AutoTuneSample,
        rpmBins: List<Double>,
        loadBins: List<Double>,
        veTable: List<List<Double>>,
        configuredDelayMs: Long = 0L
    ): OnlineResult {
        // Add to buffer
        lambdaDelayBuffer.addSample(sample)

        // Classify
        val rejectReason = com.ztune.libretune.core.autotune.classifySample(sample, filters)
        if (rejectReason != null) {
            return OnlineResult(accepted = false, rejectReason = rejectReason)
        }

        // Find delayed sample
        val delayMs = com.ztune.libretune.core.autotune.getLambdaDelayMs(sample.rpm, configuredDelayMs.toDouble())
        val matchTolerance = (lambdaDelayBuffer.meanSampleGapMs() * 0.6).toLong().coerceAtLeast(50L)
        val delayed = lambdaDelayBuffer.findDelayedSample(sample.timestampMs, delayMs, matchTolerance)

        if (delayed == null) {
            return OnlineResult(accepted = false,
                rejectReason = com.ztune.libretune.core.autotune.AutoTuneRejectReason.NO_LAMBDA_DELAY_MATCH)
        }

        // Cell attribution from delayed sample's (rpm, load)
        val cellCol = findBinIndex(delayed.rpm, rpmBins)
        val cellRow = findBinIndex(delayed.load, loadBins)
        if (cellRow < 0 || cellCol < 0) {
            return OnlineResult(accepted = false, rejectReason = com.ztune.libretune.core.autotune.AutoTuneRejectReason.RPM_OUT_OF_RANGE)
        }

        // Anchor to reference VE table (not sample's veCurr) — LibreTune bug #1 fix
        val cellVe = veTable.getOrNull(cellRow)?.getOrNull(cellCol) ?: return OnlineResult(accepted = false)

        // Calculate required VE
        val requiredVe = com.ztune.libretune.core.autotune.calculateRequiredVe(
            cellVe, sample.afr, sample.targetAfr
        )

        // EWMA update
        ewma.addObservation(requiredVe)

        // Get proposed value with confidence ramp
        val proposed = ewma.proposedValue(cellVe)

        // Apply authority limits
        val clamped = authority.clamp(proposed, cellVe)

        return OnlineResult(
            accepted = true,
            cellRow = cellRow,
            cellCol = cellCol,
            proposedVe = proposed,
            clampedVe = clamped,
            beginningVe = cellVe
        )
    }

    private fun findBinIndex(value: Double, bins: List<Double>): Int {
        if (bins.isEmpty()) return -1
        var bestIdx = 0
        var bestDist = Double.MAX_VALUE
        for (i in bins.indices) {
            val d = kotlin.math.abs(bins[i] - value)
            if (d < bestDist) { bestDist = d; bestIdx = i }
        }
        return bestIdx
    }
}
