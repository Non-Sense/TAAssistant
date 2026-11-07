package com.n0n5ense.taassistant.core

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.SubmissionDirName
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.config.parseSubmissionDirName
import com.n0n5ense.taassistant.core.grading.AutoJudge
import com.n0n5ense.taassistant.core.grading.detectTasks
import com.n0n5ense.taassistant.core.grading.parsePackageName
import com.n0n5ense.taassistant.core.grading.splitArguments
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.Execution
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.report.Csv
import com.n0n5ense.taassistant.core.report.buildSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoreLogicTest {

    private val yaml = """
        version: 2
        compileTimeout: 3000
        runningTimeout: 5000
        outputFileName: output
        tasks:
          課題1:
            word:
              - 課題1
              - kadai1
            excludeWord:
              - "前回課題"
            testcase:
              - name: test1
                arg: "1 10"
                input:
                  - "1 2 3 4"
                  - "5 5"
                expect:
                  - "^.*(?<![-\\d])45(?!\\d).*"
              - name: test2
                arg: "5 30"
                expect:
                  - "^.*(?<![-\\d])425(?!\\d).*"
          課題2:
            word:
              - 課題2
            testcase:
              - name: sample
                arg: "1 4 2"
                expect:
                  - "1\\s*\\+\\s*4\\s*\\+\\s*2\\s*=\\s*7.*"
    """.trimIndent()

    private val config = ConfigParser.parse(yaml)
    private val task1 = TaskName("課題1")
    private val task2 = TaskName("課題2")

    @Test
    fun parsesConfig() {
        assertEquals(listOf(task1, task2), config.tasks.keys.toList())
        val test1 = config.tasks.getValue(task1).testcase.first()
        assertEquals("1 10", test1.arg)
        assertEquals(listOf("1 2 3 4", "5 5"), test1.input)
        assertEquals(3, config.testCaseCount)
    }

    @Test
    fun parsesSubmissionDirNameWithDefaultPattern() {
        assertEquals(
            SubmissionDirName("t100d400", "山田 太郎"),
            config.parseSubmissionDirName("t100d400 山田 太郎_123456_assignsubmission_file_"),
        )
        assertEquals(
            SubmissionDirName("t100d400", "佐藤"),
            config.parseSubmissionDirName("t100d400 佐藤_1_assignsubmission_file"),
        )
        assertNull(config.parseSubmissionDirName("t100d400 佐藤_1_assignsubmission_onlinetext_"))
        assertNull(config.parseSubmissionDirName("workspace"))
    }

    @Test
    fun parsesSubmissionDirNameWithCustomPattern() {
        val named = ConfigParser.parse(
            """
            version: 2
            submissionDirPattern: '^(?<name>.+?)_(?<id>\d{8})$'
            tasks: {}
            """.trimIndent(),
        )
        assertEquals(
            SubmissionDirName("12345678", "山田"),
            named.parseSubmissionDirName("山田_12345678")
        )
        assertNull(named.parseSubmissionDirName("山田_1234"))

        // 名前付きグループが無ければ 1番目 = 学籍番号, 2番目 = 氏名
        val positional =
            ConfigParser.parse("version: 2\nsubmissionDirPattern: '(\\w+)-(.*)'\ntasks: {}")
        assertEquals(
            SubmissionDirName("s01", "Alice"),
            positional.parseSubmissionDirName("s01-Alice")
        )

        // 氏名グループは省略可能
        val idOnly =
            ConfigParser.parse("version: 2\nsubmissionDirPattern: '(?<id>s\\d+)'\ntasks: {}")
        assertEquals(SubmissionDirName("s01", ""), idOnly.parseSubmissionDirName("s01"))
    }

    @Test
    fun detectsTasksWithExcludeWords() {
        assertEquals(listOf(task1), detectTasks(config, "// 課題1\nclass A {}"))
        assertEquals(listOf(task1, task2), detectTasks(config, "// kadai1 と 課題2"))
        assertEquals(emptyList(), detectTasks(config, "// 課題1 (前回課題を流用)"))
        assertEquals(emptyList(), detectTasks(config, "class Nothing {}"))
    }

    @Test
    fun parsesPackageName() {
        assertEquals("jp.ac.example", parsePackageName("\uFEFFpackage jp.ac.example;\nclass A {}"))
        assertEquals(
            "a.b",
            parsePackageName("/* header\npackage x.y;\n*/\npackage a.b ; // comment")
        )
        assertNull(parsePackageName("// package x.y;\nclass A {}"))
    }

    @Test
    fun splitsArguments() {
        assertEquals(listOf("1", "10"), splitArguments(" 1   10 "))
        assertEquals(listOf("hello world", "x"), splitArguments("\"hello world\" x"))
        assertEquals(listOf(""), splitArguments("\"\""))
        assertEquals(emptyList(), splitArguments(""))
    }

    @Test
    fun judgesAutomatically() {
        val test1 = config.tasks.getValue(task1).testcase.first()
        fun exec(stdout: String, stderr: String = "", timedOut: Boolean = false) =
            Execution("java A", stdout, stderr, if (timedOut) null else 0, timedOut, 10.0)

        assertEquals(Judge.AC, AutoJudge.judge(test1, exec("sum\r\n45\r\n")))
        assertEquals(Judge.WA, AutoJudge.judge(test1, exec("145")))
        assertEquals(
            Judge.RE,
            AutoJudge.judge(
                test1,
                exec("45", "Exception in thread \"main\" java.lang.RuntimeException")
            )
        )
        assertEquals(Judge.TLE, AutoJudge.judge(test1, exec("45", timedOut = true)))
    }

    @Test
    fun buildsSummaryWithConflictsAndMissing() {
        val (t1, t2) = config.tasks.getValue(task1).testcase
        val sample = config.tasks.getValue(task2).testcase.single()
        val alice = StudentId("a0000001")
        val bob = StudentId("b0000002")
        val carol = StudentId("c0000003")
        val students = listOf(
            Student(alice, "Alice", "/ws/a"),
            Student(bob, "Bob", "/ws/b"),
            Student(carol, "Carol", "/ws/c"),
        )

        fun src(id: StudentId, name: String) = SourceFile(id, "/ws/$id/$name", "$id/sources/$name")
        val ok = CompileOutcome.Success("javac", SourceEncoding.UTF8, null, "A", "/c")
        val ng = CompileOutcome.Failure("javac", SourceEncoding.UTF8, null, "error")
        var id = 0
        fun result(
            source: SourceFile,
            task: TaskName?,
            case: TestCase?,
            judge: Judge,
            compile: CompileOutcome = ok
        ) =
            TestResult(id++, source, compile, task, case, judge)

        val results = listOf(
            // Alice: 課題1 を1ファイルで提出、test1 だけ AC。課題2 は2ファイルで提出 → CF
            result(src(alice, "A.java"), task1, t1, Judge.AC),
            result(src(alice, "A.java"), task1, t2, Judge.WA),
            result(src(alice, "B.java"), task2, sample, Judge.AC),
            result(src(alice, "C.java"), task2, sample, Judge.AC),
            // Bob: 課題1 がコンパイルエラー
            result(src(bob, "A.java"), task1, null, Judge.CE, ng),
        ).toMutableList()
        // 手動判定は集計に反映される
        results[1] = results[1].copy(manualJudge = Judge.AC)

        val summary = buildSummary(config, students, results)
        assertEquals(
            listOf(
                "ID",
                "Name",
                "課題1",
                "課題2",
                "課題1:test1",
                "課題1:test2",
                "課題2:sample"
            ), summary.header
        )

        val (a, b, c) = summary.rows
        assertEquals(listOf(100, 0), a.acceptRatios)
        assertEquals(listOf(Judge.AC, Judge.AC, Judge.CF), a.stats)
        assertEquals(listOf(Judge.CE, Judge.CE, Judge.NF), b.stats)
        // 提出が無い学生もサマリーに出る
        assertEquals(carol, c.studentId)
        assertEquals(listOf(Judge.NF, Judge.NF, Judge.NF), c.stats)
    }

    @Test
    fun escapesCsv() {
        assertEquals("plain", Csv.escape("plain"))
        assertEquals("\"a,b\"", Csv.escape("a,b"))
        assertEquals("\"say \"\"hi\"\"\nnext\"", Csv.escape("say \"hi\"\nnext"))
        assertEquals("1.235", Csv.escape(1.23456))
        assertEquals("", Csv.escape(null))
    }
}
