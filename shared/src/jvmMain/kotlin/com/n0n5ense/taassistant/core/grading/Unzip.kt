// 参考: https://qiita.com/jim/items/2c0b0a0acacd78f49b49 (CC0)
package com.n0n5ense.taassistant.core.grading

import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/** 最大ファイル数 */
private const val UNZIP_MAX_ENTRIES = 1024 * 5

/** 単体の最大ファイルサイズ */
private const val UNZIP_MAX_FILE_SIZE = 1024L * 1024L * 1024L // 1GiB

/** 全ファイルの合計最大ファイルサイズ */
private const val UNZIP_MAX_TOTAL_SIZE = 1024L * 1024L * 1024L * 5L // 5GiB

/**
 * zip を安全に展開する。
 * `__MACOSX` と `.class` ファイルは展開しない。
 * UTF-8 フラグのないエントリ名は [zipFileCharset] (既定は Shift_JIS) で解釈する。
 */
internal fun unzipFile(
    zipFile: Path,
    destDir: Path,
    zipFileCharset: Charset = Charset.forName("Shift_JIS"),
) {
    val root = destDir.toAbsolutePath().normalize()
    ZipInputStream(Files.newInputStream(zipFile), zipFileCharset).use { zip ->
        var entries = 0
        var totalRead = 0L
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val entry = zip.nextEntry ?: break
            val entryPath = Paths.get(entry.name).normalize()
            if (entryPath.startsWith("__MACOSX")) continue
            if (!entry.isDirectory && entry.name.endsWith(".class")) continue
            check(++entries <= UNZIP_MAX_ENTRIES) { "Too many files to unzip." }

            val dst = root.resolve(entryPath).normalize()
            check(dst.startsWith(root)) { "File is outside extraction target directory: ${entry.name}" }

            if (entry.isDirectory) {
                Files.createDirectories(dst)
                continue
            }
            Files.createDirectories(dst.parent)
            var fileRead = 0L
            Files.newOutputStream(dst).buffered().use { out ->
                while (true) {
                    val n = zip.read(buffer)
                    if (n < 0) break
                    totalRead += n
                    fileRead += n
                    check(totalRead <= UNZIP_MAX_TOTAL_SIZE) { "Total file size being unzipped is too big." }
                    check(fileRead <= UNZIP_MAX_FILE_SIZE) { "File being unzipped is too big." }
                    out.write(buffer, 0, n)
                }
            }
            zip.closeEntry()
        }
    }
}
