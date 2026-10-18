package com.ztune.libretune.core.basemap

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** A = gaya MS lama (pengayaan tertanam di VE). B = berbasis lambda (VE murni, ada suku stoich/AFR_target). */
enum class FuelMode { A_MS_CLASSIC, B_LAMBDA }

/** Profil campuran: lambda saat cruise dan saat WOT. */
enum class MixProfile(val lamCruise: Double, val lamWot: Double) {
    RACING(0.97, 0.86),
    BALANCED(1.00, 0.87),
    ECO(1.03, 0.88)
}

/** Default = SOHC 4 klep 150 cc, RON 90. GANTI dengan spek aktual motormu. */
data class ZSpec(
    val displCc: Double = 150.0,
    val throttleBoreMm: Double = 28.0,
    val idleRpm: Int = 1500,
    val torquePeakRpm: Int = 8000,
    val redlineRpm: Int = 11000,
    val compression: Double = 11.0,
    val ron: Double = 90.0,
    val stoich: Double = 14.7,
    val injFlowCcMin: Double = 200.0,
    val injFlow2CcMin: Double? = null,      // injektor Stage 2 (null = tidak ada)
    val vePeak: Double = 0.95,              // SOHC 4 klep: 0,95-1,05
    val kLow: Double = 0.60,
    val kHigh: Double = 0.35,
    val cd: Double = 0.75,
    val bypassFrac: Double = 0.005,         // fit dari kondisi idle
    val vvaRpm: Int? = null,
    val vvaGain: Double = 0.08,
    val safetyRetardDeg: Double = 4.0,      // retard awal start pertama
    val maxAdvanceDeg: Double = 30.0,       // batas advance beban penuh sampai knock sensor bersih
    val deadtime14vMs: Double = 1.0,
    val deadtimeSlopeMsPerV: Double = 0.10,
    val dwell14vMs: Double = 3.0,
    val profile: MixProfile = MixProfile.BALANCED,
    val mode: FuelMode = FuelMode.B_LAMBDA
) {
    fun validate() {
        require(displCc in 30.0..1500.0) { "displCc harus 30..1500" }
        require(throttleBoreMm in 10.0..80.0) { "throttleBoreMm harus 10..80" }
        require(injFlowCcMin in 50.0..3000.0) { "injFlowCcMin harus 50..3000" }
        injFlow2CcMin?.let { require(it in 50.0..3000.0) { "injFlow2CcMin harus 50..3000" } }
        require(stoich in 12.0..16.0) { "stoich harus 12..16" }
        require(compression in 7.0..16.0) { "compression harus 7..16" }
        require(ron in 85.0..120.0) { "ron harus 85..120" }
        require(idleRpm in 800..3000) { "idleRpm harus 800..3000" }
        require(idleRpm < torquePeakRpm && torquePeakRpm < redlineRpm && redlineRpm <= 14000) {
            "Urutan harus idle < puncak torsi < redline <= 14000"
        }
    }
}

/**
 * Semua tabel: baris = TPS (sumbu Y), kolom = RPM (sumbu X), sesuai veTable[16,16].
 * Nilai sudah dibulatkan ke resolusi INI (VE 1 %, AFR 0,1, advance 0,5 deg).
 * dwellMs: baris = Vbatt, kolom = RPM.
 */
data class ZBasemap(
    val rpmBins: List<Double>,
    val tpsBins: List<Double>,
    val ve: List<List<Double>>,
    val afr: List<List<Double>>,
    val advance: List<List<Double>>,
    val stage2Pct: List<List<Double>>,
    val deadtimeVbat: List<Double>,
    val deadtimeMs: List<Double>,
    val dwellRpm: List<Double>,
    val dwellVbat: List<Double>,
    val dwellMs: List<List<Double>>,
    val cltBins: List<Double>,
    val crankPwMs: List<Double>,
    val cltCorrPct: List<Double>,
    val reqFuelMs: Double,
    val maxPrimaryDutyPct: Double,
    val warnings: List<String>
)

object ZtuneBasemap {

    private const val G = 1.4
    private val SQRT_RT = sqrt(287.0 * 293.0)
    private const val AIR_G_PER_L = 1.204
    private const val FUEL_G_PER_CC = 0.745

    val TPS_BINS = listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0, 15.0, 20.0, 25.0, 30.0, 40.0, 50.0, 60.0, 70.0, 85.0, 100.0)

    private fun smooth(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun psi(r: Double): Double =
        if (r <= 0.528) 0.6847
        else sqrt(2 * G / (G - 1) * (r.pow(2 / G) - r.pow((G + 1) / G)))

    /** PW (ms) untuk VE 100 % pada AFR stoich, densitas referensi 20 C / 101,3 kPa, tanpa deadtime. */
    fun reqFuelMs(displCc: Double, stoich: Double, flowCcMin: Double): Double =
        (displCc / 1000.0 * AIR_G_PER_L / stoich) / (flowCcMin * FUEL_G_PER_CC / 60000.0)

    fun veWot(s: ZSpec, n: Double): Double {
        val x = n / s.torquePeakRpm
        val f = if (x < 1.0) 1 - s.kLow * (1 - x).pow(2) else 1 - s.kHigh * (x - 1).pow(2)
        val vva = s.vvaRpm?.let { s.vvaGain * smooth((n - it + 300) / 600.0) } ?: 0.0
        return (s.vePeak * f * (1 + vva)).coerceIn(0.30, 1.25)
    }

    /** P_manifold / P_ambient dari keseimbangan aliran throttle (orifice kompresibel) vs kebutuhan mesin. */
    fun manifoldRatio(s: ZSpec, n: Double, tpsPct: Double): Double {
        val aBore = PI / 4 * (s.throttleBoreMm / 1000).pow(2)
        val alpha = (tpsPct / 100.0).coerceIn(0.0, 1.0) * PI / 2
        val aTh = aBore * ((1 - cos(alpha)) + s.bypassFrac)
        val q0 = s.displCc * 1e-6 * (n / 120.0) * veWot(s, n)
        val c = s.cd * aTh * SQRT_RT
        var lo = 0.0
        var hi = 1.0
        repeat(40) {
            val mid = (lo + hi) / 2
            if (c * psi(mid) - mid * q0 > 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    fun afrTarget(s: ZSpec, n: Double, r: Double, tps: Double): Double {
        val red = s.redlineRpm.toDouble()
        val lamWot = s.profile.lamWot - 0.02 * smooth((n - 0.8 * red) / (0.2 * red))
        var lam = s.profile.lamCruise + (lamWot - s.profile.lamCruise) * smooth((r - 0.55) / 0.35)
        val idleW = (1 - smooth((n - 1800) / 1200)) * (1 - smooth((tps - 3.0) / 5.0))
        lam -= 0.03 * idleW                       // idle sedikit kaya untuk kestabilan 1-silinder
        return (s.stoich * lam).coerceIn(10.8, 15.5)
    }

    fun advance(s: ZSpec, n: Double, r: Double): Double {
        val rpmTerm = 1 - exp(-n / (0.25 * s.redlineRpm))
        val mbt = 10.0 + 20.0 * rpmTerm + 12.0 * (1 - r).pow(1.2) * smooth((n - 1200) / 2000)
        val crSafe = 10.5 + 0.2 * (s.ron - 90.0)
        val knock = 1.2 * max(0.0, s.compression - crSafe) * r.pow(1.5)
        val cap = 42.0 - (42.0 - s.maxAdvanceDeg) * smooth((r - 0.6) / 0.3)
        return (mbt - knock - s.safetyRetardDeg).coerceIn(5.0, max(5.0, cap))
    }

    fun deadtime(s: ZSpec, vbat: Double): Double =
        (s.deadtime14vMs + (14.0 - vbat) * s.deadtimeSlopeMsPerV).coerceAtLeast(0.3)

    private fun r50(x: Double): Double = ((x / 50.0).roundToInt() * 50.0).coerceIn(800.0, 14000.0)

    /** 16 bin RPM: titik kunci (idle, puncak torsi, VVA, redline) selalu ada, sisanya rapat di rpm rendah-menengah. */
    fun rpmBins(s: ZSpec): List<Double> {
        val idle = r50(s.idleRpm.toDouble())
        val red = r50(s.redlineRpm.toDouble())
        val keep = mutableSetOf(idle, red, r50(s.torquePeakRpm.toDouble()))
        s.vvaRpm?.let { keep.add(r50(it.toDouble())) }
        val bins = sortedSetOf<Double>()
        bins.addAll(keep)
        for (a in listOf(1500.0, 2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 0.9 * red)) {
            val v = r50(a)
            if (v > idle && v < red) bins.add(v)
        }
        while (bins.size > 16) {
            val l = bins.toList()
            val victim = (1 until l.size - 1)
                .filter { l[it] !in keep }
                .minByOrNull { l[it + 1] - l[it - 1] } ?: break
            bins.remove(l[victim])
        }
        while (bins.size < 16) {
            val l = bins.toList()
            val i = (0 until l.size - 1).maxByOrNull { l[it + 1] - l[it] } ?: break
            val mid = r50((l[i] + l[i + 1]) / 2)
            if (mid <= l[i] || mid >= l[i + 1]) break
            bins.add(mid)
        }
        return bins.toList()
    }

    fun generate(s: ZSpec): ZBasemap {
        s.validate()
        val rpm = rpmBins(s)
        check(rpm.size == 16) { "Gagal membuat 16 bin RPM (cek idle/puncak torsi/redline/VVA)" }
        val tps = TPS_BINS
        val reqFuel = reqFuelMs(s.displCc, s.stoich, s.injFlowCcMin)
        val warnings = mutableListOf<String>()

        val ratio = Array(16) { r -> DoubleArray(16) { c -> manifoldRatio(s, rpm[c], tps[r]) } }
        val veAir = Array(16) { r ->
            DoubleArray(16) { c -> (100 * veWot(s, rpm[c]) * ratio[r][c]).coerceIn(8.0, 125.0) }
        }
        for (r in 1 until 16) for (c in 0 until 16) veAir[r][c] = max(veAir[r][c], veAir[r - 1][c])

        val afr = Array(16) { r ->
            DoubleArray(16) { c -> (afrTarget(s, rpm[c], ratio[r][c], tps[r]) * 10).roundToInt() / 10.0 }
        }
        val adv = Array(16) { r ->
            DoubleArray(16) { c -> (advance(s, rpm[c], ratio[r][c]) * 2).roundToInt() / 2.0 }
        }
        val ve = Array(16) { r ->
            DoubleArray(16) { c ->
                val v = if (s.mode == FuelMode.B_LAMBDA) veAir[r][c] else veAir[r][c] * s.stoich / afr[r][c]
                v.coerceIn(8.0, 255.0).roundToInt().toDouble()
            }
        }

        // Duty injektor dan fraksi Stage 2
        val dead14 = deadtime(s, 14.0)
        var maxDuty = 0.0
        val stage2 = Array(16) { DoubleArray(16) }
        for (r in 0 until 16) for (c in 0 until 16) {
            val fuelMs = reqFuel * veAir[r][c] / 100.0 * s.stoich / afr[r][c]
            val period = 120000.0 / rpm[c]
            maxDuty = max(maxDuty, (fuelMs + dead14) / period * 100.0)
            if (s.injFlow2CcMin != null) {
                val maxPrimary = 0.70 * period - dead14
                val f2 = if (fuelMs > maxPrimary) (fuelMs - maxPrimary) / fuelMs else 0.0
                stage2[r][c] = if (f2 < 0.05) 0.0 else (min(f2, 0.9) * 100).roundToInt() / 100.0
            }
        }
        if (s.injFlow2CcMin == null && maxDuty > 85.0) {
            warnings += "Duty injektor maksimum ${maxDuty.roundToInt()}% (> 85%). Pakai injektor lebih besar atau Stage 2."
        }

        // Pemeriksaan kewajaran
        val wotLeanest = afr[15].maxOrNull() ?: 0.0
        if (wotLeanest > 0.90 * s.stoich) warnings += "AFR WOT terlalu kurus (${wotLeanest}); cek profil dan stoich."
        var maxAdvStep = 0.0
        for (r in 0 until 16) for (c in 1 until 16) maxAdvStep = max(maxAdvStep, abs(adv[r][c] - adv[r][c - 1]))
        for (r in 1 until 16) for (c in 0 until 16) maxAdvStep = max(maxAdvStep, abs(adv[r][c] - adv[r - 1][c]))
        if (maxAdvStep > 4.0) warnings += "Loncatan advance antar sel ${maxAdvStep} deg (> 4); periksa parameter."

        // Deadtime
        val dtV = listOf(8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0)
        val dtMs = dtV.map { ((deadtime(s, it) / 0.05).roundToInt() * 0.05) }

        // Dwell: baris = Vbatt, kolom = RPM
        val red = s.redlineRpm.toDouble()
        val dwRpm = listOf(1500.0, 0.35 * red, 0.70 * red, red).map { (it / 100.0).roundToInt() * 100.0 }
        val dwV = listOf(9.0, 11.0, 13.0, 15.0)
        val dwell = dwV.map { v ->
            dwRpm.map { n ->
                val hi = max(1.5, min(5.0, 0.7 * 60000.0 / n))
                val d = (s.dwell14vMs * (14.0 / v).pow(1.2)).coerceIn(1.5, hi)
                (d * 10).roundToInt() / 10.0
            }
        }

        // Cranking dan koreksi CLT
        val cltBins = listOf(-20.0, 0.0, 10.0, 20.0, 40.0, 60.0, 80.0, 100.0)
        val dt10 = deadtime(s, 10.0)
        val crankBase = reqFuel * 0.30 * s.stoich / 12.0
        val crank = cltBins.map { t ->
            val cold = 1 + 2.2 * ((20 - t) / 60).coerceIn(0.0, 1.0)
            (((crankBase * cold + dt10) * 10).roundToInt() / 10.0).coerceAtMost(25.5)
        }
        val corr = cltBins.map { t ->
            if (t >= 80) 100.0
            else (100 + 55 * ((80 - t) / 80).pow(1.3)).roundToInt().toDouble().coerceAtMost(255.0)
        }

        return ZBasemap(
            rpmBins = rpm,
            tpsBins = tps,
            ve = ve.map { it.toList() },
            afr = afr.map { it.toList() },
            advance = adv.map { it.toList() },
            stage2Pct = stage2.map { it.toList() },
            deadtimeVbat = dtV,
            deadtimeMs = dtMs,
            dwellRpm = dwRpm,
            dwellVbat = dwV,
            dwellMs = dwell,
            cltBins = cltBins,
            crankPwMs = crank,
            cltCorrPct = corr,
            reqFuelMs = reqFuel,
            maxPrimaryDutyPct = maxDuty,
            warnings = warnings
        )
    }
}