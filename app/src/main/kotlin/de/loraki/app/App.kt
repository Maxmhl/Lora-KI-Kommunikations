package de.loraki.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import de.loraki.app.ble.LinkState
import de.loraki.app.ble.MeshSession
import de.loraki.app.data.Settings
import de.loraki.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class Graph(val ctx: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = Settings(ctx)
    val store = Store(ctx)
    val session = MeshSession(ctx, scope)
    val engine = Engine(ctx, scope, store, settings, session)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** Android 12+: BLUETOOTH_SCAN + BLUETOOTH_CONNECT; davor: Standort (für BLE-Suche nötig). */
    fun hasBlePermission() =
        if (android.os.Build.VERSION.SDK_INT >= 31) granted(Manifest.permission.BLUETOOTH_CONNECT) && granted(Manifest.permission.BLUETOOTH_SCAN)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    fun connect() {
        val addr = settings.deviceAddress ?: return
        settings.wantConnected = true
        if (hasBlePermission()) runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, LinkService::class.java)) }
        scope.launch { runCatching { session.connect(addr) } }
    }

    fun disconnect() {
        settings.wantConnected = false
        session.disconnect()
        ctx.stopService(Intent(ctx, LinkService::class.java))
    }

    init {
        // automatisch wieder verbinden, solange der Nutzer verbunden sein möchte
        scope.launch {
            while (true) {
                delay(10_000)
                if (settings.wantConnected && session.state.value == LinkState.DISCONNECTED && hasBlePermission()) {
                    settings.deviceAddress?.let { a -> runCatching { session.connect(a) } }
                }
            }
        }
    }
}

class App : Application() {
    lateinit var graph: Graph
    override fun onCreate() {
        super.onCreate()
        graph = Graph(this)
        if (graph.settings.wantConnected) graph.connect()
    }
}
