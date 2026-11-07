package com.n0n5ense.taassistant.core.project

import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.grading.GradingOutcome
import com.n0n5ense.taassistant.core.grading.LastStep
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.TestResult
import com.n0n5ense.taassistant.core.report.ReportFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 採点の設定 (プロジェクトごとに保存する) */
@Serializable
data class ProjectSettings(
    val rootDir: String = "",
    val configPath: String = "",
    /** 最後に結果を書き出したディレクトリ (次の書き出しの初期位置) */
    val outputDir: String = "",
    val lastStep: LastStep = LastStep.Test,
    val parallelTests: Boolean = true,
    val cleanWorkspace: Boolean = true,
    /** 最後に書き出した形式 (書き出しメニューの先頭に出す) */
    val reportFormat: ReportFormat = ReportFormat.Xlsx,
    val jdkBinDir: String = "",
)

/** ソースに手動で割り当てた課題 */
@Serializable
data class TaskAssignment(
    /** ソースの [SourceFile.displayPath] (workspace からの相対パス) */
    val source: String,
    val tasks: List<TaskName>,
)

/**
 * プロジェクトファイル (.taa)。コンフィグ (YAML) とは別に、採点の設定・課題の割り当て・採点結果 (手動判定を含む) を保存する。
 * 中身は gzip で圧縮した JSON (インデントなし)。圧縮していない JSON も読める。
 */
@Serializable
data class ProjectFile(
    val format: String = ProjectCodec.FORMAT,
    val version: Int = ProjectCodec.CURRENT_VERSION,
    val settings: ProjectSettings = ProjectSettings(),
    val assignments: List<TaskAssignment> = emptyList(),
    /** 最後に採点した結果。結果の手動判定もここに含まれる */
    val outcome: GradingOutcome? = null,
)

object ProjectCodec {
    const val EXTENSION = "taa"
    const val FORMAT = "ta-assistant-project"

    /**
     * プロジェクトファイルの形式のバージョン。形式を変えたら上げる。
     * - 1: 最初の形式
     */
    const val CURRENT_VERSION = 1

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    /** gzip で圧縮した JSON にする */
    fun encode(project: ProjectFile): ByteArray =
        gzip(json.encodeToString(ProjectFile.serializer(), project).encodeToByteArray())

    /** gzip で圧縮した JSON と、圧縮していない JSON (UTF-8) のどちらも読める */
    fun decode(bytes: ByteArray): ProjectFile {
        val text = if (bytes.isGzip()) {
            try {
                gunzip(bytes).decodeToString()
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "プロジェクトファイルが壊れています (展開できません): ${e.message}",
                    e
                )
            }
        } else {
            bytes.decodeToString()
        }
        return decodeJson(text)
    }

    private fun decodeJson(text: String): ProjectFile {
        val element = try {
            json.parseToJsonElement(text.removePrefix(Char(0xFEFF).toString()))
        } catch (e: SerializationException) {
            throw IllegalArgumentException(
                "プロジェクトファイルとして読み込めません (JSON の形式が正しくありません): ${e.message}",
                e
            )
        }
        val root = element as? JsonObject
            ?: throw IllegalArgumentException("プロジェクトファイルとして読み込めません")
        val format = root["format"]?.jsonPrimitive?.contentOrNull
        require(format == FORMAT) { "TA Assistant のプロジェクトファイルではありません" }
        val version = root["version"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("プロジェクトファイルの version がありません")
        require(version <= CURRENT_VERSION) {
            "このプロジェクトファイル (version: $version) はより新しいバージョンのアプリで作られています。" +
                    "このアプリが対応しているのは version: $CURRENT_VERSION までです。"
        }
        return try {
            json.decodeFromJsonElement(ProjectFile.serializer(), root)
        } catch (e: SerializationException) {
            throw IllegalArgumentException(
                "プロジェクトファイルの内容が正しくありません: ${e.message}",
                e
            )
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "プロジェクトファイルの内容が正しくありません: ${e.message}",
                e
            )
        }
    }
}

// ------------------------------------------------------------ 結果の操作

private fun List<TestResult>.nextId(): Int = (maxOfOrNull { it.id } ?: -1) + 1

/** NF の結果 [notFound] を、課題を割り当てて実行した結果 [assigned] に置き換える (id は振り直す) */
fun List<TestResult>.replaceWithAssigned(
    notFound: TestResult,
    assigned: List<TestResult>
): List<TestResult> {
    val index = indexOfFirst { it.id == notFound.id }
    require(index >= 0) { "結果が見つかりません" }
    var id = nextId()
    val renumbered = assigned.map { it.copy(id = id++, assigned = true) }
    return take(index) + renumbered + drop(index + 1)
}

/** [source] の結果をすべて [replacement] に置き換える (最初の結果の位置に入れ、id は振り直す) */
fun List<TestResult>.replaceSourceResults(source: SourceFile, replacement: List<TestResult>): List<TestResult> {
    val index = indexOfFirst { it.source == source }
    if (index < 0) return this
    var id = nextId()
    val renumbered = replacement.map { it.copy(id = id++) }
    val others = filterNot { it.source == source }
    return others.take(index) + renumbered + others.drop(index)
}

/**
 * [source] と課題 [task] の紐づけを外す (1つの課題に複数のソースが当たる CF の対策など)。
 * 残った課題の結果は手動で決めたもの (assigned) とする。課題が残らなければ NF の結果1つにする
 */
fun List<TestResult>.unlinkTask(source: SourceFile, task: TaskName): List<TestResult> {
    val ofSource = filter { it.source == source }
    val first = ofSource.firstOrNull() ?: return this
    val keepsOtherTask = ofSource.any { it.taskName != null && it.taskName != task }
    if (keepsOtherTask) {
        return filterNot { it.source == source && it.taskName == task }
            .map { if (it.source == source) it.copy(assigned = true) else it }
    }
    val notFound = TestResult(
        id = 0,
        source = source,
        compile = first.compile,
        taskName = null,
        testCase = null,
        autoJudge = Judge.NF,
        assigned = true,
    )
    return replaceSourceResults(source, listOf(notFound))
}

/**
 * 再採点した結果に、前回の手動判定を引き継ぐ。
 * 同じソース・同じ課題で、テストケースの内容 (名前・引数・入力・想定出力) が変わっていないものだけを引き継ぐ。
 * @return 引き継いだ結果と、引き継いだ件数
 */
fun carryOverManualJudges(
    previous: List<TestResult>,
    current: List<TestResult>
): Pair<List<TestResult>, Int> {
    fun key(r: TestResult) = Triple(r.source.displayPath, r.taskName, r.testCase)
    val manual = previous.filter { it.manualJudge != null }.associate { key(it) to it.manualJudge }
    if (manual.isEmpty()) return current to 0
    var count = 0
    val merged = current.map { r ->
        val judge = manual[key(r)]
        if (judge != null && r.manualJudge == null) {
            count++
            r.copy(manualJudge = judge)
        } else {
            r
        }
    }
    return merged to count
}
