package com.n0n5ense.taassistant.core.grading

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal class ProcessOutput(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: ByteArray,
    val timedOut: Boolean,
    val elapsedNanos: Long,
)

/** 1ストリームあたりに保持する出力の上限 (無限ループで出力し続けるプログラム対策) */
private const val MAX_CAPTURE_BYTES = 4 * 1024 * 1024

/**
 * プロセスを実行し、標準出力・標準エラー出力を並行して読み切る。
 * - stdin は書き込んだ後に閉じる (EOF まで読むプログラムが止まらないように)
 * - timeoutMillis < 0 なら無制限
 * - コルーチンがキャンセルされたらプロセスを強制終了する
 */
internal suspend fun runProcess(
    command: List<String>,
    stdin: ByteArray?,
    timeoutMillis: Long
): ProcessOutput =
    runInterruptible(Dispatchers.IO) {
        val process = ProcessBuilder(command).start()
        val startedAt = System.nanoTime()
        try {
            val stdout = StreamCollector(process.inputStream)
            val stderr = StreamCollector(process.errorStream)
            thread(isDaemon = true, name = "process-stdin") {
                runCatching { process.outputStream.use { out -> stdin?.let(out::write) } }
            }
            val finished = if (timeoutMillis < 0) {
                process.waitFor()
                true
            } else {
                process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
            }
            val elapsed = System.nanoTime() - startedAt
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
            ProcessOutput(
                exitCode = if (finished) process.exitValue() else null,
                stdout = stdout.await(),
                stderr = stderr.await(),
                timedOut = !finished,
                elapsedNanos = elapsed,
            )
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

private class StreamCollector(stream: InputStream) {
    private val buffer = ByteArrayOutputStream()
    private val worker = thread(isDaemon = true, name = "process-reader") {
        runCatching {
            stream.use { input ->
                val chunk = ByteArray(8192)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    val room = MAX_CAPTURE_BYTES - buffer.size()
                    if (room > 0) buffer.write(chunk, 0, minOf(n, room))
                }
            }
        }
    }

    fun await(): ByteArray {
        worker.join(3000)
        return buffer.toByteArray()
    }
}

private val shiftJis: Charset = Charset.forName("Shift_JIS")

/** UTF-8 として正しければ UTF-8、そうでなければ Shift_JIS としてデコードする */
internal fun decodeText(bytes: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val text = try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        String(bytes, shiftJis)
    }
    return text.removePrefix("\uFEFF")
}
