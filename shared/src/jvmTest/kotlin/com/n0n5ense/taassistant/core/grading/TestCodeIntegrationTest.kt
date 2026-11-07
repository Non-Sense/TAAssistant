package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Execution
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 実際に javac / java を使って、自分で書いたテストコードを実行するテストを確認する */
class TestCodeIntegrationTest {
    private val root: File = Files.createTempDirectory("taassistant-testcode").toFile()
    private val toolkit = JvmGradingToolkit()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private val nl = Char(10).toString()

    private val config = ConfigParser.parse(
        listOf(
            "version: 2",
            "compileTimeout: 30000",
            "runningTimeout: 3000",
            "tasks:",
            "  電卓:",
            "    word: [Calculator]",
            "    testcase:",
            // main の中身だけを書く
            "      - name: body",
            "        testCode: |",
            "          Calculator c = new Calculator(10);",
            "          c.add(1);",
            "          System.out.println(c.add(2));",
            "        expect: ['^足しました\\s+足しました\\s+13\\s*$']",
            "      - name: wrong",
            "        testCode: System.out.println(new Calculator(0).add(1));",
            "        expect: ['^.*2\\s*$']",
            // クラス全体を書く (import とヘルパーメソッド)
            "      - name: class",
            "        testCode: |",
            "          import java.util.Arrays;",
            "",
            "          public class CalcTest {",
            "              static String show(int[] a) { return Arrays.toString(a); }",
            "              public static void main(String[] args) {",
            "                  System.out.println(show(Calculator.range(3)));",
            "              }",
            "          }",
            "        expect: ['^\\[0, 1, 2\\]\\s*$']",
            "      - name: packagePrivate",
            "        testCode: System.out.println(new Calculator(1).secret());",
            "        expect: ['^null\\s*$']",
            "      - name: missing",
            "        testCode: System.out.println(new Calculator(1).sub(1));",
            "        expect: ['.*']",
            "      - name: throws",
            "        testCode: System.out.println(new Calculator(1).div(0));",
            "        expect: ['.*']",
            "      - name: stdinAndArgs",
            "        arg: x",
            "        input: ['7']",
            "        testCode: System.out.println(Calculator.readInt() + args[0]);",
            "        expect: ['^7x\\s*$']",
            "      - name: loop",
            "        testCode: Calculator.forever();",
            "        expect: ['.*']",
        ).joinToString(nl),
    )

    private val calculator = listOf(
        "public class Calculator {",
        "    private int value;",
        "    public Calculator(int value) { this.value = value; }",
        "    public int add(int x) { value += x; System.out.println(\"足しました\"); return value; }",
        "    public int div(int x) { return value / x; }",
        "    String secret() { return null; }",
        "    public static int[] range(int n) { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = i; return a; }",
        "    public static int readInt() { return new java.util.Scanner(System.in).nextInt(); }",
        "    public static int forever() { while (true) { } }",
        "}",
    )

    private fun compile(relativePath: String, lines: List<String>): CompiledSource {
        val student = Student(StudentId("s1"), "学生1", File(root, "s1").absolutePath)
        val file = File(root, "s1/sources/$relativePath").apply {
            parentFile.mkdirs()
            writeText(lines.joinToString(nl) + nl)
        }
        val source = SourceFile(student.id, file.absolutePath, "s1/sources/$relativePath")
        val outcome = runBlocking { toolkit.compile(source, student, JavaToolchain(), config.compileTimeout) }
        assertTrue(outcome is CompileOutcome.Success, outcome.toString())
        return CompiledSource(source, outcome)
    }

    private fun runAll(target: CompileOutcome.Success): Map<String, Pair<Judge, Execution>> =
        config.tasks.getValue(TaskName("電卓")).testcase.associate { testCase ->
            val execution = runBlocking { toolkit.execute(target, testCase, JavaToolchain(), config.runningTimeout) }
            testCase.name to (AutoJudge.judge(testCase, execution) to execution)
        }

    @Test
    fun runsTestCodeAgainstSubmittedClass() {
        val results = runAll(compile("Calculator.java", calculator).outcome as CompileOutcome.Success)
        assertEquals(
            mapOf(
                "body" to Judge.AC,
                "wrong" to Judge.WA,
                "class" to Judge.AC,
                "packagePrivate" to Judge.AC,
                "missing" to Judge.TCE,
                "throws" to Judge.RE,
                "stdinAndArgs" to Judge.AC,
                "loop" to Judge.TLE,
            ),
            results.mapValues { it.value.first },
        )

        val body = results.getValue("body").second
        assertTrue(body.testCode!!.contains("public class TaaTest__"), body.testCode)
        assertTrue(body.command.contains(" TaaTest__"), body.command)
        assertNull(body.testCodeError)
        assertTrue(results.getValue("class").second.command.contains(" CalcTest"))

        val error = assertNotNull(results.getValue("missing").second.testCodeError)
        assertTrue(error.contains("cannot find symbol"), error)
        assertTrue(error.contains("TaaTest__.java"), error)
        assertTrue(!error.contains(root.path) && !error.contains("taa-test-"), "一時ディレクトリのパスは出さない: $error")
    }

    @Test
    fun worksWithPackagedClass() {
        val target = compile("calc/core/Calculator.java", listOf("package calc.core;") + calculator)
            .outcome as CompileOutcome.Success
        assertEquals("calc.core", target.packageName)
        val results = runAll(target)
        // package を書かなければ同じパッケージに入るので、そのまま (package-private も) 呼べる
        assertEquals(Judge.AC, results.getValue("body").first)
        assertEquals(Judge.AC, results.getValue("class").first)
        assertEquals(Judge.AC, results.getValue("packagePrivate").first)
        assertTrue(results.getValue("class").second.testCode!!.startsWith("package calc.core;"))

        // package を自分で書いたら、それを使う (import して呼ぶ)
        val own = config.tasks.getValue(TaskName("電卓")).testcase.first().copy(
            testCode = listOf(
                "package mytest;",
                "import calc.core.Calculator;",
                "public class Own {",
                "    public static void main(String[] args) { System.out.println(Calculator.range(2).length); }",
                "}",
            ).joinToString(nl),
            expect = listOf("^2\\s*$"),
        )
        val execution = runBlocking { toolkit.execute(target, own, JavaToolchain(), config.runningTimeout) }
        assertEquals(Judge.AC, AutoJudge.judge(own, execution), execution.toString())
        assertTrue(execution.command.contains(" mytest.Own"), execution.command)
    }

    @Test
    fun replacesClassNamePlaceholder() {
        // 学生によってクラス名が違う (Calculator / Calc / mypkg.Dentaku) ときも、同じテストコードで呼べる
        val testCase = config.tasks.getValue(TaskName("電卓")).testcase.first().copy(
            testCode = listOf(
                "\${class} c = new \${class}(10);",
                "System.out.println(c.add(2) + \" \" + \${qualifiedClass}.class.getName());",
            ).joinToString(nl),
            expect = listOf("^.*12\\s+[\\w.]+\\s*$"),
        )
        val renamed = listOf(
            "Calculator.java" to calculator,
            "Calc.java" to calculator.map { it.replace("Calculator", "Calc") },
            "mypkg/Dentaku.java" to listOf("package mypkg;") + calculator.map { it.replace("Calculator", "Dentaku") },
        )
        val names = renamed.map { (path, lines) ->
            val target = compile(path, lines).outcome as CompileOutcome.Success
            val execution = runBlocking { toolkit.execute(target, testCase, JavaToolchain(), config.runningTimeout) }
            assertEquals(Judge.AC, AutoJudge.judge(testCase, execution), execution.toString())
            execution.stdout.trim().lines().last().substringAfter(' ')
        }
        assertEquals(listOf("Calculator", "Calc", "mypkg.Dentaku"), names)
    }

    @Test
    fun runsThroughPipeline() {
        val compiled = compile("Calculator.java", calculator)
        val results = runBlocking {
            GradingPipeline(toolkit).testSource(config, compiled, listOf(TaskName("電卓")), JavaToolchain(), 4)
        }
        assertEquals(8, results.size)
        val body = results.first { it.testCase?.name == "body" }
        assertEquals(Judge.AC, body.judge)
        assertTrue(body.execution?.stdout.orEmpty().contains("13"))
    }
}
