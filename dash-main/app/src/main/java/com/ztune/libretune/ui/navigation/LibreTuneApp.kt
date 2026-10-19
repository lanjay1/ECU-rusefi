package com.ztune.libretune.ui.navigation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeveloperBoard
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ztune.libretune.R
import com.ztune.libretune.core.EcuConnectionStatus
import com.ztune.libretune.ui.screens.HomeScreen
import com.ztune.libretune.ui.screens.TuneEditorScreen
import com.ztune.libretune.ui.screens.autotune.AutoTuneScreen
import com.ztune.libretune.ui.screens.basemap.BasemapScreen
import com.ztune.libretune.ui.screens.calibration.SensorCalibrationScreen
import com.ztune.libretune.ui.screens.connection.ConnectionScreen
import com.ztune.libretune.ui.screens.curve_editor.CurveEditorScreen
import com.ztune.libretune.ui.screens.datalog.DatalogChartScreen
import com.ztune.libretune.ui.screens.datalog.DatalogScreen
import com.ztune.libretune.ui.screens.engine_constants.EngineConstantsScreen
import com.ztune.libretune.ui.screens.git.TuneHistoryScreen
import com.ztune.libretune.ui.screens.help.HelpViewerScreen
import com.ztune.libretune.ui.screens.search.GlobalSearchScreen
import com.ztune.libretune.ui.screens.settings.SettingsScreen
import com.ztune.libretune.ui.screens.tune_editor.TableEditorScreen
import kotlinx.coroutines.launch

// ======================================================================
//  Route constants
// ======================================================================

object Routes {
    const val HOME = "home"
    const val TUNE_EDITOR = "tune_editor"
    const val TABLE_EDITOR = "table_editor/{name}"
    const val CURVE_EDITOR = "curve_editor/{name}"
    const val DIALOG = "dialog/{name}"
    const val AUTOTUNE = "autotune"
    const val DATALOG = "datalog"
    const val DATALOG_CHART = "datalog_chart/{filePath}"
    const val CALIBRATION = "calibration"
    const val TUNE_HISTORY = "tune_history"
    const val HELP = "help/{topic}"
    const val SETTINGS = "settings"
    const val CONNECTION = "connection?mode={mode}"
    const val ENGINE_CONSTANTS = "engine_constants"
    const val GLOBAL_SEARCH = "global_search"
    const val BASEMAP = "basemap"
    const val ZTUNE_ACTUATOR_TEST = "ztune_actuator_test"
    const val ZTUNE_CMP = "ztune_cmp"
    const val ZTUNE_KNOCK = "ztune_knock"
    const val ZTUNE_QS = "ztune_qs"
    const val ZTUNE_INJ_PRIMARY = "ztune_inj_primary"
    const val ZTUNE_INJ_STAGE2 = "ztune_inj_stage2"
    const val ZTUNE_VVA = "ztune_vva"
    const val ZTUNE_EWP = "ztune_ewp"
    const val ZTUNE_VALVETRONIC = "ztune_valvetronic"

    /** Route statik (tanpa parameter) yang dikirim langsung oleh drawer. */
    val STATIC_ROUTES = setOf(
        HOME, TUNE_EDITOR, AUTOTUNE, DATALOG, CALIBRATION, TUNE_HISTORY,
        SETTINGS, ENGINE_CONSTANTS, GLOBAL_SEARCH, BASEMAP,
        ZTUNE_ACTUATOR_TEST, ZTUNE_CMP, ZTUNE_KNOCK, ZTUNE_QS,
        ZTUNE_INJ_PRIMARY, ZTUNE_INJ_STAGE2, ZTUNE_VVA, ZTUNE_EWP, ZTUNE_VALVETRONIC
    )
}

/**
 * Root composable navigasi ZTune.
 * MenuTreeViewModel adalah @HiltViewModel dan mengamati
 * EcuConnectionManager.activeDefinition secara reaktif — drawer/topbar/bottombar
 * ter-render benar saat ECU terhubung.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibreTuneApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val menuTreeVm: MenuTreeViewModel = hiltViewModel()
    val menuTree by menuTreeVm.menuTree.collectAsState()

    // Status koneksi ECU — menggerakkan ikon WiFi di top bar
    // (hijau = terhubung, merah = putus) dan dialog koneksi.
    val connState by menuTreeVm.connectionState.collectAsState()
    val isConnected = connState.status == EcuConnectionStatus.CONNECTED
    var showConnectDialog by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Route yang menyembunyikan bottom bar dan drawer.
    // HOME sengaja TIDAK disembunyikan. Bandingkan dengan nama dasar tiap
    // pola (buang "/{arg}" dan "?mode={arg}").
    val hideChromeRoutes = setOf(
        "connection", // dasar dari Routes.CONNECTION ("connection?mode={mode}")
        Routes.TABLE_EDITOR.substringBefore('/'),
        Routes.CURVE_EDITOR.substringBefore('/'),
        Routes.DIALOG.substringBefore('/'),
        Routes.HELP.substringBefore('/')
    )
    val baseRoute = currentRoute
        ?.substringBefore('/')
        ?.substringBefore('?')
    val showChrome = baseRoute !in hideChromeRoutes

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            if (showChrome) {
                ModalDrawerSheet(modifier = Modifier.width(280.dp)) {
                    MenuTreeDrawer(
                        menuItems = menuTree,
                        onNavigate = { route ->
                            scope.launch { drawerState.close() }
                            // Item Tools mengirim route statik Routes.* — navigasi
                            // langsung. Item menu INI mengirim targetName
                            // (nama tabel/dialog/kurva) — terjemahkan via resolveMenuRoute.
                            val navRoute = if (route in Routes.STATIC_ROUTES) route
                                           else resolveMenuRoute(route, menuTree)
                            if (navRoute != null) {
                                try {
                                    navController.navigate(navRoute)
                                } catch (_: IllegalArgumentException) {
                                    // Route tidak dikenal — abaikan, jangan crash.
                                }
                            }
                        },
                        currentRoute = currentRoute
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (showChrome) {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // Ikon WiFi — hijau saat terhubung ke ECU, merah saat tidak.
                                // Ketuk untuk membuka dialog koneksi.
                                IconButton(onClick = { showConnectDialog = true }) {
                                    Icon(
                                        imageVector = Icons.Default.Wifi,
                                        contentDescription = if (isConnected) {
                                            stringResource(R.string.conn_dialog_status_connected)
                                        } else {
                                            stringResource(R.string.conn_dialog_status_disconnected)
                                        },
                                        tint = if (isConnected) Color(0xFF22B14C) else Color(0xFFE53935)
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(stringResource(R.string.app_name))
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu")
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (showChrome) {
                    val bottomItems = listOf(
                        TopLevelDestination.HOME,
                        TopLevelDestination.TUNE_EDITOR,
                        TopLevelDestination.DATALOG,
                        TopLevelDestination.SETTINGS,
                    )
                    NavigationBar {
                        bottomItems.forEach { dest ->
                            val selected = baseRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navController.navigateToTopLevel(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(dest.labelRes)) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.HOME,
                modifier = Modifier.padding(innerPadding)
            ) {
                // -- Home ---------------------------------------------------------------
                composable(Routes.HOME) {
                    HomeScreen()
                }

                // -- Connection ---------------------------------------------------------
                composable(
                    route = Routes.CONNECTION,
                    arguments = listOf(
                        navArgument("mode") {
                            type = NavType.StringType
                            defaultValue = "usb"
                        }
                    )
                ) { backStack ->
                    val mode = backStack.arguments?.getString("mode") ?: "usb"
                    ConnectionScreen(
                        initialMode = mode,
                        // Setelah koneksi berhasil, langsung ke Tune editor.
                        onNavigateToDashboard = { navController.navigateToTopLevel(Routes.TUNE_EDITOR) },
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // -- Tune editor overview ----------------------------------------------
                composable(Routes.TUNE_EDITOR) {
                    TuneEditorScreen(
                        onNavigateToConnection = { navController.navigate("connection") },
                        onNavigateToTable = { tableName ->
                            navController.navigate("table_editor/$tableName")
                        }
                    )
                }

                // -- Table editor -------------------------------------------------------
                composable(
                    route = Routes.TABLE_EDITOR,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    TableEditorScreen(
                        tableName = name,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // -- Curve editor -------------------------------------------------------
                composable(
                    route = Routes.CURVE_EDITOR,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) {
                    // Nama kurva dibaca CurveEditorViewModel lewat SavedStateHandle["name"]
                    CurveEditorScreen(
                        onBack = { navController.popBackStack() },
                        onSave = { navController.popBackStack() }
                    )
                }

                // -- Dialog (constant editor) ------------------------------------------
                composable(
                    route = Routes.DIALOG,
                    arguments = listOf(navArgument("name") { type = NavType.StringType })
                ) { backStack ->
                    val name = backStack.arguments?.getString("name").orEmpty()
                    // Sementara didelegasikan ke table editor.
                    TableEditorScreen(
                        tableName = name,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // -- Auto-tune ----------------------------------------------------------
                composable(Routes.AUTOTUNE) {
                    AutoTuneScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Datalog ------------------------------------------------------------
                composable(Routes.DATALOG) {
                    DatalogScreen(
                        onNavigateToConnection = { navController.navigate("connection") }
                    )
                }

                // -- Datalog chart viewer ----------------------------------------------
                composable(
                    route = Routes.DATALOG_CHART,
                    arguments = listOf(navArgument("filePath") { type = NavType.StringType })
                ) {
                    DatalogChartScreen(
                        onBack = { navController.popBackStack() }
                    )
                }

                // -- Calibration --------------------------------------------------------
                composable(Routes.CALIBRATION) {
                    SensorCalibrationScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // -- Tune history (version control) ------------------------------------
                composable(Routes.TUNE_HISTORY) {
                    TuneHistoryScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Help ---------------------------------------------------------------
                composable(
                    route = Routes.HELP,
                    arguments = listOf(navArgument("topic") { type = NavType.StringType })
                ) { backStack ->
                    val topic = backStack.arguments?.getString("topic").orEmpty()
                    HelpViewerScreen(
                        topic = com.ztune.libretune.core.ini.types.HelpTopic(title = topic, text = topic),
                        onBack = { navController.popBackStack() }
                    )
                }

                // -- Settings -----------------------------------------------------------
                composable(Routes.SETTINGS) {
                    SettingsScreen()
                }

                // -- Engine Constants --------------------------------------------------
                composable(Routes.ENGINE_CONSTANTS) {
                    EngineConstantsScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Global Search ------------------------------------------------------
                composable(Routes.GLOBAL_SEARCH) {
                    GlobalSearchScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onNavigate = { route ->
                            try {
                                navController.navigate(route)
                            } catch (_: IllegalArgumentException) {
                                // Route tidak dikenal — abaikan, jangan crash.
                            }
                        }
                    )
                }

                // -- Basemap ------------------------------------------------------------
                composable(Routes.BASEMAP) {
                    BasemapScreen(onNavigateBack = { navController.popBackStack() })
                }

                // -- Fitur ZTune --------------------------------------------------------
                composable(Routes.ZTUNE_ACTUATOR_TEST) {
                    com.ztune.libretune.ui.screens.ztune_features.ActuatorTestScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.ZTUNE_CMP) {
                    com.ztune.libretune.ui.screens.ztune_features.CmpScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.ZTUNE_KNOCK) {
                    com.ztune.libretune.ui.screens.ztune_features.KnockDashboardScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.ZTUNE_QS) {
                    com.ztune.libretune.ui.screens.ztune_features.QuickShifterScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToTable = { tableName -> navController.navigate("table_editor/$tableName") }
                    )
                }
                composable(Routes.ZTUNE_INJ_PRIMARY) {
                    com.ztune.libretune.ui.screens.ztune_features.InjectorPrimaryScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.ZTUNE_INJ_STAGE2) {
                    com.ztune.libretune.ui.screens.ztune_features.InjectorStage2Screen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.ZTUNE_VVA) {
                    com.ztune.libretune.ui.screens.ztune_features.VvaScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.ZTUNE_EWP) {
                    com.ztune.libretune.ui.screens.ztune_features.CoolantPumpScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.ZTUNE_VALVETRONIC) {
                    com.ztune.libretune.ui.screens.ztune_features.ValvetronicScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }

        // ------------------------------------------------------------------
        //  Dialog koneksi ECU — dibuka dengan mengetuk ikon WiFi.
        //  USB-OTG membuka layar Connection di tab USB; WiFi TCP membuka
        //  layar Connection dengan form WiFi terpilih.
        // ------------------------------------------------------------------
        if (showConnectDialog) {
            EcuConnectDialog(
                isConnected = isConnected,
                signature = connState.signature,
                onDismiss = { showConnectDialog = false },
                onUsb = {
                    showConnectDialog = false
                    navController.navigate("connection")
                },
                onWifi = {
                    showConnectDialog = false
                    navController.navigate("connection?mode=wifi")
                }
            )
        }
    }
}

/** Navigasi ke tujuan tingkat atas dengan perilaku back-stack yang benar. */
private fun NavHostController.navigateToTopLevel(route: String) {
    val startDestId = graph.startDestinationId
    val options = androidx.navigation.NavOptions.Builder()
        .setPopUpTo(startDestId, inclusive = false, saveState = true)
        .setLaunchSingleTop(true)
        .setRestoreState(true)
        .build()
    navigate(route, options)
}

// ======================================================================
//  Dialog koneksi ECU (dibuka dari ikon WiFi di top bar)
// ======================================================================

@Composable
private fun EcuConnectDialog(
    isConnected: Boolean,
    signature: String?,
    onDismiss: () -> Unit,
    onUsb: () -> Unit,
    onWifi: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.conn_dialog_title)) },
        text = {
            Column {
                // ---- Status saat ini ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Wifi,
                        contentDescription = null,
                        tint = if (isConnected) Color(0xFF22B14C) else Color(0xFFE53935),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isConnected) {
                                stringResource(R.string.conn_dialog_status_connected)
                            } else {
                                stringResource(R.string.conn_dialog_status_disconnected)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isConnected && !signature.isNullOrEmpty()) {
                            Text(
                                text = signature,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ---- Opsi: USB OTG ----
                ConnectionOptionRow(
                    icon = Icons.Filled.Usb,
                    title = stringResource(R.string.conn_dialog_via_usb),
                    subtitle = stringResource(R.string.conn_dialog_via_usb_desc),
                    onClick = onUsb
                )
                Spacer(modifier = Modifier.height(4.dp))
                // ---- Opsi: WiFi TCP ----
                ConnectionOptionRow(
                    icon = Icons.Default.Wifi,
                    title = stringResource(R.string.conn_dialog_via_wifi),
                    subtitle = stringResource(R.string.conn_dialog_via_wifi_desc),
                    onClick = onWifi
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.conn_dialog_close))
            }
        }
    )
}

/** Satu baris pilihan metode koneksi di dalam [EcuConnectDialog]. */
@Composable
private fun ConnectionOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Terjemahkan [targetName] item menu INI ke route navigasi.
 * Mengembalikan null bila tipe item tidak punya tujuan.
 */
private fun resolveMenuRoute(
    targetName: String,
    menuTree: List<MenuItemUi>
): String? {
    val item = findMenuItemByTarget(menuTree, targetName) ?: return null

    return when (item.type) {
        MenuNodeType.TABLE -> "table_editor/${encodeRoute(item.targetName)}"
        MenuNodeType.DIALOG -> Routes.ENGINE_CONSTANTS
        MenuNodeType.CURVE -> "curve_editor/${encodeRoute(item.targetName)}"
        MenuNodeType.CALIBRATION -> Routes.CALIBRATION
        MenuNodeType.DASHBOARD -> null
        MenuNodeType.LOG -> Routes.DATALOG
        MenuNodeType.HELP -> "help/${encodeRoute(item.targetName)}"
        MenuNodeType.INDICATOR, MenuNodeType.READOUT -> Routes.ENGINE_CONSTANTS
        MenuNodeType.COMMAND -> null
        MenuNodeType.PORT_EDITOR -> null
        MenuNodeType.FOLDER -> null
    }
}

/** Cari rekursif item menu dengan targetName yang cocok. */
private fun findMenuItemByTarget(
    items: List<MenuItemUi>,
    targetName: String
): MenuItemUi? {
    for (item in items) {
        if (item.targetName == targetName) return item
        val found = findMenuItemByTarget(item.children, targetName)
        if (found != null) return found
    }
    return null
}

/** Encode string untuk dipakai di route (karakter khusus seperti '/'). */
private fun encodeRoute(s: String): String =
    android.net.Uri.encode(s)

// ======================================================================
//  Drawer
// ======================================================================

@Composable
private fun MenuTreeDrawer(
    menuItems: List<MenuItemUi>,
    onNavigate: (String) -> Unit,
    currentRoute: String?
) {
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // Menu INI — hanya ada saat terhubung ke ECU yang definisinya punya menu.
        // Saat tidak terhubung bagian ini dilewati sehingga drawer tetap
        // terbuka dengan Tools yang selalu tersedia.
        if (menuItems.isNotEmpty()) {
            Text(
                text = "Menu",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.titleMedium
            )
            HorizontalDivider()
            menuItems.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label) },
                    selected = currentRoute == item.targetName,
                    icon = { Icon(item.icon, contentDescription = null) },
                    onClick = { onNavigate(item.targetName) },
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                item.children.forEach { child ->
                    NavigationDrawerItem(
                        label = { Text("  ${child.label}", style = MaterialTheme.typography.bodyMedium) },
                        selected = currentRoute == child.targetName,
                        icon = { Icon(child.icon, contentDescription = null, modifier = Modifier.padding(start = 12.dp)) },
                        onClick = { onNavigate(child.targetName) },
                        modifier = Modifier.padding(start = 16.dp).padding(horizontal = 8.dp)
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        DrawerSection("Tools")
        DrawerItem("Engine Constants", Routes.ENGINE_CONSTANTS, currentRoute, Icons.Outlined.Settings, onNavigate)
        DrawerItem("AutoTune", Routes.AUTOTUNE, currentRoute, Icons.Outlined.Tune, onNavigate)
        DrawerItem("Basemap", Routes.BASEMAP, currentRoute, Icons.Outlined.Tune, onNavigate)
        DrawerItem("Search", Routes.GLOBAL_SEARCH, currentRoute, Icons.AutoMirrored.Outlined.Help, onNavigate)

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        DrawerSection("Fitur ZTune")
        DrawerItem("Troubleshooting Aktuator", Routes.ZTUNE_ACTUATOR_TEST, currentRoute, Icons.Outlined.Build, onNavigate)
        DrawerItem("Cam Sensor (CMP)", Routes.ZTUNE_CMP, currentRoute, Icons.Outlined.DeveloperBoard, onNavigate)
        DrawerItem("Dashboard Ketukan", Routes.ZTUNE_KNOCK, currentRoute, Icons.Outlined.BarChart, onNavigate)
        DrawerItem("Quickshifter", Routes.ZTUNE_QS, currentRoute, Icons.Outlined.Speed, onNavigate)
        DrawerItem("Injektor Primary", Routes.ZTUNE_INJ_PRIMARY, currentRoute, Icons.Outlined.Tune, onNavigate)
        DrawerItem("Injektor Stage 2", Routes.ZTUNE_INJ_STAGE2, currentRoute, Icons.Outlined.Tune, onNavigate)
        DrawerItem("VVA (Single/Dual)", Routes.ZTUNE_VVA, currentRoute, Icons.Outlined.Tune, onNavigate)
        DrawerItem("Pompa Coolant (EWP)", Routes.ZTUNE_EWP, currentRoute, Icons.Outlined.Build, onNavigate)
        DrawerItem("Valvetronic", Routes.ZTUNE_VALVETRONIC, currentRoute, Icons.Outlined.Build, onNavigate)

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        DrawerSection("Diagnostics")
        DrawerItem("Sensor Calibration", Routes.CALIBRATION, currentRoute, Icons.Outlined.Tune, onNavigate)
        DrawerItem("Tune History", Routes.TUNE_HISTORY, currentRoute, Icons.Outlined.Folder, onNavigate)
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        title,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.titleSmall
    )
}

@Composable
private fun DrawerItem(
    label: String,
    route: String,
    currentRoute: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onNavigate: (String) -> Unit
) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = currentRoute == route,
        icon = { Icon(icon, null) },
        onClick = { onNavigate(route) },
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}