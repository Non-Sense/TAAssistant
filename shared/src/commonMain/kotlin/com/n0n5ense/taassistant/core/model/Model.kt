package com.n0n5ense.taassistant.core.model

import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.config.TestCase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** テスト結果の判定 (AtCoder 方式) */
enum class Judge(val label: String, val description: String) {
    AC("AC", "正答"),
    WA("WA", "誤答"),
    TLE("TLE", "実行時間制限超過"),
    RE("RE", "実行時エラー"),
    CE("CE", "コンパイルエラー"),
    TCE("TCE", "テストコードのコンパイルエラー (メソッドが無い・引数の型が違う、またはテストコードの誤り)"),
    IE("IE", "システム内部エラー"),
    NF("NF", "提出なし / 課題を特定できない"),
    CF("CF", "複数の提出があり結果を特定できない"),
    Unknown("??", "未判定");

    override fun toString(): String = label

    companion object {
        /** 手動判定で選択できる判定 */
        val manualChoices: List<Judge> = listOf(AC, WA, TLE, RE, CE)

        /** 確認が必要そうな判定 (TCE はテストコード側の誤りのこともある) */
        val needsReview: Set<Judge> = setOf(WA, TLE, RE, TCE, IE, Unknown)
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
        /** 危ない可能性がある呼び出し (見つかったら、確認するまで実行しない) */
        val suspicious: List<SuspiciousCall> = emptyList(),
        /** ソースの内容のハッシュ (確認済みかを判断する。内容が変わったら確認し直す) */
        val sourceHash: String = "",
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
    /** テストコードを使うテストで、実際にコンパイルしたテストコード (パッケージ宣言などを補ったもの) */
    val testCode: String? = null,
    /** テストコードのコンパイルエラー (メソッドが無い・引数の型が違うなど)。null ならコンパイルできた */
    val testCodeError: String? = null,
    /** メソッド呼び出しを確認するテストで、main から直接呼んでいたメソッド。調べられなかったら null */
    val mainCalls: List<String>? = null,
)

/** 実行する前の検査で見つかった、危ない可能性がある呼び出し */
@Serializable
data class SuspiciousCall(
    /** 呼び出し先 (パッケージ付きのクラス名.メソッド名) */
    val call: String,
    /** 呼び出している場所 (パッケージ付きのクラス名.メソッド名) */
    val location: String,
    /** 種類 (外部プログラムの起動・ファイルの書き込みなど) */
    val category: String,
)

/** 1ソースファイル × 1課題 × 1テストケース の結果 */
@Serializable
data class TestResult(
    val id: Int,
    val source: SourceFile,
    val compile: CompileOutcome,
    /** null: 課題を特定できなかった */
    val taskName: TaskName?,
    /** null: テストを実行していない (NF / CE / IE など)。TCE ではテストコードのテストケースが入る */
    val testCase: TestCase?,
    val autoJudge: Judge,
    val manualJudge: Judge? = null,
    val execution: Execution? = null,
    /** 内部エラーのスタックトレースなど */
    val note: String = "",
    /** 課題を手動で割り当てて実行した結果 */
    val assigned: Boolean = false,
    /** 危ない可能性がある呼び出しがあるため、実行を保留している (確認すると実行する) */
    val held: Boolean = false,
) {
    val studentId: StudentId get() = source.studentId
    val judge: Judge get() = manualJudge ?: autoJudge
}
