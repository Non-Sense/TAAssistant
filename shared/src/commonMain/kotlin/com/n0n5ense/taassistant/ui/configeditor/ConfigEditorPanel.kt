package com.n0n5ense.taassistant.ui.configeditor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0n5ense.taassistant.core.config.Config
import com.n0n5ense.taassistant.core.config.ConfigDraft
import com.n0n5ense.taassistant.core.config.DraftField
import com.n0n5ense.taassistant.core.config.DraftIssue
import com.n0n5ense.taassistant.core.config.Task
import com.n0n5ense.taassistant.core.config.TaskDraft
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCaseDraft
import com.n0n5ense.taassistant.core.config.parseSubmissionDirName
import com.n0n5ense.taassistant.core.config.regexError
import com.n0n5ense.taassistant.core.config.sampleMatches
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.ui.common.AppVerticalScrollbar
import com.n0n5ense.taassistant.ui.common.JudgeBadge
import com.n0n5ense.taassistant.ui.common.MenuEntry
import com.n0n5ense.taassistant.ui.common.OverflowMenu
import com.n0n5ense.taassistant.ui.common.TooltipIconButton
import com.n0n5ense.taassistant.ui.common.judgeColors

private val monospace = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

/** 詳細ペインの最大幅。広いウィンドウでも1行が長くなりすぎないようにする */
private val DetailMaxWidth = 960.dp

private fun fileNameOf(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

@Composable
internal fun ConfigEditorPanel(
    state: ConfigEditorState,
    viewModel: ConfigEditorViewModel,
    gradingConfigPath: String,
    onUseForGrading: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    MessageSnackbar(state.message, snackbar, viewModel)

    Box(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
                when {
                    event.key == Key.S && event.isShiftPressed -> viewModel.saveAs()
                    event.key == Key.S -> viewModel.save()
                    event.key == Key.O -> viewModel.requestOpen()
                    event.key == Key.N -> viewModel.requestNew()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            // Esc で採点画面に戻る (ダイアログやメニューが開いているときは、そちらが先に Esc を受け取る)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onBack()
                    true
                } else {
                    false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
            EditorTopBar(state, viewModel, gradingConfigPath, onUseForGrading, onBack)
            Banners(state, viewModel)
            HorizontalDivider()
            when (state.mode) {
                EditorMode.Form -> Row(Modifier.weight(1f).fillMaxWidth()) {
                    TaskListPane(state, viewModel, Modifier.width(264.dp).fillMaxHeight())
                    VerticalDivider()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        val task = state.selectedTask
                        when {
                            task != null -> TaskEditor(task, state.draft.tasks.indexOf(task), state.draft.tasks.size, state.issues, state.collapsedTestCases, viewModel)
                            state.selection is EditorSelection.Task -> EmptyDetail(viewModel)
                            else -> GeneralSettings(state.draft, state.issues, state.dirNameSample, viewModel)
                        }
                    }
                }
                EditorMode.Yaml -> YamlEditor(state, viewModel, Modifier.weight(1f).fillMaxWidth())
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    state.pending?.let { DiscardDialog(state, viewModel) }
}

/** ViewModel のメッセージをスナックバーで表示する。削除のメッセージには「元に戻す」を付ける */
@Composable
private fun MessageSnackbar(message: EditorMessage?, host: SnackbarHostState, viewModel: ConfigEditorViewModel) {
    LaunchedEffect(message?.id) {
        if (message == null) return@LaunchedEffect
        val result = host.showSnackbar(
            message = message.text,
            actionLabel = if (message.canUndo) "元に戻す" else null,
            withDismissAction = message.isError,
            duration = when {
                message.isError -> SnackbarDuration.Long
                message.canUndo -> SnackbarDuration.Long
                else -> SnackbarDuration.Short
            },
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undo()
        viewModel.dismissMessage(message.id)
    }
}

// ---------------------------------------------------------------- トップバー

@Composable
private fun EditorTopBar(
    state: ConfigEditorState,
    viewModel: ConfigEditorViewModel,
    gradingConfigPath: String,
    onUseForGrading: (String) -> Unit,
    onBack: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 採点画面に戻る (編集中の内容は残る)
        TooltipIconButton("採点画面に戻る (Esc)", Icons.AutoMirrored.Filled.ArrowBack, onBack)
        // タイトル: ファイル名 + 状態
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    state.path?.let(::fileNameOf) ?: "新しいコンフィグ",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state.isDirty) StatusLabel("未保存", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
                if (state.path != null && state.path == gradingConfigPath) {
                    StatusLabel("採点に使用中", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer, Icons.Filled.Check)
                }
            }
            Text(
                state.path ?: "まだ保存されていません",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (state.issues.isNotEmpty()) {
            TextButton(
                onClick = viewModel::showFirstIssue,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("エラー ${state.issues.size} 件")
            }
        }

        // 表示の切り替え (フォーム / YAML) は segmented button
        SingleChoiceSegmentedButtonRow {
            EditorMode.entries.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = state.mode == mode,
                    onClick = { viewModel.switchMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, EditorMode.entries.size),
                ) { Text(mode.label) }
            }
        }

        Spacer(Modifier.width(8.dp))

        // 採点への反映。すでに採点に使っているファイルなら、保存するだけで反映される
        val usedForGrading = state.path != null && state.path == gradingConfigPath
        if (!usedForGrading) {
            OutlinedButton(onClick = {
                val path = state.path
                // 変更が無ければ保存し直さない (コメントを消さないため)
                if (path != null && !state.isDirty) onUseForGrading(path) else viewModel.save(onSaved = onUseForGrading)
            }) {
                Text(if (state.path != null && !state.isDirty) "採点に使う" else "保存して採点に使う")
            }
        }
        // 画面の主要な操作は「保存」の1つだけを filled にする
        Button(onClick = { viewModel.save() }) { Text("保存") }

        OverflowMenu(
            "ファイル",
            listOf(
                MenuEntry("新規", shortcut = "Ctrl+N", onClick = viewModel::requestNew),
                MenuEntry("開く…", shortcut = "Ctrl+O", onClick = viewModel::requestOpen),
                MenuEntry("別名で保存…", shortcut = "Ctrl+Shift+S", onClick = { viewModel.saveAs() }),
            ),
        )
    }
}

@Composable
private fun StatusLabel(text: String, container: Color, content: Color, icon: ImageVector? = null) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ---------------------------------------------------------------- バナー

/** ファイル全体に関わる、対応が必要なお知らせ (スナックバーと違い、消えずに残る) */
@Composable
private fun Banners(state: ConfigEditorState, viewModel: ConfigEditorViewModel) {
    state.migration?.let { migration ->
        Banner(
            icon = Icons.Filled.Info,
            title = "旧形式のコンフィグを新しい形式に変換して開いています",
            lines = migration.notes + "ファイルはまだ変更していません。保存して上書きすると、変換前の内容を ${fileNameOf(migration.sourcePath)}.bak に残します。",
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
        ) {
            TextButton(onClick = { viewModel.save() }) { Text("変換して保存") }
        }
    }
    if (state.hadComments) {
        Banner(
            icon = Icons.Filled.Info,
            title = "このファイルには YAML のコメントがあります",
            lines = listOf("エディタで保存するとコメントは失われます。"),
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            content = MaterialTheme.colorScheme.onSurface,
        ) {
            TextButton(onClick = viewModel::dismissCommentsNotice) { Text("閉じる") }
        }
    }
}

@Composable
private fun Banner(
    icon: ImageVector,
    title: String,
    lines: List<String>,
    container: Color,
    content: Color,
    actions: @Composable () -> Unit,
) {
    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Box(Modifier.align(Alignment.CenterVertically)) { actions() }
        }
    }
}

// ---------------------------------------------------------------- リストペイン

@Composable
private fun TaskListPane(state: ConfigEditorState, viewModel: ConfigEditorViewModel, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier) {
        val listState = rememberLazyListState()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)) {
                item {
                    PaneItem(
                        label = "全体設定",
                        supporting = "タイムアウト・出力・ディレクトリ名",
                        selected = state.selection == EditorSelection.General,
                        errorCount = state.issues.count { it.taskKey == null },
                        icon = Icons.Filled.Settings,
                    ) { viewModel.select(EditorSelection.General) }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "課題 (${state.draft.tasks.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TooltipIconButton("課題を追加", Icons.Filled.Add, viewModel::addTask)
                    }
                }
                items(state.draft.tasks, key = { it.key }) { task ->
                    PaneItem(
                        label = task.name.ifBlank { "(無題)" },
                        supporting = if (task.testCases.isEmpty()) "テストケースなし" else "テストケース ${task.testCases.size} 件",
                        selected = (state.selection as? EditorSelection.Task)?.key == task.key,
                        errorCount = state.issues.count { it.taskKey == task.key },
                    ) { viewModel.select(EditorSelection.Task(task.key)) }
                }
            }
            AppVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** ナビゲーションドロワーの項目と同じ見た目 (選択中は secondaryContainer のインジケーター) */
@Composable
private fun PaneItem(
    label: String,
    supporting: String,
    selected: Boolean,
    errorCount: Int,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.SemiBold else null)
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        badge = if (errorCount > 0) {
            { Badge { Text("$errorCount") } }
        } else {
            null
        },
        selected = selected,
        onClick = onClick,
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@Composable
private fun EmptyDetail(viewModel: ConfigEditorViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("課題がありません", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        FilledTonalButton(onClick = viewModel::addTask) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("課題を追加")
        }
    }
}

// ---------------------------------------------------------------- 詳細ペイン共通

@Composable
private fun DetailPane(content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = DetailMaxWidth).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) { content() }
        }
        AppVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** 見出しと説明の付いたフォームのまとまり */
@Composable
private fun FormSection(
    title: String,
    description: String? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (description != null) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing()
        }
        content()
    }
}

/** 正規表現をその場で試す欄 (保存されない) */
@Composable
private fun TryBox(title: String, content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text("保存されません", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

// ---------------------------------------------------------------- 全体設定

@Composable
private fun GeneralSettings(draft: ConfigDraft, issues: List<DraftIssue>, dirNameSample: String, viewModel: ConfigEditorViewModel) {
    fun issue(field: DraftField) = issues.firstOrNull { it.field == field && it.taskKey == null }?.message

    DetailPane {
        Text("全体設定", style = MaterialTheme.typography.headlineSmall)

        FormSection("タイムアウト") {
            val compileError = issue(DraftField.CompileTimeout)
            val runningError = issue(DraftField.RunningTimeout)
            FieldRow(
                RowField(1f, compileError, "1 ソースあたり") { m ->
                    EditorField(
                        label = "コンパイル",
                        value = draft.compileTimeout,
                        onValueChange = { v -> viewModel.updateGeneral { it.copy(compileTimeout = v) } },
                        error = compileError,
                        suffix = "ms",
                        supportingInside = false,
                        modifier = m,
                    )
                },
                RowField(1f, runningError, "1 テストケースあたり。負の値で無制限") { m ->
                    EditorField(
                        label = "実行",
                        value = draft.runningTimeout,
                        onValueChange = { v -> viewModel.updateGeneral { it.copy(runningTimeout = v) } },
                        error = runningError,
                        suffix = "ms",
                        supportingInside = false,
                        modifier = m,
                    )
                },
            )
        }

        FormSection("出力") {
            EditorField(
                label = "出力ファイル名",
                value = draft.outputFileName,
                onValueChange = { v -> viewModel.updateGeneral { it.copy(outputFileName = v) } },
                error = issue(DraftField.OutputFileName),
                hint = "拡張子は出力形式に合わせて自動で付きます",
            )
        }

        FormSection(
            "提出ディレクトリ名",
            description = "Moodle の提出ディレクトリ名から学籍番号と氏名を取り出す正規表現。名前付きグループ id = 学籍番号 (必須)、name = 氏名。" +
                "名前付きグループが無ければ 1番目 = 学籍番号、2番目 = 氏名。",
            trailing = {
                TextButton(
                    onClick = { viewModel.updateGeneral { it.copy(submissionDirPattern = Config.DEFAULT_SUBMISSION_DIR_PATTERN) } },
                    enabled = draft.submissionDirPattern != Config.DEFAULT_SUBMISSION_DIR_PATTERN,
                ) { Text("既定に戻す") }
            },
        ) {
            EditorField(
                label = "パターン (正規表現)",
                value = draft.submissionDirPattern,
                onValueChange = { v -> viewModel.updateGeneral { it.copy(submissionDirPattern = v) } },
                error = issue(DraftField.SubmissionDirPattern),
                hint = "ディレクトリ名全体にマッチしたものだけが採点対象になります",
                monospaced = true,
            )
            TryBox("ディレクトリ名で試す") {
                EditorField(
                    label = "ディレクトリ名",
                    value = dirNameSample,
                    onValueChange = viewModel::updateDirNameSample,
                    placeholder = "t100d400 山田 太郎_123456_assignsubmission_file_",
                )
                if (dirNameSample.isNotEmpty() && regexError(draft.submissionDirPattern) == null) {
                    val parsed = remember(draft.submissionDirPattern, dirNameSample) {
                        Config(submissionDirPattern = draft.submissionDirPattern, tasks = emptyMap()).parseSubmissionDirName(dirNameSample)
                    }
                    MatchResultText(
                        matched = parsed != null,
                        text = parsed?.let { "一致: 学籍番号 = ${it.studentId} / 氏名 = ${it.studentName.ifEmpty { "(なし)" }}" }
                            ?: "一致しません (このディレクトリは採点対象外になります)",
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 課題

@Composable
private fun TaskEditor(
    task: TaskDraft,
    index: Int,
    taskCount: Int,
    issues: List<DraftIssue>,
    collapsed: Set<Long>,
    viewModel: ConfigEditorViewModel,
) {
    fun issue(field: DraftField, caseKey: Long? = null) =
        issues.firstOrNull { it.field == field && it.taskKey == task.key && it.testCaseKey == caseKey }?.message

    DetailPane {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    task.name.ifBlank { "(無題)" },
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "課題 ${index + 1} / $taskCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OverflowMenu(
                "課題の操作",
                listOf(
                    MenuEntry("上へ移動", Icons.Filled.KeyboardArrowUp, enabled = index > 0) { viewModel.moveTask(task.key, -1) },
                    MenuEntry("下へ移動", Icons.Filled.KeyboardArrowDown, enabled = index < taskCount - 1) { viewModel.moveTask(task.key, 1) },
                    MenuEntry("複製") { viewModel.duplicateTask(task.key) },
                    MenuEntry("削除", Icons.Filled.Delete, destructive = true) { viewModel.removeTask(task.key) },
                ),
            )
        }

        FormSection("課題の判定", description = "ソースに検索ワードのどれかが含まれ、除外ワードがどれも含まれなければ、この課題の提出とみなします。") {
            EditorField(
                label = "課題名",
                value = task.name,
                onValueChange = { v -> viewModel.updateTask(task.key) { it.copy(name = v) } },
                error = issue(DraftField.TaskName),
            )
            val wordsError = issue(DraftField.Words)
            FieldRow(
                RowField(1f, wordsError, "1行に1つ") { m ->
                    EditorField(
                        label = "検索ワード",
                        value = task.words,
                        onValueChange = { v -> viewModel.updateTask(task.key) { it.copy(words = v) } },
                        error = wordsError,
                        minLines = 3,
                        supportingInside = false,
                        modifier = m,
                    )
                },
                RowField(1f, null, "1行に1つ") { m ->
                    EditorField(
                        label = "除外ワード (任意)",
                        value = task.excludeWords,
                        onValueChange = { v -> viewModel.updateTask(task.key) { it.copy(excludeWords = v) } },
                        minLines = 3,
                        supportingInside = false,
                        modifier = m,
                    )
                },
            )
        }

        FormSection(
            "テストケース (${task.testCases.size})",
            description = if (task.testCases.isEmpty()) {
                "テストケースがありません。この課題の提出はコンパイルのみ行われます。"
            } else {
                "想定出力の正規表現にすべてマッチすれば AC です。"
            },
            trailing = {
                if (task.testCases.size > 1) {
                    val allCollapsed = task.testCases.all { it.key in collapsed }
                    TextButton(onClick = { viewModel.setTestCasesCollapsed(task.testCases.map { it.key }, !allCollapsed) }) {
                        Text(if (allCollapsed) "すべて開く" else "すべて閉じる")
                    }
                }
            },
        ) {
            task.testCases.forEachIndexed { caseIndex, testCase ->
                val nameError = issue(DraftField.TestCaseName, testCase.key)
                val expectError = issue(DraftField.Expect, testCase.key)
                TestCaseCard(
                    index = caseIndex,
                    count = task.testCases.size,
                    testCase = testCase,
                    nameError = nameError,
                    expectError = expectError,
                    // エラーのあるカードは閉じられないようにする (エラーが見えなくなるため)
                    expanded = testCase.key !in collapsed || nameError != null || expectError != null,
                    onToggle = { viewModel.setTestCasesCollapsed(listOf(testCase.key), testCase.key !in collapsed) },
                    onChange = { transform -> viewModel.updateTestCase(task.key, testCase.key, transform) },
                    onMove = { delta -> viewModel.moveTestCase(task.key, testCase.key, delta) },
                    onDuplicate = { viewModel.duplicateTestCase(task.key, testCase.key) },
                    onRemove = { viewModel.removeTestCase(task.key, testCase.key) },
                )
            }
            OutlinedButton(onClick = { viewModel.addTestCase(task.key) }) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("テストケースを追加")
            }
        }
    }
}

@Composable
private fun TestCaseCard(
    index: Int,
    count: Int,
    testCase: TestCaseDraft,
    nameError: String?,
    expectError: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: ((TestCaseDraft) -> TestCaseDraft) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onRemove: () -> Unit,
) {
    val hasError = nameError != null || expectError != null
    val sampleResult = if (testCase.sampleOutput.isNotEmpty()) testCase.sampleMatches() else null

    OutlinedCard(Modifier.fillMaxWidth()) {
        // ヘッダー: クリックで開閉
        Row(
            Modifier.fillMaxWidth().clickable(enabled = !hasError, onClick = onToggle).padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "閉じる" else "開く",
                tint = if (hasError) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("#${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(
                testCase.name.ifBlank { "(無題)" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!expanded) {
                Text(
                    testCaseSummary(testCase),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (hasError) {
                Badge { Text("エラー") }
            } else if (sampleResult != null) {
                JudgeBadge(if (sampleResult) Judge.AC else Judge.WA)
            }
            OverflowMenu(
                "テストケースの操作",
                listOf(
                    MenuEntry("上へ移動", Icons.Filled.KeyboardArrowUp, enabled = index > 0) { onMove(-1) },
                    MenuEntry("下へ移動", Icons.Filled.KeyboardArrowDown, enabled = index < count - 1) { onMove(1) },
                    MenuEntry("複製", onClick = onDuplicate),
                    MenuEntry("削除", Icons.Filled.Delete, destructive = true, onClick = onRemove),
                ),
            )
        }

        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FieldRow(
                    RowField(1f, nameError, null) { m ->
                        EditorField(
                            label = "名前",
                            value = testCase.name,
                            onValueChange = { v -> onChange { it.copy(name = v) } },
                            error = nameError,
                            supportingInside = false,
                            modifier = m,
                        )
                    },
                    RowField(1.5f, null, "空白区切り。\"…\" で空白を含められます") { m ->
                        EditorField(
                            label = "コマンドライン引数 (任意)",
                            value = testCase.arg,
                            onValueChange = { v -> onChange { it.copy(arg = v) } },
                            monospaced = true,
                            supportingInside = false,
                            modifier = m,
                        )
                    },
                )
                FieldRow(
                    RowField(1f, null, "入力した通りに1行ずつ渡します") { m ->
                        EditorField(
                            label = "標準入力 (任意)",
                            value = testCase.input,
                            onValueChange = { v -> onChange { it.copy(input = v) } },
                            monospaced = true,
                            minLines = 3,
                            supportingInside = false,
                            modifier = m,
                        )
                    },
                    RowField(1.5f, expectError, "1行に1つ。改行を空白に置換した標準出力「全体」にマッチさせます") { m ->
                        EditorField(
                            label = "想定出力の正規表現",
                            value = testCase.expect,
                            onValueChange = { v -> onChange { it.copy(expect = v) } },
                            error = expectError,
                            monospaced = true,
                            minLines = 3,
                            supportingInside = false,
                            modifier = m,
                        )
                    },
                )
                TryBox("出力例で試す") {
                    EditorField(
                        label = "出力例",
                        value = testCase.sampleOutput,
                        onValueChange = { v -> onChange { it.copy(sampleOutput = v) } },
                        monospaced = true,
                        minLines = 2,
                    )
                    when (sampleResult) {
                        true -> MatchResultText(true, "AC: 出力例は想定出力にマッチします")
                        false -> MatchResultText(false, "WA: 出力例は想定出力にマッチしません")
                        null -> Unit
                    }
                }
            }
        }
    }
}

/** 閉じたカードに表示する概要 */
private fun testCaseSummary(testCase: TestCaseDraft): String = buildList {
    if (testCase.arg.isNotBlank()) add("引数: ${testCase.arg}")
    val inputLines = testCase.input.lines().count { it.isNotEmpty() }
    if (inputLines > 0) add("入力 $inputLines 行")
    val expects = testCase.expect.lines().count { it.isNotBlank() }
    add("正規表現 $expects 個")
}.joinToString("  ·  ")

// ---------------------------------------------------------------- YAML

@Composable
private fun YamlEditor(state: ConfigEditorState, viewModel: ConfigEditorViewModel, modifier: Modifier) {
    Column(modifier.padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "YAML を直接編集できます。フォームに切り替えるか保存すると内容が反映されます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.yamlError?.let {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
                    Column {
                        Text("YAML を解析できません", style = MaterialTheme.typography.titleSmall)
                        Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 6, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // 入力欄は内容に合わせて伸ばし、外側でスクロールする (スクロールバーを付けるため)
        val scroll = rememberScrollState()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewportHeight = maxHeight
            Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
                OutlinedTextField(
                    value = state.yamlText,
                    onValueChange = viewModel::updateYaml,
                    textStyle = monospace,
                    isError = state.yamlError != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = viewportHeight),
                )
            }
            AppVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

// ---------------------------------------------------------------- ダイアログ

@Composable
private fun DiscardDialog(state: ConfigEditorState, viewModel: ConfigEditorViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::cancelDiscard,
        icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
        title = { Text("変更を保存しますか?") },
        text = {
            Text("「${state.path?.let(::fileNameOf) ?: "新しいコンフィグ"}」への変更は保存されていません。保存しないと変更は失われます。")
        },
        confirmButton = { TextButton(onClick = viewModel::saveAndContinue) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = viewModel::confirmDiscard) { Text("保存しない") }
                TextButton(onClick = viewModel::cancelDiscard) { Text("キャンセル") }
            }
        },
    )
}

// ---------------------------------------------------------------- 部品

/** 横並びにする入力欄。補足文 (説明 / エラー) は欄の外に出して表示する */
private class RowField(
    val weight: Float,
    val error: String?,
    val hint: String?,
    val field: @Composable (Modifier) -> Unit,
)

/**
 * 入力欄を横に並べ、欄の高さを揃える。
 * 補足文を欄の中に入れると、補足文の行数の違いで欄の高さがずれるため、補足文は別の行にまとめる。
 */
@Composable
private fun FieldRow(vararg fields: RowField) {
    Column {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            fields.forEach { it.field(Modifier.weight(it.weight).fillMaxHeight()) }
        }
        if (fields.any { it.error != null || it.hint != null }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                fields.forEach { field ->
                    Box(Modifier.weight(field.weight).padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
                        (field.error ?: field.hint)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (field.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    hint: String? = null,
    placeholder: String? = null,
    suffix: String? = null,
    monospaced: Boolean = false,
    minLines: Int = 1,
    /** false なら補足文を表示しない ([FieldRow] が欄の外にまとめて表示する) */
    supportingInside: Boolean = true,
) {
    val labelSlot: @Composable () -> Unit = { Text(label) }
    val placeholderSlot: (@Composable () -> Unit)? =
        placeholder?.let { { Text(it, style = if (monospaced) monospace else MaterialTheme.typography.bodyMedium) } }
    val suffixSlot: (@Composable () -> Unit)? = suffix?.let { { Text(it) } }
    // エラーがあるときは説明文の代わりにエラーを表示する
    val supportingSlot: (@Composable () -> Unit)? = (error ?: hint)?.takeIf { supportingInside }?.let { { Text(it) } }
    val textStyle = if (monospaced) monospace else MaterialTheme.typography.bodyLarge
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = labelSlot,
        placeholder = placeholderSlot,
        suffix = suffixSlot,
        isError = error != null,
        supportingText = supportingSlot,
        singleLine = minLines == 1,
        minLines = minLines,
        textStyle = textStyle,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun MatchResultText(matched: Boolean, text: String) {
    val (background, foreground) = judgeColors(if (matched) Judge.AC else Judge.WA)
    Text(
        text,
        color = foreground,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.background(background, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
