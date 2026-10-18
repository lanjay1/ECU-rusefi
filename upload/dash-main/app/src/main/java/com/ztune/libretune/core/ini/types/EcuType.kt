package com.ztune.libretune.core.ini.types

/**
 * Aplikasi ini hanya mendukung firmware ZTune.
 * UNKNOWN = belum diset (EcuDefinition.default()).
 */
enum class EcuType {
    UNKNOWN,
    ZTUNE;

    /** Konsol interaktif belum ada untuk ZTune. */
    fun supportsConsole(): Boolean = false
}