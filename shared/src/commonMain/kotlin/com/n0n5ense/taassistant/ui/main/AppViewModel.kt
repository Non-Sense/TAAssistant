package com.n0n5ense.taassistant.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.GradingPipeline
import com.n0n5ense.taassistant.core.grading.GradingRequest
import com.n0n5ense.taassistant.core.grading.JavaToolchain
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.grading.LogLevel
import com.n0n5ense.taassistant.core.grading.PipelineEvent
import com.n0n5ense.taassistant.core.grading.Stage
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.model.encoding
import com.n0n5ense.taassistant.core.project.ProjectCodec
import com.n0n5ense.taassistant.core.project.ProjectFile
import com.n0n5ense.taassistant.core.project.ProjectSettings
import com.n0n5ense.taassistant.core.project.TaskAssignment
import com.n0n5ense.taassistant.core.project.carryOverManualJudges
import com.n0n5ense.taassistant.core.project.replaceSourceResults
import com.n0n5ense.taassistant.core.project.replaceWithAssigned
import com.n0n5ense.taassistant.core.project.unlinkTask
import com.n0n5ense.taassistant.core.report.ReportFormat
import com.n0n5ense.taassistant.core.report.buildReport
import com.n0n5ense.taassistant.platform.PlatformServices
import com.n0n5ense.taassistant.ui.results.ResultViewState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/** 画面で編集する採点の設定。プロジェクトファイルに保存する */
typealias SetupState = ProjectSettings

sealed interface RunStatus {
    data object Idle : RunStatus
    data class Running(val stage: Stage, val completed: Int, val total: Int) : RunStatus
    data object Finished : RunStatus
    data object Cancelled : RunStatus
    data class Failed(val message: String) : RunStatus
}

data class LogLine(val message: String, val level: LogLevel)

/** 結果の書き出しの状態 */
sealed interface ExportStatus {
    data object Writing : ExportStatus
    data class Done(val paths: List<String>) : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

/** 開いているプロジェクト */
data class OpenProject(val path: String) {
    val name: String
        get() = path.substringAfterLast('/').substringAfterLast('\\')
            .removeSuffix(".${ProjectCodec.EXTENSION}")
}

/** プロジェクトの操作の結果 (複製したときなど)。[openPath] があれば「開く」を出す */
data class ProjectNotice(val message: String, val openPath: String? = null)

sealed interface SaveStatus {
    data object Saved : SaveStatus
    data object Saving : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

data class UiState(
    /** null: プロジェクトを開いていない (ようこそ画面を表示する) */
    val project: OpenProject? = null,
    val loadingProject: Boolean = false,
    val projectError: String? = null,
    val recentProjects: List<String> = emptyList(),
    val saveStatus: SaveStatus = SaveStatus.Saved,
    val projectNotice: ProjectNotice? = null,
    val setup: SetupState = SetupState(),
    val status: RunStatus = RunStatus.Idle,
    val logs: List<LogLine> = emptyList(),
    val outcome: GradingOutcome? = null,
    /** 手動判定・課題の割り当てを反映した結果 */
    val results: List<TestResult> = emptyList(),
    /** 手動で割り当てた課題 (キーはソースの displayPath) */
    val assignments: Map<String, List<TaskName>> = emptyMap(),
    /** 課題を割り当ててテストを実行中のソース (displayPath) */
    val assigningSource: String? = null,
    /** 手動で判定する課題で、1件ずつ実行中の結果の id */
    val manualRunId: Int? = null,
    val selectedId: Int? = null,
    val export: ExportStatus? = null,
    /** 詳細タブの表示状態 (タブを切り替えても残す) */
    val resultView: ResultViewState = ResultViewState(),
) {
    val isRunning: Boolean get() = status is RunStatus.Running
    /** 結果を書き出せる (テストまで実行した結果がある) */
    val canExport: Boolean
        get() = !isRunning && outcome?.lastStep == LastStep.Test && export != ExportStatus.Writing
    val selected: TestResult? get() = selectedId?.let { id -> results.firstOrNull { it.id == id } }
}

private const val MAX_LOG_LINES = 5000
private const val MAX_RECENT_PROJECTS = 20

/** プロジェクトファイルに保存する内容。前回保存した内容と比べて、変わったときだけ保存する */
private data class SaveSnapshot(
    val path: String,
    val settings: ProjectSettings,
    val assignments: Map<String, List<TaskName>>,
    val outcome: GradingOutcome?,
    val results: List<TestResult>,
)

private fun UiState.saveSnapshot(): SaveSnapshot? =
    project?.takeUnless { isRunning || loadingProject }
        ?.let { SaveSnapshot(it.path, setup, assignments, outcome, results) }

@OptIn(FlowPreview::class)
class AppViewModel(
    private val services: PlatformServices,
) : ViewModel() {
    private val pipeline = GradingPipeline(services.toolkit)
    private val _state = MutableStateFlow(UiState(recentProjects = loadRecentProjects()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var runJob: Job? = null
    private var manualRunJob: Job? = null

    private val saveMutex = Mutex()
    private var lastSaved: SaveSnapshot? = null

    init {
        // 変更があれば、少し待ってから自動保存する
        viewModelScope.launch {
            state.map { it.saveSnapshot() }
                .distinctUntilChanged()
                .debounce(500.milliseconds)
                .collect { snapshot -> if (snapshot != null) save(snapshot) }
        }
        // 前回開いていたプロジェクトを開く
        services.settings.get(KEY_LAST_PROJECT)
            ?.takeIf { it.isNotBlank() && services.fileExists(it) }?.let { path ->
            viewModelScope.launch { load(path) }
        }
    }

    // ------------------------------------------------------------ project

    fun newProject() {
        if (state.value.isRunning) return
        val initial = state.value.project?.path?.let(services::parentDirectory)
        viewModelScope.launch {
            val path = services.pickSaveFile(
                "新しいプロジェクトの保存先",
                initial,
                listOf(ProjectCodec.EXTENSION),
                suggestedName = "新しいプロジェクト",
            ) ?: return@launch
            flushSave()
            // 実行オプションと JDK は前回の設定を引き継ぎ、ディレクトリとコンフィグは空にする。
            // プロジェクト導入前のパスが残っていれば、最初のプロジェクトにだけ引き継ぐ
            val settings = takeLegacyPaths(loadDefaultOptions())
            _state.update {
                UiState(
                    project = OpenProject(path),
                    recentProjects = it.recentProjects,
                    setup = settings,
                    logs = listOf(LogLine("プロジェクトを作成しました: $path", LogLevel.Info)),
                )
            }
            state.value.saveSnapshot()?.let { save(it) }
            rememberProject(path)
        }
    }

    fun openProject(path: String? = null) {
        if (state.value.isRunning) return
        viewModelScope.launch {
            val target = path ?: services.pickFile(
                "プロジェクトを開く",
                state.value.project?.path ?: state.value.recentProjects.firstOrNull(),
                listOf(ProjectCodec.EXTENSION),
            ) ?: return@launch
            flushSave()
            load(target)
        }
    }

    fun closeProject() {
        if (state.value.isRunning) return
        viewModelScope.launch {
            flushSave()
            services.settings.put(KEY_LAST_PROJECT, "")
            lastSaved = null
            stopManualRun()
            _state.update { UiState(recentProjects = it.recentProjects) }
        }
    }

    fun dismissProjectError() = _state.update { it.copy(projectError = null) }

    fun dismissProjectNotice() = _state.update { it.copy(projectNotice = null) }

    /** 今の内容を別のファイルに保存し、以後はそのファイルを開いている状態にする (元のファイルは最後に保存した状態で残る) */
    fun saveProjectAs() {
        viewModelScope.launch {
            val path = pickProjectCopyPath("別名で保存", suggestedName = null) ?: return@launch
            // 自動保存と同じく、元のファイルにも今の内容を書いておく
            flushSave()
            val snapshot = state.value.saveSnapshot()?.copy(path = path) ?: return@launch
            try {
                saveMutex.withLock {
                    services.toolkit.writeBinaryFile(path, encodeProject(snapshot))
                    lastSaved = snapshot
                }
                _state.update {
                    it.copy(
                        project = OpenProject(path),
                        saveStatus = SaveStatus.Saved,
                        projectNotice = ProjectNotice("別名で保存しました: $path"),
                    )
                }
                rememberProject(path)
                appendLog("プロジェクトを別名で保存しました: $path", LogLevel.Info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(projectError = "別名で保存できませんでした: ${e.message}") }
            }
        }
    }

    /**
     * 今の内容を別のファイルに複製する。開いているプロジェクトは変えない。
     * [includeResults] が false なら設定だけを複製する (次の回の課題を同じ設定で始めるときなど)。
     */
    fun duplicateProject(includeResults: Boolean) {
        viewModelScope.launch {
            val current = state.value.project?.name.orEmpty()
            val path = pickProjectCopyPath(
                if (includeResults) "プロジェクトを複製" else "設定だけを複製",
                suggestedName = if (includeResults) "$current (コピー)" else "$current (設定)",
            ) ?: return@launch
            val snapshot = state.value.saveSnapshot()?.copy(path = path) ?: return@launch
            try {
                services.toolkit.writeBinaryFile(path, encodeProject(snapshot, includeResults))
                addRecentProject(path)
                val what = if (includeResults) "複製しました" else "設定だけを複製しました"
                _state.update {
                    it.copy(
                        projectNotice = ProjectNotice(
                            "$what: $path",
                            openPath = path
                        )
                    )
                }
                appendLog("プロジェクトを$what: $path", LogLevel.Info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(projectError = "複製できませんでした: ${e.message}") }
            }
        }
    }

    /** プロジェクトのあるフォルダを開く */
    fun openProjectFolder() {
        state.value.project?.path?.let(services::parentDirectory)?.let(services::openPath)
    }

    /** 別名で保存・複製の保存先を選ぶ。今のファイルと同じなら null */
    private suspend fun pickProjectCopyPath(title: String, suggestedName: String?): String? {
        val current = state.value.project ?: return null
        if (state.value.isRunning) return null
        val path = services.pickSaveFile(
            title,
            current.path,
            listOf(ProjectCodec.EXTENSION),
            suggestedName
        ) ?: return null
        if (path == current.path) {
            _state.update { it.copy(projectError = "今開いているプロジェクトと同じファイルです") }
            return null
        }
        return path
    }

    private suspend fun load(path: String) {
        _state.update { it.copy(loadingProject = true, projectError = null) }
        try {
            val bytes = services.toolkit.readBinaryFile(path)
            val file = withContext(Dispatchers.Default) { ProjectCodec.decode(bytes) }
            val settings = file.settings.let { s ->
                s.copy(
                    rootDir = services.resolveAgainstFile(path, s.rootDir),
                    configPath = services.resolveAgainstFile(path, s.configPath),
                    outputDir = services.resolveAgainstFile(path, s.outputDir),
                )
            }
            val outcome = file.outcome
            val results = outcome?.results.orEmpty()
            stopManualRun()
            val loaded = UiState(
                project = OpenProject(path),
                recentProjects = state.value.recentProjects,
                setup = settings,
                status = if (outcome != null) RunStatus.Finished else RunStatus.Idle,
                logs = listOf(LogLine("プロジェクトを開きました: $path", LogLevel.Info)),
                outcome = outcome,
                results = results,
                assignments = file.assignments.associate { it.source to it.tasks },
                selectedId = results.firstOrNull()?.id,
                resultView = ResultViewState(collapsed = outcome?.students.orEmpty().map { it.id }
                    .toSet()),
            )
            // 開いただけでは保存し直さない
            lastSaved = loaded.saveSnapshot()
            _state.value = loaded
            rememberProject(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loadingProject = false,
                    projectError = "プロジェクトを開けませんでした: ${e.message}"
                )
            }
        }
    }

    /** 未保存の変更があればすぐに保存する (プロジェクトを切り替える前・アプリを終了する前) */
    suspend fun flushSave() {
        state.value.saveSnapshot()?.let { save(it) }
    }

    /**
     * [snapshot] をプロジェクトファイルの中身にする。パスは [snapshot] の保存先を基準にした相対パスにする。
     * [includeResults] が false なら、設定だけにする (課題の割り当てと結果を含めない)。
     */
    private suspend fun encodeProject(
        snapshot: SaveSnapshot,
        includeResults: Boolean = true
    ): ByteArray =
        withContext(Dispatchers.Default) {
            val settings = snapshot.settings.let { s ->
                s.copy(
                    rootDir = services.relativeToFile(snapshot.path, s.rootDir),
                    configPath = services.relativeToFile(snapshot.path, s.configPath),
                    outputDir = services.relativeToFile(snapshot.path, s.outputDir),
                )
            }
            ProjectCodec.encode(
                ProjectFile(
                    settings = settings,
                    assignments = if (includeResults) snapshot.assignments.map { (source, tasks) ->
                        TaskAssignment(
                            source,
                            tasks
                        )
                    } else emptyList(),
                    outcome = if (includeResults) snapshot.outcome?.copy(results = snapshot.results) else null,
                ),
            )
        }

    private suspend fun save(snapshot: SaveSnapshot) = saveMutex.withLock {
        if (snapshot == lastSaved) return@withLock
        _state.update { if (it.project?.path == snapshot.path) it.copy(saveStatus = SaveStatus.Saving) else it }
        val status = try {
            val bytes = encodeProject(snapshot)
            services.toolkit.writeBinaryFile(snapshot.path, bytes)
            lastSaved = snapshot
            SaveStatus.Saved
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SaveStatus.Failed("プロジェクトを保存できませんでした: ${e.message}")
        }
        _state.update { if (it.project?.path == snapshot.path) it.copy(saveStatus = status) else it }
    }

    private fun rememberProject(path: String) {
        services.settings.put(KEY_LAST_PROJECT, path)
        addRecentProject(path)
    }

    /** 最近のプロジェクトの先頭に追加する */
    private fun addRecentProject(path: String) {
        val recent = (listOf(path) + state.value.recentProjects.filter { it != path }).take(
            MAX_RECENT_PROJECTS
        )
        services.settings.put(KEY_RECENT_PROJECTS, recent.joinToString("\n"))
        _state.update { it.copy(recentProjects = recent) }
    }

    private fun loadRecentProjects(): List<String> =
        services.settings.get(KEY_RECENT_PROJECTS).orEmpty().lines().filter { it.isNotBlank() }

    // ------------------------------------------------------------ setup

    fun updateSetup(transform: (SetupState) -> SetupState) {
        _state.update { it.copy(setup = transform(it.setup)) }
        saveDefaultOptions(_state.value.setup)
    }

    fun browseRootDir() {
        val current = state.value.setup
        viewModelScope.launch {
            services.pickDirectory(
                "提出物ディレクトリを選択",
                current.rootDir.ifBlank { projectDir() })?.let { path ->
                updateSetup { it.copy(rootDir = path) }
            }
        }
    }

    fun browseConfig() {
        val current = state.value.setup
        viewModelScope.launch {
            services.pickFile(
                "コンフィグファイルを選択",
                current.configPath.ifBlank { current.rootDir.ifBlank { projectDir() } },
                listOf("yaml", "yml"),
            )?.let { path -> updateSetup { it.copy(configPath = path) } }
        }
    }

    fun browseJdkBinDir() {
        viewModelScope.launch {
            services.pickDirectory(
                "JDK の bin ディレクトリを選択",
                state.value.setup.jdkBinDir.ifBlank { null })
                ?.let { path -> updateSetup { it.copy(jdkBinDir = path) } }
        }
    }

    private fun projectDir(): String? = state.value.project?.path?.let(services::parentDirectory)

    // ------------------------------------------------------------ run

    fun start() {
        if (runJob?.isActive == true || state.value.assigningSource != null) return
        stopManualRun()
        val before = state.value
        val setup = before.setup
        val problem = when {
            setup.rootDir.isBlank() -> "提出物ディレクトリを指定してください"
            setup.configPath.isBlank() -> "コンフィグファイルを指定してください"
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(status = RunStatus.Failed(problem)) }
            return
        }

        val parallelism = parallelism()
        val request = GradingRequest(
            rootDir = setup.rootDir,
            configPath = setup.configPath,
            lastStep = setup.lastStep,
            compileParallelism = parallelism,
            testParallelism = if (setup.parallelTests) parallelism else 1,
            cleanWorkspace = setup.cleanWorkspace,
            toolchain = JavaToolchain(setup.jdkBinDir.trim()),
            assignments = before.assignments,
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

        // 中止・失敗したときは前回の結果に戻す (プロジェクトに保存した結果を消さない)
        fun restorePrevious(status: RunStatus) = _state.update {
            it.copy(
                status = status,
                outcome = before.outcome,
                results = before.results,
                selectedId = before.selectedId
            )
        }
        runJob = viewModelScope.launch {
            try {
                val outcome =
                    withContext(Dispatchers.Default) { pipeline.run(request, ::onPipelineEvent) }
                val (results, carried) = carryOverManualJudges(before.results, outcome.results)
                if (carried > 0) appendLog(
                    "前回の手動判定を $carried 件引き継ぎました",
                    LogLevel.Info
                )
                _state.update {
                    it.copy(
                        status = RunStatus.Finished,
                        outcome = outcome,
                        results = results,
                        selectedId = results.firstOrNull()?.id,
                        // 既定ではすべての学生を閉じておく (最初の結果を選んでいても開かない)
                        resultView = it.resultView.copy(
                            collapsed = outcome.students.map { s -> s.id }.toSet(),
                            collapsedTasks = emptySet(),
                        ),
                    )
                }
            } catch (e: CancellationException) {
                appendLog("中止しました", LogLevel.Warning)
                restorePrevious(RunStatus.Cancelled)
                throw e
            } catch (e: Throwable) {
                appendLog(e.stackTraceToString(), LogLevel.Error)
                restorePrevious(RunStatus.Failed(e.message ?: e.toString()))
            }
        }
    }

    fun cancel() {
        runJob?.cancel()
    }

    private fun parallelism() = services.availableProcessors.coerceIn(1, 16)

    private fun onPipelineEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Progress -> _state.update {
                if (it.status is RunStatus.Running) it.copy(
                    status = RunStatus.Running(
                        event.stage,
                        event.completed,
                        event.total
                    )
                ) else it
            }

            is PipelineEvent.Log -> appendLog(event.message, event.level)
        }
    }

    private fun appendLog(message: String, level: LogLevel) {
        _state.update {
            it.copy(
                logs = (it.logs + LogLine(
                    message,
                    level
                )).takeLast(MAX_LOG_LINES)
            )
        }
    }

    // ------------------------------------------------------------ review

    /** 結果を選ぶ。閉じている学生・課題の結果なら、それを開く */
    fun select(id: Int?) {
        _state.update { s ->
            val result = s.results.firstOrNull { it.id == id }
            s.copy(selectedId = id, resultView = result?.let(s.resultView::reveal) ?: s.resultView)
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

    /** [result] が手動で判定する課題 (GUI の課題など) の結果か */
    fun isManualTask(result: TestResult): Boolean {
        val task = result.taskName ?: return false
        return state.value.outcome?.config?.tasks?.get(task)?.manual == true
    }

    /**
     * 手動で判定する課題の結果を1件実行する (時間制限なし。ウィンドウを閉じるなどでプログラムが終わるまで待つ)。
     * 実行中は他の結果を実行できない。終わったら出力を結果に記録する (判定は手動で行う)
     */
    fun runManually(resultId: Int) {
        val s = state.value
        if (s.isRunning || s.manualRunId != null) return
        val result = s.results.firstOrNull { it.id == resultId } ?: return
        if (!isManualTask(result)) return
        val target = result.compile as? CompileOutcome.Success ?: return
        val testCase = result.testCase ?: TestCase(name = "")
        _state.update { it.copy(manualRunId = resultId) }
        appendLog("${result.source.displayPath} を実行しています (${result.taskName}: ${testCase.name.ifEmpty { "-" }})", LogLevel.Info)
        manualRunJob = viewModelScope.launch {
            try {
                val execution = services.toolkit.execute(target, testCase, JavaToolchain(s.setup.jdkBinDir.trim()), -1)
                _state.update { st ->
                    st.copy(results = st.results.map { if (it.id == resultId) it.copy(execution = execution) else it })
                }
                appendLog("${result.source.displayPath} の実行が終わりました (終了コード ${execution.exitCode ?: "-"})", LogLevel.Info)
            } catch (e: CancellationException) {
                appendLog("${result.source.displayPath} の実行を停止しました", LogLevel.Warning)
                throw e
            } catch (e: Exception) {
                appendLog("${result.source.displayPath} を実行できませんでした: ${e.message}", LogLevel.Error)
            } finally {
                _state.update { if (it.manualRunId == resultId) it.copy(manualRunId = null) else it }
            }
        }
    }

    /** 実行中のプログラムを強制終了する */
    fun stopManualRun() {
        manualRunJob?.cancel()
        manualRunJob = null
        _state.update { it.copy(manualRunId = null) }
    }

    /** 課題を特定できなかった (NF) ソースに課題を割り当て、その課題のテストを実行する */
    fun assignTask(resultId: Int, task: TaskName) {
        val s = state.value
        if (s.isRunning || s.assigningSource != null) return
        val outcome = s.outcome ?: return
        val notFound = s.results.firstOrNull { it.id == resultId && it.taskName == null } ?: return
        val compiled =
            outcome.compiled.firstOrNull { it.source.path == notFound.source.path } ?: run {
                appendLog(
                    "${notFound.source.displayPath}: コンパイル結果が見つからないため割り当てできません",
                    LogLevel.Error
                )
                return
            }
        val sourcePath = notFound.source.displayPath
        _state.update { it.copy(assigningSource = sourcePath) }
        viewModelScope.launch {
            try {
                val tested = withContext(Dispatchers.Default) {
                    pipeline.testSource(
                        config = outcome.config,
                        compiled = compiled,
                        tasks = listOf(task),
                        toolchain = JavaToolchain(s.setup.jdkBinDir.trim()),
                        parallelism = if (s.setup.parallelTests) parallelism() else 1,
                    )
                }
                _state.update { st ->
                    val results = st.results.replaceWithAssigned(notFound, tested)
                    val firstNew =
                        results.firstOrNull { it.source == notFound.source && it.assigned }?.id
                    st.copy(
                        results = results,
                        assignments = st.assignments + (sourcePath to listOf(task)),
                        assigningSource = null,
                        selectedId = firstNew ?: st.selectedId,
                    )
                }
                appendLog("$sourcePath に課題「$task」を割り当ててテストしました", LogLevel.Info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog(
                    "$sourcePath: 割り当てた課題のテストに失敗しました: ${e.message}",
                    LogLevel.Error
                )
                _state.update { it.copy(assigningSource = null) }
            }
        }
    }

    /**
     * ソースと課題の紐づけを外す (1つの課題に複数のソースが当たる CF の対策など)。
     * テストは実行し直さず、その課題の結果を取り除く。課題が残らなければ NF にする
     */
    fun unlinkTask(resultId: Int) {
        val s = state.value
        if (s.isRunning || s.assigningSource != null) return
        val target = s.results.firstOrNull { it.id == resultId } ?: return
        val task = target.taskName ?: return
        val source = target.source
        val remaining = s.results.filter { it.source == source }.mapNotNull { it.taskName }.distinct() - task
        _state.update { st ->
            val results = st.results.unlinkTask(source, task)
            st.copy(
                results = results,
                assignments = st.assignments + (source.displayPath to remaining),
                selectedId = results.firstOrNull { it.source == source }?.id ?: st.selectedId,
            )
        }
        appendLog(
            "${source.displayPath} を課題「$task」から外しました" +
                if (remaining.isEmpty()) " (どの課題でもなくなりました)" else "",
            LogLevel.Info,
        )
    }

    /**
     * 手動で決めた課題 (割り当て・紐づけの解除) をやめ、ソースの内容から課題を判定し直してテストする。
     * 同じ課題・テストケースの手動判定は引き継ぐ
     */
    fun restoreAutoTasks(resultId: Int) {
        val s = state.value
        if (s.isRunning || s.assigningSource != null) return
        val outcome = s.outcome ?: return
        val target = s.results.firstOrNull { it.id == resultId && it.assigned } ?: return
        val source = target.source
        val compiled = outcome.compiled.firstOrNull { it.source.path == source.path } ?: run {
            appendLog("${source.displayPath}: コンパイル結果が見つからないため判定し直せません", LogLevel.Error)
            return
        }
        _state.update { it.copy(assigningSource = source.displayPath) }
        viewModelScope.launch {
            try {
                val tested = withContext(Dispatchers.Default) {
                    pipeline.testSource(
                        config = outcome.config,
                        compiled = compiled,
                        tasks = null,
                        toolchain = JavaToolchain(s.setup.jdkBinDir.trim()),
                        parallelism = if (s.setup.parallelTests) parallelism() else 1,
                    )
                }
                _state.update { st ->
                    val previous = st.results.filter { it.source == source }
                    val (carried, _) = carryOverManualJudges(previous, tested)
                    val results = st.results.replaceSourceResults(source, carried)
                    st.copy(
                        results = results,
                        assignments = st.assignments - source.displayPath,
                        assigningSource = null,
                        selectedId = results.firstOrNull { it.source == source }?.id ?: st.selectedId,
                    )
                }
                appendLog("${source.displayPath} の課題を自動で判定し直しました", LogLevel.Info)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("${source.displayPath}: 判定し直せませんでした: ${e.message}", LogLevel.Error)
                _state.update { it.copy(assigningSource = null) }
            }
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

    fun openContainingFolder(path: String) {
        services.parentDirectory(path)?.let(services::openPath)
    }

    // ------------------------------------------------------------ export

    /**
     * 採点結果を [format] で書き出す。保存先はダイアログで選ぶ
     * (初期位置は前回書き出したディレクトリ、なければ提出物ディレクトリの親)
     */
    fun export(format: ReportFormat) {
        val snapshot = state.value
        if (!snapshot.canExport) return
        val outcome = snapshot.outcome ?: return
        val setup = snapshot.setup
        val initialDir = setup.outputDir.ifBlank {
            setup.rootDir.takeIf { it.isNotBlank() }?.let(services::parentDirectory) ?: projectDir()
        }
        viewModelScope.launch {
            val path = services.pickSaveFile(
                title = "結果を書き出す (${format.label})",
                initial = initialDir,
                extensions = listOf(format.extension),
                suggestedName = outcome.config.outputFileName,
            ) ?: return@launch
            _state.update { it.copy(export = ExportStatus.Writing) }
            val status = try {
                val paths = withContext(Dispatchers.Default) {
                    val report = buildReport(outcome.config, outcome.students, state.value.results)
                    services.toolkit.writeReport(path, format, report)
                }
                paths.forEach { appendLog("書き出しました: $it", LogLevel.Info) }
                ExportStatus.Done(paths)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                appendLog("書き出しに失敗しました: ${e.message}", LogLevel.Error)
                ExportStatus.Failed("書き出しに失敗しました: ${e.message}")
            }
            _state.update { it.copy(export = status) }
            // 次の書き出しの初期値にする
            updateSetup {
                it.copy(outputDir = services.parentDirectory(path) ?: it.outputDir, reportFormat = format)
            }
        }
    }

    fun dismissExport() = _state.update { it.copy(export = null) }

    // ------------------------------------------------------------ 新しいプロジェクトの初期値
    // パスはプロジェクトごとに .taa に保存するので、Preferences には実行オプションと JDK だけを残す

    private fun loadDefaultOptions(): SetupState {
        val s = services.settings
        val default = SetupState()
        s.remove(LEGACY_KEY_AUTO_EXPORT)
        return SetupState(
            lastStep = s.get(KEY_LAST_STEP)
                ?.let { v -> LastStep.entries.firstOrNull { it.name == v } } ?: default.lastStep,
            parallelTests = s.get(KEY_PARALLEL)?.toBooleanStrictOrNull() ?: default.parallelTests,
            cleanWorkspace = s.get(KEY_CLEAN)?.toBooleanStrictOrNull() ?: default.cleanWorkspace,
            reportFormat = s.get(KEY_FORMAT)
                ?.let { v -> ReportFormat.entries.firstOrNull { it.name == v } }
                ?: default.reportFormat,
            jdkBinDir = s.get(KEY_JDK).orEmpty(),
        )
    }

    private fun saveDefaultOptions(setup: SetupState) {
        val s = services.settings
        s.put(KEY_LAST_STEP, setup.lastStep.name)
        s.put(KEY_PARALLEL, setup.parallelTests.toString())
        s.put(KEY_CLEAN, setup.cleanWorkspace.toString())
        s.put(KEY_FORMAT, setup.reportFormat.name)
        s.put(KEY_JDK, setup.jdkBinDir)
    }

    /** プロジェクト導入前に Preferences に保存していたパスを [setup] に移し、Preferences からは消す */
    private fun takeLegacyPaths(setup: SetupState): SetupState {
        val s = services.settings
        val migrated = setup.copy(
            rootDir = s.get(LEGACY_KEY_ROOT).orEmpty(),
            configPath = s.get(LEGACY_KEY_CONFIG).orEmpty(),
            outputDir = s.get(LEGACY_KEY_OUTPUT).orEmpty(),
        )
        listOf(LEGACY_KEY_ROOT, LEGACY_KEY_CONFIG, LEGACY_KEY_OUTPUT).forEach(s::remove)
        return migrated
    }

    private companion object {
        const val KEY_LAST_STEP = "lastStep"
        const val KEY_PARALLEL = "parallelTests"
        const val KEY_CLEAN = "cleanWorkspace"
        const val KEY_FORMAT = "reportFormat"
        const val KEY_JDK = "jdkBinDir"
        const val KEY_LAST_PROJECT = "lastProject"
        const val KEY_RECENT_PROJECTS = "recentProjects"

        /** プロジェクト導入前のパス。最初のプロジェクトに引き継いだら消す */
        const val LEGACY_KEY_ROOT = "rootDir"
        const val LEGACY_KEY_CONFIG = "configPath"
        const val LEGACY_KEY_OUTPUT = "outputDir"

        /** 完了時に自動で書き出す設定 (書き出しは実行後に任意で行うようにしたので廃止) */
        const val LEGACY_KEY_AUTO_EXPORT = "autoExport"
    }
}
