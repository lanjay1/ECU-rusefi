package com.ztune.libretune.ui.screens.ztune_features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.TuneManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class VvaState(
    val enabled: Boolean = false,
    val dual: Boolean = false,
    val activationRpm: Float = 5000f,
    val deactivationRpm: Float = 4500f,
    val active: Boolean = false,
    val saving: Boolean = false,
    val lastMessage: String? = null
)

/**
 * v1.3.5 — sebelumnya `mode` dihitung tapi tidak pernah ditulis ke mana pun
 * (audit finding #5) — tombol "Simpan" cuma menampilkan pesan berisi nilai
 * yang baru saja dibuang. Sekarang benar-benar menulis `vvaMode`/
 * `vvaActivationRpm`/`vvaDeactivationRpm` ke tune aktif via [TuneManager].
 */
@HiltViewModel
class VvaViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager
) : ViewModel() {
    private val _state = MutableStateFlow(VvaState())
    val state: StateFlow<VvaState> = _state.asStateFlow()

    init {
        val mode = tuneManager.getConstantValue("vvaMode")?.toInt()
        val actRpm = tuneManager.getConstantValue("vvaActivationRpm")
        val deactRpm = tuneManager.getConstantValue("vvaDeactivationRpm")
        _state.value = _state.value.copy(
            enabled = mode?.let { it != 0 } ?: _state.value.enabled,
            dual = mode?.let { it == 2 } ?: _state.value.dual,
            activationRpm = actRpm?.toFloat() ?: _state.value.activationRpm,
            deactivationRpm = deactRpm?.toFloat() ?: _state.value.deactivationRpm
        )
    }

    fun setEnabled(v: Boolean) { _state.value = _state.value.copy(enabled = v) }
    fun setDual(v: Boolean) { _state.value = _state.value.copy(dual = v) }
    fun setActivationRpm(v: Float) { _state.value = _state.value.copy(activationRpm = v) }
    fun setDeactivationRpm(v: Float) {
        val act = _state.value.activationRpm
        _state.value = _state.value.copy(deactivationRpm = v.coerceAtMost(act - 100f))
    }

    fun save() {
        if (tuneManager.activeDefinition == null || tuneManager.currentTune == null) {
            _state.value = _state.value.copy(lastMessage = "Belum ada tune/ECU aktif — tidak bisa simpan")
            return
        }
        val s = _state.value
        val mode = when {
            !s.enabled -> 0
            s.dual -> 2
            else -> 1
        }
        viewModelScope.launch {
            _state.value = s.copy(saving = true, lastMessage = null)
            runCatching {
                tuneManager.updateConstant("vvaMode", mode.toDouble())
                tuneManager.updateConstant("vvaActivationRpm", s.activationRpm.toDouble())
                tuneManager.updateConstant("vvaDeactivationRpm", s.deactivationRpm.toDouble())
            }.onSuccess {
                _state.value = _state.value.copy(saving = false, lastMessage = "Mode=$mode tersimpan ke tune")
            }.onFailure { e ->
                _state.value = _state.value.copy(saving = false, lastMessage = "Gagal simpan: ${e.message}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VvaScreen(
    onNavigateBack: () -> Unit,
    viewModel: VvaViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VVA (Single/Dual)") },
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
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("VVA Status", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (state.active) "AKTIF" else "OFF",
                        fontWeight = FontWeight.Bold,
                        color = if (state.active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Toggle ON/OFF
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("VVA Enable", style = MaterialTheme.typography.titleSmall)
                        Text("Matikan bila motor std tanpa VVA — solenoid dimatikan.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                }
            }

            // Nested toggle Dual
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode VVA", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dual VVA (Intake + Exhaust)")
                            Text(
                                "OFF = single intake only. ON = keduanya aktif.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = state.dual,
                            onCheckedChange = viewModel::setDual,
                            enabled = state.enabled
                        )
                    }
                }
            }

            // Kondisi aktivasi (RPM saja)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Kondisi Aktivasi (RPM)", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Activation RPM: ${state.activationRpm.toInt()}")
                    Slider(
                        value = state.activationRpm,
                        onValueChange = viewModel::setActivationRpm,
                        valueRange = 0f..15000f,
                        enabled = state.enabled
                    )
                    Text("Deactivation RPM (hysteresis): ${state.deactivationRpm.toInt()}")
                    Slider(
                        value = state.deactivationRpm,
                        onValueChange = viewModel::setDeactivationRpm,
                        valueRange = 0f..15000f,
                        enabled = state.enabled
                    )
                }
            }

            // Save
            Button(
                onClick = viewModel::save,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.saving
            ) {
                if (state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Menyimpan...")
                } else {
                    Text("Simpan ke ECU")
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