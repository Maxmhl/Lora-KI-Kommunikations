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
import android.content.Context
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
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

data class FoundDevice(val name: String, val address: String, val rssi: Int)

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

    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: run { close(IOException("Bluetooth ist aus")); return@callbackFlow }
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val name = r.scanRecord?.deviceName ?: r.device.name ?: return
                trySend(FoundDevice(name, r.device.address, r.rssi))
            }
            override fun onScanFailed(errorCode: Int) { close(IOException("Scan fehlgeschlagen ($errorCode)")) }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(nusService)).build()
        scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                if (!g.requestMtu(247)) g.discoverServices()
            } else {
                _connected.value = false
                ready?.completeExceptionally(IOException("Verbindung getrennt (Status $status)"))
                writeDone?.complete(-1)
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(nusService)
            val tx = svc?.getCharacteristic(txUuid)
            rx = svc?.getCharacteristic(rxUuid)
            if (tx == null || rx == null) {
                ready?.completeExceptionally(IOException("Kein MeshCore-UART-Dienst gefunden"))
                return
            }
            g.setCharacteristicNotification(tx, true)
            val d = tx.getDescriptor(cccd)
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            else { @Suppress("DEPRECATION") d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) { _connected.value = true; ready?.complete(Unit) }
            else ready?.completeExceptionally(IOException("Benachrichtigungen nicht aktivierbar ($status) – Kopplung/PIN prüfen"))
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

    suspend fun connect(address: String) {
        close()
        val dev: BluetoothDevice = adapter?.getRemoteDevice(address) ?: throw IOException("Bluetooth nicht verfügbar")
        val r = CompletableDeferred<Unit>()
        ready = r
        gatt = dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            withTimeout(45_000) { r.await() } // inkl. Zeit für den Kopplungsdialog
        } catch (e: Exception) {
            close()
            throw if (e is IOException) e else IOException("Verbindung fehlgeschlagen: ${e.message}")
        }
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
