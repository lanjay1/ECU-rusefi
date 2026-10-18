package com.ztune.libretune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ztune.libretune.R
import com.ztune.libretune.ui.screens.ztune_features.InstrumentClusterGrid

/**
 * Home / landing screen — sekarang cuma berisi cluster 9 indikator
 * gaya UMA M9 Racing yang real-time dari ECU.
 *
 * v1.3.0:
 *  - Hapus card "Status ECU" + tombol "Hubungkan ECU" (koneksi via top bar WiFi icon).
 *  - Hapus tombol "Buka Editor Tune" (akses lewat bottom nav tab "Tune").
 *  - Cluster indikator ambil nilai langsung dari InstrumentClusterViewModel
 *    yang subscribe ke EcuConnectionManager.realtimeChannelStore.
 *    Bila ECU belum connected atau channel belum pernah update, kartu
 *    menampilkan "--" — tidak ada lagi nilai default (14.7 AFR/20°C/dst)
 *    yang terlihat seperti data asli padahal bukan.
 *  - 9 indikator: RPM, AFR, AFR TARGET, TPS, CLT, IAT, SA, VBATT, KNOCK.
 *    BOOST dan GPS SPEED dihapus (tidak relevan untuk motor NA 1-silinder,
 *    dan channel-nya memang tidak ada di firmware).
 */
@Composable
fun HomeScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Top),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(8.dp))

        // v1.2.1 — Cluster 9 indikator real-time (gaya UMA M9 Racing).
        // Tidak ada parameter — ViewModel otomatis subscribe ke ECU.
        InstrumentClusterGrid(
            modifier = Modifier.fillMaxWidth()
        )
    }
}