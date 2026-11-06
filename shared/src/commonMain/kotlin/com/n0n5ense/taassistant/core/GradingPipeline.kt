package com.n0n5ense.taassistant.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

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
)

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
        val plans = compiled.flatMap { planTests(config, it) }
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

    private class TestPlan(
        val compiled: CompiledSource,
        val taskName: TaskName?,
        val testCase: TestCase?,
        /** null ならテストを実行して判定する */
        val fixedJudge: Judge?,
    )

    private suspend fun planTests(config: Config, compiled: CompiledSource): List<TestPlan> {
        val outcome = compiled.outcome
        val text = try {
            toolkit.readSource(compiled.source, outcome.encoding ?: SourceEncoding.UTF8)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
        val tasks = detectTasks(config, text)
        if (tasks.isEmpty()) return listOf(TestPlan(compiled, null, null, Judge.NF))

        return tasks.flatMap { taskName ->
            val testCases = config.tasks.getValue(taskName).testcase
            when {
                outcome is CompileOutcome.Error -> listOf(TestPlan(compiled, taskName, null, Judge.IE))
                outcome is CompileOutcome.Failure -> listOf(TestPlan(compiled, taskName, null, Judge.CE))
                testCases.isEmpty() -> listOf(TestPlan(compiled, taskName, null, Judge.Unknown))
                else -> testCases.map { TestPlan(compiled, taskName, it, null) }
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
