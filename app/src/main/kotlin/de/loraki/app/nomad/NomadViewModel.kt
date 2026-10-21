package de.loraki.app.nomad

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.loraki.app.App
import de.loraki.core.InstalledItem
import de.loraki.core.Nomad
import de.loraki.core.RemoteItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

class NomadViewModel(app: Application) : AndroidViewModel(app) {
    private val g = (app as App).graph
    val settings = g.settings
    val repo = g.nomad
    val content = LocalContent(repo, app.assets)

    val manifest = repo.manifest
    val installed = repo.installed
    val wanted = repo.wanted
    val progress = repo.progress
    val checking = repo.checking
    val message = repo.message

    /** Läuft/wartet gerade ein Download-Auftrag? (wartet z. B. auf WLAN) */
    val downloadState: StateFlow<WorkInfo.State?> = WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(NomadWork.DOWNLOAD)
        .map { list -> list.firstOrNull { !it.state.isFinished }?.state }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conn = MutableStateFlow<String?>(null)

    fun saveServer(url: String, token: String) {
        settings.nomadUrl = url
        settings.nomadToken = token
        NomadWork.scheduleSync(getApplication(), settings)
    }

    fun test() = viewModelScope.launch {
        conn.value = "Verbinde …"
        conn.value = try {
            val m = repo.refresh()
            "Verbunden: ${m.items.size} Inhalte" + if (!m.nomadAvailable) " (NOMAD-Oberfläche nicht konfiguriert)" else ""
        } catch (e: IOException) { e.message ?: "Fehler" }
    }

    fun check() = viewModelScope.launch { try { repo.refresh(); message.value = null } catch (e: IOException) { message.value = e.message } }

    fun setInterval(days: Int) { settings.nomadIntervalDays = days; NomadWork.scheduleSync(getApplication(), settings) }
    fun setWifiOnly(v: Boolean) { settings.nomadWifiOnly = v; NomadWork.scheduleSync(getApplication(), settings) }
    fun setAuto(v: Boolean) { settings.nomadAutoDownload = v }

    fun download(vararg items: RemoteItem) {
        val series = items.map { it.series }
        series.forEach { repo.setWanted(it, true) }
        NomadWork.download(getApplication(), settings, series)
    }

    fun updateAll() {
        val p = repo.plan()
        if (p.downloads.isNotEmpty()) NomadWork.download(getApplication(), settings, p.downloads.map { it.series })
    }

    fun cancel() = NomadWork.cancelDownloads(getApplication())
    fun remove(i: InstalledItem) = repo.remove(i.series)

    override fun onCleared() { content.close() }
}
