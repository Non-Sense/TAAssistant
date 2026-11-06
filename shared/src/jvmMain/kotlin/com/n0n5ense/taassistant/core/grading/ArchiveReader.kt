package com.n0n5ense.taassistant.core.grading

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

/** 一覧する項目数の上限 */
private const val MAX_LISTED_ENTRIES = 10_000

/** アーカイブを展開せずに、中の項目を一覧する */
internal fun listArchive(file: File, format: ArchiveFormat): ArchiveListing = when (format) {
    ArchiveFormat.Zip, ArchiveFormat.Jar -> listZip(file, format)
    ArchiveFormat.Tar -> file.inputStream().buffered().use { listTar(it, format) }
    ArchiveFormat.TarGz -> GZIPInputStream(file.inputStream().buffered()).use { listTar(it, format) }
}

/** 中央ディレクトリを読む。UTF-8 フラグのないエントリ名は Shift_JIS で解釈する (展開と同じ) */
private fun listZip(file: File, format: ArchiveFormat): ArchiveListing =
    ZipFile(file, Charset.forName("Shift_JIS")).use { zip ->
        val entries = mutableListOf<ArchiveEntry>()
        var truncated = false
        for (entry in zip.entries()) {
            if (entries.size >= MAX_LISTED_ENTRIES) {
                truncated = true
                break
            }
            entries += ArchiveEntry(entry.name.trimEnd('/'), entry.size.coerceAtLeast(0), entry.isDirectory)
        }
        ArchiveListing(format, entries, truncated)
    }

/**
 * tar のヘッダを順に読む (ustar・GNU の長いファイル名・pax のパスに対応)。
 * 中身は読み飛ばす。
 */
internal fun listTar(input: InputStream, format: ArchiveFormat): ArchiveListing {
    val entries = mutableListOf<ArchiveEntry>()
    val header = ByteArray(512)
    var longName: String? = null
    var truncated = false
    while (true) {
        if (!input.readBlock(header)) break
        if (header.all { it == 0.toByte() }) break // 終端
        val size = header.octal(124, 12)
        val type = header[156].toInt().toChar()
        val isUstar = header.string(257, 5) == "ustar"
        val name = longName ?: run {
            val base = header.string(0, 100)
            val prefix = if (isUstar) header.string(345, 155) else ""
            if (prefix.isNotEmpty()) "$prefix/$base" else base
        }
        longName = null
        val dataBlocks = (size + 511) / 512 * 512
        when (type) {
            // GNU の長いファイル名: 次の項目の名前
            'L' -> {
                longName = String(input.readExactly(size.toInt()), Charsets.UTF_8).trimEnd('\u0000')
                input.skipFully(dataBlocks - size)
                continue
            }
            // pax 拡張ヘッダ: path= があれば次の項目の名前
            'x' -> {
                val pax = String(input.readExactly(size.toInt()), Charsets.UTF_8)
                longName = pax.lineSequence().firstNotNullOfOrNull { line ->
                    line.substringAfter(' ', "").takeIf { it.startsWith("path=") }?.removePrefix("path=")
                }
                input.skipFully(dataBlocks - size)
                continue
            }
            'g' -> {
                input.skipFully(dataBlocks)
                continue
            }
        }
        if (entries.size >= MAX_LISTED_ENTRIES) {
            truncated = true
            break
        }
        val isDirectory = type == '5' || name.endsWith("/")
        val path = name.removePrefix("./").trimEnd('/')
        if (path.isNotEmpty()) entries += ArchiveEntry(path, if (isDirectory) 0 else size, isDirectory)
        input.skipFully(dataBlocks)
    }
    return ArchiveListing(format, entries, truncated)
}

private fun ByteArray.string(offset: Int, length: Int): String {
    var end = offset
    while (end < offset + length && this[end] != 0.toByte()) end++
    return String(this, offset, end - offset, Charsets.UTF_8)
}

private fun ByteArray.octal(offset: Int, length: Int): Long =
    string(offset, length).trim().takeIf { it.isNotEmpty() }?.toLongOrNull(8) ?: 0L

/** 512 バイト読む。ストリームの終わりなら false */
private fun InputStream.readBlock(buffer: ByteArray): Boolean {
    var read = 0
    while (read < buffer.size) {
        val n = read(buffer, read, buffer.size - read)
        if (n < 0) return read != 0 && error("tar のヘッダが途中で終わっています")
        read += n
    }
    return true
}

private fun InputStream.readExactly(size: Int): ByteArray {
    val buffer = ByteArray(size)
    var read = 0
    while (read < size) {
        val n = read(buffer, read, size - read)
        check(n >= 0) { "tar の項目が途中で終わっています" }
        read += n
    }
    return buffer
}

private fun InputStream.skipFully(bytes: Long) {
    var remaining = bytes
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            check(read() >= 0) { "tar の項目が途中で終わっています" }
            remaining--
        }
    }
}
