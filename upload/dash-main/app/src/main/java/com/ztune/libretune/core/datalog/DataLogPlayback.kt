package com.ztune.libretune.core.datalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.zip.GZIPInputStream

/**
 * Reads and plays back a datalog file — native binary `.ztlog`
 * ([BinaryDataLogFormat], see [DataLogRecorder]), or a legacy plain `.csv` /
 * `.csv.gz` file recorded by an older version of this app.
 *
 * v1.3.9 (audit finding #19) — [DataLogRecorder] now writes `.ztlog`
 * (block-compressed binary, tahan-crash-per-blok). This reader's PRIMARY
 * path handles that format; the CSV/gzip-CSV paths are kept ONLY so old
 * recordings from before this change remain playable — new recordings never
 * use them.
 *
 * v1.3.7 (audit finding #13) — regardless of format, [load] only builds a
 * lightweight index (per-sample timestamps + which block/line holds each
 * sample) instead of parsing the entire file into memory. [getSample] reads
 * & decodes ONLY the block/line actually requested, on demand.
 *
 * Converted from LibreTune's Rust `datalog::playback` module.
 */
class DataLogPlayback(private val file: File) {

    /** Playback states. */
    enum class PlaybackState { STOPPED, PLAYING, PAUSED }

    private enum class Format { BINARY, CSV }

    // ---------------------------------------------------------------------
    // Public flows
    // ---------------------------------------------------------------------

    private val _state = MutableStateFlow(PlaybackState.STOPPED)
    val state: StateFlow<PlaybackState> = _state

    private val _position = MutableStateFlow(0)
    val position: StateFlow<Int> = _position

    private val _speed = MutableStateFlow(1.0f)
    val speed: StateFlow<Float> = _speed

    /**
     * Set once [load] succeeds: how many blocks (binary format only) were
     * skipped because the file was truncated/corrupted at that point.
     * `0` for a cleanly-terminated file, or for the legacy CSV path.
     * Surfaces the "tahan crash per-blok" behaviour to callers/UI instead of
     * silently hiding that some tail data was unreadable.
     */
    var truncatedAtByte: Long? = null
        private set

    // ---------------------------------------------------------------------
    // Shared internal state
    // ---------------------------------------------------------------------

    private var format = Format.CSV
    private var channelNames: List<String> = emptyList()

    /** Timestamp of every sample, 1:1 with global sample index. Cheap to keep fully in RAM (8 bytes/sample). */
    private var timestamps: LongArray = LongArray(0)

    private var playbackJob: Job? = null

    // ---- Binary-format-only index ----
    private data class BlockIndexEntry(val byteOffset: Long, val sampleCount: Int, val firstGlobalIndex: Int)
    private var blockIndex: List<BlockIndexEntry> = emptyList()
    private var binaryFile: RandomAccessFile? = null
    /** Small one-block cache so sequential access (the common case) doesn't redecompress every call. */
    private var cachedBlockIdx = -1
    private var cachedBlockData: BinaryDataLogFormat.DecodedBlock? = null

    // ---- CSV-format-only index (legacy) ----
    private var lineOffsets: LongArray = LongArray(0)
    private var workingCsvFile: File = file
    private var workingCsvFileIsTemp = false
    private var csvRandomAccessFile: RandomAccessFile? = null

    // ---------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------

    /**
     * Index the datalog file. Detects binary `.ztlog` vs legacy CSV/CSV.gz
     * by file signature (not just extension, so renamed files still work).
     */
    fun load(): Result<Unit> {
        if (!file.exists()) {
            return Result.failure(java.io.FileNotFoundException("Datalog file not found: ${file.absolutePath}"))
        }
        if (!file.canRead()) {
            return Result.failure(java.io.IOException("Cannot read datalog file: ${file.absolutePath}"))
        }

        return try {
            val header4 = ByteArray(4)
            FileInputStream(file).use { it.read(header4) }
            format = if (header4.contentEquals(BinaryDataLogFormat.MAGIC)) Format.BINARY else Format.CSV

            if (format == Format.BINARY) loadBinary() else loadCsv()
        } catch (e: Exception) {
            Result.failure(java.io.IOException("Failed to index datalog file: ${e.message}", e))
        }
    }

    /** Release open file handles and delete any temp decompressed file. Call when done with this instance (e.g. `onCleared`). */
    fun close() {
        try { binaryFile?.close() } catch (_: Exception) { /* best-effort */ }
        binaryFile = null
        cachedBlockData = null
        cachedBlockIdx = -1

        try { csvRandomAccessFile?.close() } catch (_: Exception) { /* best-effort */ }
        csvRandomAccessFile = null
        if (workingCsvFileIsTemp) {
            try { workingCsvFile.delete() } catch (_: Exception) { /* best-effort */ }
        }
        workingCsvFileIsTemp = false
    }

    /** Total number of samples indexed from the file. */
    fun totalSamples(): Int = timestamps.size

    /** Ordered list of channel names (excluding the timestamp column). */
    fun getChannelNames(): List<String> = channelNames.toList()

    /**
     * Read & decode the sample at [index] on demand.
     *
     * @return The sample map, or `null` if [index] is out of bounds or the
     *   underlying data can no longer be read.
     */
    @Synchronized
    fun getSample(index: Int): Map<String, Double>? {
        if (index < 0 || index >= timestamps.size) return null
        return if (format == Format.BINARY) getSampleBinary(index) else getSampleCsv(index)
    }

    /** Get the timestamp (epoch-ms) for sample at [index]. */
    fun getTimestamp(index: Int): Long? {
        if (index < 0 || index >= timestamps.size) return null
        return timestamps[index]
    }

    /**
     * Start playback from the current [position]. See class doc for details.
     */
    fun play(scope: CoroutineScope, onSample: (Map<String, Double>) -> Unit) {
        if (timestamps.isEmpty()) return
        if (_state.value == PlaybackState.PLAYING) return
        _state.value = PlaybackState.PLAYING
        playbackJob = scope.launchPlayback(onSample)
    }

    fun pause() {
        if (_state.value != PlaybackState.PLAYING) return
        playbackJob?.cancel()
        playbackJob = null
        _state.value = PlaybackState.PAUSED
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        _state.value = PlaybackState.STOPPED
        _position.value = 0
    }

    fun seek(position: Int) {
        val clamped = position.coerceIn(0, (timestamps.size - 1).coerceAtLeast(0))
        _position.value = clamped
    }

    fun setSpeed(speed: Float) {
        _speed.value = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    }

    // ---------------------------------------------------------------------
    // Binary (.ztlog) format
    // ---------------------------------------------------------------------

    private fun loadBinary(): Result<Unit> {
        closeBinary()

        val raf = RandomAccessFile(file, "r")
        binaryFile = raf

        // --- Header ---
        val magic = ByteArray(4).also { raf.readFully(it) }
        if (!magic.contentEquals(BinaryDataLogFormat.MAGIC)) {
            return Result.failure(java.io.IOException("Not a .ztlog file (bad magic)"))
        }
        val version = raf.readByte()
        if (version != BinaryDataLogFormat.VERSION) {
            return Result.failure(java.io.IOException("Unsupported .ztlog version: $version"))
        }
        val channelCount = raf.readUnsignedByte()
        val names = mutableListOf<String>()
        repeat(channelCount) {
            val len = raf.readUnsignedByte()
            val bytes = ByteArray(len).also { b -> raf.readFully(b) }
            names.add(String(bytes, Charsets.UTF_8))
        }
        channelNames = names

        // --- Blocks: index only (byte offset + sample count), decode timestamps as we go ---
        val blocks = mutableListOf<BlockIndexEntry>()
        val tsList = mutableListOf<Long>()
        var globalIndex = 0
        val fileLength = raf.length()

        truncatedAtByte = null
        while (true) {
            val blockStart = raf.filePointer
            val remaining = fileLength - blockStart
            if (remaining <= 0) break // clean EOF
            if (remaining < BinaryDataLogFormat.BLOCK_HEADER_SIZE) {
                // Not enough bytes left for even a block header — truncated mid-header.
                truncatedAtByte = blockStart
                break
            }

            val headerBytes = ByteArray(BinaryDataLogFormat.BLOCK_HEADER_SIZE)
            raf.readFully(headerBytes)
            val info = BinaryDataLogFormat.decodeBlockHeader(headerBytes, 0)

            if (info.compressedLength < 0 || (raf.length() - raf.filePointer) < info.compressedLength) {
                // Declared payload length runs past EOF — file was truncated
                // while this block was being written. Stop here; everything
                // indexed so far (all PRIOR complete blocks) remains valid
                // and playable. This is the crash-resilience #19 was about.
                truncatedAtByte = blockStart
                break
            }

            val payload = ByteArray(info.compressedLength)
            raf.readFully(payload)

            if (!BinaryDataLogFormat.verifyCrc(payload, info.crc32)) {
                // Corrupt block (bit-flip, partial write with matching length
                // by coincidence, etc). Stop here rather than risk decoding
                // garbage — same policy as a truncated block.
                truncatedAtByte = blockStart
                break
            }

            // Decode just far enough to get per-sample timestamps for the
            // index (cheap to keep fully in RAM); full float values are
            // decoded again lazily in getSampleBinary() when actually needed.
            val decoded = BinaryDataLogFormat.decodeBlockPayload(
                payload, info.sampleCount, channelCount, info.startTimestamp
            )
            blocks.add(BlockIndexEntry(blockStart, info.sampleCount, globalIndex))
            for (ts in decoded.timestamps) tsList.add(ts)
            globalIndex += info.sampleCount
        }

        blockIndex = blocks
        timestamps = tsList.toLongArray()
        _position.value = 0

        return if (timestamps.isEmpty()) {
            Result.failure(java.io.IOException("No valid samples found in .ztlog file" +
                (truncatedAtByte?.let { " (truncated at byte $it)" } ?: "")))
        } else {
            Result.success(Unit)
        }
    }

    private fun getSampleBinary(index: Int): Map<String, Double>? {
        val raf = binaryFile ?: return null
        val blockIdx = findBlockContaining(index)
        if (blockIdx < 0) return null

        if (blockIdx != cachedBlockIdx) {
            val entry = blockIndex[blockIdx]
            try {
                raf.seek(entry.byteOffset)
                val headerBytes = ByteArray(BinaryDataLogFormat.BLOCK_HEADER_SIZE)
                raf.readFully(headerBytes)
                val info = BinaryDataLogFormat.decodeBlockHeader(headerBytes, 0)
                val payload = ByteArray(info.compressedLength)
                raf.readFully(payload)
                cachedBlockData = BinaryDataLogFormat.decodeBlockPayload(
                    payload, info.sampleCount, channelNames.size, info.startTimestamp
                )
                cachedBlockIdx = blockIdx
            } catch (_: Exception) {
                return null
            }
        }

        val block = cachedBlockData ?: return null
        val localIdx = index - blockIndex[blockIdx].firstGlobalIndex
        if (localIdx !in block.values.indices) return null

        val row = mutableMapOf<String, Double>()
        for (c in channelNames.indices) {
            val v = block.values[localIdx][c]
            row[channelNames[c]] = if (v.isNaN()) Double.NaN else v.toDouble()
        }
        return row
    }

    /** Binary search for which block contains global sample [index]. */
    private fun findBlockContaining(index: Int): Int {
        var lo = 0
        var hi = blockIndex.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val entry = blockIndex[mid]
            val entryEnd = entry.firstGlobalIndex + entry.sampleCount
            when {
                index < entry.firstGlobalIndex -> hi = mid - 1
                index >= entryEnd -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    private fun closeBinary() {
        try { binaryFile?.close() } catch (_: Exception) { /* best-effort */ }
        binaryFile = null
        cachedBlockData = null
        cachedBlockIdx = -1
    }

    // ---------------------------------------------------------------------
    // Legacy CSV / CSV.gz format (old recordings only — new ones never use this)
    // ---------------------------------------------------------------------

    private fun loadCsv(): Result<Unit> {
        closeCsv()

        if (file.name.endsWith(".gz", ignoreCase = true)) {
            val temp = File.createTempFile("ztune_datalog_", ".csv", file.parentFile)
            GZIPInputStream(FileInputStream(file)).use { gz ->
                FileOutputStream(temp).use { out -> gz.copyTo(out, bufferSize = 64 * 1024) }
            }
            workingCsvFile = temp
            workingCsvFileIsTemp = true
        } else {
            workingCsvFile = file
            workingCsvFileIsTemp = false
        }

        val offsets = mutableListOf<Long>()
        val tsList = mutableListOf<Long>()

        BufferedReader(InputStreamReader(FileInputStream(workingCsvFile), Charsets.UTF_8)).use { br ->
            val headerLine = br.readLine()
                ?: return Result.failure(java.io.IOException("CSV file is empty (no header)"))
            val columns = parseCsvLine(headerLine)
            if (columns.isEmpty() || columns.first().lowercase() != DataLogSession.COLUMN_TIMESTAMP) {
                return Result.failure(java.io.IOException("Not a recognised legacy CSV datalog file"))
            }
            channelNames = columns.drop(1)
        }

        RandomAccessFile(workingCsvFile, "r").use { raf ->
            raf.readLine()
            while (true) {
                val lineStart = raf.filePointer
                val line = raf.readLine() ?: break
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                val commaIdx = trimmed.indexOf(',')
                val tsField = if (commaIdx >= 0) trimmed.substring(0, commaIdx) else trimmed
                val ts = tsField.toLongOrNull() ?: continue
                offsets.add(lineStart)
                tsList.add(ts)
            }
        }

        lineOffsets = offsets.toLongArray()
        timestamps = tsList.toLongArray()
        csvRandomAccessFile = RandomAccessFile(workingCsvFile, "r")
        _position.value = 0

        return Result.success(Unit)
    }

    private fun getSampleCsv(index: Int): Map<String, Double>? {
        val raf = csvRandomAccessFile ?: return null
        return try {
            raf.seek(lineOffsets[index])
            val line = raf.readLine() ?: return null
            val fields = parseCsvLine(line.trim())
            val row = mutableMapOf<String, Double>()
            for (i in channelNames.indices) {
                val field = fields.getOrElse(i + 1) { "" }
                row[channelNames[i]] = field.toDoubleOrNull() ?: Double.NaN
            }
            row
        } catch (_: Exception) {
            null
        }
    }

    private fun closeCsv() {
        try { csvRandomAccessFile?.close() } catch (_: Exception) { /* best-effort */ }
        csvRandomAccessFile = null
        if (workingCsvFileIsTemp) {
            try { workingCsvFile.delete() } catch (_: Exception) { /* best-effort */ }
        }
        workingCsvFileIsTemp = false
    }

    // ---------------------------------------------------------------------
    // Internal playback loop (format-agnostic — works off timestamps + getSample)
    // ---------------------------------------------------------------------

    private fun CoroutineScope.launchPlayback(
        onSample: (Map<String, Double>) -> Unit
    ): Job = launch {
        var idx = _position.value
        val speed = _speed.value

        while (isActive && idx < timestamps.size) {
            val sample = getSample(idx)
            if (sample != null) onSample(sample)
            _position.value = idx

            if (idx + 1 < timestamps.size) {
                val deltaMs = timestamps[idx + 1] - timestamps[idx]
                delay(if (deltaMs > 0) (deltaMs / speed).toLong().coerceAtLeast(1L) else 10L)
            } else {
                delay(100L)
            }
            idx++
        }
        _state.value = PlaybackState.STOPPED
    }

    // ---------------------------------------------------------------------
    // CSV parsing utilities (legacy path + toCsv export helper below)
    // ---------------------------------------------------------------------

    internal fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"'); i += 2; continue
                    } else { inQuotes = false; i++; continue }
                } else { current.append(ch); i++; continue }
            }
            when (ch) {
                ',' -> { fields.add(current.toString()); current.clear(); i++ }
                '"' -> { inQuotes = true; i++ }
                else -> { current.append(ch); i++ }
            }
        }
        fields.add(current.toString())
        return fields
    }

    companion object {
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4.0f

        /**
         * Export a list of samples to CSV text (e.g. for sharing to a
         * spreadsheet app) — the inverse of the legacy CSV loading logic.
         * The native on-disk format is `.ztlog`; this is an explicit export
         * path, not how recordings are stored.
         */
        fun toCsv(
            timestamps: List<Long>,
            channelNames: List<String>,
            samplesList: List<Map<String, Double>>
        ): String {
            val sb = StringBuilder()
            sb.append(DataLogSession.COLUMN_TIMESTAMP)
            for (ch in channelNames) { sb.append(','); sb.append(csvEscapeField(ch)) }
            sb.append('\n')
            for (i in timestamps.indices) {
                sb.append(timestamps[i])
                for (ch in channelNames) {
                    sb.append(',')
                    val value = samplesList.getOrNull(i)?.get(ch)
                    if (value != null && !value.isNaN()) sb.append(value)
                }
                sb.append('\n')
            }
            return sb.toString()
        }

        internal fun csvEscapeField(value: String): String {
            val dq = "\""
            if (value.contains(",") || value.contains(dq) || value.contains("\n") || value.contains("\r")) {
                return dq + value.replace(dq, dq + dq) + dq
            }
            return value
        }
    }
}