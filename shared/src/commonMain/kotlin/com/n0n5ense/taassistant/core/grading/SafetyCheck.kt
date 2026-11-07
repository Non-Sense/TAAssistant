package com.n0n5ense.taassistant.core.grading

import com.n0n5ense.taassistant.core.config.ExecutionPermission
import com.n0n5ense.taassistant.core.config.Task
import com.n0n5ense.taassistant.core.model.SuspiciousCall

/** コンパイル済みのクラスの中の、メソッドの呼び出し1つ */
data class CallSite(
    /** 呼び出している場所 (パッケージ付きのクラス名.メソッド名) */
    val location: String,
    /** 呼び出し先のクラス (パッケージ付き。入れ子のクラスは Outer$Inner) */
    val owner: String,
    /** 呼び出し先のメソッド名 (コンストラクタは <init>) */
    val name: String,
    /** 呼び出し先の型記述子 (例: `(Ljava/lang/String;)V`) */
    val descriptor: String,
) {
    /** [CallCheck.matches] で照合するときの表し方 */
    val call: String get() = "$owner.$name"
}

/** `javap -c -p` の出力を、クラスごとに解析する */
object Javap {
    /** 1つのクラスの解析結果 */
    data class ClassCalls(val className: String, val sourceFile: String?, val calls: List<CallSite>)

    private val compiledFrom = Regex("""^Compiled from "(.+)"$""")
    private val classDeclaration = Regex("""\b(?:class|interface|enum|record)\s+([\w.$]+)""")
    private val memberName = Regex("""([\w$]+)\(""")
    private val invoke = Regex("""//\s*(?:Interface)?Method\s+(.+?):(\(.*)$""")

    fun parse(output: String): List<ClassCalls> {
        val classes = mutableListOf<ClassCalls>()
        var sourceFile: String? = null
        var className: String? = null
        var member = "?"
        var calls = mutableListOf<CallSite>()
        fun finishClass() {
            className?.let { classes += ClassCalls(it, sourceFile, calls) }
            className = null
            sourceFile = null
            calls = mutableListOf()
        }
        for (raw in output.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            compiledFrom.find(line)?.let {
                finishClass()
                sourceFile = it.groupValues[1]
                return@let
            }
            if (compiledFrom.matches(line)) continue
            // クラスの宣言 (字下げなし)
            if (line.isNotEmpty() && !line.startsWith(" ") && line.endsWith("{")) {
                if (className != null) {
                    val keepSource = sourceFile
                    finishClass()
                    sourceFile = keepSource
                }
                className = classDeclaration.find(line)?.groupValues?.get(1)
                member = "?"
                continue
            }
            // メンバーの宣言 (2文字の字下げ。命令はもっと深い)
            if (line.startsWith("  ") && !line.startsWith("   ") && line.endsWith(";")) {
                member = when {
                    line.trim().startsWith("static {}") -> "<clinit>"
                    else -> memberName.find(line)?.groupValues?.get(1) ?: "?"
                }
                // コンストラクタは「クラス名(」と表示されるので <init> にする
                val simple = className?.substringAfterLast('.')
                if (member == simple || member == className) member = "<init>"
                continue
            }
            val current = className ?: continue
            val match = invoke.find(line) ?: continue
            val target = match.groupValues[1].replace("\"", "")
            val dot = target.lastIndexOf('.')
            val owner = if (dot < 0) current else target.substring(0, dot).replace('/', '.')
            val name = if (dot < 0) target else target.substring(dot + 1)
            calls += CallSite("$current.$member", owner, name, match.groupValues[2])
        }
        finishClass()
        return classes
    }
}

/**
 * 実行する前に、危ない可能性がある呼び出し (外部プログラムの起動・ファイルの書き込み・ネットワーク・
 * 検査をすり抜けられるリフレクションなど) を探す。見つかったソースは、TA が確認するまで実行しない。
 *
 * 書かれている呼び出しを見るだけなので、手の込んだ回避までは防げない (そのためリフレクションなども対象にしている)。
 */
object SafetyCheck {
    /**
     * 危ない可能性がある呼び出しの規則。
     * @param pattern [CallCheck.matches] の書き方 (`クラス.*` はそのクラスのすべてのメソッド)
     * @param firstParams 最初の引数の型がこのどれかのときだけ (例: `PrintWriter(String)` はファイルに書く)
     */
    data class Rule(val category: ExecutionPermission, val pattern: String, val firstParams: Set<String>? = null)

    private val PROCESS = ExecutionPermission.Process
    private val NATIVE = ExecutionPermission.Native
    private val FILE = ExecutionPermission.FileWrite
    private val NETWORK = ExecutionPermission.Network
    private val DYNAMIC = ExecutionPermission.Reflection
    private val pathParams = setOf("Ljava/lang/String;", "Ljava/io/File;", "Ljava/nio/file/Path;")

    val defaultRules: List<Rule> = buildList {
        listOf(
            "java.lang.Runtime.exec", "new java.lang.ProcessBuilder", "java.lang.ProcessBuilder.start",
            "java.awt.Desktop.open", "java.awt.Desktop.browse", "java.awt.Desktop.edit",
            "java.awt.Desktop.print", "java.awt.Desktop.mail",
        ).forEach { add(Rule(PROCESS, it)) }
        listOf(
            "java.lang.System.load", "java.lang.System.loadLibrary",
            "java.lang.Runtime.load", "java.lang.Runtime.loadLibrary",
        ).forEach { add(Rule(NATIVE, it)) }
        listOf(
            "new java.io.FileOutputStream", "new java.io.FileWriter", "new java.io.RandomAccessFile",
            "java.io.File.delete", "java.io.File.deleteOnExit", "java.io.File.renameTo", "java.io.File.mkdir",
            "java.io.File.mkdirs", "java.io.File.createNewFile", "java.io.File.createTempFile",
            "java.io.File.setWritable", "java.io.File.setReadable", "java.io.File.setExecutable",
            "java.io.File.setReadOnly", "java.io.File.setLastModified",
            "java.nio.file.Files.write", "java.nio.file.Files.writeString", "java.nio.file.Files.delete",
            "java.nio.file.Files.deleteIfExists", "java.nio.file.Files.move", "java.nio.file.Files.copy",
            "java.nio.file.Files.newOutputStream", "java.nio.file.Files.newBufferedWriter",
            "java.nio.file.Files.newByteChannel", "java.nio.file.Files.createFile",
            "java.nio.file.Files.createDirectory", "java.nio.file.Files.createDirectories",
            "java.nio.file.Files.createTempFile", "java.nio.file.Files.createTempDirectory",
            "java.nio.file.Files.createLink", "java.nio.file.Files.createSymbolicLink",
            "java.nio.file.Files.setAttribute", "java.nio.file.Files.setPosixFilePermissions",
            "java.nio.file.Files.setLastModifiedTime", "java.nio.file.Files.setOwner",
            "java.nio.channels.FileChannel.open",
        ).forEach { add(Rule(FILE, it)) }
        // System.out などに書くときもあるので、ファイル名・File・Path を渡したときだけ
        listOf("new java.io.PrintWriter", "new java.io.PrintStream", "new java.util.Formatter")
            .forEach { add(Rule(FILE, it, pathParams)) }
        listOf(
            "new java.net.Socket", "new java.net.ServerSocket", "new java.net.DatagramSocket",
            "new java.net.MulticastSocket", "java.net.URL.openConnection", "java.net.URL.openStream",
            "java.net.http.HttpClient.*", "java.nio.channels.SocketChannel.open",
            "java.nio.channels.ServerSocketChannel.open", "java.nio.channels.DatagramChannel.open",
        ).forEach { add(Rule(NETWORK, it)) }
        listOf(
            "java.lang.Class.forName", "java.lang.Class.newInstance", "java.lang.reflect.Method.invoke",
            "java.lang.reflect.Constructor.newInstance", "java.lang.reflect.AccessibleObject.setAccessible",
            "java.lang.reflect.Field.setAccessible", "java.lang.reflect.Method.setAccessible",
            "java.lang.reflect.Constructor.setAccessible", "java.lang.reflect.Proxy.newProxyInstance",
            "java.lang.invoke.MethodHandles.*", "java.lang.invoke.MethodHandle.*", "java.lang.ClassLoader.*",
            "new java.net.URLClassLoader", "java.net.URLClassLoader.newInstance",
            "new javax.script.ScriptEngineManager", "sun.misc.Unsafe.*", "jdk.internal.misc.Unsafe.*",
            "javax.tools.ToolProvider.*",
        ).forEach { add(Rule(DYNAMIC, it)) }
    }

    /** [calls] のうち、危ない可能性があるもの (同じ場所・同じ呼び出しは1つにまとめる) */
    fun detect(calls: List<CallSite>, rules: List<Rule> = defaultRules): List<SuspiciousCall> =
        calls.mapNotNull { site ->
            val rule = rules.firstOrNull { rule ->
                CallCheck.matches(rule.pattern, site.call) &&
                    (rule.firstParams == null || rule.firstParams.any { site.descriptor.startsWith("($it") })
            } ?: return@mapNotNull null
            SuspiciousCall(call = site.call, location = site.location, category = rule.category.label, permission = rule.category)
        }.distinct()

    /**
     * 課題で許可していない、危ない可能性がある呼び出し。
     * @param allow 課題で許可した種類 ([com.n0n5ense.taassistant.core.config.Task.allow])
     * @param allowCalls 課題で許可した呼び出し ([com.n0n5ense.taassistant.core.config.Task.allowCalls])
     */
    fun blocking(
        suspicious: List<SuspiciousCall>,
        allowCalls: List<String>,
        allow: Collection<ExecutionPermission> = emptyList(),
    ): List<SuspiciousCall> =
        suspicious.filter { s ->
            (s.permission == null || s.permission !in allow) && allowCalls.none { CallCheck.matches(it, s.call) }
        }

    /** 課題 [task] で許可していない、危ない可能性がある呼び出し */
    fun blocking(suspicious: List<SuspiciousCall>, task: Task?): List<SuspiciousCall> =
        blocking(suspicious, task?.allowCalls.orEmpty(), task?.allow.orEmpty())
}
