package com.fader.mobil.ui

import androidx.compose.runtime.Composable
import com.fader.mobil.ui.theme.FaderTheme

/** The palette is fixed (high contrast by default); text follows the system size. */
@Composable
fun AppRoot() {
    FaderTheme {
        FaderApp()
    }
}
