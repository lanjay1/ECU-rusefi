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

data class InjPrimaryState(
    val mode: Int = 0,
    val flowCcMin: Float = 200f,
    val deadtimeVbatt: List<Float> = listOf(6f, 8f, 10f, 12f, 13.8f, 15f, 16f, 16f),
    val deadtimeMs: List<Float> = listOf(2.6f, 1.9f, 1.45f, 1.15f, 1.0f, 0.9f, 0.85f, 0.85f),
    val saving: Boolean = false,
    val lastMessage: String? = null
)

/**
 * v1.3.5 — sebelumnya save() cuma ubah state lokal (audit finding #5).
 * Sekarang menulis `injMode`/`injFlowCcMin` (constant) dan `dtMsCurve`
 * (curve, 8 titik vs `dtVbattBins`) beneran ke tune aktif via [TuneManager].
 */
@HiltViewModel
class InjPrimaryViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager
) : ViewModel() {
    private val _state = MutableStateFlow(InjPrimaryState())
    val state: StateFlow<InjPrimaryState> = _state.asStateFlow()

    init {
        val mode = tuneManager.getConstantValue("injMode")?.toInt()
        val flow = tuneManager.getConstantValue("injFlowCcMin")
        val curve = tuneManager.currentTune?.curveValues?.get("dtMsCurve")
        val vbattBins = tuneManager.currentTune?.curveValues?.get("dtVbattBins")
        _state.value = _state.value.copy(
            mode = mode ?: _state.value.mode,
            flowCcMin = flow?.toFloat() ?: _state.value.flowCcMin,
            deadtimeMs = curve?.map { it.toFloat() } ?: _state.value.deadtimeMs,
            deadtimeVbatt = vbattBins?.map { it.toFloat() } ?: _state.value.deadtimeVbatt
        )
    }

    fun setMode(v: Int) { _state.value = _state.value.copy(mode = v) }
    fun setFlow(v: Float) { _state.value = _state.value.copy(flowCcMin = v) }
    fun setDeadtimeMs(idx: Int, v: Float) {
        val newMs = _state.value.deadtimeMs.mapIndexed { i, x -> if (i == idx) v else x }
        _state.value = _state.value.copy(deadtimeMs = newMs)
    }

    fun save() {
        if (tuneManager.activeDefinition == null || tuneManager.currentTune == null) {
            _state.value = _state.value.copy(lastMessage = "Belum ada tune/ECU aktif — tidak bisa simpan")
            return
        }
        val s = _state.value
        viewModelScope.launch {
            _state.value = s.copy(saving = true, lastMessage = null)
            runCatching {
                tuneManager.updateConstant("injMode", s.mode.toDouble())
                tuneManager.updateConstant("injFlowCcMin", s.flowCcMin.toDouble())
                s.deadtimeMs.forEachIndexed { idx, v ->
                    tuneManager.updateCurveValue("dtMsCurve", idx, v.toDouble())
                }
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
fun InjectorPrimaryScreen(
    onNavigateBack: () -> Unit,
    viewModel: InjPrimaryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Injektor Primary") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode Injektor", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Row {
                        FilterChip(
                            selected = state.mode == 0,
                            onClick = { viewModel.setMode(0) },
                            label = { Text("SINGLE") }
                        )
                        Spacer(Modifier.width(8.dp))
                        FilterChip(
                            selected = state.mode == 1,
                            onClick = { viewModel.setMode(1) },
                            label = { Text("DUAL STAGE") }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Bila DUAL STAGE dipilih, atur stage2 di menu 'Injektor Stage 2'.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Flow Rate Primary", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "%.0f cc/min".format(state.flowCcMin),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = state.flowCcMin,
                        onValueChange = viewModel::setFlow,
                        valueRange = 50f..3000f,
                        steps = 2950
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Kurva Deadtime Primary (ms vs VBatt)",
                         style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    state.deadtimeMs.forEachIndexed { i, ms ->
                        Text("VBatt %.1f V: %.2f ms".format(state.deadtimeVbatt[i], ms))
                        Slider(
                            value = ms,
                            onValueChange = { viewModel.setDeadtimeMs(i, it) },
                            valueRange = 0.5f..4f,
                            steps = 70
                        )
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