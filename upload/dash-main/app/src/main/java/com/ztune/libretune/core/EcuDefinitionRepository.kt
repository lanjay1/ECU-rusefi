package com.ztune.libretune.core

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.ztune.libretune.core.ini.EcuDefinition
import com.ztune.libretune.core.ini.parser.IniParser
import com.ztune.libretune.core.ini.types.EcuType

/**
 * Memuat dan meng-cache definisi INI dari assets/definitions/.
 * Hanya ZTune: assets/definitions/ztune/ztune.ini ([ZTUNE_INI_PATH]). Tanpa impor INI luar.
 *
 * Thread-safety: cache DAN parser disinkronkan pada [cache]
 * (IniParser punya state internal, tidak boleh dipakai paralel).
 */
class EcuDefinitionRepository(private val context: Context) {

    companion object {
        private const val TAG = "EcuDefRepo"
        private const val DEFINITIONS_DIR = "definitions"
        const val ZTUNE_INI_PATH = "definitions/ztune/ztune.ini"
    }

    private val cache = mutableMapOf<String, EcuDefinition>()
    private val parser = IniParser()

    fun listDefinitions(): List<DefinitionInfo> {
        val results = mutableListOf<DefinitionInfo>()
        val assets = context.assets
        for (dirName in safeList(assets, DEFINITIONS_DIR)) {
            val dirPath = "$DEFINITIONS_DIR/$dirName"
            for (file in safeList(assets, dirPath)) {
                if (!file.endsWith(".ini", ignoreCase = true)) continue
                val fullPath = "$dirPath/$file"
                val signature = synchronized(cache) { cache[fullPath]?.signature ?: "" }
                results.add(
                    DefinitionInfo(
                        fileName = file,
                        path = fullPath,
                        ecuType = EcuType.ZTUNE,
                        signature = signature
                    )
                )
            }
        }
        return results.sortedBy { it.fileName }
    }

    /** Pintasan: definisi ZTune bawaan. */
    fun loadZtune(): Result<EcuDefinition> = loadDefinition(ZTUNE_INI_PATH)

    fun loadDefinition(path: String): Result<EcuDefinition> = synchronized(cache) {
        cache[path]?.let { return@synchronized Result.success(it) }
        if (!path.startsWith("$DEFINITIONS_DIR/") || path.contains("..")) {
            return@synchronized Result.failure(
                IllegalArgumentException("Path definisi tidak valid: $path")
            )
        }
        try {
            val text = context.assets.open(path)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val definition = parser.parse(text).getOrThrow()
            definition.inferTableRoles()
            cache[path] = definition
            Log.d(TAG, "Loaded definition: $path (sig=${definition.signature})")
            Result.success(definition)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load definition: $path", e)
            Result.failure(e)
        }
    }

    fun findDefinitionForSignature(signature: String): EcuDefinition? {
        val def = loadZtune().getOrNull() ?: return null
        val sig = def.signature
        return if (sig.isNotEmpty() && (sig == signature || signature.startsWith(sig))) def else null
    }

    fun getAllDefinitions(): Map<String, EcuDefinition> = synchronized(cache) { cache.toMap() }

    fun clearCache() = synchronized(cache) { cache.clear() }

    private fun safeList(assetManager: AssetManager, path: String): Array<String> =
        try {
            assetManager.list(path) ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "Could not list assets: $path", e)
            emptyArray()
        }
}

data class DefinitionInfo(
    val fileName: String,
    val path: String,
    val ecuType: EcuType,
    val signature: String = ""
)