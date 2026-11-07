package com.n0n5ense.taassistant.ui.student

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.n0n5ense.taassistant.core.grading.ArchiveFormat
import com.n0n5ense.taassistant.core.grading.ArchiveListing
import com.n0n5ense.taassistant.core.grading.FileEntry
import com.n0n5ense.taassistant.core.grading.SubmissionFiles
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.platform.PlatformServices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** ファイルの中身の表示 */
sealed interface FilePreview {
    data object Loading : FilePreview
    data class Text(val text: String) : FilePreview
    data class Archive(val listing: ArchiveListing) : FilePreview
    data class Unavailable(val reason: String) : FilePreview
}

data class StudentPageState(
    val loading: Boolean = true,
    val files: SubmissionFiles? = null,
    val error: String? = null,
    val selectedFile: FileEntry? = null,
    val preview: FilePreview? = null,
)

/**
 * 学生のページ。提出ファイルの一覧と、選んだファイルの中身を読み込む。
 * Navigation 3 の画面ごとに作られ、ページを閉じると破棄される。
 */
class StudentViewModel(
    private val services: PlatformServices,
    private val student: Student,
) : ViewModel() {
    private val _state = MutableStateFlow(StudentPageState())
    val state: StateFlow<StudentPageState> = _state.asStateFlow()
    private var previewJob: Job? = null

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val files = services.toolkit.listSubmissionFiles(student)
                _state.update { s ->
                    // 最初は展開したソースの先頭を表示する
                    val selected =
                        s.selectedFile ?: files.extracted.firstOrNull { it.extension == "java" }
                        ?: files.extracted.firstOrNull()
                    s.copy(loading = false, files = files, selectedFile = selected)
                }
                state.value.selectedFile?.let(::selectFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = "ファイルの一覧を取得できませんでした: ${e.message}"
                    )
                }
            }
        }
    }

    fun selectFile(file: FileEntry) {
        previewJob?.cancel()
        // アーカイブは中の階層を表示する
        ArchiveFormat.of(file.fileName)?.let { format ->
            _state.update { it.copy(selectedFile = file, preview = FilePreview.Loading) }
            previewJob = viewModelScope.launch {
                val preview = try {
                    FilePreview.Archive(services.toolkit.listArchive(file.path, format))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FilePreview.Unavailable("アーカイブの中身を読めませんでした: ${e.message}")
                }
                _state.update { if (it.selectedFile == file) it.copy(preview = preview) else it }
            }
            return
        }
        val reason = when {
            file.extension in BINARY_EXTENSIONS -> "このファイルはプレビューできません (.${file.extension})"
            file.size > MAX_PREVIEW_BYTES -> "ファイルが大きいためプレビューできません (${
                formatSize(
                    file.size
                )
            })"

            else -> null
        }
        if (reason != null) {
            _state.update {
                it.copy(
                    selectedFile = file,
                    preview = FilePreview.Unavailable(reason)
                )
            }
            return
        }
        _state.update { it.copy(selectedFile = file, preview = FilePreview.Loading) }
        previewJob = viewModelScope.launch {
            val preview = try {
                FilePreview.Text(services.toolkit.readTextFile(file.path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FilePreview.Unavailable("読み込めませんでした: ${e.message}")
            }
            _state.update { if (it.selectedFile == file) it.copy(preview = preview) else it }
        }
    }

    fun openPath(path: String) = services.openPath(path)

    fun parentDirectory(path: String): String? = services.parentDirectory(path)

    private companion object {
        const val MAX_PREVIEW_BYTES = 1L shl 20
        val BINARY_EXTENSIONS = setOf(
            "zip", "jar", "class", "7z", "rar", "gz", "tar",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "pdf",
            "doc", "docx", "xls", "xlsx", "ppt", "pptx", "exe", "dll",
        )
    }
}

/** ファイルサイズを読みやすくする */
internal fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${(bytes * 10 / 1024) / 10.0} KB"
    else -> "${(bytes * 10 / (1024 * 1024)) / 10.0} MB"
}
