package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.Config
import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.model.CompiledSource
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.TestResult
import kotlinx.serialization.Serializable

/** どこまで実行するか */
enum class LastStep(val label: String) {
    Extract("zip 展開まで"),
    Compile("コンパイルまで"),
    Test("実行・テストまで"),
}

/** 実行中の段階 (進み具合の表示用) */
enum class Stage(val label: String) {
    Prepare("準備中"),
    Extract("zip 展開中"),
    Compile("コンパイル中"),
    Test("テスト実行中"),
}

enum class LogLevel { Info, Warning, Error }

/** 採点の途中経過 */
sealed interface PipelineEvent {
    data class Progress(val stage: Stage, val completed: Int, val total: Int) : PipelineEvent
    data class Log(val message: String, val level: LogLevel = LogLevel.Info) : PipelineEvent
}

data class GradingRequest(
    val rootDir: String,
    val configPath: String,
    val lastStep: LastStep = LastStep.Test,
    val compileParallelism: Int = 4,
    val testParallelism: Int = 4,
    val cleanWorkspace: Boolean = true,
    val toolchain: JavaToolchain = JavaToolchain(),
    /**
     * 手動で決めた課題 (キーはソースの [SourceFile.displayPath])。自動の課題判定より優先する。
     * 空のリストは「どの課題でもない」(紐づけをすべて外した) を表す
     */
    val assignments: Map<String, List<TaskName>> = emptyMap(),
)

/** 採点の結果。[lastStep] より後の段階の結果は空 */
@Serializable
data class GradingOutcome(
    val config: Config,
    val workspaceDir: String,
    val lastStep: LastStep,
    val students: List<Student>,
    val compiled: List<CompiledSource>,
    val results: List<TestResult>,
)
