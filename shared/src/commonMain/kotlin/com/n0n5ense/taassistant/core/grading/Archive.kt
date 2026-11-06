package com.n0n5ense.taassistant.core.grading

/** 中身を一覧できるアーカイブの形式 */
enum class ArchiveFormat(val label: String, val extractedOnGrading: Boolean) {
    Zip("zip", extractedOnGrading = true),
    Jar("jar", extractedOnGrading = false),
    Tar("tar", extractedOnGrading = false),
    TarGz("tar.gz", extractedOnGrading = false),
    ;

    companion object {
        /** ファイル名から形式を判定する。一覧できない形式なら null */
        fun of(fileName: String): ArchiveFormat? {
            val name = fileName.lowercase()
            return when {
                name.endsWith(".zip") -> Zip
                name.endsWith(".jar") -> Jar
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> TarGz
                name.endsWith(".tar") -> Tar
                else -> null
            }
        }
    }
}

/** アーカイブの中の1項目 */
data class ArchiveEntry(
    /** アーカイブ内のパス (区切りは /、末尾の / は付けない) */
    val path: String,
    val size: Long,
    val isDirectory: Boolean,
) {
    val name: String get() = path.substringAfterLast('/')
}

data class ArchiveListing(
    val format: ArchiveFormat,
    val entries: List<ArchiveEntry>,
    /** 項目が多すぎて途中までしか読んでいない */
    val truncated: Boolean,
) {
    val fileCount: Int get() = entries.count { !it.isDirectory }
    val totalSize: Long get() = entries.filter { !it.isDirectory }.sumOf { it.size }
}

/** 採点時の展開で除外される項目か (zip の展開と同じ規則: `__MACOSX` と `.class`) */
fun ArchiveEntry.isExcludedOnExtract(): Boolean =
    path == "__MACOSX" || path.startsWith("__MACOSX/") || (!isDirectory && path.endsWith(".class"))
