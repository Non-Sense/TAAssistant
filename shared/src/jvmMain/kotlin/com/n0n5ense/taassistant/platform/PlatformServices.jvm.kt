package com.n0n5ense.taassistant.platform

import com.n0n5ense.taassistant.core.grading.GradingToolkit
import com.n0n5ense.taassistant.core.grading.JvmGradingToolkit
import java.awt.Desktop
import java.io.File
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread

actual fun createPlatformServices(): PlatformServices = JvmPlatformServices()

private class JvmPlatformServices : PlatformServices {
    init {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    }

    override val toolkit: GradingToolkit = JvmGradingToolkit()
    override val settings: SettingsStore = PreferencesSettingsStore()
    override val availableProcessors: Int = Runtime.getRuntime().availableProcessors()

    override fun pickDirectory(title: String, initial: String?): String? =
        choose(title, initial, JFileChooser.DIRECTORIES_ONLY, filter = null)

    override fun pickFile(title: String, initial: String?, extensions: List<String>): String? =
        choose(
            title,
            initial,
            JFileChooser.FILES_ONLY,
            filter = extensions.takeIf { it.isNotEmpty() }
                ?.let { FileNameExtensionFilter(it.joinToString(", ") { ext -> "*.$ext" }, *it.toTypedArray()) },
        )

    override fun pickSaveFile(title: String, initial: String?, extensions: List<String>): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.FILES_ONLY
            extensions.takeIf { it.isNotEmpty() }?.let {
                fileFilter = FileNameExtensionFilter(it.joinToString(", ") { ext -> "*.$ext" }, *it.toTypedArray())
            }
            initial?.takeIf { it.isNotBlank() }?.let(::File)?.let { file ->
                if (file.isDirectory) currentDirectory = file else selectedFile = file
            }
        }
        if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
        var file = chooser.selectedFile
        if (extensions.isNotEmpty() && extensions.none { file.name.endsWith(".$it", ignoreCase = true) }) {
            file = File(file.path + "." + extensions.first())
        }
        if (file.exists()) {
            val answer = JOptionPane.showConfirmDialog(
                null,
                "${file.name} は既に存在します。上書きしますか?",
                title,
                JOptionPane.YES_NO_OPTION,
            )
            if (answer != JOptionPane.YES_OPTION) return null
        }
        return file.absolutePath
    }

    override fun parentDirectory(path: String): String? = File(path).absoluteFile.parentFile?.path

    override fun relativeToFile(baseFile: String, path: String): String {
        if (path.isBlank()) return path
        val base = File(baseFile).absoluteFile.parentFile?.toPath()?.normalize() ?: return path
        val target = runCatching { File(path).absoluteFile.toPath().normalize() }.getOrNull() ?: return path
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

    private fun choose(title: String, initial: String?, mode: Int, filter: FileNameExtensionFilter?): String? {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = mode
            filter?.let { fileFilter = it }
            initial?.takeIf { it.isNotBlank() }?.let(::File)?.let { file ->
                when {
                    file.isDirectory && mode == JFileChooser.DIRECTORIES_ONLY -> {
                        currentDirectory = file.parentFile ?: file
                        selectedFile = file
                    }
                    file.isDirectory -> currentDirectory = file
                    file.isFile -> selectedFile = file
                    file.parentFile?.isDirectory == true -> currentDirectory = file.parentFile
                }
            }
        }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.absolutePath else null
    }
}

private class PreferencesSettingsStore : SettingsStore {
    private val prefs = Preferences.userRoot().node("com/n0n5ense/taassistant")
    override fun get(key: String): String? = prefs.get(key, null)
    override fun put(key: String, value: String) = prefs.put(key, value)
    override fun remove(key: String) = prefs.remove(key)
}
