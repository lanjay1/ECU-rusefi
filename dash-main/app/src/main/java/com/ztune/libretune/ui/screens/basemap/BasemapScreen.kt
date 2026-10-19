package com.ztune.libretune.ui.screens.basemap

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ztune.libretune.core.basemap.Aspiration
import com.ztune.libretune.core.basemap.BaseMap
import com.ztune.libretune.core.basemap.BasemapGenerator
import com.ztune.libretune.core.basemap.EngineSpec
import com.ztune.libretune.core.basemap.FuelType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/* ============================================================================
 * Basemap Generator Screen
 *
 * Lets the user input EngineSpec (cylinders, displacement, injectors, fuel,
 * aspiration, idle, redline, boost, target WOT AFR, octane, CR) and previews
 * the generated VE / Ignition / AFR tables plus reqFuel scalar, then writes
 * them to the active tune via TuneManager.
 * ========================================================================== */

data class BasemapUiState(
    val cylinderCount: Int = 4,
    val displacementCc: String = "2000",
    val injectorSizeCc: String = "440",
    val fuelType: FuelType = FuelType.GASOLINE,
    val aspiration: Aspiration = Aspiration.NA,
    val idleRpm: String = "850",
    val redlineRpm: String = "7000",
    val boostTargetKpa: String = "",
    val targetWotAfr: String = "",
    val octane: String = "91",
    val compressionRatio: String = "10.0",
    val generatedMap: BaseMap? = null,
    val activeTab: Int = 0,
    val applyStatus: String? = null,
    val applyError: String? = null
)

@HiltViewModel
class BasemapViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(BasemapUiState())
    val state: StateFlow<BasemapUiState> = _state.asStateFlow()

    fun updateCylinderCount(v: Int) { _state.value = _state.value.copy(cylinderCount = v.coerceIn(1, 16)) }
    fun updateDisplacement(v: String) { _state.value = _state.value.copy(displacementCc = v.filter { it.isDigit() || it == '.' }) }
    fun updateInjector(v: String) { _state.value = _state.value.copy(injectorSizeCc = v.filter { it.isDigit() || it == '.' }) }
    fun updateFuelType(v: FuelType) { _state.value = _state.value.copy(fuelType = v) }
    fun updateAspiration(v: Aspiration) { _state.value = _state.value.copy(aspiration = v) }
    fun updateIdle(v: String) { _state.value = _state.value.copy(idleRpm = v.filter { it.isDigit() }) }
    fun updateRedline(v: String) { _state.value = _state.value.copy(redlineRpm = v.filter { it.isDigit() }) }
    fun updateBoost(v: String) { _state.value = _state.value.copy(boostTargetKpa = v.filter { it.isDigit() || it == '.' }) }
    fun updateWotAfr(v: String) { _state.value = _state.value.copy(targetWotAfr = v.filter { it.isDigit() || it == '.' }) }
    fun updateOctane(v: String) { _state.value = _state.value.copy(octane = v.filter { it.isDigit() || it == '.' }) }
    fun updateCr(v: String) { _state.value = _state.value.copy(compressionRatio = v.filter { it.isDigit() || it == '.' }) }
    fun updateTab(v: Int) { _state.value = _state.value.copy(activeTab = v) }

    fun generate() {
        val s = _state.value
        val spec = try {
            EngineSpec(
                cylinderCount = s.cylinderCount,
                displacementCc = s.displacementCc.toDoubleOrNull() ?: 2000.0,
                injectorSizeCc = s.injectorSizeCc.toDoubleOrNull() ?: 440.0,
                fuelType = s.fuelType,
                aspiration = s.aspiration,
                idleRpm = s.idleRpm.toIntOrNull() ?: 850,
                redlineRpm = s.redlineRpm.toIntOrNull() ?: 7000,
                boostTargetKpa = s.boostTargetKpa.toDoubleOrNull(),
                targetWotAfr = s.targetWotAfr.toDoubleOrNull(),
                octane = s.octane.toDoubleOrNull(),
                compressionRatio = s.compressionRatio.toDoubleOrNull() ?: 10.0
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(applyError = "Invalid input: ${e.message}")
            return
        }
        val map = BasemapGenerator.generateBaseMap(spec)
        _state.value = _state.value.copy(generatedMap = map, applyError = null, applyStatus = null)
    }

    fun applyToEcu() {
        val map = _state.value.generatedMap ?: return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    applyStatus = "Basemap ready: ${map.veTable.size}x${map.veTable.firstOrNull()?.size ?: 0} tables, reqFuel=${"%.2f".format(map.reqFuel)} ms. Apply-to-ECU wiring pending (TODO).",
                    applyError = null
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(applyError = "Apply failed: ${e.message}", applyStatus = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BasemapScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: BasemapViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(state.applyStatus, state.applyError) {
        state.applyError?.let { snackbarHost.showSnackbar(it) }
        state.applyStatus?.let { snackbarHost.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Basemap", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { viewModel.generate() }) {
                        Icon(Icons.Default.Refresh, "Generate")
                    }
                    IconButton(
                        onClick = { viewModel.applyToEcu() },
                        enabled = state.generatedMap != null
                    ) {
                        Icon(Icons.Default.PlayArrow, "Apply to ECU")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            EngineSpecCard(state, viewModel)
            Spacer(Modifier.height(12.dp))
            if (state.generatedMap != null) {
                GeneratedTablesCard(state, viewModel)
            } else {
                EmptyPreviewCard()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineSpecCard(state: BasemapUiState, vm: BasemapViewModel) {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Engine Specification", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(12.dp))

            Text("Cylinders", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = 8
            ) {
                listOf(1, 2, 3, 4, 5, 6, 8, 10, 12).forEach { n ->
                    FilterChip(
                        selected = state.cylinderCount == n,
                        onClick = { vm.updateCylinderCount(n) },
                        label = { Text("$n") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.displacementCc, onValueChange = vm::updateDisplacement,
                label = { Text("Displacement (cc)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = state.injectorSizeCc, onValueChange = vm::updateInjector,
                label = { Text("Injector size (cc/min)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.LocalGasStation, null) }
            )
            Spacer(Modifier.height(12.dp))

            Text("Fuel Type", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = 4
            ) {
                FuelType.entries.forEach { ft ->
                    FilterChip(
                        selected = state.fuelType == ft,
                        onClick = { vm.updateFuelType(ft) },
                        label = { Text("${ft.name} (${ft.stoich})") }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("Aspiration", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = 3
            ) {
                Aspiration.entries.forEach { asp ->
                    FilterChip(
                        selected = state.aspiration == asp,
                        onClick = { vm.updateAspiration(asp) },
                        label = { Text(asp.name) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Row {
                OutlinedTextField(
                    value = state.idleRpm, onValueChange = vm::updateIdle,
                    label = { Text("Idle RPM") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Default.Speed, null) }
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = state.redlineRpm, onValueChange = vm::updateRedline,
                    label = { Text("Redline RPM") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(12.dp))

            if (state.aspiration != Aspiration.NA) {
                OutlinedTextField(
                    value = state.boostTargetKpa, onValueChange = vm::updateBoost,
                    label = { Text("Boost target (kPa abs)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
            }

            Row {
                OutlinedTextField(
                    value = state.targetWotAfr, onValueChange = vm::updateWotAfr,
                    label = { Text("WOT AFR (blank=auto)") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                    leadingIcon = { Icon(Icons.Default.Whatshot, null) }
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = state.octane, onValueChange = vm::updateOctane,
                    label = { Text("Octane (RON)") }, singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = state.compressionRatio, onValueChange = vm::updateCr,
                label = { Text("Compression ratio") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Bolt, null) }
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.generate() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Generate Basemap")
            }
        }
    }
}

@Composable
private fun EmptyPreviewCard() {
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(
            modifier = Modifier.padding(32.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Tune, null, modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))
            Text("No basemap generated yet", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Fill in the engine spec above and tap Generate Basemap",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun GeneratedTablesCard(state: BasemapUiState, vm: BasemapViewModel) {
    val map = state.generatedMap ?: return
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Generated Basemap", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text("Required fuel: ${"%.2f".format(map.reqFuel)} ms   |   ${map.rpmBins.size}x${map.loadBins.size} tables",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(12.dp))
            TabRow(selectedTabIndex = state.activeTab) {
                listOf("VE", "Ignition", "AFR").forEachIndexed { i, label ->
                    Tab(
                        selected = state.activeTab == i,
                        onClick = { vm.updateTab(i) },
                        text = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when (state.activeTab) {
                0 -> TableGrid(map.rpmBins, map.loadBins, map.veTable, "VE %", 15.0, 120.0, Color(0xFF4CAF50))
                1 -> TableGrid(map.rpmBins, map.loadBins, map.ignitionTable, "Advance deg", 10.0, 42.0, Color(0xFFEF5350))
                2 -> TableGrid(map.rpmBins, map.loadBins, map.afrTable, "AFR", 8.0, 20.0, Color(0xFF42A5F5))
            }

            Spacer(Modifier.height(16.dp))
            Text("Scalars", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            map.scalars.forEach { (k, v) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(k, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                    Text("${"%.3f".format(v)}", style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.applyToEcu() },
                modifier = Modifier.fillMaxWidth(),
                enabled = true
            ) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(8.dp))
                Text("Apply to ECU")
            }
        }
    }
}

@Composable
private fun TableGrid(
    rpmBins: List<Double>,
    loadBins: List<Double>,
    table: List<List<Double>>,
    unitLabel: String,
    minV: Double,
    maxV: Double,
    baseColor: Color
) {
    val scroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(scroll)) {
        // Header row: rpm bins
        Row {
            Box(
                modifier = Modifier.size(48.dp).border(0.5.dp, MaterialTheme.colorScheme.outline)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Text("Load\\RPM", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            rpmBins.forEach { bin ->
                Box(
                    modifier = Modifier.size(44.dp).border(0.5.dp, MaterialTheme.colorScheme.outline)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${bin.toInt()}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        // Data rows
        loadBins.forEachIndexed { ri, load ->
            Row {
                Box(
                    modifier = Modifier.size(48.dp).border(0.5.dp, MaterialTheme.colorScheme.outline)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${load.toInt()}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                table.getOrNull(ri)?.forEach { v ->
                    val ratio = ((v - minV) / (maxV - minV)).coerceIn(0.0, 1.0)
                    val cellColor = baseColor.copy(alpha = (0.15f + ratio.toFloat() * 0.75f))
                    Box(
                        modifier = Modifier.size(44.dp).border(0.5.dp, MaterialTheme.colorScheme.outline)
                            .background(cellColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("${"%.1f".format(v)}", style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Unit: $unitLabel (range $minV – $maxV)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
