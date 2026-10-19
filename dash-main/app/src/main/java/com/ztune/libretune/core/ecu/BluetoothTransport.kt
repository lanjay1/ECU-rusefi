package com.ztune.libretune.core.ecu

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (SPP / RFCOMM) transport untuk komunikasi ECU.
 *
 * Butuh izin runtime `BLUETOOTH_CONNECT` di Android 12+ (diminta oleh UI).
 *
 * Kontrak [receive]: kembali SEGERA setelah ada >= 1 byte; tidak ada data dalam
 * [pollTimeoutMs] -> array KOSONG (bukan error). Pembacaan memakai available()
 * + delay yang bisa dibatalkan, BUKAN read() yang memblokir tanpa batas.
 */
class BluetoothTransport(
    private val context: Context,
    private val deviceNameOrAddress: String,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val pollTimeoutMs: Int = DEFAULT_POLL_TIMEOUT_MS
) : EcuTransport {

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var _connected = false
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    companion object {
        /** UUID SPP standar yang dipakai hampir semua adapter. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        const val DEFAULT_POLL_TIMEOUT_MS = 200
        private const val MAX_READ = 4096
    }

    // ------------------------------------------------------------------ connect
    @SuppressLint("MissingPermission")
    override suspend fun connect() {
        if (_connected) return

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager
            ?: throw TransportException("Bluetooth not available on this device")

        val adapter: BluetoothAdapter = bluetoothManager.adapter
            ?: throw TransportException("Bluetooth adapter not available")

        if (!adapter.isEnabled) {
            throw TransportException("Bluetooth is disabled. Please enable Bluetooth first.")
        }

        val target: BluetoothDevice = resolveDevice(adapter, deviceNameOrAddress)

        // Discovery yang sedang berjalan memperlambat dan sering menggagalkan connect().
        try { adapter.cancelDiscovery() } catch (_: SecurityException) {}

        // Coba soket secure dulu; bila gagal CEPAT (bukan timeout), coba insecure
        // (banyak modul SPP murah butuh itu).
        val t0 = System.nanoTime()
        val sock: BluetoothSocket = try {
            openAndConnect(target, secure = true)
        } catch (e: TransportException) {
            val elapsedMs = (System.nanoTime() - t0) / 1_000_000
            if (elapsedMs >= connectTimeoutMs * 0.8) throw e
            openAndConnect(target, secure = false)
        }

        socket = sock
        inputStream = sock.inputStream
        outputStream = sock.outputStream
        _connected = true
    }

    /**
     * Buat soket lalu connect dengan deadline NYATA: watchdog menutup soket dari
     * coroutine lain setelah [connectTimeoutMs], yang membangunkan connect()
     * yang sedang memblokir dengan IOException.
     */
    @SuppressLint("MissingPermission")
    private suspend fun openAndConnect(target: BluetoothDevice, secure: Boolean): BluetoothSocket {
        val sock: BluetoothSocket = try {
            if (secure) target.createRfcommSocketToServiceRecord(SPP_UUID)
            else target.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
        } catch (e: IOException) {
            throw TransportException("Failed to create RFCOMM socket for ${target.address}", e)
        }

        @Volatile var timedOut = false
        try {
            coroutineScope {
                val watchdog = launch {
                    delay(connectTimeoutMs.toLong())
                    timedOut = true
                    try { sock.close() } catch (_: IOException) {}
                }
                try {
                    withContext(Dispatchers.IO) { sock.connect() }
                } finally {
                    watchdog.cancel()
                }
            }
        } catch (e: IOException) {
            try { sock.close() } catch (_: IOException) {}
            val label = target.name ?: target.address
            throw TransportException(
                if (timedOut) "Bluetooth connect timeout setelah ${connectTimeoutMs}ms ke $label"
                else "Gagal terhubung ke Bluetooth $label (${if (secure) "secure" else "insecure"}): ${e.message}",
                e
            )
        }
        return sock
    }

    // --------------------------------------------------------------- disconnect
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _connected = false
            try { inputStream?.close() } catch (_: IOException) {}
            try { outputStream?.close() } catch (_: IOException) {}
            try { socket?.close() } catch (_: IOException) {}
            inputStream = null
            outputStream = null
            socket = null
        }
    }

    // --------------------------------------------------------------- isConnected
    override fun isConnected(): Boolean = _connected && socket?.isConnected == true

    // -------------------------------------------------------------------- send
    override suspend fun send(data: ByteArray) {
        writeMutex.withLock {
            val out = outputStream ?: throw TransportException("Not connected")
            withContext(Dispatchers.IO) {
                try {
                    out.write(data)
                    out.flush()
                } catch (e: IOException) {
                    _connected = false
                    throw TransportException("Bluetooth write failed", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ receive
    override suspend fun receive(expectedLength: Int): ByteArray {
        return readMutex.withLock {
            val input = inputStream ?: throw TransportException("Not connected")
            val cap = expectedLength.coerceIn(1, MAX_READ)
            val deadline = System.nanoTime() + pollTimeoutMs * 1_000_000L
            withContext(Dispatchers.IO) {
                var out: ByteArray? = null
                try {
                    while (out == null) {
                        val avail = input.available()
                        if (avail > 0) {
                            val buf = ByteArray(minOf(avail, cap))
                            val n = input.read(buf, 0, buf.size)
                            if (n < 0) throw IOException("stream closed by remote")
                            out = buf.copyOf(n)
                        } else if (System.nanoTime() >= deadline) {
                            out = ByteArray(0)     // belum ada data — BUKAN error
                        } else {
                            delay(5)               // bisa dibatalkan
                        }
                    }
                } catch (e: IOException) {
                    _connected = false
                    throw TransportException("Bluetooth read error", e)
                }
                out ?: ByteArray(0)
            }
        }
    }

    // --------------------------------------------------------------- description
    @SuppressLint("MissingPermission")
    override fun description(): String {
        val name = try { socket?.remoteDevice?.name } catch (_: SecurityException) { null }
        val address = socket?.remoteDevice?.address ?: deviceNameOrAddress
        return if (name != null) "BT: $name ($address)" else "BT: $address"
    }

    override fun transportType(): TransportType = TransportType.BLUETOOTH

    // --------------------------------------------------------- private helpers

    /** Resolusi nama/alamat MAC menjadi [BluetoothDevice] di antara perangkat yang sudah dipasangkan. */
    @SuppressLint("MissingPermission")
    private fun resolveDevice(adapter: BluetoothAdapter, target: String): BluetoothDevice {
        if (BluetoothAdapter.checkBluetoothAddress(target)) {
            val device = adapter.getRemoteDevice(target)
            if (device in adapter.bondedDevices) return device
            throw TransportException(
                "Bluetooth device $target is not paired. Pair it in system settings first."
            )
        }

        val matched = adapter.bondedDevices.firstOrNull {
            it.name?.equals(target, ignoreCase = true) == true
        }
        if (matched != null) return matched

        throw TransportException(
            "No paired Bluetooth device found matching \"$target\". " +
                "Available paired devices: ${adapter.bondedDevices.map { "${it.name} (${it.address})" }}"
        )
    }

    /** Daftar nama dan alamat semua perangkat Bluetooth yang sudah dipasangkan. */
    @SuppressLint("MissingPermission")
    fun pairedDevices(): List<PairedBluetoothDevice> {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as? BluetoothManager
            ?: return emptyList()
        val adapter = manager.adapter ?: return emptyList()
        return adapter.bondedDevices.map {
            PairedBluetoothDevice(name = it.name ?: "Unknown", address = it.address)
        }
    }

    /** Deskripsi sederhana perangkat Bluetooth yang sudah dipasangkan. */
    data class PairedBluetoothDevice(val name: String, val address: String)
}