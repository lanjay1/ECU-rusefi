package com.ztune.libretune.ui.screens.ztune_features

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
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
import com.ztune.libretune.core.ecu.ZtuneEcu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// State per-aktuator
data class ActuatorTestState(
    val vvaOn: Boolean = false,
    val ewpOn: Boolean = false,
    val valvetronicOn: Boolean = false,
    val qsCutOn: Boolean = false,
    val injectorOn: Boolean = false,
    val coilOn: Boolean = false,
    val pumpOn: Boolean = false,
    val fanOn: Boolean = false,
    val busy: Boolean = false,
    val lastMessage: String? = null
)

@HiltViewModel
class ActuatorTestViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {
    private val _state = MutableStateFlow(ActuatorTestState())
    val state: StateFlow<ActuatorTestState> = _state.asStateFlow()

    private suspend fun send(actuator: String, on: Boolean, ms: Int = 0) {
        val ecu = connectionManager.ecuInterface
        if (ecu !is ZtuneEcu) {
            _state.value = _state.value.copy(lastMessage = "ECU bukan ZtuneEcu")
            return
        }
        val (template, value) = when (actuator) {
            "vva" -> "benchVva" to if (on) 1 else 0
            "ewp" -> "benchEwp" to if (on) 1 else 0
            "valvetronic" -> "benchValvetronic" to if (on) 1 else 0
            "qs_cut" -> "benchQsCut" to ms
            "injector" -> "benchInjector" to if (on) 1 else 0
            "coil" -> "benchCoil" to if (on) 1 else 0
            "pump" -> "benchPump" to if (on) 1 else 0
            "fan" -> "benchFan" to if (on) 1 else 0
            else -> return
        }
        val r = ecu.sendControllerCommand("bench$actuator", template, value)
        _state.value = _state.value.copy(
            lastMessage = r.fold(
                onSuccess = { "$actuator ${if (on || ms > 0) "AKTIF" else "OFF"} OK" },
                onFailure = { "$actuator gagal: ${it.message}" }
            )
        )
    }

    fun toggleVva() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            send("vva", !_state.value.vvaOn)
            _state.value = _state.value.copy(vvaOn = !_state.value.vvaOn, busy = false)
        }
    }
    fun toggleEwp() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            send("ewp", !_state.value.ewpOn)
            _state.value = _state.value.copy(ewpOn = !_state.value.ewpOn, busy = false)
        }
    }
    fun toggleValvetronic() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            send("valvetronic", !_state.value.valvetronicOn)
            _state.value = _state.value.copy(valvetronicOn = !_state.value.valvetronicOn, busy = false)
        }
    }
    fun triggerQsCut() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, qsCutOn = true)
            send("qs_cut", true, 50)
            _state.value = _state.value.copy(qsCutOn = false, busy = false)
        }
    }
    fun toggleInjector() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            send("injector", !_state.value.injectorOn)
            _state.value = _state.value.copy(injectorOn = !_state.value.injectorOn, busy = false)
        }
    }
    fun toggleCoil() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            send("coil", !_state.value.coilOn)
            _state.value = _state.value.copy(coilOn = !_state.value.coilOn, busy = false)
        }
    }
    fun togglePump() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            send("pump", !_state.value.pumpOn)
            _state.value = _state.value.copy(pumpOn = !_state.value.pumpOn, busy = false)
        }
    }
    fun toggleFan() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true)
            send("fan", !_state.value.fanOn)
            _state.value = _state.value.copy(fanOn = !_state.value.fanOn, busy = false)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActuatorTestScreen(
    onNavigateBack: () -> Unit,
    viewModel: ActuatorTestViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Troubleshooting Aktuator") },
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
            // Info
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Troubleshooting Semua Aktuator",
                         style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Tekan tombol untuk toggle aktuator ON/OFF (bench test mode). " +
                        "Aktuator tetap aktif selama tombol ditekan / toggle ON. " +
                        "Matikan sebelum keluar dari screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Grid 2x4 = 8 aktuator
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActuatorButton(
                    label = "VVA",
                    icon = Icons.Default.Settings,
                    on = state.vvaOn,
                    busy = state.busy,
                    onClick = viewModel::toggleVva,
                    modifier = Modifier.weight(1f)
                )
                ActuatorButton(
                    label = "EWP",
                    icon = Icons.Default.Build,
                    on = state.ewpOn,
                    busy = state.busy,
                    onClick = viewModel::toggleEwp,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActuatorButton(
                    label = "VALVETRONIC",
                    icon = Icons.Default.Settings,
                    on = state.valvetronicOn,
                    busy = state.busy,
                    onClick = viewModel::toggleValvetronic,
                    modifier = Modifier.weight(1f)
                )
                ActuatorButton(
                    label = "QS CUT (50ms)",
                    icon = Icons.Default.Bolt,
                    on = state.qsCutOn,
                    busy = state.busy,
                    onClick = viewModel::triggerQsCut,
                    modifier = Modifier.weight(1f),
                    momentary = true
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActuatorButton(
                    label = "INJEKTOR",
                    icon = Icons.Default.Bolt,
                    on = state.injectorOn,
                    busy = state.busy,
                    onClick = viewModel::toggleInjector,
                    modifier = Modifier.weight(1f)
                )
                ActuatorButton(
                    label = "COIL",
                    icon = Icons.Default.Bolt,
                    on = state.coilOn,
                    busy = state.busy,
                    onClick = viewModel::toggleCoil,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActuatorButton(
                    label = "PUMP",
                    icon = Icons.Default.PowerSettingsNew,
                    on = state.pumpOn,
                    busy = state.busy,
                    onClick = viewModel::togglePump,
                    modifier = Modifier.weight(1f)
                )
                ActuatorButton(
                    label = "FAN",
                    icon = Icons.Default.PowerSettingsNew,
                    on = state.fanOn,
                    busy = state.busy,
                    onClick = viewModel::toggleFan,
                    modifier = Modifier.weight(1f)
                )
            }

            // Last message
            state.lastMessage?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(16.dp))
                }
            }

            // Warning
            Card(modifier = Modifier.fillMaxWidth(),
                 colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.width(8.dp))
                        Text("PERINGATAN", fontWeight = FontWeight.Bold,
                             color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• Jangan aktifkan INJEKTOR / COIL terlalu lama tanpa timing — bisa merusak komponen.\n" +
                        "• Jangan aktifkan QS CUT saat mesin nyala > 5000 RPM — berbahaya.\n" +
                        "• Pakai untuk bench test dengan kunci OFF (ignition on, engine off).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ActuatorButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    on: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    momentary: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(96.dp),
        enabled = !busy,
        colors = if (on) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                 else ButtonDefaults.buttonColors()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                if (momentary) "TAP" else if (on) "ON" else "OFF",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}