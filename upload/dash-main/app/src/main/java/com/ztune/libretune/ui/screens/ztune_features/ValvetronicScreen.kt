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

data class ValvetronicState(
    val mode: Int = 0,                  // 0=OFF, 1=BUTTON (auto dihapus)
    val active: Boolean = false,
    val buttonPressed: Boolean = false,
    val saving: Boolean = false,
    val lastMessage: String? = null
)

/**
 * v1.3.5 — sebelumnya save() cuma ubah state lokal + tampilkan "Tersimpan"
 * tanpa mengirim apapun ke ECU (audit finding #5). Sekarang benar-benar
 * baca/tulis constant `valvetronicMode` dari/ke tune aktif via [TuneManager]
 * (byte offset & tipe data dari ztune.ini, sama seperti field constant lain
 * di app ini — trigger/TDC, QS, dsb).
 */
@HiltViewModel
class ValvetronicViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val tuneManager: TuneManager
) : ViewModel() {
    private val _state = MutableStateFlow(ValvetronicState())
    val state: StateFlow<ValvetronicState> = _state.asStateFlow()

    init {
        tuneManager.getConstantValue("valvetronicMode")?.let { v ->
            _state.value = _state.value.copy(mode = v.toInt())
        }
    }

    fun setMode(v: Int) { _state.value = _state.value.copy(mode = v) }

    fun save() {
        if (tuneManager.activeDefinition == null || tuneManager.currentTune == null) {
            _state.value = _state.value.copy(lastMessage = "Belum ada tune/ECU aktif — tidak bisa simpan")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(saving = true, lastMessage = null)
            runCatching {
                tuneManager.updateConstant("valvetronicMode", _state.value.mode.toDouble())
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
fun ValvetronicScreen(
    onNavigateBack: () -> Unit,
    viewModel: ValvetronicViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Valvetronic (Katup Exhaust)") },
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
                    Text("Katup Exhaust 2-Kabel", style = MaterialTheme.typography.titleSmall,
                         color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Motor std tidak punya valvetronic. Pasang opsional (2 kabel ke katup exhaust). " +
                        "Kontrol via tombol kokpit.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Status Katup", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = if (state.active) "AKTIF" else "OFF",
                        fontWeight = FontWeight.Bold,
                        color = if (state.active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outline
                    )
                }
            }

            // Mode (hanya OFF dan BUTTON)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Mode Valvetronic", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                when (state.mode) {
                                    0 -> "OFF — Tidak aktif"
                                    1 -> "BUTTON — Ikut tombol kokpit"
                                    else -> "Unknown"
                                }
                            )
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text("OFF — Tidak aktif") },
                                onClick = { viewModel.setMode(0); expanded = false })
                            DropdownMenuItem(text = { Text("BUTTON — Ikut tombol kokpit") },
                                onClick = { viewModel.setMode(1); expanded = false })
                        }
                    }
                }
            }

            // Mode BUTTON info
            if (state.mode == 1) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Mode Tombol", style = MaterialTheme.typography.titleSmall,
                             color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Output katup mengikuti tombol kokpit (PIN_VALVETRONIC_BTN). " +
                            "Tekan untuk buka katup, lepas untuk tutup.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Tombol saat ini: ${if (state.buttonPressed) "DITEKAN" else "lepas"}")
                    }
                }
            }

            // Save (hanya bila bukan OFF)
            if (state.mode != 0) {
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
            }

            state.lastMessage?.let {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(it, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}