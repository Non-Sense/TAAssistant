package com.n0n5ense.taassistant

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n0n5ense.taassistant.platform.PlatformServices
import com.n0n5ense.taassistant.platform.createPlatformServices
import com.n0n5ense.taassistant.ui.AppViewModel
import com.n0n5ense.taassistant.ui.ConfigEditorViewModel
import com.n0n5ense.taassistant.ui.MainTab
import com.n0n5ense.taassistant.ui.MainArea
import com.n0n5ense.taassistant.ui.SetupPanel
import com.n0n5ense.taassistant.ui.theme.AppTheme

@Composable
fun App(services: PlatformServices = remember { createPlatformServices() }) {
    val appViewModel = viewModel { AppViewModel(services) }
    val state by appViewModel.state.collectAsStateWithLifecycle()
    val editorViewModel = viewModel { ConfigEditorViewModel(services) }
    val editorState by editorViewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MainTab.Results) }

    // TODO: 一時的にライトモードを強制している。戻すときは darkTheme の指定を消す (既定はシステム設定に従う)
    AppTheme(darkTheme = false) {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                // コンフィグ編集中は横幅を広く使う
                if (tab != MainTab.ConfigEditor) {
                    SetupPanel(
                        state = state,
                        viewModel = appViewModel,
                        onEditConfig = { tab = MainTab.ConfigEditor },
                        modifier = Modifier.width(380.dp).fillMaxHeight(),
                    )
                    VerticalDivider()
                }
                MainArea(
                    state = state,
                    viewModel = appViewModel,
                    editorState = editorState,
                    editorViewModel = editorViewModel,
                    tab = tab,
                    onTabChange = { tab = it },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}
