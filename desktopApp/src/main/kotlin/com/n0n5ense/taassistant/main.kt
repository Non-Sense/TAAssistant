package com.n0n5ense.taassistant

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.n0n5ense.taassistant.platform.attachWindow
import com.n0n5ense.taassistant.platform.createPlatformServices

fun main() = application {
    val services = remember { createPlatformServices() }
    var title by remember { mutableStateOf("TA Assistant") }
    // 閉じるときは、プロジェクトを保存し終えてから終了する
    var exitRequested by remember { mutableStateOf(false) }
    Window(
        onCloseRequest = { exitRequested = true },
        title = title,
        state = rememberWindowState(
            position = WindowPosition(Alignment.Center),
            width = 1200.dp,
            height = 780.dp,
        ),
    ) {
        // ファイル選択などの OS 標準ダイアログを、このウィンドウに対するモーダルにする
        LaunchedEffect(window) { services.attachWindow(window) }
        App(
            services = services,
            onTitleChange = { title = it },
            exitRequested = exitRequested,
            onReadyToExit = ::exitApplication,
        )
    }
}
