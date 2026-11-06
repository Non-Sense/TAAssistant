package com.n0n5ense.taassistant.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.n0n5ense.taassistant.core.CompileOutcome
import com.n0n5ense.taassistant.core.Judge
import com.n0n5ense.taassistant.core.StudentId
import com.n0n5ense.taassistant.core.TestResult
import com.n0n5ense.taassistant.core.formatMillis

enum class ResultFilter(val label: String, val accepts: (TestResult) -> Boolean) {
    All("すべて", { true }),
    NeedsReview("要確認", { it.judge in Judge.needsReview }),
    Executed("実行したもの", { it.execution != null }),
    Manual("手動判定済み", { it.manualJudge != null }),
    NotRun("NF / CE / IE", { it.judge == Judge.NF || it.judge == Judge.CE || it.judge == Judge.IE }),
}

@Composable
internal fun ResultsPanel(state: UiState, viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val outcome = state.outcome
    if (outcome == null || state.results.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                if (outcome == null) "まだ結果はありません" else "テスト結果はありません (${outcome.lastStep.label} で終了)",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val names = remember(outcome) { outcome.students.associate { it.id to it.name } }
    val studentOrder = remember(outcome) { outcome.students.withIndex().associate { (i, s) -> s.id to i } }
    val view = state.resultView
    val filter = view.filter
    val query = view.query
    // 学生ごとにまとめる (学生の順は提出物ディレクトリの順)
    val groups = remember(state.results, filter, query, names, studentOrder) {
        val q = query.trim()
        state.results
            .filter { r ->
                filter.accepts(r) && (
                    q.isEmpty() ||
                        r.studentId.value.contains(q, ignoreCase = true) ||
                        names[r.studentId].orEmpty().contains(q, ignoreCase = true) ||
                        r.taskName?.name.orEmpty().contains(q, ignoreCase = true) ||
                        r.source.displayPath.contains(q, ignoreCase = true)
                    )
            }
            .groupBy { it.studentId }
            .entries
            .sortedBy { studentOrder[it.key] ?: Int.MAX_VALUE }
            .map { (id, results) -> StudentGroup(id, names[id].orEmpty(), results) }
    }
    // キー操作で移動する順 (一覧の表示順)
    val visible = remember(groups) { groups.flatMap { it.results } }
    val selected = state.selected
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun move(delta: Int) {
        if (visible.isEmpty()) return
        val index = visible.indexOfFirst { it.id == state.selectedId }
        val next = if (index < 0) 0 else (index + delta).coerceIn(0, visible.lastIndex)
        viewModel.select(visible[next].id)
    }

    fun judgeAndAdvance(judge: Judge?) {
        val current = selected ?: return
        val index = visible.indexOfFirst { it.id == current.id }
        val nextId = visible.getOrNull(index + 1)?.id ?: current.id
        viewModel.setManualJudge(current.id, judge)
        viewModel.select(nextId)
    }

    fun onKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        when (event.key) {
            Key.DirectionDown, Key.J -> move(1)
            Key.DirectionUp, Key.K -> move(-1)
            Key.A -> judgeAndAdvance(Judge.AC)
            Key.W -> judgeAndAdvance(Judge.WA)
            Key.T -> judgeAndAdvance(Judge.TLE)
            Key.R -> judgeAndAdvance(Judge.RE)
            Key.C -> judgeAndAdvance(Judge.CE)
            Key.Backspace, Key.U -> selected?.let { viewModel.setManualJudge(it.id, null) }
            else -> return false
        }
        return true
    }

    Column(modifier.fillMaxSize()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            ResultFilter.entries.forEach { f ->
                val count = state.results.count(f.accepts)
                FilterChip(
                    selected = filter == f,
                    onClick = { viewModel.updateResultView { it.copy(filter = f) } },
                    label = { Text("${f.label} ($count)") },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { q -> viewModel.updateResultView { it.copy(query = q) } },
                placeholder = { Text("学籍番号・名前・課題で絞り込み") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(260.dp),
            )
        }
        Text(
            "キー操作: A=AC  W=WA  T=TLE  R=RE  C=CE (判定して次へ) / U・BackSpace=自動判定に戻す / ↑↓・J K=移動",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
        )
        HorizontalDivider()
        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onKeyEvent(::onKey)
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            ResultList(
                groups = groups,
                collapsed = view.collapsed,
                onCollapsedChange = { c -> viewModel.updateResultView { it.copy(collapsed = c) } },
                selectedId = state.selectedId,
                onSelect = {
                    viewModel.select(it)
                    runCatching { focusRequester.requestFocus() }
                },
                modifier = Modifier.width(340.dp).fillMaxHeight(),
            )
            VerticalDivider()
            if (selected == null) {
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text("左の一覧から結果を選択してください", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                ResultDetail(
                    result = selected,
                    studentName = names[selected.studentId].orEmpty(),
                    viewModel = viewModel,
                    onJudge = ::judgeAndAdvance,
                    onMove = ::move,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

internal class StudentGroup(val studentId: StudentId, val name: String, val results: List<TestResult>)

/** LazyColumn 上での、[groupIndex] 番目の学生の見出しの位置 */
private fun headerIndexOf(groups: List<StudentGroup>, collapsed: Set<StudentId>, groupIndex: Int): Int =
    groups.take(groupIndex).sumOf { 1 + if (it.studentId in collapsed) 0 else it.results.size }

/** LazyColumn 上の [itemIndex] 番目の項目が属する学生の番号 */
private fun groupIndexAt(groups: List<StudentGroup>, collapsed: Set<StudentId>, itemIndex: Int): Int {
    var index = 0
    groups.forEachIndexed { i, group ->
        index += 1 + if (group.studentId in collapsed) 0 else group.results.size
        if (itemIndex < index) return i
    }
    return groups.lastIndex
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultList(
    groups: List<StudentGroup>,
    collapsed: Set<StudentId>,
    onCollapsedChange: (Set<StudentId>) -> Unit,
    selectedId: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 選択中の結果が見えるようにスクロールする。
    // 開閉では動かさない (選択が変わったときと、一覧の中身が変わったときだけ)
    // タブを切り替えて戻ってきたときに再実行されないよう、最後にスクロールした条件を覚えておく
    val scrollKey = "$selectedId:" + groups.joinToString(",") { g -> "${g.studentId.value}/${g.results.size}" }
    var lastScrollKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(scrollKey) {
        if (scrollKey == lastScrollKey) return@LaunchedEffect
        lastScrollKey = scrollKey
        val groupIndex = groups.indexOfFirst { g -> g.results.any { it.id == selectedId } }
        if (groupIndex < 0) return@LaunchedEffect
        val group = groups[groupIndex]
        // 選択した学生は自動で開かれるので、開いた状態として数える
        val effective = collapsed - group.studentId
        val target = headerIndexOf(groups, effective, groupIndex) + 1 + group.results.indexOfFirst { it.id == selectedId }
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val first = visibleItems.firstOrNull()?.index ?: 0
        val last = visibleItems.lastOrNull()?.index ?: 0
        // 見出しが上に貼り付くので、1つ余裕を持たせる
        if (target <= first + 1 || target >= last) listState.scrollToItem((target - 3).coerceAtLeast(0))
    }

    // 開閉で表示位置がずれないよう、開閉と同時に基準の見出しへの位置を指定する。
    // requestScrollToItem は次のレイアウト (= 開閉後の一覧) で適用される
    fun toggle(groupIndex: Int) {
        val id = groups[groupIndex].studentId
        val header = headerIndexOf(groups, collapsed, groupIndex) // 自分より前の学生だけで決まるので、開閉の前後で同じ
        val first = listState.firstVisibleItemIndex
        // 見出しが上端に貼り付いている (中身の途中までスクロールしている) ときに閉じると、
        // 基準にしていた項目が消えて位置が飛ぶ。閉じた後は見出しが上端に来るようにする
        val stuck = header < first || (header == first && listState.firstVisibleItemScrollOffset > 0)
        if (stuck) listState.requestScrollToItem(header)
        onCollapsedChange(if (id in collapsed) collapsed - id else collapsed + id)
    }

    fun toggleAll(collapseAll: Boolean) {
        val anchor = groupIndexAt(groups, collapsed, listState.firstVisibleItemIndex)
        val next = if (collapseAll) groups.map { it.studentId }.toSet() else emptySet()
        // いま上端にいる学生の見出しが、開閉後も上端に来るようにする
        listState.requestScrollToItem(headerIndexOf(groups, next, anchor))
        onCollapsedChange(next)
    }

    if (groups.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("該当する結果はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "学生 ${groups.size} 人 · 結果 ${groups.sumOf { it.results.size }} 件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val allCollapsed = groups.all { it.studentId in collapsed }
            TextButton(onClick = { toggleAll(!allCollapsed) }) { Text(if (allCollapsed) "すべて開く" else "すべて閉じる") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            groups.forEachIndexed { groupIndex, group ->
                val isCollapsed = group.studentId in collapsed
                stickyHeader(key = "student:${group.studentId.value}") {
                    StudentHeader(
                        group = group,
                        collapsed = isCollapsed,
                        containsSelection = group.results.any { it.id == selectedId },
                        onClick = { toggle(groupIndex) },
                    )
                }
                if (!isCollapsed) {
                    items(group.results, key = { it.id }) { result ->
                        ResultRow(result, selected = result.id == selectedId, onClick = { onSelect(result.id) })
                    }
                }
            }
        }
    }
}

/** 学生の見出し。判定ごとの件数を並べる。クリックで開閉 */
@Composable
private fun StudentHeader(group: StudentGroup, collapsed: Boolean, containsSelection: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (collapsed) "開く" else "閉じる",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${group.studentId}  ${group.name}",
                    style = MaterialTheme.typography.titleSmall,
                    // 閉じていても、選択中の結果を含む学生はわかるようにする
                    color = if (collapsed && containsSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val counts = group.results.groupingBy { it.judge }.eachCount()
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Judge.entries.filter { it in counts }.forEach { judge ->
                        JudgeBadge(judge)
                        Text(
                            "${counts[judge]}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(result: TestResult, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(start = 34.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JudgeBadge(result.judge, manual = result.manualJudge != null, modifier = Modifier.width(48.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(result.taskName?.name ?: "(課題不明)", result.testCase?.name).joinToString(" : "),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                result.source.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ResultDetail(
    result: TestResult,
    studentName: String,
    viewModel: AppViewModel,
    onJudge: (Judge?) -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSource by rememberSaveable(result.source.path) { mutableStateOf(false) }
    val source by produceState<String?>(null, result.source.path, showSource) {
        value = if (showSource) viewModel.readSource(result) else null
    }
    val scroll = rememberScrollState()
    // 別の結果を選んだときだけ先頭に戻す (タブを切り替えて戻ってきたときは戻さない)
    var scrolledFor by rememberSaveable { mutableStateOf(result.id) }
    LaunchedEffect(result.id) {
        if (scrolledFor != result.id) {
            scrolledFor = result.id
            scroll.scrollTo(0)
        }
    }

    Column(
        modifier.verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- 見出し
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${result.studentId}  $studentName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "課題: ${result.taskName?.name ?: "(特定できず)"}    テストケース: ${result.testCase?.name ?: "-"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                SelectionContainer {
                    Text(
                        result.source.displayPath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedButton(onClick = { onMove(-1) }) { Text("前へ") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onMove(1) }) { Text("次へ") }
        }

        // ---- 判定
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("判定", style = MaterialTheme.typography.labelLarge)
            JudgeBadge(result.judge, manual = result.manualJudge != null)
            Text(
                if (result.manualJudge != null) "(手動 / 自動判定は ${result.autoJudge})" else "(自動判定)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Judge.manualChoices.forEach { judge ->
                val (background, foreground) = judgeColors(judge)
                val active = result.manualJudge == judge
                if (active) {
                    Button(
                        onClick = { onJudge(judge) },
                        colors = ButtonDefaults.buttonColors(containerColor = foreground, contentColor = background),
                    ) { Text(judge.label) }
                } else {
                    OutlinedButton(onClick = { onJudge(judge) }) { Text(judge.label, color = foreground) }
                }
            }
            TextButton(onClick = { viewModel.setManualJudge(result.id, null) }, enabled = result.manualJudge != null) {
                Text("自動判定に戻す")
            }
        }

        HorizontalDivider()

        // ---- 実行情報
        val execution = result.execution
        val testCase = result.testCase
        val info = buildList {
            if (testCase != null) {
                add("引数" to testCase.arg.ifEmpty { "(なし)" })
            }
            if (execution != null) {
                add("実行時間" to "${formatMillis(execution.elapsedMillis)} ms" + if (execution.timedOut) " (タイムアウト)" else "")
                add("終了コード" to (execution.exitCode?.toString() ?: "-"))
                add("実行コマンド" to execution.command)
            }
            add("コンパイル" to result.compile.command)
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                info.forEach { (label, value) ->
                    Row {
                        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(90.dp))
                        Text(value, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (testCase != null) {
            if (testCase.input.isNotEmpty()) TextBlock("標準入力", testCase.input.joinToString("\n"))
            TextBlock("想定 (正規表現)", testCase.expect.joinToString("\n"))
        }
        if (execution != null) {
            TextBlock("標準出力", execution.stdout.trimEnd())
            if (execution.stderr.isNotBlank()) TextBlock("標準エラー出力", execution.stderr.trimEnd())
        }
        when (val compile = result.compile) {
            is CompileOutcome.Failure -> TextBlock("コンパイルエラー", compile.message.trimEnd())
            is CompileOutcome.Error -> TextBlock("コンパイル時の内部エラー", compile.message.trimEnd())
            is CompileOutcome.Success -> if (compile.warnings.isNotBlank()) TextBlock("コンパイル時の警告", compile.warnings.trimEnd())
        }
        if (result.note.isNotBlank()) TextBlock("内部エラー", result.note.trimEnd())

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showSource = !showSource }) {
                Text(if (showSource) "ソースを隠す" else "ソースを表示")
            }
            TextButton(onClick = { viewModel.openPath(result.source.path) }) { Text("エディタで開く") }
        }
        if (showSource) {
            TextBlock(result.source.fileName, source ?: "読み込み中…")
        }
    }
}
