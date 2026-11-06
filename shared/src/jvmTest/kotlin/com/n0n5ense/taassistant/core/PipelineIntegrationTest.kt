package com.n0n5ense.taassistant.core

import kotlinx.coroutines.runBlocking
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 実際に javac / java を使って、展開からレポート出力までを通しで確認する */
class PipelineIntegrationTest {
    private val root: File = Files.createTempDirectory("taassistant-test").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private val config = """
        version: 2
        compileTimeout: 30000
        runningTimeout: 3000
        outputFileName: result
        tasks:
          課題1:
            word: [課題1]
            testcase:
              - name: sum
                arg: "1 10"
                expect: ["^.*(?<![-\\d])55(?!\\d).*"]
              - name: stdin
                input: ["3", "4"]
                expect: ["^\\s*7\\s*$"]
          課題2:
            word: [課題2]
            testcase:
              - name: hello
                expect: ["^こんにちは\\s*$"]
    """.trimIndent()

    private val correct = """
        // 課題1
        import java.util.Scanner;
        public class Sum {
            public static void main(String[] args) {
                if (args.length == 2) {
                    int a = Integer.parseInt(args[0]), b = Integer.parseInt(args[1]), s = 0;
                    for (int i = a; i <= b; i++) s += i;
                    System.out.println("合計: " + s);
                } else {
                    Scanner sc = new Scanner(System.in);
                    System.out.println(sc.nextInt() + sc.nextInt());
                }
            }
        }
    """.trimIndent()

    private val packaged = """
        // 課題2
        package jp.example;
        public class Hello {
            public static void main(String[] args) { System.out.println("こんにちは"); }
        }
    """.trimIndent()

    private val broken = """
        // 課題1
        public class Sum { public static void main(String[] args) { int x = "oops"; } }
    """.trimIndent()

    private val infinite = """
        // 課題1
        public class Sum { public static void main(String[] args) { while (true) {} } }
    """.trimIndent()

    private fun submission(dirName: String, files: Map<String, ByteArray>) {
        val dir = File(root, dirName).apply { mkdirs() }
        ZipOutputStream(File(dir, "submit.zip").outputStream()).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("__MACOSX/._Sum.java"))
            zip.closeEntry()
        }
    }

    @Test
    fun gradesSubmissionsEndToEnd() = runBlocking {
        val shiftJis = Charset.forName("Shift_JIS")
        submission(
            "t0000001 山田 太郎_111_assignsubmission_file_",
            mapOf(
                "kadai/Sum.java" to correct.toByteArray(Charsets.UTF_8),
                // パッケージ付き・Shift_JIS のソース
                "kadai/jp/example/Hello.java" to packaged.toByteArray(shiftJis),
            ),
        )
        submission("t0000002 鈴木_222_assignsubmission_file_", mapOf("Sum.java" to broken.toByteArray()))
        submission("t0000003 佐藤_333_assignsubmission_file", mapOf("Sum.java" to infinite.toByteArray()))
        File(root, "t0000004 提出なし_444_assignsubmission_file_").mkdirs()
        val configFile = File(root, "config.yaml").apply { writeText(config) }

        val toolkit = JvmGradingToolkit()
        val outcome = GradingPipeline(toolkit).run(
            GradingRequest(rootDir = root.path, configPath = configFile.path),
        ) { }

        assertEquals(4, outcome.students.size)
        assertEquals("山田 太郎", outcome.students.first().name)

        fun judgeOf(student: String, testCase: String?) = outcome.results
            .filter { it.studentId.value == student && it.testCase?.name == testCase }
            .map { it.judge }

        assertEquals(listOf(Judge.AC), judgeOf("t0000001", "sum"))
        assertEquals(listOf(Judge.AC), judgeOf("t0000001", "stdin"))
        assertEquals(listOf(Judge.AC), judgeOf("t0000001", "hello"))
        assertEquals(
            SourceEncoding.ShiftJIS,
            outcome.compiled.single { it.source.fileName == "Hello.java" }.outcome.encoding,
        )
        assertEquals(listOf(Judge.CE), judgeOf("t0000002", null))
        assertEquals(listOf(Judge.TLE, Judge.TLE), judgeOf("t0000003", "sum") + judgeOf("t0000003", "stdin"))

        val report = buildReport(outcome.config, outcome.students, outcome.results)
        val summaryOf = report.summary.rows.associateBy { it.studentId.value }
        assertEquals(listOf(100, 100), summaryOf.getValue("t0000001").acceptRatios)
        assertEquals(listOf(Judge.NF, Judge.NF, Judge.NF), summaryOf.getValue("t0000004").stats)

        val outDir = File(root, "out")
        val xlsx = toolkit.writeReport(outDir.path, "result", ReportFormat.Xlsx, report).single()
        XSSFWorkbook(File(xlsx)).use { wb ->
            assertEquals(listOf("Detail", "Summary"), (0 until wb.numberOfSheets).map(wb::getSheetName))
            assertEquals(outcome.results.size, wb.getSheet("Detail").lastRowNum)
            assertEquals(4, wb.getSheet("Summary").lastRowNum)
        }
        val csv = toolkit.writeReport(outDir.path, "result", ReportFormat.CsvShiftJis, report)
        assertEquals(2, csv.size)
        assertTrue(File(csv[1]).readText(shiftJis).contains("山田 太郎"))

        // UTF-8 のファイルは BOM 無しで書き出す
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val utf8Csv = toolkit.writeReport(outDir.path, "result", ReportFormat.Csv, report)
        utf8Csv.forEach { path ->
            val bytes = File(path).readBytes()
            assertFalse(bytes.take(3) == utf8Bom.toList(), "$path に BOM が付いています")
            assertTrue(bytes.toString(Charsets.UTF_8).contains("山田 太郎"))
        }
        val yamlPath = File(outDir, "config.yaml").path
        toolkit.writeTextFile(yamlPath, ConfigParser.encode(outcome.config))
        assertFalse(File(yamlPath).readBytes().take(3) == utf8Bom.toList(), "YAML に BOM が付いています")
    }
}
