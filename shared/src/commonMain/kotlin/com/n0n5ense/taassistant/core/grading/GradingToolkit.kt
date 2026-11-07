package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.SubmissionDirName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.Execution
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.report.Report
import com.n0n5ense.taassistant.core.report.ReportFormat

/** ファイル一覧の1件 */
data class FileEntry(
    /** 絶対パス */
    val path: String,
    /** 一覧の基準ディレクトリからの相対パス (区切りは /) */
    val relativePath: String,
    val size: Long,
) {
    val fileName: String get() = relativePath.substringAfterLast('/')
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
}

/** 学生の提出ファイル */
data class SubmissionFiles(
    /** 元の提出ディレクトリにあるファイル (zip など)。ディレクトリが分からなければ空 */
    val submitted: List<FileEntry>,
    /** workspace に展開・コピーしたソース */
    val extracted: List<FileEntry>,
)

/** javac / java の場所 */
data class JavaToolchain(
    /** JDK の bin ディレクトリ。空なら PATH 上のコマンドを使う */
    val jdkBinDir: String = "",
)

/**
 * ファイルシステムやプロセス実行など、プラットフォーム依存の処理。
 * 採点の手順そのものは [GradingPipeline] が持つ。
 */
interface GradingToolkit {
    suspend fun readTextFile(path: String): String

    /** UTF-8 (BOM なし) で書き込む */
    suspend fun writeTextFile(path: String, text: String)

    suspend fun readBinaryFile(path: String): ByteArray

    /** 一時ファイルに書いてから置き換える */
    suspend fun writeBinaryFile(path: String, bytes: ByteArray)

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

    /** 学生の提出ファイル (元の提出ディレクトリと、展開したソース) を一覧する */
    suspend fun listSubmissionFiles(student: Student): SubmissionFiles

    /** アーカイブを展開せずに、中の項目を一覧する */
    suspend fun listArchive(path: String, format: ArchiveFormat): ArchiveListing

    /** UTF-8 → Shift_JIS の順に試してコンパイルする */
    suspend fun compile(
        source: SourceFile,
        student: Student,
        toolchain: JavaToolchain,
        timeoutMillis: Long
    ): CompileOutcome

    suspend fun readSource(source: SourceFile, encoding: SourceEncoding): String

    suspend fun execute(
        target: CompileOutcome.Success,
        testCase: TestCase,
        toolchain: JavaToolchain,
        timeoutMillis: Long,
    ): Execution

    /** 結果ファイルを書き出し、書き出したファイルのパスを返す */
    suspend fun writeReport(
        outputDir: String,
        baseName: String,
        format: ReportFormat,
        report: Report
    ): List<String>
}
