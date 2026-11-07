package com.n0n5ense.taassistant.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 手動で判定する課題 (manual / check) のコンフィグ */
class ManualTaskConfigTest {
    private val nl = Char(10).toString()

    private val yaml = listOf(
        "version: 2",
        "tasks:",
        "  GUI:",
        "    word: [GUI]",
        "    manual: true",
        "    testcase:",
        "      - name: count",
        "        check: |-",
        "          ボタンを押すとカウントが1増える",
        "          リセットで0に戻る",
        "  課題2:",
        "    word: [課題2]",
    ).joinToString(nl)

    @Test
    fun parsesAndWritesManualTask() {
        val config = ConfigParser.parse(yaml)
        val gui = config.tasks.getValue(TaskName("GUI"))
        assertTrue(gui.manual)
        assertEquals("ボタンを押すとカウントが1増える" + nl + "リセットで0に戻る", gui.testcase.single().check)
        assertFalse(config.tasks.getValue(TaskName("課題2")).manual)

        // 書き出して読み直しても同じ。既定値 (manual: false・check なし) は書き出さない
        val written = ConfigParser.encode(config)
        assertEquals(config, ConfigParser.parse(written))
        assertEquals(1, Regex("manual:").findAll(written).count(), written)
        assertEquals(1, Regex("check:").findAll(written).count(), written)
    }

    @Test
    fun editsManualTaskInDraft() {
        val config = ConfigParser.parse(yaml)
        var key = 0L
        val draft = config.toDraft { key++ }
        assertTrue(draft.tasks[0].manual)
        assertEquals("ボタンを押すとカウントが1増える" + nl + "リセットで0に戻る", draft.tasks[0].testCases[0].check)
        assertTrue(draft.validate().isEmpty())
        assertEquals(config, draft.toConfig())

        // 手動の課題でも想定出力は必須ではない。確認することの改行は LF に揃え、末尾の空白行は除く
        val edited = draft.copy(
            tasks = listOf(
                draft.tasks[0].copy(testCases = listOf(draft.tasks[0].testCases[0].copy(check = "a\r\nb\r\n\r\n"))),
                draft.tasks[1].copy(manual = true),
            ),
        )
        assertTrue(edited.validate().isEmpty())
        val saved = edited.toConfig()
        assertEquals("a" + nl + "b", saved.tasks.getValue(TaskName("GUI")).testcase.single().check)
        assertTrue(saved.tasks.getValue(TaskName("課題2")).manual)
    }
}
