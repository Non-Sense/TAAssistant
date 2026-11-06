package com.n0n5ense.taassistant.platform

import com.n0n5ense.taassistant.core.grading.GradingToolkit

interface SettingsStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** UI から使うプラットフォーム依存の機能 */
interface PlatformServices {
    val toolkit: GradingToolkit
    val settings: SettingsStore
    val availableProcessors: Int

    fun pickDirectory(title: String, initial: String?): String?
    fun pickFile(title: String, initial: String?, extensions: List<String>): String?

    /** 保存先を選ぶ。拡張子が無ければ [extensions] の先頭を付ける。既存ファイルなら上書き確認する */
    fun pickSaveFile(title: String, initial: String?, extensions: List<String>): String?
    fun parentDirectory(path: String): String?

    /**
     * [path] を、[baseFile] のあるディレクトリからの相対パスにする (区切りは /)。
     * 空欄やそのディレクトリの外のパスはそのまま返す。
     */
    fun relativeToFile(baseFile: String, path: String): String

    /** 相対パスなら、[baseFile] のあるディレクトリを基準にした絶対パスにする */
    fun resolveAgainstFile(baseFile: String, path: String): String

    fun fileExists(path: String): Boolean

    /** ファイル / ディレクトリを OS の既定アプリで開く */
    fun openPath(path: String)
}

expect fun createPlatformServices(): PlatformServices
