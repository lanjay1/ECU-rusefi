package com.ztune.libretune.ui.screens.tune_editor.view3d

import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.hypot

/**
 * Compose wrapper for GLSurfaceView rendering the 3D heightmap.
 *
 * Rev 3 — touch overhaul. Alasan revisi: versi sebelumnya "tidak bisa
 * digerakkan" karena dua jebakan klasik GLSurfaceView:
 *
 * 1. OnTouchListener tidak pernah dipanggil di sebagian build Android/OEM
 *    karena view tidak `clickable`. Sekarang view di-set clickable secara
 *    eksplisit sehingga dispatchTouchEvent selalu menyerahkan event ke listener.
 * 2. Delta drag dihitung dari `getHistoricalX/Y` yang BISA kosong
 *    (historySize == 0) pada beberapa perangkat — gerakan jadi diabaikan.
 *    Sekarang delta dihitung manual dari posisi frame sebelumnya
 *    (lastX/lastY), bekerja di semua perangkat tanpa bergantung history.
 * 3. Pinch sekarang memakai ACTION_POINTER_DOWN/POINTER_UP eksplisit +
 *    baseline jarak antar-jari, bukan bergantung historical samples.
 *
 * Gesture:
 * - Satu jari drag  -> orbit (azimuth + elevation) + fling/inertia saat lepas
 * - Dua jari pinch  -> zoom saja (rotasi sengaja dimatikan di mode pinch)
 * - Double tap      -> reset kamera ke posisi default
 */
@Composable
fun Table3DView(
    values: List<List<Double>>,
    min: Double,
    max: Double,
    modifier: Modifier = Modifier
) {
    val renderer = remember { HeightmapRenderer() }

    // State touch dibungkus remember agar instance yang sama bertahan
    // lintas recomposition — listener (dipasang sekali di factory) tetap
    // membaca state yang hidup, bukan salinan lama.
    val touch = remember { TouchState() }

    AndroidView(
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

                // KRITIS: tanpa clickable=true, beberapa build Android tidak
                // pernah memanggil OnTouchListener walaupun sudah dipasang.
                isClickable = true
                isFocusable = true

                setOnTouchListener { view, event ->
                    val glView = view as GLSurfaceView
                    handleTouch(glView, event, renderer, touch)
                    true
                }
            }
        },
        update = { glView ->
            // setTable() mutasi mesh yang dibaca onDrawFrame() di GL thread —
            // jalankan via queueEvent agar mutasi terjadi di thread yang sama
            // dengan pembacanya (hindari race/corrupted mesh).
            glView.queueEvent {
                renderer.setTable(values, min, max)
            }
            glView.requestRender()
        },
        modifier = modifier.fillMaxSize()
    )
}

// ---------------------------------------------------------------------------
// Touch handling
// ---------------------------------------------------------------------------

/** Holder seluruh state gesture (UI thread saja — tidak perlu sinkronisasi). */
private class TouchState {
    // Orbit / drag
    var lastX: Float = 0f
    var lastY: Float = 0f
    var downX: Float = 0f
    var downY: Float = 0f
    var downTime: Long = 0L
    var moved: Boolean = false

    // Double-tap reset
    var lastTapTime: Long = 0L

    // Pinch
    var pinching: Boolean = false
    var lastSpan: Float = 0f

    // Fling / inertia
    var velocityX: Float = 0f
    var velocityY: Float = 0f
    var flinging: Boolean = false
    val decay: Float = 0.92f          // pelambatan per frame (< 1 = melambat)
    val stopThreshold: Float = 0.0005f // berhenti saat kecepatan di bawah ini
}

private fun handleTouch(
    view: GLSurfaceView,
    event: MotionEvent,
    renderer: HeightmapRenderer,
    st: TouchState
) {
    when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
            st.lastX = event.x
            st.lastY = event.y
            st.downX = event.x
            st.downY = event.y
            st.downTime = SystemClock.uptimeMillis()
            st.moved = false
            st.pinching = false
            st.flinging = false
            st.velocityX = 0f
            st.velocityY = 0f
        }

        MotionEvent.ACTION_POINTER_DOWN -> {
            if (event.pointerCount >= 2) {
                st.pinching = true
                st.flinging = false
                st.lastSpan = pointerSpan(event)
            }
        }

        MotionEvent.ACTION_MOVE -> {
            if (st.pinching && event.pointerCount >= 2) {
                // ---- Pinch zoom (rotasi sengaja tidak diterapkan) ----
                val span = pointerSpan(event)
                if (st.lastSpan > 0f && span > 0f) {
                    renderer.zoom = (renderer.zoom * st.lastSpan / span)
                        .coerceIn(0.5f, 15f)
                    view.requestRender()
                }
                st.lastSpan = span
            } else if (!st.pinching && event.pointerCount >= 1) {
                // ---- Orbit satu jari: delta manual dari posisi terakhir ----
                val dx = event.x - st.lastX
                val dy = event.y - st.lastY
                st.lastX = event.x
                st.lastY = event.y

                val sens = 0.006f // rad per piksel (~0.34 deg per piksel)
                renderer.azimuth += dx * sens
                renderer.elevation = (renderer.elevation - dy * sens)
                    .coerceIn(-1.4f, 1.4f)

                // Kecepatan ter-smooth untuk fling saat jari dilepas.
                st.velocityX = st.velocityX * 0.6f + dx * sens * 0.4f
                st.velocityY = st.velocityY * 0.6f - dy * sens * 0.4f

                if (hypot(
                        (event.x - st.downX).toDouble(),
                        (event.y - st.downY).toDouble()
                    ) > 12.0
                ) {
                    st.moved = true
                }
                view.requestRender()
            }
        }

        MotionEvent.ACTION_POINTER_UP -> {
            // Pinch tetap aktif sampai ACTION_UP tersisa satu jari.
        }

        MotionEvent.ACTION_UP -> {
            if (st.pinching) {
                // Akhir dari pinch — jangan olah tap/fling.
                st.pinching = false
                return
            }
            val elapsed = SystemClock.uptimeMillis() - st.downTime
            if (!st.moved && elapsed < 250) {
                // Tap cepat tanpa gerak — deteksi double-tap untuk reset view.
                val now = SystemClock.uptimeMillis()
                if (now - st.lastTapTime < 300) {
                    renderer.azimuth = 0.6f
                    renderer.elevation = 0.5f
                    renderer.zoom = 3.0f
                    st.velocityX = 0f
                    st.velocityY = 0f
                    st.flinging = false
                    view.requestRender()
                    st.lastTapTime = 0L
                } else {
                    st.lastTapTime = now
                }
            } else if (st.moved) {
                // Lepas setelah drag — mulai fling (inertia) sampai melambat.
                st.flinging = true
                view.post(object : Runnable {
                    override fun run() {
                        if (!st.flinging) return
                        if (st.velocityX * st.velocityX + st.velocityY * st.velocityY <
                            st.stopThreshold * st.stopThreshold
                        ) {
                            st.flinging = false
                            return
                        }
                        renderer.azimuth += st.velocityX
                        renderer.elevation = (renderer.elevation + st.velocityY)
                            .coerceIn(-1.4f, 1.4f)
                        st.velocityX *= st.decay
                        st.velocityY *= st.decay
                        view.requestRender()
                        view.post(this)
                    }
                })
            }
        }

        MotionEvent.ACTION_CANCEL -> {
            st.flinging = false
            st.pinching = false
            st.velocityX = 0f
            st.velocityY = 0f
        }
    }
}

/** Jarak antar dua pointer pertama (untuk pinch). */
private fun pointerSpan(event: MotionEvent): Float =
    if (event.pointerCount >= 2) {
        hypot(
            (event.getX(0) - event.getX(1)).toDouble(),
            (event.getY(0) - event.getY(1)).toDouble()
        ).toFloat()
    } else {
        0f
    }