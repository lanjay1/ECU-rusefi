@file:Suppress("unused")

package com.ztune.libretune.ui.screens.connection

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cyclone
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ztune.libretune.R

/**
 * ECU connection screen — USB device picker, baud rate selector, WiFi (TCP)
 * connection, demo mode, connection progress, and error display.
 *
 * @param initialMode "usb" (default) or "wifi" — selects which connection
 *   section is shown first. The WiFi icon dialog in the top bar navigates
 *   here with "wifi" so the WiFi form opens directly.
 * @param onNavigateToDashboard Called after a successful connection so the
 *   host navigation graph can pop the connection route and show Dashboard.
 * @param onNavigateBack Optional back handler — when provided, a back arrow
 *   is shown in the top app bar (the screen may be opened from anywhere via
 *   the connection dialog, so an explicit exit is always available).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    initialMode: String = MODE_USB,
    onNavigateToDashboard: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    viewModel: ConnectionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Which connection method is being configured: USB OTG or WiFi (TCP).
    var mode by rememberSaveable { mutableStateOf(if (initialMode == MODE_WIFI) MODE_WIFI else MODE_USB) }

    // Auto-navigate to Dashboard after successful connection.
    LaunchedEffect(viewModel.navigateToDashboard.collectAsState().value) {
        if (viewModel.navigateToDashboard.value) {
            viewModel.clearNavigationFlag()
            onNavigateToDashboard()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conn_title)) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    if (uiState.isConnected) {
                        IconButton(onClick = { viewModel.disconnect() }) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.conn_disconnect)
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------- Connection status banner ----------
            item {
                ConnectionStatusBanner(uiState)
            }

            // ---------- Connected state ----------
            if (uiState.isConnected) {
                item {
                    ConnectedInfoCard(
                        signature = uiState.connectionSignature,
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
            } else {
                // ---------- Connection type selector (USB / WiFi) ----------
                item {
                    ConnectionModeSelector(
                        mode = mode,
                        onModeChange = { mode = it },
                        enabled = !uiState.isConnecting
                    )
                }

                when (mode) {
                    MODE_WIFI -> {
                        // ---------- WiFi (TCP) section ----------
                        item {
                            SectionHeader(title = stringResource(R.string.conn_wifi_section))
                        }
                        item {
                            WifiConnectCard(
                                host = uiState.wifiHost,
                                port = uiState.wifiPort,
                                isConnecting = uiState.isConnecting,
                                onHostChange = { viewModel.setWifiHost(it) },
                                onPortChange = { viewModel.setWifiPort(it) },
                                onConnect = { viewModel.connectWifi() }
                            )
                        }
                    }
                    else -> {
                        // ---------- USB device list section ----------
                        item {
                            SectionHeader(
                                title = stringResource(R.string.conn_devices_section),
                                action = {
                                    IconButton(onClick = { viewModel.refreshDevices() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                    }
                                }
                            )
                        }

                        if (uiState.availableDevices.isEmpty()) {
                            item {
                                EmptyDeviceCard()
                            }
                        } else {
                            itemsIndexed(uiState.availableDevices) { index, device ->
                                UsbDeviceCard(
                                    device = device,
                                    isSelected = index == uiState.selectedDeviceIndex,
                                    onClick = { viewModel.selectDevice(index) }
                                )
                            }
                        }

                        // ---------- Baud rate ----------
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            BaudRateSelector(
                                selectedBaud = uiState.baudRate,
                                onBaudSelected = { viewModel.setBaudRate(it) },
                                enabled = !uiState.isConnecting
                            )
                        }

                        // ---------- Action buttons ----------
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            ActionButtons(
                                canConnect = uiState.selectedDeviceIndex >= 0 && !uiState.isConnecting,
                                isConnecting = uiState.isConnecting,
                                onConnect = { viewModel.connectToDevice() },
                                onDemo = { viewModel.connectDemoEcu() },
                                enabled = !uiState.isConnecting
                            )
                        }
                    }
                }
            }

            // Bottom spacing for the FAB / last item
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

// ========================================================================
//  Sub-composables
// ========================================================================

/** Connection method constants (also used by the navigation route arg). */
const val MODE_USB = "usb"
const val MODE_WIFI = "wifi"

@Composable
private fun ConnectionModeSelector(
    mode: String,
    onModeChange: (String) -> Unit,
    enabled: Boolean
) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        SegmentedButton(
            selected = mode == MODE_USB,
            onClick = { if (enabled) onModeChange(MODE_USB) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            icon = {
                Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            label = { Text(stringResource(R.string.conn_mode_usb)) }
        )
        SegmentedButton(
            selected = mode == MODE_WIFI,
            onClick = { if (enabled) onModeChange(MODE_WIFI) },
            enabled = enabled,
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            icon = {
                Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
            },
            label = { Text(stringResource(R.string.conn_mode_wifi)) }
        )
    }
}

@Composable
private fun WifiConnectCard(
    host: String,
    port: String,
    isConnecting: Boolean,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.conn_wifi_section),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = host,
                onValueChange = onHostChange,
                label = { Text(stringResource(R.string.conn_wifi_host)) },
                singleLine = true,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = port,
                onValueChange = onPortChange,
                label = { Text(stringResource(R.string.conn_wifi_port)) },
                singleLine = true,
                enabled = !isConnecting,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isConnecting && host.isNotBlank() && port.isNotBlank()
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.conn_status_connecting))
                } else {
                    Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.conn_wifi_connect))
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusBanner(state: ConnectionUiState) {
    when {
        state.isConnecting -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.conn_status_connecting),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    )
                }
            }
        }
        state.connectionError != null -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = state.connectionError,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        state.isConnected -> {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.conn_status_connected),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedInfoCard(
    signature: String?,
    onDisconnect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "ECU Connected",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (signature != null) {
                Text(
                    text = "Signature: $signature",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onDisconnect,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.conn_disconnect))
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        action()
    }
}

@Composable
private fun EmptyDeviceCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Filled.Usb,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.conn_no_devices),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

@Composable
private fun UsbDeviceCard(
    device: UsbDeviceInfo,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
            contentColor = if (isSelected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        border = if (isSelected) {
            CardDefaults.outlinedCardBorder()
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Usb,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "VID: 0x${device.vendorId.toString(16).uppercase()}  " +
                            "PID: 0x${device.productId.toString(16).uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BaudRateSelector(
    selectedBaud: Int,
    onBaudSelected: (Int) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.settings_usb_baud),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (enabled) expanded = it }
        ) {
            OutlinedTextField(
                value = "${selectedBaud} baud",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
                enabled = enabled
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                BAUD_RATES.forEach { baud ->
                    DropdownMenuItem(
                        text = { Text("$baud baud") },
                        onClick = {
                            onBaudSelected(baud)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionButtons(
    canConnect: Boolean,
    isConnecting: Boolean,
    onConnect: () -> Unit,
    onDemo: () -> Unit,
    enabled: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            enabled = canConnect && enabled
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.conn_status_connecting))
            } else {
                Icon(Icons.Filled.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect ECU")
            }
        }
        OutlinedButton(
            onClick = onDemo,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        ) {
            Icon(Icons.Default.Science, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.conn_demo_button))
        }
    }
}