package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.SubmissionDirName
import com.n0n5ense.taassistant.core.config.TestCase
import com.n0n5ense.taassistant.core.model.CompileOutcome
import com.n0n5ense.taassistant.core.model.Execution
import com.n0n5ense.taassistant.core.model.SourceEncoding
import com.n0n5ense.taassistant.core.model.SourceFile
import com.n0n5ense.taassistant.core.model.Student
import com.n0n5ense.taassistant.core.model.StudentId
import com.n0n5ense.taassistant.core.report.Csv
import com.n0n5ense.taassistant.core.report.ExcelReportWriter
import com.n0n5ense.taassistant.core.report.Report
import com.n0n5ense.taassistant.core.report.ReportFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.Charset
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

class JvmGradingToolkit : GradingToolkit {

    override suspend fun readTextFile(path: String): String = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.isFile) { "ファイルが見つかりません: $path" }
        decodeText(file.readBytes())
    }

    override suspend fun writeTextFile(path: String, text: String) = withContext(Dispatchers.IO) {
        val file = File(path).absoluteFile
        file.parentFile?.mkdirs()
        writeReplacing(file) { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    override suspend fun readBinaryFile(path: String): ByteArray = withContext(Dispatchers.IO) {
        val file = File(path)
        require(file.isFile) { "ファイルが見つかりません: $path" }
        file.readBytes()
    }

    override suspend fun writeBinaryFile(path: String, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val file = File(path).absoluteFile
            file.parentFile?.mkdirs()
            writeReplacing(file) { it.write(bytes) }
        }

    override suspend fun verifyToolchain(toolchain: JavaToolchain): String? {
        for (tool in listOf("javac", "java")) {
            val command = listOf(toolchain.executable(tool), "-version")
            val output = try {
                runProcess(command, null, 30_000)
            } catch (e: IOException) {
                return "$tool を起動できません (${e.message})。\n" +
                        "JDK がインストールされ PATH が通っているか、JDK の bin ディレクトリの指定を確認してください。"
            }
            if (output.timedOut || output.exitCode != 0) {
                return "${command.toCommandLine()} が失敗しました:\n${decodeText(output.stderr)}"
            }
        }
        return null
    }

    override suspend fun prepareWorkspace(rootDir: String, clean: Boolean): String =
        withContext(Dispatchers.IO) {
            val root = File(rootDir)
            require(root.isDirectory) { "提出物ディレクトリが存在しません: $rootDir" }
            val workspace = File(root, WORKSPACE_DIR)
            val marker = File(workspace, WORKSPACE_MARKER)
            if (clean && workspace.exists()) {
                val ownedByUs = marker.exists() ||
                        File(workspace, LEGACY_DATABASE).exists() ||
                        workspace.list().isNullOrEmpty()
                check(ownedByUs) {
                    "${workspace.absolutePath} はこのツールが作成したものではないようなので削除しませんでした。\n" +
                            "中身を確認して手動で削除するか、「workspace を初期化」をオフにしてください。"
                }
                check(workspace.deleteRecursively()) {
                    "workspace を削除できませんでした。ファイルが開かれていないか確認してください: ${workspace.absolutePath}"
                }
            }
            workspace.mkdirs()
            check(workspace.isDirectory) { "workspace を作成できませんでした: ${workspace.absolutePath}" }
            marker.createNewFile()
            workspace.absolutePath
        }

    override suspend fun extractSubmissions(
        rootDir: String,
        workspaceDir: String,
        parseDirName: (String) -> SubmissionDirName?,
        log: (String) -> Unit,
    ): List<Student> = withContext(Dispatchers.IO) {
        val workspace = File(workspaceDir).absoluteFile
        val submissionDirs = File(rootDir).listFiles().orEmpty()
            .filter { it.isDirectory && it.absoluteFile != workspace }
            .sortedBy { it.name }
        val students = linkedMapOf<StudentId, Student>()
        for (dir in submissionDirs) {
            val parsed = parseDirName(dir.name) ?: continue
            val id = StudentId(parsed.studentId)
            val name = parsed.studentName
            val studentDir = File(workspaceDir, id.value)
            val sourcesDir = File(studentDir, SOURCES_DIR).apply { mkdirs() }

            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                when (file.extension.lowercase()) {
                    "zip" -> runCatching { unzipFile(file.toPath(), sourcesDir.toPath()) }
                        .onFailure { log("$id: ${file.name} の展開に失敗しました: ${it.message}") }

                    "java" -> runCatching {
                        file.copyTo(
                            File(sourcesDir, file.relativeTo(dir).path),
                            overwrite = true
                        )
                    }
                        .onFailure { log("$id: ${file.name} のコピーに失敗しました: ${it.message}") }
                }
            }
            students.getOrPut(id) { Student(id, name, studentDir.absolutePath, dir.absolutePath) }
        }
        students.values.toList()
    }

    override suspend fun listSubmissionFiles(student: Student): SubmissionFiles =
        withContext(Dispatchers.IO) {
            fun list(dir: File): List<FileEntry> =
                if (!dir.isDirectory) {
                    emptyList()
                } else {
                    dir.walkTopDown()
                        .filter { it.isFile }
                        .map {
                            FileEntry(
                                it.absolutePath,
                                it.relativeTo(dir).path.replace(File.separatorChar, '/'),
                                it.length()
                            )
                        }
                        .sortedBy { it.relativePath }
                        .toList()
                }
            SubmissionFiles(
                submitted = student.submissionDir.takeIf { it.isNotBlank() }?.let { list(File(it)) }
                    .orEmpty(),
                extracted = list(File(student.workspaceDir, SOURCES_DIR)),
            )
        }

    override suspend fun listArchive(path: String, format: ArchiveFormat): ArchiveListing =
        withContext(Dispatchers.IO) {
            val file = File(path)
            require(file.isFile) { "ファイルが見つかりません: $path" }
            listArchive(file, format)
        }

    override suspend fun findSources(student: Student): List<SourceFile> =
        withContext(Dispatchers.IO) {
            val studentDir = File(student.workspaceDir)
            val workspace = studentDir.parentFile
            File(studentDir, SOURCES_DIR).walkTopDown()
                .filter { it.isFile && it.extension.equals("java", ignoreCase = true) }
                .sortedBy { it.path }
                .map { SourceFile(student.id, it.absolutePath, it.relativeTo(workspace).path) }
                .toList()
        }

    override suspend fun compile(
        source: SourceFile,
        student: Student,
        toolchain: JavaToolchain,
        timeoutMillis: Long,
    ): CompileOutcome = withContext(Dispatchers.IO) {
        val sourceFile = File(source.path)
        val sourceBase = File(student.workspaceDir, SOURCES_DIR)
        val compileBase = File(student.workspaceDir, COMPILE_DIR)
        val encodingProblems = mutableListOf<CompileOutcome.Failure>()
        for (encoding in SourceEncoding.entries) {
            val attempt = try {
                compileWith(sourceFile, sourceBase, compileBase, encoding, toolchain, timeoutMillis)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext CompileOutcome.Error(
                    command = "javac ${sourceFile.name}",
                    message = e.stackTraceToString()
                )
            }
            if (!attempt.isEncodingProblem) return@withContext attempt.outcome
            encodingProblems += attempt.outcome as CompileOutcome.Failure
        }
        CompileOutcome.Failure(
            command = encodingProblems.joinToString("\n") { it.command },
            encoding = null,
            packageName = encodingProblems.firstNotNullOfOrNull { it.packageName },
            message = "UTF-8 / Shift_JIS のどちらでも文字コードエラーになりました。\n\n" +
                    encodingProblems.joinToString("\n\n") { "[${it.encoding?.charsetName}]\n${it.message}" },
        )
    }

    private class CompileAttempt(val outcome: CompileOutcome, val isEncodingProblem: Boolean)

    private suspend fun compileWith(
        sourceFile: File,
        sourceBase: File,
        compileBase: File,
        encoding: SourceEncoding,
        toolchain: JavaToolchain,
        timeoutMillis: Long,
    ): CompileAttempt {
        val packageName = parsePackageName(sourceFile.readText(encoding.charset))
        val classRoot = classPathRoot(sourceFile.parentFile, packageName)
        val relative =
            classRoot.relativeToOrNull(sourceBase)?.path?.takeUnless { it.startsWith("..") }
                .orEmpty()
        val destination = (if (relative.isEmpty()) compileBase else File(
            compileBase,
            relative
        )).apply { mkdirs() }

        val command = listOf(
            toolchain.executable("javac"),
            "-J-Duser.language=en",
            "-J-Dstdout.encoding=UTF-8",
            "-J-Dstderr.encoding=UTF-8",
            "-proc:none",
            "-encoding", encoding.charsetName,
            "-d", destination.absolutePath,
            "-cp", classRoot.absolutePath,
            sourceFile.absolutePath,
        )
        val commandLine = command.toCommandLine()
        val output = runProcess(command, null, timeoutMillis)
        val message = listOf(decodeText(output.stderr), decodeText(output.stdout))
            .filter(String::isNotBlank)
            .joinToString("\n")

        return when {
            output.timedOut -> CompileAttempt(
                CompileOutcome.Failure(
                    commandLine,
                    encoding,
                    packageName,
                    "コンパイルがタイムアウトしました ($timeoutMillis ms)\n$message"
                ),
                isEncodingProblem = false,
            )

            output.exitCode == 0 -> CompileAttempt(
                CompileOutcome.Success(
                    command = commandLine,
                    encoding = encoding,
                    packageName = packageName,
                    className = sourceFile.nameWithoutExtension,
                    classPath = destination.absolutePath,
                    warnings = message,
                ),
                isEncodingProblem = false,
            )

            else -> CompileAttempt(
                CompileOutcome.Failure(commandLine, encoding, packageName, message),
                isEncodingProblem = message.contains("unmappable character"),
            )
        }
    }

    override suspend fun readSource(source: SourceFile, encoding: SourceEncoding): String =
        withContext(Dispatchers.IO) { File(source.path).readText(encoding.charset) }

    override suspend fun listCalls(
        source: SourceFile,
        target: CompileOutcome.Success,
        toolchain: JavaToolchain,
    ): List<CallSite> = withContext(Dispatchers.IO) {
        val sourceName = File(source.path).name
        val classFiles = File(target.classPath).walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .sortedBy { it.path }
            .toList()
        // コマンドラインが長くなりすぎないよう、分けて逆アセンブルする
        val classes = classFiles.chunked(100).flatMap { chunk ->
            val command = listOf(
                toolchain.executable("javap"),
                "-J-Duser.language=en",
                "-J-Dstdout.encoding=UTF-8",
                "-J-Dstderr.encoding=UTF-8",
                "-c", "-p",
            ) + chunk.map { it.absolutePath }
            val output = runProcess(command, null, JAVAP_TIMEOUT_MILLIS)
            check(!output.timedOut && output.exitCode == 0) {
                "javap でクラスを調べられませんでした: " + decodeText(output.stderr).ifBlank { decodeText(output.stdout) }
            }
            Javap.parse(decodeText(output.stdout))
        }
        // 同じ学生の他のソースから作られたクラスは除く (ソースファイル名とパッケージで見分ける)
        val prefix = target.packageName?.let { "$it." }.orEmpty()
        val own = classes.filter { c ->
            c.sourceFile == sourceName && c.className.startsWith(prefix) &&
                (prefix.isNotEmpty() || '.' !in c.className)
        }
        check(own.any { it.className == target.mainClass }) { "${target.mainClass} のクラスファイルが見つかりません" }
        own.flatMap { it.calls }
    }

    override suspend fun sourceHash(source: SourceFile): String = withContext(Dispatchers.IO) {
        MessageDigest.getInstance("SHA-256").digest(File(source.path).readBytes())
            .joinToString("") { "%02x".format(it) }
    }

    override suspend fun execute(
        target: CompileOutcome.Success,
        testCase: TestCase,
        toolchain: JavaToolchain,
        timeoutMillis: Long,
    ): Execution {
        if (testCase.calls.isNotEmpty()) return checkMainCalls(target, toolchain)
        val testCode = testCase.testCode ?: return runJava(target.classPath, target.mainClass, testCase, toolchain, timeoutMillis)
        return executeTestCode(target, testCase, testCode, toolchain, timeoutMillis)
    }

    /**
     * テストコードを使うテスト。テストコードを一時ディレクトリに置き、提出されたクラスを classpath に入れて
     * コンパイル・実行する (テストケースごとに別のディレクトリなので、並列に実行しても衝突しない)
     */
    private suspend fun executeTestCode(
        target: CompileOutcome.Success,
        testCase: TestCase,
        testCode: String,
        toolchain: JavaToolchain,
        timeoutMillis: Long,
    ): Execution = withContext(Dispatchers.IO) {
        val prepared = TestCode.prepare(testCode, target.packageName, target.className)
        val code = prepared.source
        val dir = Files.createTempDirectory("taa-test-").toFile()
        try {
            val packageDir = prepared.packageName?.replace('.', '/').orEmpty()
            val sourceFile = File(File(dir, "src/$packageDir").apply { mkdirs() }, prepared.fileName)
            sourceFile.writeText(code, Charsets.UTF_8)
            val classes = File(dir, "classes").apply { mkdirs() }
            val javac = listOf(
                toolchain.executable("javac"),
                "-J-Duser.language=en",
                "-J-Dstdout.encoding=UTF-8",
                "-J-Dstderr.encoding=UTF-8",
                "-proc:none",
            "-encoding", "UTF-8",
                "-d", classes.absolutePath,
                "-cp", target.classPath,
                sourceFile.absolutePath,
            )
            val compiled = runProcess(javac, null, TEST_CODE_COMPILE_TIMEOUT_MILLIS)
            if (compiled.timedOut || compiled.exitCode != 0) {
                // 一時ディレクトリのパスは表示しても意味がないので、ファイル名だけにする
                val message = listOf(decodeText(compiled.stderr), decodeText(compiled.stdout))
                    .filter(String::isNotBlank)
                    .joinToString("\n")
                    .replace(sourceFile.absolutePath, sourceFile.name)
                return@withContext Execution(
                    command = "javac ${sourceFile.name}",
                    stdout = "",
                    stderr = "",
                    exitCode = compiled.exitCode,
                    timedOut = false,
                    elapsedMillis = 0.0,
                    testCode = code,
                    testCodeError = if (compiled.timedOut) "テストコードのコンパイルがタイムアウトしました" else message,
                )
            }
            val classPath = classes.absolutePath + File.pathSeparator + target.classPath
            runJava(classPath, prepared.mainClass, testCase, toolchain, timeoutMillis).copy(testCode = code)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** main から直接呼んでいるメソッドを javap で調べる (実行はしない)。結果は標準出力にも1行ずつ入れる */
    private suspend fun checkMainCalls(target: CompileOutcome.Success, toolchain: JavaToolchain): Execution {
        val command = listOf(
            toolchain.executable("javap"),
            "-J-Duser.language=en",
            "-J-Dstdout.encoding=UTF-8",
            "-J-Dstderr.encoding=UTF-8",
            "-c", "-p",
            "-cp", target.classPath,
            target.mainClass,
        )
        val output = runProcess(command, null, JAVAP_TIMEOUT_MILLIS)
        val stdout = decodeText(output.stdout)
        val stderr = decodeText(output.stderr)
        val failed = output.timedOut || output.exitCode != 0
        val calls = if (failed) null else CallCheck.parseJavap(stdout, target.mainClass)
        return Execution(
            command = command.toCommandLine(),
            stdout = calls?.joinToString("\n").orEmpty(),
            stderr = when {
                failed -> listOf(stderr, stdout).filter(String::isNotBlank).joinToString("\n")
                    .ifBlank { "javap を実行できませんでした" }
                calls == null -> "${target.mainClass} に main メソッドが見つかりません"
                else -> ""
            },
            exitCode = output.exitCode,
            timedOut = output.timedOut,
            elapsedMillis = output.elapsedNanos / 1_000_000.0,
            // main が無ければ「何も呼んでいない」として WA にする
            mainCalls = if (failed) null else calls.orEmpty(),
        )
    }

    private suspend fun runJava(
        classPath: String,
        mainClass: String,
        testCase: TestCase,
        toolchain: JavaToolchain,
        timeoutMillis: Long,
    ): Execution {
        val command = listOf(
            toolchain.executable("java"),
            "-Duser.language=en",
            "-Dfile.encoding=UTF-8",
            "-Dstdout.encoding=UTF-8",
            "-Dstderr.encoding=UTF-8",
            "-Xmx$MAX_HEAP",
            "-cp", classPath,
            mainClass,
        ) + splitArguments(testCase.arg)
        val stdin = testCase.input.joinToString("") { "$it\n" }.toByteArray(Charsets.UTF_8)
        // 作業ディレクトリは毎回作って捨てる (相対パスでのファイル操作が、提出物やプロジェクトを壊さないように)
        val workingDir = Files.createTempDirectory("taa-run-").toFile()
        val output = try {
            runProcess(command, stdin, timeoutMillis, workingDir)
        } finally {
            workingDir.deleteRecursively()
        }
        return Execution(
            command = command.toCommandLine(),
            stdout = decodeText(output.stdout),
            stderr = decodeText(output.stderr),
            exitCode = output.exitCode,
            timedOut = output.timedOut,
            elapsedMillis = output.elapsedNanos / 1_000_000.0,
        )
    }

    override suspend fun writeReport(
        path: String,
        format: ReportFormat,
        report: Report,
    ): List<String> = withContext(Dispatchers.IO) {
        val target = File(path).absoluteFile
        val dir = target.parentFile.apply { mkdirs() }
        require(dir.isDirectory) { "書き出し先のディレクトリを作成できません: ${dir.path}" }
        val baseName = target.name.substringBeforeLast('.').ifEmpty { target.name }
        when (format) {
            ReportFormat.Xlsx -> {
                val file = File(dir, "$baseName.${format.extension}")
                writeReplacing(file) { ExcelReportWriter.write(it, report) }
                listOf(file.absolutePath)
            }

            ReportFormat.Csv, ReportFormat.CsvShiftJis -> {
                val charset =
                    if (format == ReportFormat.Csv) Charsets.UTF_8 else Charset.forName("Shift_JIS")
                val detail = File(dir, "$baseName-detail.csv")
                val summary = File(dir, "$baseName-summary.csv")
                writeReplacing(detail) { it.write(Csv.detail(report.details).toByteArray(charset)) }
                writeReplacing(summary) {
                    it.write(
                        Csv.summary(report.summary).toByteArray(charset)
                    )
                }
                listOf(detail.absolutePath, summary.absolutePath)
            }
        }
    }

    /** 一時ファイルに書いてから置き換える (書き込み途中で壊れたファイルを残さない) */
    private fun writeReplacing(target: File, write: (OutputStream) -> Unit) {
        val temp = File.createTempFile(".${target.name}", ".tmp", target.parentFile)
        try {
            temp.outputStream().buffered().use(write)
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (e: FileSystemException) {
                throw IllegalStateException(
                    "${target.name} に書き込めません。Excel などで開いていないか確認してください。(${e.reason ?: e.message})",
                    e,
                )
            }
        } finally {
            temp.delete()
        }
    }

    private companion object {
        const val WORKSPACE_DIR = "workspace"
        const val WORKSPACE_MARKER = ".taassistant"
        const val LEGACY_DATABASE = "database.db"
        const val SOURCES_DIR = "sources"
        const val COMPILE_DIR = "compile"
        const val TEST_CODE_COMPILE_TIMEOUT_MILLIS = 60_000L
        const val JAVAP_TIMEOUT_MILLIS = 60_000L

        /** 提出されたプログラムが使えるメモリの上限 */
        const val MAX_HEAP = "1g"
    }
}

private val SourceEncoding.charset: Charset get() = Charset.forName(charsetName)

private val isWindows =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

private fun JavaToolchain.executable(tool: String): String =
    if (jdkBinDir.isBlank()) tool else File(jdkBinDir, if (isWindows) "$tool.exe" else tool).path

/**
 * パッケージ宣言とディレクトリ構成が一致していれば、パッケージのルートディレクトリを返す。
 * 一致しなければソースファイルのあるディレクトリをそのまま返す。
 */
internal fun classPathRoot(sourceDir: File, packageName: String?): File {
    if (packageName.isNullOrBlank()) return sourceDir
    var dir = sourceDir
    for (segment in packageName.split('.').asReversed()) {
        if (dir.name != segment) return sourceDir
        dir = dir.parentFile ?: return sourceDir
    }
    return dir
}
