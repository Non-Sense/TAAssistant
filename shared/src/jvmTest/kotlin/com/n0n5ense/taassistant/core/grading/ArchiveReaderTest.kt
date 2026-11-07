package com.n0n5ense.taassistant.core.grading

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArchiveReaderTest {
    private val dir: File = Files.createTempDirectory("taassistant-archive").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun detectsFormat() {
        assertEquals(ArchiveFormat.Zip, ArchiveFormat.of("提出.ZIP"))
        assertEquals(ArchiveFormat.Jar, ArchiveFormat.of("app.jar"))
        assertEquals(ArchiveFormat.TarGz, ArchiveFormat.of("src.tar.gz"))
        assertEquals(ArchiveFormat.TarGz, ArchiveFormat.of("src.tgz"))
        assertEquals(ArchiveFormat.Tar, ArchiveFormat.of("src.tar"))
        assertNull(ArchiveFormat.of("src.7z"))
        assertNull(ArchiveFormat.of("Main.java"))
    }

    @Test
    fun listsZipWithShiftJisNames() {
        val file = File(dir, "submit.zip")
        // Windows の「送る → 圧縮」のように、UTF-8 フラグなしで Shift_JIS の名前を書く
        ZipOutputStream(file.outputStream(), Charset.forName("Shift_JIS")).use { zip ->
            fun put(name: String, bytes: ByteArray = ByteArray(0)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            put("課題1/")
            put("課題1/Main.java", "class Main {}".toByteArray())
            put("課題1/Main.class", ByteArray(10))
            put("課題2/sub/Hello.java", "class Hello {}".toByteArray()) // 途中のフォルダの項目なし
            put("__MACOSX/._Main.java", ByteArray(4))
        }
        val listing = listArchive(file, ArchiveFormat.Zip)
        assertFalse(listing.truncated)
        assertEquals(
            listOf(
                "課題1",
                "課題1/Main.java",
                "課題1/Main.class",
                "課題2/sub/Hello.java",
                "__MACOSX/._Main.java"
            ),
            listing.entries.map { it.path },
        )
        assertEquals(
            listOf(true, false, false, false, false),
            listing.entries.map { it.isDirectory })
        assertEquals(13, listing.entries[1].size)
        assertEquals(4, listing.fileCount)
        // 採点時の展開で除外されるもの
        assertEquals(
            listOf("課題1/Main.class", "__MACOSX/._Main.java"),
            listing.entries.filter { it.isExcludedOnExtract() }.map { it.path },
        )
    }

    @Test
    fun listsZipWithUtf8Names() {
        val file = File(dir, "utf8.zip")
        ZipOutputStream(file.outputStream(), Charsets.UTF_8).use { zip ->
            zip.putNextEntry(ZipEntry("課題/Main.java"))
            zip.closeEntry()
        }
        assertEquals(
            listOf("課題/Main.java"),
            listArchive(file, ArchiveFormat.Zip).entries.map { it.path })
    }

    /** テスト用の最小限の tar を作る */
    private fun tar(
        vararg entries: Pair<String, ByteArray?>,
        longNames: Boolean = false,
        pax: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun header(name: String, size: Int, type: Char, prefix: String = "") {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0, 0, minOf(100, name.toByteArray().size))
            "%011o".format(size).toByteArray().copyInto(h, 124)
            h[156] = type.code.toByte()
            "ustar".toByteArray().copyInto(h, 257)
            prefix.toByteArray().copyInto(h, 345)
            out.write(h)
        }

        fun data(bytes: ByteArray) {
            out.write(bytes)
            out.write(ByteArray((512 - bytes.size % 512) % 512))
        }
        entries.forEach { (name, bytes) ->
            val isDir = bytes == null
            val content = bytes ?: ByteArray(0)
            when {
                longNames && name.length > 20 -> {
                    val longName = name.toByteArray()
                    header("././@LongLink", longName.size, 'L')
                    data(longName)
                    header(name.take(20), content.size, if (isDir) '5' else '0')
                }

                pax && name.length > 20 -> {
                    val record = "path=$name\n"
                    val body = "${record.toByteArray().size + 3} $record".let { r ->
                        // 長さの桁数が変わる場合を考えずに、テストでは十分な固定長にする
                        "${r.toByteArray().size} $record"
                    }.toByteArray()
                    header("PaxHeader", body.size, 'x')
                    data(body)
                    header(name.take(20), content.size, if (isDir) '5' else '0')
                }

                else -> {
                    val slash = name.lastIndexOf('/', name.length - 2)
                    if (name.length > 40 && slash > 0) {
                        header(
                            name.substring(slash + 1),
                            content.size,
                            if (isDir) '5' else '0',
                            prefix = name.substring(0, slash)
                        )
                    } else {
                        header(name, content.size, if (isDir) '5' else '0')
                    }
                }
            }
            if (!isDir) data(content)
        }
        out.write(ByteArray(1024)) // 終端
        return out.toByteArray()
    }

    @Test
    fun listsTarAndTarGz() {
        val bytes = tar(
            "./src/" to null,
            "./src/Main.java" to "class Main {}".toByteArray(),
            "very/long/directory/name/for/the/ustar/prefix/Test.java" to ByteArray(600),
        )
        val tarFile = File(dir, "a.tar").apply { writeBytes(bytes) }
        val listing = listArchive(tarFile, ArchiveFormat.Tar)
        assertEquals(
            listOf(
                "src",
                "src/Main.java",
                "very/long/directory/name/for/the/ustar/prefix/Test.java"
            ),
            listing.entries.map { it.path },
        )
        assertEquals(listOf(0L, 13L, 600L), listing.entries.map { it.size })
        assertTrue(listing.entries.first().isDirectory)

        val gz = File(dir, "a.tar.gz")
        GZIPOutputStream(gz.outputStream()).use { it.write(bytes) }
        assertEquals(listing.entries, listArchive(gz, ArchiveFormat.TarGz).entries)
    }

    @Test
    fun readsGnuLongNamesAndPaxPaths() {
        val name = "課題/とても長いファイル名のディレクトリ/Main.java"
        val gnu = File(dir, "gnu.tar").apply {
            writeBytes(
                tar(
                    name to "x".toByteArray(),
                    longNames = true
                )
            )
        }
        assertEquals(listOf(name), listArchive(gnu, ArchiveFormat.Tar).entries.map { it.path })
        val pax =
            File(dir, "pax.tar").apply { writeBytes(tar(name to "x".toByteArray(), pax = true)) }
        assertEquals(listOf(name), listArchive(pax, ArchiveFormat.Tar).entries.map { it.path })
    }
}
