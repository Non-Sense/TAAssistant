package com.n0n5ense.taassistant.core.project

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Execution
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.report.Csv
import com.n0n5ense.taassistant.core.report.ReportFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectTest {
    private val config = ConfigParser.parse(
        """
        version: 2
        tasks:
          課題1:
            word: [課題1]
            testcase:
              - name: t1
                arg: "1 2"
                expect: ['^3$']
              - name: t2
                expect: ['x']
        """.trimIndent(),
    )
    private val student = Student(StudentId("s1"), "山田", "/w/s1")
    private val main = SourceFile(student.id, "/w/s1/sources/Main.java", "s1/sources/Main.java")
    private val other = SourceFile(student.id, "/w/s1/sources/Other.java", "s1/sources/Other.java")
    private val success = CompileOutcome.Success("javac", SourceEncoding.UTF8, "pkg", "Main", "/w/s1/compile")
    private val failure = CompileOutcome.Failure("javac", null, null, "error: x")
    private val t1 = config.tasks.getValue(TaskName("課題1")).testcase[0]
    private val t2 = config.tasks.getValue(TaskName("課題1")).testcase[1]

    private fun result(id: Int, source: SourceFile, testCase: TestCase?, judge: Judge, manual: Judge? = null) = TestResult(
        id = id,
        source = source,
        compile = success,
        taskName = if (judge == Judge.NF) null else TaskName("課題1"),
        testCase = testCase,
        autoJudge = judge,
        manualJudge = manual,
        execution = testCase?.let { Execution("java Main", "3\n", "", 0, false, 1.5) },
    )

    @Test
    fun roundTrip() {
        val results = listOf(
            result(0, main, t1, Judge.AC),
            result(1, main, t2, Judge.WA, manual = Judge.AC),
            result(2, other, null, Judge.NF).copy(compile = failure),
        )
        val outcome = GradingOutcome(
            config = config,
            workspaceDir = "/w",
            lastStep = LastStep.Test,
            students = listOf(student),
            compiled = listOf(CompiledSource(main, success), CompiledSource(other, failure)),
            results = results,
        )
        val project = ProjectFile(
            settings = ProjectSettings(rootDir = "submissions", configPath = "config.yaml", reportFormat = ReportFormat.Csv),
            assignments = listOf(TaskAssignment("s1/sources/Other.java", listOf(TaskName("課題1")))),
            outcome = outcome,
        )
        val bytes = ProjectCodec.encode(project)
        assertTrue(bytes.isGzip(), "gzip で圧縮して書き出す")
        val json = gunzip(bytes).decodeToString()
        assertTrue(json.startsWith("{\"format\":\"ta-assistant-project\""), "インデントなしの JSON")
        assertFalse("\n" in json)
        val decoded = ProjectCodec.decode(bytes)
        assertEquals(project, decoded)
        // 正規表現などの transient な値も復元される
        assertEquals(listOf("^3$"), decoded.outcome!!.config.tasks.values.first().testcase[0].expectRegex.map { it.pattern })
        // 圧縮していない JSON (BOM 付きでも) も読める
        assertEquals(project, ProjectCodec.decode(json.encodeToByteArray()))
        assertEquals(project, ProjectCodec.decode((Char(0xFEFF) + json).encodeToByteArray()))
    }

    @Test
    fun rejectsOtherFilesAndNewerVersions() {
        fun decode(text: String) = ProjectCodec.decode(text.encodeToByteArray())
        assertFailsWith<IllegalArgumentException> { decode("not json") }
        assertFailsWith<IllegalArgumentException> { decode("""{"format": "other", "version": 1}""") }
        // 壊れた gzip
        assertFailsWith<IllegalArgumentException> { ProjectCodec.decode(byteArrayOf(0x1f, 0x8b.toByte(), 1, 2, 3)) }
        val newer = assertFailsWith<IllegalArgumentException> {
            decode("""{"format": "${ProjectCodec.FORMAT}", "version": 99}""")
        }
        assertTrue("新しいバージョン" in newer.message.orEmpty())
        // 最小限の内容でも読める
        val minimal = decode("""{"format": "${ProjectCodec.FORMAT}", "version": 1}""")
        assertNull(minimal.outcome)
        assertEquals(ProjectSettings(), minimal.settings)
    }

    @Test
    fun assignAndUnassign() {
        val nf = result(5, other, null, Judge.NF)
        val results = listOf(result(3, main, t1, Judge.AC), nf, result(4, main, t2, Judge.AC))
        val tested = listOf(result(0, other, t1, Judge.AC), result(0, other, t2, Judge.WA))

        val assigned = results.replaceWithAssigned(nf, tested)
        assertEquals(listOf(3, 6, 7, 4), assigned.map { it.id }, "NF の位置に挿入し、id を振り直す")
        assertEquals(listOf(false, true, true, false), assigned.map { it.assigned })
        assertTrue(assigned.none { it.judge == Judge.NF })

        val restored = assigned.removeAssigned(other)
        assertEquals(3, restored.size)
        val back = restored[1]
        assertEquals(Judge.NF, back.judge)
        assertNull(back.taskName)
        assertEquals(other, back.source)
        assertFalse(back.assigned)
        assertEquals(8, back.id)
    }

    @Test
    fun carriesOverManualJudgesForUnchangedTestCases() {
        val previous = listOf(
            result(0, main, t1, Judge.WA, manual = Judge.AC),
            result(1, main, t2, Judge.WA, manual = Judge.AC),
        )
        val changedT2 = t2.copy(expect = listOf("y"))
        val current = listOf(result(0, main, t1, Judge.WA), result(1, main, changedT2, Judge.WA))
        val (merged, count) = carryOverManualJudges(previous, current)
        assertEquals(1, count)
        assertEquals(Judge.AC, merged[0].manualJudge)
        assertNull(merged[1].manualJudge, "想定出力が変わったテストケースには引き継がない")
    }
}
