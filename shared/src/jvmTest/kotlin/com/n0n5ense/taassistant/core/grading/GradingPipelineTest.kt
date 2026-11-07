package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.SubmissionDirName
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Execution
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.SuspiciousCall
import com.n0n5ense.taassistant.core.report.Report
import com.n0n5ense.taassistant.core.report.ReportFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * [GradingPipeline] の振る舞い (判定・結果の順序と id・ログ・進み具合・課題の割り当て) を固定するテスト。
 * javac / java は使わず、[FakeToolkit] で展開・コンパイル・実行の結果を与える。
 */
class GradingPipelineTest {

    private val nl = Char(10).toString()

    private val yaml = listOf(
        "version: 2",
        "compileTimeout: 1111",
        "runningTimeout: 2222",
        "tasks:",
        "  課題1:",
        "    word: [課題1]",
        "    excludeWord: [前回課題]",
        "    testcase:",
        "      - name: t1",
        "        expect: [\"^ok1$\"]",
        "      - name: t2",
        "        expect: [\"^ok2$\"]",
        "  課題2:",
        "    word: [課題2]",
        "    testcase:",
        "      - name: s",
        "        expect: [\"^ok$\"]",
        "  課題3:",
        "    word: [課題3]",
    ).joinToString(nl)

    private fun student(id: String) = Student(StudentId(id), "名前$id", "/ws/$id", "/sub/$id")

    private fun source(student: String, name: String) =
        SourceFile(StudentId(student), "/ws/$student/sources/$name", "$student/sources/$name")

    private fun success(name: String) =
        CompileOutcome.Success("javac $name", SourceEncoding.UTF8, null, name.removeSuffix(".java"), "/cp")

    /** 展開・コンパイル・実行の結果を与える偽の toolkit。呼び出しを記録する */
    private inner class FakeToolkit(
        val students: List<Student>,
        /** 学生ごとのソースと、その内容 */
        val sources: Map<StudentId, List<Pair<SourceFile, String>>>,
        /** ソースのパスごとのコンパイル結果 (無ければ成功) */
        val compileOutcomes: Map<String, CompileOutcome> = emptyMap(),
        /** (クラス名, テストケース名) ごとの実行結果 */
        val run: (className: String, testCase: TestCase) -> Execution = { _, _ -> exec("ok1") },
        val toolchainError: String? = null,
        val unreadable: Set<String> = emptySet(),
        /** ソースのパスごとの、コンパイルしたクラスの中の呼び出し (実行する前の検査に使う) */
        val classCalls: Map<String, List<CallSite>> = emptyMap(),
        /** 検査に失敗するソースのパス */
        val uninspectable: Set<String> = emptySet(),
    ) : GradingToolkit {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun readTextFile(path: String): String {
            calls += "readTextFile $path"
            return yaml
        }

        override suspend fun verifyToolchain(toolchain: JavaToolchain): String? {
            calls += "verifyToolchain ${toolchain.jdkBinDir}"
            return toolchainError
        }

        override suspend fun prepareWorkspace(rootDir: String, clean: Boolean): String {
            calls += "prepareWorkspace $rootDir $clean"
            return "$rootDir/workspace"
        }

        override suspend fun extractSubmissions(
            rootDir: String,
            workspaceDir: String,
            parseDirName: (String) -> SubmissionDirName?,
            log: (String) -> Unit,
        ): List<Student> {
            calls += "extractSubmissions $rootDir $workspaceDir"
            log("展開の警告")
            return students
        }

        override suspend fun findSources(student: Student): List<SourceFile> {
            calls += "findSources ${student.id.value}"
            return sources[student.id].orEmpty().map { it.first }
        }

        override suspend fun compile(
            source: SourceFile,
            student: Student,
            toolchain: JavaToolchain,
            timeoutMillis: Long,
        ): CompileOutcome {
            calls += "compile ${source.displayPath} ${student.id.value} $timeoutMillis"
            // 後のソースほど早く終わるようにして、結果の順序が完了順にならないことを確かめる
            delay((10 - sourceIndex(source)).coerceAtLeast(0).toLong())
            return compileOutcomes[source.path] ?: success(source.fileName)
        }

        override suspend fun listCalls(
            source: SourceFile,
            target: CompileOutcome.Success,
            toolchain: JavaToolchain,
        ): List<CallSite> {
            if (source.path in uninspectable) throw IllegalStateException("javap でクラスを調べられませんでした")
            return classCalls[source.path].orEmpty()
        }

        override suspend fun sourceHash(source: SourceFile): String = "hash:" + source.fileName

        override suspend fun readSource(source: SourceFile, encoding: SourceEncoding): String {
            if (source.path in unreadable) throw IllegalStateException("読めません")
            return sources.values.flatten().first { it.first == source }.second
        }

        override suspend fun execute(
            target: CompileOutcome.Success,
            testCase: TestCase,
            toolchain: JavaToolchain,
            timeoutMillis: Long,
        ): Execution {
            calls += "execute ${target.className} ${testCase.name} $timeoutMillis"
            delay((5 - testCase.name.length).coerceAtLeast(0).toLong())
            return run(target.className, testCase)
        }

        private fun sourceIndex(source: SourceFile) = sources.values.flatten().indexOfFirst { it.first == source }

        override suspend fun writeTextFile(path: String, text: String) = error("not used")
        override suspend fun readBinaryFile(path: String): ByteArray = error("not used")
        override suspend fun writeBinaryFile(path: String, bytes: ByteArray) = error("not used")
        override suspend fun listSubmissionFiles(student: Student): SubmissionFiles = error("not used")
        override suspend fun listArchive(path: String, format: ArchiveFormat): ArchiveListing = error("not used")
        override suspend fun writeReport(path: String, format: ReportFormat, report: Report): List<String> =
            error("not used")
    }

    private fun exec(stdout: String, stderr: String = "", timedOut: Boolean = false) =
        Execution("java", stdout, stderr, if (timedOut) null else 0, timedOut, 1.0)

    private class Recorder {
        val events = Collections.synchronizedList(mutableListOf<PipelineEvent>())
        val logs get() = events.filterIsInstance<PipelineEvent.Log>()
        val progress get() = events.filterIsInstance<PipelineEvent.Progress>()
    }

    private fun request(
        lastStep: LastStep = LastStep.Test,
        assignments: Map<String, List<TaskName>> = emptyMap(),
        parallelism: Int = 4,
        approvals: Map<String, String> = emptyMap(),
    ) = GradingRequest(
        rootDir = "/root",
        configPath = "/root/config.yaml",
        lastStep = lastStep,
        compileParallelism = parallelism,
        testParallelism = parallelism,
        cleanWorkspace = false,
        toolchain = JavaToolchain("/jdk/bin"),
        assignments = assignments,
        approvals = approvals,
    )

    private fun run(toolkit: FakeToolkit, request: GradingRequest): Pair<GradingOutcome, Recorder> {
        val recorder = Recorder()
        val outcome = runBlocking { GradingPipeline(toolkit).run(request) { recorder.events += it } }
        return outcome to recorder
    }

    // ------------------------------------------------------------ 実行ステップ

    @Test
    fun stopsAfterExtract() {
        val toolkit = FakeToolkit(listOf(student("s1")), mapOf(StudentId("s1") to listOf(source("s1", "A.java") to "課題1")))
        val (outcome, recorder) = run(toolkit, request(LastStep.Extract))

        assertEquals(LastStep.Extract, outcome.lastStep)
        assertEquals("/root/workspace", outcome.workspaceDir)
        assertEquals(listOf(student("s1")), outcome.students)
        assertEquals(emptyList(), outcome.compiled)
        assertEquals(emptyList(), outcome.results)
        assertEquals(setOf("課題1", "課題2", "課題3"), outcome.config.tasks.keys.map { it.name }.toSet())
        // 展開までなら javac / java の確認もしない
        assertEquals(
            listOf(
                "readTextFile /root/config.yaml",
                "prepareWorkspace /root false",
                "extractSubmissions /root /root/workspace",
            ),
            toolkit.calls,
        )
        assertEquals(
            listOf(PipelineEvent.Progress(Stage.Prepare, 0, 0), PipelineEvent.Progress(Stage.Extract, 0, 0)),
            recorder.progress,
        )
        assertEquals(
            listOf(
                PipelineEvent.Log("コンフィグを読み込みました: 課題 3 件 / テストケース 3 件"),
                PipelineEvent.Log("workspace: /root/workspace"),
                PipelineEvent.Log("展開の警告", LogLevel.Warning),
                PipelineEvent.Log("1 人分の提出を展開しました"),
            ),
            recorder.logs,
        )
    }

    @Test
    fun warnsWhenNoStudentsFound() {
        val (outcome, recorder) = run(FakeToolkit(emptyList(), emptyMap()), request())
        assertEquals(emptyList(), outcome.results)
        assertTrue(
            recorder.logs.any { it.level == LogLevel.Warning && it.message.startsWith("submissionDirPattern (") },
            recorder.logs.toString(),
        )
        assertTrue(recorder.logs.any { it.message == "テスト完了: " }, recorder.logs.toString())
    }

    @Test
    fun stopsAfterCompileKeepingSourceOrder() {
        val a = source("s1", "A.java")
        val b = source("s1", "B.java")
        val c = source("s2", "C.java")
        val d = source("s2", "D.java")
        val toolkit = FakeToolkit(
            listOf(student("s1"), student("s2")),
            mapOf(
                StudentId("s1") to listOf(a to "", b to ""),
                StudentId("s2") to listOf(c to "", d to ""),
            ),
            compileOutcomes = mapOf(
                b.path to CompileOutcome.Failure("javac", SourceEncoding.UTF8, null, "error"),
                d.path to CompileOutcome.Error("javac", "起動できません"),
            ),
        )
        val (outcome, recorder) = run(toolkit, request(LastStep.Compile, parallelism = 3))

        assertEquals(LastStep.Compile, outcome.lastStep)
        assertEquals(listOf(a, b, c, d), outcome.compiled.map { it.source })
        assertEquals(
            listOf("Success", "Failure", "Success", "Error"),
            outcome.compiled.map { it.outcome::class.simpleName },
        )
        assertEquals(emptyList(), outcome.results)
        assertTrue("verifyToolchain /jdk/bin" in toolkit.calls)
        assertTrue("compile s2/sources/C.java s2 1111" in toolkit.calls, "コンパイルのタイムアウトはコンフィグの値")
        assertTrue(toolkit.calls.none { it.startsWith("execute") })

        val compileProgress = recorder.progress.filter { it.stage == Stage.Compile }
        assertEquals(PipelineEvent.Progress(Stage.Compile, 0, 4), compileProgress.first())
        assertEquals((1..4).toList(), compileProgress.drop(1).map { it.completed })
        assertTrue(compileProgress.all { it.total == 4 })

        assertTrue(PipelineEvent.Log("4 ファイルをコンパイルしました (失敗 2 件)") in recorder.logs)
        assertTrue(
            PipelineEvent.Log("s2/sources/D.java: コンパイル時に内部エラーが発生しました", LogLevel.Error) in recorder.logs,
        )
    }

    @Test
    fun failsWhenToolchainIsUnavailable() {
        val toolkit = FakeToolkit(listOf(student("s1")), emptyMap(), toolchainError = "javac がありません")
        val error = assertFailsWith<IllegalStateException> { run(toolkit, request(LastStep.Compile)) }
        assertEquals("javac がありません", error.message)
        assertTrue(toolkit.calls.none { it.startsWith("prepareWorkspace") })
    }

    // ------------------------------------------------------------ 判定

    @Test
    fun judgesEachSourceTaskAndTestCase() {
        val both = source("s1", "Both.java") // 課題1・課題2 の両方
        val none = source("s1", "None.java") // どの課題でもない
        val excluded = source("s1", "Excluded.java") // excludeWord を含む
        val ce = source("s2", "Ce.java")
        val ie = source("s2", "Ie.java")
        val noCase = source("s2", "NoCase.java") // テストケースのない課題
        val crash = source("s2", "Crash.java") // 実行時に toolkit が例外を投げる
        val toolkit = FakeToolkit(
            listOf(student("s1"), student("s2")),
            mapOf(
                StudentId("s1") to listOf(both to "// 課題1 課題2", none to "class None", excluded to "課題1 前回課題"),
                StudentId("s2") to listOf(
                    ce to "課題1 課題2",
                    ie to "課題2",
                    noCase to "課題3",
                    crash to "課題2",
                ),
            ),
            compileOutcomes = mapOf(
                ce.path to CompileOutcome.Failure("javac", SourceEncoding.UTF8, null, "error"),
                ie.path to CompileOutcome.Error("javac", "起動できません"),
            ),
            run = { className, testCase ->
                when {
                    className == "Crash" -> throw IllegalStateException("プロセスを起動できません")
                    testCase.name == "t1" -> exec("ok1")
                    testCase.name == "t2" -> exec("ng")
                    else -> exec("", stderr = "Exception in thread \"main\"")
                }
            },
        )
        val (outcome, recorder) = run(toolkit, request())

        data class Row(val file: String, val task: String?, val case: String?, val judge: Judge, val executed: Boolean)

        val rows = outcome.results.map {
            Row(it.source.fileName, it.taskName?.name, it.testCase?.name, it.autoJudge, it.execution != null)
        }
        assertEquals(
            listOf(
                Row("Both.java", "課題1", "t1", Judge.AC, true),
                Row("Both.java", "課題1", "t2", Judge.WA, true),
                Row("Both.java", "課題2", "s", Judge.RE, true),
                Row("None.java", null, null, Judge.NF, false),
                Row("Excluded.java", null, null, Judge.NF, false),
                Row("Ce.java", "課題1", null, Judge.CE, false),
                Row("Ce.java", "課題2", null, Judge.CE, false),
                Row("Ie.java", "課題2", null, Judge.IE, false),
                Row("NoCase.java", "課題3", null, Judge.Unknown, false),
                Row("Crash.java", "課題2", "s", Judge.IE, false),
            ),
            rows,
        )
        // id は並んだ順に 0 から振る
        assertEquals(outcome.results.indices.toList(), outcome.results.map { it.id })
        assertTrue(outcome.results.none { it.assigned })
        assertTrue(outcome.results.all { it.manualJudge == null })
        // 結果にはソースとコンパイル結果がそのまま入る
        outcome.results.forEach { r ->
            assertEquals(outcome.compiled.single { it.source == r.source }.outcome, r.compile)
        }
        val crashed = outcome.results.single { it.source == crash }
        assertTrue(crashed.note.startsWith("internal error: java.lang.IllegalStateException: プロセスを起動できません"))
        assertTrue(outcome.results.filter { it.source != crash }.all { it.note.isEmpty() })
        // 実行のタイムアウトはコンフィグの値
        assertTrue("execute Both t1 2222" in toolkit.calls)

        val testProgress = recorder.progress.filter { it.stage == Stage.Test }
        assertEquals(PipelineEvent.Progress(Stage.Test, 0, 10), testProgress.first())
        assertEquals((1..10).toList(), testProgress.drop(1).map { it.completed })
        assertEquals(
            listOf(Stage.Prepare, Stage.Extract, Stage.Compile, Stage.Test),
            recorder.progress.map { it.stage }.distinct(),
        )
        assertEquals(
            PipelineEvent.Log("テスト完了: AC 1  WA 1  RE 1  CE 2  IE 2  NF 2  ?? 1"),
            recorder.logs.last(),
        )
    }

    @Test
    fun holdsSuspiciousSourcesUntilApproved() {
        val exec = CallSite("Evil.main", "java.lang.Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;")
        val write = CallSite("Writer.main", "java.io.FileWriter", "<init>", "(Ljava/lang/String;)V")
        val evil = source("s1", "Evil.java")
        val writer = source("s1", "Writer.java")
        val broken = source("s1", "Broken.java")
        val approved = source("s1", "Approved.java")
        val changed = source("s1", "Changed.java")
        val allowYaml = yaml.replace("    word: [課題2]", "    word: [課題2]" + nl + "    allowCalls: [new FileWriter]")
        val fake = FakeToolkit(
            listOf(student("s1")),
            mapOf(
                StudentId("s1") to listOf(
                    evil to "課題2 課題3",
                    writer to "課題2", // 課題2 では FileWriter を許可している
                    broken to "課題2", // 検査に失敗した
                    approved to "課題2",
                    changed to "課題2",
                ),
            ),
            classCalls = mapOf(
                evil.path to listOf(exec),
                writer.path to listOf(write),
                approved.path to listOf(exec),
                changed.path to listOf(exec),
            ),
            uninspectable = setOf(broken.path),
            run = { _, _ -> exec("ok") },
        )
        val toolkit = object : GradingToolkit by fake {
            override suspend fun readTextFile(path: String): String = allowYaml
        }
        val recorder = Recorder()
        val outcome = runBlocking {
            GradingPipeline(toolkit).run(
                request(
                    approvals = mapOf(
                        approved.displayPath to "hash:Approved.java",
                        // 確認した後にソースが変わった (ハッシュが違う)
                        changed.displayPath to "hash:old",
                    ),
                ),
            ) { recorder.events += it }
        }

        data class Row(val file: String, val task: String?, val case: String?, val judge: Judge, val held: Boolean)
        assertEquals(
            listOf(
                Row("Evil.java", "課題2", "s", Judge.Unknown, true),
                // テストケースのない課題は、もともと実行しない
                Row("Evil.java", "課題3", null, Judge.Unknown, false),
                Row("Writer.java", "課題2", "s", Judge.AC, false),
                Row("Broken.java", "課題2", "s", Judge.Unknown, true),
                Row("Approved.java", "課題2", "s", Judge.AC, false),
                Row("Changed.java", "課題2", "s", Judge.Unknown, true),
            ),
            outcome.results.map { Row(it.source.fileName, it.taskName?.name, it.testCase?.name, it.autoJudge, it.held) },
        )
        // 保留したソースは実行していない
        assertEquals(
            listOf("execute Writer s 2222", "execute Approved s 2222"),
            fake.calls.filter { it.startsWith("execute") }.sorted().reversed(),
        )
        // コンパイル結果に、見つかった呼び出しとソースのハッシュを記録する
        val evilOutcome = outcome.compiled.single { it.source == evil }.outcome as CompileOutcome.Success
        assertEquals(listOf(SuspiciousCall("java.lang.Runtime.exec", "Evil.main", "外部プログラムの起動")), evilOutcome.suspicious)
        assertEquals("hash:Evil.java", evilOutcome.sourceHash)
        val brokenOutcome = outcome.compiled.single { it.source == broken }.outcome as CompileOutcome.Success
        assertEquals(listOf("検査できませんでした"), brokenOutcome.suspicious.map { it.category })
        assertTrue(
            recorder.logs.any { it.level == LogLevel.Warning && it.message.startsWith("危ない可能性がある呼び出しのあるソースが 5 件") },
            recorder.logs.toString(),
        )

        // testSource: 確認済みなら実行する。メソッド呼び出しの確認は保留しない
        val config = ConfigParser.parse(allowYaml)
        val compiled = outcome.compiled.single { it.source == evil }
        val pipeline = GradingPipeline(toolkit)
        val held = runBlocking { pipeline.testSource(config, compiled, listOf(TaskName("課題2")), JavaToolchain(), 1) }
        assertEquals(listOf(true), held.map { it.held })
        val run = runBlocking { pipeline.testSource(config, compiled, listOf(TaskName("課題2")), JavaToolchain(), 1, approved = true) }
        assertEquals(listOf(Judge.AC to false), run.map { it.autoJudge to it.held })
        val callsConfig = ConfigParser.parse(
            allowYaml.replace("        expect: [\"^ok$\"]" + nl + "  課題3:", "        calls: [println]" + nl + "  課題3:"),
        )
        val calls = runBlocking { pipeline.testSource(callsConfig, compiled, listOf(TaskName("課題2")), JavaToolchain(), 1) }
        assertEquals(listOf(false), calls.map { it.held })
    }

    @Test
    fun doesNotRunManualTasks() {
        // 手動で判定する課題 (GUI の課題など) は実行せず、テストケースごとに未判定にする
        val manualYaml = listOf(
            "version: 2",
            "tasks:",
            "  GUI:",
            "    word: [GUI]",
            "    manual: true",
            "    testcase:",
            "      - name: count",
            "        check: ボタンでカウントが増える",
            "      - name: reset",
            "  課題2:",
            "    word: [課題2]",
            "    testcase:",
            "      - name: s",
            "        expect: [\"^ok$\"]",
        ).joinToString(nl)
        val gui = source("s1", "Gui.java")
        val ce = source("s1", "GuiCe.java")
        val auto = source("s1", "Auto.java")
        val fake = FakeToolkit(
            listOf(student("s1")),
            mapOf(StudentId("s1") to listOf(gui to "GUI", ce to "GUI", auto to "課題2")),
            compileOutcomes = mapOf(ce.path to CompileOutcome.Failure("javac", SourceEncoding.UTF8, null, "error")),
            run = { _, _ -> exec("ok") },
        )
        val toolkit = object : GradingToolkit by fake {
            override suspend fun readTextFile(path: String): String = manualYaml
        }
        val outcome = runBlocking { GradingPipeline(toolkit).run(request()) { } }

        data class Row(val file: String, val case: String?, val judge: Judge, val executed: Boolean)
        assertEquals(
            listOf(
                Row("Gui.java", "count", Judge.Unknown, false),
                Row("Gui.java", "reset", Judge.Unknown, false),
                Row("GuiCe.java", null, Judge.CE, false),
                Row("Auto.java", "s", Judge.AC, true),
            ),
            outcome.results.map { Row(it.source.fileName, it.testCase?.name, it.autoJudge, it.execution != null) },
        )
        assertEquals("ボタンでカウントが増える", outcome.results[0].testCase?.check)
        // 実行したのは自動の課題だけ
        assertEquals(listOf("execute Auto s 5000"), fake.calls.filter { it.startsWith("execute") })
    }

    @Test
    fun judgesTimeoutAsTle() {
        val a = source("s1", "A.java")
        val toolkit = FakeToolkit(
            listOf(student("s1")),
            mapOf(StudentId("s1") to listOf(a to "課題2")),
            run = { _, _ -> exec("ok", timedOut = true) },
        )
        val (outcome, _) = run(toolkit, request())
        assertEquals(listOf(Judge.TLE), outcome.results.map { it.autoJudge })
    }

    @Test
    fun treatsUnreadableSourceAsNotFound() {
        val a = source("s1", "A.java")
        val toolkit = FakeToolkit(
            listOf(student("s1")),
            mapOf(StudentId("s1") to listOf(a to "課題1")),
            unreadable = setOf(a.path),
        )
        val (outcome, _) = run(toolkit, request())
        assertEquals(listOf(Judge.NF), outcome.results.map { it.autoJudge })
    }

    @Test
    fun keepsOrderWithoutParallelism() {
        val sources = (1..5).map { source("s1", "S$it.java") }
        val toolkit = FakeToolkit(listOf(student("s1")), mapOf(StudentId("s1") to sources.map { it to "課題1" }))
        val (parallel, _) = run(toolkit, request(parallelism = 8))
        val (serial, _) = run(toolkit, request(parallelism = 0))
        assertEquals(parallel.results, serial.results)
        assertEquals(10, serial.results.size)
    }

    // ------------------------------------------------------------ 課題の割り当て

    @Test
    fun assignedTasksOverrideDetection() {
        val nf = source("s1", "Nf.java")
        val detected = source("s1", "Detected.java")
        val invalid = source("s1", "Invalid.java")
        val ce = source("s1", "Ce.java")
        val toolkit = FakeToolkit(
            listOf(student("s1")),
            mapOf(StudentId("s1") to listOf(nf to "class Nf", detected to "課題1", invalid to "課題2", ce to "")),
            compileOutcomes = mapOf(ce.path to CompileOutcome.Failure("javac", SourceEncoding.UTF8, null, "error")),
        )
        val (outcome, recorder) = run(
            toolkit,
            request(
                assignments = mapOf(
                    // 課題を特定できなかったソースに割り当てる
                    nf.displayPath to listOf(TaskName("課題2")),
                    // 自動で判定できたソースでも、割り当てを優先する (コンフィグにない課題は無視)
                    detected.displayPath to listOf(TaskName("課題3"), TaskName("課題9")),
                    // 割り当てた課題がすべてコンフィグになければ、自動の判定に戻る
                    invalid.displayPath to listOf(TaskName("課題9")),
                    ce.displayPath to listOf(TaskName("課題1")),
                    // どのソースにも当たらない割り当て
                    "s9/sources/Gone.java" to listOf(TaskName("課題1")),
                ),
            ),
        )
        data class Row(val file: String, val task: String?, val case: String?, val judge: Judge, val assigned: Boolean)
        assertEquals(
            listOf(
                Row("Nf.java", "課題2", "s", Judge.WA, true),
                Row("Detected.java", "課題3", null, Judge.Unknown, true),
                Row("Invalid.java", "課題2", "s", Judge.WA, false),
                Row("Ce.java", "課題1", null, Judge.CE, true),
            ),
            outcome.results.map {
                Row(it.source.fileName, it.taskName?.name, it.testCase?.name, it.autoJudge, it.assigned)
            },
        )
        assertEquals(
            listOf(
                PipelineEvent.Log("s1/sources/Detected.java: 割り当てた課題「課題9」がコンフィグにないため無視します", LogLevel.Warning),
                PipelineEvent.Log("s1/sources/Invalid.java: 割り当てた課題「課題9」がコンフィグにないため無視します", LogLevel.Warning),
            ),
            recorder.logs.filter { it.message.contains("割り当てた課題") },
        )
    }

    @Test
    fun emptyAssignmentMeansNoTask() {
        // 課題との紐づけをすべて外したソース (空の割り当て) は、ソースの内容に関わらず NF
        val a = source("s1", "A.java")
        val b = source("s1", "B.java")
        val toolkit = FakeToolkit(listOf(student("s1")), mapOf(StudentId("s1") to listOf(a to "課題1 課題2", b to "課題1")))
        val (outcome, recorder) = run(toolkit, request(assignments = mapOf(a.displayPath to emptyList())))
        assertEquals(
            listOf(Triple("A.java", Judge.NF, true), Triple("B.java", Judge.AC, false), Triple("B.java", Judge.WA, false)),
            outcome.results.map { Triple(it.source.fileName, it.autoJudge, it.assigned) },
        )
        assertTrue(recorder.logs.none { it.message.contains("割り当てた課題") }, "空の割り当ては警告しない")
    }

    @Test
    fun testSourceDetectsTasksWhenNull() {
        val a = source("s1", "A.java")
        val toolkit = FakeToolkit(listOf(student("s1")), mapOf(StudentId("s1") to listOf(a to "課題2")))
        val pipeline = GradingPipeline(toolkit)
        val config = ConfigParser.parse(yaml)
        val compiled = CompiledSource(a, success("A.java"))
        // null: ソースの内容から判定する (課題の判定を自動に戻したとき)
        val detected = runBlocking { pipeline.testSource(config, compiled, null, JavaToolchain(), 2) }
        assertEquals(listOf("課題2" to false), detected.map { it.taskName?.name to it.assigned })
        // 空: どの課題でもない
        val none = runBlocking { pipeline.testSource(config, compiled, emptyList(), JavaToolchain(), 2) }
        assertEquals(listOf(Judge.NF to true), none.map { it.autoJudge to it.assigned })
    }

    @Test
    fun testSourceRunsAssignedTasks() {
        val a = source("s1", "A.java")
        val toolkit = FakeToolkit(listOf(student("s1")), mapOf(StudentId("s1") to listOf(a to "")))
        val pipeline = GradingPipeline(toolkit)
        val config = ConfigParser.parse(yaml)
        val compiled = CompiledSource(a, success("A.java"))

        val results = runBlocking {
            pipeline.testSource(config, compiled, listOf(TaskName("課題1"), TaskName("課題3")), JavaToolchain(), 2)
        }
        assertEquals(
            listOf(Triple("課題1", "t1", Judge.AC), Triple("課題1", "t2", Judge.WA), Triple("課題3", null, Judge.Unknown)),
            results.map { Triple(it.taskName?.name, it.testCase?.name, it.autoJudge) },
        )
        assertTrue(results.all { it.assigned && it.id == 0 && it.source == a })

        // コンフィグにない課題だけなら、ソースの内容から判定する (このソースは NF)
        val fallback = runBlocking { pipeline.testSource(config, compiled, listOf(TaskName("課題9")), JavaToolchain(), 2) }
        assertEquals(listOf(Judge.NF), fallback.map { it.autoJudge })
        assertTrue(fallback.none { it.assigned })

        // コンパイルに失敗したソースは実行しない
        val failed = CompiledSource(a, CompileOutcome.Failure("javac", SourceEncoding.UTF8, null, "error"))
        val ce = runBlocking { pipeline.testSource(config, failed, listOf(TaskName("課題1")), JavaToolchain(), 2) }
        assertEquals(listOf(Judge.CE), ce.map { it.autoJudge })
    }
}
