package com.ztune.libretune.core.ecu

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Transport USB serial (usb-serial-for-android), termasuk USB-C CDC native
 * ESP32-S3 (baud tidak berpengaruh pada CDC native, tapi tetap diisi).
 *
 * Kontrak [receive]: kembali SEGERA setelah ada >= 1 byte. Tidak ada data dalam
 * [POLL_TIMEOUT_MS] -> array KOSONG (bukan error). Error I/O (mis. kabel
 * terlepas) melempar [TransportException] dan menandai transport putus.
 *
 * [assertDtr]/[assertRts]: default true (perilaku lama). Bila board ESP32 ter-reset
 * setiap kali app konek (jalur auto-reset USB-UART), set [assertRts] = false.
 */
class UsbSerialTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val baudRate: Int = DEFAULT_BAUD_RATE,
    private val dataBits: Int = UsbSerialPort.DATABITS_8,
    private val stopBits: Int = UsbSerialPort.STOPBITS_1,
    private val parity: Int = UsbSerialPort.PARITY_NONE,
    private val assertDtr: Boolean = true,
    private val assertRts: Boolean = true
) : EcuTransport {

    @Volatile private var port: UsbSerialPort? = null
    @Volatile private var _connected = false
    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    companion object {
        const val DEFAULT_BAUD_RATE = 115200
        const val POLL_TIMEOUT_MS = 200
        const val WRITE_TIMEOUT_MIN_MS = 200
        private const val MAX_READ = 4096
        private const val MIN_READ_BUFFER = 512   // library meminta buffer >= max packet size
    }

    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            // Idempoten: sudah terhubung -> kembali.
            if (_connected) return@withContext

            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver = drivers.find { it.device == device }
                ?: throw TransportException(
                    "No USB serial driver found for device ${device.deviceName}"
                )

            val connection: UsbDeviceConnection = usbManager.openDevice(device)
                ?: throw TransportException("Failed to open USB device. Check permissions.")

            val p = driver.ports.firstOrNull()
            if (p == null) {
                connection.close()
                throw TransportException("No serial ports on device")
            }

            try {
                p.open(connection)
                p.setParameters(baudRate, dataBits, stopBits, parity)
                p.dtr = assertDtr
                p.rts = assertRts
            } catch (e: Exception) {
                try { p.close() } catch (_: Exception) {}
                connection.close()
                throw TransportException("Gagal membuka port USB: ${e.message}", e)
            }
            port = p
            _connected = true
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            _connected = false
            try {
                port?.close()
            } catch (_: Exception) {
                // abaikan error close
            }
            port = null
        }
    }

    override fun isConnected(): Boolean = _connected

    override suspend fun send(data: ByteArray) {
        writeMutex.withLock {
            val p = port ?: throw TransportException("Not connected")
            withContext(Dispatchers.IO) {
                try {
                    p.write(data, writeTimeoutFor(data.size))
                } catch (e: IOException) {
                    _connected = false
                    throw TransportException("USB write error (kabel terlepas?)", e)
                }
            }
        }
    }

    override suspend fun receive(expectedLength: Int): ByteArray {
        return readMutex.withLock {
            val p = port ?: throw TransportException("Not connected")
            withContext(Dispatchers.IO) {
                val cap = expectedLength.coerceIn(1, MAX_READ)
                val buf = ByteArray(maxOf(cap, MIN_READ_BUFFER))
                try {
                    val n = p.read(buf, POLL_TIMEOUT_MS)
                    if (n > 0) buf.copyOf(n) else ByteArray(0)
                } catch (e: IOException) {
                    _connected = false
                    throw TransportException("USB read error (kabel terlepas?)", e)
                }
            }
        }
    }

    /** Timeout tulis: waktu kirim teoretis (10 bit/byte) + 500 ms, antara 200 ms dan 10 detik. */
    private fun writeTimeoutFor(bytes: Int): Int {
        val ms = bytes * 10_000L / baudRate.coerceAtLeast(1) + 500L
        return ms.coerceIn(WRITE_TIMEOUT_MIN_MS.toLong(), 10_000L).toInt()
    }

    override fun description(): String =
        "USB Serial: ${device.deviceName} @ ${baudRate}baud"

    override fun transportType(): TransportType = TransportType.USB_SERIAL

    /** Daftar semua perangkat USB serial yang tersedia. */
    fun availableDevices(): List<UsbDevice> {
        return UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .map { it.device }
    }
}