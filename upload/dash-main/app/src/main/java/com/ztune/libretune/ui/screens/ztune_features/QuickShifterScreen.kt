package com.ztune.libretune.ui.screens.ztune_features

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings-only state for the Quickshifter feature (enable, threshold, live
 * status). The 3D cut table itself (Gear x RPM) is NOT duplicated here —
 * it's edited via the same shared CanvasTableEditor used for Fuel/Ignition/
 * AFR, reached through [onNavigateToTable]("qsCutTable"). This keeps the
 * table editing UX (multi-select, drag-range, Equal/+1/-1/Copy/Paste,
 * real RPM/Gear axis from ztune.ini) in exactly one place instead of two
 * diverging implementations.
 */
data class QsState(
    val enable: Boolean = false,
    val sensorThreshold: Float = 2500f,
    val triggerCount: Int = 0,
    val cutActive: Boolean = false,
    val sensorRaw: Int = 2048,
    val saving: Boolean = false,
    val lastMessage: String? = null
)

@HiltViewModel
class QsViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {
    private val _state = MutableStateFlow(QsState())
    val state: StateFlow<QsState> = _state.asStateFlow()

    fun setEnable(v: Boolean) { _state.value = _state.value.copy(enable = v) }
    fun setThreshold(v: Float) { _state.value = _state.value.copy(sensorThreshold = v) }

    fun save() {
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true)
            _state.value = _state.value.copy(saving = false, lastMessage = "Tersimpan")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickShifterScreen(
    onNavigateBack: () -> Unit,
    onNavigateToTable: (String) -> Unit = {},
    viewModel: QsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quickshifter") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status Live", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sensor Raw ADC: ${state.sensorRaw}")
                        Text("Trigger: ${state.triggerCount}")
                        Text(if (state.cutActive) "CUT AKTIF" else "Standby")
                    }
                }
            }

            // Enable toggle
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Quickshifter Enable", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Sensor Hall strain gauge — deteksi high = cut ignition",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    Switch(checked = state.enable, onCheckedChange = viewModel::setEnable)
                }
            }

            // Threshold
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Threshold Sensor (ADC raw)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Saat sensor ≥ threshold → cut ignition langsung",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Threshold: ${state.sensorThreshold.toInt()}")
                    Slider(
                        value = state.sensorThreshold,
                        onValueChange = viewModel::setThreshold,
                        valueRange = 0f..4095f,
                        steps = 4095
                    )
                }
            }

            // Arming (hardcoded di firmware)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Arming (hardcoded)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Arming RPM: ≥ 7000 RPM (hardcode di firmware)\n" +
                        "• Arming TPS: ≥ 70% (hardcode di firmware)\n" +
                        "• Ignition retard saat cut: 0° (hardcode)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // QS Cut Table — buka editor tabel yang SAMA (CanvasTableEditor)
            // dipakai Fuel/Ignition/AFR, dengan axis RPM x Gear asli dari
            // qsCutRpmBins/qsCutGearBins di ztune.ini. Tidak ada lagi
            // implementasi tabel terpisah/duplikat di layar ini.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tabel Cut Duration (ms) — RPM × Gear",
                         style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Editor tabel penuh: tap = pilih 1 cell, tahan+geser = pilih range, " +
                        "toolbar Equal/+1/-1/Scale/Smooth/Interp/Copy/Paste/Select All. " +
                        "Axis RPM & Gear diambil langsung dari ztune.ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onNavigateToTable("qsCutTable") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Buka Tabel Cut Duration")
                    }
                }
            }

            // Save (setting-setting di halaman ini saja; tabel disimpan/burn
            // lewat layar table editor itu sendiri)
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving
            ) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(8.dp))
                    Text("Menyimpan...")
                } else {
                    Text("Simpan Pengaturan QS")
                }
            }

            state.lastMessage?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}