package com.n0n5ense.taassistant.ui.results

import com.n0n5ense.taassistant.core.config.TaskName
import com.n0n5ense.taassistant.core.model.Judge
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.model.TestResult

/** 結果タブの絞り込み */
enum class ResultFilter(val label: String, val accepts: (TestResult) -> Boolean) {
    All("すべて", { true }),
    NeedsReview("要確認", { it.judge in Judge.needsReview }),
    Executed("実行したもの", { it.execution != null }),
    Manual("手動判定・割り当て", { it.manualJudge != null || it.assigned }),
    NotRun("NF / CE / IE", { it.judge == Judge.NF || it.judge == Judge.CE || it.judge == Judge.IE }),
}

/** 学生の中の、課題ごとのまとまり (課題を特定できなかった結果は [taskName] = null) */
data class TaskGroupKey(val studentId: StudentId, val taskName: TaskName?)

val TestResult.taskGroupKey: TaskGroupKey get() = TaskGroupKey(studentId, taskName)

/** 結果タブの絞り込みと、学生・課題ごとの開閉 */
data class ResultViewState(
    val filter: ResultFilter = ResultFilter.All,
    val query: String = "",
    /** 閉じている学生 */
    val collapsed: Set<StudentId> = emptySet(),
    /** 閉じている課題 (学生ごと) */
    val collapsedTasks: Set<TaskGroupKey> = emptySet(),
) {
    /** [result] が見えるように、その学生と課題を開く */
    fun reveal(result: TestResult): ResultViewState =
        copy(collapsed = collapsed - result.studentId, collapsedTasks = collapsedTasks - result.taskGroupKey)
}
