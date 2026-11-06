package com.n0n5ense.taassistant.ui.common

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.TestResult

/** 判定ごとの (背景色, 文字色)。Excel 出力の条件付き書式と同じ配色 */
fun judgeColors(judge: Judge): Pair<Color, Color> = when (judge) {
    Judge.AC -> Color(0xFFC6EFCE) to Color(0xFF006100)
    Judge.WA, Judge.TLE -> Color(0xFFFFEB9C) to Color(0xFF9C5700)
    Judge.RE, Judge.CE -> Color(0xFFFFC7CE) to Color(0xFF9C0006)
    Judge.IE -> Color(0xFFFFFFFF) to Color(0xFF000000)
    Judge.NF -> Color(0xFFBFBFBF) to Color(0xFF000000)
    Judge.CF -> Color(0xFFD9D2E9) to Color(0xFF3F3151)
    Judge.Unknown -> Color(0xFFEEEEEE) to Color(0xFF555555)
}

/** 判定のバッジ。手動判定は鉛筆アイコンを付けて区別する */
@Composable
fun JudgeBadge(judge: Judge, modifier: Modifier = Modifier, manual: Boolean = false) {
    val (background, foreground) = judgeColors(judge)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = background,
    ) {
        Row(
            Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = judge.label,
                color = foreground,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            if (manual) {
                Icon(Icons.Filled.Edit, contentDescription = "手動判定", tint = foreground, modifier = Modifier.size(10.dp))
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
fun PathField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onBrowse: () -> Unit,
    enabled: Boolean,
    hint: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(label) },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onBrowse, enabled = enabled) { Text("参照") }
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

@Composable
fun RadioRow(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** ラベル付きの等幅テキストブロック (選択・コピー可能) */
@Composable
fun TextBlock(title: String, text: String, modifier: Modifier = Modifier, emptyText: String = "(なし)") {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // 長い行は折り返さず、横にスクロールする (ソースや出力の見た目を崩さない)
            val horizontal = rememberScrollState()
            Column {
                Box(Modifier.fillMaxWidth().horizontalScroll(horizontal)) {
                    SelectionContainer {
                        Text(
                            text = text.ifEmpty { emptyText },
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            softWrap = false,
                            color = if (text.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
                AppHorizontalScrollbar(horizontal, Modifier.fillMaxWidth())
            }
        }
    }
}

internal class MenuEntry(
    val label: String,
    val icon: ImageVector? = null,
    val shortcut: String? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
    val onClick: () -> Unit,
)

/** 使用頻度の低い操作をまとめるオーバーフローメニュー (⋮) */
@Composable
internal fun OverflowMenu(description: String, entries: List<MenuEntry>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(description, Icons.Filled.MoreVert, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            entries.forEach { entry ->
                val color = if (entry.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                DropdownMenuItem(
                    text = { Text(entry.label, color = if (entry.enabled) color else color.copy(alpha = 0.38f)) },
                    leadingIcon = entry.icon?.let { icon -> { Icon(icon, contentDescription = null, tint = if (entry.enabled) color else color.copy(alpha = 0.38f)) } },
                    trailingIcon = entry.shortcut?.let { { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    enabled = entry.enabled,
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

/** アイコンだけのボタンには、何をするかをツールチップで示す */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TooltipIconButton(tooltip: String, icon: ImageVector, onClick: () -> Unit, enabled: Boolean = true) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = tooltip) }
    }
}

/** 学生ごとの手動介入 (手動判定・課題の割り当て) の件数 */
data class Intervention(val manualJudges: Int, val assignedSources: Int) {
    val any: Boolean get() = manualJudges > 0 || assignedSources > 0
}

fun List<TestResult>.interventionsByStudent(): Map<StudentId, Intervention> =
    groupBy { it.studentId }.mapValues { (_, results) ->
        Intervention(
            manualJudges = results.count { it.manualJudge != null },
            assignedSources = results.filter { it.assigned }.map { it.source }.distinct().size,
        )
    }.filterValues { it.any }

/** 手動判定・課題の割り当てをした学生に付ける印 */
@Composable
fun InterventionMarks(intervention: Intervention?, modifier: Modifier = Modifier) {
    if (intervention == null || !intervention.any) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (intervention.manualJudges > 0) {
            MarkChip(
                "手動 ${intervention.manualJudges}",
                Icons.Filled.Edit,
                MaterialTheme.colorScheme.tertiaryContainer,
                MaterialTheme.colorScheme.onTertiaryContainer,
                description = "手動判定 ${intervention.manualJudges} 件",
            )
        }
        if (intervention.assignedSources > 0) {
            MarkChip(
                "割当 ${intervention.assignedSources}",
                Icons.Filled.Build,
                MaterialTheme.colorScheme.secondaryContainer,
                MaterialTheme.colorScheme.onSecondaryContainer,
                description = "課題を割り当てたソース ${intervention.assignedSources} 件",
            )
        }
    }
}

@Composable
private fun MarkChip(text: String, icon: ImageVector, container: Color, content: Color, description: String) {
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Row(
            Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(11.dp))
            Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
    }
}
