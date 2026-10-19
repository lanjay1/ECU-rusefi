package com.ztune.libretune.ui.screens.tune_editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Canvas-based 2D table editor.
 *
 * Gesture design:
 * - Plain single-finger drag                -> PAN the view (mostly a no-op now,
 *                                              since the table always auto-fits
 *                                              the screen — see below).
 * - Single tap                              -> select one cell (or edit, decided by caller).
 * - Long-press and hold (no movement)       -> context menu (unchanged).
 * - Long-press THEN drag                    -> multi-cell range selection.
 * - Two-finger touch                        -> consumed for panning only; no longer
 *                                              changes zoom, since there's no button
 *                                              left to reset it back to fit.
 *
 * Zoom: there are no manual zoom controls. `scale` is auto-computed from the
 * canvas size and table dimensions (rows/cols) so the whole table always fits
 * on screen in one shot, every time the screen size or table changes.
 */
@Composable
fun CanvasTableEditor(
    values: List<List<Double>>,
    xBins: List<Double>,
    yBins: List<Double>,
    min: Double,
    max: Double,
    format: String,
    units: String,
    selectedCell: Pair<Int, Int>?,
    selectedCells: Set<Pair<Int, Int>>,
    liveCell: Pair<Int, Int>?,
    onCellTap: (Int, Int) -> Unit,
    onCellLongPress: (Int, Int) -> Unit,
    onCellDragStart: (Int, Int) -> Unit,
    onCellDrag: (Int, Int) -> Unit,
    onCellDragEnd: () -> Unit,
    // v1.2.0 — Tap header axis bin untuk edit
    onXBinClick: (Int) -> Unit = {},
    onYBinClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (values.isEmpty() || values[0].isEmpty()) return

    val rows = values.size
    val cols = values[0].size
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current

    // Text layout cache: measuring text (font shaping/layout) is comparatively
    // expensive, and the previous version called textMeasurer.measure() fresh
    // for every visible cell on every single redraw — including every tiny
    // scroll/pan movement, even though the displayed numbers don't change
    // while panning, only their on-screen position does. That repeated
    // measurement was the main cause of the lag during drag. This cache is
    // keyed by the rendered string (+ text color bucket), and is only reset
    // when the underlying table data actually changes — not on every pan frame.
    val textLayoutCache = remember(values, xBins, yBins, format, units) {
        mutableMapOf<String, androidx.compose.ui.text.TextLayoutResult>()
    }

    // Colors — axis strips use fixed, higher-contrast colors instead of the
    // theme's surfaceVariant, which sits too close in luminance to the dark
    // background and reads as "see-through" even though it's fully opaque.
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant
    val selectionColor = MaterialTheme.colorScheme.primary
    val liveCursorColor = MaterialTheme.colorScheme.tertiary
    val axisBgColor = Color(0xFF2A3A36).copy(alpha = 1f)
    val axisTextColor = Color(0xFFE8F0ED).copy(alpha = 1f)

    // Base (100%) layout constants — these get multiplied by `scale` everywhere below.
    val cornerSize = with(density) { 56.dp.toPx() }
    val headerHeight = with(density) { 28.dp.toPx() }
    val baseCellWidth = with(density) { 72.dp.toPx() }
    val baseCellHeight = with(density) { 44.dp.toPx() }

    val minScale = 0.12f
    val maxScale = 2.5f

    // No manual zoom controls anymore — the table always auto-fits the
    // screen. `scale` is recomputed below whenever canvas size or table
    // dimensions change, so all rows/cols are visible without scrolling.
    var scale by remember { mutableStateOf(1f) }
    var scrollX by remember { mutableStateOf(0f) }
    var scrollY by remember { mutableStateOf(0f) }

    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Recompute fit-scale whenever the canvas size becomes known or the
    // table dimensions change (e.g. switching from an 8x8 to a 16x16 table).
    LaunchedEffect(canvasSize, rows, cols) {
        if (canvasSize.width > 0f && canvasSize.height > 0f) {
            val fitScaleX = (canvasSize.width - cornerSize) / (cols * baseCellWidth)
            val fitScaleY = (canvasSize.height - headerHeight) / (rows * baseCellHeight)
            scale = minOf(fitScaleX, fitScaleY).coerceIn(minScale, maxScale)
            scrollX = 0f
            scrollY = 0f
        }
    }

    fun clampScroll(newScrollX: Float, newScrollY: Float, currentScale: Float): Pair<Float, Float> {
        val cellW = baseCellWidth * currentScale
        val cellH = baseCellHeight * currentScale
        val maxScrollX = -((cols * cellW) - canvasSize.width + cornerSize).coerceAtLeast(0f)
        val maxScrollY = -((rows * cellH) - canvasSize.height + headerHeight).coerceAtLeast(0f)
        val clampedX = newScrollX.coerceAtMost(0f).coerceAtLeast(maxScrollX)
        val clampedY = newScrollY.coerceAtMost(0f).coerceAtLeast(maxScrollY)
        return clampedX to clampedY
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(rows, cols) {
                    canvasSize = Size(size.width.toFloat(), size.height.toFloat())

                    awaitEachGesture {
                        val cellW = baseCellWidth * scale
                        val cellH = baseCellHeight * scale

                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        val downTime = System.currentTimeMillis()
                        val downPos = firstDown.position
                        var lastPos = downPos
                        val pressCell = offsetToCell(
                            downPos, cornerSize, headerHeight, cellW, cellH, rows, cols, scrollX, scrollY
                        )

                        var longPressFired = false
                        var mode = GestureMode.NONE // NONE -> PAN or SELECT once decided
                        var multiTouch = false

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }

                            if (pressed.size >= 2) {
                                // Pinch-to-zoom + two-finger pan. The table still opens
                                // auto-fitted (see the LaunchedEffect above), but with 16+
                                // columns fitted to one screen, cells get small and the
                                // (fixed-size) text inside them gets cramped — pinch lets
                                // you zoom in to read/edit precisely, and dragging then
                                // reveals columns that no longer fit at that zoom level.
                                multiTouch = true
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
                                scale = newScale
                                val (cx, cy) = clampScroll(scrollX + panChange.x, scrollY + panChange.y, newScale)
                                scrollX = cx
                                scrollY = cy
                                event.changes.forEach { it.consume() }
                                if (pressed.all { it.changedToUp() }) break
                                continue
                            }

                            val change = pressed.firstOrNull()
                            if (change == null) {
                                // All fingers lifted.
                                break
                            }

                            val movedSinceDown = (change.position - downPos).getDistance()
                            val elapsed = System.currentTimeMillis() - downTime

                            if (mode == GestureMode.NONE) {
                                when {
                                    // Already long-pressed -> any subsequent movement starts
                                    // a range selection, never a pan. This must be checked
                                    // BEFORE the plain "moved > touchSlop" pan check below,
                                    // otherwise a post-long-press drag gets misread as a pan
                                    // (that was the bug: both checks raced, and pan always won).
                                    longPressFired && movedSinceDown > viewConfiguration.touchSlop -> {
                                        mode = GestureMode.SELECT
                                        pressCell?.let { onCellDragStart(it.first, it.second) }
                                    }
                                    // Moved before any long-press fired -> plain pan.
                                    !longPressFired && movedSinceDown > viewConfiguration.touchSlop -> {
                                        mode = GestureMode.PAN
                                    }
                                    // Held still long enough -> fire long-press (context menu),
                                    // and stay in NONE so a following drag can become SELECT.
                                    !longPressFired && elapsed >= viewConfiguration.longPressTimeoutMillis -> {
                                        longPressFired = true
                                        pressCell?.let { onCellLongPress(it.first, it.second) }
                                    }
                                }
                            }

                            when (mode) {
                                GestureMode.PAN -> {
                                    val dx = change.position.x - lastPos.x
                                    val dy = change.position.y - lastPos.y
                                    val (cx, cy) = clampScroll(scrollX + dx, scrollY + dy, scale)
                                    scrollX = cx
                                    scrollY = cy
                                    change.consume()
                                }
                                GestureMode.SELECT -> {
                                    val cell = offsetToCell(
                                        change.position, cornerSize, headerHeight, cellW, cellH, rows, cols, scrollX, scrollY
                                    )
                                    if (cell != null) onCellDrag(cell.first, cell.second)
                                    change.consume()
                                }
                                else -> {}
                            }

                            lastPos = change.position
                            if (change.changedToUp()) break
                        }

                        when {
                            mode == GestureMode.SELECT -> onCellDragEnd()
                            mode == GestureMode.PAN || multiTouch -> {
                                // Pure pan/zoom gesture — nothing else to fire.
                            }
                            !longPressFired -> {
                                // v1.2.0 — Cek apakah tap jatuh di header axis bin.
                                val cellW = baseCellWidth * scale
                                val cellH = baseCellHeight * scale
                                if (downPos.y < headerHeight && downPos.x >= cornerSize) {
                                    val colIdx = ((downPos.x - cornerSize - scrollX) / cellW).toInt()
                                    if (colIdx in xBins.indices) {
                                        onXBinClick(colIdx)
                                    } else {
                                        pressCell?.let { onCellTap(it.first, it.second) }
                                    }
                                } else if (downPos.x < cornerSize && downPos.y >= headerHeight) {
                                    val rowIdx = ((downPos.y - headerHeight - scrollY) / cellH).toInt()
                                    if (rowIdx in yBins.indices) {
                                        onYBinClick(rowIdx)
                                    } else {
                                        pressCell?.let { onCellTap(it.first, it.second) }
                                    }
                                } else {
                                    pressCell?.let { onCellTap(it.first, it.second) }
                                }
                            }
                            else -> {
                                // Long-press fired and finger lifted without dragging ->
                                // context menu already shown via onCellLongPress above.
                            }
                        }
                    }
                }
        ) {
            canvasSize = size
            val cellW = baseCellWidth * scale
            val cellH = baseCellHeight * scale
            drawTableGrid(
                textMeasurer = textMeasurer,
                textLayoutCache = textLayoutCache,
                values = values,
                xBins = xBins,
                yBins = yBins,
                min = min,
                max = max,
                format = format,
                units = units,
                selectedCell = selectedCell,
                selectedCells = selectedCells,
                liveCell = liveCell,
                cornerSize = cornerSize,
                headerHeight = headerHeight,
                cellWidth = cellW,
                cellHeight = cellH,
                scrollX = scrollX,
                scrollY = scrollY,
                gridLineColor = gridLineColor,
                selectionColor = selectionColor,
                liveCursorColor = liveCursorColor,
                axisBgColor = axisBgColor,
                axisTextColor = axisTextColor
            )
        }
    }
}

private enum class GestureMode { NONE, PAN, SELECT }

/**
 * Convert a touch offset to (row, col) cell index. `cellWidth`/`cellHeight` here
 * are already scaled (base size * current zoom).
 */
private fun offsetToCell(
    offset: Offset,
    cornerSize: Float,
    headerHeight: Float,
    cellWidth: Float,
    cellHeight: Float,
    rows: Int,
    cols: Int,
    scrollX: Float,
    scrollY: Float
): Pair<Int, Int>? {
    val x = offset.x - cornerSize - scrollX
    val y = offset.y - headerHeight - scrollY
    if (x < 0 || y < 0) return null
    val col = (x / cellWidth).toInt()
    val row = (y / cellHeight).toInt()
    if (row !in 0 until rows || col !in 0 until cols) return null
    return row to col
}

/**
 * Draw the full table grid on canvas.
 */
private fun DrawScope.drawTableGrid(
    textMeasurer: TextMeasurer,
    textLayoutCache: MutableMap<String, androidx.compose.ui.text.TextLayoutResult>,
    values: List<List<Double>>,
    xBins: List<Double>,
    yBins: List<Double>,
    min: Double,
    max: Double,
    format: String,
    units: String,
    selectedCell: Pair<Int, Int>?,
    selectedCells: Set<Pair<Int, Int>>,
    liveCell: Pair<Int, Int>?,
    cornerSize: Float,
    headerHeight: Float,
    cellWidth: Float,
    cellHeight: Float,
    scrollX: Float,
    scrollY: Float,
    gridLineColor: Color,
    selectionColor: Color,
    liveCursorColor: Color,
    axisBgColor: Color,
    axisTextColor: Color
) {
    val rows = values.size
    val cols = values[0].size

    val clipLeft = cornerSize
    val clipTop = headerHeight

    // Axis backgrounds are clipped to the table's actual content extent
    // (rows*cellHeight / cols*cellWidth), not the full remaining canvas —
    // otherwise, when zoomed out (e.g. after "Fit"), the axis-colored strip
    // keeps going past the last row/column and looks like a floating,
    // disconnected block. Actually drawn further below, AFTER the cells,
    // so the axis always ends up on top regardless of scroll position.
    val contentHeight = (rows * cellHeight + scrollY).coerceAtMost(size.height - headerHeight)
    val contentWidth = (cols * cellWidth + scrollX).coerceAtMost(size.width - cornerSize)

    val cornerText = textMeasurer.measure(
        text = units.ifEmpty { "" },
        style = TextStyle(fontSize = 9.sp, color = axisTextColor)
    )

    val cellTextStyle = TextStyle(fontSize = 10.sp)
    val axisStyle = TextStyle(fontSize = 9.sp, color = axisTextColor)

    // Clip all cell drawing (rects + text) strictly to the area right of the
    // Y-axis strip and below the X-axis strip. Without this, a cell that's
    // scrolled partway into the axis strip still gets drawn at its full
    // position — its color and value text end up overlapping/colliding with
    // the axis label sitting there, making the axis number unreadable. This
    // clip guarantees a cell can never render into the reserved axis area,
    // no matter the scroll offset.
    clipRect(left = cornerSize, top = headerHeight, right = size.width, bottom = size.height) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = cornerSize + c * cellWidth + scrollX
                val y = headerHeight + r * cellHeight + scrollY

                if (x + cellWidth < clipLeft || x > size.width) continue
                if (y + cellHeight < clipTop || y > size.height) continue

                val value = values[r][c]
                val cellColor = TableColorUtils.valueToColor(value, min, max)
                val textColor = TableColorUtils.contrastTextColor(cellColor)

                drawRect(color = cellColor, topLeft = Offset(x, y), size = Size(cellWidth, cellHeight))
                drawRect(
                    color = gridLineColor,
                    topLeft = Offset(x, y),
                    size = Size(cellWidth, cellHeight),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 0.5f)
                )

                val displayText = formatCellValue(value, format)
                val isBlackText = textColor == Color.Black
                val cacheKey = "cell:$displayText:$isBlackText"
                val textLayout = textLayoutCache.getOrPut(cacheKey) {
                    textMeasurer.measure(text = displayText, style = cellTextStyle.copy(color = textColor))
                }
                drawText(
                    textLayout,
                    topLeft = Offset(
                        x + cellWidth / 2 - textLayout.size.width / 2,
                        y + cellHeight / 2 - textLayout.size.height / 2
                    )
                )

                val isSel = selectedCell == (r to c) || (r to c) in selectedCells
                if (isSel) {
                    drawRect(
                        color = selectionColor,
                        topLeft = Offset(x, y),
                        size = Size(cellWidth, cellHeight),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
                    )
                }

                if (liveCell == (r to c)) {
                    drawRect(
                        color = liveCursorColor,
                        topLeft = Offset(x - 1f, y - 1f),
                        size = Size(cellWidth + 2f, cellHeight + 2f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f)
                    )
                }
            }
        }
    }

    // Re-draw the axis backgrounds + corner ON TOP of the (now-clipped) cells,
    // so even the clip edge itself always has a clean, solid axis strip in
    // front — belt-and-braces against any 1px seam at the clip boundary.
    drawRect(
        color = axisBgColor,
        topLeft = Offset(0f, headerHeight),
        size = Size(cornerSize, contentHeight.coerceAtLeast(0f))
    )
    drawRect(
        color = axisBgColor,
        topLeft = Offset(cornerSize, 0f),
        size = Size(contentWidth.coerceAtLeast(0f), headerHeight)
    )
    drawRect(
        color = axisBgColor,
        topLeft = Offset(0f, 0f),
        size = Size(cornerSize, headerHeight)
    )
    drawText(cornerText, topLeft = Offset(4f, headerHeight / 2 - cornerText.size.height / 2))

    for (c in 0 until cols) {
        val x = cornerSize + c * cellWidth + scrollX
        if (x + cellWidth < cornerSize || x > this.size.width) continue
        val binValue = xBins.getOrElse(c) { (c * 500).toDouble() }
        // Axis bin labels are always shown as integers (no decimal point / digits).
        // The cell `format` may be "0.0" for VE %, but RPM/kPa bins should display
        // as "500", "1000", "1500" — not "500.0", "1000.0".
        val label = formatAxisValue(binValue)
        val textLayout = textLayoutCache.getOrPut("xaxis:$label") {
            textMeasurer.measure(text = label, style = axisStyle)
        }
        drawText(
            textLayout,
            topLeft = Offset(x + cellWidth / 2 - textLayout.size.width / 2, headerHeight / 2 - textLayout.size.height / 2)
        )
    }

    for (r in 0 until rows) {
        val y = headerHeight + r * cellHeight + scrollY
        if (y + cellHeight < headerHeight || y > this.size.height) continue
        val binValue = yBins.getOrElse(r) { (20 + r * 10).toDouble() }
        // Same integer-only treatment as the X axis above.
        val label = formatAxisValue(binValue)
        val textLayout = textLayoutCache.getOrPut("yaxis:$label") {
            textMeasurer.measure(text = label, style = axisStyle)
        }
        drawText(
            textLayout,
            topLeft = Offset(cornerSize / 2 - textLayout.size.width / 2, y + cellHeight / 2 - textLayout.size.height / 2)
        )
    }

    drawLine(color = gridLineColor, start = Offset(cornerSize, 0f), end = Offset(cornerSize, size.height), strokeWidth = 1f)
    drawLine(color = gridLineColor, start = Offset(0f, headerHeight), end = Offset(size.width, headerHeight), strokeWidth = 1f)
}
