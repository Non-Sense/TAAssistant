package com.n0n5ense.taassistant.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.report.ReportFormat
import com.n0n5ense.taassistant.ui.common.JudgeBadge
import com.n0n5ense.taassistant.ui.common.TooltipIconButton
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
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBar(state, onOpenLog, Modifier.weight(1f))
            // 書き出しは実行後に任意で行う
            if (state.outcome?.lastStep == LastStep.Test) {
                ExportButton(state, viewModel)
            }
        }
        state.export?.let { ExportMessage(it, viewModel) }
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

/** 採点結果の書き出し (形式を選ぶと保存先のダイアログを開く) */
@Composable
private fun ExportButton(state: UiState, viewModel: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilledTonalButton(onClick = { open = true }, enabled = state.canExport) {
            Text(if (state.export == ExportStatus.Writing) "書き出し中…" else "結果を書き出す")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ReportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { Text(format.label + "…") },
                    // 前回書き出した形式に印を付ける
                    leadingIcon = {
                        if (format == state.setup.reportFormat) {
                            Icon(Icons.Filled.Check, contentDescription = "前回の形式")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    onClick = {
                        open = false
                        viewModel.export(format)
                    },
                )
            }
            HorizontalDivider()
            Text(
                "BOM 無しの UTF-8 CSV は Excel で開くと文字化けします。\nExcel で見るなら .xlsx か Shift_JIS を選んでください",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

/** 書き出しの結果 (書き出したファイルを開くボタン付き) */
@Composable
private fun ExportMessage(export: ExportStatus, viewModel: AppViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (export) {
            ExportStatus.Writing -> Text(
                "書き出し中…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )

            is ExportStatus.Failed -> Text(
                export.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )

            is ExportStatus.Done -> {
                Text(
                    "書き出しました:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    export.paths.forEach { path ->
                        TextButton(onClick = { viewModel.openPath(path) }) {
                            Text(fileName(path), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    export.paths.firstOrNull()?.let { first ->
                        TextButton(onClick = { viewModel.openContainingFolder(first) }) { Text("フォルダを開く") }
                    }
                }
            }
        }
        if (export != ExportStatus.Writing) {
            TooltipIconButton("閉じる", Icons.Filled.Close, onClick = viewModel::dismissExport)
        }
    }
}

private fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

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

