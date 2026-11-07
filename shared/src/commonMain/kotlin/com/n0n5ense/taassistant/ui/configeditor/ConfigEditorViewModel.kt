package com.n0n5ense.taassistant.ui.configeditor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.n0n5ense.taassistant.core.config.ConfigDraft
import com.n0n5ense.taassistant.core.config.ConfigMigrator
import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.DraftIssue
import com.n0n5ense.taassistant.core.config.LegacyConfigException
import com.n0n5ense.taassistant.core.config.TaskDraft
import com.n0n5ense.taassistant.core.config.TestCaseDraft
import com.n0n5ense.taassistant.core.config.toConfig
import com.n0n5ense.taassistant.core.config.toDraft
import com.n0n5ense.taassistant.core.config.validate
import com.n0n5ense.taassistant.platform.PlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditorMode(val label: String) { Form("フォーム"), Yaml("YAML") }

sealed interface EditorSelection {
    data object General : EditorSelection
    data class Task(val key: Long) : EditorSelection
}

/** スナックバーで表示するメッセージ。[id] で同じ文面の再表示を区別する */
data class EditorMessage(
    val text: String,
    val isError: Boolean,
    val canUndo: Boolean = false,
    val id: Long = nextMessageId++,
) {
    private companion object {
        var nextMessageId = 1L
    }
}

/** 削除を取り消すための直前の状態 */
data class UndoSnapshot(val draft: ConfigDraft, val selection: EditorSelection)

/** 旧形式のファイルを変換して開いている (まだ保存していない) */
data class MigrationInfo(
    val sourcePath: String,
    val originalText: String,
    val notes: List<String>,
)

/** 未保存の変更を破棄してよいか確認中の操作 */
enum class PendingAction { New, Open }

data class ConfigEditorState(
    val initialized: Boolean = false,
    val path: String? = null,
    val draft: ConfigDraft = ConfigDraft(),
    /** 最後に読み込み / 保存した時点の内容 */
    val savedDraft: ConfigDraft = ConfigDraft(),
    val selection: EditorSelection = EditorSelection.General,
    val mode: EditorMode = EditorMode.Form,
    val yamlText: String = "",
    val yamlBaseline: String = "",
    val yamlError: String? = null,
    /** 読み込んだファイルにコメントがあった (保存すると消える) */
    val hadComments: Boolean = false,
    val migration: MigrationInfo? = null,
    val message: EditorMessage? = null,
    val pending: PendingAction? = null,
    val undo: UndoSnapshot? = null,
    /** 閉じているテストケース (タブを切り替えても残す) */
    val collapsedTestCases: Set<Long> = emptySet(),
    /** 「ディレクトリ名で試す」の入力 (保存しない) */
    val dirNameSample: String = "",
) {
    val issues: List<DraftIssue> = draft.validate()
    val isDirty: Boolean
        get() = draft.persistentPart() != savedDraft ||
                migration != null ||
                (mode == EditorMode.Yaml && yamlText != yamlBaseline)
    val selectedTask: TaskDraft?
        get() = (selection as? EditorSelection.Task)?.let { s -> draft.tasks.firstOrNull { it.key == s.key } }
}

class ConfigEditorViewModel(private val services: PlatformServices) : ViewModel() {
    private val _state = MutableStateFlow(ConfigEditorState())
    val state: StateFlow<ConfigEditorState> = _state.asStateFlow()
    private var nextKey = 1L
    private fun newKey(): Long = nextKey++

    // ------------------------------------------------------------ ファイル

    /**
     * エディタを開いたときに呼ぶ。採点設定のコンフィグを読み込む。
     * 未保存の変更があるときは、別のファイルに切り替えずに今の内容を残す。
     */
    fun openForEditing(configPath: String) {
        val s = state.value
        when {
            !s.initialized -> {
                _state.update { it.copy(initialized = true) }
                if (configPath.isNotBlank()) load(
                    configPath,
                    quietIfMissing = true
                ) else resetToNew()
            }

            configPath.isNotBlank() && configPath != s.path && !s.isDirty -> load(configPath)
        }
    }

    fun requestNew() = runOrConfirm(PendingAction.New)
    fun requestOpen() = runOrConfirm(PendingAction.Open)

    fun confirmDiscard() {
        val action = state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        perform(action)
    }

    fun cancelDiscard() = _state.update { it.copy(pending = null) }

    /** 保存してから、確認中の操作を続ける */
    fun saveAndContinue() {
        val action = state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        save(onSaved = { perform(action) })
    }

    private fun runOrConfirm(action: PendingAction) {
        if (state.value.isDirty) _state.update { it.copy(pending = action) } else perform(action)
    }

    private fun perform(action: PendingAction) = when (action) {
        PendingAction.New -> resetToNew()
        PendingAction.Open -> {
            val current = state.value.path
            viewModelScope.launch {
                services.pickFile("コンフィグファイルを開く", current, listOf("yaml", "yml"))
                    ?.let { load(it) }
            }
            Unit
        }
    }

    private fun resetToNew() {
        val draft = ConfigDraft(
            tasks = listOf(
                TaskDraft(
                    key = newKey(),
                    name = "課題1",
                    words = "課題1",
                    testCases = listOf(TestCaseDraft(key = newKey(), name = "test1")),
                ),
            ),
        )
        _state.update {
            ConfigEditorState(
                initialized = true,
                draft = draft,
                savedDraft = ConfigDraft(),
                selection = EditorSelection.Task(draft.tasks.first().key),
                message = EditorMessage("新しいコンフィグを作成しました", isError = false),
            )
        }
    }

    private fun load(path: String, quietIfMissing: Boolean = false) {
        viewModelScope.launch {
            try {
                val text = services.toolkit.readTextFile(path)
                val draft = ConfigParser.parse(text).toDraft(::newKey)
                _state.update {
                    ConfigEditorState(
                        initialized = true,
                        path = path,
                        draft = draft,
                        savedDraft = draft.persistentPart(),
                        hadComments = text.lineSequence()
                            .any { line -> line.trimStart().startsWith("#") || " #" in line },
                        message = EditorMessage("読み込みました: $path", isError = false),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LegacyConfigException) {
                loadMigrated(path)
            } catch (e: Exception) {
                if (quietIfMissing) {
                    resetToNew()
                } else {
                    _state.update {
                        it.copy(
                            message = EditorMessage(
                                "読み込めませんでした: ${e.message}",
                                isError = true
                            )
                        )
                    }
                }
            }
        }
    }

    /** 旧形式のファイルを新しい形式に変換して開く。保存するまでファイルは変更しない */
    private suspend fun loadMigrated(path: String) {
        try {
            val text = services.toolkit.readTextFile(path)
            val migration =
                ConfigMigrator.migrate(text) ?: error("変換できる旧形式の項目が見つかりません")
            val draft = migration.config.toDraft(::newKey)
            _state.update {
                ConfigEditorState(
                    initialized = true,
                    path = path,
                    draft = draft,
                    savedDraft = draft.persistentPart(),
                    hadComments = text.lineSequence()
                        .any { line -> line.trimStart().startsWith("#") || " #" in line },
                    migration = MigrationInfo(path, text, migration.notes),
                    message = EditorMessage(
                        "旧形式のコンフィグを変換しました。保存すると新しい形式で書き出されます",
                        isError = false
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    message = EditorMessage(
                        "旧形式のコンフィグを変換できませんでした: ${e.message}",
                        isError = true
                    )
                )
            }
        }
    }

    /** 保存に成功したらパスを返す */
    fun save(onSaved: (String) -> Unit = {}) {
        val path = state.value.path
        if (path == null) saveAs(onSaved) else write(path, onSaved)
    }

    fun saveAs(onSaved: (String) -> Unit = {}) {
        if (!applyYamlIfNeeded() || !checkValid()) return
        viewModelScope.launch {
            val path = services.pickSaveFile(
                "コンフィグファイルを保存",
                state.value.path,
                listOf("yaml", "yml"),
                suggestedName = "config"
            )
                ?: return@launch
            write(path, onSaved)
        }
    }

    private fun write(path: String, onSaved: (String) -> Unit) {
        if (!applyYamlIfNeeded() || !checkValid()) return
        val draft = state.value.draft
        val yaml = ConfigParser.encode(draft.toConfig())
        val migration = state.value.migration
        viewModelScope.launch {
            try {
                // 旧形式のファイルを上書きするときは、元の内容をバックアップしておく
                val backup = migration?.takeIf { it.sourcePath == path }?.let { "$path.bak" }
                backup?.let { services.toolkit.writeTextFile(it, migration.originalText) }
                services.toolkit.writeTextFile(path, yaml)
                _state.update {
                    it.copy(
                        path = path,
                        savedDraft = draft.persistentPart(),
                        yamlText = if (it.mode == EditorMode.Yaml) yaml else it.yamlText,
                        yamlBaseline = if (it.mode == EditorMode.Yaml) yaml else it.yamlBaseline,
                        hadComments = false,
                        migration = null,
                        message = EditorMessage(
                            "保存しました: $path" + (backup?.let { b -> " (変換前のファイルを $b に残しました)" }
                                ?: ""),
                            isError = false,
                        ),
                    )
                }
                onSaved(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        message = EditorMessage(
                            "保存できませんでした: ${e.message}",
                            isError = true
                        )
                    )
                }
            }
        }
    }

    private fun checkValid(): Boolean {
        val issues = state.value.issues
        if (issues.isEmpty()) return true
        showFirstIssue()
        _state.update {
            it.copy(
                message = EditorMessage(
                    "エラーが ${issues.size} 件あるため保存できません: ${issues.first().message}",
                    isError = true
                )
            )
        }
        return false
    }

    // ------------------------------------------------------------ モード

    fun switchMode(mode: EditorMode) {
        val s = state.value
        if (s.mode == mode) return
        when (mode) {
            EditorMode.Yaml -> {
                if (s.issues.isNotEmpty()) {
                    _state.update {
                        it.copy(
                            message = EditorMessage(
                                "エラーを直してから YAML 表示に切り替えてください",
                                isError = true
                            )
                        )
                    }
                    return
                }
                val yaml = ConfigParser.encode(s.draft.toConfig())
                _state.update {
                    it.copy(
                        mode = EditorMode.Yaml,
                        yamlText = yaml,
                        yamlBaseline = yaml,
                        yamlError = null,
                        message = null
                    )
                }
            }

            EditorMode.Form -> if (applyYamlIfNeeded()) _state.update { it.copy(mode = EditorMode.Form) }
        }
    }

    fun updateYaml(text: String) = _state.update { it.copy(yamlText = text, yamlError = null) }

    /** YAML 表示中なら、テキストを解析してフォームの内容に反映する */
    private fun applyYamlIfNeeded(): Boolean {
        val s = state.value
        if (s.mode != EditorMode.Yaml || s.yamlText == s.yamlBaseline) return true
        return try {
            val parsed = ConfigParser.parse(s.yamlText).toDraft(::newKey)
            // 出力例は名前が同じテストケースに引き継ぐ
            val samples =
                s.draft.tasks.flatMap { t -> t.testCases.map { (t.name to it.name) to it.sampleOutput } }
                    .toMap()
            val draft = parsed.copy(
                tasks = parsed.tasks.map { t ->
                    t.copy(testCases = t.testCases.map { it.copy(sampleOutput = samples[t.name to it.name].orEmpty()) })
                },
            )
            val selectedName = s.selectedTask?.name
            _state.update {
                it.copy(
                    draft = draft,
                    undo = null,
                    yamlBaseline = it.yamlText,
                    yamlError = null,
                    selection = draft.tasks.firstOrNull { t -> t.name == selectedName }
                        ?.let { t -> EditorSelection.Task(t.key) } ?: EditorSelection.General,
                )
            }
            true
        } catch (e: Exception) {
            _state.update { it.copy(yamlError = e.message ?: e.toString()) }
            false
        }
    }

    // ------------------------------------------------------------ 編集

    fun select(selection: EditorSelection) = _state.update { it.copy(selection = selection) }

    /** 表示し終えたメッセージを消す (その間に次のメッセージが来ていたら残す) */
    fun dismissMessage(id: Long) =
        _state.update { if (it.message?.id == id) it.copy(message = null) else it }

    fun setTestCasesCollapsed(keys: Collection<Long>, collapsed: Boolean) = _state.update {
        it.copy(collapsedTestCases = if (collapsed) it.collapsedTestCases + keys else it.collapsedTestCases - keys.toSet())
    }

    fun updateDirNameSample(text: String) = _state.update { it.copy(dirNameSample = text) }

    fun dismissCommentsNotice() = _state.update { it.copy(hadComments = false) }

    /** 最初のエラーがある画面 (全体設定 / 課題) を開く */
    fun showFirstIssue() {
        _state.update { s ->
            val issue = s.issues.firstOrNull() ?: return@update s
            s.copy(
                mode = EditorMode.Form,
                selection = issue.taskKey?.let { EditorSelection.Task(it) }
                    ?: EditorSelection.General,
            )
        }
    }

    /** 直前の削除を取り消す */
    fun undo() = _state.update { s ->
        val snapshot = s.undo ?: return@update s
        s.copy(draft = snapshot.draft, selection = snapshot.selection, undo = null, message = null)
    }

    fun updateGeneral(transform: (ConfigDraft) -> ConfigDraft) =
        _state.update { it.copy(draft = transform(it.draft), undo = null) }

    fun addTask() {
        val key = newKey()
        _state.update { s ->
            val name = generateSequence(s.draft.tasks.size + 1) { it + 1 }
                .map { "課題$it" }
                .first { candidate -> s.draft.tasks.none { it.name == candidate } }
            val task = TaskDraft(
                key,
                name = name,
                words = name,
                testCases = listOf(TestCaseDraft(newKey(), name = "test1"))
            )
            s.copy(
                draft = s.draft.copy(tasks = s.draft.tasks + task),
                selection = EditorSelection.Task(key),
                undo = null
            )
        }
    }

    fun updateTask(key: Long, transform: (TaskDraft) -> TaskDraft) = editTasks { tasks ->
        tasks.map { if (it.key == key) transform(it) else it }
    }

    fun removeTask(key: Long) {
        _state.update { s ->
            val index = s.draft.tasks.indexOfFirst { it.key == key }
            if (index < 0) return@update s
            val removed = s.draft.tasks[index]
            val tasks = s.draft.tasks.filterNot { it.key == key }
            val next = tasks.getOrNull(index.coerceAtMost(tasks.lastIndex))
            s.copy(
                draft = s.draft.copy(tasks = tasks),
                selection = next?.let { EditorSelection.Task(it.key) } ?: EditorSelection.General,
                undo = UndoSnapshot(s.draft, s.selection),
                message = EditorMessage(
                    "「${removed.name.ifBlank { "(無題)" }}」を削除しました",
                    isError = false,
                    canUndo = true
                ),
            )
        }
    }

    fun moveTask(key: Long, delta: Int) = editTasks { it.move(key, delta) { t -> t.key } }

    fun duplicateTask(key: Long) {
        val newTaskKey = newKey()
        _state.update { s ->
            val index = s.draft.tasks.indexOfFirst { it.key == key }
            if (index < 0) return@update s
            val source = s.draft.tasks[index]
            val copy = source.copy(
                key = newTaskKey,
                name = source.name + " (コピー)",
                testCases = source.testCases.map { it.copy(key = newKey()) },
            )
            val tasks = s.draft.tasks.toMutableList().apply { add(index + 1, copy) }
            s.copy(
                draft = s.draft.copy(tasks = tasks),
                selection = EditorSelection.Task(newTaskKey),
                undo = null
            )
        }
    }

    fun addTestCase(taskKey: Long) {
        val key = newKey()
        updateTask(taskKey) { task ->
            val name = generateSequence(task.testCases.size + 1) { it + 1 }
                .map { "test$it" }
                .first { candidate -> task.testCases.none { it.name == candidate } }
            task.copy(testCases = task.testCases + TestCaseDraft(key, name = name))
        }
    }

    fun updateTestCase(taskKey: Long, caseKey: Long, transform: (TestCaseDraft) -> TestCaseDraft) =
        updateTask(taskKey) { task ->
            task.copy(testCases = task.testCases.map { if (it.key == caseKey) transform(it) else it })
        }

    fun removeTestCase(taskKey: Long, caseKey: Long) {
        val before = state.value
        val removed =
            before.draft.tasks.firstOrNull { it.key == taskKey }?.testCases?.firstOrNull { it.key == caseKey }
                ?: return
        updateTask(taskKey) { task -> task.copy(testCases = task.testCases.filterNot { it.key == caseKey }) }
        _state.update {
            it.copy(
                undo = UndoSnapshot(before.draft, before.selection),
                message = EditorMessage(
                    "テストケース「${removed.name.ifBlank { "(無題)" }}」を削除しました",
                    isError = false,
                    canUndo = true
                ),
            )
        }
    }

    fun moveTestCase(taskKey: Long, caseKey: Long, delta: Int) =
        updateTask(taskKey) { task ->
            task.copy(
                testCases = task.testCases.move(
                    caseKey,
                    delta
                ) { it.key })
        }

    fun duplicateTestCase(taskKey: Long, caseKey: Long) {
        val key = newKey()
        updateTask(taskKey) { task ->
            val index = task.testCases.indexOfFirst { it.key == caseKey }
            if (index < 0) return@updateTask task
            val source = task.testCases[index]
            val cases = task.testCases.toMutableList()
                .apply { add(index + 1, source.copy(key = key, name = source.name + "-copy")) }
            task.copy(testCases = cases)
        }
    }

    private fun editTasks(transform: (List<TaskDraft>) -> List<TaskDraft>) =
        _state.update {
            it.copy(
                draft = it.draft.copy(tasks = transform(it.draft.tasks)),
                undo = null
            )
        }
}

private fun <T> List<T>.move(key: Long, delta: Int, keyOf: (T) -> Long): List<T> {
    val index = indexOfFirst { keyOf(it) == key }
    val target = index + delta
    if (index < 0 || target !in indices) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}
