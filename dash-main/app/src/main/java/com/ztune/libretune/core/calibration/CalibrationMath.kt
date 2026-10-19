package com.ztune.libretune.core.calibration

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * P1: Sensor calibration math engine.
 * Ported from LibreTune's calibration/mod.rs.
 */

const val ADC_COUNTS: Int = 1024
const val ADC_REFERENCE_VOLTS: Double = 5.0

fun adcToVolts(adc: Int): Double = adc.toDouble() / ADC_COUNTS * ADC_REFERENCE_VOLTS

/**
 * Steinhart-Hart thermistor fit for NTC sensors (CLT, IAT).
 * 1/T = A + B·ln(R) + C·(ln(R))³
 */
data class ThermistorFit(val a: Double, val b: Double, val c: Double, val biasResistor: Double) {
    fun temperatureAtResistance(r: Double): Double {
        if (r <= 0) return Double.NaN
        val lnR = ln(r)
        val invT = a + b * lnR + c * lnR.pow(3)
        return 1.0 / invT - 273.15
    }
    fun resistanceAtAdc(adc: Int): Double {
        val v = adcToVolts(adc)
        if (v >= ADC_REFERENCE_VOLTS || v <= 0) return Double.NaN
        return biasResistor * v / (ADC_REFERENCE_VOLTS - v)
    }
    fun temperatureAtAdc(adc: Int): Double = temperatureAtResistance(resistanceAtAdc(adc))
    fun sampleAtBins(bins: IntArray): DoubleArray = DoubleArray(kotlin.math.min(bins.size, 32)) { i -> temperatureAtAdc(bins[i]) }

    companion object {
        fun fromPoints(biasResistor: Double, points: List<Pair<Double, Double>>): ThermistorFit? {
            if (points.size < 3) return null
            val (t1, r1) = points[0]; val (t2, r2) = points[1]; val (t3, r3) = points[2]
            if (r1 <= 0 || r2 <= 0 || r3 <= 0) return null
            val k1 = t1 + 273.15; val k2 = t2 + 273.15; val k3 = t3 + 273.15
            val l1 = ln(r1); val l2 = ln(r2); val l3 = ln(r3)
            val cross = (l2 - l1) * (k3 - k1) - (l3 - l1) * (k2 - k1)
            if (abs(cross) < 1e-10) return null
            val y1 = 1.0/k1; val y2 = 1.0/k2; val y3 = 1.0/k3
            val g2 = (y2 - y1) / (l2 - l1); val g3 = (y3 - y1) / (l3 - l1)
            val c = (g3 - g2) / ((l3 - l2) * (l3 - l1))
            if (c.isNaN() || c.isInfinite()) return null
            val b = g2 - c * (l1*l1 + l1*l2 + l2*l2)
            val a = y1 - (b + l1*l1*c) * l1
            return ThermistorFit(a, b, c, biasResistor)
        }
        fun fromBeta(beta: Double, biasResistor: Double, refTempC: Double = 25.0, refResistance: Double = 2500.0): ThermistorFit {
            val refTempK = refTempC + 273.15
            return ThermistorFit(1.0/refTempK - (1.0/beta)*ln(refResistance), 1.0/beta, 0.0, biasResistor)
        }
    }
}

data class LinearWideband(val point1: Pair<Double, Double>, val point2: Pair<Double, Double>) {
    val slope = if (abs(point2.first - point1.first) < 1e-6) 0.0 else (point2.second - point1.second) / (point2.first - point1.first)
    val intercept = point1.second - slope * point1.first
    fun afrAtVolts(v: Double) = slope * v + intercept
    fun afrAtAdc(adc: Int) = afrAtVolts(adcToVolts(adc))
    fun curve() = DoubleArray(ADC_COUNTS) { adc -> afrAtAdc(adc) }
    companion object {
        val Innovate_LC1 = LinearWideband(0.0 to 7.35, 5.0 to 22.39)
        val AEM_30_4100 = LinearWideband(0.1 to 10.0, 4.9 to 20.0)
        val _14Point7 = LinearWideband(0.0 to 9.0, 5.0 to 19.0)
    }
}

data class AfrCorrection(val gain: Double = 1.0, val offset: Double = 0.0) {
    fun apply(afr: Double) = gain * afr + offset
    companion object {
        fun fromOnePoint(measured: Double, expected: Double) = AfrCorrection(1.0, expected - measured)
        fun fromTwoPoints(m1: Double, e1: Double, m2: Double, e2: Double): AfrCorrection? {
            if (abs(m2 - m1) < 1e-6) return null
            val gain = (e2 - e1) / (m2 - m1)
            if (gain <= 0) return null
            return AfrCorrection(gain, e1 - gain * m1)
        }
    }
}
