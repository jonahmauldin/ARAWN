package com.arawn.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign

// Stub screens — each becomes a real feature in later phases.

@Composable fun VaultScreen() = PlaceholderScreen("⊗ SECURE VAULT", "Phase E")

@Composable
private fun PlaceholderScreen(title: String, phase: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "// $title\n// available $phase",
            color = Color(0xFF3A3A3A),
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
        )
    }
}
