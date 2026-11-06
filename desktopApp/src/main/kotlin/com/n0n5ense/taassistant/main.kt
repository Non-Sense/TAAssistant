package com.n0n5ense.taassistant

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "TA Assistant",
        state = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            width = 1200.dp,
            height = 780.dp,
        ),
    ) {
        App()
    }
}
