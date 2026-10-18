package com.ztune.libretune.ui.screens.calibration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorCalibrationScreen(
    onNavigateBack: () -> Unit,
    viewModel: SensorCalibrationViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kalibrasi") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Mode tabs: Sensor (kurva voltase 2-titik) vs Trigger/TDC (wizard beda alur)
            CalibModeTabs(selected = state.mode, onSelect = viewModel::setMode)

            if (state.mode == CalibrationMode.TRIGGER) {
                TriggerCalibrationSection(
                    trigger = state.trigger,
                    onTotalTeethChange = viewModel::setTotalTeeth,
                    onMissingTeethChange = viewModel::setMissingTeeth,
                    onEdgeChange = viewModel::setEdge,
                    onSaveSetup = viewModel::saveTriggerSetup,
                    onMarkTdc = viewModel::markTdcNow,
                    onSaveOffset = viewModel::saveTdcOffset,
                    onDismissMessage = viewModel::clearTriggerMessage
                )
                return@Column
            }

            if (state.mode == CalibrationMode.AFR_DELAY) {
                AfrDelayTestSection(
                    afrDelay = state.afrDelay,
                    onStart = viewModel::startAfrDelayRecording,
                    onStop = viewModel::stopAfrDelayRecordingAndAnalyze,
                    onCancel = viewModel::cancelAfrDelayRecording,
                    onSave = viewModel::saveAfrDelayResult,
                    onDismissMessage = viewModel::clearAfrDelayMessage
                )
                return@Column
            }

            // Calibration type chips
            CalibTypeChips(
                selected = state.type,
                onSelect = viewModel::setCalibrationType
            )

            // Live ADC readout card
            LiveAdcCard(
                type = state.type,
                adcValue = state.liveAdcValue,
                step = state.step
            )

            // Step content
            when (state.step) {
                CalibrationStep.IDLE -> IdleStep(
                    adcValue = state.liveAdcValue,
                    onCaptureLow = viewModel::captureLow
                )
                CalibrationStep.CAPTURE_LOW -> CaptureLowStep(
                    adcLow = state.adcLow,
                    onCaptureHigh = viewModel::captureHigh,
                    onReset = viewModel::reset
                )
                CalibrationStep.CAPTURE_HIGH -> CaptureHighStep(
                    adcLow = state.adcLow,
                    adcHigh = state.adcHigh,
                    onGenerate = viewModel::generateCalibration,
                    onReset = viewModel::reset
                )
                CalibrationStep.PREVIEW -> PreviewStep(
                    values = state.newValues,
                    onWrite = viewModel::writeCalibration,
                    onReset = viewModel::reset
                )
                CalibrationStep.WRITING -> WritingStep(progress = state.progress)
                CalibrationStep.DONE -> DoneStep(isVerified = state.isVerified, onReset = viewModel::reset)
                CalibrationStep.ERROR -> ErrorStep(
                    message = state.errorMessage,
                    onRetry = viewModel::reset
                )
                CalibrationStep.VERIFYING -> WritingStep(progress = state.progress)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CalibTypeChips(
    selected: CalibrationType,
    onSelect: (CalibrationType) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CalibrationType.entries.forEach { type ->
            FilterChip(
                selected = type == selected,
                onClick = { onSelect(type) },
                label = { Text(type.name) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun LiveAdcCard(
    type: CalibrationType,
    adcValue: Double,
    step: CalibrationStep
) {
    val isActive = step in listOf(
        CalibrationStep.IDLE, CalibrationStep.CAPTURE_LOW, CalibrationStep.CAPTURE_HIGH
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${type.name} ADC (Live)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = String.format("%.1f", adcValue),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = if (isActive) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun IdleStep(adcValue: Double, onCaptureLow: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 1: Capture Low Reference", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set the sensor to its minimum known position " +
                    "(e.g. throttle closed, ambient temperature). " +
                    "Current ADC: ${String.format("%.1f", adcValue)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onCaptureLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Capture Low Value")
            }
        }
    }
}

@Composable
private fun CaptureLowStep(
    adcLow: Double,
    onCaptureHigh: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 2: Capture High Reference", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Low ADC captured:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format("%.1f", adcLow),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                "Now set the sensor to its maximum known position " +
                    "(e.g. throttle fully open, apply heat).",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onCaptureHigh,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Capture High Value")
            }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun CaptureHighStep(
    adcLow: Double,
    adcHigh: Double,
    onGenerate: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 3: Confirm & Generate", style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Low ADC:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format("%.1f", adcLow),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("High ADC:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    String.format("%.1f", adcHigh),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Button(
                onClick = onGenerate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Generate Calibration Table")
            }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun PreviewStep(
    values: List<Double>,
    onWrite: () -> Unit,
    onReset: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Step 4: Preview & Write", style = MaterialTheme.typography.titleMedium)
            Text(
                "Review the generated calibration table before writing to ECU.",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                values.forEachIndexed { index, value ->
                    Column(
                        modifier = Modifier.padding(vertical = 2.dp),
                        horizontalAlignment = Alignment.Start
                    ) {
                        Text(
                            "[$index] ${String.format("%.2f", value)}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Button(
                onClick = onWrite,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Write to ECU")
            }
            TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
                Text("Reset")
            }
        }
    }
}

@Composable
private fun WritingStep(progress: Float) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Writing Calibration...", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${(progress * 100).toInt()}%",
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DoneStep(isVerified: Boolean, onReset: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                if (isVerified) "Calibration Written & Verified"
                else "Calibration Written",
                style = MaterialTheme.typography.titleMedium
            )
            Button(
                onClick = onReset,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Calibrate Another Sensor")
            }
        }
    }
}

@Composable
private fun ErrorStep(message: String?, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                message ?: "An unknown error occurred",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

// =============================================================================
//  Mode tabs — Sensor (kurva voltase) vs Trigger / TDC (wizard beda alur)
// =============================================================================

@Composable
private fun CalibModeTabs(
    selected: CalibrationMode,
    onSelect: (CalibrationMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selected == CalibrationMode.SENSOR,
            onClick = { onSelect(CalibrationMode.SENSOR) },
            label = { Text("Sensor") },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = selected == CalibrationMode.TRIGGER,
            onClick = { onSelect(CalibrationMode.TRIGGER) },
            label = { Text("Trigger / TDC") },
            modifier = Modifier.weight(1f)
        )
        FilterChip(
            selected = selected == CalibrationMode.AFR_DELAY,
            onClick = { onSelect(CalibrationMode.AFR_DELAY) },
            label = { Text("AFR Delay") },
            modifier = Modifier.weight(1f)
        )
    }
}

// =============================================================================
//  Trigger / TDC calibration — UI-first, backend firmware belum ada.
//
//  BUILD STATUS: Semua field "Setup" (Total Teeth/Missing Teeth/Edge) sudah
//  bisa disimpan beneran ke tune (lewat TuneManager.updateConstant, sama
//  seperti field constant lain di app ini). Bagian "Tandai TDC" masih STUB
//  total — lihat komentar TODO di SensorCalibrationViewModel.markTdcNow().
//  Tombol tetap ada dan bisa ditekan supaya alur UI-nya bisa dicoba/direview
//  sekarang, tapi hasilnya jujur ditampilkan sebagai "belum bisa diukur"
//  sampai command firmware-nya siap.
// =============================================================================

@Composable
private fun TriggerCalibrationSection(
    trigger: TriggerCalibState,
    onTotalTeethChange: (Int) -> Unit,
    onMissingTeethChange: (Int) -> Unit,
    onEdgeChange: (TriggerEdge) -> Unit,
    onSaveSetup: () -> Unit,
    onMarkTdc: () -> Unit,
    onSaveOffset: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

        // ---- Setup gigi trigger ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Setup Trigger / Pulser", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Jumlah gigi total, gigi hilang (missing tooth), dan edge deteksi — " +
                    "ini disimpan langsung ke tune, sudah berfungsi penuh.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                NumberStepperRow(
                    label = "Total Teeth",
                    value = trigger.totalTeeth,
                    range = 4..60,
                    onChange = onTotalTeethChange
                )
                NumberStepperRow(
                    label = "Missing Teeth",
                    value = trigger.missingTeeth,
                    range = 1..5,
                    onChange = onMissingTeethChange
                )

                Text("Edge Deteksi", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = trigger.edge == TriggerEdge.RISING,
                        onClick = { onEdgeChange(TriggerEdge.RISING) },
                        label = { Text("Rising") }
                    )
                    FilterChip(
                        selected = trigger.edge == TriggerEdge.FALLING,
                        onClick = { onEdgeChange(TriggerEdge.FALLING) },
                        label = { Text("Falling") }
                    )
                }

                Button(
                    onClick = onSaveSetup,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !trigger.saving
                ) {
                    Text(if (trigger.saving) "Menyimpan..." else "Simpan Setup Trigger")
                }
            }
        }

        // ---- Kalibrasi offset TDC ----
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Kalibrasi Offset TDC", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Offset tersimpan saat ini: ${"%.1f".format(trigger.currentOffsetDeg)}°",
                    style = MaterialTheme.typography.bodyMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Sudut trigger live:")
                    Text(
                        trigger.liveTriggerAngleDeg?.let { "%.1f°".format(it) } ?: "— (belum tersedia)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Peringatan jujur: fitur ini belum bisa dipakai beneran sampai
                // firmware punya command kalibrasi TDC.
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Belum tersedia: firmware ESP32-S3 belum punya command untuk " +
                        "menandai TDC secara live. UI di bawah sudah siap dipakai, tapi " +
                        "hasil ukurnya masih placeholder sampai bagian firmware/protokol " +
                        "serial-nya selesai dikerjakan.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                Text(
                    "Langkah nanti kalau firmware sudah siap: putar mesin manual sampai " +
                    "piston tepat di TDC kompresi, lalu tekan tombol di bawah pas posisi pas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onMarkTdc,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Tandai TDC Sekarang")
                }

                trigger.markedAngleDeg?.let {
                    Text("Sudut ditandai: %.1f°".format(it))
                }
                trigger.computedOffsetDeg?.let {
                    Text("Offset dihitung: %.1f°".format(it), style = MaterialTheme.typography.titleSmall)
                }

                Button(
                    onClick = onSaveOffset,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = trigger.computedOffsetDeg != null && !trigger.saving
                ) {
                    Text(if (trigger.saving) "Menyimpan..." else "Simpan Offset TDC")
                }
            }
        }

        trigger.message?.let { msg ->
            AlertDialog(
                onDismissRequest = onDismissMessage,
                title = { Text("Trigger / TDC") },
                text = { Text(msg) },
                confirmButton = {
                    TextButton(onClick = onDismissMessage) { Text("OK") }
                }
            )
        }
    }
}

@Composable
private fun NumberStepperRow(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (value - 1 >= range.first) onChange(value - 1) }) {
                Text("−", style = MaterialTheme.typography.titleLarge)
            }
            Text(
                value.toString(),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.width(40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            IconButton(onClick = { if (value + 1 <= range.last) onChange(value + 1) }) {
                Text("+", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

// =============================================================================
//  AFR Delay Test — v1.3.8 (audit finding #15)
//
//  Berbeda dari Trigger/TDC, ini BEKERJA PENUH tanpa command firmware baru —
//  murni mengamati channel tps/o2Afr/rpm yang sudah ada, sambil user blip
//  gas manual sekali.
// =============================================================================

@Composable
private fun AfrDelayTestSection(
    afrDelay: AfrDelayTestState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    onDismissMessage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AFR / Lambda Delay Test", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Mengukur berapa lama sensor AFR butuh waktu untuk 'melihat' " +
                    "perubahan yang terjadi di mesin — penting untuk motor 1-silinder " +
                    "karena header/knalpot pendek bisa punya delay jauh beda dari asumsi " +
                    "default AutoTune.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    "Delay tersimpan saat ini: " +
                    if (afrDelay.currentSavedDelayMs > 0) "${afrDelay.currentSavedDelayMs} ms (hasil ukur)"
                    else "belum diukur — AutoTune pakai kurva RPM generik",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Cara pakai:", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Nyalakan mesin, biarkan idle stabil.\n" +
                    "2. Tekan \"Mulai Rekam\".\n" +
                    "3. Dalam beberapa detik, BLIP GAS SEKALI dengan tegas lalu lepas.\n" +
                    "4. Rekaman berhenti otomatis setelah 8 detik dan langsung dianalisis.",
                    style = MaterialTheme.typography.bodyMedium
                )

                if (afrDelay.isRecording) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Merekam... ${afrDelay.recordedSamples} sample")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onStop, modifier = Modifier.weight(1f)) { Text("Stop & Analisis Sekarang") }
                        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Batal") }
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Mulai Rekam")
                    }
                }
            }
        }

        if (afrDelay.lastResultMs != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Hasil Pengukuran", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${afrDelay.lastResultMs.toLong()} ms",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (afrDelay.resultHistory.size > 1) {
                        Text(
                            "5 hasil terakhir: " + afrDelay.resultHistory.joinToString(", ") { "${it.toLong()}ms" } +
                            " — kalau angkanya jauh beda-beda tiap coba, ulangi test dengan blip gas yang lebih tegas/konsisten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                        Text("Pakai sebagai Lambda Delay AutoTune")
                    }
                }
            }
        }

        afrDelay.statusMessage?.let { msg ->
            AlertDialog(
                onDismissRequest = onDismissMessage,
                title = { Text("AFR Delay Test") },
                text = { Text(msg) },
                confirmButton = { TextButton(onClick = onDismissMessage) { Text("OK") } }
            )
        }
    }
}