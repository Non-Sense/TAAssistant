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

/** 展開 → コンパイル → 課題判定 → テスト実行 の一連の流れ */
class GradingPipeline(private val toolkit: GradingToolkit) {

    suspend fun run(request: GradingRequest, onEvent: (PipelineEvent) -> Unit): GradingOutcome =
        Session(request, onEvent).run()

    /**
     * 1つのソースを、指定した課題としてテストする (NF のソースに課題を手動で割り当てたとき)。
     * コンフィグにない課題は無視し、1つも残らなければソースの内容から判定する。
     * 結果の id は 0 なので、呼び出し側で振り直す。
     */
    suspend fun testSource(
        config: Config,
        compiled: CompiledSource,
        tasks: List<TaskName>,
        toolchain: JavaToolchain,
        parallelism: Int,
    ): List<TestResult> =
        planTests(config, compiled, tasks.validIn(config))
            .mapConcurrently(parallelism, onProgress = {}) { runTest(it, toolchain, config.runningTimeout) }

    /** 1回の採点。段階ごとの処理と、ログ・進み具合の通知を持つ */
    private inner class Session(
        private val request: GradingRequest,
        private val onEvent: (PipelineEvent) -> Unit,
    ) {
        private fun log(message: String, level: LogLevel = LogLevel.Info) = onEvent(PipelineEvent.Log(message, level))

        private fun progress(stage: Stage, completed: Int = 0, total: Int = 0) =
            onEvent(PipelineEvent.Progress(stage, completed, total))

        suspend fun run(): GradingOutcome {
            val (config, workspace) = prepare()
            val students = extract(config, workspace)
            fun outcome(compiled: List<CompiledSource> = emptyList(), results: List<TestResult> = emptyList()) =
                GradingOutcome(config, workspace, request.lastStep, students, compiled, results)

            if (request.lastStep == LastStep.Extract) return outcome()
            val compiled = compile(config, students)
            if (request.lastStep == LastStep.Compile) return outcome(compiled)
            return outcome(compiled, test(config, compiled))
        }

        /** コンフィグを読み、javac / java を確認して workspace を用意する */
        private suspend fun prepare(): Pair<Config, String> {
            progress(Stage.Prepare)
            val config = ConfigParser.parse(toolkit.readTextFile(request.configPath))
            log("コンフィグを読み込みました: 課題 ${config.tasks.size} 件 / テストケース ${config.testCaseCount} 件")
            if (request.lastStep >= LastStep.Compile) {
                toolkit.verifyToolchain(request.toolchain)?.let { throw IllegalStateException(it) }
            }
            val workspace = toolkit.prepareWorkspace(request.rootDir, request.cleanWorkspace)
            log("workspace: $workspace")
            return config to workspace
        }

        private suspend fun extract(config: Config, workspace: String): List<Student> {
            progress(Stage.Extract)
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
            return students
        }

        private suspend fun compile(config: Config, students: List<Student>): List<CompiledSource> {
            val sources = students.flatMap { student -> toolkit.findSources(student).map { student to it } }
            progress(Stage.Compile, total = sources.size)
            val compiled = sources.mapConcurrently(
                parallelism = request.compileParallelism,
                onProgress = { progress(Stage.Compile, it, sources.size) },
            ) { (student, source) ->
                CompiledSource(source, toolkit.compile(source, student, request.toolchain, config.compileTimeout))
            }
            val failures = compiled.count { it.outcome !is CompileOutcome.Success }
            log("${compiled.size} ファイルをコンパイルしました (失敗 $failures 件)")
            compiled.filter { it.outcome is CompileOutcome.Error }.forEach {
                log("${it.source.displayPath}: コンパイル時に内部エラーが発生しました", LogLevel.Error)
            }
            return compiled
        }

        private suspend fun test(config: Config, compiled: List<CompiledSource>): List<TestResult> {
            val assignments = validAssignments(config)
            val plans = compiled.flatMap { planTests(config, it, assignments[it.source.displayPath]) }
            progress(Stage.Test, total = plans.size)
            val results = plans.mapConcurrently(
                parallelism = request.testParallelism,
                onProgress = { progress(Stage.Test, it, plans.size) },
            ) { runTest(it, request.toolchain, config.runningTimeout) }
                .mapIndexed { index, result -> result.copy(id = index) }

            val counts = results.groupingBy { it.judge }.eachCount()
            log("テスト完了: " + Judge.entries.filter { it in counts }.joinToString("  ") { "$it ${counts[it]}" })
            return results
        }

        /** 割り当てからコンフィグにない課題を除く (除いた課題は警告する)。課題が残らなかったソースは自動の判定に戻す */
        private fun validAssignments(config: Config): Map<String, List<TaskName>> =
            request.assignments.mapNotNull { (path, tasks) ->
                tasks.filter { it !in config.tasks }.forEach { task ->
                    log("$path: 割り当てた課題「$task」がコンフィグにないため無視します", LogLevel.Warning)
                }
                tasks.validIn(config)?.let { path to it }
            }.toMap()
    }

    /** [assigned] があればその課題として、なければソースの内容から判定した課題としてテストする */
    private suspend fun planTests(config: Config, compiled: CompiledSource, assigned: List<TaskName>?): List<TestPlan> =
        if (assigned != null) {
            planFor(config, compiled, assigned, assigned = true)
        } else {
            planFor(config, compiled, detectTasks(config, readSourceOrEmpty(compiled)), assigned = false)
        }

    /** 読めないソースは空として扱う (どの課題にも当たらず NF になる) */
    private suspend fun readSourceOrEmpty(compiled: CompiledSource): String = try {
        toolkit.readSource(compiled.source, compiled.outcome.encoding ?: SourceEncoding.UTF8)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ""
    }

    private suspend fun runTest(plan: TestPlan, toolchain: JavaToolchain, timeoutMillis: Long): TestResult {
        val base = TestResult(
            id = 0,
            source = plan.compiled.source,
            compile = plan.compiled.outcome,
            taskName = plan.taskName,
            testCase = (plan as? TestPlan.Execute)?.testCase,
            autoJudge = Judge.Unknown,
            assigned = plan.assigned,
        )
        return when (plan) {
            is TestPlan.Fixed -> base.copy(autoJudge = plan.judge)
            is TestPlan.Execute -> try {
                val execution = toolkit.execute(plan.target, plan.testCase, toolchain, timeoutMillis)
                base.copy(autoJudge = AutoJudge.judge(plan.testCase, execution), execution = execution)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                base.copy(autoJudge = Judge.IE, note = "internal error: ${e.stackTraceToString()}")
            }
        }
    }
}

/** 1つのソース × 課題 × テストケース で行うこと */
private sealed class TestPlan(val compiled: CompiledSource, val taskName: TaskName?, val assigned: Boolean) {
    /** 実行せずに判定が決まる (NF / CE / IE / テストケースなし) */
    class Fixed(compiled: CompiledSource, taskName: TaskName?, assigned: Boolean, val judge: Judge) :
        TestPlan(compiled, taskName, assigned)

    /** 実行して判定する */
    class Execute(
        compiled: CompiledSource,
        val target: CompileOutcome.Success,
        taskName: TaskName,
        val testCase: TestCase,
        assigned: Boolean,
    ) : TestPlan(compiled, taskName, assigned)
}

/**
 * [tasks] としてテストする計画を立てる。課題がなければ NF、コンパイルに失敗していれば課題ごとに CE / IE、
 * テストケースのない課題は未判定とし、それ以外はテストケースごとに実行する。
 */
private fun planFor(config: Config, compiled: CompiledSource, tasks: List<TaskName>, assigned: Boolean): List<TestPlan> {
    if (tasks.isEmpty()) return listOf(TestPlan.Fixed(compiled, null, assigned, Judge.NF))
    return tasks.flatMap { task ->
        when (val outcome = compiled.outcome) {
            is CompileOutcome.Error -> listOf(TestPlan.Fixed(compiled, task, assigned, Judge.IE))
            is CompileOutcome.Failure -> listOf(TestPlan.Fixed(compiled, task, assigned, Judge.CE))
            is CompileOutcome.Success -> config.tasks.getValue(task).testcase
                .map<TestCase, TestPlan> { TestPlan.Execute(compiled, outcome, task, it, assigned) }
                .ifEmpty { listOf(TestPlan.Fixed(compiled, task, assigned, Judge.Unknown)) }
        }
    }
}

/** コンフィグにある課題だけを残す。1つも残らなければ null (自動の判定に戻す) */
private fun List<TaskName>.validIn(config: Config): List<TaskName>? =
    filter { it in config.tasks }.takeIf { it.isNotEmpty() }

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
