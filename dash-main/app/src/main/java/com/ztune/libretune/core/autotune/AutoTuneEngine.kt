package com.ztune.libretune.core.autotune

import kotlin.math.abs

/**
 * Configuration for the AutoTune engine.
 *
 * The engine is intentionally conservative:
 * - invalid table dimensions are rejected
 * - RPM/load axes must be finite and strictly ascending
 * - samples outside the actual table axis are never assigned to edge cells
 * - smoothing never creates adjustments in cells without sufficient data
 * - authority limits are applied after smoothing
 */
data class AutoTuneConfig(
    val veTableName: String = "",
    val targetAfrTableName: String = "",
    val lambdaTargetTables: List<String> = emptyList(),
    /** Maximum fractional change per autotune pass (0.1 = 10%). */
    val authority: Float = 0.1f,
    /** Minimum EWMA weight required before a cell can be adjusted. */
    val minCellSamples: Int = 5,
    /** Number of smoothing passes applied to the adjustment grid. */
    val smoothingPasses: Int = 3,
    /** Cells excluded from adjustment, stored as (row, col). */
    val lockedCells: Set<Pair<Int, Int>> = emptySet(),
    val afrChannelName: String = "afr",
    val rpmChannelName: String = "rpm",
    val loadChannelName: String = "map",
    val targetAfrChannelName: String = "afrTarget",
    val cltChannelName: String = "clt",
    val tpsChannelName: String = "tps",
    val tpsRateChannelName: String = "tpsdot",
    val filters: AutoTuneFilters = AutoTuneFilters(),
    /**
     * Fixed lambda delay in milliseconds.
     * 0 means use the RPM-based delay curve.
     */
    val lambdaDelayMs: Double = 0.0
)

/**
 * Result of one AutoTune computation pass.
 */
data class AutoTuneResult(
    val adjustments: List<List<Double>>,
    val heatMap: List<List<AutoTuneCellInfo>>,
    val totalAdjustments: Int,
    val lockedCellCount: Int,
    val rejectedSampleCounts: Map<AutoTuneRejectReason, Int> = emptyMap()
)

/**
 * Diagnostic information for one VE table cell.
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
 * Pipeline:
 *
 *   sample filter
 *       ↓
 *   lambda delay compensation
 *       ↓
 *   axis validation
 *       ↓
 *   safe binning
 *       ↓
 *   EWMA
 *       ↓
 *   smoothing
 *       ↓
 *   authority clamp
 *       ↓
 *   heatmap / final result
 */
class AutoTuneEngine(private val config: AutoTuneConfig) {

    private val lambdaBuffer = LambdaDelayBuffer()

    private val processedSamples = mutableListOf<ProcessedSample>()

    private val lockedCells = config.lockedCells.toMutableSet()

    private var cachedHeatMap: Map<Pair<Int, Int>, AutoTuneCellInfo> = emptyMap()

    private val rejectCounts =
        mutableMapOf<AutoTuneRejectReason, Int>()

    /**
     * Add one realtime/logged AutoTune sample.
     */
    fun addSample(sample: AutoTuneSample) {
        /*
         * NaN comparisons are false in Kotlin.
         *
         * Therefore classifySample() alone is not sufficient to protect
         * against NaN/infinite values. Reject them before the filter chain.
         *
         * There is currently no dedicated INVALID_NUMERIC_DATA enum in the
         * existing public API, so these samples are silently discarded here.
         * Package 6 can add a dedicated diagnostic reason without changing
         * this engine's public API.
         */
        if (
            !sample.rpm.isFinite() ||
            !sample.load.isFinite() ||
            !sample.afr.isFinite() ||
            !sample.targetAfr.isFinite() ||
            !sample.clt.isFinite() ||
            !sample.tps.isFinite() ||
            !sample.tpsRate.isFinite()
        ) {
            return
        }

        val reason = classifySample(sample, config.filters)

        if (reason != null) {
            rejectCounts[reason] =
                (rejectCounts[reason] ?: 0) + 1
            return
        }

        lambdaBuffer.addSample(sample)

        val delayMs =
            getLambdaDelayMs(
                sample.rpm,
                config.lambdaDelayMs
            )

        val delayed =
            lambdaBuffer.findDelayedSample(
                sample.timestampMs,
                delayMs
            )

        if (delayed == null) {
            rejectCounts[
                AutoTuneRejectReason.NO_LAMBDA_DELAY_MATCH
            ] =
                (
                    rejectCounts[
                        AutoTuneRejectReason.NO_LAMBDA_DELAY_MATCH
                    ] ?: 0
                    ) + 1

            return
        }

        /*
         * Use delayed RPM/load for cell attribution.
         * Use current AFR/target AFR for the measurement.
         */
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
     * Convenience overload for callers that only have:
     * rpm, load, measured AFR and target AFR.
     */
    fun addSample(
        rpm: Double,
        load: Double,
        measuredAfr: Double,
        targetAfr: Double
    ) {
        addSample(
            AutoTuneSample(
                rpm = rpm,
                load = load,
                afr = measuredAfr,
                targetAfr = targetAfr,
                ve = 0.0,
                clt = 999.0,
                tps = 0.0,
                tpsRate = 0.0,
                accelEnrichActive = false,
                fuelCutActive = false
            )
        )
    }

    /**
     * Compute AutoTune adjustments.
     *
     * The supplied table and axes are treated as one atomic structure:
     *
     *   rows    == loadBins.size
     *   columns == rpmBins.size
     *
     * All rows must have identical column counts.
     */
    fun computeAdjustments(
        currentTableValues: List<List<Double>>,
        rpmBins: List<Double>,
        loadBins: List<Double>
    ): AutoTuneResult {

        validateConfiguration()
        validateTableAndAxes(
            currentTableValues,
            rpmBins,
            loadBins
        )

        val rows = currentTableValues.size
        val cols = currentTableValues[0].size

        if (processedSamples.isEmpty()) {
            return zeroResult(currentTableValues)
        }

        /*
         * One EWMA object per table cell.
         */
        val ewma =
            Array(rows) {
                Array(cols) {
                    VECorrectionEWMA()
                }
            }

        /*
         * Diagnostic AFR error statistics.
         */
        val errorSum =
            Array(rows) {
                DoubleArray(cols)
            }

        val errorCount =
            Array(rows) {
                IntArray(cols)
            }

        /*
         * Whether each cell has enough valid data to participate
         * in AutoTune and smoothing.
         */
        val eligible =
            Array(rows) {
                BooleanArray(cols)
            }

        /*
         * --------------------------------------------------------------
         * STEP 1
         * Safe binning + EWMA accumulation
         * --------------------------------------------------------------
         */
        for (sample in processedSamples) {

            /*
             * Out-of-axis samples are explicitly rejected.
             *
             * This is critical:
             *
             * RPM axis:
             *   1000 2000 3000 4000
             *
             * Sample:
             *   8000 RPM
             *
             * must NOT become cell 4000 RPM.
             */
            val row =
                binIndex(
                    sample.load,
                    loadBins
                )

            if (row < 0) {
                rejectCounts[
                    AutoTuneRejectReason.LOAD_OUT_OF_RANGE
                ] =
                    (
                        rejectCounts[
                            AutoTuneRejectReason.LOAD_OUT_OF_RANGE
                        ] ?: 0
                        ) + 1

                continue
            }

            val col =
                binIndex(
                    sample.rpm,
                    rpmBins
                )

            if (col < 0) {
                rejectCounts[
                    AutoTuneRejectReason.RPM_OUT_OF_RANGE
                ] =
                    (
                        rejectCounts[
                            AutoTuneRejectReason.RPM_OUT_OF_RANGE
                        ] ?: 0
                        ) + 1

                continue
            }

            val beginningValue =
                currentTableValues[row][col]

            /*
             * Non-finite table values must never enter the correction
             * calculation.
             */
            if (!beginningValue.isFinite()) {
                continue
            }

            val requiredVe =
                calculateRequiredVe(
                    beginningValue,
                    sample.afr,
                    sample.targetAfr
                )

            if (!requiredVe.isFinite()) {
                continue
            }

            ewma[row][col].addObservation(
                requiredVe
            )

            errorSum[row][col] +=
                sample.afr - sample.targetAfr

            errorCount[row][col]++
        }

        /*
         * --------------------------------------------------------------
         * STEP 2
         * Determine which cells actually have enough data.
         * --------------------------------------------------------------
         */
        for (r in 0 until rows) {
            for (c in 0 until cols) {

                eligible[r][c] =
                    !(r to c in lockedCells) &&
                    ewma[r][c].totalWeight() >=
                    config.minCellSamples
            }
        }

        /*
         * --------------------------------------------------------------
         * STEP 3
         * EWMA proposal.
         *
         * IMPORTANT:
         * No authority clamp yet.
         *
         * This allows smoothing to operate on the actual proposal field.
         * Authority is enforced only after smoothing.
         * --------------------------------------------------------------
         */
        val proposedAdjustments =
            MutableList(rows) { r ->
                MutableList(cols) { c ->

                    if (!eligible[r][c]) {
                        0.0
                    } else {

                        val beginningValue =
                            currentTableValues[r][c]

                        val proposedValue =
                            ewma[r][c]
                                .proposedValue(beginningValue)

                        if (
                            !proposedValue.isFinite() ||
                            beginningValue == 0.0
                        ) {
                            0.0
                        } else {
                            (
                                proposedValue -
                                    beginningValue
                                ) /
                                beginningValue *
                                100.0
                        }
                    }
                }
            }

        /*
         * --------------------------------------------------------------
         * STEP 4
         * Smoothing.
         *
         * Smoothing is deliberately performed BEFORE authority clamping.
         *
         * Unlike the previous implementation, smoothing cannot create
         * changes in cells that have no valid data.
         * --------------------------------------------------------------
         */
        if (config.smoothingPasses > 0) {
            smooth(
                adjustments = proposedAdjustments,
                eligible = eligible,
                passes = config.smoothingPasses
            )
        }

        /*
         * --------------------------------------------------------------
         * STEP 5
         * Authority clamp AFTER smoothing.
         * --------------------------------------------------------------
         */
        val authorityLimits =
            AutoTuneAuthorityLimits(
                maxCellPercentageChange =
                    config.authority.toDouble() * 100.0
            )

        val finalAdjustments =
            MutableList(rows) { r ->
                MutableList(cols) { c ->

                    /*
                     * Locked and data-insufficient cells are immutable.
                     */
                    if (!eligible[r][c]) {
                        0.0
                    } else {

                        val beginningValue =
                            currentTableValues[r][c]

                        val smoothedPercent =
                            proposedAdjustments[r][c]

                        if (
                            !smoothedPercent.isFinite() ||
                            beginningValue == 0.0
                        ) {
                            0.0
                        } else {

                            /*
                             * Convert the smoothed percentage back to a
                             * proposed absolute VE value.
                             */
                            val proposedValue =
                                beginningValue *
                                    (
                                        1.0 +
                                            smoothedPercent / 100.0
                                        )

                            if (!proposedValue.isFinite()) {
                                0.0
                            } else {

                                /*
                                 * Apply:
                                 *
                                 * 1. absolute delta
                                 * 2. percentage authority
                                 * 3. absolute rails
                                 */
                                val clampedValue =
                                    authorityLimits.clamp(
                                        proposedValue,
                                        beginningValue
                                    )

                                if (
                                    !clampedValue.isFinite()
                                ) {
                                    0.0
                                } else {
                                    (
                                        clampedValue -
                                            beginningValue
                                        ) /
                                        beginningValue *
                                        100.0
                                }
                            }
                        }
                    }
                }
            }

        /*
         * --------------------------------------------------------------
         * STEP 6
         * Heatmap and aggregate statistics.
         * --------------------------------------------------------------
         */
        val heatMap =
            MutableList(rows) {
                mutableListOf<AutoTuneCellInfo>()
            }

        var totalAdjustments = 0
        var lockedCellCount = 0

        for (r in 0 until rows) {

            for (c in 0 until cols) {

                val locked =
                    (r to c) in lockedCells

                val count =
                    errorCount[r][c]

                val avgError =
                    if (count > 0) {
                        errorSum[r][c] /
                            count.toDouble()
                    } else {
                        0.0
                    }

                val adjustment =
                    finalAdjustments[r][c]

                if (locked) {
                    lockedCellCount++
                }

                if (
                    adjustment.isFinite() &&
                    abs(adjustment) > 1e-9
                ) {
                    totalAdjustments++
                }

                heatMap[r].add(
                    AutoTuneCellInfo(
                        row = r,
                        col = c,
                        sampleCount = count,
                        averageError = avgError,
                        totalAdjustment = adjustment,
                        isLocked = locked
                    )
                )
            }
        }

        cachedHeatMap =
            buildMap {
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        put(
                            r to c,
                            heatMap[r][c]
                        )
                    }
                }
            }

        return AutoTuneResult(
            adjustments =
                finalAdjustments.map {
                    it.toList()
                },
            heatMap =
                heatMap.map {
                    it.toList()
                },
            totalAdjustments =
                totalAdjustments,
            lockedCellCount =
                lockedCellCount,
            rejectedSampleCounts =
                rejectCounts.toMap()
        )
    }

    /**
     * Clear all accumulated samples.
     *
     * Locked cells are intentionally preserved.
     */
    fun clearData() {
        processedSamples.clear()
        lambdaBuffer.clear()
        rejectCounts.clear()
        cachedHeatMap = emptyMap()
    }

    /**
     * Return the last computed heatmap.
     */
    fun getHeatMap():
        Map<Pair<Int, Int>, AutoTuneCellInfo> =
        cachedHeatMap

    /**
     * Lock or unlock a cell.
     */
    fun setCellLocked(
        row: Int,
        col: Int,
        locked: Boolean
    ) {
        val key = row to col

        if (locked) {
            lockedCells.add(key)
        } else {
            lockedCells.remove(key)
        }
    }

    /**
     * Check whether a cell is locked.
     */
    fun isCellLocked(
        row: Int,
        col: Int
    ): Boolean =
        (row to col) in lockedCells

    /**
     * Number of accepted, delay-matched samples waiting for computation.
     */
    fun sampleCount(): Int =
        processedSamples.size

    /**
     * Return rejected sample counters.
     */
    fun rejectedSampleCounts():
        Map<AutoTuneRejectReason, Int> =
        rejectCounts.toMap()

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    private fun validateConfiguration() {

        require(config.authority.isFinite()) {
            "AutoTune authority must be finite."
        }

        require(config.authority in 0.0f..1.0f) {
            "AutoTune authority must be between 0.0 and 1.0."
        }

        require(config.minCellSamples > 0) {
            "AutoTune minCellSamples must be greater than zero."
        }

        require(config.smoothingPasses >= 0) {
            "AutoTune smoothingPasses cannot be negative."
        }

        require(config.lambdaDelayMs.isFinite()) {
            "AutoTune lambdaDelayMs must be finite."
        }

        require(config.lambdaDelayMs >= 0.0) {
            "AutoTune lambdaDelayMs cannot be negative."
        }
    }

    private fun validateTableAndAxes(
        table: List<List<Double>>,
        rpmBins: List<Double>,
        loadBins: List<Double>
    ) {

        require(table.isNotEmpty()) {
            "AutoTune VE table must not be empty."
        }

        val cols =
            table.firstOrNull()?.size ?: 0

        require(cols > 0) {
            "AutoTune VE table must have at least one column."
        }

        /*
         * Every row must have exactly the same number of columns.
         */
        table.forEachIndexed { rowIndex, row ->

            require(row.size == cols) {
                "AutoTune VE table is not rectangular: " +
                    "row $rowIndex has ${row.size} columns, " +
                    "expected $cols."
            }

            row.forEachIndexed { colIndex, value ->

                require(value.isFinite()) {
                    "AutoTune VE table contains a non-finite " +
                        "value at row=$rowIndex col=$colIndex."
                }
            }
        }

        require(rpmBins.size == cols) {
            "RPM axis size ${rpmBins.size} does not match " +
                "table column count $cols."
        }

        require(loadBins.size == table.size) {
            "Load axis size ${loadBins.size} does not match " +
                "table row count ${table.size}."
        }

        validateAxis(
            name = "RPM",
            bins = rpmBins
        )

        validateAxis(
            name = "Load",
            bins = loadBins
        )
    }

    private fun validateAxis(
        name: String,
        bins: List<Double>
    ) {

        require(bins.isNotEmpty()) {
            "$name axis must not be empty."
        }

        bins.forEachIndexed { index, value ->

            require(value.isFinite()) {
                "$name axis contains a non-finite value at index $index."
            }
        }

        for (i in 1 until bins.size) {

            require(bins[i] > bins[i - 1]) {
                "$name axis must be strictly ascending: " +
                    "bins[$i - 1]=${bins[i - 1]} " +
                    "must be < bins[$i]=${bins[i]}."
            }
        }
    }

    // ------------------------------------------------------------------
    // Smoothing
    // ------------------------------------------------------------------

    /**
     * Smooth only cells that have sufficient valid data.
     *
     * Unsampled cells are NOT allowed to acquire an adjustment simply
     * because they are next to an active cell.
     *
     * Locked cells participate as zero-value anchors.
     */
    private fun smooth(
        adjustments: MutableList<MutableList<Double>>,
        eligible: Array<BooleanArray>,
        passes: Int
    ) {

        val rows = adjustments.size

        if (rows == 0) return

        val cols = adjustments[0].size

        if (cols == 0) return

        val kernel =
            arrayOf(
                intArrayOf(-1, -1),
                intArrayOf(0, -1),
                intArrayOf(1, -1),

                intArrayOf(-1, 0),
                intArrayOf(0, 0),
                intArrayOf(1, 0),

                intArrayOf(-1, 1),
                intArrayOf(0, 1),
                intArrayOf(1, 1)
            )

        val weights =
            doubleArrayOf(
                1.0, 2.0, 1.0,
                2.0, 4.0, 2.0,
                1.0, 2.0, 1.0
            )

        repeat(passes) {

            val source =
                Array(rows) { r ->
                    DoubleArray(cols) { c ->
                        adjustments[r][c]
                    }
                }

            for (r in 0 until rows) {

                for (c in 0 until cols) {

                    /*
                     * Locked cells are permanently zero.
                     */
                    if ((r to c) in lockedCells) {
                        adjustments[r][c] = 0.0
                        continue
                    }

                    /*
                     * Unsampled cells remain zero.
                     */
                    if (!eligible[r][c]) {
                        adjustments[r][c] = 0.0
                        continue
                    }

                    var weightedSum = 0.0
                    var totalWeight = 0.0

                    for (k in kernel.indices) {

                        val nr =
                            r + kernel[k][0]

                        val nc =
                            c + kernel[k][1]

                        if (
                            nr !in 0 until rows ||
                            nc !in 0 until cols
                        ) {
                            continue
                        }

                        /*
                         * Only valid/eligible cells and locked anchors
                         * participate in smoothing.
                         */
                        val neighbourEligible =
                            eligible[nr][nc]

                        val neighbourLocked =
                            (nr to nc) in lockedCells

                        if (
                            !neighbourEligible &&
                            !neighbourLocked
                        ) {
                            continue
                        }

                        val value =
                            source[nr][nc]

                        if (!value.isFinite()) {
                            continue
                        }

                        weightedSum +=
                            value * weights[k]

                        totalWeight +=
                            weights[k]
                    }

                    adjustments[r][c] =
                        if (totalWeight > 0.0) {
                            weightedSum / totalWeight
                        } else {
                            source[r][c]
                        }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Binning
    // ------------------------------------------------------------------

    /**
     * Find the nearest axis bin.
     *
     * IMPORTANT:
     * Values outside [firstBin, lastBin] return -1.
     *
     * They are never clamped to the nearest edge cell.
     */
    private fun binIndex(
        value: Double,
        bins: List<Double>
    ): Int {

        if (!value.isFinite()) {
            return -1
        }

        if (bins.isEmpty()) {
            return -1
        }

        if (value < bins.first()) {
            return -1
        }

        if (value > bins.last()) {
            return -1
        }

        if (bins.size == 1) {
            return 0
        }

        var low = 0
        var high = bins.lastIndex

        while (low < high) {

            val mid =
                low + (high - low) / 2

            if (bins[mid] < value) {
                low = mid + 1
            } else {
                high = mid
            }
        }

        /*
         * low is the first bin >= value.
         */
        val upperIndex = low

        if (upperIndex == 0) {
            return 0
        }

        val lowerIndex =
            upperIndex - 1

        val lowerDistance =
            abs(
                value -
                    bins[lowerIndex]
            )

        val upperDistance =
            abs(
                bins[upperIndex] -
                    value
            )

        return if (
            upperDistance < lowerDistance
        ) {
            upperIndex
        } else {
            lowerIndex
        }
    }

    // ------------------------------------------------------------------
    // Zero result
    // ------------------------------------------------------------------

    /**
     * Produce a safe zero-adjustment result.
     *
     * Locked cells are counted correctly.
     */
    private fun zeroResult(
        table: List<List<Double>>
    ): AutoTuneResult {

        val heatMap =
            table.mapIndexed { r, row ->

                row.mapIndexed { c, _ ->

                    AutoTuneCellInfo(
                        row = r,
                        col = c,
                        sampleCount = 0,
                        averageError = 0.0,
                        totalAdjustment = 0.0,
                        isLocked =
                            (r to c) in lockedCells
                    )
                }
            }

        val lockedCount =
            heatMap.sumOf { row ->
                row.count { it.isLocked }
            }

        return AutoTuneResult(
            adjustments =
                table.map { row ->
                    row.map { 0.0 }
                },
            heatMap =
                heatMap,
            totalAdjustments = 0,
            lockedCellCount =
                lockedCount,
            rejectedSampleCounts =
                rejectCounts.toMap()
        )
    }

    // ------------------------------------------------------------------
    // Internal data
    // ------------------------------------------------------------------

    private data class ProcessedSample(
        val rpm: Double,
        val load: Double,
        val afr: Double,
        val targetAfr: Double
    )
}