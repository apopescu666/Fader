package com.fader.mobil

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.core.FaderPlayer
import com.fader.mobil.core.RemoteLibrary
import com.fader.mobil.data.SettingsStore
import com.fader.mobil.data.UiSettings
import com.fader.mobil.ui.AppRoot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        enableEdgeToEdge()
        FaderPlayer.init(this)
        FaderPlayer.applyCrossfade(SettingsStore.fade(this))
        FaderPlayer.setShuffle(SettingsStore.shuffle(this))
        UiSettings.load(this)
        FaderPlayer.setGapless(UiSettings.gapless)
        RemoteLibrary.init(this)
        setContent {
            AppRoot()
            CrashReportDialog()
        }
    }

    /**
     * Records the last uncaught exception, so a random crash can be reported
     * without adb: the next launch shows the stack trace.
     */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(filesDir, "last-crash.txt").writeText(
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
                        "\nThread: " + thread.name + "\n\n" +
                        Log.getStackTraceString(error),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}

@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val file = File(context.filesDir, "last-crash.txt")
        if (file.isFile) report = runCatching { file.readText() }.getOrNull()
    }
    val text = report
    if (text != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Last crash") },
            text = {
                Box(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching { File(context.filesDir, "last-crash.txt").delete() }
                        report = null
                    },
                ) { Text("Close") }
            },
        )
    }
}
