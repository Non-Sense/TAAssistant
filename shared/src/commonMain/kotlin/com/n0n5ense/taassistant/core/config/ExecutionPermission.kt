package com.n0n5ense.taassistant.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 実行する前の検査で、危ない可能性があるとみなす呼び出しの種類。
 * 課題の [Task.allow] に書いた種類は、確認なしで実行する (ファイルに書く課題など)
 */
@Serializable
enum class ExecutionPermission(val label: String, val description: String) {
    @SerialName("process")
    Process("外部プログラムの起動", "Runtime.exec・ProcessBuilder・Desktop.open など"),

    @SerialName("native")
    Native("ネイティブコードの読み込み", "System.load・System.loadLibrary など"),

    @SerialName("fileWrite")
    FileWrite("ファイルの書き込み・削除", "FileWriter・FileOutputStream・Files.write・File.delete など"),

    @SerialName("network")
    Network("ネットワーク", "Socket・ServerSocket・URL.openConnection・HttpClient など"),

    @SerialName("reflection")
    Reflection("リフレクション・動的なコードの実行", "Class.forName・Method.invoke・ClassLoader・ScriptEngine など"),
}
