package com.ztune.libretune.core

import android.util.Log
import com.ztune.libretune.core.autotune.AutoTuneController
import com.ztune.libretune.core.ecu.*
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.realtime.RealtimeChannelStore
import com.ztune.libretune.core.realtime.RealtimeDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/** Possible states of an ECU connection. */
enum class EcuConnectionStatus {
    DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, SYNCING, ERROR
}

/** Snapshot of the current connection state, observed by the UI via [StateFlow]. */
data class EcuConnectionState(
    val status: EcuConnectionStatus = EcuConnectionStatus.DISCONNECTED,
    val transportName: String? = null,
    val signature: String? = null,
    val lastError: String? = null,
    val reconnectAttempt: Int = 0,
    val reconnectMaxAttempts: Int = 0,
    val syncProgress: Float = 0f,
    val syncedPages: Int = 0,
    val totalPages: Int = 0
)

/**
 * Mengelola seluruh siklus hidup koneksi ECU: connect, handshake, streaming
 * realtime, auto-reconnect (exponential backoff), sync data, dan burn page.
 *
 * @param applicationScope  Scope coroutine seumur aplikasi.
 * @param settings          Setelan aplikasi (baud, konfigurasi reconnect).
 * @param channelStore      Diisi nilai channel realtime hasil decode.
 * @param dataLogManager    Menerima update realtime saat merekam.
 */
class EcuConnectionManager(
    private val applicationScope: CoroutineScope,
    private val settings: AppSettings,
    private val channelStore: RealtimeChannelStore,
    private val dataLogManager: DataLogManager,
    private val tuneManager: TuneManager,
    private val appContext: android.content.Context
) {
    private val _state = MutableStateFlow(EcuConnectionState())
    val state: StateFlow<EcuConnectionState> = _state.asStateFlow()

    @Volatile var ecuInterface: EcuInterface? = null; private set
    @Volatile var transport: EcuTransport? = null; private set
    @Volatile var activeDefinition: EcuDefinition? = null; private set
    var autoTuneController: AutoTuneController? = null

    private var realtimeDecoder: RealtimeDecoder? = null
    private var connectJob: Job? = null
    private var tuneLoadJob: Job? = null
    private var streamJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var generation = 0L

    /** Waktu (ms, monotonic) stream loop terakhir dimulai — masa tenang heartbeat. */
    @Volatile private var lastStreamStartMs = 0L

    /**
     * true selama transaksi burn berjalan. Atomik: dua burn serentak tidak
     * mungkin lolos bersamaan. Heartbeat WAJIB mengecek flag ini sebelum
     * restart stream / commReset supaya transaksi tidak terputus di tengah.
     */
    private val burnActive = AtomicBoolean(false)

    val isConnected: Boolean get() = state.value.status == EcuConnectionStatus.CONNECTED

    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    // ------------------------------------------------------------------
    //  Connect
    // ------------------------------------------------------------------

    /**
     * Connect ke ECU: buat antarmuka lewat [EcuFactory], buka transport,
     * panggil [EcuInterface.connect] (handshake), lalu mulai streaming.
     */
    fun connect(transport: EcuTransport, definition: EcuDefinition) {
        disconnectInternal()
        val gen = ++generation
        _state.update { resetState(it, EcuConnectionStatus.CONNECTING, transport.description()) }

        connectJob = applicationScope.launch {
            var created: EcuInterface? = null
            try {
                val ecu = EcuFactory.create(definition.ecuType)
                created = ecu
                guard(gen) { transport.connect() }
                guard(gen) { unwrap(ecu.connect(transport, definition), "ECU connect failed") }
                val signature = guard(gen) { unwrap(ecu.querySignature(), "Signature query failed") }

                // disconnect()/connect() baru bisa terjadi selama handshake:
                // jangan pasang state basi dan jangan menyalakan service.
                if (gen != generation) throw CancellationException("Generation superseded")

                this@EcuConnectionManager.ecuInterface = ecu
                this@EcuConnectionManager.transport = transport
                activeDefinition = definition
                realtimeDecoder = RealtimeDecoder(definition)

                _state.update { it.copy(status = EcuConnectionStatus.CONNECTED, signature = signature) }
                // JANGAN panggil ecu.startStreaming(): loop manager di bawah adalah
                // satu-satunya loop yang mengisi channelStore → UI.
                startStreamLoop(ecu, definition, gen)
                startHeartbeatMonitor(gen)

                // Muat tune penuh dari ECU di background. Gagal muat TIDAK memutus
                // koneksi — dicatat di lastError.
                tuneLoadJob?.cancel()
                tuneLoadJob = applicationScope.launch(Dispatchers.IO) {
                    try {
                        tuneManager.loadFromEcu(ecu, definition).getOrThrow()
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        if (gen == generation) {
                            _state.update {
                                it.copy(lastError =
                                    "Tune load failed: ${e.message ?: e.javaClass.simpleName}")
                            }
                        }
                    }
                }

                // Foreground service hanya untuk koneksi hardware sungguhan
                // (mock tidak butuh; Android 14+ menolak tipe connectedDevice
                // tanpa izin hardware). Kegagalan start service TIDAK boleh
                // membatalkan koneksi.
                val isMockConnection = transport.transportType() == TransportType.MOCK
                if (!isMockConnection) {
                    runCatching { EcuCommunicationService.start(appContext) }
                        .onFailure { Log.w(TAG, "Foreground service gagal dimulai: ${it.message}") }
                }
            } catch (e: CancellationException) {
                // Dibatalkan (disconnect / connect baru): bersihkan apa yang
                // sudah dibuat supaya port tidak bocor.
                cleanupAsync(created, transport)
                throw e
            } catch (e: Exception) {
                if (gen != generation) {
                    cleanupAsync(created, transport)
                    return@launch
                }
                releaseResources()
                val fatal = e is ProtocolException &&
                    (e.message?.contains("inkompatibel") == true ||
                        e.message?.contains("signature mismatch") == true)
                _state.update {
                    it.copy(status = EcuConnectionStatus.ERROR, lastError = e.message ?: e.javaClass.simpleName)
                }
                if (fatal || !settings.autoReconnect.value) {
                    cleanupAsync(created, transport)
                } else {
                    // Tutup sisa koneksi; attemptReconnect membuka ulang transport.
                    runCatching { created?.disconnect() }
                    attemptReconnect(transport, definition)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Streaming loop
    // ------------------------------------------------------------------

    private fun startStreamLoop(ecu: EcuInterface, definition: EcuDefinition, gen: Long) {
        streamJob?.cancel()
        lastStreamStartMs = nowMs()
        streamJob = applicationScope.launch(Dispatchers.IO) {
            val decoder = RealtimeDecoder(definition)
            while (isActive && gen == generation) {
                try {
                    val rawData = ecu.readRealtimeData().getOrNull()
                    if (rawData == null) { delay(50); continue }
                    val decoded = decoder.decodeRealtimeData(rawData)
                    channelStore.updateChannels(decoded)

                    if (dataLogManager.recordingState.value == DataLogRecordingState.RECORDING) {
                        dataLogManager.onRealtimeUpdate(decoded)
                    }
                    autoTuneController?.let { ctrl ->
                        if (ctrl.state.value.isRunning) ctrl.feedSample(decoded)
                    }
                    delay(20) // ~50 Hz throttle
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(100) // error baca sementara — mundur sebentar
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Heartbeat monitor
    // ------------------------------------------------------------------

    private fun startHeartbeatMonitor(gen: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = applicationScope.launch {
            while (isActive && gen == generation) {
                delay(HEARTBEAT_CHECK_MS)
                if (gen != generation) break
                // Jangan ganggu transaksi burn. Selama burn stream memang bisa
                // berhenti — itu normal, bukan link mati.
                if (burnActive.get()) continue
                // Masa tenang setelah stream (re)start: tunggu data pertama.
                if (nowMs() - lastStreamStartMs < HEARTBEAT_TIMEOUT_MS) continue
                if (!channelStore.isReceivingData(HEARTBEAT_TIMEOUT_MS)) {
                    restartStreamWithBackoff(gen)
                }
            }
        }
    }

    /**
     * Restart stream dengan exponential backoff. Bila transport sudah putus,
     * atau restart gagal [MAX_RESTARTS] kali, lanjut ke reconnect penuh
     * (bukan berhenti di ERROR).
     */
    private suspend fun restartStreamWithBackoff(gen: Long) {
        val ecu = ecuInterface ?: return
        val def = activeDefinition ?: return
        val t = transport ?: return

        if (!t.isConnected()) {
            attemptReconnect(t, def)
            return
        }

        repeat(MAX_RESTARTS) { attempt ->
            if (gen != generation) return
            delay((BASE_BACKOFF_MS * 2.0.pow(attempt)).toLong().coerceAtMost(MAX_BACKOFF_MS))
            // Cek ulang SETELAH delay: burn bisa dimulai selama menunggu.
            if (gen != generation || burnActive.get()) return
            try {
                ecu.stopStreaming()
                ecu.commReset()
                startStreamLoop(ecu, def, gen)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* percobaan berikutnya */ }
        }
        attemptReconnect(t, def)
    }

    // ------------------------------------------------------------------
    //  Auto-reconnect
    // ------------------------------------------------------------------

    private fun attemptReconnect(transport: EcuTransport, definition: EcuDefinition) {
        if (!settings.autoReconnect.value) {
            _state.update {
                it.copy(status = EcuConnectionStatus.ERROR,
                    lastError = it.lastError ?: "Koneksi terputus")
            }
            cleanupAsync(null, transport)
            return
        }

        val myGen = generation
        val oldEcu = ecuInterface
        reconnectJob?.cancel()
        // Hentikan heartbeat dan stream lama supaya tidak berebut dengan reconnect.
        heartbeatJob?.cancel(); heartbeatJob = null
        streamJob?.cancel(); streamJob = null

        val maxAttempts = settings.reconnectMaxAttempts.value
        val baseDelay = settings.reconnectDelayMs.value
        _state.update {
            it.copy(status = EcuConnectionStatus.RECONNECTING,
                reconnectMaxAttempts = maxAttempts, reconnectAttempt = 0)
        }

        reconnectJob = applicationScope.launch {
            // Tutup antarmuka lama (juga menutup transport) sebelum membuka ulang.
            runCatching { oldEcu?.disconnect() }

            for (attempt in 1..maxAttempts) {
                val delayMs = (baseDelay * 2.0.pow(attempt - 1)).toLong().coerceAtMost(MAX_RECONNECT_DELAY_MS)
                _state.update { it.copy(reconnectAttempt = attempt) }
                delay(delayMs)

                // Pengguna bisa disconnect / connect baru selama delay.
                if (myGen != generation) return@launch

                var ecu: EcuInterface? = null
                try {
                    if (!transport.isConnected()) transport.connect()
                    val newEcu = EcuFactory.create(definition.ecuType)
                    ecu = newEcu
                    if (newEcu.connect(transport, definition).isFailure) {
                        runCatching { newEcu.disconnect() }
                        continue
                    }
                    val sig = newEcu.querySignature().getOrNull()
                    if (sig == null) {
                        runCatching { newEcu.disconnect() }
                        continue
                    }

                    if (myGen != generation) {
                        runCatching { newEcu.stopStreaming() }
                        runCatching { newEcu.disconnect() }
                        return@launch
                    }

                    this@EcuConnectionManager.ecuInterface = newEcu
                    this@EcuConnectionManager.transport = transport
                    activeDefinition = definition
                    realtimeDecoder = RealtimeDecoder(definition)
                    startStreamLoop(newEcu, definition, myGen)
                    startHeartbeatMonitor(myGen)
                    _state.update {
                        it.copy(status = EcuConnectionStatus.CONNECTED, signature = sig,
                            lastError = null, reconnectAttempt = 0)
                    }
                    return@launch
                } catch (e: CancellationException) {
                    cleanupAsync(ecu, transport)
                    throw e
                } catch (_: Exception) {
                    runCatching { ecu?.disconnect() }   // coba lagi di iterasi berikutnya
                }
            }
            _state.update {
                it.copy(status = EcuConnectionStatus.ERROR,
                    lastError = "Reconnect failed after $maxAttempts attempts")
            }
            cleanupAsync(null, transport)
        }
    }

    // ------------------------------------------------------------------
    //  Disconnect
    // ------------------------------------------------------------------

    /** Putuskan dari ECU dan lepaskan semua resource. */
    fun disconnect() {
        disconnectInternal()
        _state.update { EcuConnectionState() }
    }

    private fun disconnectInternal() {
        generation++
        heartbeatJob?.cancel(); heartbeatJob = null
        streamJob?.cancel(); streamJob = null
        connectJob?.cancel(); connectJob = null
        tuneLoadJob?.cancel(); tuneLoadJob = null
        reconnectJob?.cancel(); reconnectJob = null

        EcuCommunicationService.stop(appContext)

        val ecu = ecuInterface
        val t = transport
        cleanupAsync(ecu, t)
        transport = null; ecuInterface = null; activeDefinition = null; realtimeDecoder = null
        channelStore.clear()
    }

    private fun releaseResources() {
        transport = null; ecuInterface = null; activeDefinition = null; realtimeDecoder = null
    }

    /** Tutup antarmuka dan transport di background (tidak memblokir pemanggil). */
    private fun cleanupAsync(ecu: EcuInterface?, t: EcuTransport?) {
        if (ecu == null && t == null) return
        applicationScope.launch(Dispatchers.IO) {
            runCatching { ecu?.stopStreaming() }
            runCatching { ecu?.disconnect() }
            runCatching { t?.disconnect() }
        }
    }

    // ------------------------------------------------------------------
    //  Sync ECU data
    // ------------------------------------------------------------------

    /**
     * Baca semua page dari ECU dengan callback progres.
     *
     * @param onProgress Dipanggil dengan (syncedCount, totalCount) per page.
     * @return Map pageIndex → byte page bila sukses.
     */
    suspend fun syncEcuData(
        onProgress: ((synced: Int, total: Int) -> Unit)? = null
    ): Result<Map<Int, ByteArray>> {
        val ecu = ecuInterface ?: return Result.failure(IllegalStateException("Not connected"))
        val def = activeDefinition ?: return Result.failure(IllegalStateException("No definition loaded"))
        val totalPages = def.nPages.toInt().coerceAtLeast(0)
        if (totalPages == 0) return Result.success(emptyMap())

        _state.update {
            it.copy(status = EcuConnectionStatus.SYNCING, totalPages = totalPages,
                syncedPages = 0, syncProgress = 0f)
        }

        return try {
            val result = mutableMapOf<Int, ByteArray>()
            for (page in 0 until totalPages) {
                val pageSize = if (page < def.pageSizes.size) def.pageSizes[page].toInt() and 0xFFFF else 0
                if (pageSize <= 0) continue
                result[page] = readChunked(ecu, page, 0, pageSize)

                _state.update {
                    it.copy(syncedPages = result.size, syncProgress = result.size.toFloat() / totalPages)
                }
                onProgress?.invoke(result.size, totalPages)
            }
            _state.update { it.copy(status = EcuConnectionStatus.CONNECTED, syncProgress = 1f) }
            Result.success(result)
        } catch (e: CancellationException) {
            _state.update { it.copy(status = EcuConnectionStatus.CONNECTED) }
            throw e
        } catch (e: Exception) {
            // Koneksi sendiri masih hidup — jangan ubah jadi ERROR (UI menganggapnya putus).
            _state.update {
                it.copy(status = EcuConnectionStatus.CONNECTED, lastError = "Sync failed: ${e.message}")
            }
            Result.failure(e)
        }
    }

    // ------------------------------------------------------------------
    //  Burn pages
    // ------------------------------------------------------------------

    /**
     * Tulis data ke page ECU, lalu burn bila [autoBurn]. Hasil tulis TIDAK
     * diabaikan: chunk yang gagal membatalkan seluruh operasi (tidak ada
     * burn data setengah jadi).
     */
    suspend fun burnPage(
        page: Int,
        offset: Int,
        data: ByteArray,
        autoBurn: Boolean = true,
        burnDelayMs: Long = DEFAULT_BURN_DELAY_MS
    ): Result<Unit> {
        val ecu = ecuInterface ?: return Result.failure(IllegalStateException("Not connected"))
        if (!burnActive.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Burn transaction already in progress"))
        }
        return try {
            writeChunked(ecu, page, offset, data)
            if (autoBurn) {
                delay(burnDelayMs)
                unwrap(ecu.burnPage(page), "Burn page $page failed")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            burnActive.set(false)
        }
    }

    /** Tulis dan burn satu page penuh dari offset 0 dengan auto-burn. */
    suspend fun burnFullPage(page: Int, pageData: ByteArray, autoBurn: Boolean = true): Result<Unit> =
        burnPage(page, 0, pageData, autoBurn)

    /**
     * Burn aman dengan backup, verifikasi, dan rollback:
     *   1. Backup : baca page dari ECU.
     *   2. Tulis  : tulis [pageData]; gagal → rollback.
     *   3. Verifikasi: baca balik dan bandingkan byte per byte; beda → rollback.
     *   4. Burn   : perintah burn ke ECU.
     *   5. Verifikasi pasca-burn: baca balik; GAGAL BACA dianggap GAGAL (bukan
     *      sukses), beda data → rollback + burn ulang + verifikasi.
     *
     * CATATAN ZTUNE: firmware ZTune menyimpan ke NVS tiap WRITE_TUNE (burn
     * eksplisit = no-op). Artinya langkah 2 SUDAH permanen; rollback menulis
     * ulang backup (dan itu juga tersimpan).
     *
     * Belum pernah diuji dengan hardware sungguhan.
     */
    suspend fun burnPageSafely(page: Int, pageData: ByteArray): Result<Unit> {
        val ecu = ecuInterface ?: return Result.failure(IllegalStateException("Not connected"))
        val def = activeDefinition ?: return Result.failure(IllegalStateException("No definition loaded"))
        if (!burnActive.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("Burn transaction already in progress"))
        }

        return try {
            // 1. Backup (chunked, exact-length)
            val pageSize = if (page < def.pageSizes.size) def.pageSizes[page].toInt() and 0xFFFF else pageData.size
            val backup = readChunked(ecu, page, 0, pageSize)

            // 2. Tulis
            try {
                writeChunked(ecu, page, 0, pageData)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val restored = restoreBackup(ecu, page, backup, burn = false)
                return Result.failure(
                    IllegalStateException(
                        "Tulis gagal: ${e.message}. " + restoreNote(restored), e
                    )
                )
            }

            // 3. Verifikasi baca-balik
            val readBack = try {
                readChunked(ecu, page, 0, pageData.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (readBack == null || !readBack.contentEquals(pageData)) {
                val restored = restoreBackup(ecu, page, backup, burn = false)
                val why = if (readBack == null) "baca-balik gagal" else "data baca-balik tidak sama dengan yang ditulis"
                return Result.failure(
                    IllegalStateException("Verifikasi gagal ($why). " + restoreNote(restored))
                )
            }

            // 4. Burn
            try {
                delay(DEFAULT_BURN_DELAY_MS)
                unwrap(ecu.burnPage(page), "Burn page $page failed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val restored = restoreBackup(ecu, page, backup, burn = true)
                return Result.failure(
                    IllegalStateException("Burn gagal: ${e.message}. " + restoreNote(restored), e)
                )
            }

            // 5. Verifikasi pasca-burn — gagal baca = GAGAL
            val postBurn = try {
                readChunked(ecu, page, 0, pageData.size)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (postBurn == null) {
                return Result.failure(
                    IllegalStateException(
                        "Tidak bisa memverifikasi isi ECU setelah burn (baca ulang gagal). " +
                            "Jangan anggap berhasil — baca ulang tune dari ECU dan periksa manual."
                    )
                )
            }
            if (!postBurn.contentEquals(pageData)) {
                val restored = restoreBackup(ecu, page, backup, burn = true)
                return if (restored) {
                    Result.failure(
                        IllegalStateException(
                            "Verifikasi pasca-burn gagal: data di flash tidak sama. " +
                                "Backup berhasil dipulihkan — ECU kembali ke tune sebelumnya, aman dicoba lagi."
                        )
                    )
                } else {
                    Result.failure(
                        IllegalStateException(
                            "KRITIS: verifikasi pasca-burn gagal DAN pemulihan backup ke flash JUGA gagal. " +
                                "Isi flash ECU saat ini TIDAK DIKETAHUI PASTI — jangan cabut/matikan ECU. " +
                                "Baca ulang tune dari ECU dan periksa manual sebelum melanjutkan."
                        )
                    )
                }
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            burnActive.set(false)
        }
    }

    /** Tulis ulang [backup], (opsional) burn, lalu verifikasi baca-balik. true = ECU kembali ke data lama. */
    private suspend fun restoreBackup(ecu: EcuInterface, page: Int, backup: ByteArray, burn: Boolean): Boolean =
        try {
            writeChunked(ecu, page, 0, backup)
            if (burn) {
                delay(DEFAULT_BURN_DELAY_MS)
                ecu.burnPage(page).getOrThrow()
            }
            readChunked(ecu, page, 0, backup.size).contentEquals(backup)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private fun restoreNote(restored: Boolean): String =
        if (restored) "Backup dipulihkan dan terverifikasi — ECU kembali ke tune sebelumnya."
        else "PEMULIHAN BACKUP GAGAL — isi ECU tidak pasti. Baca ulang tune dari ECU sebelum melanjutkan."

    // ------------------------------------------------------------------
    // Transfer chunked (melempar exception — tidak ada Result yang bisa diabaikan)
    // ------------------------------------------------------------------

    /** Ukuran chunk = protocol.blockSize dari definisi (ztune.ini: 16384 = 1 page). */
    private fun transferBlockSize(): Int =
        activeDefinition?.protocol?.blockSize?.coerceIn(1, 65536) ?: 256

    /** Baca [length] byte mulai [offset] dalam chunk exact-length. Short read = exception. */
    private suspend fun readChunked(ecu: EcuInterface, page: Int, offset: Int, length: Int): ByteArray {
        val block = transferBlockSize()
        val out = ByteArray(length)
        var pos = 0
        while (pos < length) {
            val chunkLen = min(block, length - pos)
            val chunk = ecu.readBlock(page, offset + pos, chunkLen).getOrThrow()
            if (chunk.size != chunkLen) {
                throw IllegalStateException(
                    "readChunked: short read page $page offset ${offset + pos} — " +
                        "expected $chunkLen bytes, got ${chunk.size}"
                )
            }
            System.arraycopy(chunk, 0, out, pos, chunkLen)
            pos += chunkLen
        }
        return out
    }

    /** Tulis [data] mulai [offset] dalam chunk per protocol.blockSize; chunk gagal = exception. */
    private suspend fun writeChunked(ecu: EcuInterface, page: Int, offset: Int, data: ByteArray) {
        val block = transferBlockSize()
        var pos = 0
        while (pos < data.size) {
            val chunkLen = min(block, data.size - pos)
            val chunk = data.copyOfRange(pos, pos + chunkLen)
            unwrap(ecu.writeBlock(page, offset + pos, chunk), "Write block failed at offset ${offset + pos}")
            pos += chunkLen
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Jalankan [block]; lempar bila generasi sudah digantikan. */
    private inline fun <T> guard(gen: Long, block: () -> T): T {
        if (gen != generation) throw CancellationException("Generation superseded")
        return block()
    }

    /** Buka [Result], lempar exception-nya bila gagal. */
    private fun <T> unwrap(result: Result<T>, msg: String): T =
        result.getOrThrow() ?: throw RuntimeException(msg)

    private fun resetState(base: EcuConnectionState, status: EcuConnectionStatus, transportName: String?) =
        base.copy(status = status, transportName = transportName, signature = null, lastError = null,
            reconnectAttempt = 0, reconnectMaxAttempts = 0, syncProgress = 0f, syncedPages = 0, totalPages = 0)

    private companion object {
        const val TAG = "EcuConnMgr"
        const val HEARTBEAT_CHECK_MS = 500L
        const val HEARTBEAT_TIMEOUT_MS = 2000L
        const val MAX_RESTARTS = 4
        const val BASE_BACKOFF_MS = 200L
        const val MAX_BACKOFF_MS = 5000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val DEFAULT_BURN_DELAY_MS = 50L
    }
}