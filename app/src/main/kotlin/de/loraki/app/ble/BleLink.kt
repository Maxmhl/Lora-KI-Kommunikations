package de.loraki.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.ParcelUuid
import de.loraki.core.Companion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

/** [likely]: bietet den Nordic-UART-Dienst an oder heißt wie ein MeshCore-Gerät. */
data class FoundDevice(val name: String, val address: String, val rssi: Int, val likely: Boolean)

object GattText {
    fun status(status: Int): String = when (status) {
        0 -> "OK"
        5, 15 -> "Authentifizierung fehlgeschlagen ($status): Kopplung in den Android-Bluetooth-Einstellungen entfernen und neu koppeln (PIN 123456)"
        8 -> "Zeitüberschreitung (8): Node außer Reichweite oder ausgeschaltet"
        19 -> "Node hat die Verbindung beendet (19): evtl. ist bereits ein anderes Gerät/die MeshCore-App verbunden"
        22 -> "Verbindung vom Handy beendet (22)"
        62 -> "Verbindungsaufbau fehlgeschlagen (62)"
        133 -> "Allgemeiner Bluetooth-Fehler (133): Node nicht erreichbar, bereits mit einem anderen Gerät verbunden oder veraltete Kopplung"
        else -> "Bluetooth-Fehler ($status)"
    }
}

/** BLE-Verbindung zum MeshCore-Companion über den Nordic UART Service. */
@SuppressLint("MissingPermission")
class BleLink(private val ctx: Context) {
    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val nusService = UUID.fromString(Companion.NUS_SERVICE)
    private val rxUuid = UUID.fromString(Companion.NUS_RX)
    private val txUuid = UUID.fromString(Companion.NUS_TX)
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Eingehende Frames (ein Frame je Notification). */
    val frames = Channel<ByteArray>(Channel.UNLIMITED)
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var ready: CompletableDeferred<Unit>? = null
    private var writeDone: CompletableDeferred<Int>? = null
    private val writeMutex = Mutex()

    /** Sucht alle BLE-Geräte (ohne Filter – manche Firmware sendet die UUID nur in der Scan-Antwort). */
    fun scan(): Flow<FoundDevice> = callbackFlow {
        val a = adapter
        if (a == null || !a.isEnabled) { close(IOException("Bluetooth ist ausgeschaltet – bitte einschalten")); return@callbackFlow }
        val scanner = a.bluetoothLeScanner ?: run { close(IOException("BLE-Scanner nicht verfügbar")); return@callbackFlow }
        var count = 0
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name
                val hasNus = r.scanRecord?.serviceUuids?.any { it.uuid == nusService } == true
                val byName = name?.contains("meshcore", true) == true || name?.contains("wismesh", true) == true
                if (name == null && !hasNus) return
                if (count++ == 0) BleLog.log("Scan: erstes Gerät gefunden")
                trySend(FoundDevice(name ?: "(ohne Namen)", r.device.address, r.rssi, hasNus || byName))
            }
            override fun onScanFailed(errorCode: Int) {
                BleLog.log("Scan fehlgeschlagen, Code $errorCode")
                close(IOException("Scan fehlgeschlagen (Code $errorCode)" + if (errorCode == 1) " – läuft schon ein Scan? App neu starten" else ""))
            }
        }
        BleLog.log("Scan gestartet")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose { BleLog.log("Scan beendet ($count Ergebnisse)"); runCatching { scanner.stopScan(cb) } }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            BleLog.log("GATT-Zustand: neu=$newState status=$status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                BleLog.log("Verbunden, Bindungsstatus=${g.device.bondState}; verhandle MTU")
                if (!g.requestMtu(247)) g.discoverServices()
            } else {
                _connected.value = false
                ready?.completeExceptionally(IOException(GattText.status(status)))
                writeDone?.complete(-1)
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            BleLog.log("MTU $mtu (Status $status); suche Dienste")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            BleLog.log("Dienste entdeckt (Status $status): " + g.services.joinToString { it.uuid.toString().take(8) })
            val svc = g.getService(nusService)
            val tx = svc?.getCharacteristic(txUuid)
            rx = svc?.getCharacteristic(rxUuid)
            if (tx == null || rx == null) {
                ready?.completeExceptionally(IOException("Kein MeshCore-UART-Dienst (6E400001…) auf diesem Gerät – falsches Gerät oder keine Companion-Firmware?"))
                return
            }
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(cccd)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            BleLog.log("Benachrichtigungen aktivieren: Status $status")
            if (status == BluetoothGatt.GATT_SUCCESS) { _connected.value = true; ready?.complete(Unit) }
            else ready?.completeExceptionally(IOException("Benachrichtigungen nicht aktivierbar – " + GattText.status(status)))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { writeDone?.complete(status) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            frames.trySend(value.copyOf())
        }

        @Deprecated("Für API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) @Suppress("DEPRECATION") frames.trySend(c.value.copyOf())
        }
    }

    /** Koppelt (Bonding) mit dem Node, falls noch nicht geschehen – Android zeigt die PIN-Abfrage (MeshCore: 123456). */
    private suspend fun ensureBonded(dev: BluetoothDevice) {
        if (dev.bondState == BluetoothDevice.BOND_BONDED) { BleLog.log("Bereits gekoppelt"); return }
        val result = CompletableDeferred<Boolean>()
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val d = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (d?.address != dev.address) return
                val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                val prev = i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1)
                BleLog.log("Kopplung: $prev -> $st")
                if (st == BluetoothDevice.BOND_BONDED) result.complete(true)
                else if (st == BluetoothDevice.BOND_NONE && prev == BluetoothDevice.BOND_BONDING) result.complete(false)
            }
        }
        ContextCompat.registerReceiver(ctx, rcv, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        try {
            if (dev.bondState == BluetoothDevice.BOND_BONDED) return
            if (dev.bondState == BluetoothDevice.BOND_NONE) {
                BleLog.log("Starte Kopplung – bitte PIN eingeben (MeshCore-Standard: 123456)")
                // LE-Transport erzwingen (bei zuvor unbekanntem Gerätetyp versucht Android sonst Classic); createBond(int) ist nicht öffentlich
                val started = runCatching {
                    BluetoothDevice::class.java.getMethod("createBond", Int::class.javaPrimitiveType).invoke(dev, BluetoothDevice.TRANSPORT_LE) as Boolean
                }.getOrElse { dev.createBond() }
                if (!started) throw IOException("Kopplung konnte nicht gestartet werden")
            }
            val ok = try { withTimeout(90_000) { result.await() } } catch (e: Exception) { false }
            if (!ok) throw IOException("Kopplung fehlgeschlagen oder abgebrochen – PIN korrekt (Standard 123456)? Sonst Node in den Android-Bluetooth-Einstellungen entfernen und erneut versuchen")
        } finally { runCatching { ctx.unregisterReceiver(rcv) } }
    }

    suspend fun connect(address: String) {
        close()
        while (frames.tryReceive().isSuccess) { /* alte Frames verwerfen */ }
        val dev: BluetoothDevice = adapter?.takeIf { it.isEnabled }?.getRemoteDevice(address)
            ?: throw IOException("Bluetooth ist ausgeschaltet oder nicht verfügbar")
        BleLog.log("Verbinde mit $address (${dev.name ?: "?"})")
        ensureBonded(dev)
        var lastError: IOException? = null
        for (attempt in 1..2) {
            val r = CompletableDeferred<Unit>()
            ready = r
            gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            try {
                withTimeout(45_000) { r.await() }
                BleLog.log("BLE-Verbindung steht")
                return
            } catch (e: Exception) {
                lastError = if (e is IOException) e else IOException("Verbindung fehlgeschlagen: ${e.message ?: "Zeitüberschreitung"}")
                BleLog.log("Versuch $attempt fehlgeschlagen: ${lastError.message}")
                close()
                if (attempt == 1) delay(1500)
            }
        }
        throw lastError ?: IOException("Verbindung fehlgeschlagen")
    }

    suspend fun write(data: ByteArray) = writeMutex.withLock {
        val g = gatt ?: throw IOException("Nicht verbunden")
        val c = rx ?: throw IOException("Nicht verbunden")
        val done = CompletableDeferred<Int>()
        writeDone = done
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION") c.value = data
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") g.writeCharacteristic(c)
        }
        if (!started) throw IOException("Schreiben abgelehnt")
        val status = try { withTimeout(10_000) { done.await() } } catch (e: Exception) { throw IOException("Schreib-Timeout") }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Schreibfehler ($status)")
    }

    fun close() {
        _connected.value = false
        gatt?.let { runCatching { it.disconnect() }; runCatching { it.close() } }
        gatt = null
        rx = null
    }
}
