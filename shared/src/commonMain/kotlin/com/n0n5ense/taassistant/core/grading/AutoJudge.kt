package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.model.Execution
import com.n0n5ense.taassistant.core.model.Judge

object AutoJudge {
    private val runtimeErrorMarkers = listOf(
        "Exception in thread",
        "Error: Main method not found in class",
        "Error: Could not find or load main class",
        "Error: Unable to initialize main class",
    )

    fun judge(testCase: TestCase, execution: Execution): Judge = when {
        // メソッドが無い・引数の型が違うなど、提出されたクラスがテストコードの想定と合わない
        // (提出されたソース自体のコンパイルエラー CE とは区別する)
        execution.testCodeError != null -> Judge.TCE
        execution.timedOut -> Judge.TLE
        runtimeErrorMarkers.any(execution.stderr::contains) -> Judge.RE
        testCase.matches(execution.stdout) -> Judge.AC
        else -> Judge.WA
    }
}
