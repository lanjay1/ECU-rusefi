package com.ztune.libretune.ui.screens.ztune_features

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * v1.3.0 — Cluster 9 indikator gaya UMA M9 Racing (real-time, NO hardcode).
 *
 * Semua nilai diambil langsung dari ECU realtime via InstrumentClusterViewModel.
 * Kalau belum konek / channel belum pernah update, kartu menampilkan "--"
 * (bukan angka default yang terlihat seperti data asli).
 *
 * 9 indikator: RPM, AFR, AFR TARGET, TPS, CLT, IAT, SA (spark advance),
 * VBATT, KNOCK. (BOOST dan GPS SPEED dihapus — motor NA tidak punya sensor
 * MAP dan firmware tidak mengirim channel kecepatan; ENGINE TEMP dihapus
 * karena dulu cuma duplikat CLT.)
 *
 * Penggunaan:
 *   InstrumentClusterGrid()  // otomatis subscribe ke ECU realtime
 *
 * Letakkan di HomeScreen atau DashboardScreen.
 */
@Composable
fun InstrumentClusterGrid(
    viewModel: InstrumentClusterViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val indicators = listOf(
        IndicatorData(
            "RPM", fmt(state.rpm), "rpm",
            warning = (state.rpm ?: 0) > 9000, yellowAccent = true
        ),
        IndicatorData(
            "AFR", fmt(state.afr, "%.1f"), "AFR",
            warning = state.afr?.let { it > 15.5f || it < 12.5f } ?: false
        ),
        IndicatorData(
            "AFR TARGET", fmt(state.afrTarget, "%.1f"), "AFR",
            warning = false
        ),
        IndicatorData(
            "TPS", fmt(state.tps, "%.0f"), "%",
            warning = false
        ),
        IndicatorData(
            "CLT", fmt(state.clt, "%.0f"), "°C",
            warning = (state.clt ?: 0f) > 105f
        ),
        IndicatorData(
            "IAT", fmt(state.iat, "%.0f"), "°C",
            warning = (state.iat ?: 0f) > 60f
        ),
        IndicatorData(
            "SA", fmt(state.sparkAdvance, "%.1f"), "°",
            warning = (state.sparkAdvance ?: 0f) < -5f
        ),
        IndicatorData(
            "VBATT", fmt(state.vbatt, "%.1f"), "V",
            warning = state.vbatt?.let { it < 10f || it > 16f } ?: false
        ),
        IndicatorData(
            "KNOCK", fmt(state.knockDb, "%.0f"), "dB",
            warning = (state.knockDb ?: -100f) > -20f, yellowAccent = true
        )
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IndicatorCard(indicators[0], Modifier.weight(1f))
            IndicatorCard(indicators[1], Modifier.weight(1f))
            IndicatorCard(indicators[2], Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IndicatorCard(indicators[3], Modifier.weight(1f))
            IndicatorCard(indicators[4], Modifier.weight(1f))
            IndicatorCard(indicators[5], Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IndicatorCard(indicators[6], Modifier.weight(1f))
            IndicatorCard(indicators[7], Modifier.weight(1f))
            IndicatorCard(indicators[8], Modifier.weight(1f))
        }
    }
}

/** "--" kalau null, supaya jelas beda antara "belum ada data" dan "nilainya 0". */
private fun fmt(value: Float?, pattern: String): String =
    value?.let { pattern.format(it) } ?: "--"

private fun fmt(value: Int?): String =
    value?.toString() ?: "--"

internal data class IndicatorData(
    val label: String,
    val value: String,
    val unit: String,
    val warning: Boolean = false,
    val yellowAccent: Boolean = false
)

@Composable
private fun IndicatorCard(
    indicator: IndicatorData,
    modifier: Modifier = Modifier
) {
    val borderColor = when {
        indicator.warning -> MaterialTheme.colorScheme.error
        indicator.yellowAccent -> Color(0xFFFFC107)
        else -> MaterialTheme.colorScheme.outline
    }
    val textColor = when {
        indicator.warning -> MaterialTheme.colorScheme.error
        indicator.yellowAccent -> Color(0xFFFFC107)
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = modifier.height(80.dp),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(2.dp, borderColor),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = indicator.label,
                style = MaterialTheme.typography.labelSmall,
                color = borderColor,
                fontSize = 11.sp,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = indicator.value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = textColor,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
            Text(
                text = indicator.unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 10.sp
            )
        }
    }
}