package com.ztune.libretune.ui.screens.ztune_features

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.ecu.ZtuneEcu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class KnockState(
    val enable: Boolean = false,
    val levelDb: Float = -100f,
    val floorDb: Float = -100f,
    val thresholdDb: Float = 0f,
    val freqHz: Float = 9500f,
    val retardDeg: Float = 0f,
    val knockCount: Int = 0,
    val dumping: Boolean = false,
    val lastDumpMessage: String? = null,
    val historyDb: List<Float> = emptyList(),
    val historyRetard: List<Float> = emptyList(),
    val historyCount: List<Float> = emptyList()
)

@HiltViewModel
class KnockViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {
    private val _state = MutableStateFlow(KnockState())
    val state: StateFlow<KnockState> = _state.asStateFlow()

    fun dumpBurst() {
        viewModelScope.launch {
            _state.value = _state.value.copy(dumping = true)
            try {
                val ecu = connectionManager.ecuInterface
                if (ecu is ZtuneEcu) {
                    val result = ecu.sendControllerCommand("knockDump", "knockDump", 0)
                    _state.value = _state.value.copy(
                        dumping = false,
                        lastDumpMessage = result.fold(
                            onSuccess = { "Dump OK: ${it.size} bytes" },
                            onFailure = { "Gagal: ${it.message}" }
                        )
                    )
                } else {
                    _state.value = _state.value.copy(
                        dumping = false,
                        lastDumpMessage = "ECU bukan ZtuneEcu"
                    )
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    dumping = false,
                    lastDumpMessage = "Error: ${e.message}"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnockDashboardScreen(
    onNavigateBack: () -> Unit,
    viewModel: KnockViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dashboard Ketukan") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::dumpBurst, enabled = !state.dumping) {
                        Icon(Icons.Default.Refresh, contentDescription = "Dump Burst")
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GaugeCard("Level (dB)", "%.1f".format(state.levelDb), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
                GaugeCard("Floor (dB)", "%.1f".format(state.floorDb), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
                GaugeCard("Threshold (dB)", "%.1f".format(state.thresholdDb), MaterialTheme.colorScheme.secondary, Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GaugeCard("Retard (°)", "%.2f".format(state.retardDeg), MaterialTheme.colorScheme.error, Modifier.weight(1f))
                GaugeCard("Knock Count", "${state.knockCount}", MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Frekuensi Resonansi", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "%.0f Hz (%.1f kHz)".format(state.freqHz, state.freqHz / 1000f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            SparklineCard(
                title = "Riwayat Level (30 dtk)",
                data = state.historyDb,
                color = MaterialTheme.colorScheme.primary,
                yMin = -100f,
                yMax = 0f
            )

            SparklineCard(
                title = "Riwayat Retard",
                data = state.historyRetard,
                color = MaterialTheme.colorScheme.error,
                yMin = 0f,
                yMax = 20f
            )

            SparklineCard(
                title = "Riwayat Count",
                data = state.historyCount,
                color = MaterialTheme.colorScheme.tertiary,
                yMin = 0f,
                yMax = state.historyCount.maxOrNull()?.coerceAtLeast(1f) ?: 1f
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::dumpBurst,
                    enabled = !state.dumping,
                    modifier = Modifier.weight(1f)
                ) {
                    if (state.dumping) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Dumping...")
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Dump Burst")
                    }
                }
                OutlinedButton(
                    onClick = { /* TODO: export CSV */ },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Export CSV")
                }
            }

            state.lastDumpMessage?.let { msg ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(msg, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun GaugeCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
private fun SparklineCard(
    title: String,
    data: List<Float>,
    color: Color,
    yMin: Float,
    yMax: Float
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
            ) {
                if (data.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val range = (yMax - yMin).coerceAtLeast(0.1f)
                val path = Path()
                data.forEachIndexed { i, v ->
                    val x = (i.toFloat() / (data.size - 1)) * w
                    val y = h - ((v - yMin) / range) * h
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = color, style = Stroke(width = 2f))
            }
        }
    }
}