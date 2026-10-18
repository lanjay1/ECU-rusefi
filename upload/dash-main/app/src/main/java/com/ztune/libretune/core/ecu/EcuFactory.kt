package com.ztune.libretune.core.ecu

import com.ztune.libretune.core.ini.types.EcuType

/** Hanya ada satu firmware (ZTune), jadi factory selalu membuat ZtuneEcu. */
object EcuFactory {

    @Suppress("UNUSED_PARAMETER")
    fun create(ecuType: EcuType = EcuType.ZTUNE): EcuInterface = ZtuneEcu()

    fun detectFromSignature(signature: String): EcuType =
        if (signature.lowercase().contains("ztune")) EcuType.ZTUNE else EcuType.UNKNOWN
}