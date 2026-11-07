package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.ExecutionPermission
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.toConfig
import com.n0n5ense.taassistant.core.config.toDraft
import com.n0n5ense.taassistant.core.model.SuspiciousCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 実行する前の検査 (javap の解析と、危ない可能性がある呼び出しの判定) */
class SafetyCheckTest {
    private val nl = Char(10).toString()

    /** 2つのクラス (入れ子のクラスを含む) をまとめて逆アセンブルした出力 */
    private val javap = listOf(
        "Compiled from \"Main.java\"",
        "public class app.Main {",
        "  private static int count;",
        "",
        "  public app.Main();",
        "    Code:",
        "       1: invokespecial #1                  // Method java/lang/Object.\"<init>\":()V",
        "",
        "  public static void main(java.lang.String[]);",
        "    Code:",
        "       3: invokestatic  #7                  // Method run:()V",
        "      10: invokespecial #9                  // Method java/io/PrintWriter.\"<init>\":(Ljava/io/OutputStream;)V",
        "",
        "  static void run();",
        "    Code:",
        "       0: invokestatic  #13                 // Method java/lang/Runtime.getRuntime:()Ljava/lang/Runtime;",
        "       5: invokevirtual #19                 // Method java/lang/Runtime.exec:(Ljava/lang/String;)Ljava/lang/Process;",
        "",
        "  static {};",
        "    Code:",
        "       4: invokespecial #23                 // Method java/io/PrintWriter.\"<init>\":(Ljava/lang/String;)V",
        "}",
        "Compiled from \"Main.java\"",
        "class app.Main\$Helper {",
        "  void delete(java.io.File);",
        "    Code:",
        "       1: invokevirtual #7                  // Method java/io/File.delete:()Z",
        "       7: invokeinterface #12,  2           // InterfaceMethod java/util/List.add:(Ljava/lang/Object;)Z",
        "}",
        "Compiled from \"Other.java\"",
        "public class app.Other {",
        "  public static void main(java.lang.String[]);",
        "    Code:",
        "       0: invokestatic  #2                  // Method java/lang/Class.forName:(Ljava/lang/String;)Ljava/lang/Class;",
        "}",
    ).joinToString(nl)

    @Test
    fun parsesClassesAndCallSites() {
        val classes = Javap.parse(javap)
        assertEquals(listOf("app.Main", "app.Main\$Helper", "app.Other"), classes.map { it.className })
        assertEquals(listOf("Main.java", "Main.java", "Other.java"), classes.map { it.sourceFile })
        val main = classes[0].calls
        assertEquals(
            listOf(
                "app.Main.<init>" to "java.lang.Object.<init>",
                "app.Main.main" to "app.Main.run",
                "app.Main.main" to "java.io.PrintWriter.<init>",
                "app.Main.run" to "java.lang.Runtime.getRuntime",
                "app.Main.run" to "java.lang.Runtime.exec",
                "app.Main.<clinit>" to "java.io.PrintWriter.<init>",
            ),
            main.map { it.location to it.call },
        )
        assertEquals("(Ljava/lang/String;)Ljava/lang/Process;", main[4].descriptor)
        assertEquals("app.Main\$Helper.delete" to "java.io.File.delete", classes[1].calls[0].let { it.location to it.call })
        // Windows の改行でも同じ
        assertEquals(classes, Javap.parse(javap.replace(nl, "\r\n")))
    }

    @Test
    fun detectsSuspiciousCalls() {
        val calls = Javap.parse(javap).flatMap { it.calls }
        assertEquals(
            listOf(
                SuspiciousCall("java.lang.Runtime.exec", "app.Main.run", "外部プログラムの起動", ExecutionPermission.Process),
                // ファイル名を渡した PrintWriter はファイルに書く (System.out などに書くものは対象外)
                SuspiciousCall("java.io.PrintWriter.<init>", "app.Main.<clinit>", "ファイルの書き込み・削除", ExecutionPermission.FileWrite),
                SuspiciousCall("java.io.File.delete", "app.Main\$Helper.delete", "ファイルの書き込み・削除", ExecutionPermission.FileWrite),
                SuspiciousCall("java.lang.Class.forName", "app.Other.main", "リフレクション・動的なコードの実行", ExecutionPermission.Reflection),
            ),
            SafetyCheck.detect(calls),
        )
        // よく使う安全な呼び出しは対象外
        val safe = listOf("java.io.PrintStream.println", "java.util.Scanner.<init>", "java.util.Arrays.sort", "java.lang.Math.max")
            .map { CallSite("A.main", it.substringBeforeLast('.'), it.substringAfterLast('.'), "()V") }
        assertEquals(emptyList(), SafetyCheck.detect(safe))
        // 同じ場所・同じ呼び出しは1つにまとめる
        val twice = List(2) { CallSite("A.main", "java.lang.Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;") }
        assertEquals(1, SafetyCheck.detect(twice).size)
        // クラスのすべてのメソッド (Files.* など)
        val handle = CallSite("A.main", "java.lang.invoke.MethodHandles", "lookup", "()V")
        assertEquals(listOf("リフレクション・動的なコードの実行"), SafetyCheck.detect(listOf(handle)).map { it.category })
    }

    @Test
    fun allowsCallsPerTask() {
        val suspicious = listOf(
            SuspiciousCall("java.io.FileWriter.<init>", "A.main", "ファイルの書き込み・削除", ExecutionPermission.FileWrite),
            SuspiciousCall("java.nio.file.Files.write", "A.save", "ファイルの書き込み・削除", ExecutionPermission.FileWrite),
        )
        assertEquals(suspicious, SafetyCheck.blocking(suspicious, emptyList()))
        assertEquals(listOf(suspicious[1]), SafetyCheck.blocking(suspicious, listOf("new FileWriter")))
        assertEquals(emptyList(), SafetyCheck.blocking(suspicious, listOf("new FileWriter", "Files.*")))
        assertEquals(emptyList(), SafetyCheck.blocking(suspicious, listOf("FileWriter.<init>", "java.nio.file.Files.write")))
    }

    @Test
    fun allowsCategoriesPerTask() {
        val exec = SuspiciousCall("java.lang.Runtime.exec", "A.main", "外部プログラムの起動", ExecutionPermission.Process)
        val write = SuspiciousCall("java.io.FileWriter.<init>", "A.main", "ファイルの書き込み・削除", ExecutionPermission.FileWrite)
        // 検査できなかったものは、種類では許可できない
        val unknown = SuspiciousCall("(検査できませんでした)", "", "検査できませんでした")
        val all = listOf(exec, write, unknown)
        assertEquals(listOf(exec, unknown), SafetyCheck.blocking(all, emptyList(), setOf(ExecutionPermission.FileWrite)))
        assertEquals(listOf(unknown), SafetyCheck.blocking(all, emptyList(), ExecutionPermission.entries))
        // 種類と個別の呼び出しを組み合わせられる
        assertEquals(listOf(unknown), SafetyCheck.blocking(all, listOf("Runtime.exec"), setOf(ExecutionPermission.FileWrite)))
        // 課題の設定から
        val task = com.n0n5ense.taassistant.core.config.Task(word = listOf("x"), allow = listOf(ExecutionPermission.Process))
        assertEquals(listOf(write, unknown), SafetyCheck.blocking(all, task))
        assertEquals(all, SafetyCheck.blocking(all, null))
        // すべての規則に種類がある
        assertTrue(SafetyCheck.defaultRules.map { it.category }.toSet() == ExecutionPermission.entries.toSet())
    }

    @Test
    fun editsAllowCallsInConfig() {
        val yaml = listOf(
            "version: 2",
            "tasks:",
            "  ファイル:",
            "    word: [File]",
            "    allow: [network, fileWrite]",
            "    allowCalls: [new FileWriter, 'Files.*']",
        ).joinToString(nl)
        val config = ConfigParser.parse(yaml)
        assertEquals(
            listOf(ExecutionPermission.Network, ExecutionPermission.FileWrite),
            config.tasks.getValue(TaskName("ファイル")).allow,
        )
        val encoded = ConfigParser.encode(config)
        assertTrue("    allow:" + nl + "    - 'network'" + nl + "    - 'fileWrite'" in encoded, encoded)
        assertEquals(listOf("new FileWriter", "Files.*"), config.tasks.getValue(TaskName("ファイル")).allowCalls)
        assertEquals(config, ConfigParser.parse(ConfigParser.encode(config)))
        var key = 0L
        val draft = config.toDraft { key++ }
        assertEquals("new FileWriter" + nl + "Files.*", draft.tasks[0].allowCalls)
        assertEquals(setOf(ExecutionPermission.Network, ExecutionPermission.FileWrite), draft.tasks[0].allow)
        // エディタで保存すると、種類はいつも同じ順に並べる
        assertEquals(
            listOf(ExecutionPermission.FileWrite, ExecutionPermission.Network),
            draft.toConfig().tasks.values.single().allow,
        )
        assertEquals(config.copy(tasks = config.tasks.mapValues { it.value.copy(allow = it.value.allow.sortedBy { p -> p.ordinal }) }), draft.toConfig())
        val trimmed = draft.copy(tasks = listOf(draft.tasks[0].copy(allowCalls = "  Files.write " + nl + nl)))
        assertEquals(listOf("Files.write"), trimmed.toConfig().tasks.values.single().allowCalls)
        // 書かなければ YAML にも出さない
        assertTrue("allowCalls" !in ConfigParser.encode(trimmed.copy(tasks = listOf(trimmed.tasks[0].copy(allowCalls = ""))).toConfig()))
    }
}
