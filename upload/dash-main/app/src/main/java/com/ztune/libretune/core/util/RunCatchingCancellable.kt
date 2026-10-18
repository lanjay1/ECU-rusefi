package com.ztune.libretune.core.util

import kotlinx.coroutines.TimeoutCancellationException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Jalankan [block] dan bungkus hasilnya dalam [Result].
 *
 *  - Pembatalan coroutine (Job.cancel, scope dibatalkan) DILEMPAR ULANG supaya
 *    structured concurrency tetap benar.
 *  - [TimeoutCancellationException] adalah subclass CancellationException, tapi
 *    artinya "operasi kehabisan waktu", bukan "coroutine dibatalkan". Karena itu
 *    dijadikan Result.failure biasa, bukan dilempar ulang.
 *
 * Catatan: lebih baik pakai withTimeoutOrNull di tempat timeout dibuat, lalu
 * lempar exception sendiri. Fungsi ini jaring pengaman.
 * Jangan dipakai untuk kode non-suspend; runCatching biasa sudah cukup di sana.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: TimeoutCancellationException) {
    Result.failure(e)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}