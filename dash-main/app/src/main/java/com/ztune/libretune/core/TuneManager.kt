package com.ztune.libretune.core

import android.content.Context
import android.net.Uri
import com.ztune.libretune.core.ecu.EcuInterface
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.types.Constant
import com.ztune.libretune.core.ini.types.CurveDefinition
import com.ztune.libretune.core.ini.types.DataType
import com.ztune.libretune.core.ini.types.TableDefinition
import com.ztune.libretune.core.realtime.RealtimeDecoder
import com.ztune.libretune.core.tune.ByteOrderWriter
import com.ztune.libretune.core.tune.ConstantManifestEntry
import com.ztune.libretune.core.tune.IniMetadata
import com.ztune.libretune.core.tune.Tune
import com.ztune.libretune.core.tune.TuneSerializer

/**
 * Manages the current tune: loading from ECU memory or files, applying edits,
 * tracking dirty state, and saving back to files.
 *
 * All mutations (updateConstant, updateTableCell, updateCurveValue) encode the
 * display value back into raw bytes in the page data buffer and mark the tune
 * as dirty. A [RealtimeDecoder] is constructed per-call with the current
 * [activeDefinition] — this avoids the stale-decoder bug where a singleton
 * decoder bound to an empty definition was used for all encode/decode.
 */
class TuneManager(
    private val context: Context
) {
    // ----------------------------------------------------------------------
    // State
    // ----------------------------------------------------------------------

    /*
     * v2.0.0 (audit round-2, finding R2-C5) — OWNERSHIP MODEL:
     *
     * TuneManager adalah PEMILIK TUNGGAL (authoritative owner) state tune.
     * Sebelumnya `currentTune`/`isDirty`/`activeDefinition` adalah var polos
     * yang ditulis dari dua context berbeda tanpa sinkronisasi:
     *   - IO thread  : loadFromEcu (dipanggil EcuConnectionManager setelah connect)
     *   - Main thread: updateConstant/updateTableCell/updateTableAxisBin/
     *                  updateCurveValue/saveToFile/reset (dari ViewModel)
     * Race konkret: user mengedit sel saat auto-load berjalan → setPageData
     * menimpa Tune yang baru selesai dimuat (lost update), atau loadFromMemory
     * menimpa tune yang baru diedit.
     *
     * Model yang dipilih (minimal, tanpa mengubah API publik):
     *   1. `@Volatile` pada ketiga var → publikasi referensi atomik; pembaca
     *      (getter lock-free) selalu melihat objek Tune yang KONSISTEN penuh
     *      (mutator memakai copy-on-write, bukan mutasi in-place pada objek
     *      yang sudah terpublikasi).
     *   2. `@Synchronized` pada SEMUA titik tulis state + titik swap
     *      (loadFromMemory/save/load/reset/mutator/setPageData) → satu
     *      transisi state atomik per operasi.
     *   3. loadFromEcu TIDAK memegang lock selama I/O (membaca halaman ke
     *      list lokal), hanya saat swap akhir via loadFromMemory — network
     *      I/O 2 detik tidak boleh memblokir mutator UI.
     * Alasan bukan Mutex/actor: mutator adalah fungsi non-suspend yang dipanggil
     * puluhan call-site UI; synchronized menjaga API tanpa migrasi massal,
     * dan durasi kritisnya mikro-detik (encode 1 sel).
     */

    /** The currently loaded tune (null until a tune is read). */
    @Volatile
    var currentTune: Tune? = null
        private set

    /** The ECU definition that governs the current tune's layout. */
    @Volatile
    var activeDefinition: EcuDefinition? = null
        private set

    /** Whether the tune has unsaved modifications since last load/save. */
    @Volatile
    var isDirty: Boolean = false
        private set

    // ----------------------------------------------------------------------
    // Load from raw memory image
    // ----------------------------------------------------------------------

    /**
     * Parse a full ECU memory image into a [Tune] using the given [definition].
     *
     * The memory bytes are split into per-page buffers according to
     * [EcuDefinition.pageSizes], then every constant, table, and curve defined
     * in the INI is decoded via [RealtimeDecoder].
     */
    fun loadFromMemory(memoryBytes: ByteArray, definition: EcuDefinition) {
        // v2.0.0 (R2-C5): seluruh badan fungsi adalah titik swap state —
        // dieksekusi eksklusif terhadap mutator lain.
        synchronized(this) {
            activeDefinition = definition
            isDirty = false

            // ---- Phase 5: Construct decoder with the CURRENT definition ----
            // Previously this used the stale singleton decoder from AppModule
            // which was bound to EcuDefinition.default() (empty). That caused
            // all encode/decode operations to use wrong scales/offsets.
            val activeDecoder = RealtimeDecoder(definition)

            val pageData = splitIntoPages(memoryBytes, definition)
            val constantValues = mutableMapOf<String, Double>()
            val tableValues = mutableMapOf<String, List<List<Double>>>()
            val curveValues = mutableMapOf<String, List<Double>>()

            // Decode constants
            for ((name, constant) in definition.constants) {
                if (constant.isPcVariable) continue
                val pageBytes = pageData[constant.page] ?: continue
                constantValues[name] = activeDecoder.decodeConstant(name, pageBytes, constant)
            }

            // Decode tables
            for ((name, table) in definition.tables) {
                val pageBytes = pageData[table.valuesPage] ?: pageData[table.page] ?: continue
                tableValues[name] = activeDecoder.decodeTable(pageBytes, table)
            }

            // Decode curves
            for ((name, curve) in definition.curves) {
                val pageBytes = pageData[curve.valuesPage] ?: continue
                curveValues[name] = activeDecoder.decodeCurve(pageBytes, curve)
            }

            // Build constant manifest for later validation
            val manifest = buildConstantManifest(definition)

            currentTune = Tune(
                iniMetadata = IniMetadata(
                    signature = definition.signature,
                    name = definition.versionInfo,
                    hash = definition.computeStructuralHash(),
                    specVersion = definition.iniSpecVersion
                ),
                constantManifest = manifest,
                pageData = pageData,
                constantValues = constantValues,
                tableValues = tableValues,
                curveValues = curveValues
            )
        }
    }

    // ----------------------------------------------------------------------
    // Load from ECU (reads all pages, then delegates to loadFromMemory)
    // ----------------------------------------------------------------------

    /**
     * Read every page from the ECU via [ecu], then parse into a [Tune].
     *
     * @param ecu        The connected ECU interface.
     * @param definition The INI definition describing memory layout.
     * @param onProgress Callback receiving (currentPage, totalPages) as each
     *   page is read, suitable for progress-bar updates.
     */
    suspend fun loadFromEcu(
        ecu: EcuInterface,
        definition: EcuDefinition,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result<Unit> = runCatching {
        val totalPages = definition.nPages.toInt() and 0xFF
        require(totalPages > 0) { "Definition has zero pages" }

        // v2.0.0 (audit round-2, R2-C2) — SEBELUMNYA setiap halaman dibaca
        // SATU panggilan readBlock(page, 0, pageSize). Untuk driver MS/
        // Speeduino/rusEFI/FOME readBlock kini fail-loud untuk >256 B, dan
        // perilaku lama (silent clamp) membuat tune terpotong tanpa error.
        // SEKARANG: chunking per protocol.blockSize dari definisi (pola yang
        // sama dengan EcuConnectionManager.syncEcuData), dengan verifikasi
        // panjang persis per chunk. Untuk ZTUNE, ztune.ini mendeklarasikan
        // blockSize = 16384 (1 halaman penuh) → tetap 1 transaksi READ_TUNE.
        // I/O TIDAK memegang lock TuneManager — lock hanya di swap akhir
        // (loadFromMemory), lihat R2-C5.
        activeDefinition = definition
        val blockSize = definition.protocol.blockSize.coerceIn(1, 65536)

        val pageChunks = mutableListOf<ByteArray>()
        for (page in 0 until totalPages) {
            val pageSize = definition.pageSizes.getOrElse(page) { 0 }.toInt() and 0xFFFF
            require(pageSize > 0) { "Page $page has zero size" }

            val pageData = ByteArray(pageSize)
            var offset = 0
            while (offset < pageSize) {
                val chunkLen = minOf(blockSize, pageSize - offset)
                val chunk = ecu.readBlock(page, offset, chunkLen)
                    .getOrElse { throw IllegalStateException(
                        "Failed to read page $page at offset $offset: ${it.message}", it
                    ) }
                if (chunk.size != chunkLen) {
                    throw IllegalStateException(
                        "Short read on page $page at offset $offset: " +
                            "expected $chunkLen bytes, got ${chunk.size}"
                    )
                }
                System.arraycopy(chunk, 0, pageData, offset, chunkLen)
                offset += chunkLen
            }
            pageChunks.add(pageData)
            onProgress(page + 1, totalPages)
        }

        val memoryBytes = ByteArray(pageChunks.sumOf { it.size })
        var offset2 = 0
        for (chunk in pageChunks) {
            System.arraycopy(chunk, 0, memoryBytes, offset2, chunk.size)
            offset2 += chunk.size
        }

        loadFromMemory(memoryBytes, definition)
    }

    // ----------------------------------------------------------------------
    // File I/O
    // ----------------------------------------------------------------------

    /**
     * Save the current tune to the file at [uri] via [TuneSerializer].
     *
     * After a successful save the dirty flag is cleared.
     *
     * @return [Result.success] or a descriptive failure.
     */
    fun saveToFile(uri: Uri): Result<Unit> {
        // v2.0.0 (R2-C5): snapshot konsisten untuk serialisasi.
        synchronized(this) {
            val tune = currentTune
                ?: return Result.failure(IllegalStateException("No tune loaded"))

            val result = TuneSerializer.save(context, uri, tune)
            if (result.isSuccess) isDirty = false
            return result
        }
    }

    /**
     * Load a tune from the file at [uri] via [TuneSerializer].
     *
     * @return [Result.success] or a descriptive failure.
     */
    fun loadFromFile(uri: Uri): Result<Unit> {
        // v2.0.0 (R2-C5): swap atomik tune + definition.
        synchronized(this) {
            val result = TuneSerializer.load(context, uri)
                .getOrElse { return Result.failure(it) }

            currentTune = result
            isDirty = false
            activeDefinition = null // file loads don't carry a live definition
            return Result.success(Unit)
        }
    }

    // ----------------------------------------------------------------------
    // Value accessors (delegate to the Tune object)
    // ----------------------------------------------------------------------

    /** Get a decoded constant value, or null if not present. */
    fun getConstantValue(name: String): Double? =
        synchronized(this) { currentTune?.getConstantValue(name) }

    /** Get a single table cell value, or null if out of range. */
    fun getTableCell(tableName: String, row: Int, col: Int): Double? =
        synchronized(this) { currentTune?.getTableCell(tableName, row, col) }

    /** Get a single curve value by index, or null if out of range. */
    fun getCurveValue(curveName: String, index: Int): Double? =
        synchronized(this) { currentTune?.getCurveValue(curveName, index) }

    // ----------------------------------------------------------------------
    // Mutation: constants
    // ----------------------------------------------------------------------

    /**
     * Update a constant's display value, encode it back into page data.
     *
     * Validates that the encoded raw value fits within the constant's [DataType]
     * range before writing.
     *
     * @param name  Constant name (must exist in the active definition).
     * @param value New display (scaled) value.
     * @throws IllegalStateException if no tune is loaded or the constant is
     *   unknown.
     * @throws IllegalArgumentException if the value is out of the data type's
     *   representable range.
     */
    fun updateConstant(name: String, value: Double) {
        // v2.0.0 (R2-C5): synchronized — lihat ownership model di atas.
        synchronized(this) {
            val tune = currentTune
                ?: throw IllegalStateException("No tune loaded")
            val def = activeDefinition
                ?: throw IllegalStateException("No active definition")
            val constant = def.constants[name]
                ?: throw IllegalArgumentException("Unknown constant: $name")

        // ---- Phase 5: Validate raw value fits data type range ----
        val raw = (value - constant.translate) / constant.scale
        require(constant.dataType.isInRange(raw)) {
            "Value $value encodes to raw $raw which is outside ${constant.dataType} range " +
                "[${constant.dataType.rawMin}, ${constant.dataType.rawMax}]"
        }

        val page = constant.page
            val pageBytes = tune.pageData[page]?.toMutableByteArray()
                ?: throw IllegalStateException("Page $page data not available")

            // Construct a decoder with the CURRENT active definition, not the stale
            // singleton from AppModule.
            val activeDecoder = RealtimeDecoder(def)
            val encoded = activeDecoder.encodeConstant(value, constant)
            require(encoded.size <= pageBytes.size - constant.offset) {
                "Encoded value overflows page bounds at offset ${constant.offset}"
            }
            System.arraycopy(encoded, 0, pageBytes, constant.offset, encoded.size)

            setPageData(page, pageBytes)
            tune.constantValues[name] = value
            isDirty = true
        }
    }

    // ----------------------------------------------------------------------
    // Mutation: table cells
    // ----------------------------------------------------------------------

    /**
     * Update a single table cell, encode it back into page data.
     *
     * Validates that the encoded raw value fits within the table's [DataType]
     * range before writing. This prevents silent overflow/truncation that
     * could corrupt the ECU's fuel/ignition tables.
     *
     * @param tableName Table name (must exist in the active definition).
     * @param row       Zero-based row index.
     * @param col       Zero-based column index.
     * @param value     New display (scaled) value.
     * @throws IllegalArgumentException if the value is out of the data type's
     *   representable range, or if the row/col is out of bounds.
     */
    fun updateTableCell(tableName: String, row: Int, col: Int, value: Double) {
        // v2.0.0 (R2-C5): synchronized — lihat ownership model di atas.
        synchronized(this) {
            val tune = currentTune
                ?: throw IllegalStateException("No tune loaded")
            val def = activeDefinition
                ?: throw IllegalStateException("No active definition")
            val table = def.getTableByNameOrMap(tableName)
                ?: throw IllegalArgumentException("Unknown table: $tableName")

            require(row in 0 until table.rows) { "Row $row out of range [0, ${table.rows})" }
            require(col in 0 until table.cols) { "Col $col out of range [0, ${table.cols})" }

            // ---- Phase 5: Validate raw value fits data type range ----
            val raw = (value - table.translate) / table.scale
            require(table.dataType.isInRange(raw)) {
                "Value $value encodes to raw $raw which is outside ${table.dataType} range " +
                    "[${table.dataType.rawMin}, ${table.dataType.rawMax}]"
            }

            val page = table.valuesPage
            val pageBytes = tune.pageData[page]?.toMutableByteArray()
                ?: throw IllegalStateException("Page $page data not available")

            val cellOffset = table.valuesOffset + (row * table.cols + col) * table.dataType.byteSize
            val writer = ByteOrderWriter(table.dataType.byteSize, def.endianness)
            writer.writeValue(table.dataType, raw)
            val encoded = writer.toByteArray()

            require(cellOffset + encoded.size <= pageBytes.size) {
                "Encoded cell overflows page bounds at offset $cellOffset"
            }
            System.arraycopy(encoded, 0, pageBytes, cellOffset, encoded.size)

            setPageData(page, pageBytes)
            tune.setTableCell(tableName, row, col, value)
            isDirty = true
        }
    }
           /**
     * v1.2.0 — Update satu bin di axis table (X bins atau Y bins).
     *
     * Dipakai UI tune editor untuk edit RPM bin atau Load bin lewat tap header
     * di CanvasTableEditor. Setelah ditulis, dipakai juga oleh UI ZTune
     * (QuickShifter cut table, Stage2 fraction table, dll).
     */
    fun updateTableAxisBin(tableName: String, isXAxis: Boolean, idx: Int, value: Double) {
        // v2.0.0 (R2-C5): synchronized — lihat ownership model di atas.
        synchronized(this) {
            val tune = currentTune
                ?: throw IllegalStateException("No tune loaded")
            val def = activeDefinition
                ?: throw IllegalStateException("No active definition")
            val table = def.getTableByNameOrMap(tableName)
                ?: throw IllegalArgumentException("Unknown table: $tableName")

        // v1.2.0 FIX: TableAxis nullable. `requireNotNull` mengembalikan tipe
            // non-null (kontrak function) — compiler jamin `axis` bertipe `TableAxis`
            // (non-null), tidak perlu smart-cast.
            val axis = requireNotNull(if (isXAxis) table.xAxis else table.yAxis) {
                "Table '$tableName' has no ${if (isXAxis) "X" else "Y"} axis"
            }

            require(idx in 0 until axis.size) { "Bin index $idx out of range [0, ${axis.size})" }

            // ---- Validasi range raw ----
            val raw = (value - axis.translate) / axis.scale
            require(axis.dataType.isInRange(raw)) {
                "Value $value encodes to raw $raw outside ${axis.dataType} range"
            }

            // ---- Encode & write ke page ----
            val binPage = axis.binsPage.takeIf { it != 0 } ?: table.valuesPage
            val pageBytes = tune.pageData[binPage]?.toMutableByteArray()
                ?: throw IllegalStateException("Page $binPage data not available")

            val binOffset = axis.binsOffset + idx * axis.dataType.byteSize
            val writer = ByteOrderWriter(axis.dataType.byteSize, def.endianness)
            writer.writeValue(axis.dataType, raw)
            val encoded = writer.toByteArray()

            require(binOffset + encoded.size <= pageBytes.size) {
                "Encoded bin overflows page bounds at offset $binOffset"
            }
            System.arraycopy(encoded, 0, pageBytes, binOffset, encoded.size)

            setPageData(binPage, pageBytes)
            isDirty = true
        }
    }

    // ----------------------------------------------------------------------
    // Mutation: curves
    // ----------------------------------------------------------------------

    /**
     * Update a single curve value, encode it back into page data.
     *
     * Validates that the encoded raw value fits within the curve's [DataType]
     * range before writing.
     *
     * @param curveName Curve name (must exist in the active definition).
     * @param index     Zero-based bin index.
     * @param value     New display (scaled) value.
     * @throws IllegalArgumentException if the value is out of the data type's
     *   representable range, or if the index is out of bounds.
     */
    fun updateCurveValue(curveName: String, index: Int, value: Double) {
        // v2.0.0 (R2-C5): synchronized — lihat ownership model di atas.
        synchronized(this) {
            val tune = currentTune
                ?: throw IllegalStateException("No tune loaded")
            val def = activeDefinition
                ?: throw IllegalStateException("No active definition")
            val curve = def.getCurveByNameOrMap(curveName)
                ?: throw IllegalArgumentException("Unknown curve: $curveName")

            require(index in 0 until curve.size) {
                "Index $index out of range [0, ${curve.size})"
            }

            // ---- Phase 5: Validate raw value fits data type range ----
            val raw = (value - curve.translate) / curve.scale
            require(curve.dataType.isInRange(raw)) {
                "Value $value encodes to raw $raw which is outside ${curve.dataType} range " +
                    "[${curve.dataType.rawMin}, ${curve.dataType.rawMax}]"
            }

            val page = curve.valuesPage
            val pageBytes = tune.pageData[page]?.toMutableByteArray()
                ?: throw IllegalStateException("Page $page data not available")

            val binOffset = curve.valuesOffset + index * curve.dataType.byteSize
            val writer = ByteOrderWriter(curve.dataType.byteSize, def.endianness)
            writer.writeValue(curve.dataType, raw)
            val encoded = writer.toByteArray()

            require(binOffset + encoded.size <= pageBytes.size) {
                "Encoded curve value overflows page bounds at offset $binOffset"
            }
            System.arraycopy(encoded, 0, pageBytes, binOffset, encoded.size)

            setPageData(page, pageBytes)
            tune.setCurveValue(curveName, index, value)
            isDirty = true
        }
    }

    // ----------------------------------------------------------------------
    // Page data helpers
    // ----------------------------------------------------------------------

    /** Get the raw byte array for a page, or null. */
    fun getPageData(page: Int): ByteArray? =
        synchronized(this) { currentTune?.getPageData(page) }

    /** Replace the raw byte array for a page (immutable-map update). */
    private fun setPageData(page: Int, bytes: ByteArray) {
        // v2.0.0 (R2-C5): dipanggil dari dalam synchronized block mutator;
        // tetap synchronized agar aman bila dipanggil langsung di masa depan.
        synchronized(this) {
            val tune = currentTune ?: return
            val newData = tune.pageData.toMutableMap()
            newData[page] = bytes
            currentTune = tune.copy(pageData = newData)
        }
    }

    // ----------------------------------------------------------------------
    // Deep copy
    // ----------------------------------------------------------------------

    /**
     * Return an independent deep copy of the current tune.
     *
     * The copy has its own page data byte arrays and value maps; mutations
     * to the copy do not affect the original.
     *
     * @return The cloned tune, or null if no tune is loaded.
     */
    fun deepCopy(): Tune? = synchronized(this) { currentTune?.deepCopy() }

    // ----------------------------------------------------------------------
    // Reset
    // ----------------------------------------------------------------------

    /** Discard the current tune, clear dirty state, and release the definition. */
    fun reset() {
        synchronized(this) {
            currentTune = null
            activeDefinition = null
            isDirty = false
        }
    }

    // ----------------------------------------------------------------------
    // Constant manifest
    // ----------------------------------------------------------------------

    /**
     * Build a [ConstantManifestEntry] list from the active definition's constants.
     *
     * The manifest captures each constant's name, data type, page, offset,
     * scale, and translate so that a saved tune can be validated against a
     * potentially different INI file when re-opened.
     */
    fun buildConstantManifest(definition: EcuDefinition): List<ConstantManifestEntry> {
        return definition.constants.values
            .filter { !it.isPcVariable }
            .map { c ->
                ConstantManifestEntry(
                    name = c.name,
                    dataType = c.dataType.name,
                    page = c.page,
                    offset = c.offset,
                    scale = c.scale,
                    translate = c.translate
                )
            }
    }

    /**
     * Validate the current tune's constant manifest against a definition.
     *
     * Returns a list of mismatch descriptions. An empty list means the tune
     * is fully compatible with the given definition.
     */
    fun validateManifest(definition: EcuDefinition): List<String> {
        val tune = currentTune ?: return emptyList()
        val issues = mutableListOf<String>()
        val tuneManifest = tune.constantManifest.associateBy { it.name }

        for ((name, constant) in definition.constants) {
            if (constant.isPcVariable) continue
            val entry = tuneManifest[name]
            if (entry == null) {
                issues.add("Constant '$name' exists in INI but not in tune manifest")
                continue
            }
            if (entry.dataType != constant.dataType.name) {
                issues.add("$name: data type mismatch (tune=${entry.dataType}, ini=${constant.dataType.name})")
            }
            if (entry.page != constant.page) {
                issues.add("$name: page mismatch (tune=${entry.page}, ini=${constant.page})")
            }
            if (entry.offset != constant.offset) {
                issues.add("$name: offset mismatch (tune=${entry.offset}, ini=${constant.offset})")
            }
        }

        // Check for constants in tune that no longer exist in the INI
        for (name in tuneManifest.keys) {
            if (name !in definition.constants) {
                issues.add("Constant '$name' in tune manifest but missing from INI")
            }
        }

        return issues
    }

    // ----------------------------------------------------------------------
    // Private helpers
    // ----------------------------------------------------------------------

    /**
     * Split a contiguous memory image into per-page byte arrays.
     */
    private fun splitIntoPages(
        memoryBytes: ByteArray,
        definition: EcuDefinition
    ): Map<Int, ByteArray> {
        val totalPages = definition.nPages.toInt() and 0xFF
        val pages = mutableMapOf<Int, ByteArray>()
        var offset = 0

        for (page in 0 until totalPages) {
            val pageSize = definition.pageSizes.getOrElse(page) { 0 }.toInt() and 0xFFFF
            if (pageSize <= 0) continue
            val end = (offset + pageSize).coerceAtMost(memoryBytes.size)
            if (offset >= memoryBytes.size) break
            pages[page] = memoryBytes.copyOfRange(offset, end)
            offset += pageSize
        }

        return pages
    }

    /** Shorthand to create a mutable copy of a byte array. */
    private fun ByteArray.toMutableByteArray(): ByteArray = this.copyOf()
}