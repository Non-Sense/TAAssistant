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

    /** OS 標準のダイアログでフォルダを選ぶ。キャンセルなら null */
    suspend fun pickDirectory(title: String, initial: String?): String?

    /** OS 標準のダイアログでファイルを選ぶ。キャンセルなら null */
    suspend fun pickFile(title: String, initial: String?, extensions: List<String>): String?

    /**
     * OS 標準のダイアログで保存先を選ぶ。拡張子が無ければ [extensions] の先頭を付ける。既存ファイルの上書き確認は OS のダイアログが行う。
     * @param initial 最初に表示するファイルかフォルダ
     * @param suggestedName 最初に入力しておくファイル名 (拡張子なし)。null なら [initial] のファイル名
     */
    suspend fun pickSaveFile(
        title: String,
        initial: String?,
        extensions: List<String>,
        suggestedName: String? = null
    ): String?

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
