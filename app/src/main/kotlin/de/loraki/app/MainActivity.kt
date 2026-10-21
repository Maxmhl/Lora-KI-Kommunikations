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
import de.loraki.app.ui.ChatScreen
import de.loraki.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

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
                    if (screen == "chat") ChatScreen(vm, onSettings = { screen = "settings" })
                    else SettingsScreen(vm, onBack = { screen = "chat" })
                }
            }
        }
    }
}
