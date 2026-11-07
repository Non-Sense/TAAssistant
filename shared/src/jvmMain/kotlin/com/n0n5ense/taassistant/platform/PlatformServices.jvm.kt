package com.n0n5ense.taassistant.platform

import com.n0n5ense.taassistant.core.grading.GradingToolkit
import com.n0n5ense.taassistant.core.grading.JvmGradingToolkit
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogParent
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openDirectoryPicker
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import java.awt.Desktop
import java.awt.Window
import java.io.File
import java.util.prefs.Preferences
import kotlin.concurrent.thread

actual fun createPlatformServices(): PlatformServices = JvmPlatformServices()

internal class JvmPlatformServices : PlatformServices {
    init {
        runCatching { FileKit.init(appId = "TAAssistant") }
    }

    override val toolkit: GradingToolkit = JvmGradingToolkit()
    override val settings: SettingsStore = PreferencesSettingsStore()
    override val availableProcessors: Int = Runtime.getRuntime().availableProcessors()

    /** ダイアログをモーダルにする親ウィンドウ ([attachWindow] で設定する) */
    var parentWindow: Window? = null

    private fun dialogSettings(title: String) = FileKitDialogSettings(
        title = title,
        parent = parentWindow?.let { FileKitDialogParent.awt(it) },
    )

    /** 最初に表示するフォルダ (ファイルならその親、存在しなければ存在する親) */
    private fun initialDirectory(initial: String?): PlatformFile? {
        var dir = initial?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile } ?: return null
        if (dir.isFile) dir = dir.parentFile ?: return null
        while (!dir.isDirectory) dir = dir.parentFile ?: return null
        return PlatformFile(dir)
    }

    override suspend fun pickDirectory(title: String, initial: String?): String? =
        FileKit.openDirectoryPicker(
            directory = initialDirectory(initial),
            dialogSettings = dialogSettings(title)
        )
            ?.file?.absolutePath

    override suspend fun pickFile(
        title: String,
        initial: String?,
        extensions: List<String>
    ): String? =
        FileKit.openFilePicker(
            type = if (extensions.isEmpty()) FileKitType.File() else FileKitType.File(extensions.toSet()),
            directory = initialDirectory(initial),
            dialogSettings = dialogSettings(title),
        )?.file?.absolutePath

    override suspend fun pickSaveFile(
        title: String,
        initial: String?,
        extensions: List<String>,
        suggestedName: String?
    ): String? {
        val initialFile = initial?.takeIf { it.isNotBlank() }?.let(::File)
        val name = suggestedName
            ?: initialFile?.takeIf { !it.isDirectory && it.name.isNotEmpty() }?.nameWithoutExtension
            ?: "untitled"
        val picked = FileKit.openFileSaver(
            suggestedName = name,
            extension = extensions.firstOrNull(),
            directory = initialDirectory(initial),
            dialogSettings = dialogSettings(title),
        ) ?: return null
        var file = picked.file.absoluteFile
        if (extensions.isNotEmpty() && extensions.none {
                file.name.endsWith(
                    ".$it",
                    ignoreCase = true
                )
            }) {
            file = File(file.path + "." + extensions.first())
        }
        return file.absolutePath
    }

    override fun parentDirectory(path: String): String? = File(path).absoluteFile.parentFile?.path

    override fun relativeToFile(baseFile: String, path: String): String {
        if (path.isBlank()) return path
        val base = File(baseFile).absoluteFile.parentFile?.toPath()?.normalize() ?: return path
        val target =
            runCatching { File(path).absoluteFile.toPath().normalize() }.getOrNull() ?: return path
        if (!target.startsWith(base)) return path
        return base.relativize(target).toString().replace(File.separatorChar, '/').ifEmpty { "." }
    }

    override fun resolveAgainstFile(baseFile: String, path: String): String {
        if (path.isBlank()) return path
        val file = File(path)
        if (file.isAbsolute) return file.path
        val base = File(baseFile).absoluteFile.parentFile ?: return path
        return base.toPath().resolve(path).normalize().toString()
    }

    override fun fileExists(path: String): Boolean = File(path).isFile

    override fun openPath(path: String) {
        thread(isDaemon = true) {
            runCatching { Desktop.getDesktop().open(File(path)) }
        }
    }
}

private class PreferencesSettingsStore : SettingsStore {
    private val prefs = Preferences.userRoot().node("com/n0n5ense/taassistant")
    override fun get(key: String): String? = prefs.get(key, null)
    override fun put(key: String, value: String) = prefs.put(key, value)
    override fun remove(key: String) = prefs.remove(key)
}

/** ファイル選択などのダイアログを、[window] に対するモーダルにする (desktopApp から呼ぶ) */
fun PlatformServices.attachWindow(window: Window) {
    (this as? JvmPlatformServices)?.parentWindow = window
}
