package com.n0n5ense.taassistant.core

/** javac / java の場所 */
data class JavaToolchain(
    /** JDK の bin ディレクトリ。空なら PATH 上のコマンドを使う */
    val jdkBinDir: String = "",
)

enum class ReportFormat(val label: String) {
    Xlsx("Excel (.xlsx)"),
    Csv("CSV (UTF-8)"),
    CsvShiftJis("CSV (Shift_JIS)"),
}

/**
 * ファイルシステムやプロセス実行など、プラットフォーム依存の処理。
 * 採点の手順そのものは [GradingPipeline] が持つ。
 */
interface GradingToolkit {
    suspend fun readTextFile(path: String): String

    /** UTF-8 (BOM なし) で書き込む */
    suspend fun writeTextFile(path: String, text: String)

    /** javac / java が使えるか確認する。問題があればエラーメッセージを返す */
    suspend fun verifyToolchain(toolchain: JavaToolchain): String?

    /** <rootDir>/workspace を用意してその絶対パスを返す */
    suspend fun prepareWorkspace(rootDir: String, clean: Boolean): String

    /**
     * 提出ディレクトリの zip / java を workspace/<学籍番号>/sources に展開する。
     * [parseDirName] が null を返したディレクトリは対象外。
     */
    suspend fun extractSubmissions(
        rootDir: String,
        workspaceDir: String,
        parseDirName: (String) -> SubmissionDirName?,
        log: (String) -> Unit,
    ): List<Student>

    suspend fun findSources(student: Student): List<SourceFile>

    /** UTF-8 → Shift_JIS の順に試してコンパイルする */
    suspend fun compile(source: SourceFile, student: Student, toolchain: JavaToolchain, timeoutMillis: Long): CompileOutcome

    suspend fun readSource(source: SourceFile, encoding: SourceEncoding): String

    suspend fun execute(
        target: CompileOutcome.Success,
        testCase: TestCase,
        toolchain: JavaToolchain,
        timeoutMillis: Long,
    ): Execution

    /** 結果ファイルを書き出し、書き出したファイルのパスを返す */
    suspend fun writeReport(outputDir: String, baseName: String, format: ReportFormat, report: Report): List<String>
}
