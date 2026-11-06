package com.n0n5ense.taassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0n5ense.taassistant.core.Judge
import com.n0n5ense.taassistant.core.LogLevel

internal enum class MainTab(val label: String) {
    Results("結果"),
    Summary("サマリー"),
    Log("ログ"),
    ConfigEditor("コンフィグ編集"),
}

@Composable
internal fun MainArea(
    state: UiState,
    viewModel: AppViewModel,
    editorState: ConfigEditorState,
    editorViewModel: ConfigEditorViewModel,
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusKind = state.status::class
    LaunchedEffect(statusKind) {
        when (state.status) {
            is RunStatus.Running, is RunStatus.Failed -> onTabChange(MainTab.Log)
            RunStatus.Finished -> if (state.results.isNotEmpty()) onTabChange(MainTab.Results)
            else -> Unit
        }
    }
    LaunchedEffect(tab) {
        if (tab == MainTab.ConfigEditor) editorViewModel.openForEditing(state.setup.configPath)
    }

    Column(modifier) {
        StatusBar(state, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            MainTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { onTabChange(t) },
                    text = { Text(if (t == MainTab.ConfigEditor && editorState.isDirty) "${t.label} ●" else t.label) },
                )
            }
        }
        // タブを切り替えても、各タブのスクロール位置などを残す
        val tabStates = rememberSaveableStateHolder()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    MainTab.Results -> ResultsPanel(state, viewModel)
                    MainTab.Summary -> SummaryPanel(state, onOpenResult = { id ->
                        viewModel.select(id)
                        onTabChange(MainTab.Results)
                    })
                    MainTab.Log -> LogPanel(state.logs)
                    MainTab.ConfigEditor -> ConfigEditorPanel(
                        state = editorState,
                        viewModel = editorViewModel,
                        gradingConfigPath = state.setup.configPath,
                        onUseForGrading = { path -> viewModel.updateSetup { it.copy(configPath = path) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBar(state: UiState, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val status = state.status) {
            RunStatus.Idle -> Text("設定を入力して「実行」を押してください", style = MaterialTheme.typography.bodyMedium)
            is RunStatus.Running -> {
                Text(
                    status.stage.label + if (status.total > 0) "  ${status.completed} / ${status.total}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.total > 0) {
                    LinearProgressIndicator(
                        progress = { status.completed.toFloat() / status.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            RunStatus.Finished -> {
                val outcome = state.outcome
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "完了 (${outcome?.lastStep?.label})  学生 ${outcome?.students?.size ?: 0} 人 / ソース ${outcome?.compiled?.size ?: 0} 件",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val counts = state.results.groupingBy { it.judge }.eachCount()
                    Judge.entries.filter { it in counts }.forEach { judge ->
                        JudgeBadge(judge)
                        Text("${counts[judge]}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            RunStatus.Cancelled -> Text("中止しました", style = MaterialTheme.typography.bodyMedium)
            is RunStatus.Failed -> Text(
                "エラー: ${status.message}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun LogPanel(logs: List<LogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // 行が増えたときだけ末尾へスクロールする (タブに戻ってきたときは位置を残す)
    var scrolledSize by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(logs.size) {
        if (logs.size == scrolledSize) return@LaunchedEffect
        scrolledSize = logs.size
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }
    if (logs.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("ログはありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    SelectionContainer(modifier.fillMaxSize()) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(12.dp)) {
            itemsIndexed(logs) { _, line ->
                Text(
                    line.message,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = when (line.level) {
                        LogLevel.Info -> MaterialTheme.colorScheme.onSurface
                        LogLevel.Warning -> MaterialTheme.colorScheme.tertiary
                        LogLevel.Error -> MaterialTheme.colorScheme.error
                    },
                )
            }
        }
    }
}
