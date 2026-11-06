package com.n0n5ense.taassistant.ui.student

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n0n5ense.taassistant.core.grading.ArchiveEntry
import com.n0n5ense.taassistant.core.grading.ArchiveListing
import com.n0n5ense.taassistant.core.grading.FileEntry
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.isExcludedOnExtract
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.model.encoding
import com.n0n5ense.taassistant.core.report.buildSummary
import com.n0n5ense.taassistant.ui.common.AppHorizontalScrollbar
import com.n0n5ense.taassistant.ui.common.AppVerticalScrollbar
import com.n0n5ense.taassistant.ui.common.InterventionMarks
import com.n0n5ense.taassistant.ui.common.JudgeBadge
import com.n0n5ense.taassistant.ui.common.TooltipIconButton
import com.n0n5ense.taassistant.ui.common.interventionsByStudent

/**
 * 学生のページ。課題ごとの結果と、提出ファイルの一覧・中身を表示する。
 *
 * @param results 手動判定・課題の割り当てを反映した全員分の結果
 * @param onOpenResult 結果を選んで、結果タブで開く
 * @param onPrevious / [onNext] 前後の学生のページに切り替える (いなければ null)
 */
@Composable
internal fun StudentScreen(
    student: Student,
    outcome: GradingOutcome,
    results: List<TestResult>,
    viewModel: StudentViewModel,
    onBack: () -> Unit,
    onOpenResult: (Int) -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    val page by viewModel.state.collectAsStateWithLifecycle()
    val studentResults = remember(results, student.id) { results.filter { it.studentId == student.id } }

    // Esc で戻れるよう、ページにフォーカスを置く
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
        StudentTopBar(student, studentResults, results, onBack, onPrevious, onNext)
        HorizontalDivider()
        Row(Modifier.weight(1f).fillMaxWidth()) {
            val scroll = rememberScrollState()
            Box(Modifier.width(460.dp).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(scroll).padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    TaskResultsSection(student, outcome, results, studentResults, onOpenResult)
                    FilesSection(page, outcome, studentResults, viewModel)
                }
                AppVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
            VerticalDivider()
            FilePreviewPane(page, viewModel, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

// ---------------------------------------------------------------- 上部

@Composable
private fun StudentTopBar(
    student: Student,
    studentResults: List<TestResult>,
    allResults: List<TestResult>,
    onBack: () -> Unit,
    onPrevious: (() -> Unit)?,
    onNext: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TooltipIconButton("採点画面に戻る (Esc)", Icons.AutoMirrored.Filled.ArrowBack, onBack)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "${student.id}  ${student.name}",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val intervention = remember(allResults, student.id) { allResults.interventionsByStudent()[student.id] }
                InterventionMarks(intervention)
            }
            val counts = studentResults.groupingBy { it.judge }.eachCount()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (counts.isEmpty()) {
                    Text("結果がありません", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Judge.entries.filter { it in counts }.forEach { judge ->
                    JudgeBadge(judge)
                    Text("${counts[judge]}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 4.dp))
                }
            }
        }
        OutlinedButton(onClick = { onPrevious?.invoke() }, enabled = onPrevious != null) { Text("前の学生") }
        OutlinedButton(onClick = { onNext?.invoke() }, enabled = onNext != null) { Text("次の学生") }
    }
}

// ---------------------------------------------------------------- 課題ごとの結果

@Composable
private fun TaskResultsSection(
    student: Student,
    outcome: GradingOutcome,
    allResults: List<TestResult>,
    studentResults: List<TestResult>,
    onOpenResult: (Int) -> Unit,
) {
    val summary = remember(outcome, allResults, student.id) {
        buildSummary(outcome.config, listOf(student), allResults.filter { it.studentId == student.id })
    }
    val row = summary.rows.firstOrNull { it.studentId == student.id }
    Section("課題ごとの結果") {
        if (row == null || summary.taskNames.isEmpty()) {
            EmptyText("結果がありません")
            return@Section
        }
        summary.taskNames.forEachIndexed { index, task ->
            val columns = summary.columns.withIndex().filter { it.value.taskName == task }
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(task.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        "${row.acceptRatios[index]}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                // テストケースごとの判定。クリックでその結果を開く
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    columns.forEach { (columnIndex, column) ->
                        val judge = row.stats[columnIndex]
                        val target = studentResults.firstOrNull { it.taskName == task && it.testCase == column.testCase }
                            ?: studentResults.firstOrNull { it.taskName == task }
                        Row(
                            Modifier
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.small)
                                .clickable(enabled = target != null) { target?.let { onOpenResult(it.id) } }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            JudgeBadge(judge, manual = target?.manualJudge != null)
                            Text(column.testCase.name, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        val notFound = studentResults.filter { it.taskName == null }
        if (notFound.isNotEmpty()) {
            Text(
                "課題を特定できなかったソース ${notFound.size} 件 (下の「展開したソース」で NF と表示)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- ファイル一覧

@Composable
private fun FilesSection(
    page: StudentPageState,
    outcome: GradingOutcome,
    studentResults: List<TestResult>,
    viewModel: StudentViewModel,
) {
    val compiled = remember(outcome) { outcome.compiled.associateBy { it.source.path } }
    val files = page.files
    when {
        page.loading && files == null -> Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("ファイルを読み込んでいます…", style = MaterialTheme.typography.bodySmall)
        }

        page.error != null -> Text(page.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp))

        files != null -> {
            Section(
                "提出ファイル (${files.submitted.size})",
                trailing = { TooltipIconButton("一覧を更新", Icons.Filled.Refresh, viewModel::reload) },
            ) {
                if (files.submitted.isEmpty()) {
                    EmptyText("元の提出ディレクトリが分かりません (再実行すると表示されます)")
                }
                files.submitted.forEach { file ->
                    FileRow(file, selected = page.selectedFile == file, onClick = { viewModel.selectFile(file) })
                }
            }
            Section("展開したソース (${files.extracted.size})") {
                if (files.extracted.isEmpty()) EmptyText("ありません")
                files.extracted.forEach { file ->
                    val sourceResults = studentResults.filter { it.source.path == file.path }
                    FileRow(
                        file = file,
                        selected = page.selectedFile == file,
                        onClick = { viewModel.selectFile(file) },
                        details = { SourceDetails(compiled[file.path]?.outcome, sourceResults) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    file: FileEntry,
    selected: Boolean,
    onClick: () -> Unit,
    details: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                file.relativePath,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(formatSize(file.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        details?.invoke()
    }
}

/** ソースの課題判定とコンパイル結果 */
@Composable
private fun SourceDetails(compile: CompileOutcome?, results: List<TestResult>) {
    if (compile == null && results.isEmpty()) {
        Text("採点していません", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        when (compile) {
            is CompileOutcome.Success -> Text(
                "コンパイル成功 (${compile.encoding.charsetName}${compile.packageName?.let { ", package $it" } ?: ""})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is CompileOutcome.Failure -> JudgeBadge(Judge.CE)
            is CompileOutcome.Error -> JudgeBadge(Judge.IE)
            null -> Unit
        }
        if (results.any { it.taskName == null }) JudgeBadge(Judge.NF)
        results.mapNotNull { it.taskName }.distinct().forEach { task ->
            val assigned = results.any { it.taskName == task && it.assigned }
            Text(
                task.name + if (assigned) " (割り当て)" else "",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

// ---------------------------------------------------------------- ファイルの中身

@Composable
private fun FilePreviewPane(page: StudentPageState, viewModel: StudentViewModel, modifier: Modifier) {
    val file = page.selectedFile
    Column(modifier) {
        if (file == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("左の一覧からファイルを選択してください", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(file.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    file.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = { viewModel.openPath(file.path) }) { Text("開く") }
            TextButton(onClick = { viewModel.parentDirectory(file.path)?.let(viewModel::openPath) }) { Text("フォルダを開く") }
        }
        HorizontalDivider()
        when (val preview = page.preview) {
            null, FilePreview.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            is FilePreview.Unavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(preview.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            is FilePreview.Text -> CodeView(preview.text, Modifier.fillMaxSize())

            is FilePreview.Archive -> ArchiveView(preview.listing, Modifier.fillMaxSize())
        }
    }
}

/** 行番号付きで、折り返さずに表示する (縦横にスクロール) */
@Composable
private fun CodeView(text: String, modifier: Modifier) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    val lines = remember(text) { text.trimEnd('\n', '\r').lines() }
    val numberWidth = lines.size.toString().length
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Row(Modifier.fillMaxSize().verticalScroll(vertical)) {
                // 行番号 (選択・コピーの対象にしない)
                Text(
                    lines.indices.joinToString("\n") { (it + 1).toString().padStart(numberWidth) },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = false,
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                    SelectionContainer {
                        Text(
                            lines.joinToString("\n"),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            softWrap = false,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
            AppVerticalScrollbar(vertical, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        AppHorizontalScrollbar(horizontal, Modifier.fillMaxWidth())
    }
}

// ---------------------------------------------------------------- アーカイブの中身

/** アーカイブの中の階層 (フォルダ・ファイル) */
private class ArchiveNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val excluded: Boolean,
) {
    val children = mutableListOf<ArchiveNode>()

    /** フォルダ内のファイル数・合計サイズ (下の階層を含む) */
    val fileCount: Int get() = if (isDirectory) children.sumOf { it.fileCount } else 1
    val totalSize: Long get() = if (isDirectory) children.sumOf { it.totalSize } else size
}

/** 一覧からツリーを作る。途中のフォルダが一覧に無くても補う */
private fun buildArchiveTree(listing: ArchiveListing): List<ArchiveNode> {
    val root = ArchiveNode("", "", isDirectory = true, size = 0, excluded = false)
    val dirs = mutableMapOf("" to root)
    fun dir(path: String): ArchiveNode = dirs.getOrPut(path) {
        val parent = dir(path.substringBeforeLast('/', ""))
        val entry = ArchiveEntry(path, 0, isDirectory = true)
        ArchiveNode(path.substringAfterLast('/'), path, true, 0, entry.isExcludedOnExtract()).also { parent.children += it }
    }
    listing.entries.forEach { entry ->
        if (entry.isDirectory) {
            dir(entry.path)
        } else {
            val parent = dir(entry.path.substringBeforeLast('/', ""))
            parent.children += ArchiveNode(entry.name, entry.path, false, entry.size, entry.isExcludedOnExtract())
        }
    }
    fun sort(node: ArchiveNode) {
        node.children.sortWith(compareBy<ArchiveNode>({ !it.isDirectory }, { it.name.lowercase() }))
        node.children.forEach(::sort)
    }
    sort(root)
    return root.children
}

private class VisibleNode(val node: ArchiveNode, val depth: Int)

@Composable
private fun ArchiveView(listing: ArchiveListing, modifier: Modifier) {
    val tree = remember(listing) { buildArchiveTree(listing) }
    var collapsed by remember(listing) { mutableStateOf(emptySet<String>()) }
    val visible = remember(tree, collapsed) {
        buildList {
            fun walk(nodes: List<ArchiveNode>, depth: Int) {
                nodes.forEach { node ->
                    add(VisibleNode(node, depth))
                    if (node.isDirectory && node.path !in collapsed) walk(node.children, depth + 1)
                }
            }
            walk(tree, 0)
        }
    }
    val excludedCount = listing.entries.count { !it.isDirectory && it.isExcludedOnExtract() }
    Column(modifier) {
        // 概要と注意
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${listing.format.label} · ${listing.fileCount} ファイル · 合計 ${formatSize(listing.totalSize)} (展開後)" +
                        if (listing.truncated) " · 項目が多いため途中まで表示" else "",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                val dirPaths = remember(tree) {
                    buildList {
                        fun walk(nodes: List<ArchiveNode>): Unit = nodes.forEach { if (it.isDirectory) { add(it.path); walk(it.children) } }
                        walk(tree)
                    }
                }
                if (dirPaths.isNotEmpty()) {
                    val allCollapsed = collapsed.containsAll(dirPaths)
                    TextButton(onClick = { collapsed = if (allCollapsed) emptySet() else dirPaths.toSet() }) {
                        Text(if (allCollapsed) "すべて開く" else "すべて閉じる")
                    }
                }
            }
            when {
                !listing.format.extractedOnGrading -> Text(
                    "${listing.format.label} は採点時に展開されません (展開されるのは zip だけです)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                excludedCount > 0 -> Text(
                    "薄く表示しているファイル ($excludedCount 件) は採点時に展開されません (__MACOSX と .class)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("空のアーカイブです", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 4.dp)) {
                items(visible, key = { it.node.path + if (it.node.isDirectory) "/" else "" }) { item ->
                    ArchiveRow(
                        item = item,
                        collapsed = item.node.path in collapsed,
                        dimmed = item.node.excluded || !listing.format.extractedOnGrading,
                        onToggle = {
                            val path = item.node.path
                            collapsed = if (path in collapsed) collapsed - path else collapsed + path
                        },
                    )
                }
            }
            AppVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@Composable
private fun ArchiveRow(item: VisibleNode, collapsed: Boolean, dimmed: Boolean, onToggle: () -> Unit) {
    val node = item.node
    val color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (node.isDirectory) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = 8.dp + 20.dp * item.depth, end = 16.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (node.isDirectory) {
            Icon(
                if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsed) "開く" else "閉じる",
                tint = color,
                modifier = Modifier.size(18.dp),
            )
        } else {
            Spacer(Modifier.width(18.dp))
        }
        Text(
            if (node.isDirectory) node.name + "/" else node.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = if (node.isDirectory) FontWeight.SemiBold else null,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (node.excluded) {
            Text("展開しない", style = MaterialTheme.typography.labelSmall, color = color)
        }
        Text(
            if (node.isDirectory) "${node.fileCount} 件 · ${formatSize(node.totalSize)}" else formatSize(node.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 部品

@Composable
private fun Section(title: String, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

@Composable
private fun EmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}
