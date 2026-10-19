package com.ztune.libretune.core.tune

import com.ztune.libretune.core.ini.types.DataType

/**
 * P1: INI version migration.
 * Ported from LibreTune's tune/migration.rs.
 *
 * Compares a saved tune's constant manifest against the current INI
 * definition and classifies differences by severity.
 */
data class ConstantChange(
    val name: String,
    val oldType: String, val newType: String,
    val oldScale: Double, val newScale: Double,
    val oldPage: Int, val newPage: Int,
    val oldOffset: Int, val newOffset: Int
)

data class MigrationReport(
    val missingInTune: List<String>,      // new INI constants not in saved tune
    val missingInIni: List<String>,       // removed constants
    val typeChanged: List<ConstantChange>,
    val scaleChanged: List<ConstantChange>,
    val locationChanged: List<ConstantChange>,
    val canAutoMigrate: Boolean,
    val requiresUserReview: Boolean,
    val severity: String                   // "high", "medium", "low"
) {
    val totalChanges: Int get() = missingInTune.size + missingInIni.size + typeChanged.size + scaleChanged.size + locationChanged.size
    val hasChanges: Boolean get() = totalChanges > 0
}

object TuneMigration {
    /**
     * Compare a saved constant manifest against the current INI definition.
     *
     * Severity rules:
     * - type_changed → high (data interpretation differs, user must review)
     * - missing_in_ini → high (constant no longer exists)
     * - scale_changed → medium (values need rescaling)
     * - missing_in_tune → low (new constant, auto-migrate with default)
     * - location_changed → low (same data, different address, auto-migrate)
     */
    fun compareManifests(
        savedManifest: List<ConstantManifestEntry>,
        currentConstants: Map<String, com.ztune.libretune.core.ini.types.Constant>
    ): MigrationReport {
        val savedByName = savedManifest.associateBy { it.name }
        val currentByName = currentConstants

        val missingInTune = currentByName.keys.filter { it !in savedByName }.sorted()
        val missingInIni = savedByName.keys.filter { it !in currentByName }.sorted()

        val typeChanged = mutableListOf<ConstantChange>()
        val scaleChanged = mutableListOf<ConstantChange>()
        val locationChanged = mutableListOf<ConstantChange>()

        for ((name, saved) in savedByName) {
            val current = currentByName[name] ?: continue

            val typeChanged_ = saved.dataType != current.dataType.name
            val scaleChanged_ = saved.scale != current.scale
            val locationChanged_ = saved.page != current.page || saved.offset != current.offset

            if (typeChanged_ || scaleChanged_ || locationChanged_) {
                val change = ConstantChange(
                    name = name,
                    oldType = saved.dataType, newType = current.dataType.name,
                    oldScale = saved.scale, newScale = current.scale,
                    oldPage = saved.page, newPage = current.page,
                    oldOffset = saved.offset, newOffset = current.offset
                )
                when {
                    typeChanged_ -> typeChanged.add(change)
                    scaleChanged_ -> scaleChanged.add(change)
                    else -> locationChanged.add(change)
                }
            }
        }

        val hasHigh = typeChanged.isNotEmpty() || missingInIni.isNotEmpty()
        val hasMedium = scaleChanged.isNotEmpty()
        val severity = when {
            hasHigh -> "high"
            hasMedium -> "medium"
            else -> "low"
        }

        return MigrationReport(
            missingInTune = missingInTune,
            missingInIni = missingInIni,
            typeChanged = typeChanged,
            scaleChanged = scaleChanged,
            locationChanged = locationChanged,
            canAutoMigrate = !hasHigh,
            requiresUserReview = hasHigh,
            severity = severity
        )
    }
}
