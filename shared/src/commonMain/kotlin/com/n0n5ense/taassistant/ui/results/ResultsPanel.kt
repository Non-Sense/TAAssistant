package com.n0n5ense.taassistant.ui.results

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.rememberUpdatedState
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
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.report.formatMillis
import com.n0n5ense.taassistant.ui.common.AppVerticalScrollbar
import com.n0n5ense.taassistant.ui.common.Intervention
import com.n0n5ense.taassistant.ui.common.InterventionMarks
import com.n0n5ense.taassistant.ui.common.JudgeBadge
import com.n0n5ense.taassistant.ui.common.TextBlock
import com.n0n5ense.taassistant.ui.common.TooltipIconButton
import com.n0n5ense.taassistant.ui.common.interventionsByStudent
import com.n0n5ense.taassistant.ui.common.judgeColors
import com.n0n5ense.taassistant.ui.main.AppViewModel
import com.n0n5ense.taassistant.ui.main.UiState

@Composable
internal fun ResultsPanel(
    state: UiState,
    viewModel: AppViewModel,
    modifier: Modifier = Modifier,
    onOpenStudent: (StudentId) -> Unit = {},
) {
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

    val view = state.resultView
    val filter = view.filter
    val query = view.query
    val names = remember(outcome) { outcome.students.associate { it.id to it.name } }
    val groups = remember(state.results, filter, query, outcome, names) {
        val q = query.trim()
        groupResults(
            outcome,
            state.results.filter { r ->
                filter.accepts(r) && (
                        q.isEmpty() ||
                                r.studentId.value.contains(q, ignoreCase = true) ||
                                names[r.studentId].orEmpty().contains(q, ignoreCase = true) ||
                                r.taskName?.name.orEmpty().contains(q, ignoreCase = true) ||
                                r.source.displayPath.contains(q, ignoreCase = true)
                        )
            },
        )
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
        KeyHint()
        HorizontalDivider()
        ResultBrowser(
            state = state,
            viewModel = viewModel,
            groups = groups,
            showStudentHeaders = true,
            onOpenStudent = onOpenStudent,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

/** キー操作の説明 */
@Composable
internal fun KeyHint(modifier: Modifier = Modifier) {
    Text(
        "キー操作: A=AC  W=WA  T=TLE  R=RE  C=CE (判定して次へ) / U・BackSpace=自動判定に戻す / ↑↓・J K=移動",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 14.dp, vertical = 2.dp),
    )
}

/**
 * 結果を学生ごと → 課題ごとにまとめる。
 * 学生は提出物ディレクトリの順、課題はコンフィグの順、課題を特定できなかった結果は最後。
 */
internal fun groupResults(outcome: GradingOutcome, results: List<TestResult>): List<StudentGroup> {
    val names = outcome.students.associate { it.id to it.name }
    val studentOrder = outcome.students.withIndex().associate { (i, s) -> s.id to i }
    val taskOrder = outcome.config.tasks.keys.withIndex().associate { (i, task) -> task to i }
    return results
        .groupBy { it.studentId }
        .entries
        .sortedBy { studentOrder[it.key] ?: Int.MAX_VALUE }
        .map { (id, studentResults) ->
            val tasks = studentResults.groupBy { it.taskName }.entries
                .sortedBy { (task, _) -> task?.let { taskOrder[it] } ?: Int.MAX_VALUE }
                .map { (task, taskResults) -> TaskGroup(TaskGroupKey(id, task), taskResults) }
            StudentGroup(id, names[id].orEmpty(), tasks)
        }
}

/**
 * 結果の一覧と詳細 (手動判定・課題の割り当て)、キー操作。詳細タブと学生のページで使う。
 *
 * @param showStudentHeaders 学生の見出しを出す (学生のページでは1人分なので出さない)
 * @param onOpenStudent 学生のページを開く。null なら「学生のページ」ボタンを出さない
 */
@Composable
internal fun ResultBrowser(
    state: UiState,
    viewModel: AppViewModel,
    groups: List<StudentGroup>,
    showStudentHeaders: Boolean,
    onOpenStudent: ((StudentId) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val outcome = state.outcome ?: return
    val view = state.resultView
    val interventions = remember(state.results) { state.results.interventionsByStudent() }
    // キー操作で移動する順 (一覧の表示順)
    val visible = remember(groups) { groups.flatMap { it.results } }
    // 選択中の結果がこの一覧に無ければ (別の学生の結果など)、詳細は出さない
    val selected = state.selected?.takeIf { s -> visible.any { it.id == s.id } }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    // キー操作・ボタンのコールバックは、常に最新の一覧と選択を見るようにする。
    // (ローカル関数の参照 ::f は、捕まえた値が違っても等しいとみなされるため、
    //  そのまま渡すと Compose が古いコールバックを使い続けてしまう)
    val latestVisible by rememberUpdatedState(visible)
    val latestSelectedId by rememberUpdatedState(selected?.id)

    val move: (Int) -> Unit = remember(viewModel) {
        { delta ->
            val list = latestVisible
            if (list.isNotEmpty()) {
                val index = list.indexOfFirst { it.id == latestSelectedId }
                val next = if (index < 0) 0 else (index + delta).coerceIn(0, list.lastIndex)
                viewModel.select(list[next].id)
            }
        }
    }

    val judgeAndAdvance: (Judge?) -> Unit = remember(viewModel) {
        { judge ->
            val list = latestVisible
            val currentId = latestSelectedId
            if (currentId != null) {
                val index = list.indexOfFirst { it.id == currentId }
                val nextId = list.getOrNull(index + 1)?.id ?: currentId
                viewModel.setManualJudge(currentId, judge)
                viewModel.select(nextId)
            }
        }
    }

    val onKey: (KeyEvent) -> Boolean = remember(viewModel) {
        handler@{ event ->
            if (event.type != KeyEventType.KeyDown) return@handler false
            if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@handler false
            when (event.key) {
                Key.DirectionDown, Key.J -> move(1)
                Key.DirectionUp, Key.K -> move(-1)
                Key.A -> judgeAndAdvance(Judge.AC)
                Key.W -> judgeAndAdvance(Judge.WA)
                Key.T -> judgeAndAdvance(Judge.TLE)
                Key.R -> judgeAndAdvance(Judge.RE)
                Key.C -> judgeAndAdvance(Judge.CE)
                Key.Backspace, Key.U -> latestSelectedId?.let { viewModel.setManualJudge(it, null) }
                else -> return@handler false
            }
            true
        }
    }

    Row(
        modifier
            .onKeyEvent(onKey)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        ResultList(
            groups = groups,
            interventions = interventions,
            showStudentHeaders = showStudentHeaders,
            collapsed = view.collapsed,
            onCollapsedChange = { c -> viewModel.updateResultView { it.copy(collapsed = c) } },
            collapsedTasks = view.collapsedTasks,
            onCollapsedTasksChange = { c -> viewModel.updateResultView { it.copy(collapsedTasks = c) } },
            selectedId = selected?.id,
            onOpenStudent = { id -> onOpenStudent?.invoke(id) },
            onSelect = {
                viewModel.select(it)
                runCatching { focusRequester.requestFocus() }
            },
            modifier = Modifier.width(340.dp).fillMaxHeight(),
        )
        VerticalDivider()
        if (selected == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Text(
                    "左の一覧から結果を選択してください",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            ResultDetail(
                result = selected,
                studentName = outcome.students.firstOrNull { it.id == selected.studentId }?.name.orEmpty(),
                viewModel = viewModel,
                onJudge = judgeAndAdvance,
                onMove = move,
                tasks = outcome.config.tasks.keys.toList(),
                assigning = state.assigningSource == selected.source.displayPath,
                canAssign = !state.isRunning && state.assigningSource == null,
                onOpenStudent = onOpenStudent?.let { open -> { open(selected.studentId) } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

internal class TaskGroup(val key: TaskGroupKey, val results: List<TestResult>)

internal class StudentGroup(
    val studentId: StudentId,
    val name: String,
    val tasks: List<TaskGroup>
) {
    /** 表示順 (課題の順) に並べた結果 */
    val results: List<TestResult> = tasks.flatMap { it.results }
}

/** 一覧の1行 (学生の見出し / 課題の見出し / 結果) */
private sealed interface ListEntry {
    val key: Any

    class Student(val group: StudentGroup) : ListEntry {
        override val key: Any get() = "student:${group.studentId.value}"
    }

    class Task(val group: TaskGroup) : ListEntry {
        override val key: Any get() = "task:${group.key.studentId.value}:${group.key.taskName?.name}"
    }

    class Result(val result: TestResult) : ListEntry {
        override val key: Any get() = result.id
    }
}

/** 開閉の状態に合わせて、一覧に並べる行を作る。[showStudentHeaders] が false なら学生の見出しを出さず、学生は開いた状態にする */
private fun buildEntries(
    groups: List<StudentGroup>,
    collapsed: Set<StudentId>,
    collapsedTasks: Set<TaskGroupKey>,
    showStudentHeaders: Boolean = true,
): List<ListEntry> = buildList {
    groups.forEach { group ->
        if (showStudentHeaders) add(ListEntry.Student(group))
        if (!showStudentHeaders || group.studentId !in collapsed) {
            group.tasks.forEach { task ->
                add(ListEntry.Task(task))
                if (task.key !in collapsedTasks) task.results.forEach { add(ListEntry.Result(it)) }
            }
        }
    }
}

private fun List<ListEntry>.indexOfStudent(id: StudentId): Int =
    indexOfFirst { it is ListEntry.Student && it.group.studentId == id }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResultList(
    groups: List<StudentGroup>,
    interventions: Map<StudentId, Intervention>,
    showStudentHeaders: Boolean,
    collapsed: Set<StudentId>,
    onCollapsedChange: (Set<StudentId>) -> Unit,
    collapsedTasks: Set<TaskGroupKey>,
    onCollapsedTasksChange: (Set<TaskGroupKey>) -> Unit,
    selectedId: Int?,
    onOpenStudent: (StudentId) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val entries = remember(groups, collapsed, collapsedTasks, showStudentHeaders) {
        buildEntries(groups, collapsed, collapsedTasks, showStudentHeaders)
    }

    // 選択中の結果が見えるようにスクロールする。
    // 開閉では動かさない (選択が変わったときと、一覧の中身が変わったときだけ)
    // タブを切り替えて戻ってきたときに再実行されないよう、最後にスクロールした条件を覚えておく
    val scrollKey =
        "$selectedId:" + groups.joinToString(",") { g -> "${g.studentId.value}/${g.results.size}" }
    var lastScrollKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(scrollKey) {
        if (scrollKey == lastScrollKey) return@LaunchedEffect
        lastScrollKey = scrollKey
        val selected =
            groups.firstNotNullOfOrNull { g -> g.results.firstOrNull { it.id == selectedId } }
                ?: return@LaunchedEffect
        // 選択した結果の学生・課題は自動で開かれるので、開いた状態として数える
        val target = buildEntries(
            groups,
            collapsed - selected.studentId,
            collapsedTasks - selected.taskGroupKey,
            showStudentHeaders
        )
            .indexOfFirst { it is ListEntry.Result && it.result.id == selectedId }
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val first = visibleItems.firstOrNull()?.index ?: 0
        val last = visibleItems.lastOrNull()?.index ?: 0
        // 見出しが上に貼り付くので、1つ余裕を持たせる
        if (target <= first + 1 || target >= last) listState.scrollToItem(
            (target - 3).coerceAtLeast(
                0
            )
        )
    }

    // 開閉で表示位置がずれないよう、開閉と同時に基準の見出しへの位置を指定する。
    // requestScrollToItem は次のレイアウト (= 開閉後の一覧) で適用される
    fun scrolledPast(index: Int): Boolean {
        val first = listState.firstVisibleItemIndex
        return index < first || (index == first && listState.firstVisibleItemScrollOffset > 0)
    }

    fun toggle(group: StudentGroup) {
        val id = group.studentId
        // 見出しの位置は自分より前の行だけで決まるので、開閉の前後で同じ
        val header = entries.indexOfStudent(id)
        // 見出しが上端に貼り付いている (中身の途中までスクロールしている) ときに閉じると、
        // 基準にしていた行が消えて位置が飛ぶ。閉じた後は見出しが上端に来るようにする
        if (scrolledPast(header)) listState.requestScrollToItem(header)
        onCollapsedChange(if (id in collapsed) collapsed - id else collapsed + id)
    }

    fun toggleTask(task: TaskGroup) {
        val header = entries.indexOfFirst { it is ListEntry.Task && it.group.key == task.key }
        // 課題の見出しより下までスクロールしているときは、学生の見出し (無ければ課題の見出し) を上端にする
        if (header >= 0 && scrolledPast(header)) {
            listState.requestScrollToItem(if (showStudentHeaders) entries.indexOfStudent(task.key.studentId) else header)
        }
        onCollapsedTasksChange(if (task.key in collapsedTasks) collapsedTasks - task.key else collapsedTasks + task.key)
    }

    fun toggleAll(collapseAll: Boolean) {
        // いま上端にいる学生の見出しが、開閉後も上端に来るようにする
        val anchor = entries.take(listState.firstVisibleItemIndex + 1)
            .lastOrNull { it is ListEntry.Student } as ListEntry.Student?
        val next = if (collapseAll) groups.map { it.studentId }.toSet() else emptySet()
        anchor?.let {
            listState.requestScrollToItem(
                buildEntries(
                    groups,
                    next,
                    collapsedTasks
                ).indexOfStudent(it.group.studentId)
            )
        }
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
                (if (showStudentHeaders) "学生 ${groups.size} 人 · " else "") + "結果 ${groups.sumOf { it.results.size }} 件",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
                    .padding(vertical = if (showStudentHeaders) 0.dp else 12.dp),
            )
            if (showStudentHeaders) {
                val allCollapsed = groups.all { it.studentId in collapsed }
                TextButton(onClick = { toggleAll(!allCollapsed) }) { Text(if (allCollapsed) "すべて開く" else "すべて閉じる") }
            }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                entries.forEach { entry ->
                    when (entry) {
                        // 学生の見出しは上端に貼り付ける (課題の見出しは貼り付けない。どの学生を見ているかを残すため)
                        is ListEntry.Student -> stickyHeader(key = entry.key) {
                            val group = entry.group
                            StudentHeader(
                                group = group,
                                intervention = interventions[group.studentId],
                                collapsed = group.studentId in collapsed,
                                containsSelection = group.results.any { it.id == selectedId },
                                onClick = { toggle(group) },
                                onOpenStudent = { onOpenStudent(group.studentId) },
                            )
                        }

                        is ListEntry.Task -> item(key = entry.key) {
                            val task = entry.group
                            TaskHeader(
                                task = task,
                                collapsed = task.key in collapsedTasks,
                                containsSelection = task.results.any { it.id == selectedId },
                                onClick = { toggleTask(task) },
                            )
                        }

                        is ListEntry.Result -> item(key = entry.key) {
                            ResultRow(
                                entry.result,
                                selected = entry.result.id == selectedId,
                                onClick = { onSelect(entry.result.id) })
                        }
                    }
                }
            }
            AppVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

/** 課題の見出し。判定ごとの件数を並べる。クリックで開閉 */
@Composable
private fun TaskHeader(
    task: TaskGroup,
    collapsed: Boolean,
    containsSelection: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
            contentDescription = if (collapsed) "開く" else "閉じる",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            task.key.taskName?.name ?: "(課題不明)",
            style = MaterialTheme.typography.labelLarge,
            color = if (collapsed && containsSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (task.results.any { it.assigned }) {
            Text(
                "(割り当て)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.weight(1f))
        val counts = task.results.groupingBy { it.judge }.eachCount()
        Judge.entries.filter { it in counts }.forEach { judge ->
            JudgeBadge(judge)
            Text(
                "${counts[judge]}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 学生の見出し。判定ごとの件数を並べる。クリックで開閉 */
@Composable
private fun StudentHeader(
    group: StudentGroup,
    intervention: Intervention?,
    collapsed: Boolean,
    containsSelection: Boolean,
    onClick: () -> Unit,
    onOpenStudent: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick)
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                contentDescription = if (collapsed) "開く" else "閉じる",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "${group.studentId}  ${group.name}",
                        style = MaterialTheme.typography.titleSmall,
                        // 閉じていても、選択中の結果を含む学生はわかるようにする
                        color = if (collapsed && containsSelection) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    // 手動判定・課題の割り当てをした学生 (絞り込みで隠れた結果も数える)
                    InterventionMarks(intervention)
                }
                val counts = group.results.groupingBy { it.judge }.eachCount()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
            TooltipIconButton("学生のページ (提出ファイル)", Icons.Filled.Person, onOpenStudent)
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
            .padding(start = 44.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        JudgeBadge(
            result.judge,
            manual = result.manualJudge != null,
            modifier = Modifier.width(48.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                result.testCase?.name ?: when (result.judge) {
                    Judge.NF -> "課題を特定できません"
                    Judge.CE -> "コンパイルエラー"
                    Judge.IE -> "内部エラー"
                    else -> "(テストケースなし)"
                },
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
    tasks: List<TaskName>,
    assigning: Boolean,
    canAssign: Boolean,
    onOpenStudent: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val source by produceState<String?>(null, result.source.path) {
        value = null
        value = viewModel.readSource(result)
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

    Box(modifier) {
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp),
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
                if (onOpenStudent != null) {
                    TextButton(onClick = onOpenStudent) { Text("学生のページ") }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedButton(onClick = { onMove(-1) }) { Text("前へ") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onMove(1) }) { Text("次へ") }
            }

            // ---- 課題の割り当て
            if (result.taskName == null) {
                AssignTaskCard(
                    result,
                    tasks,
                    assigning,
                    canAssign,
                    onAssign = { viewModel.assignTask(result.id, it) })
            } else if (result.assigned) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "課題「${result.taskName}」は手動で割り当てました",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { viewModel.unassignTask(result.id) },
                        enabled = canAssign
                    ) { Text("割り当てを解除") }
                }
            }

            // ---- 判定
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("判定", style = MaterialTheme.typography.labelLarge)
                JudgeBadge(result.judge, manual = result.manualJudge != null)
                Text(
                    if (result.manualJudge != null) "(手動 / 自動判定は ${result.autoJudge})" else "(自動判定)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Judge.manualChoices.forEach { judge ->
                    val (background, foreground) = judgeColors(judge)
                    val active = result.manualJudge == judge
                    if (active) {
                        Button(
                            onClick = { onJudge(judge) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = foreground,
                                contentColor = background
                            ),
                        ) { Text(judge.label) }
                    } else {
                        OutlinedButton(onClick = { onJudge(judge) }) {
                            Text(
                                judge.label,
                                color = foreground
                            )
                        }
                    }
                }
                TextButton(
                    onClick = { viewModel.setManualJudge(result.id, null) },
                    enabled = result.manualJudge != null
                ) {
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
                            Text(
                                label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(90.dp)
                            )
                            Text(value, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (testCase != null) {
                if (testCase.input.isNotEmpty()) TextBlock(
                    "標準入力",
                    testCase.input.joinToString("\n")
                )
                TextBlock("想定 (正規表現)", testCase.expect.joinToString("\n"))
            }
            if (execution != null) {
                TextBlock("標準出力", execution.stdout.trimEnd())
                if (execution.stderr.isNotBlank()) TextBlock(
                    "標準エラー出力",
                    execution.stderr.trimEnd()
                )
            }
            when (val compile = result.compile) {
                is CompileOutcome.Failure -> TextBlock(
                    "コンパイルエラー",
                    compile.message.trimEnd()
                )

                is CompileOutcome.Error -> TextBlock(
                    "コンパイル時の内部エラー",
                    compile.message.trimEnd()
                )

                is CompileOutcome.Success -> if (compile.warnings.isNotBlank()) TextBlock(
                    "コンパイル時の警告",
                    compile.warnings.trimEnd()
                )
            }
            if (result.note.isNotBlank()) TextBlock("内部エラー", result.note.trimEnd())

            HorizontalDivider()
            // ソースは常に表示する (長いソースでも埋もれないよう、エディタで開くボタンは先に置く)
            TextButton(onClick = { viewModel.openPath(result.source.path) }) { Text("エディタで開く") }
            TextBlock(result.source.fileName, source ?: "読み込み中…")
        }
        AppVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

/** 課題を特定できなかったソースに、課題を割り当てる */
@Composable
private fun AssignTaskCard(
    result: TestResult,
    tasks: List<TaskName>,
    assigning: Boolean,
    enabled: Boolean,
    onAssign: (TaskName) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("課題を特定できませんでした", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (assigning) {
                        "割り当てた課題のテストを実行しています…"
                    } else {
                        "ソースにどの課題の検索ワードも含まれていません。課題を割り当てると、その課題のテストを実行します。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (assigning) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                Box {
                    FilledTonalButton(
                        onClick = { menuOpen = true },
                        enabled = enabled && tasks.isNotEmpty()
                    ) {
                        Text("課題を割り当て")
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        tasks.forEach { task ->
                            DropdownMenuItem(
                                text = { Text(task.name) },
                                onClick = {
                                    menuOpen = false
                                    onAssign(task)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
