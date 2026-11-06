package com.n0n5ense.taassistant

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
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

    /** 前回開いていたプロジェクトとして [projectPath] を返す。実際の Preferences には触れない */
    /** 保存先を選ぶダイアログの代わりに返すパス */
    private val savePaths = ArrayDeque<String>()

    private fun services(projectPath: String): PlatformServices {
        val real = createPlatformServices()
        val values = mutableMapOf("lastProject" to projectPath)
        return object : PlatformServices by real {
            override fun pickSaveFile(title: String, initial: String?, extensions: List<String>): String? = savePaths.removeFirstOrNull()

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
        val editButton = hasText("コンフィグを編集…", substring = true)
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
        onNode(hasText("課題1", substring = true).and(androidx.compose.ui.test.hasClickAction())).performClick()
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 10_000) { exists(editButton) && !exists(backButton) }
    }

    @Test
    fun opensStudentPage() = runComposeUiTest {
        val nl = Char(10).toString()
        File(dir, "config.yaml").writeText(listOf("version: 2", "tasks:", "  課題1:", "    word: [課題1]", "    testcase:", "      - name: t1", "        expect: ['x']").joinToString(nl))
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
            File(workspace, "sources/Main$n.java").writeText("// 課題1" + nl + "public class Main$n {}" + nl)
            Student(StudentId("s$n"), "学生$n", workspace.path, submission.path)
        }
        val compiled = students.mapIndexed { i, st ->
            CompiledSource(
                SourceFile(st.id, File(st.workspaceDir, "sources/Main${i + 1}.java").absolutePath, "${st.id}/sources/Main${i + 1}.java"),
                CompileOutcome.Success("javac", SourceEncoding.UTF8, null, "Main${i + 1}", ""),
            )
        }
        val results = compiled.mapIndexed { i, c -> TestResult(i, c.source, c.outcome, TaskName("課題1"), testCase, Judge.AC) }
        val outcome = GradingOutcome(config, File(dir, "workspace").path, LastStep.Test, students, compiled, results)
        val project = File(dir, "test.taa")
        project.writeBytes(ProjectCodec.encode(ProjectFile(settings = ProjectSettings(configPath = "config.yaml"), outcome = outcome)))
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                App(services(project.path))
            }
        }

        // 結果タブの学生の見出しに、学生のページを開くボタンがある
        val openStudent = hasContentDescription("学生のページ (提出ファイル)")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(openStudent).fetchSemanticsNodes().size == 2 }
        // 選択中の結果 (学生1) の詳細から、学生のページを開く
        onNode(hasText("学生のページ")).performClick()
        val back = hasContentDescription("採点画面に戻る (Esc)")
        waitUntil(timeoutMillis = 10_000) { exists(back) }
        waitUntil(timeoutMillis = 10_000) { exists(hasText("提出ファイル (1)")) && exists(hasText("展開したソース (1)")) }
        assertTrue(exists(hasText("s1  学生1")))
        assertTrue(exists(hasText("submit1.zip")))
        // 展開したソースの中身を表示している
        waitUntil(timeoutMillis = 10_000) { exists(hasText("public class Main1", substring = true)) }
        // zip は中の階層を表示する
        onNode(hasText("submit1.zip")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("kadai/", substring = true)) && exists(hasText("Main1.java", substring = true)) }

        // 次の学生へ
        onNode(hasText("次の学生")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("s2  学生2")) && exists(hasText("submit2.zip")) }

        // テストケースの判定をクリックすると、結果タブでその結果を開く
        onNode(hasText("t1")).performClick()
        waitUntil(timeoutMillis = 10_000) { !exists(back) && exists(hasText("s2/sources/Main2.java")) }

        // もう一度開いて Esc で戻る
        onAllNodes(openStudent)[0].performClick()
        waitUntil(timeoutMillis = 10_000) { exists(back) }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitUntil(timeoutMillis = 10_000) { !exists(back) }
    }

    @Test
    fun savesAsAndDuplicatesProject() = runComposeUiTest {
        val nl = Char(10).toString()
        File(dir, "config.yaml").writeText(listOf("version: 2", "tasks:", "  課題1:", "    word: [課題1]").joinToString(nl))
        File(dir, "submissions").mkdirs()
        val config = ConfigParser.parse(File(dir, "config.yaml").readText())
        val outcome = GradingOutcome(config, "", LastStep.Test, listOf(Student(StudentId("s1"), "学生1", "")), emptyList(), emptyList())
        val project = File(dir, "第1回.taa")
        project.writeBytes(
            ProjectCodec.encode(
                ProjectFile(settings = ProjectSettings(rootDir = "submissions", configPath = "config.yaml"), outcome = outcome),
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
        assertEquals(File(dir, "config.yaml").absolutePath, File(copied.settings.configPath).absolutePath)
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
}
