package com.ztune.libretune.ui.screens.ztune_features

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.ecu.EcuInterface
import com.ztune.libretune.core.ecu.RealtimeUpdate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * v1.3.0 — ViewModel untuk InstrumentClusterGrid (9 indikator gaya UMA M9 Racing).
 *
 * Subscribe ke EcuConnectionManager.ecuInterface.realtimeUpdates untuk update
 * nilai indikator secara real-time. Semua field nullable — null berarti
 * "belum ada data" (belum konek / channel belum pernah update), BUKAN angka
 * default yang dipajang seolah-olah nilai asli. UI (InstrumentClusterGrid)
 * menampilkan "--" untuk nilai null.
 *
 * v1.3.0 — dua indikator dihapus karena channel-nya memang tidak ada di
 * ztune.ini [OutputChannels] (map/vss tidak pernah dikirim firmware, jadi
 * sebelumnya selalu nampilin angka statis 100 kPa / 0 km/h yang terlihat
 * seperti data asli padahal bukan):
 *   - BOOST (kPa)     → dihapus, motor NA, tidak ada sensor MAP
 *   - GPS SPEED       → dihapus, tidak ada channel kecepatan di firmware
 * ENGINE TEMP juga dihapus karena cuma duplikat CLT (channel yang sama).
 * Diganti dengan 3 channel yang benar-benar ada dan relevan untuk tuning:
 *   - AFR TARGET (afrTarget) — pembanding langsung ke AFR aktual
 *   - TPS (tps)               — posisi throttle live
 *   - KNOCK (knockDb)         — indikator keamanan mesin real-time
 */
data class InstrumentState(
    val afr: Float? = null,
    val afrTarget: Float? = null,
    val clt: Float? = null,
    val iat: Float? = null,
    val sparkAdvance: Float? = null,
    val rpm: Int? = null,
    val vbatt: Float? = null,
    val tps: Float? = null,
    val knockDb: Float? = null
)

@HiltViewModel
class InstrumentClusterViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {

    private val _state = MutableStateFlow(InstrumentState())
    val state: StateFlow<InstrumentState> = _state.asStateFlow()

    private var realtimeJob: Job? = null
    private var connectionJob: Job? = null

    init {
        // Observe connection state — start/stop realtime subscription
        connectionJob = viewModelScope.launch {
            connectionManager.state.collect { connState ->
                val ecu = connectionManager.ecuInterface
                if (ecu != null && ecu.isConnected) {
                    startRealtime(ecu)
                } else {
                    stopRealtime()
                    // Belum konek — semua nilai null ("--" di UI), bukan angka karangan.
                    _state.value = InstrumentState()
                }
            }
        }
    }

    private fun startRealtime(ecu: EcuInterface) {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            ecu.realtimeUpdates.collect { update ->
                applyRealtime(update)
            }
        }
    }

    private fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
    }

    private fun applyRealtime(update: RealtimeUpdate) {
        // channelValues: Map<String, Double> — nama channel INI (sesuai
        // [OutputChannels] di ztune.ini) ke nilai yang sudah di-scale.
        val v = update.channelValues
        if (v.isEmpty()) return

        _state.value = InstrumentState(
            afr = v["o2Afr"]?.toFloat(),
            afrTarget = v["afrTarget"]?.toFloat(),
            clt = v["clt"]?.toFloat(),
            iat = v["iat"]?.toFloat(),
            sparkAdvance = v["advance"]?.toFloat(),
            rpm = v["rpm"]?.toInt(),
            vbatt = v["vbatt"]?.toFloat(),
            tps = v["tps"]?.toFloat(),
            knockDb = v["knockDb"]?.toFloat()
        )
    }

    override fun onCleared() {
        super.onCleared()
        realtimeJob?.cancel()
        connectionJob?.cancel()
    }
}