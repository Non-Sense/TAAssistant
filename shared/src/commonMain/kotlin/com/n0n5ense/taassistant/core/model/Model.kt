package com.n0n5ense.taassistant.core.model

import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** テスト結果の判定 (AtCoder 方式) */
enum class Judge(val label: String, val description: String) {
    AC("AC", "正答"),
    WA("WA", "誤答"),
    TLE("TLE", "実行時間制限超過"),
    RE("RE", "実行時エラー"),
    CE("CE", "コンパイルエラー"),
    IE("IE", "システム内部エラー"),
    NF("NF", "提出なし / 課題を特定できない"),
    CF("CF", "複数の提出があり結果を特定できない"),
    Unknown("??", "未判定");

    override fun toString(): String = label

    companion object {
        /** 手動判定で選択できる判定 */
        val manualChoices: List<Judge> = listOf(AC, WA, TLE, RE, CE)

        /** 確認が必要そうな判定 */
        val needsReview: Set<Judge> = setOf(WA, TLE, RE, IE, Unknown)
    }
}

enum class SourceEncoding(val charsetName: String) {
    UTF8("UTF-8"),
    ShiftJIS("Shift_JIS"),
}

@Serializable
@JvmInline
value class StudentId(val value: String) {
    override fun toString(): String = value
}

@Serializable
data class Student(
    val id: StudentId,
    val name: String,
    /** workspace/<学籍番号> の絶対パス */
    val workspaceDir: String,
    /** 元の提出ディレクトリ (Moodle からダウンロードしたもの) の絶対パス。古いプロジェクトファイルでは空 */
    val submissionDir: String = "",
)

@Serializable
data class SourceFile(
    val studentId: StudentId,
    /** ソースファイルの絶対パス */
    val path: String,
    /** workspace からの相対パス (表示用) */
    val displayPath: String,
) {
    val fileName: String get() = displayPath.substringAfterLast('/').substringAfterLast('\\')
}

@Serializable
sealed interface CompileOutcome {
    val command: String

    @Serializable
    @SerialName("success")
    data class Success(
        override val command: String,
        val encoding: SourceEncoding,
        val packageName: String?,
        val className: String,
        /** -d で指定した出力先 (実行時の classpath) */
        val classPath: String,
        val warnings: String = "",
    ) : CompileOutcome {
        val mainClass: String get() = packageName?.let { "$it.$className" } ?: className
    }

    @Serializable
    @SerialName("failure")
    data class Failure(
        override val command: String,
        /** null: どの文字コードでも読めなかった */
        val encoding: SourceEncoding?,
        val packageName: String?,
        val message: String,
    ) : CompileOutcome

    /** javac を起動できなかった等のシステム側エラー */
    @Serializable
    @SerialName("error")
    data class Error(
        override val command: String,
        val message: String,
    ) : CompileOutcome
}

val CompileOutcome.encoding: SourceEncoding?
    get() = when (this) {
        is CompileOutcome.Success -> encoding
        is CompileOutcome.Failure -> encoding
        is CompileOutcome.Error -> null
    }

val CompileOutcome.packageName: String?
    get() = when (this) {
        is CompileOutcome.Success -> packageName
        is CompileOutcome.Failure -> packageName
        is CompileOutcome.Error -> null
    }

@Serializable
data class CompiledSource(
    val source: SourceFile,
    val outcome: CompileOutcome,
)

/** プログラムを1回実行した結果 */
@Serializable
data class Execution(
    val command: String,
    val stdout: String,
    val stderr: String,
    /** タイムアウト時は null */
    val exitCode: Int?,
    val timedOut: Boolean,
    val elapsedMillis: Double,
)

/** 1ソースファイル × 1課題 × 1テストケース の結果 */
@Serializable
data class TestResult(
    val id: Int,
    val source: SourceFile,
    val compile: CompileOutcome,
    /** null: 課題を特定できなかった */
    val taskName: TaskName?,
    /** null: テストを実行していない (NF / CE / IE など) */
    val testCase: TestCase?,
    val autoJudge: Judge,
    val manualJudge: Judge? = null,
    val execution: Execution? = null,
    /** 内部エラーのスタックトレースなど */
    val note: String = "",
    /** 課題を手動で割り当てて実行した結果 */
    val assigned: Boolean = false,
) {
    val studentId: StudentId get() = source.studentId
    val judge: Judge get() = manualJudge ?: autoJudge
}
