package com.ztune.libretune.core.autotune

import kotlin.math.abs

/**
 * Configuration for the AutoTune engine.
 *
 * v1.3.2 — Wired to the safety/correctness logic in [AutoTuneLogic.kt] that was
 * previously written but never called by anything (see audit finding #2):
 *  - [filters] / [classifySample]: reject samples during DFCO/fuel-cut,
 *    accel-enrichment, cold engine, transient TPS, and railed AFR sensor.
 *  - [authorityLimits]: proper 3-stage clamp (absolute delta → percentage →
 *    absolute rails) instead of a single blunt percent clamp.
 *  - [calculateRequiredVe] + [VECorrectionEWMA]: correction computed from the
 *    reference VE table value (not the ECU's interpolated veCurr), with a
 *    confidence ramp so sparse cells don't propose full-strength changes.
 *  - [LambdaDelayBuffer] + [getLambdaDelayMs]: compensates for exhaust
 *    transport delay so an AFR reading is attributed to the (rpm, load) the
 *    engine was actually at `delay_ms` ago, not the state at the moment the
 *    sensor reading arrived.
 */
data class AutoTuneConfig(
    val veTableName: String = "",
    val targetAfrTableName: String = "",
    val lambdaTargetTables: List<String> = emptyList(),
    /** Maximum fractional change per autotune pass (e.g. 0.1 = 10%). Feeds [authorityLimits]. */
    val authority: Float = 0.1f,
    /** Minimum number of samples (EWMA weight) in a cell before it will be adjusted. */
    val minCellSamples: Int = 5,
    /** Number of smoothing passes applied to the adjustment grid. */
    val smoothingPasses: Int = 3,
    /** Cells locked out of adjustment, stored as (row, col) pairs. */
    val lockedCells: Set<Pair<Int, Int>> = emptySet(),
    /** Channel name for the measured AFR/Lambda input. */
    val afrChannelName: String = "afr",
    /** Channel name for the RPM input. */
    val rpmChannelName: String = "rpm",
    /** Channel name for the load (MAP/TPS) input. */
    val loadChannelName: String = "map",
    /** Channel name for the target AFR input (looked up with a few fallback aliases too). */
    val targetAfrChannelName: String = "afrTarget",
    /** Channel name for coolant temperature — used by the cold-engine filter. */
    val cltChannelName: String = "clt",
    /** Channel name for throttle position — informational, not currently filtered on directly. */
    val tpsChannelName: String = "tps",
    /** Channel name for TPS rate of change — used by the transient filter. Matches ztune.ini's `tpsdot`. */
    val tpsRateChannelName: String = "tpsdot",
    /** Sample-rejection filter chain. See [AutoTuneFilters] / [classifySample]. */
    val filters: AutoTuneFilters = AutoTuneFilters(),
    /**
     * Fixed lambda delay override in ms, or 0 to use the RPM-based curve
     * ([getLambdaDelayMs]: ~200ms @ 800rpm → ~50ms @ 6000rpm). A 1-cylinder
     * engine's short exhaust path means this can differ a lot from
     * multi-cylinder-tuned defaults — see audit finding re: no delay
     * measurement tool. Until that tool exists, this is the best estimate.
     */
    val lambdaDelayMs: Double = 0.0
)

/**
 * Result of a single autotune computation pass.
 *
 * @property adjustments 2D array of percentage adjustments matching the table dimensions.
 *   Each value represents the suggested percent change to the VE cell
 *   (e.g. +5.0 means "increase this cell by 5%").
 * @property heatMap 2D array of per-cell diagnostic info, matching table dimensions.
 * @property totalAdjustments Number of cells that received a non-zero adjustment.
 * @property lockedCellCount Number of cells that were skipped due to being locked.
 * @property rejectedSampleCounts How many raw samples were dropped by each filter reason.
 *   Surfaced so the UI can eventually show "1200 samples fed, 340 rejected: 210 cold engine,
 *   90 transient, 40 AFR railed" instead of a single opaque sample count.
 */
data class AutoTuneResult(
    val adjustments: List<List<Double>>,
    val heatMap: List<List<AutoTuneCellInfo>>,
    val totalAdjustments: Int,
    val lockedCellCount: Int,
    val rejectedSampleCounts: Map<AutoTuneRejectReason, Int> = emptyMap()
)

/**
 * Diagnostic information about a single cell's autotune state.
 *
 * @property row Row index in the VE table.
 * @property col Column index in the VE table.
 * @property sampleCount Number of logged data points that fell into this cell
 *   (EWMA total weight, rounded — with uniform weighting this equals the raw count).
 * @property averageError Average (measured AFR − target AFR) across all samples in this cell.
 *   Positive → engine running richer than target; negative → leaner.
 * @property totalAdjustment Cumulative percentage adjustment applied to this cell.
 * @property isLocked Whether this cell is locked and excluded from adjustment.
 */
data class AutoTuneCellInfo(
    val row: Int,
    val col: Int,
    val sampleCount: Int,
    val averageError: Double,
    val totalAdjustment: Double,
    val isLocked: Boolean
)

/**
 * Core AutoTune engine.
 *
 * Adjusts VE table cells based on logged AFR vs target AFR data.
 *
 * The engine works in two phases:
 *
 * 1. **Data collection** — [addSample] runs each incoming reading through the
 *    [AutoTuneLogic] safety filter chain ([classifySample]), then through the
 *    [LambdaDelayBuffer] to attribute the (delay-compensated) AFR reading to
 *    the engine's (rpm, load) at the time the exhaust gas was actually
 *    produced. Samples that fail a filter, or that don't have a delay-matched
 *    historical point yet, are dropped (and counted in [AutoTuneResult.rejectedSampleCounts]).
 *
 * 2. **Computation** — [computeAdjustments] bins every accepted sample into the
 *    VE table grid defined by `rpmBins × loadBins`, accumulates a per-cell
 *    [VECorrectionEWMA] of [calculateRequiredVe] observations, and clamps the
 *    proposal through [AutoTuneAuthorityLimits] (3-stage: absolute delta →
 *    percentage of beginning value → absolute rails).
 *
 * Locked cells (see [AutoTuneConfig.lockedCells] / [setCellLocked]) always
 * receive a 0% adjustment and act as anchors during smoothing.
 */
class AutoTuneEngine(private val config: AutoTuneConfig) {

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    /** Raw samples buffered for lambda-delay lookback (see [LambdaDelayBuffer]). */
    private val lambdaBuffer = LambdaDelayBuffer()

    /** Samples that passed filtering + delay-matching, ready to be binned at compute time. */
    private val processedSamples = mutableListOf<ProcessedSample>()

    /** Runtime set of locked cells (starts from config, mutable via [setCellLocked]). */
    private val lockedCells = config.lockedCells.toMutableSet()

    /** Cached heat map from the most recent [computeAdjustments] call. */
    private var cachedHeatMap: Map<Pair<Int, Int>, AutoTuneCellInfo> = emptyMap()

    /** Counts of rejected raw samples, keyed by reason — for diagnostics. */
    private val rejectCounts = mutableMapOf<AutoTuneRejectReason, Int>()

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Accumulate a single data sample, running it through the full
     * [AutoTuneLogic] safety filter chain and lambda-delay compensation
     * before it becomes eligible for binning.
     */
    fun addSample(sample: AutoTuneSample) {
        val reason = classifySample(sample, config.filters)
        if (reason != null) {
            rejectCounts[reason] = (rejectCounts[reason] ?: 0) + 1
            return
        }

        lambdaBuffer.addSample(sample)

        val delayMs = getLambdaDelayMs(sample.rpm, config.lambdaDelayMs)
        val delayed = lambdaBuffer.findDelayedSample(sample.timestampMs, delayMs)
        if (delayed == null) {
            // Not enough history yet (start of session) to find the engine
            // state from `delayMs` ago — drop rather than mis-attribute.
            rejectCounts[AutoTuneRejectReason.NO_LAMBDA_DELAY_MATCH] =
                (rejectCounts[AutoTuneRejectReason.NO_LAMBDA_DELAY_MATCH] ?: 0) + 1
            return
        }

        // Cell attribution uses the DELAYED (historical) rpm/load — that's
        // the engine state that actually produced the exhaust gas this AFR
        // reading measures. The AFR/target values are from the CURRENT
        // sample, since that's the (delayed) reading itself.
        processedSamples.add(
            ProcessedSample(
                rpm = delayed.rpm,
                load = delayed.load,
                afr = sample.afr,
                targetAfr = sample.targetAfr
            )
        )
    }

    /**
     * Convenience overload for simple (rpm, load, afr, targetAfr)-only callers
     * that don't have CLT/TPS-rate/enrichment-flag data available. This SKIPS
     * the cold-engine, transient, and enrichment-active filters entirely
     * (there's nothing to filter on) — only the RPM/load bounds and AFR-railed
     * checks still apply. Prefer [addSample] with a full [AutoTuneSample]
     * whenever that data is available.
     */
    fun addSample(rpm: Double, load: Double, measuredAfr: Double, targetAfr: Double) {
        addSample(
            AutoTuneSample(
                rpm = rpm,
                load = load,
                afr = measuredAfr,
                targetAfr = targetAfr,
                ve = 0.0,
                clt = 999.0,       // deliberately "hot" so the cold-engine filter never fires — no CLT data given
                tps = 0.0,
                tpsRate = 0.0,     // deliberately zero so the transient filter never fires — no TPS-rate data given
                accelEnrichActive = false,
                fuelCutActive = false
            )
        )
    }

    /**
     * Run the autotune algorithm and compute adjustments for every cell.
     *
     * @param currentTableValues Current VE table values as a 2D list (`rows × cols`).
     *   Used both for dimension checking AND as the reference "beginning value"
     *   for [calculateRequiredVe] and [AutoTuneAuthorityLimits.clamp] — per-cell,
     *   not the ECU's interpolated veCurr output.
     * @param rpmBins RPM axis breakpoints in ascending order. Length must equal
     *   `currentTableValues[0].size` (number of columns).
     * @param loadBins Load axis breakpoints in ascending order. Length must equal
     *   `currentTableValues.size` (number of rows).
     * @return The computed [AutoTuneResult], or a zero-adjustment result when
     *   no valid data is available.
     */
    fun computeAdjustments(
        currentTableValues: List<List<Double>>,
        rpmBins: List<Double>,
        loadBins: List<Double>
    ): AutoTuneResult {
        val rows = currentTableValues.size
        val cols = if (rows > 0) currentTableValues[0].size else 0

        // Early return for degenerate inputs.
        if (rows == 0 || cols == 0 || processedSamples.isEmpty()) {
            return zeroResult(currentTableValues)
        }

        // --- Step 1: Bin samples into cells, accumulate EWMA of required-VE ---
        val ewma = Array(rows) { Array(cols) { VECorrectionEWMA() } }
        val errorSum = Array(rows) { DoubleArray(cols) }
        val errorCount = Array(rows) { IntArray(cols) }

        for (sample in processedSamples) {
            val row = binIndex(sample.load, loadBins)
            val col = binIndex(sample.rpm, rpmBins)
            if (row !in 0 until rows || col !in 0 until cols) continue

            val beginningValue = currentTableValues[row][col]
            val requiredVe = calculateRequiredVe(beginningValue, sample.afr, sample.targetAfr)
            ewma[row][col].addObservation(requiredVe)

            errorSum[row][col] += (sample.afr - sample.targetAfr)
            errorCount[row][col]++
        }

        // --- Step 2: Proposed value per cell → clamp via authority limits → percent ---
        val authorityLimits = AutoTuneAuthorityLimits(
            maxCellPercentageChange = (config.authority.toDouble() * 100.0)
        )

        val rawAdjustments = MutableList(rows) { r ->
            MutableList(cols) { c ->
                val locked = (r to c) in lockedCells
                val weight = ewma[r][c].totalWeight()

                if (locked || weight < config.minCellSamples) {
                    0.0
                } else {
                    val beginningValue = currentTableValues[r][c]
                    val proposed = ewma[r][c].proposedValue(beginningValue)
                    val clamped = authorityLimits.clamp(proposed, beginningValue)
                    if (beginningValue != 0.0) (clamped - beginningValue) / beginningValue * 100.0 else 0.0
                }
            }
        }

        // --- Step 3: Smoothing passes ---
        if (config.smoothingPasses > 0) {
            smooth(rawAdjustments, config.smoothingPasses)
        }

        // --- Step 4: Build heat map & aggregate stats ---
        val heatMap = mutableListOf<MutableList<AutoTuneCellInfo>>()
        var totalAdjustments = 0
        var lockedCellCount = 0

        for (r in 0 until rows) {
            val rowList = mutableListOf<AutoTuneCellInfo>()
            for (c in 0 until cols) {
                val locked = (r to c) in lockedCells
                val count = errorCount[r][c]
                val avgError = if (count > 0) errorSum[r][c] / count else 0.0
                val adj = rawAdjustments[r][c]

                if (locked) lockedCellCount++
                if (adj != 0.0) totalAdjustments++

                rowList.add(
                    AutoTuneCellInfo(
                        row = r,
                        col = c,
                        sampleCount = count,
                        averageError = avgError,
                        totalAdjustment = adj,
                        isLocked = locked
                    )
                )
            }
            heatMap.add(rowList)
        }

        // Cache for [getHeatMap].
        cachedHeatMap = buildMap {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    put(r to c, heatMap[r][c])
                }
            }
        }

        return AutoTuneResult(
            adjustments = rawAdjustments.map { it.toList() },
            heatMap = heatMap.map { it.toList() },
            totalAdjustments = totalAdjustments,
            lockedCellCount = lockedCellCount,
            rejectedSampleCounts = rejectCounts.toMap()
        )
    }

    /**
     * Clear all accumulated sample data.
     *
     * Does **not** clear locked cells; use [setCellLocked] individually
     * or create a new engine instance for that.
     */
    fun clearData() {
        processedSamples.clear()
        lambdaBuffer.clear()
        rejectCounts.clear()
        cachedHeatMap = emptyMap()
    }

    /**
     * Return the heat map from the most recent [computeAdjustments] call.
     *
     * If no computation has been performed yet, returns an empty map.
     */
    fun getHeatMap(): Map<Pair<Int, Int>, AutoTuneCellInfo> = cachedHeatMap

    /**
     * Lock or unlock a single cell.
     *
     * Locked cells are excluded from autotune adjustments and act as anchors
     * during smoothing (they always contribute 0.0 to their neighbours'
     * weighted average, causing adjustments to taper toward locked regions).
     *
     * @param row Row index of the cell.
     * @param col Column index of the cell.
     * @param locked `true` to lock, `false` to unlock.
     */
    fun setCellLocked(row: Int, col: Int, locked: Boolean) {
        val key = row to col
        if (locked) lockedCells.add(key) else lockedCells.remove(key)
    }

    /**
     * Check whether a specific cell is currently locked.
     */
    fun isCellLocked(row: Int, col: Int): Boolean = (row to col) in lockedCells

    /** Return the current number of accumulated (accepted, un-binned) samples. */
    fun sampleCount(): Int = processedSamples.size

    /** Return counts of raw samples rejected so far, keyed by reason. */
    fun rejectedSampleCounts(): Map<AutoTuneRejectReason, Int> = rejectCounts.toMap()

    // ------------------------------------------------------------------
    // Smoothing
    // ------------------------------------------------------------------

    /**
     * Apply [passes] iterations of 3×3 weighted-average smoothing **in-place**.
     *
     * The kernel is a discrete Gaussian approximation:
     * ```
     *  1  2  1
     *  2  4  2      (total weight = 16)
     *  1  2  1
     * ```
     *
     * Edge cells use whatever neighbours are available (reduced kernel).
     * Locked cells are pinned to 0.0 after every pass so they act as anchors.
     */
    private fun smooth(
        adjustments: MutableList<MutableList<Double>>,
        passes: Int
    ) {
        val rows = adjustments.size
        val cols = if (rows > 0) adjustments[0].size else 0
        if (rows == 0 || cols == 0) return

        val lockedSet = lockedCells

        // Pre-allocated kernel: (deltaRow, deltaCol, weight)
        val kernel = arrayOf(
            intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1),
            intArrayOf(-1,  0), intArrayOf(0,  0), intArrayOf(1,  0),
            intArrayOf(-1,  1), intArrayOf(0,  1), intArrayOf(1,  1)
        )
        val weights = doubleArrayOf(1.0, 2.0, 1.0, 2.0, 4.0, 2.0, 1.0, 2.0, 1.0)

        repeat(passes) {
            // Snapshot current state so each pass reads from the previous.
            val src = Array(rows) { r -> DoubleArray(cols) { adjustments[r][it] } }

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    if ((r to c) in lockedSet) {
                        adjustments[r][c] = 0.0
                        continue
                    }

                    var wSum = 0.0
                    var wTotal = 0.0
                    for (k in kernel.indices) {
                        val nr = r + kernel[k][0]
                        val nc = c + kernel[k][1]
                        if (nr in 0 until rows && nc in 0 until cols) {
                            wSum += src[nr][nc] * weights[k]
                            wTotal += weights[k]
                        }
                    }
                    adjustments[r][c] = if (wTotal > 0.0) wSum / wTotal else 0.0
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Find the nearest-neighbour bin index for [value] in a sorted [bins] list.
     *
     * Uses binary search to locate the insertion point, then picks the closer
     * of the two adjacent bins. Returns -1 if [bins] is empty.
     */
    private fun binIndex(value: Double, bins: List<Double>): Int {
        val n = bins.size
        if (n == 0) return -1
        if (n == 1) return 0

        // Binary search for the first element >= value.
        var lo = 0
        var hi = n - 1
        while (lo < hi) {
            val mid = lo + (hi - lo) / 2
            if (bins[mid] < value) lo = mid + 1 else hi = mid
        }

        // lo is the index of the first bin >= value (or the last bin if value
        // exceeds all).  Pick the closer of lo and lo-1.
        val prev = if (lo > 0) lo - 1 else lo
        return if (abs(value - bins[lo]) <= abs(value - bins[prev])) lo else prev
    }

    /** Produce a zero-adjustment result with an empty heat map. */
    private fun zeroResult(table: List<List<Double>>): AutoTuneResult {
        val heatMap = table.mapIndexed { r, row ->
            row.mapIndexed { c, _ ->
                AutoTuneCellInfo(
                    row = r, col = c,
                    sampleCount = 0,
                    averageError = 0.0,
                    totalAdjustment = 0.0,
                    isLocked = (r to c) in lockedCells
                )
            }
        }
        return AutoTuneResult(
            adjustments = table.map { row -> row.map { 0.0 } },
            heatMap = heatMap,
            totalAdjustments = 0,
            lockedCellCount = 0,
            rejectedSampleCounts = rejectCounts.toMap()
        )
    }

    // ------------------------------------------------------------------
    // Internal data types
    // ------------------------------------------------------------------

    /** A sample that passed filtering + lambda-delay matching, ready to bin. */
    private data class ProcessedSample(
        val rpm: Double,
        val load: Double,
        val afr: Double,
        val targetAfr: Double
    )
}