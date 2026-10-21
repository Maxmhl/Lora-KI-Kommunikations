package de.loraki.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import de.loraki.app.nomad.NomadViewModel
import de.loraki.app.ui.ChatScreen
import de.loraki.app.ui.NomadScreen
import de.loraki.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val nomadVm: NomadViewModel by viewModels()
    private val nomadChatVm: de.loraki.app.nomad.NomadChatViewModel by viewModels()
    private val lookupVm: de.loraki.app.nomad.NomadLookupViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            val ctx = LocalContext.current
            val scheme = if (android.os.Build.VERSION.SDK_INT >= 31)
                (if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx))
            else if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()
            MaterialTheme(colorScheme = scheme) {
                Surface {
                    var screen by rememberSaveable { mutableStateOf("chat") }
                    if (screen == "settings") SettingsScreen(vm, onBack = { screen = "chat" })
                    else Scaffold(bottomBar = {
                        NavigationBar {
                            NavigationBarItem(screen == "chat", { screen = "chat" }, { Icon(Icons.AutoMirrored.Filled.Chat, null) }, label = { Text("KI-Chat") })
                            NavigationBarItem(screen == "nomad", { screen = "nomad" }, { Icon(Icons.Default.Public, null) }, label = { Text("Nomad") })
                        }
                    }) { inner ->
                        Box(Modifier.padding(inner).consumeWindowInsets(inner)) {
                            if (screen == "chat") ChatScreen(vm, onSettings = { screen = "settings" })
                            else NomadScreen(nomadVm, nomadChatVm, lookupVm, onStartLoraKb = { vm.newKnowledgeBaseChat(); screen = "chat" })
                        }
                    }
                }
            }
        }
    }
}
