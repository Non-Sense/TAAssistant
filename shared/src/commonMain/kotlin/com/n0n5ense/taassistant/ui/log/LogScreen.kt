package com.n0n5ense.taassistant.ui.log

import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0n5ense.taassistant.core.grading.LogLevel
import com.n0n5ense.taassistant.ui.common.AppHorizontalScrollbar
import com.n0n5ense.taassistant.ui.common.AppVerticalScrollbar
import com.n0n5ense.taassistant.ui.common.TooltipIconButton
import com.n0n5ense.taassistant.ui.main.LogLine
import com.n0n5ense.taassistant.ui.main.RunStatus
import com.n0n5ense.taassistant.ui.main.UiState

/** ログの画面。採点画面の「ログ」から開く */
@Composable
internal fun LogScreen(state: UiState, onBack: () -> Unit) {
    // Esc で戻れるよう、画面にフォーカスを置く
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TooltipIconButton("採点画面に戻る (Esc)", Icons.AutoMirrored.Filled.ArrowBack, onBack)
            Text("ログ", style = MaterialTheme.typography.titleLarge)
            LogCounts(state.logs)
        }
        // 実行中は進み具合も出す
        (state.status as? RunStatus.Running)?.let { status ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    status.stage.label + if (status.total > 0) "  ${status.completed} / ${status.total}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (status.total > 0) {
                    LinearProgressIndicator(
                        progress = { status.completed.toFloat() / status.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        LogList(state.logs, Modifier.weight(1f).fillMaxWidth())
    }
}

/** 行数と、警告・エラーの件数 */
@Composable
internal fun LogCounts(logs: List<LogLine>) {
    val warnings = logs.count { it.level == LogLevel.Warning }
    val errors = logs.count { it.level == LogLevel.Error }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${logs.size} 行",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (warnings > 0) Text(
            "警告 $warnings",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.tertiary
        )
        if (errors > 0) Text(
            "エラー $errors",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun LogList(logs: List<LogLine>, modifier: Modifier = Modifier) {
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
    // 長い行は折り返さず、横にスクロールする
    val horizontal = rememberScrollState()
    Column(modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportWidth = maxWidth
            SelectionContainer(Modifier.fillMaxSize().horizontalScroll(horizontal)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight().widthIn(min = viewportWidth),
                    contentPadding = PaddingValues(12.dp),
                ) {
                    itemsIndexed(logs) { _, line ->
                        Text(
                            line.message,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            softWrap = false,
                            color = when (line.level) {
                                LogLevel.Info -> MaterialTheme.colorScheme.onSurface
                                LogLevel.Warning -> MaterialTheme.colorScheme.tertiary
                                LogLevel.Error -> MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
            AppVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        AppHorizontalScrollbar(horizontal, Modifier.fillMaxWidth())
    }
}
