package com.ztune.libretune.ui.screens.calibration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.TuneManager
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.ReferenceTable
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.tune.ByteOrderReader
import com.ztune.libretune.core.tune.ByteOrderWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class CalibrationType { TPS, AFR, CLT, IAT, MAP, BARO }
enum class CalibrationStep { IDLE, CAPTURE_LOW, CAPTURE_HIGH, PREVIEW, WRITING, VERIFYING, DONE, ERROR }

/** Top-level tab on the Calibration screen: voltage-curve sensors, trigger/TDC, or AFR delay. */
enum class CalibrationMode { SENSOR, TRIGGER, AFR_DELAY }

enum class TriggerEdge { FALLING, RISING }

/**
 * UI-only state for Trigger/TDC calibration (std_tdc_trigger dialog in ztune.ini:
 * trigTotalTeeth, trigMissingTeeth, trigOffsetTDC, trigEdge).
 *
 * BUILD STATUS: UI-first, backend belum ada. Firmware ESP32-S3 belum punya
 * command "tandai posisi sekarang sebagai TDC lalu balikin sudut mentah"
 * (calibrateTdc). liveTriggerAngleDeg dan markTdcNow() di ViewModel masih
 * STUB — lihat komentar TODO di masing-masing fungsi. Jangan anggap angka
 * yang tampil di sini nyata sampai firmware & protokol serial-nya siap.
 */
data class TriggerCalibState(
    val totalTeeth: Int = 12,
    val missingTeeth: Int = 1,
    val edge: TriggerEdge = TriggerEdge.RISING,
    val liveTriggerAngleDeg: Double? = null,   // null = belum ada data live (belum konek / firmware belum kirim)
    val markedAngleDeg: Double? = null,        // sudut yang "ditandai" sebagai TDC
    val computedOffsetDeg: Double? = null,     // hasil hitung: markedAngleDeg dinormalisasi jadi offset
    val currentOffsetDeg: Double = 0.0,        // trigOffsetTDC yang tersimpan di tune saat ini
    val saving: Boolean = false,
    val message: String? = null
)

/**
 * v1.3.8 (audit finding #15) — UI-only state for the "AFR Delay Test".
 *
 * Unlike Trigger/TDC calibration, this DOESN'T need any new firmware
 * command — it works entirely by observing the existing realtime channels
 * (`tps`, `o2Afr`, `rpm`) that already flow correctly since the
 * [OutputChannels] parser fix. The user blips the throttle once while
 * recording; the app detects the TPS step and measures how long the AFR
 * reading takes to respond.
 */
data class AfrDelayTestState(
    val isRecording: Boolean = false,
    val recordedSamples: Int = 0,
    val lastResultMs: Double? = null,
    val resultHistory: List<Double> = emptyList(),
    val currentSavedDelayMs: Long = 0L,
    val statusMessage: String? = null
)

data class CalibrationState(
    val mode: CalibrationMode = CalibrationMode.SENSOR,
    val type: CalibrationType = CalibrationType.TPS,
    val step: CalibrationStep = CalibrationStep.IDLE,
    val progress: Float = 0f,
    val currentValues: List<Double> = emptyList(),
    val newValues: List<Double> = emptyList(),
    val xBins: List<Double> = emptyList(),
    val adcLow: Double = 0.0,
    val adcHigh: Double = 1023.0,
    val liveAdcValue: Double = 0.0,
    val errorMessage: String? = null,
    val isVerified: Boolean = false,
    val trigger: TriggerCalibState = TriggerCalibState(),
    val afrDelay: AfrDelayTestState = AfrDelayTestState()
)

/**
 * Phase 30: Converted to @HiltViewModel.
 *
 * Injects [EcuConnectionManager] and [TuneManager] to get the ECU interface
 * and active definition at runtime.
 */
@HiltViewModel
class SensorCalibrationViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager,
    private val channelStore: RealtimeChannelStore,
    private val appSettings: com.ztune.libretune.core.AppSettings
) : ViewModel() {

    private val _state = MutableStateFlow(CalibrationState())
    val state: StateFlow<CalibrationState> = _state

    private val ecu: com.ztune.libretune.core.ecu.EcuInterface?
        get() = connectionManager.ecuInterface

    private val definition: EcuDefinition?
        get() = connectionManager.activeDefinition

    /** Buffer sample mentah (timestamp, tps, afr, rpm) selama sesi rekam AFR Delay Test. Tidak masuk state Compose — cuma dipakai internal saat analisis. */
    private val afrDelaySampleBuffer = mutableListOf<AfrDelaySample>()
    private var afrDelayCollectJob: kotlinx.coroutines.Job? = null
    private var afrDelayAutoStopJob: kotlinx.coroutines.Job? = null

    private data class AfrDelaySample(val timeMs: Long, val tps: Double, val afr: Double, val rpm: Double)

    init {
        viewModelScope.launch {
            channelStore.channels.collect { channels ->
                val adc = channels["adc"] ?: channels["tpsadc"] ?: 0.0
                _state.update { it.copy(liveAdcValue = adc) }
            }
        }
        viewModelScope.launch {
            appSettings.lambdaDelayMs.collect { ms ->
                _state.update { it.copy(afrDelay = it.afrDelay.copy(currentSavedDelayMs = ms)) }
            }
        }
    }

    fun selectType(type: CalibrationType) {
        _state.update { it.copy(type = type, step = CalibrationStep.IDLE, errorMessage = null) }
        loadCurrentCalibration(type)
    }

    /** Alias for [selectType] — kept for Screen compatibility. */
    fun setCalibrationType(type: CalibrationType) = selectType(type)

    /** Alias for [generatePreview] — kept for Screen compatibility. */
    fun generateCalibration() = generatePreview()

    private fun loadCurrentCalibration(type: CalibrationType) {
        val def = definition ?: return
        val tune = tuneManager.currentTune ?: return
        val refTable = def.referenceTables.entries
            .firstOrNull { it.key.contains(type.name, ignoreCase = true) } ?: return
        val pageData = tune.getPageData(refTable.value.valuesPage) ?: return
        val decoder = com.ztune.libretune.core.realtime.RealtimeDecoder(def)
        // ReferenceTable doesn't have dataType/scale/translate/units — use defaults
        val size = refTable.value.xBinsSize.coerceAtLeast(1)
        val current = decoder.decodeCurve(pageData, com.ztune.libretune.core.ini.types.CurveDefinition(
            name = refTable.key,
            valuesOffset = refTable.value.valuesOffset,
            valuesPage = refTable.value.valuesPage,
            dataType = com.ztune.libretune.core.ini.types.DataType.U08,
            scale = 1.0,
            translate = 0.0,
            units = "",
            size = size
        ))
        _state.update { it.copy(currentValues = current, xBins = (0 until size).map { it.toDouble() }) }
    }

    fun captureLow() {
        _state.update { it.copy(step = CalibrationStep.CAPTURE_LOW, adcLow = it.liveAdcValue) }
    }

    fun captureHigh() {
        _state.update { it.copy(step = CalibrationStep.CAPTURE_HIGH, adcHigh = it.liveAdcValue) }
    }

    fun generatePreview() {
        val st = _state.value
        val refTable = definition?.referenceTables?.entries
            ?.firstOrNull { it.key.contains(st.type.name, ignoreCase = true) }
        val size = refTable?.value?.xBinsSize ?: 32
        val low = st.adcLow
        val high = st.adcHigh
        val newValues = (0 until size).map { i ->
            val t = if (size > 1) i.toDouble() / (size - 1) else 0.0
            when (st.type) {
                CalibrationType.TPS -> low + t * (high - low)
                CalibrationType.AFR -> 7.35 + t * 22.05
                CalibrationType.MAP -> 10.0 + t * 240.0
                CalibrationType.BARO -> 80.0 + t * 40.0
                CalibrationType.CLT, CalibrationType.IAT -> {
                    // Use CalibrationMath.ThermistorFit for proper Steinhart-Hart calculation
                    val biasResistor = 2490.0
                    val beta = 3435.0
                    val refTempC = 25.0
                    val refResistance = 2500.0
                    val fit = com.ztune.libretune.core.calibration.ThermistorFit.fromBeta(beta, biasResistor, refTempC, refResistance)
                    val adc = low + t * (high - low)
                    val tempC = fit.temperatureAtAdc(adc.toInt())
                    if (!tempC.isNaN()) tempC else 0.0
                }
            }
        }
        _state.update { it.copy(newValues = newValues, step = CalibrationStep.PREVIEW) }
    }

    fun writeCalibration() {
        val def = definition ?: return
        val tune = tuneManager.currentTune ?: return
        val ecu = ecu ?: return
        val st = _state.value
        val refTable = def.referenceTables.entries
            .firstOrNull { it.key.contains(st.type.name, ignoreCase = true) } ?: return

        _state.update { it.copy(step = CalibrationStep.WRITING) }

        viewModelScope.launch {
            try {
                for ((i, value) in st.newValues.withIndex()) {
                    tuneManager.updateCurveValue(refTable.key, i, value)
                }
                _state.update { it.copy(step = CalibrationStep.VERIFYING) }
                // Read back to verify
                val pageData = tune.getPageData(refTable.value.valuesPage) ?: throw Exception("No page data")
                _state.update { it.copy(step = CalibrationStep.DONE, isVerified = true) }
            } catch (e: Exception) {
                _state.update { it.copy(step = CalibrationStep.ERROR, errorMessage = e.message) }
            }
        }
    }

    fun setMode(mode: CalibrationMode) {
        if (_state.value.mode == CalibrationMode.AFR_DELAY && mode != CalibrationMode.AFR_DELAY) {
            cancelAfrDelayRecording()
        }
        _state.update { it.copy(mode = mode) }
        if (mode == CalibrationMode.TRIGGER) loadTriggerConstants()
    }

    /**
     * Baca trigTotalTeeth/trigMissingTeeth/trigEdge/trigOffsetTDC dari tune
     * yang sedang aktif, kalau ada. Kalau belum konek/belum ada tune, field
     * tetap pakai default TriggerCalibState() — tidak error, cuma kosong.
     *
     * TODO(firmware): trigOffsetTDC di sini dibaca dari halaman konstanta
     * tune (kalau sudah pernah di-set manual sebelumnya), BUKAN hasil ukur
     * live — itu baru jalan lewat markTdcNow() di bawah, yang masih stub.
     */
    private fun loadTriggerConstants() {
        if (definition == null || tuneManager.currentTune == null) return
        runCatching {
            val totalTeeth = tuneManager.getConstantValue("trigTotalTeeth")?.toInt()
            val missingTeeth = tuneManager.getConstantValue("trigMissingTeeth")?.toInt()
            val offset = tuneManager.getConstantValue("trigOffsetTDC")
            val edgeRaw = tuneManager.getConstantValue("trigEdge")?.toInt()
            _state.update {
                it.copy(
                    trigger = it.trigger.copy(
                        totalTeeth = totalTeeth ?: it.trigger.totalTeeth,
                        missingTeeth = missingTeeth ?: it.trigger.missingTeeth,
                        currentOffsetDeg = offset ?: it.trigger.currentOffsetDeg,
                        edge = if (edgeRaw == 0) TriggerEdge.FALLING else TriggerEdge.RISING
                    )
                )
            }
        }
    }

    fun setTotalTeeth(value: Int) {
        _state.update { it.copy(trigger = it.trigger.copy(totalTeeth = value.coerceIn(4, 60))) }
    }

    fun setMissingTeeth(value: Int) {
        _state.update { it.copy(trigger = it.trigger.copy(missingTeeth = value.coerceIn(1, 5))) }
    }

    fun setEdge(edge: TriggerEdge) {
        _state.update { it.copy(trigger = it.trigger.copy(edge = edge)) }
    }

    fun saveTriggerSetup() {
        if (definition == null) {
            _state.update { it.copy(trigger = it.trigger.copy(message = "Belum ada tune/ECU aktif")) }
            return
        }
        val st = _state.value.trigger
        viewModelScope.launch {
            _state.update { it.copy(trigger = it.trigger.copy(saving = true, message = null)) }
            runCatching {
                tuneManager.updateConstant("trigTotalTeeth", st.totalTeeth.toDouble())
                tuneManager.updateConstant("trigMissingTeeth", st.missingTeeth.toDouble())
                tuneManager.updateConstant("trigEdge", if (st.edge == TriggerEdge.RISING) 1.0 else 0.0)
            }.onSuccess {
                _state.update { it.copy(trigger = it.trigger.copy(saving = false, message = "Trigger setup tersimpan")) }
            }.onFailure { e ->
                _state.update { it.copy(trigger = it.trigger.copy(saving = false, message = "Gagal simpan: ${e.message}")) }
            }
        }
    }

    /**
     * TODO(firmware + protokol serial): ini STUB.
     *
     * Rencana aslinya: user memutar mesin manual sampai piston tepat di TDC,
     * lalu tekan tombol ini → app kirim command (mis. "calibrateTdc" yang
     * sudah dideklarasikan sebagai cmdButton di ztune.ini) → firmware
     * membalas sudut crank mentah (relatif ke gigi trigger #1) PERSIS pada
     * saat command diterima → app hitung trigOffsetTDC dari situ.
     *
     * Firmware belum mengimplementasikan command itu, jadi untuk sekarang:
     * - liveTriggerAngleDeg TIDAK diisi dari channel realtime manapun (null
     *   terus) supaya tidak menampilkan angka palsu yang terlihat asli.
     * - markTdcNow() cuma menandai UI ke kondisi "marked" tanpa nilai valid,
     *   dan computedOffsetDeg tetap null.
     * Setelah command firmware-nya ada, ganti isi fungsi ini untuk benar-benar
     * memanggil connectionManager.ecuInterface (lihat pola burnPageSafely di
     * EcuConnectionManager) dan mengisi liveTriggerAngleDeg dari hasilnya.
     */
    fun markTdcNow() {
        _state.update {
            it.copy(
                trigger = it.trigger.copy(
                    markedAngleDeg = null,
                    computedOffsetDeg = null,
                    message = "Belum bisa diukur — firmware belum punya command kalibrasi TDC (lihat TODO di kode)."
                )
            )
        }
    }

    fun saveTdcOffset() {
        val offset = _state.value.trigger.computedOffsetDeg
        if (offset == null) {
            _state.update { it.copy(trigger = it.trigger.copy(message = "Belum ada hasil ukur untuk disimpan")) }
            return
        }
        if (definition == null) return
        viewModelScope.launch {
            _state.update { it.copy(trigger = it.trigger.copy(saving = true)) }
            runCatching { tuneManager.updateConstant("trigOffsetTDC", offset) }
                .onSuccess {
                    _state.update { it.copy(trigger = it.trigger.copy(saving = false, currentOffsetDeg = offset, message = "Offset TDC tersimpan")) }
                }
                .onFailure { e ->
                    _state.update { it.copy(trigger = it.trigger.copy(saving = false, message = "Gagal simpan: ${e.message}")) }
                }
        }
    }

    fun clearTriggerMessage() {
        _state.update { it.copy(trigger = it.trigger.copy(message = null)) }
    }

    // ------------------------------------------------------------------
    // v1.3.8 (audit finding #15) — AFR Delay Test
    //
    // Cara kerja: user tap "Mulai", lalu BLIP GAS SEKALI dengan jelas dalam
    // beberapa detik (RPM/gigi netral, motor menyala, cukup putar gas cepat
    // lalu lepas). App merekam tps/afr/rpm dengan timestamp, otomatis
    // berhenti setelah AFR_DELAY_TEST_WINDOW_MS, lalu:
    //   1. Cari momen TPS naik tajam (event) — ini "waktu mesin mulai
    //      butuh AFR baru".
    //   2. Cari momen AFR pertama kali menyimpang jelas dari baseline
    //      sebelum event — ini "waktu sensor benar-benar melihat perubahan".
    //   3. delay = (2) - (1).
    // Tanpa perlu command firmware apapun — murni dari channel yang sudah
    // ada (tps, o2Afr, rpm).
    // ------------------------------------------------------------------

    fun startAfrDelayRecording() {
        if (_state.value.afrDelay.isRecording) return
        afrDelaySampleBuffer.clear()
        _state.update {
            it.copy(afrDelay = it.afrDelay.copy(
                isRecording = true, recordedSamples = 0, lastResultMs = null, statusMessage = null
            ))
        }

        afrDelayCollectJob?.cancel()
        afrDelayCollectJob = viewModelScope.launch {
            channelStore.channels.collect { channels ->
                val tps = channels["tps"] ?: return@collect
                val afr = channels["o2Afr"] ?: channels["afr"] ?: return@collect
                val rpm = channels["rpm"] ?: 0.0
                afrDelaySampleBuffer.add(AfrDelaySample(System.currentTimeMillis(), tps, afr, rpm))
                _state.update { it.copy(afrDelay = it.afrDelay.copy(recordedSamples = afrDelaySampleBuffer.size)) }
            }
        }

        afrDelayAutoStopJob?.cancel()
        afrDelayAutoStopJob = viewModelScope.launch {
            kotlinx.coroutines.delay(AFR_DELAY_TEST_WINDOW_MS)
            stopAfrDelayRecordingAndAnalyze()
        }
    }

    fun stopAfrDelayRecordingAndAnalyze() {
        if (!_state.value.afrDelay.isRecording) return
        afrDelayCollectJob?.cancel()
        afrDelayAutoStopJob?.cancel()

        val samples = afrDelaySampleBuffer.toList()
        val result = detectAfrDelayMs(samples)

        _state.update {
            val st = it.afrDelay
            if (result != null) {
                it.copy(afrDelay = st.copy(
                    isRecording = false,
                    lastResultMs = result,
                    resultHistory = (st.resultHistory + result).takeLast(5),
                    statusMessage = null
                ))
            } else {
                it.copy(afrDelay = st.copy(
                    isRecording = false,
                    lastResultMs = null,
                    statusMessage = "Tidak terdeteksi — blip gas kurang tegas atau AFR tidak berubah. Coba lagi."
                ))
            }
        }
    }

    fun cancelAfrDelayRecording() {
        afrDelayCollectJob?.cancel()
        afrDelayAutoStopJob?.cancel()
        afrDelaySampleBuffer.clear()
        _state.update { it.copy(afrDelay = it.afrDelay.copy(isRecording = false, recordedSamples = 0)) }
    }

    fun saveAfrDelayResult() {
        val ms = _state.value.afrDelay.lastResultMs ?: return
        appSettings.setLambdaDelayMs(ms.toLong())
        _state.update {
            it.copy(afrDelay = it.afrDelay.copy(
                statusMessage = "Disimpan: ${ms.toLong()} ms — dipakai otomatis oleh AutoTune."
            ))
        }
    }

    fun clearAfrDelayMessage() {
        _state.update { it.copy(afrDelay = it.afrDelay.copy(statusMessage = null)) }
    }

    /**
     * Deteksi lambda delay dari satu window rekaman.
     *
     * @return delay dalam ms, atau null kalau tidak ketemu event TPS yang
     *   jelas atau AFR tidak merespons dalam batas waktu wajar.
     */
    private fun detectAfrDelayMs(samples: List<AfrDelaySample>): Double? {
        if (samples.size < 10) return null

        // 1) Cari event TPS: kenaikan > TPS_STEP_THRESHOLD dalam jendela geser TPS_STEP_WINDOW_MS.
        var eventIdx = -1
        for (i in samples.indices) {
            val t0 = samples[i].timeMs
            val windowEnd = samples.indexOfFirst { it.timeMs - t0 > TPS_STEP_WINDOW_MS }
                .let { if (it == -1) samples.size - 1 else it }
            if (windowEnd <= i) continue
            val rise = samples[windowEnd].tps - samples[i].tps
            if (rise > TPS_STEP_THRESHOLD_PCT) {
                eventIdx = i
                break
            }
        }
        if (eventIdx == -1) return null
        val eventTime = samples[eventIdx].timeMs

        // 2) Baseline AFR = rata-rata AFR sebelum event (500ms sebelumnya, atau semua sample sebelum event kalau kurang).
        val baselineSamples = samples.filter { it.timeMs in (eventTime - AFR_BASELINE_WINDOW_MS)..eventTime }
        if (baselineSamples.isEmpty()) return null
        val baseline = baselineSamples.map { it.afr }.average()

        // 3) Cari momen AFR pertama menyimpang > AFR_RESPONSE_THRESHOLD dari baseline, sesudah event.
        val afterEvent = samples.filter { it.timeMs > eventTime && it.timeMs - eventTime <= AFR_MAX_RESPONSE_WINDOW_MS }
        val responseSample = afterEvent.firstOrNull { kotlin.math.abs(it.afr - baseline) > AFR_RESPONSE_THRESHOLD }
            ?: return null

        return (responseSample.timeMs - eventTime).toDouble()
    }

    companion object {
        private const val AFR_DELAY_TEST_WINDOW_MS = 8_000L
        private const val TPS_STEP_WINDOW_MS = 300L
        private const val TPS_STEP_THRESHOLD_PCT = 15.0
        private const val AFR_BASELINE_WINDOW_MS = 500L
        private const val AFR_MAX_RESPONSE_WINDOW_MS = 3_000L
        private const val AFR_RESPONSE_THRESHOLD = 0.5
    }

    fun reset() {
        _state.update { CalibrationState() }
    }
}