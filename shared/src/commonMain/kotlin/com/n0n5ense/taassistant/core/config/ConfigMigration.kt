package com.n0n5ense.taassistant.core.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.n0n5ense.taassistant.core.config.ConfigMigrator.steps

/** 古いバージョンのコンフィグを現在の形式に変換した結果 */
data class ConfigMigration(
    val fromVersion: Int,
    val config: Config,
    /** 変換後の YAML */
    val yaml: String,
    /** 変換内容の説明 (ユーザーに表示する) */
    val notes: List<String>,
)

/** 古いバージョンのため、そのままでは読み込めないコンフィグ */
class LegacyConfigException(val version: Int) : IllegalArgumentException(
    "古い形式のコンフィグです (version: $version、現在は ${Config.CURRENT_VERSION})。" +
            "「コンフィグ編集」で開くと新しい形式に変換できます。",
)

/**
 * 古いバージョンのコンフィグを、1バージョンずつ順に変換する。
 * 形式を変えたときは [Config.CURRENT_VERSION] を上げて、[steps] に変換手順を1つ追加する。
 */
object ConfigMigrator {
    private const val VERSION_KEY = "version"

    /** version 項目が無いコンフィグはバージョン 1 とみなす */
    private const val UNVERSIONED = 1

    /** バージョン [from] → [from] + 1 の変換 */
    private class Step(
        val from: Int,
        val apply: (root: YamlMap, notes: MutableList<String>) -> YamlMap
    )

    private val steps = listOf(
        Step(from = 1) { root, notes ->
            val disablePackage =
                root.entries.entries.firstOrNull { it.key.content == "disablePackage" }
            if (disablePackage != null) {
                val enabled =
                    (disablePackage.value as? YamlScalar)?.content?.toBooleanStrictOrNull() == true
                notes += if (enabled) {
                    "disablePackage: true を削除しました。workspace の初期化は、設定パネルの「実行前に workspace を初期化する」で指定してください。"
                } else {
                    "disablePackage: false を削除しました (効果のない項目でした)。"
                }
            }
            root.without("disablePackage")
        },
    )

    init {
        check(steps.map { it.from } == (UNVERSIONED until Config.CURRENT_VERSION).toList()) {
            "ConfigMigrator.steps が Config.CURRENT_VERSION と対応していません"
        }
    }

    /**
     * YAML に書かれたバージョン。version 項目が無ければ 1。
     * YAML として読めなければ null。version が整数でなければ例外。
     */
    fun detectVersion(yamlText: String): Int? {
        val root = parseRoot(yamlText) ?: return null
        val node = root.entries.entries.firstOrNull { it.key.content == VERSION_KEY }?.value
            ?: return UNVERSIONED
        return (node as? YamlScalar)?.content?.toIntOrNull()
            ?: throw IllegalArgumentException("version は整数で指定してください")
    }

    /**
     * 古いバージョンのコンフィグを現在の形式に変換する。
     * すでに現在のバージョンなら null。変換後の内容に誤りがある場合は例外を投げる。
     */
    fun migrate(yamlText: String): ConfigMigration? {
        val fromVersion = detectVersion(yamlText) ?: return null
        if (fromVersion >= Config.CURRENT_VERSION) return null
        var root = parseRoot(yamlText)!!.without(VERSION_KEY)

        val notes = mutableListOf<String>()
        for (step in steps.filter { it.from >= fromVersion }) {
            root = step.apply(root, notes)
        }
        notes += "version: ${Config.CURRENT_VERSION} を追加しました。"

        // version は既定値 (現在のバージョン) で補われる
        val config = Yaml.default.decodeFromYamlNode(Config.serializer(), root)
        return ConfigMigration(fromVersion, config, ConfigParser.encode(config), notes)
    }

    private fun parseRoot(yamlText: String): YamlMap? =
        runCatching {
            Yaml.default.parseToYamlNode(yamlText.removePrefix(Char(0xFEFF).toString()))
        }.getOrNull() as? YamlMap

    private fun YamlMap.without(key: String): YamlMap =
        YamlMap(entries.filterKeys { it.content != key }, path)
}
