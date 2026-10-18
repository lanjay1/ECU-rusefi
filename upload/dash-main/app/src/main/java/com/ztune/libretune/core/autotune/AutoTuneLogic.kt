package com.ztune.libretune.core.autotune

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.TableDefinition
import com.ztune.libretune.core.ini.types.TableRole
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * P0 Safety: Prevent AutoTune on non-VE tables.
 *
 * Ported from LibreTune's `autotune/mod.rs::fuel_tune_refusal`.
 * Without this, a user could "autotune" the ignition table, which
 * would multiply degrees of advance by AFR ratio — destroying the engine.
 */
object AutoTuneSafety {

    /**
     * Check if a table is safe to AutoTune.
     * @return null if safe, error message if not.
     */
    fun fuelTuneRefusal(definition: EcuDefinition, tableName: String): String? {
        val table = definition.getTableByNameOrMap(tableName) ?: return "Table '$tableName' not found in definition"
        return when (table.role) {
            TableRole.VE -> null
            TableRole.AFR_TARGET -> "Cannot AutoTune AFR target table '$tableName'. AutoTune is for VE tables only."
            TableRole.IGNITION -> "SAFETY: Cannot AutoTune ignition table '$tableName'. AutoTune adjusts VE based on AFR error — applying this to ignition advance would multiply degrees by AFR ratio, destroying the engine."
            TableRole.WARMUP_ENRICHMENT -> "Cannot AutoTune warmup enrichment table '$tableName'. AutoTune is for VE tables only."
            TableRole.OTHER -> "Table '$tableName' is not a VE table. AutoTune is for VE tables only."
        }
    }

    /**
     * Find all VE tables in the definition that are safe to AutoTune.
     */
    fun fuelTunableTables(definition: EcuDefinition): List<String> {
        return definition.tables.values
            .filter { it.role == TableRole.VE }
            .map { it.name }
            .sorted()
    }
}

/**
 * P0 Safety: VE-Analyze filter chain.
 *
 * Ported from LibreTune's `autotune/mod.rs::passes_filters` + `classify`.
 * Without these filters, AutoTune will corrupt the VE table on:
 * - Lift-off (DFCO): wideband reads full lean → commands max enrichment
 * - Cold start: CLT < 60°C → enrichment active → AFR meaningless
 * - Tip-in: TPS rate > threshold → accel enrich → AFR not representative
 * - Railed sensor: AFR < 10 or > 19.5 → sensor at stop, not a measurement
 * - Transient: RPM/load changing rapidly → wrong cell attribution
 */
data class AutoTuneFilters(
    val minRpm: Double = 1500.0,
    val maxRpm: Double = 7500.0,
    val minClt: Double = 60.0,
    val maxTpsRate: Double = 80.0,           // %/sec
    val minSteadyMs: Long = 0L,              // 0 = disabled
    val excludeAccelEnrich: Boolean = true,
    val excludeFuelCut: Boolean = true,
    val afrRailLow: Double = 10.0,
    val afrRailHigh: Double = 19.5,
    val minLoad: Double = 0.0,
    val maxLoad: Double = Double.MAX_VALUE,
    val customFilter: String? = null
)

data class AutoTuneSample(
    val rpm: Double,
    val load: Double,
    val afr: Double,
    val targetAfr: Double,
    val ve: Double,
    val clt: Double,
    val tps: Double,
    val tpsRate: Double,
    val accelEnrichActive: Boolean = false,
    val fuelCutActive: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis()
)

enum class AutoTuneRejectReason(val displayName: String) {
    RPM_OUT_OF_RANGE("RPM out of range"),
    LOAD_OUT_OF_RANGE("Load out of range"),
    CLT_TOO_COLD("Coolant too cold"),
    TPS_RATE_TOO_HIGH("TPS rate too high (transient)"),
    ACCEL_ENRICH_ACTIVE("Accel enrichment active"),
    FUEL_CUT_ACTIVE("Fuel cut active (DFCO)"),
    AFR_RAILED("AFR sensor railed"),
    NOT_STEADY("Not steady state"),
    CUSTOM_FILTER("Custom filter rejected"),
    NO_LAMBDA_DELAY_MATCH("No lambda delay match in buffer")
}

/**
 * Check if a sample passes all filters.
 * @return null if sample passes, AutoTuneRejectReason if rejected.
 */
fun classifySample(sample: AutoTuneSample, filters: AutoTuneFilters): AutoTuneRejectReason? {
    // RPM bounds
    if (sample.rpm < filters.minRpm || sample.rpm > filters.maxRpm)
        return AutoTuneRejectReason.RPM_OUT_OF_RANGE

    // Load bounds
    if (sample.load < filters.minLoad || sample.load > filters.maxLoad)
        return AutoTuneRejectReason.LOAD_OUT_OF_RANGE

    // CLT filter
    if (sample.clt < filters.minClt)
        return AutoTuneRejectReason.CLT_TOO_COLD

    // TPS rate (transient detection)
    if (abs(sample.tpsRate) > filters.maxTpsRate)
        return AutoTuneRejectReason.TPS_RATE_TOO_HIGH

    // Accel enrichment exclusion
    if (filters.excludeAccelEnrich && sample.accelEnrichActive)
        return AutoTuneRejectReason.ACCEL_ENRICH_ACTIVE

    // Fuel cut (DFCO) exclusion
    if (filters.excludeFuelCut && sample.fuelCutActive)
        return AutoTuneRejectReason.FUEL_CUT_ACTIVE

    // AFR railed sensor exclusion
    if (sample.afr < filters.afrRailLow || sample.afr > filters.afrRailHigh)
        return AutoTuneRejectReason.AFR_RAILED

    return null
}

/**
 * P2 Correctness: Lambda-delay correlation buffer.
 *
 * Ported from LibreTune's `autotune/mod.rs::find_delayed_data_point`.
 *
 * The engine's exhaust gas takes time to travel from the cylinder to the
 * wideband sensor. This means the AFR reading at time T corresponds to
 * the engine state at time T - delay_ms. Without compensating for this,
 * AutoTune attributes AFR readings to the wrong cell during transients.
 *
 * Algorithm:
 * 1. Buffer recent samples in a VecDeque
 * 2. When a new sample arrives, find the sample from delay_ms ago
 * 3. Use the historical sample's (rpm, load) for cell attribution
 * 4. Use the current sample's AFR (which is the delayed reading)
 */
class LambdaDelayBuffer(
    private val maxBufferAgeMs: Long = 10_000L
) {
    private val buffer = ArrayDeque<AutoTuneSample>()

    fun addSample(sample: AutoTuneSample) {
        buffer.addLast(sample)
        // Prune old samples
        val cutoff = sample.timestampMs - maxBufferAgeMs
        while (buffer.isNotEmpty() && buffer.first().timestampMs < cutoff) {
            buffer.removeFirst()
        }
    }

    /**
     * Find the sample closest to (now - delayMs).
     *
     * @param nowMs Current timestamp
     * @param delayMs Lambda delay in milliseconds
     * @param matchToleranceMs How close the match must be (default: 50ms)
     * @return The historical sample, or null if no match within tolerance
     */
    fun findDelayedSample(nowMs: Long, delayMs: Long, matchToleranceMs: Long = 50L): AutoTuneSample? {
        if (buffer.isEmpty()) return null
        val targetTime = nowMs - delayMs

        // Find the sample closest to targetTime
        var best: AutoTuneSample? = null
        var bestDiff = Long.MAX_VALUE

        for (sample in buffer) {
            val diff = abs(sample.timestampMs - targetTime)
            if (diff < bestDiff) {
                bestDiff = diff
                best = sample
            }
        }

        // Accept if within tolerance
        return if (bestDiff <= matchToleranceMs) best else null
    }

    /**
     * Get the mean sample gap for adaptive tolerance.
     * LibreTune uses 0.6 × mean_gap as tolerance.
     */
    fun meanSampleGapMs(): Long {
        if (buffer.size < 2) return 50L
        val gaps = mutableListOf<Long>()
        for (i in 1 until buffer.size) {
            gaps.add(buffer[i].timestampMs - buffer[i-1].timestampMs)
        }
        return (gaps.sum() / gaps.size).coerceAtLeast(20L)
    }

    fun clear() = buffer.clear()
    fun size() = buffer.size
}

/**
 * P2 Correctness: EWMA (Exponentially Weighted Moving Average) for VE correction.
 *
 * Ported from LibreTune's `autotune/mod.rs::raw_required_cma`.
 *
 * Instead of a simple arithmetic mean, LibreTune uses a cumulative moving
 * average with hit-weighting. This prevents outliers from over-influencing
 * the proposed VE value.
 */
class VECorrectionEWMA(
    private val baseWeight: Double = 20.0    // confidence ramp anchor
) {
    private var cma: Double = 0.0
    private var totalWeight: Double = 0.0

    /**
     * Add a new required VE observation.
     *
     * @param requiredVe The raw required VE = cellVe × (actualAfr / targetAfr)
     * @param hitWeight Weight of this sample (1.0 = uniform, <1.0 = edge of cell)
     */
    fun addObservation(requiredVe: Double, hitWeight: Double = 1.0) {
        totalWeight += hitWeight
        cma += (requiredVe - cma) * (hitWeight / totalWeight)
    }

    /**
     * Get the proposed VE value with confidence ramp applied.
     *
     * The confidence ramp prevents sparse cells (few samples) from proposing
     * full-strength changes. A cell needs baseWeight samples before its
     * proposal carries full authority.
     */
    fun proposedValue(beginningValue: Double): Double {
        if (totalWeight <= 0) return beginningValue
        val confidence = min(totalWeight / baseWeight, 1.0)
        return beginningValue + (cma - beginningValue) * confidence
    }

    fun rawCma(): Double = cma
    fun totalWeight(): Double = totalWeight
    fun reset() { cma = 0.0; totalWeight = 0.0 }
}

/**
 * P0 Safety: Authority limits (3-stage clamp).
 *
 * Ported from LibreTune's `autotune/mod.rs::apply_authority_limits`.
 *
 * Three stages of clamping, applied in order:
 * 1. Absolute per-cell delta (maxCellValueChange)
 * 2. Percentage of beginning value (maxCellPercentageChange)
 * 3. Absolute rails (minCellValue, maxCellValue)
 *
 * Rails are applied LAST to bound a series of edits from walking
 * a cell anywhere at all.
 */
data class AutoTuneAuthorityLimits(
    val maxCellValueChange: Double = 10.0,        // absolute VE units
    val maxCellPercentageChange: Double = 20.0,   // % of beginning value
    val minCellValue: Double = 0.0,
    val maxCellValue: Double = 255.0
) {
    /**
     * Apply all 3 stages of authority limits.
     * @param proposedValue The value proposed by the EWMA
     * @param beginningValue The original VE value at session start
     * @return The clamped value
     */
    fun clamp(proposedValue: Double, beginningValue: Double): Double {
        // Stage 1: Absolute per-cell delta
        val absDelta = proposedValue - beginningValue
        val clampedAbs = absDelta.coerceIn(-maxCellValueChange, maxCellValueChange)
        val afterAbs = beginningValue + clampedAbs

        // Stage 2: Percentage of beginning value
        val maxPctDelta = abs(beginningValue) * maxCellPercentageChange / 100.0
        val pctDelta = (afterAbs - beginningValue).coerceIn(-maxPctDelta, maxPctDelta)
        val afterPct = beginningValue + pctDelta

        // Stage 3: Absolute rails (LAST — bounds series of edits)
        return afterPct.coerceIn(
            min(minCellValue, maxCellValue),
            max(minCellValue, maxCellValue)
        )
    }
}

/**
 * P0 Safety: Calculate required VE from AFR error.
 *
 * Ported from LibreTune's `autotune/mod.rs::calculate_required_ve`.
 *
 * Formula: required_ve = cell_ve × (actual_afr / target_afr)
 *
 * This is the core correction formula. If actual AFR is richer than target
 * (actual < target), the VE is too high → required_ve < cell_ve → reduce VE.
 * If actual AFR is leaner than target (actual > target), VE is too low →
 * required_ve > cell_ve → increase VE.
 *
 * IMPORTANT: cell_ve must come from the reference VE TABLE, not from the
 * ECU's veCurr output channel. The ECU interpolates veCurr between bins,
 * so scaling that interpolated value by (actual/target) yields a number
 * written to the cell corner — a different quantity. This is LibreTune
 * bug #1, which caused 36.5% of samples to be made worse.
 */
fun calculateRequiredVe(cellVe: Double, actualAfr: Double, targetAfr: Double): Double {
    if (targetAfr <= 0) return cellVe
    return cellVe * (actualAfr / targetAfr)
}

/**
 * P2 Correctness: RPM-based lambda delay curve.
 *
 * Ported from LibreTune's `autotune/mod.rs::get_lambda_delay_ms`.
 *
 * At low RPM (800), exhaust transport delay is ~200ms.
 * At high RPM (6000), transport delay is ~50ms.
 * Linear interpolation between these points.
 */
fun getLambdaDelayMs(rpm: Double, configuredDelayMs: Double = 0.0): Long {
    // If user configured a fixed delay, use it
    if (configuredDelayMs > 0) return configuredDelayMs.toLong()

    // Otherwise use RPM-based curve: 200ms @ 800rpm → 50ms @ 6000rpm
    val idleDelay = 200.0
    val highRpmDelay = 50.0
    val idleRpm = 800.0
    val highRpm = 6000.0

    val t = ((rpm - idleRpm) / (highRpm - idleRpm)).coerceIn(0.0, 1.0)
    return (idleDelay + t * (highRpmDelay - idleDelay)).toLong()
}

/**
 * P2 Correctness: Flow-scaled lambda delay table.
 *
 * Ported from LibreTune's `autotune/mod.rs::compute_flow_scaled_delay_table`.
 *
 * Models transport delay as: delay = floor + K / flow
 * where flow = rpm × load × ve (volumetric flow proxy).
 * Anchored so warm idle = idleDelayMs, high-flow asymptote = floorMs.
 */
fun computeFlowScaledDelayTable(
    veTable: List<List<Double>>,
    rpmBins: List<Double>,
    loadBins: List<Double>,
    idleDelayMs: Double = 200.0,
    floorMs: Double = 50.0
): List<List<Double>> {
    if (veTable.isEmpty() || rpmBins.isEmpty() || loadBins.isEmpty()) return emptyList()

    // Find the flow at idle (minimum flow cell)
    val idleFlow = rpmBins.first() * loadBins.first() * veTable[0][0]
    if (idleFlow <= 0) return List(loadBins.size) { List(rpmBins.size) { idleDelayMs } }

    // K = (idleDelay - floor) × idleFlow
    val k = (idleDelayMs - floorMs) * idleFlow

    return loadBins.mapIndexed { r, load ->
        rpmBins.mapIndexed { c, rpm ->
            val ve = veTable.getOrNull(r)?.getOrNull(c) ?: 50.0
            val flow = rpm * load * ve
            if (flow > 0) (floorMs + k / flow) else idleDelayMs
        }
    }
}

/**
 * P2 Correctness: Steady-state detection.
 *
 * Ported from LibreTune's `autotune/mod.rs::is_steady`.
 *
 * Walks the buffer backwards from the current sample. If any sample
 * within the steady window had RPM >100 rpm or load >3 kPa different
 * from current, the sample is rejected as transient.
 */
fun isSteadyState(
    buffer: LambdaDelayBuffer,
    current: AutoTuneSample,
    steadyMs: Long
): Boolean {
    if (steadyMs <= 0) return true
    val cutoff = current.timestampMs - steadyMs

    // We can't directly iterate the buffer, so we check via the delayed sample
    val delayed = buffer.findDelayedSample(current.timestampMs, steadyMs, steadyMs)
    if (delayed == null) return false

    val rpmDiff = abs(delayed.rpm - current.rpm)
    val loadDiff = abs(delayed.load - current.load)

    return rpmDiff <= 100.0 && loadDiff <= 3.0
}

/**
 * P2 Correctness: Normalize lambda to AFR.
 *
 * Ported from LibreTune's `autotune/mod.rs::normalise_to_afr`.
 * If the value is < 2.0, it's lambda → multiply by stoichiometric AFR.
 */
fun normalizeToAfr(value: Double, stoich: Double = 14.7): Double {
    return if (abs(value) < 2.0) value * stoich else value
}

/**
 * P2 Correctness: Hit weighting modes.
 *
 * Ported from LibreTune's `autotune/mod.rs::HitWeighting`.
 * Down-weights samples that fall between cell centers.
 */
enum class HitWeightingMode {
    UNIFORM,
    CELL_PROXIMITY,
    CELL_PROXIMITY_SQUARED,
    CELL_CENTRE_ONLY
}

fun calculateHitWeight(
    rpm: Double, load: Double,
    rpmBins: List<Double>, loadBins: List<Double>,
    cellRow: Int, cellCol: Int,
    mode: HitWeightingMode
): Double {
    if (mode == HitWeightingMode.UNIFORM) return 1.0

    val binRpm = rpmBins.getOrNull(cellCol) ?: return 1.0
    val binLoad = loadBins.getOrNull(cellRow) ?: return 1.0

    // Find distance to neighboring bins
    val rpmDist = abs(rpm - binRpm)
    val loadDist = abs(load - binLoad)

    val nextRpmDist = if (cellCol + 1 < rpmBins.size) abs(rpmBins[cellCol + 1] - binRpm) else rpmDist
    val nextLoadDist = if (cellRow + 1 < loadBins.size) abs(loadBins[cellRow + 1] - binLoad) else loadDist

    val rpmWeight = if (nextRpmDist > 0) 1.0 - (rpmDist / nextRpmDist).coerceIn(0.0, 1.0) else 1.0
    val loadWeight = if (nextLoadDist > 0) 1.0 - (loadDist / nextLoadDist).coerceIn(0.0, 1.0) else 1.0

    return when (mode) {
        HitWeightingMode.UNIFORM -> 1.0
        HitWeightingMode.CELL_PROXIMITY -> rpmWeight * loadWeight
        HitWeightingMode.CELL_PROXIMITY_SQUARED -> (rpmWeight * loadWeight).let { it * it }
        HitWeightingMode.CELL_CENTRE_ONLY -> if (rpmWeight >= 0.75 && loadWeight >= 0.75) 1.0 else 0.0
    }
}
