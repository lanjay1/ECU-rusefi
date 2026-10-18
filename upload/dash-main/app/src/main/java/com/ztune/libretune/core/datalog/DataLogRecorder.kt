package com.ztune.libretune.core.datalog

import android.content.Context
import com.ztune.libretune.core.realtime.RealtimeData
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records realtime ECU data to a binary, block-compressed `.ztlog` file.
 *
 * v1.3.9 (audit finding #19) — SEBELUMNYA ditulis sebagai `.csv` mentah,
 * lalu sempat diperbaiki sementara jadi `.csv.gz` (gzip transparan). Itu
 * masih satu gzip stream utuh untuk seluruh file — kalau proses ke-kill di
 * tengah sesi (baterai habis, force-close), SISA FILE SETELAH TITIK CRASH
 * jadi tidak terbaca sama sekali.
 *
 * SEKARANG format biner [BinaryDataLogFormat]: sample dikumpulkan per-blok
 * (256 sample), tiap blok dikompres Deflate SENDIRI-SENDIRI dan diberi
 * panjang + CRC32 di depannya sebelum ditulis ke disk. Kalau proses mati di
 * tengah jalan, blok yang sedang ditulis mungkin rusak/terpotong, tapi
 * SEMUA BLOK SEBELUMNYA (yang sudah selesai ditulis+flush) tetap terbaca
 * penuh oleh [DataLogPlayback] — bukan seluruh file jadi tidak terbaca.
 *
 * Thread safety: all public methods are safe to call from any thread.
 * The internal state is synchronised on [writerLock].
 *
 * Converted from LibreTune's Rust `datalog::recorder` module.
 */
class DataLogRecorder(private val context: Context) {

    /** Recording lifecycle states. */
    enum class State { IDLE, RECORDING, PAUSED }

    // ---------------------------------------------------------------------
    // Public flows
    // ---------------------------------------------------------------------

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val _sampleCount = MutableStateFlow(0)
    /** Observable count of samples written so far in the current session. */
    val sampleCount: StateFlow<Int> = _sampleCount

    // ---------------------------------------------------------------------
    // Internal state
    // ---------------------------------------------------------------------

    private var out: BufferedOutputStream? = null
    private var file: File? = null
    private var session: DataLogSession? = null
    private var channelNames: List<String> = emptyList()
    private var recordingJob: Job? = null

    /** Samples accumulated for the block currently being filled. */
    private val pendingBlock = mutableListOf<BinaryDataLogFormat.RawSample>()

    /** Lock guarding all writer state so [recordSample] is thread-safe. */
    private val writerLock = Any()

    /** Date format used for auto-generated session names. */
    private val sessionNameFormatter = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Start recording to a new `.ztlog` file.
     *
     * Creates the file in the app's external-files directory under
     * `datalog/`, writes the binary header (magic/version/channel names),
     * and transitions to [State.RECORDING].
     *
     * @param channelNames Ordered list of channel names to log. The order is
     *   preserved in the binary column layout.
     * @param sessionName Optional human-readable name; defaults to an
     *   auto-generated timestamp-based name.
     * @return [Result.success] with the new [DataLogSession] metadata, or
     *   [Result.failure] if recording is already active or file I/O fails.
     */
    fun startRecording(channelNames: List<String>, sessionName: String = ""): Result<DataLogSession> {
        synchronized(writerLock) {
            if (_state.value != State.IDLE) {
                return Result.failure(IllegalStateException(
                    "Cannot start recording: current state is ${_state.value}"
                ))
            }
            if (channelNames.isEmpty()) {
                return Result.failure(IllegalArgumentException("channelNames must not be empty"))
            }
            if (channelNames.size > 255) {
                return Result.failure(IllegalArgumentException("Max 255 channels supported, got ${channelNames.size}"))
            }

            this.channelNames = channelNames
            pendingBlock.clear()

            val logDir = File(context.getExternalFilesDir(null), "datalog")
            if (!logDir.exists() && !logDir.mkdirs()) {
                return Result.failure(IOException("Failed to create datalog directory: $logDir"))
            }

            val safeName = (sessionName.ifBlank { sessionNameFormatter.format(Date()) })
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val logFile = File(logDir, "${safeName}${BinaryDataLogFormat.EXTENSION}")

            try {
                val stream = BufferedOutputStream(FileOutputStream(logFile))
                stream.write(BinaryDataLogFormat.encodeHeader(channelNames))
                stream.flush()
                out = stream
                file = logFile
            } catch (e: Exception) {
                return Result.failure(IOException("Failed to create datalog file: ${e.message}", e))
            }

            val now = System.currentTimeMillis()
            val newSession = DataLogSession(
                id = now,
                name = safeName,
                startedAt = now,
                channelNames = channelNames,
                sampleCount = 0,
                filePath = logFile.absolutePath
            )
            session = newSession
            _sampleCount.value = 0
            _state.value = State.RECORDING

            return Result.success(newSession)
        }
    }

    /**
     * Append a realtime data sample.
     *
     * Only writes when the state is [State.RECORDING]. Samples are buffered
     * in memory until a full block ([BinaryDataLogFormat.DEFAULT_BLOCK_SIZE]
     * samples) accumulates, then the whole block is compressed and flushed
     * to disk as one unit (see class doc for why — crash resilience).
     *
     * @param data The decoded realtime snapshot to log.
     */
    fun recordSample(data: RealtimeData) {
        recordSampleInternal(data.timestamp) { ch -> data.channels[ch] }
    }

    /**
     * Convenience: record a raw map of channel values (not from [RealtimeData]).
     *
     * @param timestamp Epoch-millis timestamp for this sample.
     * @param values Channel name → value map.
     */
    fun recordSample(timestamp: Long, values: Map<String, Double>) {
        recordSampleInternal(timestamp) { ch -> values[ch] }
    }

    private fun recordSampleInternal(timestamp: Long, lookup: (String) -> Double?) {
        if (_state.value != State.RECORDING) return

        synchronized(writerLock) {
            if (out == null) return
            val floats = FloatArray(channelNames.size) { i ->
                val v = lookup(channelNames[i])
                if (v != null && !v.isNaN()) v.toFloat() else Float.NaN
            }
            pendingBlock.add(BinaryDataLogFormat.RawSample(timestamp, floats))
            _sampleCount.value += 1

            if (pendingBlock.size >= BinaryDataLogFormat.DEFAULT_BLOCK_SIZE) {
                flushPendingBlock()
            }
        }
    }

    /** Encode+write the current pendingBlock (if non-empty) and clear it. Caller must hold [writerLock]. */
    private fun flushPendingBlock() {
        if (pendingBlock.isEmpty()) return
        val stream = out ?: return
        try {
            val blockBytes = BinaryDataLogFormat.encodeBlock(pendingBlock)
            stream.write(blockBytes)
            stream.flush()
        } catch (_: IOException) {
            _state.value = State.IDLE
        } finally {
            pendingBlock.clear()
        }
    }

    /**
     * Pause the current recording.
     *
     * Flushes the in-progress block to disk (so a pause doesn't risk losing
     * buffered-but-unwritten samples) and stops accepting new samples until
     * [resume] is called.
     */
    fun pause() {
        if (_state.value == State.RECORDING) {
            synchronized(writerLock) {
                flushPendingBlock()
            }
            _state.value = State.PAUSED
        }
    }

    /** Resume a paused recording. */
    fun resume() {
        if (_state.value == State.PAUSED) {
            _state.value = State.RECORDING
        }
    }

    /**
     * Stop recording and finalise the session.
     *
     * Flushes any partially-filled block (so the tail of a session isn't
     * lost just because it didn't reach a full block size), closes the
     * file, and returns the completed [DataLogSession].
     *
     * @return The completed session, or `null` if no session was active.
     */
    fun stopRecording(): DataLogSession? {
        synchronized(writerLock) {
            if (_state.value == State.IDLE) return null

            flushPendingBlock()
            try {
                out?.close()
            } catch (_: IOException) { /* best-effort close */ }

            out = null
            _state.value = State.IDLE

            val s = session ?: return null
            val completed = s.copy(
                endedAt = System.currentTimeMillis(),
                sampleCount = _sampleCount.value
            )
            session = completed
            return completed
        }
    }

    /**
     * Get the current session metadata, or `null` if not recording.
     */
    fun getCurrentSession(): DataLogSession? {
        synchronized(writerLock) {
            return session
        }
    }

    companion object {
        /** Samples buffered before a block is compressed and flushed to disk. */
        const val FLUSH_INTERVAL = BinaryDataLogFormat.DEFAULT_BLOCK_SIZE
    }
}