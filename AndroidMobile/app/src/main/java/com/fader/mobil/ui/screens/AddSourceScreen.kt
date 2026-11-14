package com.fader.mobil.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fader.mobil.ui.components.ScreenTitle
import com.fader.mobil.ui.theme.Accent
import com.fader.mobil.ui.theme.AccentInk
import com.fader.mobil.ui.theme.Separator
import com.fader.mobil.ui.theme.TextPrimary
import com.fader.mobil.ui.theme.TextSecondary

@Composable
fun AddSourceScreen(
    connecting: Boolean,
    error: String?,
    initialUrl: String = "",
    initialUser: String = "",
    onCancel: () -> Unit,
    onConnect: (url: String, user: String, password: String) -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var user by rememberSaveable { mutableStateOf(initialUser) }
    var password by rememberSaveable { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel", color = Accent, fontSize = 16.sp)
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            ScreenTitle("Add WebDAV")
            Spacer(Modifier.height(6.dp))
            Text(
                "HTTPS address of the share, for example https://nas.example.com/music/",
                color = TextSecondary,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(18.dp))

            Field("Server URL", url, { url = it }, KeyboardType.Uri)
            Spacer(Modifier.height(12.dp))
            Field("Username", user, { user = it }, KeyboardType.Text)
            Spacer(Modifier.height(12.dp))
            Field("Password", password, { password = it }, KeyboardType.Password, secret = true)

            if (error != null) {
                Spacer(Modifier.height(14.dp))
                Text(error, color = Color(0xFFFF6B6B), fontSize = 14.sp)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { onConnect(url, user, password) },
                enabled = url.isNotBlank() && !connecting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = AccentInk,
                    disabledContainerColor = Accent.copy(alpha = 0.4f),
                    disabledContentColor = AccentInk.copy(alpha = 0.6f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
            ) {
                if (connecting) {
                    CircularProgressIndicator(Modifier.size(20.dp), color = AccentInk, strokeWidth = 2.dp)
                    Spacer(Modifier.size(10.dp))
                }
                Text(
                    if (connecting) "Connecting…" else "Connect",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(
                "Works from any network over HTTPS — home Wi-Fi or mobile data.",
                color = TextSecondary,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Separator,
            focusedLabelColor = Accent,
            unfocusedLabelColor = TextSecondary,
            cursorColor = Accent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}
