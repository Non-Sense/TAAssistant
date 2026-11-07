package com.n0n5ense.taassistant

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.platform.PlatformServices
import com.n0n5ense.taassistant.platform.createPlatformServices
import com.n0n5ense.taassistant.ui.configeditor.ConfigEditorPanel
import com.n0n5ense.taassistant.ui.configeditor.ConfigEditorViewModel
import com.n0n5ense.taassistant.ui.log.LogScreen
import com.n0n5ense.taassistant.ui.main.AppViewModel
import com.n0n5ense.taassistant.ui.main.MainArea
import com.n0n5ense.taassistant.ui.main.MainTab
import com.n0n5ense.taassistant.ui.main.RunStatus
import com.n0n5ense.taassistant.ui.main.SetupPanel
import com.n0n5ense.taassistant.ui.main.WelcomeScreen
import com.n0n5ense.taassistant.ui.student.StudentScreen
import com.n0n5ense.taassistant.ui.student.StudentViewModel
import com.n0n5ense.taassistant.ui.theme.AppTheme

/** プロジェクトを開いているときの画面 (Navigation 3 のバックスタックのキー) */
private sealed interface Screen {
    /** 設定パネル + 結果・サマリー・ログのタブ */
    data object Grading : Screen

    /** コンフィグエディタ (採点画面から開いて、戻る) */
    data object ConfigEditor : Screen

    /** 実行のログ */
    data object Log : Screen

    /** 学生のページ (課題ごとの結果と提出ファイル) */
    data class Student(val id: StudentId) : Screen
}

/**
 * @param onTitleChange ウィンドウのタイトル (開いているプロジェクト名) が変わったとき
 * @param exitRequested true になったら、プロジェクトを保存してから [onReadyToExit] を呼ぶ
 */
@Composable
fun App(
    services: PlatformServices = remember { createPlatformServices() },
    onTitleChange: (String) -> Unit = {},
    exitRequested: Boolean = false,
    onReadyToExit: () -> Unit = {},
) {
    val appViewModel = viewModel { AppViewModel(services) }
    val state by appViewModel.state.collectAsStateWithLifecycle()
    // エディタの ViewModel はアプリ全体で1つ (採点画面に戻っても、編集中の内容を残す)
    val editorViewModel = viewModel { ConfigEditorViewModel(services) }
    val editorState by editorViewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MainTab.Summary) }
    // 採点が終わったらサマリーを表示する。
    // 画面遷移で作り直される採点画面の中ではなくここで行う (学生のページなどから戻るたびに切り替わらないように)
    val statusKind = state.status::class
    LaunchedEffect(statusKind) {
        if (state.status == RunStatus.Finished && state.results.isNotEmpty()) tab = MainTab.Summary
    }

    val projectName = state.project?.name
    LaunchedEffect(projectName) {
        onTitleChange(projectName?.let { "$it - TA Assistant" } ?: "TA Assistant")
    }
    LaunchedEffect(exitRequested) {
        if (exitRequested) {
            appViewModel.flushSave()
            onReadyToExit()
        }
    }

    // TODO: 一時的にライトモードを強制している。戻すときは darkTheme の指定を消す (既定はシステム設定に従う)
    AppTheme(darkTheme = false) {
        Surface(Modifier.fillMaxSize()) {
            val project = state.project
            if (project == null) {
                WelcomeScreen(state, appViewModel)
                return@Surface
            }
            // プロジェクトを切り替えたら採点画面から始める
            val backStack = remember(project.path) { mutableStateListOf<Screen>(Screen.Grading) }
            val back: () -> Unit =
                { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

            fun open(screen: Screen) {
                if (backStack.last() != screen) backStack.add(screen)
            }

            NavDisplay(
                backStack = backStack,
                onBack = back,
                // 画面ごとにスクロール位置などの状態と ViewModel を持つ (学生のページを閉じると破棄)
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<Screen.Grading> {
                        Row(Modifier.fillMaxSize()) {
                            SetupPanel(
                                state = state,
                                viewModel = appViewModel,
                                configEditorDirty = editorState.isDirty,
                                onEditConfig = { open(Screen.ConfigEditor) },
                                // 新しいコンフィグでエディタを開く (未保存の変更があればエディタで確認する)
                                onNewConfig = {
                                    editorViewModel.requestNew()
                                    open(Screen.ConfigEditor)
                                },
                                onOpenLog = { open(Screen.Log) },
                                modifier = Modifier.width(380.dp).fillMaxHeight(),
                            )
                            VerticalDivider()
                            MainArea(
                                state = state,
                                viewModel = appViewModel,
                                tab = tab,
                                onTabChange = { tab = it },
                                onOpenStudent = { open(Screen.Student(it)) },
                                onOpenLog = { open(Screen.Log) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                    }
                    entry<Screen.ConfigEditor> {
                        // 開くたびに、採点に使っているコンフィグを読み込む (未保存の変更があればそのまま)
                        LaunchedEffect(Unit) { editorViewModel.openForEditing(state.setup.configPath) }
                        ConfigEditorPanel(
                            state = editorState,
                            viewModel = editorViewModel,
                            gradingConfigPath = state.setup.configPath,
                            onUseForGrading = { path ->
                                appViewModel.updateSetup { it.copy(configPath = path) }
                                back()
                            },
                            onBack = back,
                        )
                    }
                    entry<Screen.Log> {
                        LogScreen(state, onBack = back)
                    }
                    entry<Screen.Student> { key ->
                        val outcome = state.outcome
                        val students = outcome?.students.orEmpty()
                        val index = students.indexOfFirst { it.id == key.id }
                        if (outcome == null || index < 0) {
                            // 再実行などで学生がいなくなったら戻る
                            LaunchedEffect(Unit) { back() }
                            return@entry
                        }
                        val student = students[index]

                        // 前後の学生へは、履歴を増やさずに切り替える
                        fun switchTo(other: com.n0n5ense.taassistant.core.model.Student) {
                            backStack[backStack.lastIndex] = Screen.Student(other.id)
                        }
                        StudentScreen(
                            student = student,
                            state = state,
                            appViewModel = appViewModel,
                            viewModel = viewModel(key = "student:${student.id.value}") {
                                StudentViewModel(
                                    services,
                                    student
                                )
                            },
                            onBack = back,
                            onPrevious = students.getOrNull(index - 1)?.let { { switchTo(it) } },
                            onNext = students.getOrNull(index + 1)?.let { { switchTo(it) } },
                        )
                    }
                },
            )
        }
    }
}
