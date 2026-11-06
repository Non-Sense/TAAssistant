package com.n0n5ense.taassistant.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.GradingPipeline
import com.n0n5ense.taassistant.core.grading.GradingRequest
import com.n0n5ense.taassistant.core.grading.JavaToolchain
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.grading.LogLevel
import com.n0n5ense.taassistant.core.grading.PipelineEvent
import com.n0n5ense.taassistant.core.grading.Stage
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.model.encoding
import com.n0n5ense.taassistant.core.project.ProjectCodec
import com.n0n5ense.taassistant.core.project.ProjectFile
import com.n0n5ense.taassistant.core.project.ProjectSettings
import com.n0n5ense.taassistant.core.project.TaskAssignment
import com.n0n5ense.taassistant.core.project.carryOverManualJudges
import com.n0n5ense.taassistant.core.project.removeAssigned
import com.n0n5ense.taassistant.core.project.replaceWithAssigned
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

data class ExportStatus(val message: String, val paths: List<String>, val isError: Boolean)

/** 開いているプロジェクト */
data class OpenProject(val path: String) {
    val name: String get() = path.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".${ProjectCodec.EXTENSION}")
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
    val selectedId: Int? = null,
    val export: ExportStatus? = null,
    /** 詳細タブの表示状態 (タブを切り替えても残す) */
    val resultView: ResultViewState = ResultViewState(),
) {
    val isRunning: Boolean get() = status is RunStatus.Running
    val canExport: Boolean get() = !isRunning && outcome?.lastStep == LastStep.Test
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
    project?.takeUnless { isRunning || loadingProject }?.let { SaveSnapshot(it.path, setup, assignments, outcome, results) }

@OptIn(FlowPreview::class)
class AppViewModel(
    private val services: PlatformServices,
) : ViewModel() {
    private val pipeline = GradingPipeline(services.toolkit)
    private val _state = MutableStateFlow(UiState(recentProjects = loadRecentProjects()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var runJob: Job? = null

    private val saveMutex = Mutex()
    private var lastSaved: SaveSnapshot? = null

    init {
        // 変更があれば、少し待ってから自動保存する
        viewModelScope.launch {
            state.map { it.saveSnapshot() }
                .distinctUntilChanged()
                .debounce(500)
                .collect { snapshot -> if (snapshot != null) save(snapshot) }
        }
        // 前回開いていたプロジェクトを開く
        services.settings.get(KEY_LAST_PROJECT)?.takeIf { it.isNotBlank() && services.fileExists(it) }?.let { path ->
            viewModelScope.launch { load(path) }
        }
    }

    // ------------------------------------------------------------ project

    fun newProject() {
        if (state.value.isRunning) return
        val initial = state.value.project?.path?.let(services::parentDirectory)
        val path = services.pickSaveFile("新しいプロジェクトの保存先", initial, listOf(ProjectCodec.EXTENSION)) ?: return
        viewModelScope.launch {
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
        val target = path ?: services.pickFile(
            "プロジェクトを開く",
            state.value.project?.path ?: state.value.recentProjects.firstOrNull(),
            listOf(ProjectCodec.EXTENSION),
        ) ?: return
        viewModelScope.launch {
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
            _state.update { UiState(recentProjects = it.recentProjects) }
        }
    }

    fun dismissProjectError() = _state.update { it.copy(projectError = null) }

    fun dismissProjectNotice() = _state.update { it.copy(projectNotice = null) }

    /** 今の内容を別のファイルに保存し、以後はそのファイルを開いている状態にする (元のファイルは最後に保存した状態で残る) */
    fun saveProjectAs() {
        val path = pickProjectCopyPath("別名で保存") ?: return
        viewModelScope.launch {
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
        val path = pickProjectCopyPath(if (includeResults) "プロジェクトを複製" else "設定だけを複製") ?: return
        viewModelScope.launch {
            val snapshot = state.value.saveSnapshot()?.copy(path = path) ?: return@launch
            try {
                services.toolkit.writeBinaryFile(path, encodeProject(snapshot, includeResults))
                addRecentProject(path)
                val what = if (includeResults) "複製しました" else "設定だけを複製しました"
                _state.update { it.copy(projectNotice = ProjectNotice("$what: $path", openPath = path)) }
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
    private fun pickProjectCopyPath(title: String): String? {
        val current = state.value.project ?: return null
        if (state.value.isRunning) return null
        val path = services.pickSaveFile(title, services.parentDirectory(current.path), listOf(ProjectCodec.EXTENSION)) ?: return null
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
                resultView = ResultViewState(collapsed = outcome?.students.orEmpty().map { it.id }.toSet()),
            )
            // 開いただけでは保存し直さない
            lastSaved = loaded.saveSnapshot()
            _state.value = loaded
            rememberProject(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loadingProject = false, projectError = "プロジェクトを開けませんでした: ${e.message}") }
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
    private suspend fun encodeProject(snapshot: SaveSnapshot, includeResults: Boolean = true): ByteArray =
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
                    assignments = if (includeResults) snapshot.assignments.map { (source, tasks) -> TaskAssignment(source, tasks) } else emptyList(),
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
        val recent = (listOf(path) + state.value.recentProjects.filter { it != path }).take(MAX_RECENT_PROJECTS)
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
        services.pickDirectory("提出物ディレクトリを選択", current.rootDir.ifBlank { projectDir() })?.let { path ->
            updateSetup {
                it.copy(rootDir = path, outputDir = it.outputDir.ifBlank { services.parentDirectory(path).orEmpty() })
            }
        }
    }

    fun browseConfig() {
        val current = state.value.setup
        services.pickFile(
            "コンフィグファイルを選択",
            current.configPath.ifBlank { current.rootDir.ifBlank { projectDir() } },
            listOf("yaml", "yml"),
        )?.let { path -> updateSetup { it.copy(configPath = path) } }
    }

    fun browseOutputDir() {
        val current = state.value.setup
        services.pickDirectory("出力先ディレクトリを選択", current.outputDir.ifBlank { current.rootDir.ifBlank { projectDir() } })
            ?.let { path -> updateSetup { it.copy(outputDir = path) } }
    }

    fun browseJdkBinDir() {
        services.pickDirectory("JDK の bin ディレクトリを選択", state.value.setup.jdkBinDir.ifBlank { null })
            ?.let { path -> updateSetup { it.copy(jdkBinDir = path) } }
    }

    private fun projectDir(): String? = state.value.project?.path?.let(services::parentDirectory)

    // ------------------------------------------------------------ run

    fun start() {
        if (runJob?.isActive == true || state.value.assigningSource != null) return
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
            it.copy(status = status, outcome = before.outcome, results = before.results, selectedId = before.selectedId)
        }
        runJob = viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.Default) { pipeline.run(request, ::onPipelineEvent) }
                val (results, carried) = carryOverManualJudges(before.results, outcome.results)
                if (carried > 0) appendLog("前回の手動判定を $carried 件引き継ぎました", LogLevel.Info)
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
                if (outcome.lastStep == LastStep.Test && setup.autoExport) export()
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
                if (it.status is RunStatus.Running) it.copy(status = RunStatus.Running(event.stage, event.completed, event.total)) else it
            }
            is PipelineEvent.Log -> appendLog(event.message, event.level)
        }
    }

    private fun appendLog(message: String, level: LogLevel) {
        _state.update { it.copy(logs = (it.logs + LogLine(message, level)).takeLast(MAX_LOG_LINES)) }
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

    /** 課題を特定できなかった (NF) ソースに課題を割り当て、その課題のテストを実行する */
    fun assignTask(resultId: Int, task: TaskName) {
        val s = state.value
        if (s.isRunning || s.assigningSource != null) return
        val outcome = s.outcome ?: return
        val notFound = s.results.firstOrNull { it.id == resultId && it.taskName == null } ?: return
        val compiled = outcome.compiled.firstOrNull { it.source.path == notFound.source.path } ?: run {
            appendLog("${notFound.source.displayPath}: コンパイル結果が見つからないため割り当てできません", LogLevel.Error)
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
                    val firstNew = results.firstOrNull { it.source == notFound.source && it.assigned }?.id
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
                appendLog("$sourcePath: 割り当てた課題のテストに失敗しました: ${e.message}", LogLevel.Error)
                _state.update { it.copy(assigningSource = null) }
            }
        }
    }

    /** 課題の割り当てを解除して NF に戻す */
    fun unassignTask(resultId: Int) {
        val s = state.value
        if (s.isRunning || s.assigningSource != null) return
        val result = s.results.firstOrNull { it.id == resultId && it.assigned } ?: return
        _state.update { st ->
            val results = st.results.removeAssigned(result.source)
            st.copy(
                results = results,
                assignments = st.assignments - result.source.displayPath,
                selectedId = results.firstOrNull { it.source == result.source && it.taskName == null }?.id,
            )
        }
        appendLog("${result.source.displayPath} の課題の割り当てを解除しました", LogLevel.Info)
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

    // ------------------------------------------------------------ 新しいプロジェクトの初期値
    // パスはプロジェクトごとに .taa に保存するので、Preferences には実行オプションと JDK だけを残す

    private fun loadDefaultOptions(): SetupState {
        val s = services.settings
        val default = SetupState()
        return SetupState(
            lastStep = s.get(KEY_LAST_STEP)?.let { v -> LastStep.entries.firstOrNull { it.name == v } } ?: default.lastStep,
            parallelTests = s.get(KEY_PARALLEL)?.toBooleanStrictOrNull() ?: default.parallelTests,
            cleanWorkspace = s.get(KEY_CLEAN)?.toBooleanStrictOrNull() ?: default.cleanWorkspace,
            autoExport = s.get(KEY_AUTO_EXPORT)?.toBooleanStrictOrNull() ?: default.autoExport,
            reportFormat = s.get(KEY_FORMAT)?.let { v -> ReportFormat.entries.firstOrNull { it.name == v } } ?: default.reportFormat,
            jdkBinDir = s.get(KEY_JDK).orEmpty(),
        )
    }

    private fun saveDefaultOptions(setup: SetupState) {
        val s = services.settings
        s.put(KEY_LAST_STEP, setup.lastStep.name)
        s.put(KEY_PARALLEL, setup.parallelTests.toString())
        s.put(KEY_CLEAN, setup.cleanWorkspace.toString())
        s.put(KEY_AUTO_EXPORT, setup.autoExport.toString())
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
        const val KEY_AUTO_EXPORT = "autoExport"
        const val KEY_FORMAT = "reportFormat"
        const val KEY_JDK = "jdkBinDir"
        const val KEY_LAST_PROJECT = "lastProject"
        const val KEY_RECENT_PROJECTS = "recentProjects"

        /** プロジェクト導入前のパス。最初のプロジェクトに引き継いだら消す */
        const val LEGACY_KEY_ROOT = "rootDir"
        const val LEGACY_KEY_CONFIG = "configPath"
        const val LEGACY_KEY_OUTPUT = "outputDir"
    }
}
