package de.loraki.app

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Hält den Prozess während der Funkverbindung am Leben (Foreground Service). */
class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "link")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("LoRa-KI verbunden")
            .setContentText("Verbindung zum MeshCore-Node aktiv")
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        try {
            if (android.os.Build.VERSION.SDK_INT >= 29)
                startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(2, n)
        } catch (e: Exception) {
            // Darf die Verbindung nicht verhindern: ohne Vordergrunddienst läuft sie nur, solange die App offen ist.
            de.loraki.app.ble.BleLog.log("Vordergrunddienst nicht startbar: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
}
