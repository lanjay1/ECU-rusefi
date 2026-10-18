package com.ztune.libretune.core.ini.parser

import android.content.res.AssetManager
import com.ztune.libretune.core.ini.*
import com.ztune.libretune.core.ini.types.*

/**
 * Exception thrown when INI parsing fails.
 */
class IniParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Main INI parser that converts a stream of [IniToken]s into an [EcuDefinition].
 *
 * This is a port of LibreTune's Rust `ini/parser.rs` module.
 *
 * The parser maintains a preprocessor stack for `#if`/`#else`/`#endif` blocks,
 * a symbol table for `#define` macros, and processes each section according to
 * the TunerStudio INI specification.
 */
class IniParser {

    // ------------------------------------------------------------------
    // Preprocessor state
    // ------------------------------------------------------------------

    /** Symbols the user has activated before parsing (e.g. from firmware detection). */
    private val activeSymbols = mutableSetOf<String>()

    /** All symbols tested via `#if` — used to populate `EcuDefinition.testedSymbols`. */
    private val testedSymbols = mutableSetOf<String>()

    /** Macro definitions: name -> list of value tokens. */
    private val defines = mutableMapOf<String, List<String>>()

    /**
     * Preprocessor conditional stack.  Each entry is (symbol, branchTaken).
     * When the stack is non-empty and any entry has `!branchTaken`, we are
     * in a "skipping" region.
     */
    private val ppStack = mutableListOf<Pair<String, Boolean>>()

    /** Include nesting depth to prevent infinite recursion. */
    private var includeDepth = 0

    companion object {
        /** Preprocessor symbols that the user can pre-set. */
        var defaultSymbols = setOf<String>()
        private const val MAX_INCLUDE_DEPTH = 10
    }

    // ------------------------------------------------------------------
    // Section-local accumulator state
    // ------------------------------------------------------------------

    /**
 * Mutable accumulator for section-local parsing state.
 * Reused across section transitions (flushed at each section boundary).
 */
    private inner class SectionState {
        var currentSection = ""
        var currentPage: Int = 0
        val pendingMenuItems = mutableListOf<Menu>()
        var currentDialogName = ""
        val currentDialogComponents = mutableListOf<DialogComponent>()
        var currentSettingGroupName = ""
        var currentSettingConstants = mutableListOf<String>()
        var currentIndicatorName = ""
        val currentIndicatorConfigs = mutableListOf<IndicatorConfig>()
        var currentReadoutName = ""
        val currentReadoutConfigs = mutableListOf<ReadoutConfig>()
        var currentLoggerName = ""
        val currentLoggerChannels = mutableListOf<LoggerChannel>()
        var currentHelpTopicName = ""
        val currentHelpText = StringBuilder()
        var currentDatalogViewName = ""
        val currentDatalogViewEntries = mutableListOf<String>()

        /** Flush all accumulators into [def]. */
        fun flush(def: EcuDefinition) {
            if (pendingMenuItems.isNotEmpty()) {
                def.menus.addAll(pendingMenuItems)
                pendingMenuItems.clear()
            }
            if (currentDialogName.isNotEmpty() && currentDialogComponents.isNotEmpty()) {
                def.dialogs[currentDialogName] = DialogDefinition(
                    name = currentDialogName,
                    components = currentDialogComponents.toList()
                )
                currentDialogName = ""
                currentDialogComponents.clear()
            }
            if (currentSettingGroupName.isNotEmpty()) {
                def.settingGroups[currentSettingGroupName] = SettingGroup(
                    name = currentSettingGroupName,
                    constants = currentSettingConstants.toList()
                )
                currentSettingGroupName = ""
                currentSettingConstants.clear()
            }
            if (currentIndicatorName.isNotEmpty() && currentIndicatorConfigs.isNotEmpty()) {
                def.indicatorPanels[currentIndicatorName] = IndicatorPanel(
                    name = currentIndicatorName,
                    indicators = currentIndicatorConfigs.toList()
                )
                currentIndicatorName = ""
                currentIndicatorConfigs.clear()
            }
            if (currentReadoutName.isNotEmpty() && currentReadoutConfigs.isNotEmpty()) {
                def.readoutPanels[currentReadoutName] = ReadoutPanel(
                    name = currentReadoutName,
                    readouts = currentReadoutConfigs.toList()
                )
                currentReadoutName = ""
                currentReadoutConfigs.clear()
            }
            if (currentLoggerName.isNotEmpty() && currentLoggerChannels.isNotEmpty()) {
                def.diagnosticLoggers.add(DiagnosticLogger(
                    name = currentLoggerName,
                    channels = currentLoggerChannels.toList()
                ))
                currentLoggerName = ""
                currentLoggerChannels.clear()
            }
            if (currentHelpTopicName.isNotEmpty()) {
                def.helpTopics[currentHelpTopicName] = HelpTopic(text = currentHelpText.toString())
                currentHelpTopicName = ""
                currentHelpText.clear()
            }
            if (currentDatalogViewName.isNotEmpty() && currentDatalogViewEntries.isNotEmpty()) {
                def.datalogViews[currentDatalogViewName] = DatalogView(
                    name = currentDatalogViewName,
                    entries = currentDatalogViewEntries.toList()
                )
                currentDatalogViewName = ""
                currentDatalogViewEntries.clear()
            }
        }

        /** Reset all accumulators for a new section. */
        fun reset() {
            pendingMenuItems.clear()
            currentDialogName = ""
            currentDialogComponents.clear()
            currentSettingGroupName = ""
            currentSettingConstants.clear()
            currentIndicatorName = ""
            currentIndicatorConfigs.clear()
            currentReadoutName = ""
            currentReadoutConfigs.clear()
            currentLoggerName = ""
            currentLoggerChannels.clear()
            currentHelpTopicName = ""
            currentHelpText.clear()
            currentDatalogViewName = ""
            currentDatalogViewEntries.clear()
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Parse the full text of an INI file into an [EcuDefinition].
     *
     * @param content The complete INI file text.
     * @return [Result.success] with the populated definition, or
     *         [Result.failure] with a descriptive exception.
     */
    fun parse(content: String): Result<EcuDefinition> {
        return try {
            val def = EcuDefinition()
            activeSymbols.clear()
            activeSymbols.addAll(defaultSymbols)
            testedSymbols.clear()
            defines.clear()
            ppStack.clear()
            includeDepth = 0

            val tokenizer = IniTokenizer()
            val tokens = tokenizer.tokenize(content)
            processTokens(tokens, def)

            // Copy preprocessor state into the definition
            def.activeSymbols.addAll(activeSymbols)
            def.testedSymbols.addAll(testedSymbols)
            def.defines.putAll(defines)

            // Infer table roles for the UI
            def.inferTableRoles()

            Result.success(def)
        } catch (e: Exception) {
            Result.failure(IniParseException("INI parse error: ${e.message}", e))
        }
    }

    /**
     * Parse an INI file from the Android assets directory.
     *
     * @param assetsPath Path relative to the assets root (e.g. "tuning/myEcu.ini").
     * @param assetManager Android AssetManager for reading the file.
     * @return [Result.success] with the populated definition, or [Result.failure].
     */
    fun parseFromFile(assetsPath: String, assetManager: AssetManager): Result<EcuDefinition> {
        return try {
            val content = assetManager.open(assetsPath).bufferedReader().use { it.readText() }
            parse(content)
        } catch (e: Exception) {
            Result.failure(IniParseException("Failed to read INI file '$assetsPath': ${e.message}", e))
        }
    }

    // ------------------------------------------------------------------
    // Token processing loop
    // ------------------------------------------------------------------

    /**
     * Walk the token list, dispatching to section-specific handlers.
     */
    private fun processTokens(tokens: List<IniToken>, def: EcuDefinition) {
        val st = SectionState()

        for (token in tokens) {
            // ---- Preprocessor directives (always processed regardless of section) ----
            when (token.type) {
                TokenType.DIRECTIVE_IF -> {
                    handleIf(token)
                    continue
                }
                TokenType.DIRECTIVE_ELSE -> {
                    handleElse()
                    continue
                }
                TokenType.DIRECTIVE_ENDIF -> {
                    handleEndif()
                    continue
                }
                TokenType.DIRECTIVE_DEFINE -> {
                    if (isSkipping()) continue
                    handleDefine(token)
                    continue
                }
                TokenType.DIRECTIVE_INCLUDE -> {
                    if (isSkipping()) continue
                    // #include resolution is handled before parsing in production;
                    // the parser itself just records the token.
                    continue
                }
                else -> {}
            }

            // If we're in a skipped preprocessor block, skip everything else
            if (isSkipping()) continue

            when (token.type) {
                TokenType.BLANK, TokenType.COMMENT -> { /* skip */ }

                TokenType.SECTION -> {
                    st.flush(def)
                    st.reset()
                    st.currentSection = token.content.trim()
                        .removePrefix("[").removeSuffix("]").trim()
                }

                TokenType.KEY_VALUE, TokenType.CONTINUATION -> {
                    val key = token.key
                    val value = token.value

                    when (st.currentSection) {
                        // ---- Identity / protocol ----
                        "MegaTune" -> parseMegaTune(key, value, def)

                        // ---- Data sections ----
                        "Constants" -> parseConstant(key, value, def, st)
                        "OutputChannels" -> parseOutputChannel(key, value, def)
                        "TableEditor", "Table3D", "Table2D" ->
                            parseTableEditor(key, value, st.currentSection, def)
                        "CurveEditor", "Curve2D" -> parseCurveEditor(key, value, def)

                        // ---- UI sections ----
                        "GaugeConfigurations", "GaugeConfig" -> parseGauge(key, value, def)
                        "Menu" -> parseMenu(key, value, st, def)
                        "Dialog" -> parseDialog(key, value, st)
                        "SettingGroups" -> parseSettingGroup(key, value, st, def)

                        // ---- Help ----
                        "HelpTopic" -> parseHelpTopic(key, value, st)

                        // ---- Datalog ----
                        "Datalog" -> parseDatalog(key, value, def)
                        "DatalogViews" -> parseDatalogView(key, value, st, def)

                        // ---- Panels ----
                        "FrontPage" -> parseFrontPage(key, value, def)
                        "IndicatorPanel" -> parseIndicatorPanel(key, value, st)
                        "readoutPanel" -> parseReadoutPanel(key, value, st)

                        // ---- Commands & loggers ----
                        "ControllerCommands" -> parseControllerCommand(key, value, def)
                        "LoggerDefinitions" -> parseLoggerDefinition(key, value, def)
                        "DiagnosticLoggers" -> parseDiagnosticLogger(key, value, st, def)

                        // ---- Reference tables ----
                        "ReferenceTables" -> parseReferenceTable(key, value, def)

                        // ---- Port editors ----
                        "PortEditor" -> parsePortEditor(key, value, def)

                        // ---- FTP browser ----
                        "FTPBrowser" -> parseFtpBrowser(key, value, def)

                        // ---- Key actions ----
                        "KeyActions" -> parseKeyAction(key, value, def)

                        // ---- Analysis configs ----
                        "VeAnalyze" -> parseVeAnalyze(key, value, def)
                        "WueAnalyze" -> parseWueAnalyze(key, value, def)
                        "GammaE" -> parseGammaE(key, value, def)

                        // ---- Constants extensions ----
                        "ConstantsExtensions" -> parseConstantsExtensions(key, value, def)

                        // ---- Table write helpers ----
                        "TableWriteCommand" -> def.tableWriteCommand = value
                        "tableBlockingFactor" -> def.tableBlockingFactor = FieldParser.parseInt(value)

                        // ---- Default / PC variables ----
                        "PCVariable" -> {
                            val v = FieldParser.parseInt(value)
                            def.pcVariables[key] = v.toByte()
                        }
                        "defaultValue" -> {
                            def.defaultValues[key] = FieldParser.parseDouble(value)
                        }

                        else -> {
                            // Unknown section — ignore silently
                        }
                    }
                }

                else -> { /* CONTINUATION already handled above */ }
            }
        }

        // Flush any remaining accumulators
        st.flush(def)
    }

    // ==================================================================
    // Section parsers
    // ==================================================================

    // ------------------------------------------------------------------
    // [MegaTune]
    // ------------------------------------------------------------------

    private fun parseMegaTune(key: String, value: String, def: EcuDefinition) {
        when (key) {
            "signature" -> def.signature = value.removeSurrounding("\"")
            "signaturePrefix" -> def.signaturePrefix = value.removeSurrounding("\"").ifEmpty { null }
            "queryCommand" -> def.queryCommand = value.removeSurrounding("\"")
            "versionInfo" -> def.versionInfo = value.removeSurrounding("\"")
            "iniSpecVersion" -> def.iniSpecVersion = value.trim()
            "ecuitype", "ecuType" -> {
                def.ecuType = when (value.trim().lowercase()) {
                    "ztune" -> EcuType.ZTUNE
                    else -> EcuType.UNKNOWN
                }
            }
            "endianness" -> def.endianness = FieldParser.parseEndianness(value)
            "pageSize" -> {
                val parts = splitIniLine(value)
                def.pageSizes = parts.map { FieldParser.parseInt(it).toShort() }
                def.nPages = def.pageSizes.size.toByte()
            }
            "nPages" -> def.nPages = FieldParser.parseInt(value).toByte()
            "blockSize" -> def.protocol = def.protocol.copy(blockSize = FieldParser.parseInt(value))
            "timeout" -> def.protocol = def.protocol.copy(timeout = FieldParser.parseInt(value))
            "burnCommand" -> def.protocol = def.protocol.copy(burnCommand = value.removeSurrounding("\""))
            "commandTimeout" -> def.protocol = def.protocol.copy(commandTimeout = FieldParser.parseInt(value))
        }
    }

    // ------------------------------------------------------------------
    // [Constants]
    // ------------------------------------------------------------------

    private fun parseConstant(key: String, value: String, def: EcuDefinition, st: SectionState? = null) {
        val name = key.trim()
        if (name.isEmpty()) return

        // Handle the "page = N" directive — switches the current page context
        // for subsequent constants in this [Constants] section.
        if (name.equals("page", ignoreCase = true)) {
            val page = FieldParser.parseInt(value.trim())
            if (page != null && st != null) {
                st.currentPage = page
            }
            return
        }

        // Resolve $define references in the value
        val resolvedValue = FieldParser.resolveDefines(value, defines)

        // Split into field block and description
        val (field, description) = FieldParser.splitFieldAndDescription(resolvedValue)

        val constant = if (field.startsWith("{")) {
            // Standard brace-delimited format: {type; page; offset; scale; translate; units; lo; hi}
            FieldParser.parseConstantField(field, name, defines)
        } else {
            // Try TS-style comma-separated format first:
            //   bits, U08, offset, [lo:hi], "units", scale, translate
            //   array, U08, offset, [count], "units", scale, translate
            //   scalar, U08, offset, "units", scale, translate
            //   2Darray, U08, offset, [rows,cols], "units", scale, translate
            val tsParsed = parseTsStyleConstant(name, field)
            if (tsParsed != null) {
                tsParsed
            } else {
                // Basic format: name = description (scalar, u8, page=0, offset=0)
                createBasicConstant(name, field.ifEmpty { description })
            }
        }

        if (constant != null) {
            // Apply current page context from the [Constants] section if the
            // constant wasn't explicitly given a page (TS-style format doesn't
            // include page in the field — it inherits from the section).
            val withPage = if (st != null && constant.page == 0 && st.currentPage > 0) {
                constant.copy(page = st.currentPage)
            } else {
                constant
            }
            val withMeta = withPage.copy(
                helpText = if (description.isNotEmpty()) description else withPage.helpText
            )
            def.constants[name] = withMeta
        }
    }

    /**
     * Parse TS-style comma-separated constant definition.
     *
     * Supported formats (in order of dispatch):
     *   bits,    U08, offset, [lo:hi],    "units", scale, translate
     *   array,   U08, offset, [count],    "units", scale, translate
     *   array,   U08, offset, [rows,cols],"units", scale, translate   (2D)
     *   scalar,  U08, offset,             "units", scale, translate
     *   U08,     offset,                  "units", scale, translate    (bare type)
     *
     * Returns null if the field doesn't match any TS-style pattern.
     */
    private fun parseTsStyleConstant(name: String, field: String): com.ztune.libretune.core.ini.types.Constant? {
        val parts = FieldParser.splitFieldBlockByComma(field)
        if (parts.size < 2) return null

        var idx = 0
        val typeKeyword = parts[idx].trim().lowercase()
        idx++

        // Detect "bits" / "array" / "scalar" / "2darray" prefix
        val dataTypeStr: String
        when (typeKeyword) {
            "bits", "array", "scalar", "2darray", "2d_array" -> {
                dataTypeStr = parts.getOrNull(idx)?.trim()?.lowercase() ?: "u08"
                idx++
            }
            else -> {
                // No prefix; typeKeyword itself is the data type
                dataTypeStr = typeKeyword
                // Don't advance idx (already consumed)
                idx = 1
            }
        }

        val dataType = FieldParser.parseDataType(dataTypeStr)

        // offset
        val offsetStr = parts.getOrNull(idx)?.trim() ?: return null
        // Strip range like [0:255] or [16] or [16,16] from offset field
        // Actually in TS format, offset is just a number; the range comes as a separate field
        val offset = FieldParser.parseInt(offsetStr) ?: return null
        idx++

        // Peek next field — could be [lo:hi] range, [count] array size, or "units"
        var rangeStr: String? = null
        var arrayShape: com.ztune.libretune.core.ini.types.Shape = com.ztune.libretune.core.ini.types.Shape.Scalar
        val nextField = parts.getOrNull(idx)?.trim() ?: ""
        if (nextField.startsWith("[")) {
            rangeStr = nextField.removeSurrounding("[", "]").trim()
            idx++
            // Detect 2D shape [rows,cols] vs 1D [count] vs range [lo:hi]
            if (rangeStr.contains(":")) {
                // Range like 0:255 — scalar with bounds, no shape change
                arrayShape = com.ztune.libretune.core.ini.types.Shape.Scalar
            } else if (rangeStr.contains(",")) {
                // 2D shape like 16,16
                val dims = rangeStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (dims.size == 2) {
                    arrayShape = com.ztune.libretune.core.ini.types.Shape.Array2D(rows = dims[0], cols = dims[1])
                }
            } else {
                // 1D count
                val count = rangeStr.toIntOrNull()
                if (count != null && count > 1) {
                    arrayShape = com.ztune.libretune.core.ini.types.Shape.Array1D(size = count)
                }
            }
        }

        // Units (quoted)
        val unitsRaw = parts.getOrNull(idx)?.trim() ?: ""
        val units = unitsRaw.removeSurrounding("\"").removeSurrounding("'")
        idx++

        // Scale
        val scale = parts.getOrNull(idx)?.trim()?.toDoubleOrNull() ?: 1.0
        idx++

        // Translate
        val translate = parts.getOrNull(idx)?.trim()?.toDoubleOrNull() ?: 0.0
        idx++

        // lo/hi (optional, from range string)
        var lo = Double.NaN
        var hi = Double.NaN
        if (rangeStr != null && rangeStr.contains(":")) {
            val parts2 = rangeStr.split(":")
            lo = parts2.getOrNull(0)?.trim()?.toDoubleOrNull() ?: Double.NaN
            hi = parts2.getOrNull(1)?.trim()?.toDoubleOrNull() ?: Double.NaN
        }

        // v1.2.1 — some INIs (e.g. ztune.ini) put lo/hi as two TRAILING fields
        // after translate instead of inside the bracket:
        //   array, U16, 200, [16], "RPM", 1.0, 0.0, 800, 14000
        // Here the bracket [16] is just the array count (no ":"), so the lo/hi
        // above stays NaN and we'd otherwise silently default to 0..255. Check
        // for two extra numeric fields at the tail and use them if present.
        if (lo.isNaN() && hi.isNaN()) {
            val trailLo = parts.getOrNull(idx)?.trim()?.toDoubleOrNull()
            val trailHi = parts.getOrNull(idx + 1)?.trim()?.toDoubleOrNull()
            if (trailLo != null && trailHi != null) {
                lo = trailLo
                hi = trailHi
            }
        }

        // v1.3.6 (audit finding #10) — SEBELUMNYA default lo/hi selalu 0.0/255.0
        // (rentang U08 unsigned) untuk SEMUA tipe data kalau INI tidak
        // menyebut [lo:hi] eksplisit — salah untuk tipe signed (S08/S16/S32)
        // yang rentang aslinya negatif (mis. S08 = -128..127), dan juga
        // kurang tepat untuk tipe lebih lebar seperti U16/U32/S16/S32.
        // SEKARANG default diambil dari rentang raw milik dataType itu
        // sendiri (scaled+translated), bukan angka 0.0/255.0 yang dihardcode
        // untuk semua tipe.
        val defaultMin = dataType.rawMin * scale + translate
        val defaultMax = dataType.rawMax * scale + translate

        return com.ztune.libretune.core.ini.types.Constant(
            name = name,
            page = 0,  // will be overridden by current page context if needed
            offset = offset,
            dataType = dataType,
            scale = scale,
            translate = translate,
            units = units,
            shape = arrayShape,
            min = if (lo.isNaN()) minOf(defaultMin, defaultMax) else lo,
            max = if (hi.isNaN()) maxOf(defaultMin, defaultMax) else hi,
            helpText = ""
        )
    }


    /**
     * Create a basic (minimal) constant when no field block is present.
     */
    private fun createBasicConstant(name: String, description: String): Constant {
        return Constant(
            name = name,
            page = 0,
            offset = 0,
            dataType = DataType.U08,
            helpText = description
        )
    }

    // ------------------------------------------------------------------
    // [OutputChannels]
    // ------------------------------------------------------------------

    private fun parseOutputChannel(key: String, value: String, def: EcuDefinition) {
        val name = key.trim()
        if (name.isEmpty()) return

        val resolvedValue = FieldParser.resolveDefines(value, defines)
        val (field, _description) = FieldParser.splitFieldAndDescription(resolvedValue)

        val channel = if (field.startsWith("{")) {
            FieldParser.parseOutputChannelField(field, name)
        } else {
            // v1.3.1 — TunerStudio asli menulis baris ini tanpa kurung kurawal,
            // dipisah koma, diawali keyword scalar/bits/expression:
            //   clt = scalar, S16, 6, "C", 1.0, -40.0
            // Ini BUKAN format "offset polos" (channelName = 123) — coba parser
            // TS dulu, baru fallback ke offset-only kalau baris itu benar cuma
            // satu angka polos.
            FieldParser.parseOutputChannelTsField(field, name)
                ?: run {
                    val offset = FieldParser.parseInt(field)
                    OutputChannel(name = name, offset = offset)
                }
        }

        if (channel != null) {
            def.outputChannels[name] = channel
        }
    }

    // ------------------------------------------------------------------
    // [TableEditor] / [Table3D] / [Table2D]
    // ------------------------------------------------------------------

    private fun parseTableEditor(key: String, value: String, section: String, def: EcuDefinition) {
        val name = key.trim()
        if (name.isEmpty()) return

        val resolvedValue = FieldParser.resolveDefines(value, defines)
        val (field, title) = FieldParser.splitFieldAndDescription(resolvedValue)

        // Parse the table editor entry: "title", tableName, mapName, { xBins, yBins }
        // The field may or may not start with { — handle both formats.
        // Use splitFieldBlockSmart to handle both semicolon-style and comma-style (TS) formats.
        val inner = FieldParser.extractExpression(field) ?: field.trim()
        val parts = FieldParser.splitFieldBlockSmart(inner)

        if (parts.size < 2) return

        // TS format: tableEditor = "VE Table 1", veTable1Tbl, veTable1Map, { rpmBins1, mapBins1 }
        // parts[0] = title (quoted display name, e.g. "VE Table 1")
        // parts[1] = tableName (data constant name, e.g. veTable1Tbl)
        // parts[2] = mapName (legacy/optional, usually same as tableName)
        // parts[3] = { xBinsName, yBinsName } (braced sub-block)
        //
        // Some INIs omit the title and start directly with tableName, so we
        // detect that case by checking if parts[0] is quoted.
        val firstPartRaw = parts.getOrNull(0)?.trim() ?: ""
        val hasTitle = firstPartRaw.startsWith("\"") || firstPartRaw.startsWith("'")
        val titleIdx = if (hasTitle) 0 else -1
        val tableIdx = if (hasTitle) 1 else 0
        val mapIdx = tableIdx + 1
        val binsIdx = mapIdx + 1

        val titleStr = if (titleIdx >= 0)
            parts.getOrNull(titleIdx)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: name
        else name

        val tableName = parts.getOrNull(tableIdx)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: name
        val mapName = parts.getOrNull(mapIdx)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")
        val binsPartRaw = parts.getOrNull(binsIdx)?.trim()
        val binsPart = binsPartRaw?.removeSurrounding("{", "}")?.trim() ?: binsPartRaw
        val binsParts = binsPart?.split(",")?.map { it.trim() } ?: emptyList()
        val xBinsName = binsParts.getOrNull(0) ?: ""
        val yBinsName = binsParts.getOrNull(1) ?: ""

        // Resolve memory layout from linked constants
        val tableConstant = def.constants[tableName]
        val xBinsConstant = def.constants[xBinsName]
        val yBinsConstant = def.constants[yBinsName]

        // Get values from the linked constant
        val page = tableConstant?.page ?: 0
        val valuesOffset = tableConstant?.offset ?: 0
        val dataType = tableConstant?.dataType ?: com.ztune.libretune.core.ini.types.DataType.U08
        val scale = tableConstant?.scale ?: 1.0
        val translate = tableConstant?.translate ?: 0.0
        val units = tableConstant?.units ?: ""

        // Determine rows/cols from the array shape
        val shape = tableConstant?.shape
        val rows = when (shape) {
            is com.ztune.libretune.core.ini.types.Shape.Array2D -> shape.rows
            is com.ztune.libretune.core.ini.types.Shape.Array1D -> shape.size
            else -> {
                // Fallback: infer from xBins/yBins array sizes
                val xSize = (xBinsConstant?.shape as? com.ztune.libretune.core.ini.types.Shape.Array1D)?.size ?: 8
                val ySize = (yBinsConstant?.shape as? com.ztune.libretune.core.ini.types.Shape.Array1D)?.size ?: 8
                if (yBinsName.isNotEmpty()) ySize else 1
            }
        }
        val cols = when (shape) {
            is com.ztune.libretune.core.ini.types.Shape.Array2D -> shape.cols
            is com.ztune.libretune.core.ini.types.Shape.Array1D -> shape.size
            else -> {
                val xSize = (xBinsConstant?.shape as? com.ztune.libretune.core.ini.types.Shape.Array1D)?.size ?: 8
                xSize
            }
        }

        // Build axis definitions from linked bin constants
        val xAxis = if (xBinsName.isNotEmpty()) {
            com.ztune.libretune.core.ini.types.TableAxis(
                binsName = xBinsName,
                binsOffset = xBinsConstant?.offset ?: 0,
                binsPage = xBinsConstant?.page ?: page,
                dataType = xBinsConstant?.dataType ?: com.ztune.libretune.core.ini.types.DataType.U08,
                scale = xBinsConstant?.scale ?: 1.0,
                translate = xBinsConstant?.translate ?: 0.0,
                units = xBinsConstant?.units ?: "",
                min = xBinsConstant?.min ?: 0.0,
                max = xBinsConstant?.max ?: 255.0,
                size = (xBinsConstant?.shape as? com.ztune.libretune.core.ini.types.Shape.Array1D)?.size ?: cols
            )
        } else null

        val yAxis = if (yBinsName.isNotEmpty()) {
            com.ztune.libretune.core.ini.types.TableAxis(
                binsName = yBinsName,
                binsOffset = yBinsConstant?.offset ?: 0,
                binsPage = yBinsConstant?.page ?: page,
                dataType = yBinsConstant?.dataType ?: com.ztune.libretune.core.ini.types.DataType.U08,
                scale = yBinsConstant?.scale ?: 1.0,
                translate = yBinsConstant?.translate ?: 0.0,
                units = yBinsConstant?.units ?: "",
                min = yBinsConstant?.min ?: 0.0,
                max = yBinsConstant?.max ?: 255.0,
                size = (yBinsConstant?.shape as? com.ztune.libretune.core.ini.types.Shape.Array1D)?.size ?: rows
            )
        } else null

        val tableDef = com.ztune.libretune.core.ini.types.TableDefinition(
            name = tableName,
            mapName = mapName,
            tableType = if (yBinsName.isNotEmpty()) com.ztune.libretune.core.ini.types.TableType.TABLE_3D
                        else com.ztune.libretune.core.ini.types.TableType.TABLE_2D,
            page = page,
            xAxis = xAxis,
            yAxis = yAxis,
            valuesOffset = valuesOffset,
            valuesPage = page,
            dataType = dataType,
            scale = scale,
            translate = translate,
            units = units,
            rows = rows,
            cols = cols,
            title = titleStr
        )

        def.tables[tableName] = tableDef
        if (mapName != null && mapName.isNotEmpty()) {
            def.tableMapToName[mapName] = tableName
        }
    }

    // ------------------------------------------------------------------
    // [CurveEditor] / [Curve2D]
    // ------------------------------------------------------------------

    private fun parseCurveEditor(key: String, value: String, def: EcuDefinition) {
        val name = key.trim()
        if (name.isEmpty()) return

        val resolvedValue = FieldParser.resolveDefines(value, defines)
        val (field, title) = FieldParser.splitFieldAndDescription(resolvedValue)

        val inner = FieldParser.extractExpression(field) ?: field
        val parts = FieldParser.splitFieldBlockSmart(inner)

        // TS-style format: curveEditor = "title", valuesConstantName, binsConstantName
        // Some INIs use the brace/semicolon format with explicit offsets.
        val firstPart = parts.getOrNull(0)?.trim() ?: ""
        val hasTitle = firstPart.startsWith("\"") || firstPart.startsWith("'")
        val titleStr = if (hasTitle) {
            firstPart.removeSurrounding("\"").removeSurrounding("'")
        } else title.ifEmpty { name }

        val valuesName: String
        val binsName: String
        var binsOffset = 0
        var binsPage = 0
        var valuesOffset = 0
        var valuesPage = 0
        var size = 0
        var scaleStr = "1"
        var translateStr = "0"
        var units = ""

        if (hasTitle) {
            // TS-style: "title", valuesName, binsName
            valuesName = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
            binsName = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
            // Resolve size/offsets/scale from linked constants if available
            val binsConst = def.constants[binsName]
            val valuesConst = def.constants[valuesName]
            if (binsConst != null) {
                binsOffset = binsConst.offset
                binsPage = binsConst.page
                size = (binsConst.shape as? com.ztune.libretune.core.ini.types.Shape.Array1D)?.size ?: 8
            }
            if (valuesConst != null) {
                valuesOffset = valuesConst.offset
                valuesPage = valuesConst.page
                scaleStr = valuesConst.scale.toString()
                translateStr = valuesConst.translate.toString()
                units = valuesConst.units
                if (size == 0) size = (valuesConst.shape as? com.ztune.libretune.core.ini.types.Shape.Array1D)?.size ?: 8
            }
        } else {
            // Brace-delimited format: valuesName; binsOffset; binsPage; valuesOffset; valuesPage; size; scale; translate; units
            binsName = parts.getOrNull(0)?.trim() ?: ""
            binsOffset = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0
            binsPage = parts.getOrNull(2)?.let { FieldParser.parseInt(it) } ?: 0
            valuesOffset = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 0
            valuesPage = parts.getOrNull(4)?.let { FieldParser.parseInt(it) } ?: 0
            size = parts.getOrNull(5)?.let { FieldParser.parseInt(it) } ?: 0
            scaleStr = parts.getOrNull(6)?.trim() ?: "1"
            translateStr = parts.getOrNull(7)?.trim() ?: "0"
            units = parts.getOrNull(8)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
            valuesName = binsName  // legacy
        }

        // Setiap baris `curveEditor = ...` punya key yang sama ("curveEditor"),
        // jadi pakai nama constant nilai sebagai kunci agar kurva tidak saling menimpa.
        val curveName = if (hasTitle && valuesName.isNotEmpty()) valuesName else name
        def.curves[curveName] = CurveDefinition(
            name = curveName,
            binsName = binsName,
            binsOffset = binsOffset,
            binsPage = binsPage,
            valuesOffset = valuesOffset,
            valuesPage = valuesPage,
            scale = FieldParser.parseDouble(scaleStr),
            translate = FieldParser.parseDouble(translateStr),
            units = units,
            size = size,
            title = titleStr
        )
    }

    // ------------------------------------------------------------------
    // [GaugeConfigurations] / [GaugeConfig]
    // ------------------------------------------------------------------

    private fun parseGauge(key: String, value: String, def: EcuDefinition) {
        val line = "$key = $value"
        val gauge = FieldParser.parseGaugeLine(line)
        if (gauge != null) {
            def.gauges[key] = gauge
        }
    }

    // ------------------------------------------------------------------
    // [Menu]
    // ------------------------------------------------------------------

    private fun parseMenu(key: String, value: String, st: SectionState, def: EcuDefinition) {
        val lowerKey = key.lowercase()

        when {
            lowerKey == "menu" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val command = parts.getOrNull(1)?.trim() ?: ""
                val helpTopic = parts.getOrNull(2)?.trim() ?: ""
                val condition = parts.getOrNull(3)?.trim() ?: ""

                val (tableName, dialogName, cleanedCmd) = resolveMenuTarget(command, def)

                st.pendingMenuItems.add(Menu(
                    label = label,
                    command = cleanedCmd,
                    dialogName = dialogName,
                    tableName = tableName,
                    helpTopic = helpTopic.ifEmpty { null },
                    condition = condition.ifEmpty { null }
                ))
            }
            lowerKey == "submenu" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val command = parts.getOrNull(1)?.trim() ?: ""
                val helpTopic = parts.getOrNull(2)?.trim() ?: ""
                val condition = parts.getOrNull(3)?.trim() ?: ""

                val (tableName, dialogName, cleanedCmd) = resolveMenuTarget(command, def)

                val subItem = Menu(
                    label = label,
                    command = cleanedCmd,
                    dialogName = dialogName,
                    tableName = tableName,
                    helpTopic = helpTopic.ifEmpty { null },
                    condition = condition.ifEmpty { null }
                )

                val lastIdx = st.pendingMenuItems.lastIndex
                if (lastIdx >= 0) {
                    val lastMenu = st.pendingMenuItems[lastIdx]
                    st.pendingMenuItems[lastIdx] = lastMenu.copy(subMenu = lastMenu.subMenu + subItem)
                }
            }
        }
    }

    /**
     * Resolve a menu command into (tableName, dialogName, cleanedCommand).
     *
     * Recognised forms:
     *   "table,veTable1Tbl"      -> tableName=veTable1Tbl
     *   "dialog,std_engine"      -> dialogName=std_engine
     *   "calibration,..."        -> cleanedCmd="calibration,..."
     *   "veTable1Tbl"            -> if it matches a defined table -> tableName, else cleanedCmd=raw
     *   "std_engine"             -> if it matches a defined dialog -> dialogName, else cleanedCmd=raw
     */
    private fun resolveMenuTarget(command: String, def: EcuDefinition): Triple<String?, String?, String> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Triple(null, null, "")

        if (trimmed.startsWith("table,", ignoreCase = true)) {
            val name = trimmed.removePrefix("table,").removePrefix("TABLE,").trim()
            return Triple(name, null, "")
        }
        if (trimmed.startsWith("dialog,", ignoreCase = true)) {
            val name = trimmed.removePrefix("dialog,").removePrefix("DIALOG,").trim()
            return Triple(null, name, "")
        }
        if (trimmed.startsWith("calibration", ignoreCase = true)) {
            return Triple(null, null, trimmed)
        }

        // Bare name -- check if it matches a table or dialog
        if (def.tables.containsKey(trimmed) || def.tableMapToName.containsKey(trimmed)) {
            return Triple(trimmed, null, "")
        }
        if (def.dialogs.containsKey(trimmed)) {
            return Triple(null, trimmed, "")
        }
        // Could be a curve -- treat curve as table-like (will be routed to curve_editor)
        if (def.curves.containsKey(trimmed)) {
            return Triple(trimmed, null, "")
        }

        // Unknown -- pass through as raw command
        return Triple(null, null, trimmed)
    }

    // ------------------------------------------------------------------
    // [Dialog]
    // ------------------------------------------------------------------

    private fun parseDialog(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()

        when {
            lowerKey == "dialog" || lowerKey == "dialogdef" -> {
                // dialog = name, title
                val parts = splitIniLine(value)
                st.currentDialogName = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                st.currentDialogComponents.clear()
            }
            lowerKey == "field" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val fieldName = parts.getOrNull(1)?.trim() ?: ""
                val condition = parts.getOrNull(2)?.trim()?.ifEmpty { null }

                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.FIELD,
                    label = label,
                    name = fieldName,
                    visibilityCondition = condition
                ))
            }
            lowerKey == "spinner" -> {
                val parts = splitIniLine(value)
                val label = parts.getOrNull(0)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val fieldName = parts.getOrNull(1)?.trim() ?: ""

                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.SPINNER,
                    label = label,
                    name = fieldName
                ))
            }
            lowerKey == "panel" -> {
                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.PANEL,
                    panelName = value.trim()
                ))
            }
            lowerKey == "separator" -> {
                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.SEPARATOR
                ))
            }
            lowerKey == "tab" -> {
                st.currentDialogComponents.add(DialogComponent(
                    type = DialogComponentType.TAB,
                    tabName = value.trim().removeSurrounding("\"")
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [SettingGroups]
    // ------------------------------------------------------------------

    private fun parseSettingGroup(key: String, value: String, st: SectionState, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "settinggroup" || lowerKey == "group" -> {
                // Flush previous group into def
                if (st.currentSettingGroupName.isNotEmpty()) {
                    def.settingGroups[st.currentSettingGroupName] = SettingGroup(
                        name = st.currentSettingGroupName,
                        constants = st.currentSettingConstants.toList()
                    )
                    st.currentSettingConstants.clear()
                }
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                st.currentSettingGroupName = name
                if (name.isNotEmpty()) {
                    def.settingGroups[name] = SettingGroup(name = name, title = title)
                }
            }
            lowerKey == "constant" || lowerKey == "entry" -> {
                val constantName = value.trim()
                if (constantName.isNotEmpty()) {
                    st.currentSettingConstants.add(constantName)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [HelpTopic]
    // ------------------------------------------------------------------

    private fun parseHelpTopic(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "helptopic" -> {
                // Flush previous topic into the pending state;
                // actual write to EcuDefinition happens at section change.
                st.currentHelpTopicName = ""
                st.currentHelpText.clear()
                val parts = splitIniLine(value)
                st.currentHelpTopicName = parts.getOrNull(0)?.trim() ?: ""
            }
            lowerKey == "helptext" || lowerKey == "text" -> {
                val text = value.trim().removeSurrounding("\"")
                if (st.currentHelpText.isNotEmpty()) {
                    st.currentHelpText.append("\n")
                }
                st.currentHelpText.append(text)
            }
        }
    }

    // ------------------------------------------------------------------
    // [Datalog]
    // ------------------------------------------------------------------

    private fun parseDatalog(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "datalog" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val channelName = parts.getOrNull(1)?.trim() ?: ""
                def.datalogEntries.add(DatalogEntry(name = name, channelName = channelName))
            }
            lowerKey == "entry" -> {
                def.datalogEntries.add(DatalogEntry(channelName = value.trim()))
            }
        }
    }

    // ------------------------------------------------------------------
    // [DatalogViews]
    // ------------------------------------------------------------------

    private fun parseDatalogView(key: String, value: String, st: SectionState, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "datalogview" -> {
                if (st.currentDatalogViewName.isNotEmpty()) {
                    def.datalogViews[st.currentDatalogViewName] = DatalogView(
                        name = st.currentDatalogViewName,
                        entries = st.currentDatalogViewEntries.toList()
                    )
                    st.currentDatalogViewEntries.clear()
                }
                val name = value.trim()
                st.currentDatalogViewName = name
                if (name.isNotEmpty()) {
                    def.datalogViews[name] = DatalogView(name = name)
                }
            }
            lowerKey == "entry" || lowerKey == "channel" -> {
                val channel = value.trim()
                if (channel.isNotEmpty()) {
                    st.currentDatalogViewEntries.add(channel)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [FrontPage]
    // ------------------------------------------------------------------

    private fun parseFrontPage(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        if (def.frontpage == null) {
            def.frontpage = FrontPageConfig()
        }
        val fp = def.frontpage!!

        when (lowerKey) {
            "gauge" -> def.frontpage = fp.copy(gauges = fp.gauges + value.trim())
            "indicator" -> def.frontpage = fp.copy(indicators = fp.indicators + value.trim())
            "readout" -> def.frontpage = fp.copy(readouts = fp.readouts + value.trim())
            "gauges" -> def.frontpage = fp.copy(gauges = splitIniLine(value).map { it.trim() })
            "indicators" -> def.frontpage = fp.copy(indicators = splitIniLine(value).map { it.trim() })
            "readouts" -> def.frontpage = fp.copy(readouts = splitIniLine(value).map { it.trim() })
        }
    }

    // ------------------------------------------------------------------
    // [IndicatorPanel]
    // ------------------------------------------------------------------

    private fun parseIndicatorPanel(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "indicatorpanel" -> {
                // Flush previous panel
                if (st.currentIndicatorName.isNotEmpty() && st.currentIndicatorConfigs.isNotEmpty()) {
                    // Will be flushed at section change; just update name
                }
                st.currentIndicatorName = value.trim().removeSurrounding("\"")
                st.currentIndicatorConfigs.clear()
            }
            lowerKey == "indicator" -> {
                val parts = splitIniLine(value)
                st.currentIndicatorConfigs.add(IndicatorConfig(
                    name = parts.getOrNull(0)?.trim() ?: "",
                    channelName = parts.getOrNull(1)?.trim() ?: "",
                    label = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                    onColor = parts.getOrNull(3)?.trim() ?: "green",
                    offColor = parts.getOrNull(4)?.trim() ?: "red"
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [readoutPanel]
    // ------------------------------------------------------------------

    private fun parseReadoutPanel(key: String, value: String, st: SectionState) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "readoutpanel" -> {
                st.currentReadoutName = value.trim().removeSurrounding("\"")
                st.currentReadoutConfigs.clear()
            }
            lowerKey == "readout" -> {
                val parts = splitIniLine(value)
                st.currentReadoutConfigs.add(ReadoutConfig(
                    name = parts.getOrNull(0)?.trim() ?: "",
                    channelName = parts.getOrNull(1)?.trim() ?: "",
                    label = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                    decimals = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 1,
                    units = parts.getOrNull(4)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [ControllerCommands]
    // ------------------------------------------------------------------

    private fun parseControllerCommand(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "controllercommand" || lowerKey == "command" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val command = parts.getOrNull(1)?.trim() ?: ""
                val description = parts.getOrNull(2)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                if (name.isNotEmpty()) {
                    def.controllerCommands[name] = ControllerCommand(
                        name = name, command = command, description = description
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [LoggerDefinitions]
    // ------------------------------------------------------------------

    private fun parseLoggerDefinition(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "loggerdefinition" || lowerKey == "logger" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.loggerDefinitions[name] = LoggerDefinition(
                        name = name,
                        startCommand = parts.getOrNull(1)?.trim() ?: "",
                        stopCommand = parts.getOrNull(2)?.trim() ?: "",
                        readCommand = parts.getOrNull(3)?.trim() ?: "",
                        logSize = parts.getOrNull(4)?.let { FieldParser.parseInt(it) } ?: 0
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [DiagnosticLoggers]
    // ------------------------------------------------------------------

    private fun parseDiagnosticLogger(key: String, value: String, st: SectionState, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "diagnosticlogger" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                val startCmd = parts.getOrNull(2)?.trim() ?: ""
                val stopCmd = parts.getOrNull(3)?.trim() ?: ""
                val readCmd = parts.getOrNull(4)?.trim() ?: ""
                val recordSize = parts.getOrNull(5)?.let { FieldParser.parseInt(it) } ?: 0
                val bufferSize = parts.getOrNull(6)?.let { FieldParser.parseInt(it) } ?: 0

                if (name.isNotEmpty()) {
                    // Flush previous logger's channels
                    if (st.currentLoggerName.isNotEmpty() && st.currentLoggerChannels.isNotEmpty()) {
                        // Find the previously-added logger and append channels
                        val prevIdx = def.diagnosticLoggers.indexOfFirst { it.name == st.currentLoggerName }
                        if (prevIdx >= 0) {
                            val prev = def.diagnosticLoggers[prevIdx]
                            def.diagnosticLoggers[prevIdx] = prev.copy(
                                channels = prev.channels + st.currentLoggerChannels
                            )
                        }
                        st.currentLoggerChannels.clear()
                    }

                    def.diagnosticLoggers.add(DiagnosticLogger(
                        name = name, title = title,
                        startCommand = startCmd, stopCommand = stopCmd,
                        readCommand = readCmd, recordSize = recordSize,
                        bufferSize = bufferSize
                    ))
                    st.currentLoggerName = name
                }
            }
            lowerKey == "channel" -> {
                val parts = splitIniLine(value)
                st.currentLoggerChannels.add(LoggerChannel(
                    name = parts.getOrNull(0)?.trim() ?: "",
                    offset = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0,
                    bitOffset = parts.getOrNull(2)?.let { FieldParser.parseInt(it) } ?: -1,
                    bitWidth = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 0,
                    dataType = parts.getOrNull(4)?.let { FieldParser.parseDataType(it) } ?: DataType.U16,
                    scale = parts.getOrNull(5)?.let { FieldParser.parseDouble(it) } ?: 1.0,
                    translate = parts.getOrNull(6)?.let { FieldParser.parseDouble(it) } ?: 0.0,
                    units = parts.getOrNull(7)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                    label = parts.getOrNull(8)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                ))
            }
        }
    }

    // ------------------------------------------------------------------
    // [ReferenceTables]
    // ------------------------------------------------------------------

    private fun parseReferenceTable(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "referencetable" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.referenceTables[name] = ReferenceTable(
                        name = name,
                        xBinsOffset = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0,
                        xBinsPage = parts.getOrNull(2)?.let { FieldParser.parseInt(it) } ?: 0,
                        xBinsSize = parts.getOrNull(3)?.let { FieldParser.parseInt(it) } ?: 0,
                        valuesOffset = parts.getOrNull(4)?.let { FieldParser.parseInt(it) } ?: 0,
                        valuesPage = parts.getOrNull(5)?.let { FieldParser.parseInt(it) } ?: 0,
                        valuesSize = parts.getOrNull(6)?.let { FieldParser.parseInt(it) } ?: 0
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [PortEditor]
    // ------------------------------------------------------------------

    private fun parsePortEditor(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "porteditor" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.portEditors[name] = PortEditorConfig(
                        name = name,
                        title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: "",
                        mode = parts.getOrNull(2)?.trim() ?: ""
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [FTPBrowser]
    // ------------------------------------------------------------------

    private fun parseFtpBrowser(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "ftpbrowser" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                if (name.isNotEmpty()) {
                    def.ftpBrowsers[name] = FTPBrowserConfig(
                        name = name,
                        title = parts.getOrNull(1)?.trim()?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [KeyActions]
    // ------------------------------------------------------------------

    private fun parseKeyAction(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "keyaction" -> {
                val parts = splitIniLine(value)
                val keyName = parts.getOrNull(0)?.trim() ?: ""
                val action = parts.getOrNull(1)?.trim() ?: ""
                if (keyName.isNotEmpty()) {
                    def.keyActions.add(KeyAction(key = keyName, action = action))
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // [VeAnalyze]
    // ------------------------------------------------------------------

    private fun parseVeAnalyze(key: String, value: String, def: EcuDefinition) {
        when (key.lowercase()) {
            "vetable" -> def.veAnalyze = (def.veAnalyze ?: VeAnalyzeConfig()).copy(veTableName = value.trim())
            "targettable" -> def.veAnalyze = (def.veAnalyze ?: VeAnalyzeConfig()).copy(targetTableName = value.trim())
            "lambdatarget" -> {
                val tables = splitIniLine(value).map { it.trim() }
                def.veAnalyze = (def.veAnalyze ?: VeAnalyzeConfig()).copy(lambdaTargetTables = tables)
            }
        }
    }

    // ------------------------------------------------------------------
    // [WueAnalyze]
    // ------------------------------------------------------------------

    private fun parseWueAnalyze(key: String, value: String, def: EcuDefinition) {
        when (key.lowercase()) {
            "wuecurve" -> def.wueAnalyze = (def.wueAnalyze ?: WueAnalyzeConfig()).copy(wueCurveName = value.trim())
            "targettable" -> def.wueAnalyze = (def.wueAnalyze ?: WueAnalyzeConfig()).copy(targetTableName = value.trim())
            "lambdatarget" -> {
                val tables = splitIniLine(value).map { it.trim() }
                def.wueAnalyze = (def.wueAnalyze ?: WueAnalyzeConfig()).copy(lambdaTargetTables = tables)
            }
        }
    }

    // ------------------------------------------------------------------
    // [GammaE]
    // ------------------------------------------------------------------

    private fun parseGammaE(key: String, value: String, def: EcuDefinition) {
        when (key.lowercase()) {
            "table" -> def.gammaE = (def.gammaE ?: GammaEConfig()).copy(table = value.trim())
            "channel" -> def.gammaE = (def.gammaE ?: GammaEConfig()).copy(channel = value.trim())
            "overlaytable" -> def.gammaE = (def.gammaE ?: GammaEConfig()).copy(overlayTable = value.trim())
        }
    }

    // ------------------------------------------------------------------
    // [ConstantsExtensions]
    // ------------------------------------------------------------------

    private fun parseConstantsExtensions(key: String, value: String, def: EcuDefinition) {
        val lowerKey = key.lowercase()
        when {
            lowerKey == "maintainconstantvalue" -> {
                val parts = splitIniLine(value)
                val constantName = parts.getOrNull(0)?.trim() ?: ""
                val expression = parts.getOrNull(1)?.trim() ?: ""
                if (constantName.isNotEmpty()) {
                    def.maintainConstantValues.add(MaintainConstantValue(
                        constantName = constantName, expression = expression
                    ))
                }
            }
            lowerKey == "maximumelements" || lowerKey == "maximuelements" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val max = parts.getOrNull(1)?.let { FieldParser.parseInt(it) } ?: 0
                if (name.isNotEmpty()) {
                    def.maximumElements[name] = max
                }
            }
            lowerKey == "defaultvalue" -> {
                val parts = splitIniLine(value)
                val name = parts.getOrNull(0)?.trim() ?: ""
                val defaultVal = parts.getOrNull(1)?.let { FieldParser.parseDouble(it) } ?: 0.0
                if (name.isNotEmpty()) {
                    def.defaultValues[name] = defaultVal
                }
            }
            lowerKey == "requirespowercycle" -> {
                def.requiresPowerCycle.add(value.trim())
            }
        }
    }

    // ==================================================================
    // Preprocessor handling
    // ==================================================================

    /**
     * Handle `#if SYMBOL` or `#ifdef SYMBOL`.
     *
     * Supports simple symbol presence checks and compound conditions:
     * - `#if CELSIUS` — single symbol
     * - `#if CELSIUS && STM32` — all must be active (AND)
     * - `#if CELSIUS || FAHRENHEIT` — any must be active (OR)
     */
    private fun handleIf(token: IniToken) {
        val condition = token.value.trim()
        val hasOr = condition.contains("||")

        val symbols = condition
            .replace("&&", " ").replace("||", " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("&&") && !it.equals("||") }

        for (sym in symbols) {
            testedSymbols.add(sym)
        }

        val branchTaken = if (hasOr) {
            symbols.any { it in activeSymbols }
        } else {
            symbols.all { it in activeSymbols }
        }

        ppStack.add(Pair(condition, branchTaken))
    }

    /** Handle `#else` — flip the most recent `#if` result. */
    private fun handleElse() {
        if (ppStack.isNotEmpty()) {
            val last = ppStack.removeAt(ppStack.lastIndex)
            ppStack.add(Pair(last.first, !last.second))
        }
    }

    /** Handle `#endif` — pop the preprocessor stack. */
    private fun handleEndif() {
        if (ppStack.isNotEmpty()) {
            ppStack.removeAt(ppStack.lastIndex)
        }
    }

    /** Handle `#define NAME value1 value2 ...` */
    private fun handleDefine(token: IniToken) {
        val name = token.key.trim()
        val valueStr = token.value.trim()
        if (name.isEmpty()) return

        defines[name] = if (valueStr.isNotEmpty()) {
            valueStr.split(Regex("\\s+"))
        } else {
            emptyList()
        }
    }

    /**
     * Check whether we are currently in a skipped preprocessor block.
     * A line is skipped if ANY entry on the preprocessor stack has
     * `branchTaken == false`.
     */
    private fun isSkipping(): Boolean = ppStack.any { !it.second }
}