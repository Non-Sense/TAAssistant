package com.n0n5ense.taassistant.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.ui.common.AppVerticalScrollbar
import com.n0n5ense.taassistant.ui.common.CheckRow
import com.n0n5ense.taassistant.ui.common.MenuEntry
import com.n0n5ense.taassistant.ui.common.OverflowMenu
import com.n0n5ense.taassistant.ui.common.PathField
import com.n0n5ense.taassistant.ui.common.RadioRow
import com.n0n5ense.taassistant.ui.common.SectionLabel
import com.n0n5ense.taassistant.ui.log.LogCounts

@Composable
internal fun SetupPanel(
    state: UiState,
    viewModel: AppViewModel,
    onEditConfig: () -> Unit,
    onNewConfig: () -> Unit = {},
    configEditorDirty: Boolean = false,
    onOpenLog: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val setup = state.setup
    val editable = !state.isRunning

    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProjectHeader(state, viewModel)

            SectionLabel("入力")
            PathField(
                label = "提出物ディレクトリ",
                value = setup.rootDir,
                onValueChange = { v -> viewModel.updateSetup { it.copy(rootDir = v) } },
                onBrowse = viewModel::browseRootDir,
                enabled = editable,
                hint = "Moodle の zip を展開したディレクトリ (形式はコンフィグの submissionDirPattern で変更可)",
            )
            PathField(
                label = "コンフィグファイル (YAML)",
                value = setup.configPath,
                onValueChange = { v -> viewModel.updateSetup { it.copy(configPath = v) } },
                onBrowse = viewModel::browseConfig,
                enabled = editable,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                // 編集中の (未保存の) コンフィグがあれば、パスが空でもエディタに戻れる
                TextButton(onClick = onEditConfig, enabled = setup.configPath.isNotBlank() || configEditorDirty) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("編集…")
                }
                TextButton(onClick = onNewConfig) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("新規作成…")
                }
                if (configEditorDirty) {
                    Text(
                        "● 未保存の変更あり",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            SectionLabel("実行ステップ")
            Column {
                LastStep.entries.forEach { step ->
                    RadioRow(step.label, selected = setup.lastStep == step, enabled = editable) {
                        viewModel.updateSetup { it.copy(lastStep = step) }
                    }
                }
            }

            SectionLabel("オプション")
            Column {
                CheckRow("テストを並列実行する", setup.parallelTests, editable) { v ->
                    viewModel.updateSetup { it.copy(parallelTests = v) }
                }
                CheckRow("実行前に workspace を初期化する", setup.cleanWorkspace, editable) { v ->
                    viewModel.updateSetup { it.copy(cleanWorkspace = v) }
                }
            }

            SectionLabel("Java")
            PathField(
                label = "JDK の bin ディレクトリ",
                value = setup.jdkBinDir,
                onValueChange = { v -> viewModel.updateSetup { it.copy(jdkBinDir = v) } },
                onBrowse = viewModel::browseJdkBinDir,
                enabled = editable,
                hint = "空欄なら PATH 上の javac / java を使います",
            )

            HorizontalDivider(Modifier.padding(vertical = 6.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = viewModel::start,
                    enabled = !state.isRunning,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("実行")
                }
                OutlinedButton(onClick = viewModel::cancel, enabled = state.isRunning) {
                    Text("中止")
                }
            }
            // 実行のログ (別の画面で開く)
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) {
                Text("ログ", modifier = Modifier.padding(end = 12.dp))
                LogCounts(state.logs)
            }
        }
        AppVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** 開いているプロジェクトの名前・保存状態と、プロジェクトの操作 */
@Composable
private fun ProjectHeader(state: UiState, viewModel: AppViewModel) {
    val project = state.project ?: return
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                project.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                project.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            when (val save = state.saveStatus) {
                SaveStatus.Saved -> Text(
                    "自動保存済み",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                SaveStatus.Saving -> Text(
                    "保存中…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                is SaveStatus.Failed -> Text(
                    save.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.projectError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.projectNotice?.let { notice ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        notice.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    notice.openPath?.let { path ->
                        TextButton(
                            onClick = { viewModel.openProject(path) },
                            enabled = !state.isRunning
                        ) { Text("開く") }
                    }
                    TextButton(onClick = viewModel::dismissProjectNotice) { Text("閉じる") }
                }
            }
        }
        OverflowMenu(
            "プロジェクト",
            listOf(
                MenuEntry(
                    "新しいプロジェクト…",
                    enabled = !state.isRunning,
                    onClick = viewModel::newProject
                ),
                MenuEntry(
                    "プロジェクトを開く…",
                    enabled = !state.isRunning,
                    onClick = { viewModel.openProject() }),
                MenuEntry(
                    "別名で保存…",
                    enabled = !state.isRunning,
                    onClick = viewModel::saveProjectAs
                ),
                MenuEntry(
                    "複製…",
                    enabled = !state.isRunning,
                    onClick = { viewModel.duplicateProject(includeResults = true) }),
                MenuEntry(
                    "設定だけを複製…",
                    enabled = !state.isRunning,
                    onClick = { viewModel.duplicateProject(includeResults = false) }),
                MenuEntry("フォルダを開く", onClick = viewModel::openProjectFolder),
                MenuEntry(
                    "プロジェクトを閉じる",
                    enabled = !state.isRunning,
                    onClick = viewModel::closeProject
                ),
            ),
        )
    }
}
