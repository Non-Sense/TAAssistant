package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.Config
import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.config.parseSubmissionDirName
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.model.encoding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable

enum class LastStep(val label: String) {
    Extract("zip 展開まで"),
    Compile("コンパイルまで"),
    Test("実行・テストまで"),
}

enum class Stage(val label: String) {
    Prepare("準備中"),
    Extract("zip 展開中"),
    Compile("コンパイル中"),
    Test("テスト実行中"),
}

enum class LogLevel { Info, Warning, Error }

sealed interface PipelineEvent {
    data class Progress(val stage: Stage, val completed: Int, val total: Int) : PipelineEvent
    data class Log(val message: String, val level: LogLevel = LogLevel.Info) : PipelineEvent
}

data class GradingRequest(
    val rootDir: String,
    val configPath: String,
    val lastStep: LastStep = LastStep.Test,
    val compileParallelism: Int = 4,
    val testParallelism: Int = 4,
    val cleanWorkspace: Boolean = true,
    val toolchain: JavaToolchain = JavaToolchain(),
    /** 手動で割り当てた課題 (キーはソースの [SourceFile.displayPath])。自動の課題判定より優先する */
    val assignments: Map<String, List<TaskName>> = emptyMap(),
)

@Serializable
data class GradingOutcome(
    val config: Config,
    val workspaceDir: String,
    val lastStep: LastStep,
    val students: List<Student>,
    val compiled: List<CompiledSource>,
    val results: List<TestResult>,
)

/** 展開 → コンパイル → 課題判定 → テスト実行 の一連の流れ */
class GradingPipeline(private val toolkit: GradingToolkit) {

    suspend fun run(request: GradingRequest, onEvent: (PipelineEvent) -> Unit): GradingOutcome {
        fun log(message: String, level: LogLevel = LogLevel.Info) = onEvent(PipelineEvent.Log(message, level))

        // ---- 準備
        onEvent(PipelineEvent.Progress(Stage.Prepare, 0, 0))
        val config = ConfigParser.parse(toolkit.readTextFile(request.configPath))
        log("コンフィグを読み込みました: 課題 ${config.tasks.size} 件 / テストケース ${config.testCaseCount} 件")
        if (request.lastStep >= LastStep.Compile) {
            toolkit.verifyToolchain(request.toolchain)?.let { throw IllegalStateException(it) }
        }
        val workspace = toolkit.prepareWorkspace(request.rootDir, request.cleanWorkspace)
        log("workspace: $workspace")

        // ---- 展開
        onEvent(PipelineEvent.Progress(Stage.Extract, 0, 0))
        val students = toolkit.extractSubmissions(request.rootDir, workspace, config::parseSubmissionDirName) {
            log(it, LogLevel.Warning)
        }
        if (students.isEmpty()) {
            log(
                "submissionDirPattern (${config.submissionDirPattern}) に一致する提出ディレクトリが見つかりませんでした",
                LogLevel.Warning,
            )
        }
        log("${students.size} 人分の提出を展開しました")
        if (request.lastStep == LastStep.Extract) {
            return GradingOutcome(config, workspace, request.lastStep, students, emptyList(), emptyList())
        }

        // ---- コンパイル
        val sources = students.flatMap { student -> toolkit.findSources(student).map { student to it } }
        onEvent(PipelineEvent.Progress(Stage.Compile, 0, sources.size))
        val compiled = sources.mapConcurrently(
            parallelism = request.compileParallelism,
            onProgress = { onEvent(PipelineEvent.Progress(Stage.Compile, it, sources.size)) },
        ) { (student, source) ->
            CompiledSource(source, toolkit.compile(source, student, request.toolchain, config.compileTimeout))
        }
        val compileFailures = compiled.count { it.outcome !is CompileOutcome.Success }
        log("${compiled.size} ファイルをコンパイルしました (失敗 $compileFailures 件)")
        compiled.filter { it.outcome is CompileOutcome.Error }.forEach {
            log("${it.source.displayPath}: コンパイル時に内部エラーが発生しました", LogLevel.Error)
        }
        if (request.lastStep == LastStep.Compile) {
            return GradingOutcome(config, workspace, request.lastStep, students, compiled, emptyList())
        }

        // ---- テスト
        request.assignments.forEach { (path, tasks) ->
            tasks.filter { it !in config.tasks }.forEach { task ->
                log("$path: 割り当てた課題「$task」がコンフィグにないため無視します", LogLevel.Warning)
            }
        }
        val plans = compiled.flatMap { planTests(config, it, request.assignments[it.source.displayPath]) }
        onEvent(PipelineEvent.Progress(Stage.Test, 0, plans.size))
        val results = plans.mapConcurrently(
            parallelism = request.testParallelism,
            onProgress = { onEvent(PipelineEvent.Progress(Stage.Test, it, plans.size)) },
        ) { plan -> runTest(plan, request.toolchain, config.runningTimeout) }
            .mapIndexed { index, result -> result.copy(id = index) }

        val counts = results.groupingBy { it.judge }.eachCount()
        log("テスト完了: " + Judge.entries.filter { it in counts }.joinToString("  ") { "$it ${counts[it]}" })
        return GradingOutcome(config, workspace, request.lastStep, students, compiled, results)
    }

    /**
     * 1つのソースを、指定した課題としてテストする (NF のソースに課題を手動で割り当てたとき)。
     * 結果の id は 0 なので、呼び出し側で振り直す。
     */
    suspend fun testSource(
        config: Config,
        compiled: CompiledSource,
        tasks: List<TaskName>,
        toolchain: JavaToolchain,
        parallelism: Int,
    ): List<TestResult> =
        planTests(config, compiled, tasks).mapConcurrently(parallelism, onProgress = {}) { runTest(it, toolchain, config.runningTimeout) }

    private class TestPlan(
        val compiled: CompiledSource,
        val taskName: TaskName?,
        val testCase: TestCase?,
        /** null ならテストを実行して判定する */
        val fixedJudge: Judge?,
        val assigned: Boolean,
    )

    /** [assigned] があれば、ソースの内容から判定する代わりにその課題としてテストする */
    private suspend fun planTests(config: Config, compiled: CompiledSource, assigned: List<TaskName>? = null): List<TestPlan> {
        val outcome = compiled.outcome
        val assignedTasks = assigned?.filter { it in config.tasks }?.takeIf { it.isNotEmpty() }
        if (assignedTasks != null) return plansFor(config, compiled, assignedTasks, assigned = true)
        val text = try {
            toolkit.readSource(compiled.source, outcome.encoding ?: SourceEncoding.UTF8)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val tasks = detectTasks(config, text)
        if (tasks.isEmpty()) return listOf(TestPlan(compiled, null, null, Judge.NF, assigned = false))
        return plansFor(config, compiled, tasks, assigned = false)
    }

    private fun plansFor(config: Config, compiled: CompiledSource, tasks: List<TaskName>, assigned: Boolean): List<TestPlan> {
        val outcome = compiled.outcome
        return tasks.flatMap { taskName ->
            val testCases = config.tasks.getValue(taskName).testcase
            when {
                outcome is CompileOutcome.Error -> listOf(TestPlan(compiled, taskName, null, Judge.IE, assigned))
                outcome is CompileOutcome.Failure -> listOf(TestPlan(compiled, taskName, null, Judge.CE, assigned))
                testCases.isEmpty() -> listOf(TestPlan(compiled, taskName, null, Judge.Unknown, assigned))
                else -> testCases.map { TestPlan(compiled, taskName, it, null, assigned) }
            }
        }
    }

    private suspend fun runTest(plan: TestPlan, toolchain: JavaToolchain, timeoutMillis: Long): TestResult {
        val base = TestResult(
            id = 0,
            source = plan.compiled.source,
            compile = plan.compiled.outcome,
            taskName = plan.taskName,
            testCase = plan.testCase,
            autoJudge = plan.fixedJudge ?: Judge.Unknown,
            assigned = plan.assigned,
        )
        if (plan.fixedJudge != null) return base

        val target = plan.compiled.outcome as CompileOutcome.Success
        val testCase = plan.testCase!!
        return try {
            val execution = toolkit.execute(target, testCase, toolchain, timeoutMillis)
            base.copy(autoJudge = AutoJudge.judge(testCase, execution), execution = execution)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            base.copy(autoJudge = Judge.IE, note = "internal error: ${e.stackTraceToString()}")
        }
    }
}

private suspend fun <T, R> List<T>.mapConcurrently(
    parallelism: Int,
    onProgress: (completed: Int) -> Unit,
    transform: suspend (T) -> R,
): List<R> = coroutineScope {
    val semaphore = Semaphore(parallelism.coerceAtLeast(1))
    val mutex = Mutex()
    var completed = 0
    map { item ->
        async {
            semaphore.withPermit { transform(item) }.also {
                mutex.withLock { onProgress(++completed) }
            }
        }
    }.awaitAll()
}
