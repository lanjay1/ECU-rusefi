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
import com.ztune.libretune.core.ecu.ZtuneEcu
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EwpState(
    val installed: Boolean = true,        // true = EWP terpasang, false = dilepas
    val manualOn: Boolean = false,         // toggle manual (only used when installed)
    val autoOn: Boolean = false,           // status auto (saat mesin nyala)
    val saving: Boolean = false,
    val lastMessage: String? = null
)

@HiltViewModel
class EwpViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager
) : ViewModel() {
    private val _state = MutableStateFlow(EwpState())
    val state: StateFlow<EwpState> = _state.asStateFlow()

    fun setInstalled(v: Boolean) {
        // Saat dilepas (installed=false), paksa off
        _state.value = _state.value.copy(installed = v, manualOn = if (!v) false else _state.value.manualOn)
    }

    fun toggleManual() {
        // Hanya bisa di-toggle bila EWP terpasang
        if (!_state.value.installed) return
        _state.value = _state.value.copy(manualOn = !_state.value.manualOn)
        sendBenchCommand(_state.value.manualOn)
    }

    private fun sendBenchCommand(on: Boolean) {
        viewModelScope.launch {
            val ecu = connectionManager.ecuInterface
            if (ecu is ZtuneEcu) {
                val r = ecu.sendControllerCommand("benchEwp", "benchEwp", if (on) 1 else 0)
                _state.value = _state.value.copy(
                    lastMessage = r.fold(
                        onSuccess = { if (on) "Pompa ON (manual)" else "Pompa OFF" },
                        onFailure = { "Gagal: ${it.message}" }
                    )
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoolantPumpScreen(
    onNavigateBack: () -> Unit,
    viewModel: EwpViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pompa Coolant (EWP)") },
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
                    Text("Mode Pompa Coolant", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Hardcoded mode: Relay ON/OFF. Pompa dinyalakan otomatis saat mesin nyala (auto), " +
                        "atau manual via tombol ON/OFF di bawah. Matikan toggle 'EWP Terpasang' bila pompa dilepas.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Toggle EWP terpasang / dilepas
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("EWP Terpasang", style = MaterialTheme.typography.titleSmall)
                        Text("Bila dilepas, semua kontrol EWP dimatikan.",
                             style = MaterialTheme.typography.bodySmall,
                             color = MaterialTheme.colorScheme.outline)
                    }
                    Switch(checked = state.installed, onCheckedChange = viewModel::setInstalled)
                }
            }

            // Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Status Pompa", style = MaterialTheme.typography.titleSmall)
                    val isOn = state.installed && (state.manualOn || state.autoOn)
                    Text(
                        text = if (isOn) "ON" else "OFF",
                        fontWeight = FontWeight.Bold,
                        color = if (isOn) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Tombol ON/OFF (only enabled bila installed)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Kontrol Manual", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Bila mesin nyala, pompa auto-ON. Tombol ini untuk override manual (force ON).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = viewModel::toggleManual,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.installed,
                        colors = if (state.manualOn)
                            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        else ButtonDefaults.buttonColors()
                    ) {
                        Text(
                            text = if (state.manualOn) "MATIKAN POMPA (manual)" else "NYALAKAN POMPA (manual)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (!state.installed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Tombol dinonaktifkan karena EWP tidak terpasang.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
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