package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 実際に javac / javap を使って、main から直接呼んでいるメソッドの確認を確かめる */
class CallCheckIntegrationTest {
    private val root: File = Files.createTempDirectory("taassistant-calls").toFile()
    private val toolkit = JvmGradingToolkit()
    private val nl = Char(10).toString()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private val config = ConfigParser.parse(
        listOf(
            "version: 2",
            "tasks:",
            "  並べ替え:",
            "    word: [Sorter]",
            // 手動で判定する課題でも、呼び出しの確認は自動で行う
            "    manual: true",
            "    testcase:",
            "      - name: direct",
            "        calls: [Arrays.sort, new ArrayList]",
            "      - name: indirect",
            "        calls: [Collections.reverse]",
            "      - name: lambda",
            "        calls: [println]",
            "      - name: gui",
            "        check: 画面を確認する",
        ).joinToString(nl),
    )

    private fun compile(relativePath: String, lines: List<String>): CompiledSource {
        val student = Student(StudentId("s1"), "学生1", File(root, "s1").absolutePath)
        val file = File(root, "s1/sources/$relativePath").apply {
            parentFile.mkdirs()
            writeText(lines.joinToString(nl) + nl)
        }
        val source = SourceFile(student.id, file.absolutePath, "s1/sources/$relativePath")
        val outcome = runBlocking { toolkit.compile(source, student, JavaToolchain(), 30_000) }
        assertTrue(outcome is CompileOutcome.Success, outcome.toString())
        return CompiledSource(source, outcome)
    }

    private val sorter = listOf(
        "import java.util.*;",
        "public class Sorter {",
        "    public static void main(String[] args) {",
        "        int[] a = {3, 1, 2};",
        "        Arrays.sort(a);",
        "        List<Integer> list = new ArrayList<>();",
        "        Runnable r = () -> System.out.println(Arrays.toString(a));",
        "        r.run();",
        "        helper(list);",
        "    }",
        "    static void helper(List<Integer> list) { Collections.reverse(list); }",
        "}",
    )

    @Test
    fun checksDirectCallsThroughPipeline() {
        val compiled = compile("Sorter.java", sorter)
        val results = runBlocking {
            GradingPipeline(toolkit).testSource(config, compiled, listOf(TaskName("並べ替え")), JavaToolchain(), 2)
        }
        assertEquals(
            listOf("direct" to Judge.AC, "indirect" to Judge.WA, "lambda" to Judge.AC, "gui" to Judge.Unknown),
            results.map { it.testCase?.name to it.judge },
        )
        val direct = results.first().execution!!
        assertTrue(direct.command.contains("javap"), direct.command)
        val calls = direct.mainCalls!!
        assertTrue("java.util.Arrays.sort" in calls, calls.toString())
        assertTrue("Sorter.helper" in calls, calls.toString())
        assertTrue("java.util.Collections.reverse" !in calls, "自作メソッドの中は見ない")
        // 結果ファイルや詳細のために、標準出力にも1行ずつ入れる
        assertEquals(calls, direct.stdout.lines())
    }

    @Test
    fun worksWithPackagedClassAndMissingMain() {
        val packaged = compile("app/Sorter.java", listOf("package app;") + sorter)
        val direct = config.tasks.getValue(TaskName("並べ替え")).testcase.first()
        val execution = runBlocking { toolkit.execute(packaged.outcome as CompileOutcome.Success, direct, JavaToolchain(), 3000) }
        assertEquals(Judge.AC, AutoJudge.judge(direct, execution), execution.toString())
        assertTrue("app.Sorter.helper" in execution.mainCalls!!)

        // main が無ければ WA (理由を標準エラー出力に書く)
        val noMain = compile("NoMain.java", listOf("public class NoMain { static void f() { java.util.Arrays.sort(new int[0]); } }"))
        val none = runBlocking { toolkit.execute(noMain.outcome as CompileOutcome.Success, direct, JavaToolchain(), 3000) }
        assertEquals(Judge.WA, AutoJudge.judge(direct, none))
        assertEquals(emptyList(), none.mainCalls)
        assertTrue(none.stderr.contains("main メソッドが見つかりません"), none.stderr)
    }
}
