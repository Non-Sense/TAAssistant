package com.n0n5ense.taassistant

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.project.ProjectCodec
import com.n0n5ense.taassistant.core.project.ProjectFile
import com.n0n5ense.taassistant.core.project.ProjectSettings
import com.n0n5ense.taassistant.platform.PlatformServices
import com.n0n5ense.taassistant.platform.SettingsStore
import com.n0n5ense.taassistant.platform.createPlatformServices
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** コンフィグエディタを開いて、採点画面に戻る (Navigation 3) */
@OptIn(ExperimentalTestApi::class)
class NavigationUiTest {
    private val dir: File = Files.createTempDirectory("taassistant-nav").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    /** 保存先を選ぶダイアログの代わりに返すパス */
    private val savePaths = ArrayDeque<String>()

    /** ファイルを開くダイアログの代わりに返すパス */
    private val openPaths = ArrayDeque<String>()

    /** 前回開いていたプロジェクトとして [projectPath] を返す。実際の Preferences には触れない */
    private fun services(projectPath: String): PlatformServices {
        val real = createPlatformServices()
        val values = mutableMapOf("lastProject" to projectPath)
        return object : PlatformServices by real {
            override suspend fun pickSaveFile(
                title: String,
                initial: String?,
                extensions: List<String>,
                suggestedName: String?
            ): String? =
                savePaths.removeFirstOrNull()

            override suspend fun pickFile(title: String, initial: String?, extensions: List<String>): String? =
                openPaths.removeFirstOrNull()

            override val settings: SettingsStore = object : SettingsStore {
                override fun get(key: String): String? = values[key]
                override fun put(key: String, value: String) {
                    values[key] = value
                }

                override fun remove(key: String) {
                    values.remove(key)
                }
            }
        }
    }

    private fun SemanticsNodeInteractionsProvider.exists(matcher: androidx.compose.ui.test.SemanticsMatcher) =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun opensSwitchedConfigInEditor() = runComposeUiTest {
        // コンフィグを切り替えた後に「編集…」を押すと、切り替えた後のコンフィグを開く
        File(dir, "a.yaml").writeText("version: 2\ntasks:\n  課題A:\n    word: [A]\n")
        File(dir, "b.yaml").writeText("version: 2\ntasks:\n  課題B:\n    word: [B]\n")
        val project = File(dir, "test.taa")
        project.writeBytes(ProjectCodec.encode(ProjectFile(settings = ProjectSettings(configPath = "a.yaml"))))
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                App(services(project.path))
            }
        }
        val editButton = hasText("編集…").and(hasClickAction())
        val backButton = hasContentDescription("採点画面に戻る (Esc)")
        val taskA = hasText("課題A").and(hasClickAction())
        val taskB = hasText("課題B").and(hasClickAction())
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }

        // a.yaml を開く
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) && exists(taskA) }
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }

        // 「参照」で b.yaml に切り替える (提出物ディレクトリ・コンフィグ・JDK の順)
        openPaths += File(dir, "b.yaml").absolutePath
        onAllNodes(hasText("参照").and(hasClickAction()))[1].performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText(File(dir, "b.yaml").absolutePath)) }

        // 編集すると b.yaml を開いている
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) && exists(taskB) }
        assertFalse(exists(taskA))
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }

        // 手で入力して a.yaml に戻しても、a.yaml を開く
        val configField = { onAllNodes(androidx.compose.ui.test.hasSetTextAction())[1] }
        configField().performTextReplacement(File(dir, "a.yaml").absolutePath)
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) && exists(taskA) }
        assertFalse(exists(taskB))

        // 編集中のコンフィグに未保存の変更があるときに切り替えると、黙って前のコンフィグを開かずに確認する
        onNode(taskA).performClick()
        val nameField = androidx.compose.ui.test.hasSetTextAction().and(hasText("課題A"))
        waitUntil(timeoutMillis = 10_000) { exists(nameField) }
        onNode(nameField).performTextReplacement("課題A2")
        waitUntil(timeoutMillis = 10_000) { exists(hasText("課題A2").and(hasClickAction())) }
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }
        configField().performTextReplacement(File(dir, "b.yaml").absolutePath)
        onNode(editButton).performClick()
        val dialog = hasText("採点に使うコンフィグが「b.yaml」に切り替わりました。", substring = true)
        waitUntil(timeoutMillis = 10_000) { exists(dialog) }
        // キャンセル: 編集中の a.yaml を続ける
        onNode(hasText("キャンセル")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists(dialog) && exists(hasText("課題A2").and(hasClickAction())) }
        // もう一度開いても (切り替えていないので) 聞かない
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) && exists(hasText("課題A2").and(hasClickAction())) }
        assertFalse(exists(dialog))

        // a.yaml → b.yaml と切り替え直して、今度は「保存しない」: b.yaml を開く (a.yaml は変更しない)
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }
        configField().performTextReplacement(File(dir, "a.yaml").absolutePath)
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) }
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }
        configField().performTextReplacement(File(dir, "b.yaml").absolutePath)
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(dialog) }
        onNode(hasText("保存しない")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(taskB) && !exists(dialog) }
        assertTrue(File(dir, "a.yaml").readText().contains("課題A:"), "保存しないので a.yaml は変わらない")
    }

    @Test
    fun opensConfigEditorAndGoesBack() = runComposeUiTest {
        File(dir, "config.yaml").writeText("version: 2\ntasks:\n  課題1:\n    word: [課題1]\n")
        val project = File(dir, "test.taa")
        project.writeBytes(ProjectCodec.encode(ProjectFile(settings = ProjectSettings(configPath = "config.yaml"))))
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                App(services(project.path))
            }
        }

        // 前回のプロジェクトが開き、採点画面になる
        val editButton = hasText("編集…").and(androidx.compose.ui.test.hasClickAction())
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }

        // コンフィグエディタへ
        onNode(editButton).performClick()
        val backButton = hasContentDescription("採点画面に戻る (Esc)")
        waitUntil(timeoutMillis = 10_000) { exists(backButton) && !exists(editButton) }
        waitUntil(timeoutMillis = 10_000) { exists(hasText("config.yaml")) } // 採点に使っているコンフィグを開いている

        // 戻るボタンで採点画面へ
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(editButton) && !exists(backButton) }

        // もう一度開いて、Esc で戻る
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) }
        onNode(backButton).performClick() // フォーカスをエディタ内に置く
        waitUntil(timeoutMillis = 10_000) { exists(editButton) }
        onNode(editButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) }
        onNode(
            hasText(
                "課題1",
                substring = true
            ).and(androidx.compose.ui.test.hasClickAction())
        ).performClick()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 10_000) { exists(editButton) && !exists(backButton) }

        // 「新規作成…」: 新しいコンフィグでエディタを開く
        val newButton = hasText("新規作成…").and(androidx.compose.ui.test.hasClickAction())
        onNode(newButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(backButton) && exists(hasText("新しいコンフィグを作成しました")) }
        assertFalse(exists(hasText("config.yaml")), "採点に使っているコンフィグではなく、新しいコンフィグを開く")
        // 戻ると、未保存の変更があることが分かる
        onNode(backButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("● 未保存の変更あり")) }
        // 未保存の変更があるときにもう一度「新規作成…」を押すと、エディタで確認する
        onNode(newButton).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("変更を保存しますか?")) }
        onNode(hasText("キャンセル")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists(hasText("変更を保存しますか?")) && exists(backButton) }
    }

    @Test
    fun opensStudentPage() = runComposeUiTest {
        val nl = Char(10).toString()
        File(dir, "config.yaml").writeText(
            listOf(
                "version: 2",
                "tasks:",
                "  課題1:",
                "    word: [課題1]",
                "    testcase:",
                "      - name: t1",
                "        expect: ['x']"
            ).joinToString(nl)
        )
        val config = ConfigParser.parse(File(dir, "config.yaml").readText())
        val testCase = config.tasks.values.first().testcase.first()
        // 学生ごとに、元の提出ディレクトリと展開したソースを作る
        val students = (1..2).map { n ->
            val submission = File(dir, "s$n 学生${n}_1_assignsubmission_file_").apply { mkdirs() }
            ZipOutputStream(File(submission, "submit$n.zip").outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("kadai/Main$n.java"))
                zip.write("class Main$n {}".toByteArray())
                zip.closeEntry()
            }
            val workspace = File(dir, "workspace/s$n").apply { mkdirs() }
            File(workspace, "sources").mkdirs()
            File(
                workspace,
                "sources/Main$n.java"
            ).writeText("// 課題1" + nl + "public class Main$n {}" + nl)
            Student(StudentId("s$n"), "学生$n", workspace.path, submission.path)
        }
        val compiled = students.mapIndexed { i, st ->
            CompiledSource(
                SourceFile(
                    st.id,
                    File(st.workspaceDir, "sources/Main${i + 1}.java").absolutePath,
                    "${st.id}/sources/Main${i + 1}.java"
                ),
                CompileOutcome.Success("javac", SourceEncoding.UTF8, null, "Main${i + 1}", ""),
            )
        }
        // 学生1 には、課題を特定できない (NF) ソースもある
        File(
            students[0].workspaceDir,
            "sources/Other1.java"
        ).writeText("public class Other1 {}" + nl)
        val other = CompiledSource(
            SourceFile(
                students[0].id,
                File(students[0].workspaceDir, "sources/Other1.java").absolutePath,
                "s1/sources/Other1.java"
            ),
            CompileOutcome.Success(
                "javac",
                SourceEncoding.UTF8,
                null,
                "Other1",
                students[0].workspaceDir
            ),
        )
        val results = compiled.mapIndexed { i, c ->
            TestResult(
                i,
                c.source,
                c.outcome,
                TaskName("課題1"),
                testCase,
                Judge.AC
            )
        } +
                TestResult(2, other.source, other.outcome, null, null, Judge.NF)
        val outcome = GradingOutcome(
            config,
            File(dir, "workspace").path,
            LastStep.Test,
            students,
            compiled + other,
            results
        )
        val project = File(dir, "test.taa")
        project.writeBytes(
            ProjectCodec.encode(
                ProjectFile(
                    settings = ProjectSettings(configPath = "config.yaml"),
                    outcome = outcome
                )
            )
        )
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                App(services(project.path))
            }
        }

        // 最初はサマリーを表示している
        waitUntil(timeoutMillis = 10_000) { exists(hasText("手動介入")) }
        onNode(hasText("詳細")).performClick()
        // 詳細タブの学生の見出しに、学生のページを開くボタンがある
        val openStudent = hasContentDescription("学生のページ (提出ファイル)")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(openStudent).fetchSemanticsNodes().size == 2 }
        // 選択中の結果 (学生1) の詳細から、学生のページを開く
        onNode(hasText("学生のページ")).performClick()
        val back = hasContentDescription("採点画面に戻る (Esc)")
        waitUntil(timeoutMillis = 10_000) { exists(back) }
        assertTrue(exists(hasText("s1  学生1")))

        // 「結果」タブ: 詳細タブと同じ一覧 (1人分なので学生の見出しは出さない)
        waitUntil(timeoutMillis = 10_000) { exists(hasText("結果 (2)")) }
        assertFalse(exists(openStudent))
        assertFalse(
            exists(hasText("学生のページ")),
            "学生のページの中では「学生のページ」ボタンを出さない"
        )

        // 課題を特定できないソースに、課題を割り当てる (その場でテストを実行する)
        onNode(hasText("課題を特定できません")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("課題を割り当て")) }
        onNode(hasText("課題を割り当て")).performClick()
        val menuItem = hasText("課題1").and(hasAnyAncestor(isPopup()))
        waitUntil(timeoutMillis = 10_000) { exists(menuItem) }
        onNode(menuItem).performClick()
        val restore = hasText("課題の判定を自動に戻す")
        waitUntil(timeoutMillis = 30_000) { exists(restore) && exists(hasText("課題「課題1」は手動で決めました")) }
        assertTrue(exists(hasText("(割り当て)")))
        assertFalse(exists(hasText("課題を特定できません")))
        // 「この課題から外す」: 課題が残らないので、どの課題でもない NF になる
        onNode(hasText("この課題から外す")).performClick()
        waitUntil(timeoutMillis = 10_000) {
            exists(hasText("すべての課題から外しました")) && exists(hasText("課題を特定できません"))
        }
        assertTrue(exists(restore))
        // 「課題の判定を自動に戻す」: ソースの内容から判定し直す (このソースは課題を特定できない)
        onNode(restore).performClick()
        waitUntil(timeoutMillis = 30_000) {
            exists(hasText("課題を特定できません")) && !exists(restore) && !exists(hasText("すべての課題から外しました"))
        }

        // 「提出ファイル」タブ
        onNode(hasText("提出ファイル", substring = true)).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("提出ファイル (1)")) && exists(hasText("展開したソース (2)")) }
        assertTrue(exists(hasText("submit1.zip")))
        // 展開したソースの中身を表示している
        waitUntil(timeoutMillis = 10_000) {
            exists(
                hasText(
                    "public class Main1",
                    substring = true
                )
            )
        }
        // zip は中の階層を表示する
        onNode(hasText("submit1.zip")).performClick()
        waitUntil(timeoutMillis = 10_000) {
            exists(hasText("kadai/", substring = true)) && exists(
                hasText("Main1.java", substring = true)
            )
        }

        // 次の学生へ
        onNode(hasText("次の学生")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("s2  学生2")) && exists(hasText("結果 (1)")) }

        // Esc で戻る
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 10_000) { !exists(back) }
    }

    @Test
    fun savesAsAndDuplicatesProject() = runComposeUiTest {
        val nl = Char(10).toString()
        File(dir, "config.yaml").writeText(
            listOf(
                "version: 2",
                "tasks:",
                "  課題1:",
                "    word: [課題1]"
            ).joinToString(nl)
        )
        File(dir, "submissions").mkdirs()
        val config = ConfigParser.parse(File(dir, "config.yaml").readText())
        val outcome = GradingOutcome(
            config,
            "",
            LastStep.Test,
            listOf(Student(StudentId("s1"), "学生1", "")),
            emptyList(),
            emptyList()
        )
        val project = File(dir, "第1回.taa")
        project.writeBytes(
            ProjectCodec.encode(
                ProjectFile(
                    settings = ProjectSettings(
                        rootDir = "submissions",
                        configPath = "config.yaml"
                    ), outcome = outcome
                ),
            ),
        )
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                App(services(project.path))
            }
        }
        waitUntil(timeoutMillis = 10_000) { exists(hasText("第1回")) }

        // 結果の書き出しは実行後の任意の操作: 形式を選ぶと保存先を聞かれる
        fun export(format: String, path: File) {
            savePaths += path.path
            onNode(hasText("結果を書き出す")).performClick()
            waitUntil(timeoutMillis = 10_000) { exists(hasText(format)) }
            onNode(hasText(format)).performClick()
        }
        val xlsx = File(dir, "out/結果.xlsx")
        export("Excel (.xlsx)…", xlsx)
        waitUntil(timeoutMillis = 10_000) { xlsx.isFile && exists(hasText("書き出しました:")) }
        assertTrue(exists(hasText("結果.xlsx")))
        // CSV は詳細とサマリーの 2 ファイルになる
        export("CSV (Shift_JIS)…", File(dir, "out/結果.csv"))
        waitUntil(timeoutMillis = 10_000) {
            File(dir, "out/結果-detail.csv").isFile && File(dir, "out/結果-summary.csv").isFile
        }
        waitUntil(timeoutMillis = 10_000) { exists(hasText("結果-summary.csv")) }
        onNode(hasContentDescription("閉じる")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists(hasText("書き出しました:")) }

        fun menu(item: String) {
            onNode(hasContentDescription("プロジェクト")).performClick()
            waitUntil(timeoutMillis = 10_000) { exists(hasText(item)) }
            onNode(hasText(item)).performClick()
        }

        // 設定だけを複製: 結果を含めない。開いているプロジェクトは変わらない
        val settingsOnly = File(dir, "next/第2回.taa")
        savePaths += settingsOnly.path
        menu("設定だけを複製…")
        waitUntil(timeoutMillis = 10_000) { settingsOnly.isFile && exists(hasText("開く")) }
        val copied = ProjectCodec.decode(settingsOnly.readBytes())
        assertNull(copied.outcome)
        // 保存先が変わるので、相対パスは新しい場所を基準に作り直す (フォルダの外になるので絶対パス)
        assertEquals(
            File(dir, "config.yaml").absolutePath,
            File(copied.settings.configPath).absolutePath
        )
        assertTrue(exists(hasText("第1回")))

        // 複製: 結果も含める
        val duplicate = File(dir, "第1回 (コピー).taa")
        savePaths += duplicate.path
        menu("複製…")
        waitUntil(timeoutMillis = 10_000) { duplicate.isFile }
        val duplicated = ProjectCodec.decode(duplicate.readBytes())
        assertEquals(outcome, duplicated.outcome)
        assertEquals("config.yaml", duplicated.settings.configPath)

        // 別名で保存: 以後は新しいファイルを開いている
        val renamed = File(dir, "第1回 改.taa")
        savePaths += renamed.path
        menu("別名で保存…")
        waitUntil(timeoutMillis = 10_000) { renamed.isFile && exists(hasText("第1回 改")) }
        assertEquals("submissions", ProjectCodec.decode(renamed.readBytes()).settings.rootDir)
        assertTrue(project.isFile, "元のファイルは残る")
    }

    @Test
    fun opensLogScreen() = runComposeUiTest {
        File(dir, "config.yaml").writeText("version: 2\ntasks:\n  課題1:\n    word: [課題1]\n")
        val project = File(dir, "test.taa")
        project.writeBytes(ProjectCodec.encode(ProjectFile(settings = ProjectSettings(configPath = "config.yaml"))))
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                App(services(project.path))
            }
        }
        // 実行ボタンの近くの「ログ」から開く
        val logButton = hasText("ログ").and(hasClickAction())
        waitUntil(timeoutMillis = 10_000) { exists(logButton) && exists(hasText("実行")) }
        onNode(logButton).performScrollTo().performClick()
        val back = hasContentDescription("採点画面に戻る (Esc)")
        waitUntil(timeoutMillis = 10_000) { exists(back) && !exists(hasText("実行")) }
        assertTrue(exists(hasText("プロジェクトを開きました", substring = true)))

        // Esc で採点画面に戻る
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 10_000) { !exists(back) && exists(hasText("実行")) }
    }
}
