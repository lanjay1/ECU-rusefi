package com.ztune.libretune.ui.screens.ztune_features

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
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

data class InjStage2State(
    val enabled: Boolean = false,
    val flowCcMin: Float = 350f,
    val deadtimeVbatt: List<Float> = listOf(6f, 8f, 10f, 12f, 13.8f, 15f, 16f, 16f),
    val deadtimeMs: List<Float> = listOf(2.6f, 1.9f, 1.45f, 1.15f, 1.0f, 0.9f, 0.85f, 0.85f),
    val fracRpm: List<Float> = (0..15).map { 800f + it * 580f },
    val fracLoad: List<Float> = (0..15).map { it * 6.25f },
    val fracTable: List<List<Float>> = List(16) { List(16) { 0f } },
    val saving: Boolean = false,
    val lastMessage: String? = null
)

/**
 * v1.3.5 — sebelumnya save() cuma ubah state lokal, `enabled` bahkan tidak
 * pernah ditulis ke field manapun sama sekali (audit finding #5). Sekarang
 * menulis `injMode`/`inj2FlowCcMin` (constant), `inj2DtMsCurve` (curve),
 * dan `inj2FracTable` (table 16x16) beneran ke tune aktif via [TuneManager].
 */
@HiltViewModel
class InjStage2ViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager
) : ViewModel() {
    private val _state = MutableStateFlow(InjStage2State())
    val state: StateFlow<InjStage2State> = _state.asStateFlow()

    init {
        val mode = tuneManager.getConstantValue("injMode")?.toInt()
        val flow = tuneManager.getConstantValue("inj2FlowCcMin")
        val curve = tuneManager.currentTune?.curveValues?.get("inj2DtMsCurve")
        val vbattBins = tuneManager.currentTune?.curveValues?.get("inj2DtVbattBins")
        val fracTable = tuneManager.currentTune?.tableValues?.get("inj2FracTable")
        _state.value = _state.value.copy(
            enabled = mode?.let { it == 1 } ?: _state.value.enabled,
            flowCcMin = flow?.toFloat() ?: _state.value.flowCcMin,
            deadtimeMs = curve?.map { it.toFloat() } ?: _state.value.deadtimeMs,
            deadtimeVbatt = vbattBins?.map { it.toFloat() } ?: _state.value.deadtimeVbatt,
            fracTable = fracTable?.map { row -> row.map { it.toFloat() } } ?: _state.value.fracTable
        )
    }

    fun setEnabled(v: Boolean) { _state.value = _state.value.copy(enabled = v) }
    fun setFlow(v: Float) { _state.value = _state.value.copy(flowCcMin = v) }
    fun setDeadtimeMs(idx: Int, v: Float) {
        val newMs = _state.value.deadtimeMs.mapIndexed { i, x -> if (i == idx) v else x }
        _state.value = _state.value.copy(deadtimeMs = newMs)
    }
    fun setFracCell(loadIdx: Int, rpmIdx: Int, v: Float) {
        val newTable = _state.value.fracTable.mapIndexed { i, row ->
            if (i == loadIdx) row.mapIndexed { j, x -> if (j == rpmIdx) v else x } else row
        }
        _state.value = _state.value.copy(fracTable = newTable)
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
                tuneManager.updateConstant("injMode", if (s.enabled) 1.0 else 0.0)
                tuneManager.updateConstant("inj2FlowCcMin", s.flowCcMin.toDouble())
                s.deadtimeMs.forEachIndexed { idx, v ->
                    tuneManager.updateCurveValue("inj2DtMsCurve", idx, v.toDouble())
                }
                for (row in s.fracTable.indices) {
                    for (col in s.fracTable[row].indices) {
                        tuneManager.updateTableCell("inj2FracTable", row, col, s.fracTable[row][col].toDouble())
                    }
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
fun InjectorStage2Screen(
    onNavigateBack: () -> Unit,
    viewModel: InjStage2ViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Injektor Stage 2") },
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
                    Text("Injektor Stage 2 (Secondary)", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text("Pakai untuk racing dengan dual injector. Aktifkan mode DUAL_STAGE di menu Injektor Primary.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.outline)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Flow Rate Stage 2", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "%.0f cc/min".format(state.flowCcMin),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Slider(
                        value = state.flowCcMin,
                        onValueChange = viewModel::setFlow,
                        valueRange = 50f..5000f,
                        steps = 4950
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Kurva Deadtime Stage 2 (ms vs VBatt)",
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Tabel Fraksi Stage 2 (0..1) — Load × RPM",
                         style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "0 = primary only, 1 = secondary only, 0.5 = split 50/50.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(8.dp))
                    FracTableView(
                        fracTable = state.fracTable,
                        loadBins = state.fracLoad,
                        rpmBins = state.fracRpm
                    )
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

@Composable
private fun FracTableView(
    fracTable: List<List<Float>>,
    loadBins: List<Float>,
    rpmBins: List<Float>
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.width(36.dp))
            rpmBins.forEach { rpm ->
                Text(
                    text = "${rpm.toInt()}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        fracTable.forEachIndexed { loadIdx, row ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "L${loadBins.getOrNull(loadIdx)?.toInt() ?: loadIdx}",
                    modifier = Modifier.width(36.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
                row.forEach { value ->
                    val intensity = value.coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .background(
                                Color(
                                    red = 0f,
                                    green = intensity,
                                    blue = 1f - intensity,
                                    alpha = 0.4f + intensity * 0.5f
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "%.2f".format(value),
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap cell untuk edit (TODO: dialog edit)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}