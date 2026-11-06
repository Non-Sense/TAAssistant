package com.n0n5ense.taassistant.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigMigrationTest {
    /** version 項目なし = バージョン 1 */
    private val v1 = """
        compileTimeout: 3000
        runningTimeout: 5000
        outputFileName: output
        disablePackage: true
        tasks:
          課題1:
            word: [課題1]
            testcase:
              - name: t1
                arg: "1 10"
                expect: ['^.*45.*']
    """.trimIndent()

    private val v2 = """
        version: 2
        compileTimeout: 3000
        tasks:
          課題1:
            word: [課題1]
    """.trimIndent()

    @Test
    fun detectsVersion() {
        assertEquals(1, ConfigMigrator.detectVersion(v1))
        assertEquals(2, ConfigMigrator.detectVersion(v2))
        assertNull(ConfigMigrator.detectVersion("tasks: [unclosed"))
        assertFailsWith<IllegalArgumentException> { ConfigMigrator.detectVersion("version: abc\ntasks: {}") }
    }

    @Test
    fun parseRejectsOldAndNewerVersions() {
        assertEquals(1, assertFailsWith<LegacyConfigException> { ConfigParser.parse(v1) }.version)
        // version なしで、旧項目も無いファイルも古い形式として扱う
        val unversioned = v2.lines().drop(1).joinToString("\n")
        assertFailsWith<LegacyConfigException> { ConfigParser.parse(unversioned) }

        val newer = assertFailsWith<IllegalArgumentException> { ConfigParser.parse(v2.replace("version: 2", "version: 99")) }
        assertFalse(newer is LegacyConfigException)
        assertTrue("新しいバージョン" in newer.message.orEmpty())

        assertEquals(Config.CURRENT_VERSION, ConfigParser.parse(v2).version)
    }

    @Test
    fun migratesVersion1() {
        val migration = assertNotNull(ConfigMigrator.migrate(v1))
        assertEquals(1, migration.fromVersion)
        assertTrue(migration.notes.any { "disablePackage: true" in it })
        assertTrue(migration.notes.any { "version: 2" in it })
        assertEquals(listOf(TaskName("課題1")), migration.config.tasks.keys.toList())
        // version が先頭に書かれ、廃止項目は消える
        assertTrue(migration.yaml.startsWith("version: 2\n"), migration.yaml)
        assertFalse("disablePackage" in migration.yaml)
        assertEquals(migration.config, ConfigParser.parse(migration.yaml))
    }

    @Test
    fun migratesUnversionedConfigWithoutLegacyKeys() {
        // version が無いだけで、廃止項目を含まないファイルも変換対象になる
        val unversioned = v2.lines().drop(1).joinToString("\n")
        assertEquals(1, ConfigMigrator.detectVersion(unversioned))

        val migration = assertNotNull(ConfigMigrator.migrate(unversioned))
        assertEquals(1, migration.fromVersion)
        assertEquals(listOf("version: 2 を追加しました。"), migration.notes)
        assertTrue(migration.yaml.startsWith("version: 2\n"), migration.yaml)
        assertEquals(ConfigParser.parse(v2), migration.config)
    }

    @Test
    fun currentVersionNeedsNoMigration() {
        assertNull(ConfigMigrator.migrate(v2))
    }

    @Test
    fun otherErrorsAreNotHidden() {
        // 旧項目以外の誤り (typo) は変換で握りつぶさない
        assertFailsWith<Exception> { ConfigMigrator.migrate(v1.replace("word:", "wrod:")) }
        // 現在のバージョンの単なる誤りは LegacyConfigException にしない
        val e = assertFailsWith<Exception> { ConfigParser.parse(v2.replace("word:", "wrod:")) }
        assertFalse(e is LegacyConfigException)
    }
}
