package com.n0n5ense.taassistant.ui.results

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

/** 結果タブの絞り込みと、学生ごとの開閉 */
data class ResultViewState(
    val filter: ResultFilter = ResultFilter.All,
    val query: String = "",
    val collapsed: Set<StudentId> = emptySet(),
)
