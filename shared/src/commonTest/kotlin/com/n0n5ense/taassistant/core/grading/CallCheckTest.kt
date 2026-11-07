package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.DraftField
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.config.TestMethod
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

/** main から直接呼んでいるメソッドの確認のうち、javap を使わない部分 */
class CallCheckTest {
    private val nl = Char(10).toString()

    /** 実際の `javap -c -p Hello` の出力 (JDK 21) */
    private val javap = listOf(
        "Compiled from \"Hello.java\"",
        "public class Hello {",
        "  public Hello();",
        "    Code:",
        "       0: aload_0",
        "       1: invokespecial #1                  // Method java/lang/Object.\"<init>\":()V",
        "       4: return",
        "",
        "  public static void main(java.lang.String[]);",
        "    Code:",
        "      13: invokestatic  #7                  // Method java/util/Arrays.sort:([I)V",
        "      16: new           #13                 // class java/util/ArrayList",
        "      20: invokespecial #15                 // Method java/util/ArrayList.\"<init>\":()V",
        "      26: invokestatic  #16                 // Method java/lang/Integer.valueOf:(I)Ljava/lang/Integer;",
        "      29: invokeinterface #22,  2           // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z",
        "      35: invokedynamic #28,  0             // InvokeDynamic #0:run:()Ljava/lang/Runnable;",
        "      42: invokedynamic #32,  0             // InvokeDynamic #1:accept:()Ljava/util/function/Consumer;",
        "      47: invokeinterface #36,  2           // InterfaceMethod java/util/List.forEach:(Ljava/util/function/Consumer;)V",
        "      52: invokestatic  #40                 // Method helper:()V",
        "      55: return",
        "",
        "  static void helper();",
        "    Code:",
        "       4: invokespecial #15                 // Method java/util/ArrayList.\"<init>\":()V",
        "       7: invokestatic  #45                 // Method java/util/Collections.reverse:(Ljava/util/List;)V",
        "      10: return",
        "",
        "  private static void lambda\$main\$0();",
        "    Code:",
        "       0: getstatic     #51                 // Field java/lang/System.out:Ljava/io/PrintStream;",
        "       5: invokevirtual #59                 // Method java/io/PrintStream.println:(Ljava/lang/String;)V",
        "       8: return",
        "}",
    ).joinToString(nl)

    @Test
    fun parsesDirectCallsInMain() {
        assertEquals(
            listOf(
                "java.util.Arrays.sort",
                "java.util.ArrayList.<init>",
                "java.lang.Integer.valueOf",
                "java.util.List.add",
                "java.util.List.forEach",
                // 同じクラスのメソッドはクラス名が省略されている
                "Hello.helper",
                // main の中のラムダの本体
                "java.io.PrintStream.println",
            ),
            CallCheck.parseJavap(javap, "Hello"),
        )
        // パッケージ付きのクラス
        assertTrue(CallCheck.parseJavap(javap, "pkg.Hello")!!.contains("pkg.Hello.helper"))
        // main が無い (static でない main も対象外)
        assertNull(CallCheck.parseJavap(javap.replace("public static void main(java.lang.String[]);", "public void main(java.lang.String[]);"), "Hello"))
        assertNull(CallCheck.parseJavap("public class X {" + nl + "}", "X"))
        // Windows の改行
        assertEquals(CallCheck.parseJavap(javap, "Hello"), CallCheck.parseJavap(javap.replace(nl, "\r\n"), "Hello"))
    }

    @Test
    fun matchesSpecifications() {
        val calls = CallCheck.parseJavap(javap, "Hello")!!
        fun found(spec: String) = calls.any { CallCheck.matches(spec, it) }
        // メソッド名だけ / クラス付き (パッケージは省略可) / 末尾の () は無視
        assertTrue(found("sort"))
        assertTrue(found("Arrays.sort"))
        assertTrue(found("java.util.Arrays.sort"))
        assertTrue(found("Arrays.sort()"))
        assertFalse(found("List.sort"))
        assertFalse(found("util.Arrays.sort2"))
        // コンストラクタ
        assertTrue(found("new ArrayList"))
        assertTrue(found("java.util.ArrayList.<init>"))
        assertFalse(found("new LinkedList"))
        // 自作メソッドの中 (間接の呼び出し) は数えない
        assertTrue(found("helper"))
        assertFalse(found("Collections.reverse"))
        // ラムダの中は数える
        assertTrue(found("System.out.println") || found("PrintStream.println"))
        assertTrue(found("println"))
        // 入れ子のクラス (javap では Outer$Inner)
        assertTrue(CallCheck.matches("Map.Entry.getKey", "java.util.Map\$Entry.getKey"))
        assertTrue(CallCheck.matches("Entry.getKey", "java.util.Map\$Entry.getKey"))

        assertEquals(listOf("Collections.reverse"), CallCheck.missing(listOf("sort", "Collections.reverse"), calls))
    }

    @Test
    fun judgesCallCheck() {
        val testCase = TestCase("calls", calls = listOf("Arrays.sort", "new ArrayList"))
        fun execution(calls: List<String>?) = Execution("javap", "", "", 0, false, 1.0, mainCalls = calls)
        assertEquals(Judge.AC, AutoJudge.judge(testCase, execution(listOf("java.util.Arrays.sort", "java.util.ArrayList.<init>"))))
        assertEquals(Judge.WA, AutoJudge.judge(testCase, execution(listOf("java.util.Arrays.sort"))))
        assertEquals(Judge.WA, AutoJudge.judge(testCase, execution(emptyList())), "main が無い")
        assertEquals(Judge.IE, AutoJudge.judge(testCase, execution(null)), "javap を実行できなかった")
    }

    @Test
    fun editsCallsInDraft() {
        val yaml = listOf(
            "version: 2",
            "tasks:",
            "  課題1:",
            "    word: [課題1]",
            "    testcase:",
            "      - name: uses-sort",
            "        calls: [Arrays.sort, new Scanner]",
        ).joinToString(nl)
        val config = ConfigParser.parse(yaml)
        assertEquals(listOf("Arrays.sort", "new Scanner"), config.tasks.getValue(TaskName("課題1")).testcase.single().calls)
        assertEquals(config, ConfigParser.parse(ConfigParser.encode(config)))

        var key = 0L
        val draft = config.toDraft { key++ }
        val case = draft.tasks[0].testCases[0]
        assertEquals(TestMethod.Calls, case.method)
        assertEquals("Arrays.sort" + nl + "new Scanner", case.calls)
        assertTrue(draft.validate().isEmpty())
        assertEquals(config, draft.toConfig())

        fun withCase(c: com.n0n5ense.taassistant.core.config.TestCaseDraft) =
            draft.copy(tasks = listOf(draft.tasks[0].copy(testCases = listOf(c))))
        // 空ならエラー
        assertEquals(listOf(DraftField.Calls), withCase(case.copy(calls = " " + nl)).validate().map { it.field })
        // 前後の空白は除く
        assertEquals(listOf("sort"), withCase(case.copy(calls = "  sort  " + nl + nl)).toConfig().tasks.values.single().testcase.single().calls)
        // 他の方法に切り替えると保存しない
        assertEquals(emptyList(), withCase(case.copy(method = TestMethod.Main)).toConfig().tasks.values.single().testcase.single().calls)
    }
}
