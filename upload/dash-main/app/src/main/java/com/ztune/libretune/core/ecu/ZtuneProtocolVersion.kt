package com.ztune.libretune.core.ecu

/**
 * v2.0.0 (audit round-2, finding R2-SIGNATURE) — versi protokol ZTune.
 *
 * Mewakili string versi firmware/app berformat "ZTune MAJOR.MINOR.PATCH"
 * (mis. "ZTune 1.2.0"). Bisa diparse dari:
 *  - respons CMD_PING_INFO (teks mentah atau JSON yang mengandung
 *    field "fw":"ZTune x.y.z"), dan
 *  - definition.signature dari INI (mis. signature = "ZTune 1.2.0").
 *
 * Kebijakan kompatibilitas (lihat ZtuneEcu.connect):
 *  - MAJOR beda  → keluarga protokol berbeda → tolak koneksi.
 *  - MINOR beda  → sesi read-only (WRITE ditolak).
 *  - PATCH beda  → kompatibel penuh.
 *
 * Regex dipakai pada TEKS respons (bukan JSON.parse) supaya tetap bekerja
 * baik firmware mengirim JSON maupun plain text — pencarian dilakukan pada
 * kemunculan "ZTune <versi>" pertama.
 */
data class ZtuneProtocolVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** Teks asli tempat versi diparse (untuk pesan error/diagnostik). */
    val raw: String
) {
    override fun toString(): String = "ZTune $major.$minor.$patch"

    companion object {
        /**
         * Mencari pola `ZTune MAJOR.MINOR[.PATCH]` (case-insensitive pada
         * kata "ztune") di [text] dan mengembalikan versi parsable, atau
         * null bila tidak ditemukan / komponen versi bukan angka.
         */
        fun parse(text: String?): ZtuneProtocolVersion? {
            if (text.isNullOrBlank()) return null
            // Accept "ZTune 1.2.0", "ZTune 1.2", "ZTUNE 1.2.0"; embedded in JSON OK.
            val regex = Regex("""ztune\s+(\d+)\.(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)
            val m = regex.find(text) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            if (major < 0 || minor < 0 || patch < 0) return null
            return ZtuneProtocolVersion(major, minor, patch, text.take(200))
        }
    }
}