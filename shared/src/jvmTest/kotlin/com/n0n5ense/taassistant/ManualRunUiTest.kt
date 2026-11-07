package com.n0n5ense.taassistant

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.JavaToolchain
import com.n0n5ense.taassistant.core.grading.JvmGradingToolkit
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Judge
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
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 手動で判定する課題 (GUI の課題など): 詳細から1件ずつ実行・停止し、手動で判定する */
@OptIn(ExperimentalTestApi::class)
class ManualRunUiTest {
    private val dir: File = Files.createTempDirectory("taassistant-manual").toFile()
    private val nl = Char(10).toString()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun services(projectPath: String): PlatformServices {
        val real = createPlatformServices()
        val values = mutableMapOf("lastProject" to projectPath)
        return object : PlatformServices by real {
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

    private fun SemanticsNodeInteractionsProvider.exists(matcher: SemanticsMatcher) =
        onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** 失敗したときの手がかりに、画面の文言をまとめる */
    private fun SemanticsNodeInteractionsProvider.screenTexts(): String =
        onAllNodes(hasText("", substring = true)).fetchSemanticsNodes()
            .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            .joinToString(" | ") { it.text }

    @Test
    fun runsAndStopsProgramOneByOne() = runComposeUiTest {
        File(dir, "config.yaml").writeText(
            listOf(
                "version: 2",
                "tasks:",
                "  GUI:",
                "    word: [GUI]",
                "    manual: true",
                "    testcase:",
                "      - name: hello",
                "        arg: world",
                "        check: 挨拶が表示される",
                "      - name: forever",
                "        arg: wait",
                "        check: 止まらない",
            ).joinToString(nl),
        )
        val config = ConfigParser.parse(File(dir, "config.yaml").readText())

        // 引数が wait なら終わらない (ウィンドウを開いたままの GUI の代わり)
        val student = Student(StudentId("s1"), "学生1", File(dir, "workspace/s1").absolutePath)
        val file = File(dir, "workspace/s1/sources/Hello.java").apply {
            parentFile.mkdirs()
            writeText(
                listOf(
                    "// GUI",
                    "public class Hello {",
                    "    public static void main(String[] args) throws Exception {",
                    "        if (args[0].equals(\"wait\")) { System.out.println(\"waiting\"); Thread.sleep(600_000); }",
                    "        System.out.println(\"hello \" + args[0]);",
                    "    }",
                    "}",
                ).joinToString(nl),
            )
        }
        val source = SourceFile(student.id, file.absolutePath, "s1/sources/Hello.java")
        val compile = runBlocking { JvmGradingToolkit().compile(source, student, JavaToolchain(), 30_000) }
        assertTrue(compile is CompileOutcome.Success, compile.toString())
        val cases = config.tasks.getValue(TaskName("GUI")).testcase
        val results = cases.mapIndexed { i, tc -> TestResult(i, source, compile, TaskName("GUI"), tc, Judge.Unknown) }
        val outcome = GradingOutcome(
            config, File(dir, "workspace").path, LastStep.Test, listOf(student),
            listOf(CompiledSource(source, compile)), results,
        )
        val project = File(dir, "test.taa")
        project.writeBytes(
            ProjectCodec.encode(ProjectFile(settings = ProjectSettings(configPath = "config.yaml"), outcome = outcome)),
        )
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { App(services(project.path)) }
        }

        // 詳細タブで学生・課題を開き、最初の結果 (hello) を選ぶ
        waitUntil(timeoutMillis = 10_000) { exists(hasText("詳細")) }
        onNode(hasText("詳細")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("すべて開く")) }
        onNode(hasText("すべて開く")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("hello").and(hasClickAction())) }
        onNode(hasText("hello").and(hasClickAction())).performClick()

        waitUntil(timeoutMillis = 10_000) { exists(hasText("手動で判定する課題")) }
        assertTrue(exists(hasText("挨拶が表示される")))
        assertTrue(exists(hasText("(実行して、下のボタンかキーで判定してください)")))

        // 実行する (引数を渡し、終わったら出力を記録する)
        onNode(hasText("実行 (Enter)")).performScrollTo().performClick()
        try {
            waitUntil(timeoutMillis = 30_000) { exists(hasText("実行済み (終了コード 0)。もう一度実行できます")) }
        } catch (e: Throwable) {
            throw AssertionError("実行が終わりませんでした。画面の文言: " + screenTexts(), e)
        }
        assertTrue(exists(hasText("hello world", substring = true)))

        // 手動で判定する (A で AC にして次へ)
        onRoot().performKeyInput { pressKey(Key.A) }
        waitUntil(timeoutMillis = 10_000) { exists(hasText("挨拶が表示される").not().and(hasText("止まらない"))) }

        // 終わらないプログラムは「停止」で止める
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitUntil(timeoutMillis = 30_000) { exists(hasText("停止")) }
        onNode(hasText("停止")).performScrollTo().performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("実行 (Enter)")) && !exists(hasText("停止")) }
        // 停止したときは出力を記録しない
        assertTrue(!exists(hasText("実行済み", substring = true)))
        assertEquals(true, exists(hasText("引数と標準入力を渡して起動します。時間制限はありません")))
    }
}
