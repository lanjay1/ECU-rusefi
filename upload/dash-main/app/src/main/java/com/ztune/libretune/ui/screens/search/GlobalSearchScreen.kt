package com.ztune.libretune.ui.screens.search

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ztune.libretune.ui.navigation.MenuItemUi
import com.ztune.libretune.ui.navigation.MenuNodeType
import com.ztune.libretune.ui.navigation.MenuTreeViewModel

data class SearchResult(val title: String, val subtitle: String, val category: String, val route: String?)

/**
 * Entri pencarian statis — hanya route yang DIJAMIN terdaftar di NavHost,
 * jadi aman dinavigasi kapan pun (offline maupun terkoneksi).
 * Tidak ada lagi route tabel hardcoded seperti "table_editor/veTable1Tbl";
 * tabel & kurva sekarang diambil dinamis dari definisi ECU yang aktif.
 */
private val staticResults = listOf(
    SearchResult("Tune Editor", "Overview tabel & kurva tuning", "Navigation", "tune_editor"),
    SearchResult("AutoTune", "Koreksi VE otomatis", "Navigation", "autotune"),
    SearchResult("Datalog", "Rekam data ECU", "Navigation", "datalog"),
    SearchResult("Engine Constants", "Parameter skalar", "Navigation", "engine_constants"),
    SearchResult("Sensor Calibration", "TPS, CLT, IAT, O2", "Navigation", "calibration"),
    SearchResult("Tune History", "Riwayat versi tune", "Navigation", "tune_history"),
    SearchResult("Basemap", "Generator basemap motor", "Navigation", "basemap"),
    SearchResult("Settings", "Konfigurasi aplikasi", "Navigation", "settings"),
    SearchResult("Troubleshooting Aktuator", "Tes aktuator bench", "ZTune", "ztune_actuator_test"),
    SearchResult("Cam Sensor (CMP)", "Pengaturan CMP", "ZTune", "ztune_cmp"),
    SearchResult("Dashboard Ketukan", "Knock", "ZTune", "ztune_knock"),
    SearchResult("Quickshifter", "Pengaturan QS", "ZTune", "ztune_qs"),
    SearchResult("Injektor Primary", "Flow dan deadtime", "ZTune", "ztune_inj_primary"),
    SearchResult("Injektor Stage 2", "Stage 2 dan fraksi", "ZTune", "ztune_inj_stage2"),
    SearchResult("VVA", "Single/Dual VVA", "ZTune", "ztune_vva"),
    SearchResult("Pompa Coolant (EWP)", "Kontrol EWP", "ZTune", "ztune_ewp"),
    SearchResult("Valvetronic", "Katup exhaust", "ZTune", "ztune_valvetronic"),
    // Kanal live — hanya informasi (route null, tidak dinavigasi)
    SearchResult("RPM", "Kecepatan mesin", "Channels", null),
    SearchResult("TPS", "Posisi throttle", "Channels", null),
    SearchResult("AFR", "Air-fuel ratio (o2Afr)", "Channels", null),
    SearchResult("CLT", "Suhu coolant", "Channels", null),
)

/** Kumpulkan semua item bertipe [type] dari pohon menu (rekursif). */
private fun collectByType(items: List<MenuItemUi>, type: MenuNodeType, out: MutableList<MenuItemUi>) {
    for (item in items) {
        if (item.type == type && item.targetName.isNotBlank()) out.add(item)
        collectByType(item.children, type, out)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalSearchScreen(
    onNavigateBack: () -> Unit = {},
    onNavigate: (String) -> Unit = {},
    menuTreeVm: MenuTreeViewModel = hiltViewModel()
) {
    var query by remember { mutableStateOf("") }
    val menuTree by menuTreeVm.menuTree.collectAsState()

    // Statis + dinamis dari definisi ECU aktif. Saat offline menuTree kosong,
    // jadi hasil pencarian tinggal entri statis yang semuanya route valid.
    val allResults = remember(menuTree) {
        buildList {
            addAll(staticResults)
            val tables = mutableListOf<MenuItemUi>()
            val curves = mutableListOf<MenuItemUi>()
            collectByType(menuTree, MenuNodeType.TABLE, tables)
            collectByType(menuTree, MenuNodeType.CURVE, curves)
            tables.forEach { t ->
                add(
                    SearchResult(
                        t.label,
                        "Tabel ECU: ${t.targetName}",
                        "Tables",
                        "table_editor/${Uri.encode(t.targetName)}"
                    )
                )
            }
            curves.forEach { c ->
                add(
                    SearchResult(
                        c.label,
                        "Kurva ECU: ${c.targetName}",
                        "Curves",
                        "curve_editor/${Uri.encode(c.targetName)}"
                    )
                )
            }
        }
    }

    val filtered = if (query.isBlank()) allResults
        else allResults.filter {
            it.title.contains(query, true) ||
                it.subtitle.contains(query, true) ||
                it.category.contains(query, true)
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Search") },
                navigationIcon = { IconButton(onClick = onNavigateBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                placeholder = { Text("Search tables, channels, settings...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear") } },
                singleLine = true
            )
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(filtered) { result ->
                    ListItem(
                        headlineContent = { Text(result.title) },
                        supportingContent = { Text(result.subtitle) },
                        leadingContent = { Icon(Icons.Default.Search, null) },
                        trailingContent = { Text(result.category, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        modifier = Modifier.clickable {
                            result.route?.let { onNavigate(it) }
                        }
                    )
                }
            }
        }
    }
}