package com.n0n5ense.taassistant.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.ui.common.JudgeBadge
import com.n0n5ense.taassistant.ui.results.ResultsPanel
import com.n0n5ense.taassistant.ui.results.SummaryPanel

internal enum class MainTab(val label: String) {
    /** 最初に表示する画面 */
    Summary("サマリー"),
    Details("詳細"),
}

@Composable
internal fun MainArea(
    state: UiState,
    viewModel: AppViewModel,
    tab: MainTab,
    onTabChange: (MainTab) -> Unit,
    modifier: Modifier = Modifier,
    onOpenStudent: (StudentId) -> Unit = {},
    onOpenLog: () -> Unit = {},
) {
    Column(modifier) {
        StatusBar(
            state,
            onOpenLog,
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
        )
        PrimaryTabRow(selectedTabIndex = tab.ordinal) {
            MainTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { onTabChange(t) },
                    text = { Text(t.label) },
                )
            }
        }
        // タブを切り替えても、各タブのスクロール位置などを残す
        val tabStates = rememberSaveableStateHolder()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            tabStates.SaveableStateProvider(tab.name) {
                when (tab) {
                    MainTab.Details -> ResultsPanel(state, viewModel, onOpenStudent = onOpenStudent)
                    MainTab.Summary -> SummaryPanel(
                        state,
                        onOpenResult = { id ->
                            viewModel.select(id)
                            onTabChange(MainTab.Details)
                        },
                        onOpenStudent = onOpenStudent,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBar(state: UiState, onOpenLog: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val status = state.status) {
            RunStatus.Idle -> Text(
                "設定を入力して「実行」を押してください",
                style = MaterialTheme.typography.bodyMedium
            )

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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
            is RunStatus.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "エラー: ${status.message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenLog) { Text("ログを見る") }
            }
        }
    }
}

