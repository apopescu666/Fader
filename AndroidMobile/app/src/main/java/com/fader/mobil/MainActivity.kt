package com.fader.mobil

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.fader.mobil.core.FaderPlayer
import com.fader.mobil.core.RemoteLibrary
import com.fader.mobil.data.SettingsStore
import com.fader.mobil.data.UiSettings
import com.fader.mobil.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FaderPlayer.init(this)
        FaderPlayer.setCrossfade(SettingsStore.fade(this))
        UiSettings.load(this)
        FaderPlayer.setGapless(UiSettings.gapless)
        RemoteLibrary.init(this)
        setContent {
            AppRoot()
        }
    }
}
