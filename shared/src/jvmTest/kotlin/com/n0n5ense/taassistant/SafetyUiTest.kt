package com.n0n5ense.taassistant

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.GradingPipeline
import com.n0n5ense.taassistant.core.grading.JavaToolchain
import com.n0n5ense.taassistant.core.grading.JvmGradingToolkit
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
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

/** 危ない可能性がある呼び出しのあるソース: 保留を表示し、確認して実行すると許可を保存する */
@OptIn(ExperimentalTestApi::class)
class SafetyUiTest {
    private val dir: File = Files.createTempDirectory("taassistant-safety-ui").toFile()
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

    @Test
    fun approvesHeldSourceAndRunsIt() = runComposeUiTest {
        File(dir, "config.yaml").writeText(
            listOf(
                "version: 2",
                "tasks:",
                "  課題1:",
                "    word: [課題1]",
                "    testcase:",
                "      - name: hello",
                "        expect: ['^hello\\s*$']",
            ).joinToString(nl),
        )
        val config = ConfigParser.parse(File(dir, "config.yaml").readText())
        val student = Student(StudentId("s1"), "学生1", File(dir, "workspace/s1").absolutePath)
        val file = File(dir, "workspace/s1/sources/Main.java").apply {
            parentFile.mkdirs()
            writeText(
                listOf(
                    "// 課題1",
                    "public class Main {",
                    "    public static void main(String[] args) throws Exception {",
                    "        if (args.length > 99) Runtime.getRuntime().exec(\"calc\");",
                    "        System.out.println(\"hello\");",
                    "    }",
                    "}",
                ).joinToString(nl),
            )
        }
        val source = SourceFile(student.id, file.absolutePath, "s1/sources/Main.java")
        val toolkit = JvmGradingToolkit()
        val pipeline = GradingPipeline(toolkit)
        val compiled = runBlocking {
            CompiledSource(source, pipeline.inspect(source, toolkit.compile(source, student, JavaToolchain(), 30_000), JavaToolchain()))
        }
        val results = runBlocking { pipeline.testSource(config, compiled, null, JavaToolchain(), 1) }
            .mapIndexed { i, r -> r.copy(id = i) }
        assertEquals(listOf(true), results.map { it.held })
        val outcome = GradingOutcome(config, File(dir, "workspace").path, LastStep.Test, listOf(student), listOf(compiled), results)
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

        waitUntil(timeoutMillis = 10_000) { exists(hasText("詳細")) }
        onNode(hasText("詳細")).performClick()
        // 「実行を保留」で絞り込める
        waitUntil(timeoutMillis = 10_000) { exists(hasText("実行を保留 (1)")) }
        onNode(hasText("すべて開く")).performClick()
        waitUntil(timeoutMillis = 10_000) { exists(hasText("hello").and(hasClickAction())) }
        onNode(hasText("hello").and(hasClickAction())).performClick()

        // 保留の理由と、見つかった呼び出しを表示する
        waitUntil(timeoutMillis = 10_000) { exists(hasText("実行を保留しています")) }
        assertTrue(exists(hasText("外部プログラムの起動: java.lang.Runtime.exec  (Main.main)")))

        // 確認して実行する
        onNode(hasText("ソースを確認して実行")).performScrollTo().performClick()
        waitUntil(timeoutMillis = 30_000) { exists(hasText("確認済み (実行を許可しました)")) }
        assertTrue(exists(hasText("hello", substring = true)))
        assertTrue(exists(hasText("実行を保留 (0)")))

        // 許可はソースのハッシュと一緒にプロジェクトに保存される
        val hash = (compiled.outcome as CompileOutcome.Success).sourceHash
        waitUntil(timeoutMillis = 10_000) {
            runCatching { ProjectCodec.decode(project.readBytes()) }.getOrNull()?.approvals?.any { it.hash == hash } == true
        }
        val saved = ProjectCodec.decode(project.readBytes())
        assertEquals("s1/sources/Main.java", saved.approvals.single().source)
        assertEquals(listOf(Judge.AC to false), saved.outcome!!.results.map { it.judge to it.held })
    }
}
