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
        execution.timedOut -> Judge.TLE
        runtimeErrorMarkers.any(execution.stderr::contains) -> Judge.RE
        testCase.matches(execution.stdout) -> Judge.AC
        else -> Judge.WA
    }
}
