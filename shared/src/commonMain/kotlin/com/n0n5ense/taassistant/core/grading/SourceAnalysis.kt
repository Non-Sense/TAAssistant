package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.Config
import com.n0n5ense.taassistant.core.config.TaskName

/**
 * ソースコードの内容から、どの課題の提出かを判定する。
 * `word` のどれかを含み、`excludeWord` をどれも含まない課題をすべて返す。
 */
fun detectTasks(config: Config, sourceText: String): List<TaskName> =
    config.tasks
        .filter { (_, task) ->
            task.word.any(sourceText::contains) && task.excludeWord.none(sourceText::contains)
        }
        .keys
        .toList()

private val blockCommentRegex = Regex("""/\*[\s\S]*?\*/""")
private val lineCommentRegex = Regex("""//[^\r\n]*""")
private val packageRegex = Regex("""^\uFEFF?[ \t]*package\s+([^\s;]+)\s*;""", RegexOption.MULTILINE)

/** `package xxx;` 宣言を探す (コメントアウトされたものは無視) */
fun parsePackageName(sourceText: String): String? {
    val stripped = sourceText.replace(blockCommentRegex, "").replace(lineCommentRegex, "")
    return packageRegex.find(stripped)?.groupValues?.get(1)
}

/**
 * コマンドライン引数の文字列を分割する。
 * 空白区切りで、ダブルクォートで囲んだ部分は空白を含めて1つの引数として扱う。
 */
fun splitArguments(line: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var inQuote = false
    var inToken = false
    for (c in line) {
        when {
            inQuote -> if (c == '"') inQuote = false else current.append(c)
            c == '"' -> {
                inQuote = true
                inToken = true
            }
            c.isWhitespace() -> if (inToken) {
                args += current.toString()
                current.clear()
                inToken = false
            }
            else -> {
                current.append(c)
                inToken = true
            }
        }
    }
    if (inToken) args += current.toString()
    return args
}

/** 表示用にコマンドを1行の文字列にする */
fun List<String>.toCommandLine(): String =
    joinToString(" ") { if (it.isEmpty() || it.any(Char::isWhitespace)) "\"$it\"" else it }
