package com.n0n5ense.taassistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigDraftTest {
    private var key = 0L
    private fun newKey() = key++

    private val yaml = """
        version: 2
        compileTimeout: 2000
        runningTimeout: -1
        outputFileName: result
        submissionDirPattern: '^(?<id>\d+)_(?<name>.*)$'
        tasks:
          課題1:
            word: [課題1, kadai1]
            excludeWord: [前回課題]
            testcase:
              - name: t1
                arg: '"hello world" 2'
                input: ['1 2', '', '3']
                expect: ['^.*(?<![-\d])45(?!\d).*', '.*end.*']
          課題2:
            word: [課題2]
    """.trimIndent()

    @Test
    fun roundTripsThroughDraftAndYaml() {
        val original = ConfigParser.parse(yaml)
        val draft = original.toDraft(::newKey)
        assertEquals("1 2\n\n3", draft.tasks[0].testCases[0].input)
        assertTrue(draft.validate().isEmpty())

        val restored = draft.toConfig()
        assertEquals(original, restored)

        val written = ConfigParser.encode(restored)
        assertEquals(original, ConfigParser.parse(written))
        // 正規表現はエスケープされずに読める形で書き出される
        assertTrue("""'^.*(?<![-\d])45(?!\d).*'""" in written, written)
        // 既定値の項目は省略される
        assertFalse("excludeWord" in written.substringAfter("課題2"), written)
        // 基本設定は既定値でも必ず書き出す
        val defaults = ConfigParser.encode(Config(tasks = emptyMap()))
        assertTrue(defaults.startsWith("version: 2\ncompileTimeout: 3000\nrunningTimeout: 5000\noutputFileName: 'output'"), defaults)
    }

    @Test
    fun reportsValidationIssues() {
        val draft = ConfigDraft(
            compileTimeout = "abc",
            outputFileName = "a/b",
            submissionDirPattern = "(",
            tasks = listOf(
                TaskDraft(1, name = "A", words = "\n  \n", testCases = listOf(
                    TestCaseDraft(10, name = "t", expect = "ok\n[unclosed"),
                    TestCaseDraft(11, name = "t"),
                )),
                TaskDraft(2, name = "A", words = "x"),
                TaskDraft(3, name = " ", words = "x"),
            ),
        )
        val issues = draft.validate()
        fun has(field: DraftField, task: Long? = null, case: Long? = null) =
            issues.any { it.field == field && it.taskKey == task && it.testCaseKey == case }

        assertTrue(has(DraftField.CompileTimeout))
        assertFalse(has(DraftField.RunningTimeout))
        assertTrue(has(DraftField.OutputFileName))
        assertTrue(has(DraftField.SubmissionDirPattern))
        assertTrue(has(DraftField.Words, 1))
        assertTrue(has(DraftField.TaskName, 1) && has(DraftField.TaskName, 2) && has(DraftField.TaskName, 3))
        assertTrue(has(DraftField.TestCaseName, 1, 10) && has(DraftField.TestCaseName, 1, 11))
        assertTrue(issues.single { it.field == DraftField.Expect }.message.startsWith("2行目"))
    }

    @Test
    fun checksSampleOutput() {
        val tc = TestCaseDraft(1, name = "t", expect = "^.*45.*", sampleOutput = "sum\n45\n")
        assertEquals(true, tc.sampleMatches())
        assertEquals(false, tc.copy(sampleOutput = "44").sampleMatches())
        assertEquals(null, tc.copy(expect = "[").sampleMatches())
    }
}
