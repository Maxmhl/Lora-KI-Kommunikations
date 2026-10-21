package de.loraki.app.ble

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Verbindungsprotokoll, das in den Einstellungen angezeigt und kopiert werden kann. */
object BleLog {
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.GERMANY)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun log(msg: String) {
        Log.d("LoraKi", msg)
        _lines.value = (_lines.value + "${fmt.format(Date())}  $msg").takeLast(300)
    }

    fun clear() { _lines.value = emptyList() }
}
