package com.n0n5ense.taassistant.core.report

import com.n0n5ense.taassistant.core.config.Config
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.model.packageName
import kotlin.math.roundToLong

/** 結果ファイルに書き出す内容 (形式非依存) */
data class Report(
    val details: List<DetailRow>,
    val summary: SummaryTable,
)

fun buildReport(config: Config, students: List<Student>, results: List<TestResult>): Report {
    val names = students.associate { it.id to it.name }
    return Report(
        details = results.map { DetailRow.of(it, names) },
        summary = buildSummary(config, students, results),
    )
}

// ---------------------------------------------------------------- Detail

data class DetailRow(
    val studentId: String,
    val studentName: String,
    val sourcePath: String,
    val taskName: String,
    val testCaseName: String,
    val arg: String,
    val input: String,
    val stat: Judge,
    val timeMillis: Double?,
    val expect: String,
    val stdout: String,
    val stderr: String,
    val compileError: String,
    val packageName: String,
    val className: String,
    val runCommand: String,
    val compileCommand: String,
) {
    /** 各セルの値。String / Double / null(空セル) */
    fun cells(): List<Any?> = listOf(
        studentId,
        studentName,
        sourcePath,
        taskName,
        testCaseName,
        arg,
        input,
        stat.label,
        timeMillis,
        expect,
        stdout,
        stderr,
        compileError,
        packageName,
        className,
        runCommand,
        compileCommand,
    )

    companion object {
        val header: List<String> = listOf(
            "studentID",
            "studentName",
            "sourcePath",
            "taskName",
            "testCase",
            "arg",
            "input",
            "stat",
            "time(ms)",
            "expect",
            "stdout",
            "stderr",
            "compileError",
            "package",
            "class",
            "runCommand",
            "compileCommand",
        )
        val statColumn: Int = header.indexOf("stat")
        val wrapColumns: Set<Int> =
            setOf("stdout", "stderr", "compileError").map(header::indexOf).toSet()

        fun of(result: TestResult, studentNames: Map<StudentId, String>): DetailRow {
            val compile = result.compile
            val execution = result.execution
            return DetailRow(
                studentId = result.studentId.value,
                studentName = studentNames[result.studentId].orEmpty(),
                sourcePath = result.source.displayPath,
                taskName = result.taskName?.name.orEmpty(),
                testCaseName = result.testCase?.name.orEmpty(),
                arg = result.testCase?.arg.orEmpty(),
                input = result.testCase?.input?.joinToString("\n").orEmpty(),
                stat = result.judge,
                timeMillis = execution?.elapsedMillis,
                expect = result.testCase?.expect?.joinToString("\n").orEmpty(),
                stdout = execution?.stdout.orEmpty().normalizeNewlines().trimEnd(),
                stderr = listOf(execution?.stderr.orEmpty(), result.note)
                    .filter(String::isNotBlank)
                    .joinToString("\n")
                    .normalizeNewlines()
                    .trimEnd(),
                compileError = when (compile) {
                    is CompileOutcome.Success -> ""
                    is CompileOutcome.Failure -> compile.message
                    is CompileOutcome.Error -> "InternalError:\n${compile.message}"
                }.normalizeNewlines().trimEnd(),
                packageName = compile.packageName.orEmpty(),
                className = (compile as? CompileOutcome.Success)?.className.orEmpty(),
                runCommand = execution?.command.orEmpty(),
                compileCommand = compile.command,
            )
        }
    }
}

private fun String.normalizeNewlines(): String = replace("\r\n", "\n").replace('\r', '\n')

// ---------------------------------------------------------------- Summary

data class SummaryColumn(val taskName: TaskName, val testCase: TestCase) {
    val title: String get() = "$taskName:${testCase.name}"
}

data class SummaryRow(
    val studentId: StudentId,
    val studentName: String,
    /** 課題ごとの正答率(%) */
    val acceptRatios: List<Int>,
    /** テストケースごとの判定 */
    val stats: List<Judge>,
)

data class SummaryTable(
    val taskNames: List<TaskName>,
    val columns: List<SummaryColumn>,
    val rows: List<SummaryRow>,
) {
    val header: List<String>
        get() = listOf("ID", "Name") + taskNames.map { it.name } + columns.map { it.title }
}

/**
 * 学生 × テストケースの集計表を作る。
 * - 1つのテストケースに対して結果が複数(複数ファイルが同じ課題と判定された)なら CF
 * - テストを実行していない課題(CE など)はその判定
 * - 何もなければ NF
 */
fun buildSummary(config: Config, students: List<Student>, results: List<TestResult>): SummaryTable {
    val taskNames = config.tasks.keys.toList()
    val columns =
        config.tasks.flatMap { (name, task) -> task.testcase.map { SummaryColumn(name, it) } }
    val names = students.associate { it.id to it.name }
    val resultsByStudent = results.groupBy { it.studentId }
    val studentIds =
        (students.map { it.id } + resultsByStudent.keys).distinct().sortedBy { it.value }

    val rows = studentIds.map { id ->
        val byKey = resultsByStudent[id].orEmpty().groupBy { it.taskName to it.testCase }
        val stats = columns.map { column ->
            summarize(byKey[column.taskName to column.testCase], byKey[column.taskName to null])
        }
        val ratios = taskNames.map { task ->
            val taskColumns = columns.filter { it.taskName == task }
            if (taskColumns.isEmpty()) {
                0
            } else {
                val accepted = taskColumns.count { column ->
                    byKey[column.taskName to column.testCase].orEmpty()
                        .count { it.judge == Judge.AC } == 1
                }
                accepted * 100 / taskColumns.size
            }
        }
        SummaryRow(id, names[id].orEmpty(), ratios, stats)
    }
    return SummaryTable(taskNames, columns, rows)
}

private fun summarize(perTestCase: List<TestResult>?, perTask: List<TestResult>?): Judge = when {
    perTestCase != null && perTestCase.size == 1 -> perTestCase.single().judge
    perTestCase != null && perTestCase.size > 1 -> Judge.CF
    !perTask.isNullOrEmpty() -> perTask.first().judge
    else -> Judge.NF
}

// ---------------------------------------------------------------- CSV

object Csv {
    private const val NEWLINE = "\r\n"

    fun detail(rows: List<DetailRow>): String = buildString {
        append(line(DetailRow.header)).append(NEWLINE)
        rows.forEach { append(line(it.cells())).append(NEWLINE) }
    }

    fun summary(table: SummaryTable): String = buildString {
        append(line(table.header)).append(NEWLINE)
        table.rows.forEach { row ->
            val cells = listOf(
                row.studentId.value,
                row.studentName
            ) + row.acceptRatios + row.stats.map { it.label }
            append(line(cells)).append(NEWLINE)
        }
    }

    fun line(cells: List<Any?>): String = cells.joinToString(",", transform = ::escape)

    fun escape(value: Any?): String {
        val s = when (value) {
            null -> ""
            is Double -> formatMillis(value)
            else -> value.toString()
        }
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else {
            s
        }
    }
}

fun formatMillis(ms: Double): String = ((ms * 1000).roundToLong() / 1000.0).toString()

/** 結果ファイルの形式 */
enum class ReportFormat(val label: String) {
    Xlsx("Excel (.xlsx)"),
    Csv("CSV (UTF-8)"),
    CsvShiftJis("CSV (Shift_JIS)"),
}
