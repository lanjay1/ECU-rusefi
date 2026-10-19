package com.ztune.libretune.ui.screens.ztune_features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
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

data class CmpState(
    val enabled: Boolean = false,
    val edge: Int = 0,
    val angleDeg: Float = 0f,
    val camSynced: Boolean = false,
    val eventCount: Int = 0,
    val saving: Boolean = false,
    val lastMessage: String? = null
)

/**
 * v1.3.5 — sebelumnya save() cuma ubah state lokal (audit finding #5).
 * Sekarang baca/tulis constant `cmpEnable`/`cmpEdge`/`cmpAngleDeg` beneran
 * dari/ke tune aktif via [TuneManager].
 */
@HiltViewModel
class CmpViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager
) : ViewModel() {
    private val _state = MutableStateFlow(CmpState())
    val state: StateFlow<CmpState> = _state.asStateFlow()

    init {
        val enabled = tuneManager.getConstantValue("cmpEnable")
        val edge = tuneManager.getConstantValue("cmpEdge")
        val angle = tuneManager.getConstantValue("cmpAngleDeg")
        _state.value = _state.value.copy(
            enabled = enabled?.let { it != 0.0 } ?: _state.value.enabled,
            edge = edge?.toInt() ?: _state.value.edge,
            angleDeg = angle?.toFloat() ?: _state.value.angleDeg
        )
    }

    fun setEnabled(v: Boolean) { _state.value = _state.value.copy(enabled = v) }
    fun setEdge(v: Int) { _state.value = _state.value.copy(edge = v) }
    fun setAngle(v: Float) { _state.value = _state.value.copy(angleDeg = v) }

    fun save() {
        if (tuneManager.activeDefinition == null || tuneManager.currentTune == null) {
            _state.value = _state.value.copy(lastMessage = "Belum ada tune/ECU aktif — tidak bisa simpan")
            return
        }
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(saving = true, lastMessage = null)
            runCatching {
                tuneManager.updateConstant("cmpEnable", if (s.enabled) 1.0 else 0.0)
                tuneManager.updateConstant("cmpEdge", s.edge.toDouble())
                tuneManager.updateConstant("cmpAngleDeg", s.angleDeg.toDouble())
            }.onSuccess {
                _state.value = _state.value.copy(saving = false, lastMessage = "Tersimpan ke tune")
            }.onFailure { e ->
                _state.value = _state.value.copy(saving = false, lastMessage = "Gagal simpan: ${e.message}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CmpScreen(
    onNavigateBack: () -> Unit,
    viewModel: CmpViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cam Sensor (CMP)") },
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Status CMP", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = if (state.camSynced) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp)
                        ) {}
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.camSynced) "Tersinkron" else "Belum tersinkron")
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Event count: ${state.eventCount}", style = MaterialTheme.typography.bodySmall)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Aktifkan Cam Sensor", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Bila dimatikan, ECU pakai mode CKP-only non-sequential 360° (wasted spark, dual-fire).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("CMP Enable")
                        Switch(checked = state.enabled, onCheckedChange = viewModel::setEnabled)
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Edge Trigger", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        FilterChip(
                            selected = state.edge == 0,
                            onClick = { viewModel.setEdge(0) },
                            label = { Text("FALLING") }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = state.edge == 1,
                            onClick = { viewModel.setEdge(1) },
                            label = { Text("RISING") }
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Posisi Cam Event", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Sudut: %.1f°".format(state.angleDeg),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = state.angleDeg,
                        onValueChange = viewModel::setAngle,
                        valueRange = 0f..720f,
                        steps = 719
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0°", style = MaterialTheme.typography.bodySmall)
                        Text("360°", style = MaterialTheme.typography.bodySmall)
                        Text("720°", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

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