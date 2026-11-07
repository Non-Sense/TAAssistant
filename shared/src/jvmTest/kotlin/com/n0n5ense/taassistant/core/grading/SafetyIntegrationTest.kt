package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ConfigParser
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 実際に javac / javap / java を使って、実行する前の検査と、実行時の対策を確かめる */
class SafetyIntegrationTest {
    private val root: File = Files.createTempDirectory("taassistant-safety").toFile()
    private val toolkit = JvmGradingToolkit()
    private val pipeline = GradingPipeline(toolkit)
    private val nl = Char(10).toString()
    private val student = Student(StudentId("s1"), "学生1", File(root, "s1").absolutePath)

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    /** コンパイルして、実行する前の検査をする */
    private fun compile(relativePath: String, lines: List<String>): CompiledSource {
        val file = File(root, "s1/sources/$relativePath").apply {
            parentFile.mkdirs()
            writeText(lines.joinToString(nl) + nl)
        }
        val source = SourceFile(student.id, file.absolutePath, "s1/sources/$relativePath")
        val outcome = runBlocking {
            pipeline.inspect(source, toolkit.compile(source, student, JavaToolchain(), 30_000), JavaToolchain())
        }
        assertTrue(outcome is CompileOutcome.Success, outcome.toString())
        return CompiledSource(source, outcome)
    }

    @Test
    fun detectsSuspiciousCallsInAllClassesOfSource() {
        val evil = compile(
            "app/Evil.java",
            listOf(
                "package app;",
                "import java.io.*;",
                "public class Evil {",
                "    public static void main(String[] args) throws Exception {",
                "        PrintWriter out = new PrintWriter(System.out);", // System.out に書くのは対象外
                "        out.println(\"hi\");",
                "        out.flush();",
                "        new Helper().run();",
                "    }",
                "    static class Helper {",
                "        void run() throws IOException { Runtime.getRuntime().exec(\"calc\"); }",
                "    }",
                "}",
                "class Other {",
                "    void save() throws IOException { new FileWriter(\"x.txt\").close(); }",
                "}",
            ),
        )
        // 同じ学生の別のソース (同じディレクトリにクラスファイルができる)
        val clean = compile("app/Clean.java", listOf("package app;", "public class Clean { public static void main(String[] a) { System.out.println(1); } }"))

        val outcome = evil.outcome as CompileOutcome.Success
        assertEquals(
            setOf(
                "java.lang.Runtime.exec" to "app.Evil\$Helper.run",
                "java.io.FileWriter.<init>" to "app.Other.save",
            ),
            outcome.suspicious.map { it.call to it.location }.toSet(),
        )
        assertEquals(64, outcome.sourceHash.length)
        // 別のソースのクラスは、Evil のクラスと同じディレクトリにあっても対象外
        val cleanOutcome = clean.outcome as CompileOutcome.Success
        assertEquals(emptyList(), cleanOutcome.suspicious)
        assertNotEquals(outcome.sourceHash, cleanOutcome.sourceHash)
    }

    @Test
    fun holdsUntilApprovedThroughPipeline() {
        val config = ConfigParser.parse(
            listOf(
                "version: 2",
                "tasks:",
                "  課題1:",
                "    word: [課題1]",
                "    testcase:",
                "      - name: t",
                "        expect: ['^ok\\s*$']",
            ).joinToString(nl),
        )
        val compiled = compile(
            "Main.java",
            listOf(
                "// 課題1",
                "public class Main {",
                "    public static void main(String[] args) throws Exception {",
                "        if (args.length > 99) Class.forName(\"x\");",
                "        System.out.println(\"ok\");",
                "    }",
                "}",
            ),
        )
        val held = runBlocking { pipeline.testSource(config, compiled, null, JavaToolchain(), 1) }
        assertEquals(listOf(Judge.Unknown to true), held.map { it.judge to it.held })
        assertEquals(null, held.single().execution)
        val approved = runBlocking { pipeline.testSource(config, compiled, null, JavaToolchain(), 1, approved = true) }
        assertEquals(listOf(Judge.AC to false), approved.map { it.judge to it.held })
    }

    @Test
    fun runsInTemporaryWorkingDirectory() {
        // 相対パスで書いたファイルは、毎回作って捨てる作業ディレクトリに作られる
        val compiled = compile(
            "Writer.java",
            listOf(
                "public class Writer {",
                "    public static void main(String[] args) throws Exception {",
                "        java.io.File f = new java.io.File(\"taa-relative.txt\");",
                "        new java.io.FileWriter(f).close();",
                "        System.out.println(f.getAbsoluteFile().getParentFile().getName());",
                "    }",
                "}",
            ),
        )
        val execution = runBlocking {
            toolkit.execute(compiled.outcome as CompileOutcome.Success, TestCase("t"), JavaToolchain(), 10_000)
        }
        assertEquals(0, execution.exitCode, execution.stderr)
        val dirName = execution.stdout.trim()
        assertTrue(dirName.startsWith("taa-run-"), dirName)
        assertFalse(File(System.getProperty("java.io.tmpdir"), dirName).exists(), "作業ディレクトリは実行後に消す")
        assertFalse(File("taa-relative.txt").exists(), "このアプリの作業ディレクトリには書かない")
        assertTrue(execution.command.contains("-Xmx1g"), execution.command)
    }

    @Test
    fun killsChildProcessesOnTimeout() {
        // 自分自身を子プロセスとして起動し、子の PID を出力してから待ち続ける
        val compiled = compile(
            "Spawner.java",
            listOf(
                "public class Spawner {",
                "    public static void main(String[] args) throws Exception {",
                "        if (args.length > 0) { Thread.sleep(600_000); return; }",
                "        String java = ProcessHandle.current().info().command().orElse(\"java\");",
                "        Process child = new ProcessBuilder(java, \"-cp\", System.getProperty(\"java.class.path\"), \"Spawner\", \"child\").start();",
                "        System.out.println(child.pid());",
                "        System.out.flush();",
                "        Thread.sleep(600_000);",
                "    }",
                "}",
            ),
        )
        val execution = runBlocking {
            toolkit.execute(compiled.outcome as CompileOutcome.Success, TestCase("t"), JavaToolchain(), 5_000)
        }
        assertTrue(execution.timedOut)
        val childPid = execution.stdout.trim().toLong()
        // 少し待っても子プロセスが残っていない
        val deadline = System.currentTimeMillis() + 10_000
        while (ProcessHandle.of(childPid).map { it.isAlive }.orElse(false) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertFalse(ProcessHandle.of(childPid).map { it.isAlive }.orElse(false), "子プロセス $childPid が残っています")
    }
}
