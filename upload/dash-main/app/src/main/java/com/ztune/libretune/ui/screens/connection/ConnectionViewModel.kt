@file:Suppress("unused")

package com.ztune.libretune.ui.screens.connection

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.ztune.libretune.core.AppSettings
import com.ztune.libretune.core.EcuConnectionManager
import com.ztune.libretune.core.EcuDefinitionRepository
import com.ztune.libretune.core.ecu.EcuTransport
import com.ztune.libretune.core.ecu.TcpTransport
import com.ztune.libretune.core.ecu.UsbSerialTransport
import com.ztune.libretune.core.ini.EcuDefinition
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import kotlin.coroutines.resume

/**
 * A discovered USB serial device for display in the connection list.
 */
data class UsbDeviceInfo(
    val name: String,
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val usbDevice: UsbDevice? = null
)

/**
 * UI state for the Connection screen.
 *
 * @property availableDevices    List of detected USB serial devices.
 * @property selectedDeviceIndex Index into [availableDevices] of the user's selection.
 * @property baudRate            Currently selected baud rate.
 * @property isConnecting        Whether a connection attempt is in progress.
 * @property connectionError     Human-readable error string, or null.
 * @property isConnected         Whether the ECU is currently connected.
 * @property connectionSignature ECU signature after successful handshake.
 */
data class ConnectionUiState(
    val availableDevices: List<UsbDeviceInfo> = emptyList(),
    val selectedDeviceIndex: Int = -1,
    val baudRate: Int = 115200,
    val isConnecting: Boolean = false,
    val connectionError: String? = null,
    val isConnected: Boolean = false,
    val connectionSignature: String? = null,
    // --- WiFi (TCP) connection ---
    val wifiHost: String = DEFAULT_WIFI_HOST,
    val wifiPort: String = DEFAULT_WIFI_PORT
) {
    companion object {
        /** Default host/port for ESP32-based WiFi ECU adapters. */
        const val DEFAULT_WIFI_HOST = "192.168.4.1"
        const val DEFAULT_WIFI_PORT = "8888"
    }
}

/**
 * Common baud rates for ECU serial communication.
 */
val BAUD_RATES = listOf(
    9600, 19200, 38400, 57600, 115200, 230400, 460800, 921600
)

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connectionManager: EcuConnectionManager,
    private val settings: AppSettings,
    private val definitionRepository: EcuDefinitionRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    /** Emitted once when a connection succeeds so the UI can navigate away. */
    private val _navigateToDashboard = MutableStateFlow(false)
    val navigateToDashboard: StateFlow<Boolean> = _navigateToDashboard.asStateFlow()

    init {
        // Load the saved default baud rate.
        _uiState.update { it.copy(baudRate = settings.usbBaudRate.value) }

        // Observe the saved baud rate for live updates from settings.
        viewModelScope.launch {
            settings.usbBaudRate.collect { baud ->
                _uiState.update { it.copy(baudRate = baud) }
            }
        }

        // Observe the ECU connection manager state.
        viewModelScope.launch {
            connectionManager.state.collect { connState ->
                _uiState.update { prev ->
                    prev.copy(
                        isConnecting = connState.status == com.ztune.libretune.core.EcuConnectionStatus.CONNECTING,
                        isConnected = connState.status == com.ztune.libretune.core.EcuConnectionStatus.CONNECTED,
                        connectionError = connState.lastError,
                        connectionSignature = connState.signature
                    )
                }
                // Auto-navigate to dashboard on successful connection.
                if (connState.status == com.ztune.libretune.core.EcuConnectionStatus.CONNECTED) {
                    _navigateToDashboard.update { true }
                }
            }
        }

        // Initial device scan.
        refreshDevices()
    }

    // ========================================================================
    //  Public actions
    // ========================================================================

    /** Enumerate available USB serial devices. */
    fun refreshDevices() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val devices = mutableListOf<UsbDeviceInfo>()

        if (usbManager != null) {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            for (driver in drivers) {
                val dev = driver.device
                devices.add(
                    UsbDeviceInfo(
                        name = buildString {
                            append(driver.javaClass.simpleName)
                            if (dev.productName != null) append(" – ${dev.productName}")
                        },
                        deviceId = dev.deviceId,
                        vendorId = dev.vendorId,
                        productId = dev.productId,
                        usbDevice = dev
                    )
                )
            }
        }

        _uiState.update { prev ->
            val newIdx = if (prev.selectedDeviceIndex >= devices.size) -1
            else prev.selectedDeviceIndex
            prev.copy(availableDevices = devices, selectedDeviceIndex = newIdx)
        }
    }

    /** Select a device from the list by index. */
    fun selectDevice(index: Int) {
        _uiState.update { it.copy(selectedDeviceIndex = index, connectionError = null) }
    }

    /** Update the baud rate. */
    fun setBaudRate(baud: Int) {
        settings.setUsbBaudRate(baud)
        // The settings flow collector will update uiState, but we also set it
        // eagerly so the UI responds immediately.
        _uiState.update { it.copy(baudRate = baud) }
    }

    /** Update the WiFi host/IP field. */
    fun setWifiHost(host: String) {
        _uiState.update { it.copy(wifiHost = host) }
    }

    /** Update the WiFi port field (digits only, max 5 chars). */
    fun setWifiPort(port: String) {
        _uiState.update { it.copy(wifiPort = port.filter { ch -> ch.isDigit() }.take(5)) }
    }

    /**
     * Connect to the selected USB device.
     *
     * Flow:
     *   1. Validate device selection
     *   2. Check USB permission — if not granted, request via [UsbManager.requestPermission]
     *      with a [BroadcastReceiver] + [PendingIntent], and suspend until the user
     *      accepts/denies.
     *   3. Open transport
     *   4. Resolve INI definition (selalu ztune.ini)
     *   5. Connect via [EcuConnectionManager]
     */
    fun connectToDevice() {
        val state = _uiState.value
        val deviceInfo = state.availableDevices.getOrNull(state.selectedDeviceIndex)
        val usbDevice = deviceInfo?.usbDevice
            ?: run {
                _uiState.update { it.copy(connectionError = "No device selected") }
                return
            }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: run {
                _uiState.update { it.copy(connectionError = "USB service unavailable") }
                return
            }

        _uiState.update { it.copy(isConnecting = true, connectionError = null) }

        viewModelScope.launch {
            var transport: EcuTransport? = null
            try {
                // ---- Phase 2: USB permission flow ----
                // If permission not yet granted, request it and suspend until
                // the user accepts or denies. Cancellation-safe: if the user
                // navigates away (viewModelScope cancelled), the receiver is
                // unregistered and the coroutine resumes with false.
                if (!usbManager.hasPermission(usbDevice)) {
                    Log.i(TAG, "Requesting USB permission for device ${usbDevice.deviceName}")
                    val granted = requestUsbPermission(usbManager, usbDevice)
                    if (!granted) {
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                connectionError = "USB permission denied by user"
                            )
                        }
                        return@launch
                    }
                    Log.i(TAG, "USB permission granted for device ${usbDevice.deviceName}")
                }

                // ---- Open transport ----
                transport = UsbSerialTransport(
                    usbManager = usbManager,
                    device = usbDevice,
                    baudRate = state.baudRate
                )
                transport.connect()

                // ---- Resolve definition (ztune.ini) and connect ----
                val definition = identifyAndResolveDefinition(transport)
                connectionManager.connect(transport, definition)
           } catch (e: Exception) {
                runCatching { transport?.disconnect() }
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectionError = e.message ?: "Connection failed"
                    )
                }
            }
        }
    }

    // ========================================================================
    //  Phase 2: USB permission flow
    // ========================================================================

    /**
     * Request USB permission for [device] from the user via a system dialog.
     *
     * Registers a [BroadcastReceiver] for [UsbManager.ACTION_USB_PERMISSION],
     * launches the permission [PendingIntent] via [UsbManager.requestPermission],
     * and suspends until the user accepts or denies (or the coroutine is
     * cancelled).
     *
     * This function is cancellation-safe: if the coroutine is cancelled
     * (e.g. user navigates away, ViewModel cleared), the receiver is
     * unregistered and the coroutine resumes with `false`.
     *
     * @return `true` if permission was granted, `false` if denied or
     *         cancelled.
     */
    private suspend fun requestUsbPermission(
        usbManager: UsbManager,
        device: UsbDevice
    ): Boolean = suspendCancellableCoroutine { cont ->

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return

                val granted = intent.getBooleanExtra(
                    UsbManager.EXTRA_PERMISSION_GRANTED, false
                )
                val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                }

                Log.i(TAG, "USB permission broadcast: granted=$granted, device=${dev?.deviceName}")

                runCatching {
                    context?.unregisterReceiver(this)
                }

                if (cont.isActive) {
                    cont.resume(granted)
                }
            }
        }

        // Register receiver. Use RECEIVER_NOT_EXPORTED on API 33+ since the
        // broadcast comes from the system (UsbManager), not from another app.
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        // Build the PendingIntent that the system will use to deliver the
        // permission result to our receiver.
        val intent = Intent(ACTION_USB_PERMISSION)
        intent.setPackage(context.packageName)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            pendingIntentFlags
        )

        // If the coroutine is cancelled, unregister the receiver so we don't
        // leak it and don't resume after the caller is gone.
        cont.invokeOnCancellation {
            runCatching {
                context.unregisterReceiver(receiver)
            }
        }

        // Request permission — this shows the system permission dialog.
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Demo/mock ECU — SEMENTARA DINONAKTIFKAN.
     * Mock lama menirukan protokol MegaSquirt (sudah dihapus). Akan diganti
     * MockZtuneTransport yang menirukan frame ZTune (0x55 0xAA + JSON + CRC-16).
     */
    fun connectDemoEcu() {
        _uiState.update {
            it.copy(
                isConnecting = false,
                connectionError = "Mode demo belum tersedia (menunggu MockZtuneTransport)"
            )
        }
    }

    /**
     * Connect to an ECU over WiFi using a raw TCP socket ([TcpTransport]).
     *
     * Typical use case: ESP32-based ZTune ECU yang membuka port TCP.
     * Host/port are read from [uiState] ([ConnectionUiState.wifiHost] /
     * [ConnectionUiState.wifiPort]) as edited on the Connection screen.
     */
    fun connectWifi() {
        val state = _uiState.value
        val host = state.wifiHost.trim()
        val port = state.wifiPort.toIntOrNull()

        if (host.isEmpty()) {
            _uiState.update { it.copy(connectionError = "WiFi host/IP must not be empty") }
            return
        }
        if (port == null || port !in 1..65535) {
            _uiState.update { it.copy(connectionError = "WiFi port must be 1–65535") }
            return
        }

        _uiState.update { it.copy(isConnecting = true, connectionError = null) }

        viewModelScope.launch {
            var transport: EcuTransport? = null
            try {
                transport = TcpTransport(host = host, port = port, context = context)
                transport.connect()

                val definition = identifyAndResolveDefinition(transport)
                connectionManager.connect(transport, definition)
            } catch (e: Exception) {
                runCatching { transport?.disconnect() }
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        connectionError = e.message ?: "WiFi connection failed"
                    )
                }
            }
        }
    }

    // ========================================================================
    //  Definition resolution
    // ========================================================================

    /**
     * Hanya ada firmware ZTune: tidak ada lagi query signature protokol MS.
     * Definisi selalu ztune.ini. Handshake + cek versi dilakukan
     * ZtuneEcu.connect() lewat EcuConnectionManager.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun identifyAndResolveDefinition(transport: EcuTransport): EcuDefinition =
        definitionRepository.loadZtune().getOrElse { e ->
            throw IllegalStateException("ztune.ini gagal dimuat: ${e.message}", e)
        }

    /** Disconnect the currently connected ECU. */
    fun disconnect() {
        connectionManager.disconnect()
        _uiState.update {
            it.copy(
                isConnecting = false,
                isConnected = false,
                connectionError = null,
                connectionSignature = null
            )
        }
    }

    /** Clear the navigation flag after the UI has handled it. */
    fun clearNavigationFlag() {
        _navigateToDashboard.update { false }
    }

    /** Dismiss the current error banner. */
    fun dismissError() {
        _uiState.update { it.copy(connectionError = null) }
    }

    private companion object {
        private const val TAG = "ConnectionVM"
        private const val ACTION_USB_PERMISSION = "android.hardware.usb.action.USB_PERMISSION"
    }
}