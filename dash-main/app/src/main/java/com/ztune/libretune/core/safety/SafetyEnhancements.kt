package com.ztune.libretune.core.safety

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.DataType

/**
 * P4: Predictive pin conflict check.
 * Ported from LibreTune's pin_conflict.rs::conflict_if_assigning.
 *
 * Checks whether a proposed pin assignment would create a conflict
 * BEFORE the value is written to the ECU.
 */
object PredictivePinConflict {

    data class ConflictPrediction(
        val wouldConflict: Boolean,
        val conflictingConstants: List<String>,
        val pinLabel: String
    )

    /**
     * Check if assigning a specific value to a constant would create a pin conflict.
     *
     * @param definition The ECU definition
     * @param constantName The constant being changed
     * @param newValueIndex The new bits-option index being assigned
     * @param getCurrentValue Function returning current value index for a constant name
     * @return ConflictPrediction with details
     */
    fun checkAssignment(
        definition: EcuDefinition,
        constantName: String,
        newValueIndex: Int,
        getCurrentValue: (String) -> Int?
    ): ConflictPrediction {
        val target = definition.constants[constantName]
            ?: return ConflictPrediction(false, emptyList(), "")

        if (target.dataType != DataType.BITS || target.bitOptions.size < 3) {
            return ConflictPrediction(false, emptyList(), "")
        }

        val newPinLabel = target.bitOptions.getOrNull(newValueIndex)?.trim() ?: ""
        if (newPinLabel.isEmpty() || isUnassigned(newPinLabel) || isBoardDefault(newPinLabel)) {
            return ConflictPrediction(false, emptyList(), newPinLabel)
        }

        val conflicts = mutableListOf<String>()
        for ((otherName, otherConst) in definition.constants) {
            if (otherName == constantName) continue
            if (otherConst.dataType != DataType.BITS || otherConst.bitOptions.size < 3) continue

            val otherIdx = getCurrentValue(otherName) ?: continue
            val otherPin = otherConst.bitOptions.getOrNull(otherIdx)?.trim() ?: continue
            if (otherPin.isEmpty() || isUnassigned(otherPin) || isBoardDefault(otherPin)) continue

            if (otherPin.equals(newPinLabel, ignoreCase = true)) {
                conflicts.add(otherName)
            }
        }

        return ConflictPrediction(
            wouldConflict = conflicts.isNotEmpty(),
            conflictingConstants = conflicts,
            pinLabel = newPinLabel
        )
    }

    private fun isUnassigned(label: String): Boolean {
        val lower = label.lowercase().trim()
        return lower in setOf("none", "off", "disabled", "invalid", "no pin", "n/a", "")
    }

    private fun isBoardDefault(label: String): Boolean {
        return label.lowercase().trim() == "board default"
    }
}

/**
 * P4: AI ClampResult with reason for UI feedback.
 * Ported from LibreTune's agent/safety.rs::ClampResult.
 */
data class ClampResult(
    val originalValue: Double,
    val clampedValue: Double,
    val wasClamped: Boolean,
    val reason: String? = null
)

object AiSafetyClamp {
    /**
     * Clamp a proposed table edit value with detailed result.
     *
     * @param proposedValue The value proposed by the AI
     * @param currentValue The current cell value (for relative clamping)
     * @param limits Authority limits
     * @return ClampResult with reason if clamped
     */
    fun clampWithReason(
        proposedValue: Double,
        currentValue: Double,
        limits: com.ztune.libretune.core.autotune.AutoTuneAuthorityLimits
    ): ClampResult {
        val clamped = limits.clamp(proposedValue, currentValue)

        if (clamped == proposedValue) {
            return ClampResult(proposedValue, clamped, false)
        }

        val reason = buildString {
            append("Clamped from ${"%.2f".format(proposedValue)} to ${"%.2f".format(clamped)}: ")
            val absDelta = proposedValue - currentValue
            when {
                kotlin.math.abs(absDelta) > limits.maxCellValueChange -> {
                    append("absolute Δ limit ${limits.maxCellValueChange} exceeded (Δ=${"%.2f".format(absDelta)})")
                }
                kotlin.math.abs(absDelta) > kotlin.math.abs(currentValue) * limits.maxCellPercentageChange / 100 -> {
                    append("percentage limit ${limits.maxCellPercentageChange}% exceeded")
                }
                clamped <= limits.minCellValue || clamped >= limits.maxCellValue -> {
                    append("absolute rail [${limits.minCellValue}, ${limits.maxCellValue}] exceeded")
                }
                else -> append("authority limit exceeded")
            }
        }

        return ClampResult(proposedValue, clamped, true, reason)
    }
}
