package com.ztune.libretune.core.safety

import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.DataType

/**
 * Deteksi konflik pin pada konfigurasi ECU.
 *
 * Detector ini hanya menganggap constant bertipe BITS sebagai kandidat
 * assignment pin. Nilai aktual dapat diberikan melalui [getValue].
 *
 * Jika [getValue] tidak tersedia, detector akan mencoba menggunakan
 * [EcuDefinition.defaultValues]. Jika tidak ada default value, index 0
 * digunakan sebagai fallback terakhir.
 */
data class PinConflict(
    val pinLabel: String,
    val constants: List<String>
) {
    val description: String
        get() = "Pin '$pinLabel' digunakan oleh: ${constants.joinToString(", ")}"
}

data class PinConflictReport(
    val conflicts: List<PinConflict>,
    val totalPinConstants: Int,
    val activePinConstants: Int
) {
    val hasConflicts: Boolean
        get() = conflicts.isNotEmpty()

    val summary: String
        get() = if (hasConflicts) {
            "Konflik pin terdeteksi:\n" +
                conflicts.joinToString("\n") {
                    "  • ${it.description}"
                }
        } else {
            "Tidak ada konflik pin ($activePinConstants assignment aktif)."
        }
}

object PinConflictDetector {

    private val PIN_NAME_PATTERNS = listOf(
        "pin",
        "outputpin",
        "inputpin",
        "gpiopin"
    )

    private val EXCLUDE_PATTERNS = listOf(
        "pinmode",
        "pinvert",
        "pindir",
        "pinpull",
        "pintype"
    )

    private val UNASSIGNED_LABELS = setOf(
        "",
        "none",
        "off",
        "disabled",
        "invalid",
        "no pin",
        "n/a"
    )

    private const val BOARD_DEFAULT = "board default"

    /**
     * Mendeteksi semua konflik assignment pin pada ECU definition.
     *
     * @param definition definisi ECU yang akan diperiksa.
     * @param getValue fungsi untuk mengambil nilai aktual constant.
     * Jika null, defaultValues dari definition digunakan.
     */
    fun detectConflicts(
        definition: EcuDefinition,
        getValue: ((String) -> Int?)? = null
    ): PinConflictReport {

        val pinConstants = definition.constants.values
            .filter(::isPinConstant)

        val pinAssignments = mutableMapOf<String, MutableList<String>>()

        for (constant in pinConstants) {
            val valueIndex = resolveValueIndex(
                definition = definition,
                constant = constant,
                getValue = getValue
            )

            val pinLabel = resolvePinLabel(
                constant = constant,
                valueIndex = valueIndex
            )

            if (isUnassigned(pinLabel) || isBoardDefault(pinLabel)) {
                continue
            }

            val normalizedPin = normalizePinLabel(pinLabel)

            pinAssignments
                .getOrPut(normalizedPin) { mutableListOf() }
                .add(constant.name)
        }

        val conflicts = pinAssignments
            .filter { (_, constants) -> constants.size > 1 }
            .map { (pin, constants) ->
                PinConflict(
                    pinLabel = pin,
                    constants = constants.distinct().sorted()
                )
            }
            .sortedBy { it.pinLabel }

        return PinConflictReport(
            conflicts = conflicts,
            totalPinConstants = pinConstants.size,
            activePinConstants = pinAssignments.values.sumOf { it.size }
        )
    }

    /**
     * Menentukan index nilai constant.
     *
     * Urutan prioritas:
     *
     * 1. getValue dari caller.
     * 2. defaultValues dari EcuDefinition.
     * 3. index 0 sebagai fallback.
     *
     * Default value dianggap sebagai nilai display/raw yang kemudian
     * dikonversi menjadi index bit option.
     */
    private fun resolveValueIndex(
        definition: EcuDefinition,
        constant: Constant,
        getValue: ((String) -> Int?)?
    ): Int {

        getValue?.invoke(constant.name)?.let { value ->
            return value
        }

        definition.defaultValues[constant.name]?.let { value ->
            val rounded = value.toInt()

            if (rounded in constant.bitOptions.indices) {
                return rounded
            }
        }

        return 0
    }

    /**
     * Memeriksa apakah constant merupakan assignment pin.
     */
    private fun isPinConstant(constant: Constant): Boolean {
        if (constant.dataType != DataType.BITS) {
            return false
        }

        if (constant.bitOptions.size < 3) {
            return false
        }

        val nameLower = constant.name.lowercase()

        if (EXCLUDE_PATTERNS.any { nameLower.contains(it) }) {
            return false
        }

        return PIN_NAME_PATTERNS.any { nameLower.contains(it) } ||
            constant.bitOptions.any(::looksLikePinLabel)
    }

    /**
     * Mengambil label pin berdasarkan index bit option.
     */
    private fun resolvePinLabel(
        constant: Constant,
        valueIndex: Int
    ): String {

        if (valueIndex !in constant.bitOptions.indices) {
            return ""
        }

        return constant.bitOptions[valueIndex].trim()
    }

    /**
     * Normalisasi label pin supaya perbandingan tidak sensitif terhadap
     * huruf besar/kecil atau spasi yang tidak diperlukan.
     *
     * Contoh:
     * "PA0" -> "PA0"
     * "pa0" -> "PA0"
     * " PA0 " -> "PA0"
     */
    private fun normalizePinLabel(label: String): String {
        return label.trim()
            .replace(Regex("\\s+"), " ")
            .uppercase()
    }

    /**
     * Memeriksa apakah label menunjukkan pin yang valid.
     *
     * Contoh:
     * PA0
     * PB5
     * PC13
     * PD7
     * D0
     * D13
     * A0
     * A5
     */
    private fun looksLikePinLabel(label: String): Boolean {
        val upper = label.trim().uppercase()

        // STM32-style:
        // PA0, PB5, PC13, PD7, etc.
        if (
            upper.length in 3..5 &&
            upper[0] == 'P' &&
            upper[1] in 'A'..'Z' &&
            upper.drop(2).all { it.isDigit() }
        ) {
            return true
        }

        // Arduino-style:
        // D0, D13, A0, A5
        if (
            upper.length in 2..4 &&
            upper[0] in charArrayOf('A', 'D') &&
            upper.drop(1).all { it.isDigit() }
        ) {
            return true
        }

        // Generic pin labels:
        // "PIN1", "PIN12", etc.
        if (
            upper.contains("PIN") &&
            upper.any { it.isDigit() }
        ) {
            return true
        }

        return false
    }

    private fun isUnassigned(label: String): Boolean {
        return label.trim()
            .lowercase() in UNASSIGNED_LABELS
    }

    private fun isBoardDefault(label: String): Boolean {
        return label.trim()
            .lowercase() == BOARD_DEFAULT
    }

    /**
     * Memeriksa apakah perubahan nilai sebuah constant akan menimbulkan
     * konflik dengan assignment pin lain.
     *
     * Constant yang sedang diperiksa sendiri tidak dimasukkan sebagai
     * konflik.
     */
    fun wouldConflict(
        definition: EcuDefinition,
        constantName: String,
        newValueIndex: Int,
        getValue: ((String) -> Int?)? = null
    ): List<String> {

        val target = definition.constants[constantName]
            ?: return emptyList()

        if (!isPinConstant(target)) {
            return emptyList()
        }

        val newPin = resolvePinLabel(
            constant = target,
            valueIndex = newValueIndex
        )

        if (
            isUnassigned(newPin) ||
            isBoardDefault(newPin)
        ) {
            return emptyList()
        }

        val normalizedNewPin = normalizePinLabel(newPin)

        val report = detectConflicts(
            definition = definition,
            getValue = getValue
        )

        return report.conflicts
            .firstOrNull {
                normalizePinLabel(it.pinLabel) == normalizedNewPin
            }
            ?.constants
            ?.filter { it != constantName }
            .orEmpty()
    }
}