package com.n0n5ense.taassistant.core

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
        matchesExpectation(testCase, execution.stdout) -> Judge.AC
        else -> Judge.WA
    }

    /** 改行を空白に置換した標準出力全体が、expect の正規表現すべてにマッチするか */
    fun matchesExpectation(testCase: TestCase, stdout: String): Boolean {
        if (testCase.expectRegex.isEmpty()) return false
        val flattened = stdout.replace('\r', ' ').replace('\n', ' ')
        return testCase.expectRegex.all { it.matches(flattened) }
    }
}
