package com.n0n5ense.taassistant.ui.results

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.n0n5ense.taassistant.core.report.SummaryColumn
import com.n0n5ense.taassistant.core.report.SummaryRow
import com.n0n5ense.taassistant.core.report.buildSummary
import com.n0n5ense.taassistant.ui.common.AppHorizontalScrollbar
import com.n0n5ense.taassistant.ui.common.AppVerticalScrollbar
import com.n0n5ense.taassistant.ui.common.Intervention
import com.n0n5ense.taassistant.ui.common.InterventionMarks
import com.n0n5ense.taassistant.ui.common.JudgeBadge
import com.n0n5ense.taassistant.ui.common.interventionsByStudent
import com.n0n5ense.taassistant.ui.main.UiState

private val idWidth = 100.dp
private val nameWidth = 130.dp
private val interventionWidth = 150.dp
private val ratioWidth = 90.dp
private val statWidth = 96.dp

@Composable
internal fun SummaryPanel(state: UiState, onOpenResult: (Int) -> Unit, modifier: Modifier = Modifier) {
    val outcome = state.outcome
    if (outcome == null || state.results.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("まだ結果はありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val table = remember(outcome, state.results) { buildSummary(outcome.config, outcome.students, state.results) }
    val horizontal = rememberScrollState()
    val listState = rememberLazyListState()
    val interventions = remember(state.results) { state.results.interventionsByStudent() }

    fun openCell(row: SummaryRow, column: SummaryColumn) {
        val candidates = state.results.filter { it.studentId == row.studentId && it.taskName == column.taskName }
        val target = candidates.firstOrNull { it.testCase == column.testCase } ?: candidates.firstOrNull()
        target?.let { onOpenResult(it.id) }
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .horizontalScroll(horizontal)
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderCell("ID", idWidth)
            HeaderCell("Name", nameWidth)
            HeaderCell("手動介入", interventionWidth)
            table.taskNames.forEach { HeaderCell("${it.name} (%)", ratioWidth) }
            table.columns.forEach { HeaderCell(it.title, statWidth) }
        }
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                items(table.rows, key = { it.studentId.value }) { row ->
                    SummaryRowView(row, interventions[row.studentId], table.columns, horizontal) { column -> openCell(row, column) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
            AppVerticalScrollbar(listState, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        AppHorizontalScrollbar(horizontal, Modifier.fillMaxWidth())
        Text(
            "${table.rows.size} 人  ·  判定セルをクリックすると詳細を開きます",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(width).padding(horizontal = 8.dp),
    )
}

@Composable
private fun SummaryRowView(
    row: SummaryRow,
    intervention: Intervention?,
    columns: List<SummaryColumn>,
    horizontal: ScrollState,
    onCellClick: (SummaryColumn) -> Unit,
) {
    Row(
        Modifier.horizontalScroll(horizontal).height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(row.studentId.value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(idWidth).padding(horizontal = 8.dp))
        Text(
            row.studentName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(nameWidth).padding(horizontal = 8.dp),
        )
        Box(Modifier.width(interventionWidth).padding(horizontal = 8.dp)) {
            InterventionMarks(intervention)
        }
        row.acceptRatios.forEach { ratio -> RatioCell(ratio) }
        row.stats.forEachIndexed { index, judge ->
            Box(
                Modifier.width(statWidth).fillMaxHeight().clickable { onCellClick(columns[index]) }.padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                JudgeBadge(judge)
            }
        }
    }
}

@Composable
private fun RatioCell(ratio: Int) {
    Box(Modifier.width(ratioWidth).padding(horizontal = 8.dp, vertical = 6.dp).fillMaxHeight()) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(ratio.coerceIn(0, 100) / 100f)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        )
        Text(
            "$ratio",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
        )
    }
}
