package com.n0n5ense.taassistant.core

import com.charleskorn.kaml.SingleLineStringStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.jvm.JvmInline

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Config(
    /** コンフィグの形式のバージョン。YAML の先頭に必ず書き出す */
    @EncodeDefault val version: Int = CURRENT_VERSION,
    /** コンパイルのタイムアウト(ミリ秒) */
    @EncodeDefault val compileTimeout: Long = 3000,
    /** 実行のタイムアウト(ミリ秒)。負の値なら無制限 */
    @EncodeDefault val runningTimeout: Long = 5000,
    /** 出力ファイル名(拡張子なし) */
    @EncodeDefault val outputFileName: String = "output",
    /**
     * 提出ディレクトリ名の正規表現 (ディレクトリ名全体にマッチさせる)。
     * 名前付きグループ `id` (学籍番号, 必須) と `name` (氏名, 任意) を使う。
     * 名前付きグループが無ければ 1番目 = 学籍番号, 2番目 = 氏名 として扱う。
     */
    val submissionDirPattern: String = DEFAULT_SUBMISSION_DIR_PATTERN,
    val tasks: Map<TaskName, Task>,
) {
    init {
        require(version == CURRENT_VERSION) { "version: $version には対応していません (現在のバージョン: $CURRENT_VERSION)" }
    }

    val testCaseCount: Int get() = tasks.values.sumOf { it.testcase.size }

    @Transient
    val submissionDirRegex: Regex = Regex(submissionDirPattern)

    companion object {
        /**
         * 現在のコンフィグ形式のバージョン。形式を変えたら上げて、[ConfigMigrator] に変換手順を追加する。
         * - 1: version 項目なし (disablePackage あり)
         * - 2: version 項目を追加、disablePackage を廃止
         */
        const val CURRENT_VERSION = 2

        /** `<学籍番号> <名前>_<数字>_assignsubmission_file_` (Moodle の既定) */
        const val DEFAULT_SUBMISSION_DIR_PATTERN = """^(?<id>\S+)\s+(?<name>[^_]*)_.*assignsubmission_file_?$"""
    }
}

data class SubmissionDirName(val studentId: String, val studentName: String)

/** 提出ディレクトリ名から学籍番号と氏名を取り出す。対象外のディレクトリなら null */
fun Config.parseSubmissionDirName(dirName: String): SubmissionDirName? {
    val match = submissionDirRegex.matchEntire(dirName) ?: return null
    val named = "(?<id>" in submissionDirPattern
    fun group(name: String, index: Int): String? =
        if (named) {
            runCatching { match.groups[name]?.value }.getOrNull()
        } else {
            match.groupValues.getOrNull(index)
        }
    val id = group("id", 1)?.trim().orEmpty()
    if (id.isEmpty()) return null
    return SubmissionDirName(id, group("name", 2)?.trim().orEmpty())
}

@Serializable
data class Task(
    /** ソース中にこの文字列のどれかが含まれていれば、この課題の提出とみなす */
    val word: List<String>,
    /** ソース中にこの文字列のどれかが含まれていれば、この課題の提出とはみなさない */
    val excludeWord: List<String> = emptyList(),
    val testcase: List<TestCase> = emptyList(),
)

@Serializable
@JvmInline
value class TaskName(val name: String) {
    override fun toString(): String = name
}

@Serializable
data class TestCase(
    val name: String,
    /** コマンドライン引数。空白区切り、ダブルクォートで囲むと空白を含められる */
    val arg: String = "",
    /** 標準入力 (1要素 = 1行) */
    val input: List<String> = emptyList(),
    /** 想定標準出力 (正規表現)。改行を空白に置換した標準出力全体と、すべてがマッチすれば AC */
    val expect: List<String> = emptyList(),
) {
    @Transient
    val expectRegex: List<Regex> = expect.map(::Regex)
}

object ConfigParser {
    private val writer = Yaml(
        configuration = YamlConfiguration(
            encodeDefaults = false,
            singleLineStringStyle = SingleLineStringStyle.SingleQuoted,
        ),
    )

    /**
     * 現在のバージョンのコンフィグを読み込む。
     * 古いバージョン (version 項目なしを含む) なら [LegacyConfigException] を投げる。
     */
    fun parse(yamlText: String): Config {
        val text = yamlText.removePrefix("\uFEFF")
        val version = ConfigMigrator.detectVersion(text)
        when {
            version == null -> Unit // YAML として壊れている。下の decode でエラー内容を出す
            version < Config.CURRENT_VERSION -> throw LegacyConfigException(version)
            version > Config.CURRENT_VERSION -> throw IllegalArgumentException(
                "このコンフィグ (version: $version) はより新しいバージョンのアプリ用です。" +
                    "このアプリが対応しているのは version: ${Config.CURRENT_VERSION} までです。",
            )
        }
        return Yaml.default.decodeFromString(Config.serializer(), text)
    }

    /** 基本設定以外の既定値の項目は省略して書き出す。文字列はシングルクォート (正規表現をエスケープせずに読める) */
    fun encode(config: Config): String =
        writer.encodeToString(Config.serializer(), config).trimEnd() + "\n"
}
