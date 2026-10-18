package com.ztune.libretune.core.ecu

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Transport TCP untuk ECU ZTune lewat WiFi (ESP32 sebagai AP atau satu LAN).
 *
 * Kontrak [receive]: kembali SEGERA setelah ada >= 1 byte (maksimal expectedLength).
 * Tidak ada data dalam [pollTimeoutMs] -> mengembalikan array KOSONG (bukan error).
 * Hanya kegagalan I/O sungguhan yang melempar [TransportException].
 *
 * [context] dipakai untuk mengikat socket ke jaringan WiFi. Saat terhubung ke AP
 * ESP32 yang tidak punya internet, Android bisa tetap memakai data seluler sebagai
 * jaringan default, sehingga socket ke 192.168.4.1 gagal tanpa pengikatan ini.
 */
class TcpTransport(
    private val host: String,
    private val port: Int,
    private val context: Context? = null,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val pollTimeoutMs: Int = DEFAULT_POLL_TIMEOUT_MS
) : EcuTransport {

    @Volatile private var socket: Socket? = null
    @Volatile private var inputStream: InputStream? = null
    @Volatile private var outputStream: OutputStream? = null
    @Volatile private var connected = false

    private val readMutex = Mutex()
    private val writeMutex = Mutex()

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_POLL_TIMEOUT_MS = 200
        private const val MAX_READ = 8192
    }

    // ------------------------------------------------------------------ connect
    override suspend fun connect() {
        withContext(Dispatchers.IO) {
            if (isConnected()) return@withContext
            closeQuietly()   // bersihkan sisa koneksi lama

            val sock = Socket()
            try {
                // Ikat ke jaringan WiFi bila ada (lihat KDoc kelas).
                wifiNetwork()?.bindSocket(sock)
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.soTimeout = pollTimeoutMs
                sock.connect(InetSocketAddress(host, port), connectTimeoutMs)
            } catch (e: IOException) {
                try { sock.close() } catch (_: IOException) {}
                throw TransportException(
                    "Gagal terhubung ke TCP $host:$port — ${e.message}", e
                )
            }

            socket = sock
            inputStream = sock.getInputStream()
            outputStream = sock.getOutputStream()
            connected = true
        }
    }

    // --------------------------------------------------------------- disconnect
    override suspend fun disconnect() {
        withContext(Dispatchers.IO) { closeQuietly() }
    }

    private fun closeQuietly() {
        connected = false
        try { inputStream?.close() } catch (_: IOException) {}
        try { outputStream?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        inputStream = null
        outputStream = null
        socket = null
    }

    // --------------------------------------------------------------- isConnected
    override fun isConnected(): Boolean {
        if (!connected) return false
        val s = socket ?: return false
        return !s.isClosed && s.isConnected && !s.isInputShutdown && !s.isOutputShutdown
    }

    // -------------------------------------------------------------------- send
    override suspend fun send(data: ByteArray) {
        writeMutex.withLock {
            val out = outputStream ?: throw TransportException("Belum terhubung")
            withContext(Dispatchers.IO) {
                try {
                    out.write(data)
                    out.flush()
                } catch (e: IOException) {
                    connected = false
                    throw TransportException("Gagal kirim TCP ke $host:$port", e)
                }
            }
        }
    }

    // ------------------------------------------------------------------ receive
    override suspend fun receive(expectedLength: Int): ByteArray {
        return readMutex.withLock {
            val input = inputStream ?: throw TransportException("Belum terhubung")
            withContext(Dispatchers.IO) {
                val buf = ByteArray(expectedLength.coerceIn(1, MAX_READ))
                try {
                    val n = input.read(buf, 0, buf.size)
                    when {
                        n > 0 -> buf.copyOf(n)
                        n < 0 -> {
                            connected = false
                            throw TransportException("Koneksi TCP ditutup oleh $host:$port")
                        }
                        else -> ByteArray(0)
                    }
                } catch (_: SocketTimeoutException) {
                    // Belum ada data dalam pollTimeoutMs: BUKAN error fatal.
                    ByteArray(0)
                } catch (e: IOException) {
                    connected = false
                    throw TransportException("Error baca TCP dari $host:$port", e)
                }
            }
        }
    }

    // ------------------------------------------------------------- jaringan WiFi
    @Suppress("DEPRECATION")
    private fun wifiNetwork(): Network? {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    override fun description(): String = "TCP $host:$port"

    override fun transportType(): TransportType = TransportType.TCP
}