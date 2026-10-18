package com.ztune.libretune.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.ztune.libretune.MainActivity
import com.ztune.libretune.R

/**
 * Foreground service that keeps the ECU connection alive when the app is
 * backgrounded. Displays a sticky notification with connection status.
 *
 * Phase 2: This prevents Android from killing the app process during
 * datalogging or realtime streaming while driving.
 *
 * v1.3.6 (audit finding #8) — SEBELUMNYA WakeLock diambil sekali dengan
 * timeout 10 menit dan tidak pernah diperbarui: sesi logging/tuning yang
 * berjalan lebih dari 10 menit berisiko CPU tidur di tengah baca serial,
 * bikin datalog bolong atau koneksi diam-diam macet tanpa error yang jelas.
 * SEKARANG WakeLock diperpanjang otomatis setiap [WAKELOCK_RENEW_INTERVAL_MS]
 * selama service ini hidup, sampai [onDestroy] memanggil [releaseWakeLock].
 */
class EcuCommunicationService : Service() {

    companion object {
        private const val CHANNEL_ID = "ecu_communication"
        private const val NOTIFICATION_ID = 1

        /** WakeLock timeout per acquire() — tetap 10 menit sebagai batas aman kalau renewal loop gagal jalan. */
        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
        /** Perpanjang WakeLock jauh sebelum timeout-nya habis (di 6 menit dari 10 menit). */
        private const val WAKELOCK_RENEW_INTERVAL_MS = 6 * 60 * 1000L

        fun start(context: Context) {
            val intent = Intent(context, EcuCommunicationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EcuCommunicationService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val renewHandler = Handler(Looper.getMainLooper())
    private val renewRunnable = object : Runnable {
        override fun run() {
            renewWakeLock()
            renewHandler.postDelayed(this, WAKELOCK_RENEW_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        renewHandler.postDelayed(renewRunnable, WAKELOCK_RENEW_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("ECU streaming active"))
        return START_STICKY
    }

    override fun onDestroy() {
        renewHandler.removeCallbacks(renewRunnable)
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ECU Communication",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps ECU connection alive in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ZTune")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ztune:ecu_streaming")
        wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
    }

    /**
     * Re-acquire the same WakeLock instance to push its timeout forward
     * another [WAKELOCK_TIMEOUT_MS], called every [WAKELOCK_RENEW_INTERVAL_MS]
     * (well before the previous acquire's timeout would expire) as long as
     * this service is alive. If the lock was somehow released or lost,
     * acquire a fresh one instead of silently doing nothing.
     */
    private fun renewWakeLock() {
        val lock = wakeLock
        if (lock != null && lock.isHeld) {
            lock.acquire(WAKELOCK_TIMEOUT_MS)
        } else {
            acquireWakeLock()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}