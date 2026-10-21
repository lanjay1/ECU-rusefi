package com.ztune.libretune.core.autotune

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.TableRole
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object AutoTuneSafety {

    fun fuelTuneRefusal(
        definition: EcuDefinition,
        tableName: String
    ): String? {
        val table = definition.getTableByNameOrMap(tableName)
            ?: return "Table '$tableName' not found in definition"

        return when (table.role) {
            TableRole.VE -> null
            TableRole.AFR_TARGET ->
                "Cannot AutoTune AFR target table '$tableName'. AutoTune is for VE tables only."

            TableRole.IGNITION ->
                "SAFETY: Cannot AutoTune ignition table '$tableName'. AutoTune adjusts VE based on AFR error."

            TableRole.WARMUP_ENRICHMENT ->
                "Cannot AutoTune warmup enrichment table '$tableName'. AutoTune is for VE tables only."

            TableRole.OTHER ->
                "Table '$tableName' is not a VE table. AutoTune is for VE tables only."
        }
    }

    fun fuelTunableTables(definition: EcuDefinition): List<String> {
        return definition.tables.values
            .filter { it.role == TableRole.VE }
            .map { it.name }
            .sorted()
    }
}

data class AutoTuneFilters(
    val minRpm: Double = 1500.0,
    val maxRpm: Double = 7500.0,
    val minClt: Double = 60.0,
    val maxTpsRate: Double = 80.0,
    val minSteadyMs: Long = 0L,
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

private fun finite(value: Double): Boolean = value.isFinite()

private fun validFilterConfig(filters: AutoTuneFilters): Boolean {
    return filters.minRpm.isFinite() &&
        filters.maxRpm.isFinite() &&
        filters.minRpm <= filters.maxRpm &&
        filters.minClt.isFinite() &&
        filters.maxTpsRate.isFinite() &&
        filters.maxTpsRate >= 0.0 &&
        filters.minSteadyMs >= 0L &&
        filters.afrRailLow.isFinite() &&
        filters.afrRailHigh.isFinite() &&
        filters.afrRailLow > 0.0 &&
        filters.afrRailLow < filters.afrRailHigh &&
        filters.minLoad.isFinite() &&
        filters.maxLoad.isFinite() &&
        filters.minLoad <= filters.maxLoad
}

fun classifySample(
    sample: AutoTuneSample,
    filters: AutoTuneFilters
): AutoTuneRejectReason? {
    if (!validFilterConfig(filters)) {
        return AutoTuneRejectReason.CUSTOM_FILTER
    }

    if (
        !finite(sample.rpm) ||
        !finite(sample.load) ||
        !finite(sample.afr) ||
        !finite(sample.targetAfr) ||
        !finite(sample.ve) ||
        !finite(sample.clt) ||
        !finite(sample.tps) ||
        !finite(sample.tpsRate) ||
        sample.timestampMs < 0L
    ) {
        return AutoTuneRejectReason.CUSTOM_FILTER
    }

    if (sample.rpm < filters.minRpm || sample.rpm > filters.maxRpm) {
        return AutoTuneRejectReason.RPM_OUT_OF_RANGE
    }

    if (sample.load < filters.minLoad || sample.load > filters.maxLoad) {
        return AutoTuneRejectReason.LOAD_OUT_OF_RANGE
    }

    if (sample.clt < filters.minClt) {
        return AutoTuneRejectReason.CLT_TOO_COLD
    }

    if (abs(sample.tpsRate) > filters.maxTpsRate) {
        return AutoTuneRejectReason.TPS_RATE_TOO_HIGH
    }

    if (filters.excludeAccelEnrich && sample.accelEnrichActive) {
        return AutoTuneRejectReason.ACCEL_ENRICH_ACTIVE
    }

    if (filters.excludeFuelCut && sample.fuelCutActive) {
        return AutoTuneRejectReason.FUEL_CUT_ACTIVE
    }

    if (
        sample.afr < filters.afrRailLow ||
        sample.afr > filters.afrRailHigh
    ) {
        return AutoTuneRejectReason.AFR_RAILED
    }

    if (sample.targetAfr <= 0.0) {
        return AutoTuneRejectReason.AFR_RAILED
    }

    return null
}

class LambdaDelayBuffer(
    private val maxBufferAgeMs: Long = 10_000L
) {
    private val buffer = ArrayDeque<AutoTuneSample>()

    fun addSample(sample: AutoTuneSample) {
        if (!sample.timestampMs.isFiniteTimestamp()) return

        if (buffer.isNotEmpty() &&
            sample.timestampMs < buffer.last().timestampMs
        ) {
            return
        }

        buffer.addLast(sample)

        val cutoff = sample.timestampMs - maxBufferAgeMs
        while (
            buffer.isNotEmpty() &&
            buffer.first().timestampMs < cutoff
        ) {
            buffer.removeFirst()
        }
    }

    fun findDelayedSample(
        nowMs: Long,
        delayMs: Long,
        matchToleranceMs: Long = 50L
    ): AutoTuneSample? {
        if (
            buffer.isEmpty() ||
            nowMs < 0L ||
            delayMs < 0L ||
            matchToleranceMs < 0L
        ) {
            return null
        }

        val targetTime = nowMs - delayMs
        var best: AutoTuneSample? = null
        var bestDiff = Long.MAX_VALUE

        for (sample in buffer) {
            val diff = abs(sample.timestampMs - targetTime)
            if (diff < bestDiff) {
                bestDiff = diff
                best = sample
            }
        }

        return if (best != null && bestDiff <= matchToleranceMs) {
            best
        } else {
            null
        }
    }

    fun meanSampleGapMs(): Long {
        if (buffer.size < 2) return 50L

        var total = 0L
        var count = 0

        for (i in 1 until buffer.size) {
            val gap = buffer[i].timestampMs - buffer[i - 1].timestampMs
            if (gap > 0L) {
                total += gap
                count++
            }
        }

        if (count == 0) return 50L

        return (total / count).coerceAtLeast(20L)
    }

    fun snapshot(): List<AutoTuneSample> = buffer.toList()

    fun clear() = buffer.clear()

    fun size(): Int = buffer.size
}

private fun Long.isFiniteTimestamp(): Boolean = this >= 0L

class VECorrectionEWMA(
    private val baseWeight: Double = 20.0
) {
    private var cma = 0.0
    private var totalWeight = 0.0

    fun addObservation(
        requiredVe: Double,
        hitWeight: Double = 1.0
    ) {
        if (!requiredVe.isFinite()) return
        if (!hitWeight.isFinite() || hitWeight <= 0.0) return

        val newWeight = totalWeight + hitWeight
        if (!newWeight.isFinite() || newWeight <= 0.0) return

        if (totalWeight <= 0.0) {
            cma = requiredVe
            totalWeight = hitWeight
            return
        }

        cma += (requiredVe - cma) * (hitWeight / newWeight)
        totalWeight = newWeight
    }

    fun proposedValue(beginningValue: Double): Double {
        if (!beginningValue.isFinite()) return beginningValue
        if (totalWeight <= 0.0) return beginningValue
        if (!cma.isFinite()) return beginningValue

        val confidence = if (baseWeight > 0.0 && baseWeight.isFinite()) {
            (totalWeight / baseWeight).coerceIn(0.0, 1.0)
        } else {
            1.0
        }

        return beginningValue + (cma - beginningValue) * confidence
    }

    fun rawCma(): Double = cma

    fun totalWeight(): Double = totalWeight

    fun reset() {
        cma = 0.0
        totalWeight = 0.0
    }
}

data class AutoTuneAuthorityLimits(
    val maxCellValueChange: Double = 10.0,
    val maxCellPercentageChange: Double = 20.0,
    val minCellValue: Double = 0.0,
    val maxCellValue: Double = 255.0
) {
    fun clamp(
        proposedValue: Double,
        beginningValue: Double
    ): Double {
        if (!proposedValue.isFinite()) return beginningValue
        if (!beginningValue.isFinite()) return proposedValue

        val maxAbs = maxCellValueChange
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0

        val maxPct = maxCellPercentageChange
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: 0.0

        val lowerRail = min(minCellValue, maxCellValue)
        val upperRail = max(minCellValue, maxCellValue)

        val absDelta = proposedValue - beginningValue
        val afterAbs = beginningValue +
            absDelta.coerceIn(-maxAbs, maxAbs)

        val pctDelta = abs(beginningValue) * maxPct / 100.0
        val afterPct = beginningValue +
            (afterAbs - beginningValue).coerceIn(
                -pctDelta,
                pctDelta
            )

        return afterPct.coerceIn(lowerRail, upperRail)
    }
}

fun calculateRequiredVe(
    cellVe: Double,
    actualAfr: Double,
    targetAfr: Double
): Double {
    if (
        !cellVe.isFinite() ||
        !actualAfr.isFinite() ||
        !targetAfr.isFinite() ||
        targetAfr <= 0.0 ||
        actualAfr <= 0.0
    ) {
        return cellVe
    }

    val result = cellVe * (actualAfr / targetAfr)
    return if (result.isFinite()) result else cellVe
}

fun getLambdaDelayMs(
    rpm: Double,
    configuredDelayMs: Double = 0.0
): Long {
    if (!rpm.isFinite()) return 200L

    if (configuredDelayMs.isFinite() && configuredDelayMs > 0.0) {
        return configuredDelayMs
            .coerceIn(0.0, Long.MAX_VALUE.toDouble())
            .toLong()
    }

    val idleDelay = 200.0
    val highRpmDelay = 50.0
    val idleRpm = 800.0
    val highRpm = 6000.0

    val t = ((rpm - idleRpm) /
        (highRpm - idleRpm)).coerceIn(0.0, 1.0)

    return (idleDelay + t * (highRpmDelay - idleDelay))
        .coerceAtLeast(0.0)
        .toLong()
}

fun computeFlowScaledDelayTable(
    veTable: List<List<Double>>,
    rpmBins: List<Double>,
    loadBins: List<Double>,
    idleDelayMs: Double = 200.0,
    floorMs: Double = 50.0
): List<List<Double>> {
    if (
        veTable.isEmpty() ||
        rpmBins.isEmpty() ||
        loadBins.isEmpty()
    ) {
        return emptyList()
    }

    if (
        idleDelayMs !in 0.0..Double.MAX_VALUE ||
        floorMs !in 0.0..Double.MAX_VALUE ||
        floorMs > idleDelayMs
    ) {
        return emptyList()
    }

    if (!isStrictlyAscendingFinite(rpmBins)) return emptyList()
    if (!isStrictlyAscendingFinite(loadBins)) return emptyList()

    if (veTable.size != loadBins.size) return emptyList()
    if (veTable.any { it.size != rpmBins.size }) return emptyList()
    if (veTable.any { row -> row.any { !it.isFinite() } }) {
        return emptyList()
    }

    val idleVe = veTable.first().first()
    val idleRpm = rpmBins.first()
    val idleLoad = loadBins.first()

    val idleFlow = idleRpm * idleLoad * idleVe

    if (!idleFlow.isFinite() || idleFlow <= 0.0) {
        return List(loadBins.size) {
            List(rpmBins.size) { idleDelayMs }
        }
    }

    val k = (idleDelayMs - floorMs) * idleFlow

    return loadBins.indices.map { row ->
        rpmBins.indices.map { col ->
            val flow = rpmBins[col] *
                loadBins[row] *
                veTable[row][col]

            if (flow.isFinite() && flow > 0.0) {
                (floorMs + k / flow)
                    .coerceAtLeast(floorMs)
            } else {
                idleDelayMs
            }
        }
    }
}

private fun isStrictlyAscendingFinite(
    values: List<Double>
): Boolean {
    if (values.isEmpty()) return false

    for (i in values.indices) {
        val current = values[i]
        if (!current.isFinite()) return false

        if (i > 0 && current <= values[i - 1]) {
            return false
        }
    }

    return true
}

fun isSteadyState(
    buffer: LambdaDelayBuffer,
    current: AutoTuneSample,
    steadyMs: Long
): Boolean {
    if (steadyMs <= 0L) return true

    if (
        !current.rpm.isFinite() ||
        !current.load.isFinite() ||
        current.timestampMs < 0L
    ) {
        return false
    }

    val cutoff = current.timestampMs - steadyMs
    val samples = buffer.snapshot()

    if (samples.isEmpty()) return false

    var foundWindowSample = false

    for (sample in samples.asReversed()) {
        if (sample.timestampMs < cutoff) break

        if (sample.timestampMs > current.timestampMs) continue

        foundWindowSample = true

        if (
            !sample.rpm.isFinite() ||
            !sample.load.isFinite()
        ) {
            return false
        }

        if (abs(sample.rpm - current.rpm) > 100.0) {
            return false
        }

        if (abs(sample.load - current.load) > 3.0) {
            return false
        }
    }

    return foundWindowSample
}

fun normalizeToAfr(
    value: Double,
    stoich: Double = 14.7
): Double {
    if (
        !value.isFinite() ||
        !stoich.isFinite() ||
        stoich <= 0.0
    ) {
        return value
    }

    return if (abs(value) < 2.0) {
        value * stoich
    } else {
        value
    }
}

enum class HitWeightingMode {
    UNIFORM,
    CELL_PROXIMITY,
    CELL_PROXIMITY_SQUARED,
    CELL_CENTRE_ONLY
}

fun calculateHitWeight(
    rpm: Double,
    load: Double,
    rpmBins: List<Double>,
    loadBins: List<Double>,
    cellRow: Int,
    cellCol: Int,
    mode: HitWeightingMode
): Double {
    if (mode == HitWeightingMode.UNIFORM) return 1.0

    if (
        !rpm.isFinite() ||
        !load.isFinite() ||
        !isStrictlyAscendingFinite(rpmBins) ||
        !isStrictlyAscendingFinite(loadBins)
    ) {
        return 0.0
    }

    if (
        cellRow !in loadBins.indices ||
        cellCol !in rpmBins.indices
    ) {
        return 0.0
    }

    val cellRpm = rpmBins[cellCol]
    val cellLoad = loadBins[cellRow]

    val rpmLeft = if (cellCol > 0) {
        rpmBins[cellCol] - rpmBins[cellCol - 1]
    } else {
        Double.POSITIVE_INFINITY
    }

    val rpmRight = if (cellCol + 1 < rpmBins.size) {
        rpmBins[cellCol + 1] - rpmBins[cellCol]
    } else {
        Double.POSITIVE_INFINITY
    }

    val loadDown = if (cellRow > 0) {
        loadBins[cellRow] - loadBins[cellRow - 1]
    } else {
        Double.POSITIVE_INFINITY
    }

    val loadUp = if (cellRow + 1 < loadBins.size) {
        loadBins[cellRow + 1] - loadBins[cellRow]
    } else {
        Double.POSITIVE_INFINITY
    }

    val rpmDistance = abs(rpm - cellRpm)
    val loadDistance = abs(load - cellLoad)

    val rpmScale = min(rpmLeft, rpmRight)
        .takeIf { it.isFinite() && it > 0.0 }
        ?: max(rpmLeft.takeIf { it.isFinite() } ?: 0.0, rpmRight)

    val loadScale = min(loadDown, loadUp)
        .takeIf { it.isFinite() && it > 0.0 }
        ?: max(loadDown.takeIf { it.isFinite() } ?: 0.0, loadUp)

    val rpmWeight = if (rpmScale > 0.0 && rpmScale.isFinite()) {
        (1.0 - rpmDistance / rpmScale).coerceIn(0.0, 1.0)
    } else {
        1.0
    }

    val loadWeight = if (loadScale > 0.0 && loadScale.isFinite()) {
        (1.0 - loadDistance / loadScale).coerceIn(0.0, 1.0)
    } else {
        1.0
    }

    val weight = rpmWeight * loadWeight

    return when (mode) {
        HitWeightingMode.UNIFORM -> 1.0
        HitWeightingMode.CELL_PROXIMITY -> weight
        HitWeightingMode.CELL_PROXIMITY_SQUARED -> weight * weight
        HitWeightingMode.CELL_CENTRE_ONLY ->
            if (rpmWeight >= 0.75 && loadWeight >= 0.75) 1.0 else 0.0
    }.coerceIn(0.0, 1.0)
}