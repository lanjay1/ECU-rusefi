package com.ztune.libretune.core.datalog

import com.ztune.libretune.core.realtime.RealtimeChannelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * P3: Datalog enhancements — sample rate limiter, discard counter, .mlg format.
 * Ported from LibreTune's datalog/recorder.rs.
 */
data class DatalogStats(
    val totalSamples: Long,
    val discardedSamples: Long,
    val malformedRows: Long,
    val effectiveRateHz: Double,
    val durationMs: Long
)

class SampleRateLimiter(private var rateHz: Int = 50) {
    private var lastSampleMs: Long = 0
    val minIntervalMs: Long get() = (1000.0 / rateHz).toLong()

    fun shouldRecord(nowMs: Long): Boolean {
        val elapsed = nowMs - lastSampleMs
        if (elapsed < minIntervalMs) return false
        lastSampleMs = nowMs
        return true
    }

    fun setRate(hz: Int) { rateHz = hz.coerceIn(1, 200) }
    fun getRate(): Int = rateHz
}

/**
 * MegaLogViewer (.mlg) binary format support.
 * Simplified — real .mlg has a complex binary header.
 */
object MlgFormat {
    fun isMlgFile(filename: String): Boolean = filename.lowercase().endsWith(".mlg")

    fun writeMlgHeader(channels: List<String>): ByteArray {
        // .mlg header: magic + version + channel count + channel names
        val sb = StringBuilder()
        sb.append("MLG2\n")
        sb.append("${channels.size}\n")
        channels.forEach { sb.append("$it\n") }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }
}

/**
 * P2: Multi-pass dependency resolution for computed channels.
 * Ported from LibreTune's realtime/evaluator.rs.
 *
 * Problem: if computed channel B references computed channel A,
 * and B is evaluated before A in iteration order, B gets 0.
 * Solution: up to 3 passes, breaking early when no value changes.
 */
object ComputedChannelResolver {
    /**
     * Resolve computed channels in multiple passes.
     *
     * @param directChannels Already-decoded direct channels
     * @param computedChannels Map of name → expression string
     * @param evaluator Function that evaluates an expression given current values
     * @return Updated map with all computed channels resolved
     */
    fun resolve(
        directChannels: Map<String, Double>,
        computedChannels: Map<String, String>,
        evaluator: (String, Map<String, Double>) -> Double
    ): Map<String, Double> {
        val result = directChannels.toMutableMap()
        val epsilon = 1e-7

        for (pass in 0 until 3) {
            var changed = false
            for ((name, expr) in computedChannels) {
                val oldValue = result[name] ?: 0.0
                val newValue = evaluator(expr, result)
                if (kotlin.math.abs(newValue - oldValue) > epsilon) {
                    result[name] = newValue
                    changed = true
                }
            }
            if (!changed) break
        }
        return result
    }
}
