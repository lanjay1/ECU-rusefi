package com.ztune.libretune.core.basemap

import kotlin.math.pow

enum class FuelType(val stoich: Double) {
    GASOLINE(14.7), E85(9.8), E100(9.0), METHANOL(6.4), LPG(15.5), CNG(17.2), DIESEL(14.5)
}
enum class Aspiration { NA, TURBO, SUPERCHARGED }

data class EngineSpec(
    val cylinderCount: Int = 4, val displacementCc: Double = 2000.0,
    val injectorSizeCc: Double = 440.0, val fuelType: FuelType = FuelType.GASOLINE,
    val aspiration: Aspiration = Aspiration.NA, val idleRpm: Int = 850,
    val redlineRpm: Int = 7000, val boostTargetKpa: Double? = null,
    val targetWotAfr: Double? = null, val octane: Double? = null, val compressionRatio: Double = 10.0
)

data class BaseMap(
    val rpmBins: List<Double>, val loadBins: List<Double>,
    val veTable: List<List<Double>>, val ignitionTable: List<List<Double>>,
    val afrTable: List<List<Double>>, val crankingEnrichment: List<Double>,
    val warmupEnrichment: List<Double>, val primePulseMs: Double,
    val reqFuel: Double, val scalars: Map<String, Double>
)

object BasemapGenerator {
    fun generateRpmBins(idle: Int, redline: Int, count: Int = 16): List<Double> {
        return (0 until count).map { i ->
            val t = (i.toDouble() / (count - 1)).pow(1.3)
            (idle + t * (redline - idle))
        }.map { (it / 100).toInt() * 100.0 }
    }
    fun generateLoadBins(maxLoadKpa: Double, count: Int = 16): List<Double> {
        return (0 until count).map { i -> 15.0 + i.toDouble() / (count - 1) * (maxLoadKpa - 15.0) }
    }
    fun generateVeTable(spec: EngineSpec, rpmBins: List<Double>, loadBins: List<Double>): List<List<Double>> {
        return loadBins.map { load ->
            rpmBins.map { rpm ->
                val rn = (rpm - rpmBins.first()) / (rpmBins.last() - rpmBins.first()).coerceAtLeast(1.0)
                val mn = (load - loadBins.first()) / (loadBins.last() - loadBins.first()).coerceAtLeast(1.0)
                val rpmFactor = 1.0 - (rn - 0.6).pow(2) * 0.3
                val base = when (spec.aspiration) {
                    Aspiration.NA -> 30.0 + 55.0 * mn * rpmFactor
                    else -> 28.0 + 62.0 * mn * rpmFactor * (1.0 + (mn - 0.5) * 0.3)
                }
                base.coerceIn(15.0, 120.0)
            }
        }
    }
    /**
     * v1.3.6 (audit finding #9) — SEBELUMNYA `rn`/`mn` dihitung dari INDEKS
     * kolom/baris (`ci/(size-1)`), bukan dari NILAI RPM/load asli seperti di
     * [generateVeTable]. Bin RPM/load tidak selalu berjarak rata (biasanya
     * lebih rapat di RPM rendah) — akibatnya kurva advance naik ke puncak
     * lebih cepat dari yang dimaksud rumusnya kalau spacing bin tidak rata.
     * SEKARANG dipakai posisi ternormalisasi dari NILAI rpm/load asli, sama
     * seperti pendekatan [generateVeTable].
     */
    fun generateIgnitionTable(spec: EngineSpec, rpmBins: List<Double>, loadBins: List<Double>): List<List<Double>> {
        val octane = spec.octane ?: 91.0
        val cr = spec.compressionRatio
        val peakAdvance = 34.0 + (91 - octane) * 0.6 + (10.0 - cr) * 1.5
        val rpmSpan = (rpmBins.last() - rpmBins.first()).coerceAtLeast(1.0)
        val loadSpan = (loadBins.last() - loadBins.first()).coerceAtLeast(1.0)
        return loadBins.map { load ->
            rpmBins.map { rpm ->
                val rn = (rpm - rpmBins.first()) / rpmSpan
                val mn = (load - loadBins.first()) / loadSpan
                val idle = 14.0; val wotPull = 6.0 + 10.0 * mn
                val boostRetard = if (load > 101.0) (load - 101.0) * 0.8 else 0.0
                (idle + (peakAdvance - idle) * (rn / 0.55).coerceAtMost(1.0) - wotPull * mn - boostRetard).coerceIn(10.0, 42.0)
            }
        }
    }
    fun generateAfrTable(spec: EngineSpec, rpmBins: List<Double>, loadBins: List<Double>): List<List<Double>> {
        val stoich = spec.fuelType.stoich
        val wot = spec.targetWotAfr ?: stoich * 0.85
        return loadBins.map { load ->
            rpmBins.map { _ ->
                val mn = (load - loadBins.first()) / (loadBins.last() - loadBins.first()).coerceAtLeast(1.0)
                when { mn < 0.2 -> stoich * 1.02; mn < 0.5 -> stoich * 0.99; mn < 0.8 -> stoich * 0.97; else -> wot }
            }
        }
    }
    /**
     * v1.3.7 (audit finding #18) — SEBELUMNYA `dispPerCyl` (displacement PER
     * SILINDER) dibagi LAGI dengan `cylinderCount` di baris berikutnya —
     * pembagian ganda yang salah secara dimensi untuk mesin multi-silinder.
     * (Tidak berdampak untuk motor 1-silinder karena membagi dengan 1, tapi
     * tetap salah kalau `EngineSpec` ini dipakai untuk konfigurasi lain.)
     * SEKARANG reqFuel dihitung standar: reqFuel = dispPerCyl × stoich × 10 / injectorSize.
     */
    fun generateReqFuel(spec: EngineSpec): Double {
        val dispPerCyl = spec.displacementCc / spec.cylinderCount
        val reqFuel = (dispPerCyl * spec.fuelType.stoich * 10.0) / spec.injectorSizeCc
        return reqFuel.coerceIn(0.1, 25.5)
    }
    /**
     * v1.3.3 — Kurva warmup enrichment (%), dipakai untuk `asePctCurve` di
     * ztune.ini (unit "%", 100 = tidak ada koreksi/netral).
     *
     * BUG LAMA: formula `180+(20-t)*1.5` untuk t≤0 lalu `180-t*2` untuk
     * 0<t≤80 menghasilkan -40°C→270%, 0°C→210%, 40°C→100%, 60°C→60%,
     * 80°C→20%, lalu MELOMPAT BALIK ke 100% di 100°C. Antara 40-90°C
     * (rentang suhu kerja normal motor) nilainya turun JAUH DI BAWAH
     * baseline netral 100 — kalau dipakai apa adanya, motor nge-lean parah
     * tepat di suhu kerja normal sebelum "pulih" di 100°C.
     *
     * FIX: turun monoton dari nilai tinggi (dingin) menuju 100 (netral),
     * lalu rata di 100 — tidak pernah di bawah 100 di suhu manapun.
     */
    fun generateWarmupEnrichmentCurve(cltBinsC: List<Int> = listOf(-40, -20, 0, 20, 40, 60, 80, 100)): List<Double> {
        return cltBinsC.map { t -> (100.0 + (60.0 - t) * 1.5).coerceAtLeast(100.0) }
    }

    /**
     * v1.3.3 — Kurva cranking pulse width (ms), dipakai untuk `crankPwCurve`
     * di ztune.ini (unit "ms" — ABSOLUT, bukan persen).
     *
     * BUG LAMA: fungsi ini sebelumnya memakai formula % enrichment yang
     * SAMA PERSIS dengan warmup curve di atas (180, 210, 270, dst) lalu
     * ditulis langsung sebagai milidetik — pulsa cranking 270ms adalah
     * angka yang tidak masuk akal (cranking pulse normal ~2-20ms).
     *
     * FIX: kurva terpisah, diskalakan longgar terhadap ukuran injektor
     * (injektor lebih besar → pulsa lebih pendek untuk massa bahan bakar
     * yang sama), monoton turun dari dingin ke hangat, rata di atas ~20°C.
     */
    fun generateCrankingPulseCurve(
        spec: EngineSpec,
        cltBinsC: List<Int> = listOf(-40, -20, 0, 20, 40, 60, 80, 100)
    ): List<Double> {
        val basePulseMs = (6.0 * (440.0 / spec.injectorSizeCc)).coerceIn(2.0, 20.0)
        return cltBinsC.map { t ->
            val coldMultiplier = 1.0 + ((20.0 - t) / 60.0).coerceIn(0.0, 2.5)
            (basePulseMs * coldMultiplier).coerceIn(1.0, 40.0)
        }
    }

    fun generateBaseMap(spec: EngineSpec): BaseMap {
        val maxLoad = spec.boostTargetKpa ?: if (spec.aspiration == Aspiration.NA) 105.0 else 200.0
        val rpmBins = generateRpmBins(spec.idleRpm, spec.redlineRpm)
        val loadBins = generateLoadBins(maxLoad)
        return BaseMap(rpmBins, loadBins,
            generateVeTable(spec, rpmBins, loadBins),
            generateIgnitionTable(spec, rpmBins, loadBins),
            generateAfrTable(spec, rpmBins, loadBins),
            generateCrankingPulseCurve(spec),
            generateWarmupEnrichmentCurve(),
            (440.0 / spec.injectorSizeCc * 2.0).coerceIn(0.5, 15.0),
            generateReqFuel(spec),
            mapOf("reqFuel" to generateReqFuel(spec), "nCylinders" to spec.cylinderCount.toDouble(), "stoich" to spec.fuelType.stoich)
        )
    }
}