package com.n0n5ense.taassistant.core.config

/**
 * GUI エディタで編集中のコンフィグ。
 * 入力途中の不正な値 (数値でないタイムアウト、壊れた正規表現など) も保持できるよう、すべて文字列で持つ。
 * 複数要素の項目は「1行 = 1要素」のテキストで持つ。
 */
data class ConfigDraft(
    val compileTimeout: String = "3000",
    val runningTimeout: String = "5000",
    val outputFileName: String = "output",
    val submissionDirPattern: String = Config.DEFAULT_SUBMISSION_DIR_PATTERN,
    val tasks: List<TaskDraft> = emptyList(),
) {
    /** 保存対象外の項目 (お試し用の出力など) を除いたもの。未保存判定に使う */
    fun persistentPart(): ConfigDraft =
        copy(tasks = tasks.map { t -> t.copy(testCases = t.testCases.map { it.copy(sampleOutput = "") }) })
}

data class TaskDraft(
    /** UI 上で要素を識別するためのキー (保存されない) */
    val key: Long,
    val name: String = "",
    val words: String = "",
    val excludeWords: String = "",
    val testCases: List<TestCaseDraft> = emptyList(),
    /** true: 採点時に実行せず、1件ずつ実行して手動で判定する (GUI の課題など) */
    val manual: Boolean = false,
    /** 確認なしで実行してよい呼び出しの種類 */
    val allow: Set<ExecutionPermission> = emptySet(),
    /** 確認なしで実行してよい呼び出し (1行 = 1つ) */
    val allowCalls: String = "",
)

data class TestCaseDraft(
    val key: Long,
    val name: String = "",
    val arg: String = "",
    /** 標準入力 (改行区切り。空行も1行として扱う) */
    val input: String = "",
    /** 想定出力の正規表現 (1行 = 1つ) */
    val expect: String = "",
    /** 正規表現を試すための出力例 (保存されない) */
    val sampleOutput: String = "",
    /** テストの方法 */
    val method: TestMethod = TestMethod.Main,
    /** テストコード (Java) */
    val testCode: String = "",
    /** 確認すること (手動で判定する課題) */
    val check: String = "",
    /** main から直接呼んでいるか確認するメソッド (1行 = 1つ) */
    val calls: String = "",
)

/** テストケースのテストの方法 */
enum class TestMethod {
    /** 提出されたクラスの main を実行する */
    Main,

    /** 自分で書いたテストコードを実行する */
    TestCode,

    /** main から直接呼んでいるメソッドを確認する (実行しない) */
    Calls,
}

enum class DraftField {
    CompileTimeout, RunningTimeout, OutputFileName, SubmissionDirPattern,
    TaskName, Words, TestCaseName, Expect, TestCode, Calls,
}

data class DraftIssue(
    val field: DraftField,
    val message: String,
    val taskKey: Long? = null,
    val testCaseKey: Long? = null,
)

// ---------------------------------------------------------------- 変換

fun Config.toDraft(newKey: () -> Long): ConfigDraft = ConfigDraft(
    compileTimeout = compileTimeout.toString(),
    runningTimeout = runningTimeout.toString(),
    outputFileName = outputFileName,
    submissionDirPattern = submissionDirPattern,
    tasks = tasks.map { (name, task) ->
        TaskDraft(
            key = newKey(),
            name = name.name,
            words = task.word.joinToString("\n"),
            excludeWords = task.excludeWord.joinToString("\n"),
            manual = task.manual,
            allow = task.allow.toSet(),
            allowCalls = task.allowCalls.joinToString("\n"),
            testCases = task.testcase.map { tc ->
                TestCaseDraft(
                    key = newKey(),
                    name = tc.name,
                    arg = tc.arg,
                    input = tc.input.joinToString("\n"),
                    expect = tc.expect.joinToString("\n"),
                    method = when {
                        tc.calls.isNotEmpty() -> TestMethod.Calls
                        tc.testCode != null -> TestMethod.TestCode
                        else -> TestMethod.Main
                    },
                    calls = tc.calls.joinToString("\n"),
                    testCode = tc.testCode.orEmpty(),
                    check = tc.check,
                )
            },
        )
    },
)

/** [validate] でエラーが無いことを確認してから呼ぶこと */
fun ConfigDraft.toConfig(): Config = Config(
    compileTimeout = compileTimeout.trim().toLong(),
    runningTimeout = runningTimeout.trim().toLong(),
    outputFileName = outputFileName.trim(),
    submissionDirPattern = submissionDirPattern,
    tasks = tasks.associateTo(linkedMapOf()) { task ->
        TaskName(task.name.trim()) to Task(
            word = task.words.nonBlankLines(),
            excludeWord = task.excludeWords.nonBlankLines(),
            manual = task.manual,
            // 並びはいつも同じにする (YAML の差分が出ないように)
            allow = ExecutionPermission.entries.filter { it in task.allow },
            allowCalls = task.allowCalls.nonBlankLines().map { it.trim() },
            testcase = task.testCases.map { tc ->
                TestCase(
                    name = tc.name.trim(),
                    arg = tc.arg,
                    input = tc.input.inputLines(),
                    expect = tc.expect.nonBlankLines(),
                    testCode = if (tc.method == TestMethod.TestCode) tc.testCode.replace("\r", "").trimEnd() + "\n" else null,
                    calls = if (tc.method == TestMethod.Calls) tc.calls.nonBlankLines().map { it.trim() } else emptyList(),
                    check = tc.check.replace("\r", "").trimEnd(),
                )
            },
        )
    },
)

private fun String.nonBlankLines(): List<String> =
    replace("\r", "").split("\n").filter { it.isNotBlank() }

private fun String.inputLines(): List<String> {
    val text = replace("\r", "")
    return if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")
}

// ---------------------------------------------------------------- 検証

private val invalidFileNameChars = setOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')

fun ConfigDraft.validate(): List<DraftIssue> = buildList {
    if (compileTimeout.trim().toLongOrNull() == null) {
        add(DraftIssue(DraftField.CompileTimeout, "整数(ミリ秒)を入力してください"))
    }
    if (runningTimeout.trim().toLongOrNull() == null) {
        add(DraftIssue(DraftField.RunningTimeout, "整数(ミリ秒)を入力してください。負の値で無制限"))
    }
    when {
        outputFileName.isBlank() -> add(
            DraftIssue(
                DraftField.OutputFileName,
                "ファイル名を入力してください"
            )
        )

        outputFileName.any { it in invalidFileNameChars } ->
            add(DraftIssue(DraftField.OutputFileName, "ファイル名に使えない文字が含まれています"))
    }
    regexError(submissionDirPattern)?.let { add(DraftIssue(DraftField.SubmissionDirPattern, it)) }

    val taskNameCounts = tasks.groupingBy { it.name.trim() }.eachCount()
    for (task in tasks) {
        val name = task.name.trim()
        when {
            name.isEmpty() -> add(
                DraftIssue(
                    DraftField.TaskName,
                    "課題名を入力してください",
                    task.key
                )
            )

            taskNameCounts.getValue(name) > 1 -> add(
                DraftIssue(
                    DraftField.TaskName,
                    "課題名が重複しています",
                    task.key
                )
            )
        }
        if (task.words.nonBlankLines().isEmpty()) {
            add(DraftIssue(DraftField.Words, "検索ワードを1つ以上入力してください", task.key))
        }
        val caseNameCounts = task.testCases.groupingBy { it.name.trim() }.eachCount()
        for (tc in task.testCases) {
            val caseName = tc.name.trim()
            when {
                caseName.isEmpty() ->
                    add(
                        DraftIssue(
                            DraftField.TestCaseName,
                            "テストケース名を入力してください",
                            task.key,
                            tc.key
                        )
                    )

                caseNameCounts.getValue(caseName) > 1 ->
                    add(
                        DraftIssue(
                            DraftField.TestCaseName,
                            "テストケース名が課題内で重複しています",
                            task.key,
                            tc.key
                        )
                    )
            }
            tc.expect.nonBlankLines().forEachIndexed { i, line ->
                regexError(line)?.let {
                    add(DraftIssue(DraftField.Expect, "${i + 1}行目: $it", task.key, tc.key))
                }
            }
            if (tc.method == TestMethod.TestCode && tc.testCode.isBlank()) {
                add(DraftIssue(DraftField.TestCode, "テストコードを入力してください", task.key, tc.key))
            }
            if (tc.method == TestMethod.Calls && tc.calls.nonBlankLines().isEmpty()) {
                add(DraftIssue(DraftField.Calls, "確認するメソッドを1行に1つ入力してください (例: Arrays.sort)", task.key, tc.key))
            }
        }
    }
}

fun regexError(pattern: String): String? = try {
    Regex(pattern)
    null
} catch (e: Exception) {
    "正規表現が不正です: ${e.message?.lineSequence()?.firstOrNull().orEmpty()}"
}

/** 出力例が想定出力にマッチするか。正規表現が不正なら null */
fun TestCaseDraft.sampleMatches(): Boolean? {
    val patterns = expect.nonBlankLines()
    if (patterns.any { regexError(it) != null }) return null
    val testCase = TestCase(name = name, expect = patterns)
    return testCase.matches(sampleOutput)
}
