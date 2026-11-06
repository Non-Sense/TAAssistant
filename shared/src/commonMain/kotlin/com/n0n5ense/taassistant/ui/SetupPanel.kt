package com.n0n5ense.taassistant.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n0n5ense.taassistant.core.LastStep
import com.n0n5ense.taassistant.core.ReportFormat

@Composable
internal fun SetupPanel(
    state: UiState,
    viewModel: AppViewModel,
    onEditConfig: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val setup = state.setup
    val editable = !state.isRunning

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("TA Assistant", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Moodle の提出物を展開・コンパイル・テストして結果をまとめます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
        TextButton(onClick = onEditConfig, modifier = Modifier.padding(top = 0.dp)) {
            Text(if (setup.configPath.isBlank()) "コンフィグを新規作成…" else "コンフィグを編集…")
        }
        PathField(
            label = "出力先ディレクトリ",
            value = setup.outputDir,
            onValueChange = { v -> viewModel.updateSetup { it.copy(outputDir = v) } },
            onBrowse = viewModel::browseOutputDir,
            enabled = editable,
            hint = "空欄なら提出物ディレクトリの親に出力します",
        )

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
            CheckRow("完了時に結果ファイルを出力する", setup.autoExport, editable) { v ->
                viewModel.updateSetup { it.copy(autoExport = v) }
            }
        }

        SectionLabel("出力形式")
        Column {
            ReportFormat.entries.forEach { format ->
                RadioRow(format.label, selected = setup.reportFormat == format, enabled = editable) {
                    viewModel.updateSetup { it.copy(reportFormat = format) }
                }
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = viewModel::start, enabled = !state.isRunning, modifier = Modifier.weight(1f)) {
                Text("実行")
            }
            OutlinedButton(onClick = viewModel::cancel, enabled = state.isRunning) {
                Text("中止")
            }
        }
        OutlinedButton(onClick = viewModel::export, enabled = state.canExport, modifier = Modifier.fillMaxWidth()) {
            Text("結果ファイルを出力")
        }

        state.export?.let { export ->
            Text(
                export.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (export.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            export.paths.forEach { path ->
                TextButton(onClick = { viewModel.openPath(path) }) {
                    Text(
                        "開く: " + path.substringAfterLast('/').substringAfterLast('\\'),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
