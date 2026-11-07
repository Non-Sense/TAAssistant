package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.DraftField
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.config.toConfig
import com.n0n5ense.taassistant.core.config.toDraft
import com.n0n5ense.taassistant.core.config.validate
import com.n0n5ense.taassistant.core.model.Execution
import com.n0n5ense.taassistant.core.model.Judge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 自分で書いたテストコードのうち、javac / java を使わない部分 (コンフィグ・コードの補完・判定) */
class TestCodeTest {
    private val nl = Char(10).toString()

    private val yaml = listOf(
        "version: 2",
        "tasks:",
        "  電卓:",
        "    word: [Calculator]",
        "    testcase:",
        "      - name: main",
        "        expect: ['^ok$']",
        "      - name: code",
        "        input: ['1']",
        "        testCode: |",
        "          Calculator c = new Calculator(10);",
        "          System.out.println(c.add(1, 2));",
        "        expect: ['^13$']",
    ).joinToString(nl)

    private fun case(name: String) =
        ConfigParser.parse(yaml).tasks.getValue(TaskName("電卓")).testcase.first { it.name == name }

    // ------------------------------------------------------------ コンフィグ

    @Test
    fun parsesAndWritesTestCode() {
        assertEquals(
            "Calculator c = new Calculator(10);" + nl + "System.out.println(c.add(1, 2));" + nl,
            case("code").testCode,
        )
        assertNull(case("main").testCode)

        // 書き出して読み直しても同じ。main を実行するテストには testCode を書き出さない
        val config = ConfigParser.parse(yaml)
        val written = ConfigParser.encode(config)
        assertEquals(config, ConfigParser.parse(written))
        assertEquals(1, Regex("testCode:").findAll(written).count(), written)
    }

    @Test
    fun editsTestCodeInDraft() {
        val config = ConfigParser.parse(yaml)
        var key = 0L
        val draft = config.toDraft { key++ }
        val code = draft.tasks[0].testCases[1]
        assertEquals(com.n0n5ense.taassistant.core.config.TestMethod.TestCode, code.method)
        assertEquals(com.n0n5ense.taassistant.core.config.TestMethod.Main, draft.tasks[0].testCases[0].method)
        assertTrue(draft.validate().isEmpty())
        assertEquals(config, draft.toConfig())

        fun withCase(case: com.n0n5ense.taassistant.core.config.TestCaseDraft) =
            draft.copy(tasks = listOf(draft.tasks[0].copy(testCases = listOf(case))))

        // テストコードが空ならエラー
        assertEquals(listOf(DraftField.TestCode), withCase(code.copy(testCode = " \n")).validate().map { it.field })

        // main を実行するテストに切り替えると、テストコードは保存しない
        val switched = withCase(code.copy(method = com.n0n5ense.taassistant.core.config.TestMethod.Main)).toConfig()
        assertNull(switched.tasks.getValue(TaskName("電卓")).testcase.single().testCode)

        // 改行は LF に揃え、末尾は改行1つにする
        val crlf = withCase(code.copy(testCode = "a();\r\nb();\r\n\r\n")).toConfig()
        assertEquals("a();" + nl + "b();" + nl, crlf.tasks.getValue(TaskName("電卓")).testcase.single().testCode)
    }

    // ------------------------------------------------------------ コードの補完

    @Test
    fun wrapsMainBody() {
        val prepared = TestCode.prepare("Calculator c = new Calculator(10);" + nl + nl + "c.run();", "calc.core", "Calculator")
        assertEquals("calc.core", prepared.packageName)
        assertEquals("TaaTest__", prepared.className)
        assertEquals("calc.core.TaaTest__", prepared.mainClass)
        assertEquals("TaaTest__.java", prepared.fileName)
        assertEquals(
            listOf(
                "package calc.core;",
                "",
                "public class TaaTest__ {",
                "    public static void main(String[] args) throws Throwable {",
                "        Calculator c = new Calculator(10);",
                "",
                "        c.run();",
                "    }",
                "}",
                "",
            ).joinToString(nl),
            prepared.source,
        )

        // 提出されたクラスにパッケージが無ければ、パッケージ宣言も付けない
        val plain = TestCode.prepare("run();", null, "Calculator")
        assertEquals("TaaTest__", plain.mainClass)
        assertTrue(plain.source.startsWith("public class TaaTest__ {"), plain.source)
    }

    @Test
    fun replacesClassPlaceholders() {
        // 学生によってクラス名が違っても、同じテストコードで呼べる
        val body = "\${class} c = new \${class}(10);" + nl + "System.out.println(\${qualifiedClass}.class);"
        val calc = TestCode.prepare(body, "calc.core", "Calc")
        assertTrue(calc.source.contains("        Calc c = new Calc(10);" + nl), calc.source)
        assertTrue(calc.source.contains("System.out.println(calc.core.Calc.class);"), calc.source)
        // パッケージが無ければ、パッケージ付きの名前もクラス名だけ
        val plain = TestCode.prepare(body, null, "Calculator")
        assertTrue(plain.source.contains("Calculator c = new Calculator(10);"), plain.source)
        assertTrue(plain.source.contains("System.out.println(Calculator.class);"), plain.source)

        // クラス全体を書くとき: 自分のクラス名にも使える (置き換えてから public class を探す)
        val whole = TestCode.prepare(
            "package my.test;" + nl + "public class \${class}Test {" + nl +
                "    public static void main(String[] a) { new \${qualifiedClass}(); }" + nl + "}",
            "calc.core",
            "Calc",
        )
        assertEquals("CalcTest", whole.className)
        assertEquals("my.test.CalcTest", whole.mainClass)
        assertTrue(whole.source.contains("new calc.core.Calc();"), whole.source)
    }

    @Test
    fun keepsWholeClass() {
        val code = listOf(
            "import java.util.*;",
            "public final class CalcTest {",
            "    public static void main(String[] args) { }",
            "}",
        ).joinToString(nl)
        val prepared = TestCode.prepare(code, "calc.core", "Calculator")
        assertEquals("CalcTest", prepared.className)
        assertEquals("calc.core.CalcTest", prepared.mainClass)
        assertEquals("package calc.core;" + nl + nl + code + nl, prepared.source)

        // package を書いていればそのまま使う
        val own = TestCode.prepare("package my.test;" + nl + code, "calc.core", "Calculator")
        assertEquals("my.test.CalcTest", own.mainClass)
        assertEquals("package my.test;" + nl + code + nl, own.source)

        // public でないクラスだけなら main の中身として扱う (ローカルクラスなど)
        val local = TestCode.prepare("class Helper { }" + nl + "new Helper();", null, "Calculator")
        assertEquals("TaaTest__", local.className)
    }

    // ------------------------------------------------------------ 判定

    @Test
    fun judgesTestCode() {
        fun execution(stdout: String = "", stderr: String = "", testCodeError: String? = null) =
            Execution("java", stdout, stderr, 0, false, 1.0, testCodeError = testCodeError)

        val code = case("code")
        assertEquals(Judge.AC, AutoJudge.judge(code, execution("13")))
        assertEquals(Judge.WA, AutoJudge.judge(code, execution("12")))
        assertEquals(Judge.RE, AutoJudge.judge(code, execution(stderr = "Exception in thread \"main\"")))
        // メソッドが無いなど、テストコードがコンパイルできない
        assertEquals(Judge.TCE, AutoJudge.judge(code, execution(testCodeError = "cannot find symbol")))
        assertTrue(Judge.TCE in Judge.needsReview, "テストコードの誤りのこともあるので要確認にする")
        // main を実行するテストは従来どおり
        assertEquals(Judge.AC, AutoJudge.judge(TestCase("m", expect = listOf("^ok$")), execution("ok")))
    }
}
