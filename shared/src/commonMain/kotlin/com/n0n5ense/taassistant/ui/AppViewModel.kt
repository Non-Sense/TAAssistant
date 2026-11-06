package com.n0n5ense.taassistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.n0n5ense.taassistant.core.GradingOutcome
import com.n0n5ense.taassistant.core.GradingPipeline
import com.n0n5ense.taassistant.core.GradingRequest
import com.n0n5ense.taassistant.core.JavaToolchain
import com.n0n5ense.taassistant.core.Judge
import com.n0n5ense.taassistant.core.StudentId
import com.n0n5ense.taassistant.core.LastStep
import com.n0n5ense.taassistant.core.LogLevel
import com.n0n5ense.taassistant.core.PipelineEvent
import com.n0n5ense.taassistant.core.ReportFormat
import com.n0n5ense.taassistant.core.SourceEncoding
import com.n0n5ense.taassistant.core.Stage
import com.n0n5ense.taassistant.core.TestResult
import com.n0n5ense.taassistant.core.buildReport
import com.n0n5ense.taassistant.core.encoding
import com.n0n5ense.taassistant.platform.PlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SetupState(
    val rootDir: String = "",
    val configPath: String = "",
    val outputDir: String = "",
    val lastStep: LastStep = LastStep.Test,
    val parallelTests: Boolean = true,
    val cleanWorkspace: Boolean = true,
    val autoExport: Boolean = true,
    val reportFormat: ReportFormat = ReportFormat.Xlsx,
    val jdkBinDir: String = "",
)

sealed interface RunStatus {
    data object Idle : RunStatus
    data class Running(val stage: Stage, val completed: Int, val total: Int) : RunStatus
    data object Finished : RunStatus
    data object Cancelled : RunStatus
    data class Failed(val message: String) : RunStatus
}

data class LogLine(val message: String, val level: LogLevel)

data class ExportStatus(val message: String, val paths: List<String>, val isError: Boolean)

/** 結果タブの絞り込みと、学生ごとの開閉 */
data class ResultViewState(
    val filter: ResultFilter = ResultFilter.All,
    val query: String = "",
    val collapsed: Set<StudentId> = emptySet(),
)

data class UiState(
    val setup: SetupState = SetupState(),
    val status: RunStatus = RunStatus.Idle,
    val logs: List<LogLine> = emptyList(),
    val outcome: GradingOutcome? = null,
    /** 手動判定を反映した結果 */
    val results: List<TestResult> = emptyList(),
    val selectedId: Int? = null,
    val export: ExportStatus? = null,
    /** 結果タブの表示状態 (タブを切り替えても残す) */
    val resultView: ResultViewState = ResultViewState(),
) {
    val isRunning: Boolean get() = status is RunStatus.Running
    val canExport: Boolean get() = !isRunning && outcome?.lastStep == LastStep.Test
    val selected: TestResult? get() = selectedId?.let { id -> results.firstOrNull { it.id == id } }
}

private const val MAX_LOG_LINES = 5000

class AppViewModel(
    private val services: PlatformServices,
) : ViewModel() {
    private val pipeline = GradingPipeline(services.toolkit)
    private val _state = MutableStateFlow(UiState(setup = loadSetup()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var runJob: Job? = null

    // ------------------------------------------------------------ setup

    fun updateSetup(transform: (SetupState) -> SetupState) {
        _state.update { it.copy(setup = transform(it.setup)) }
        saveSetup(_state.value.setup)
    }

    fun browseRootDir() {
        val current = state.value.setup
        services.pickDirectory("提出物ディレクトリを選択", current.rootDir.ifBlank { null })?.let { path ->
            updateSetup {
                it.copy(rootDir = path, outputDir = it.outputDir.ifBlank { services.parentDirectory(path).orEmpty() })
            }
        }
    }

    fun browseConfig() {
        val current = state.value.setup
        services.pickFile("コンフィグファイルを選択", current.configPath.ifBlank { current.rootDir }, listOf("yaml", "yml"))
            ?.let { path -> updateSetup { it.copy(configPath = path) } }
    }

    fun browseOutputDir() {
        val current = state.value.setup
        services.pickDirectory("出力先ディレクトリを選択", current.outputDir.ifBlank { current.rootDir })
            ?.let { path -> updateSetup { it.copy(outputDir = path) } }
    }

    fun browseJdkBinDir() {
        services.pickDirectory("JDK の bin ディレクトリを選択", state.value.setup.jdkBinDir.ifBlank { null })
            ?.let { path -> updateSetup { it.copy(jdkBinDir = path) } }
    }

    // ------------------------------------------------------------ run

    fun start() {
        if (runJob?.isActive == true) return
        val setup = state.value.setup
        val problem = when {
            setup.rootDir.isBlank() -> "提出物ディレクトリを指定してください"
            setup.configPath.isBlank() -> "コンフィグファイルを指定してください"
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(status = RunStatus.Failed(problem)) }
            return
        }

        val parallelism = services.availableProcessors.coerceIn(1, 16)
        val request = GradingRequest(
            rootDir = setup.rootDir,
            configPath = setup.configPath,
            lastStep = setup.lastStep,
            compileParallelism = parallelism,
            testParallelism = if (setup.parallelTests) parallelism else 1,
            cleanWorkspace = setup.cleanWorkspace,
            toolchain = JavaToolchain(setup.jdkBinDir.trim()),
        )
        _state.update {
            it.copy(
                status = RunStatus.Running(Stage.Prepare, 0, 0),
                logs = emptyList(),
                outcome = null,
                results = emptyList(),
                selectedId = null,
                export = null,
            )
        }
        runJob = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) { pipeline.run(request, ::onPipelineEvent) }
                _state.update {
                    it.copy(
                        status = RunStatus.Finished,
                        outcome = outcome,
                        results = outcome.results,
                        selectedId = outcome.results.firstOrNull()?.id,
                        // 既定ではすべての学生を閉じておく (最初の結果を選んでいても開かない)
                        resultView = it.resultView.copy(collapsed = outcome.students.map { s -> s.id }.toSet()),
                    )
                }
                if (outcome.lastStep == LastStep.Test && setup.autoExport) export()
            } catch (e: CancellationException) {
                appendLog("中止しました", LogLevel.Warning)
                _state.update { it.copy(status = RunStatus.Cancelled) }
                throw e
            } catch (e: Throwable) {
                appendLog(e.stackTraceToString(), LogLevel.Error)
                _state.update { it.copy(status = RunStatus.Failed(e.message ?: e.toString())) }
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
    }

    private fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Progress -> _state.update {
                if (it.status is RunStatus.Running) it.copy(status = RunStatus.Running(event.stage, event.completed, event.total)) else it
            }
            is PipelineEvent.Log -> appendLog(event.message, event.level)
        }
    }

    private fun appendLog(message: String, level: LogLevel) {
        _state.update { it.copy(logs = (it.logs + LogLine(message, level)).takeLast(MAX_LOG_LINES)) }
    }

    // ------------------------------------------------------------ review

    /** 結果を選ぶ。閉じている学生の結果なら、その学生を開く */
    fun select(id: Int?) {
        _state.update { s ->
            val student = s.results.firstOrNull { it.id == id }?.studentId
            val view = if (student != null && student in s.resultView.collapsed) {
                s.resultView.copy(collapsed = s.resultView.collapsed - student)
            } else {
                s.resultView
            }
            s.copy(selectedId = id, resultView = view)
        }
    }

    fun updateResultView(transform: (ResultViewState) -> ResultViewState) {
        _state.update { it.copy(resultView = transform(it.resultView)) }
    }

    fun setManualJudge(id: Int, judge: Judge?) {
        _state.update { s ->
            s.copy(results = s.results.map { if (it.id == id) it.copy(manualJudge = judge) else it })
        }
    }

    suspend fun readSource(result: TestResult): String = try {
        services.toolkit.readSource(result.source, result.compile.encoding ?: SourceEncoding.UTF8)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "ソースを読み込めませんでした: ${e.message}"
    }

    fun openPath(path: String) = services.openPath(path)

    // ------------------------------------------------------------ export

    fun export() {
        val snapshot = state.value
        val outcome = snapshot.outcome ?: return
        val setup = snapshot.setup
        val outputDir = setup.outputDir.ifBlank { services.parentDirectory(setup.rootDir) ?: setup.rootDir }
        viewModelScope.launch {
            val status = try {
                val paths = withContext(Dispatchers.Default) {
                    val report = buildReport(outcome.config, outcome.students, state.value.results)
                    services.toolkit.writeReport(outputDir, outcome.config.outputFileName, setup.reportFormat, report)
                }
                paths.forEach { appendLog("出力しました: $it", LogLevel.Info) }
                ExportStatus("結果ファイルを出力しました", paths, isError = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("出力に失敗しました: ${e.message}", LogLevel.Error)
                ExportStatus("出力に失敗しました: ${e.message}", emptyList(), isError = true)
            }
            _state.update { it.copy(export = status) }
        }
    }

    // ------------------------------------------------------------ persistence

    private fun loadSetup(): SetupState {
        val s = services.settings
        val default = SetupState()
        return SetupState(
            rootDir = s.get(KEY_ROOT).orEmpty(),
            configPath = s.get(KEY_CONFIG).orEmpty(),
            outputDir = s.get(KEY_OUTPUT).orEmpty(),
            lastStep = s.get(KEY_LAST_STEP)?.let { v -> LastStep.entries.firstOrNull { it.name == v } } ?: default.lastStep,
            parallelTests = s.get(KEY_PARALLEL)?.toBooleanStrictOrNull() ?: default.parallelTests,
            cleanWorkspace = s.get(KEY_CLEAN)?.toBooleanStrictOrNull() ?: default.cleanWorkspace,
            autoExport = s.get(KEY_AUTO_EXPORT)?.toBooleanStrictOrNull() ?: default.autoExport,
            reportFormat = s.get(KEY_FORMAT)?.let { v -> ReportFormat.entries.firstOrNull { it.name == v } } ?: default.reportFormat,
            jdkBinDir = s.get(KEY_JDK).orEmpty(),
        )
    }

    private fun saveSetup(setup: SetupState) {
        val s = services.settings
        s.put(KEY_ROOT, setup.rootDir)
        s.put(KEY_CONFIG, setup.configPath)
        s.put(KEY_OUTPUT, setup.outputDir)
        s.put(KEY_LAST_STEP, setup.lastStep.name)
        s.put(KEY_PARALLEL, setup.parallelTests.toString())
        s.put(KEY_CLEAN, setup.cleanWorkspace.toString())
        s.put(KEY_AUTO_EXPORT, setup.autoExport.toString())
        s.put(KEY_FORMAT, setup.reportFormat.name)
        s.put(KEY_JDK, setup.jdkBinDir)
    }

    private companion object {
        const val KEY_ROOT = "rootDir"
        const val KEY_CONFIG = "configPath"
        const val KEY_OUTPUT = "outputDir"
        const val KEY_LAST_STEP = "lastStep"
        const val KEY_PARALLEL = "parallelTests"
        const val KEY_CLEAN = "cleanWorkspace"
        const val KEY_AUTO_EXPORT = "autoExport"
        const val KEY_FORMAT = "reportFormat"
        const val KEY_JDK = "jdkBinDir"
    }
}
