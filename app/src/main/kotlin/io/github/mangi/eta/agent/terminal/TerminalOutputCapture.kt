package io.github.mangi.eta.agent.terminal

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

internal class TerminalOutputCapture(
    private val directory: File,
    private val marker: String,
    private val identity: String,
    private val environment: TerminalEnvironment,
) {
    companion object {
        const val PREVIEW_CHARS = 8_000
        const val PREVIEW_HEAD_CHARS = 2_000
        const val PREVIEW_TAIL_CHARS = 6_000
        const val OUTPUT_FILE_MAX_MIB = 32
        const val OUTPUT_FILE_MAX_BYTES = OUTPUT_FILE_MAX_MIB * 1024 * 1024
        const val STDOUT_TAIL_WITH_STDERR_CHARS = 4_000
        const val STDERR_PREVIEW_CHARS = 2_000
        private const val OUTPUT_FILE_FORMAT_BYTES = 19
        private const val OUTPUT_FILE_CONTENT_MAX_BYTES = OUTPUT_FILE_MAX_BYTES - OUTPUT_FILE_FORMAT_BYTES
        private const val WINDOW_BYTES = 40 * 1024
    }

    private val stdout = CapturedStream()
    private val stderr = CapturedStream()
    private val warnings = mutableListOf<String>()
    private var outputFile: File? = null
    private var stderrFile: File? = null
    private var stdoutSink: FileOutputStream? = null
    private var stderrSink: FileOutputStream? = null
    private var fileBytes = 0
    private var fileTruncated = false
    private var writeFailure: IOException? = null

    @Synchronized
    fun stdout(bytes: ByteArray, length: Int) = capture(stdout, bytes, length)

    @Synchronized
    fun stderr(bytes: ByteArray, length: Int) = capture(stderr, bytes, length)

    @Synchronized
    private fun capture(stream: CapturedStream, bytes: ByteArray, length: Int) {
        val wasCapturingToFile = outputFile != null
        stream.capture(bytes, length)
        if (outputFile == null && stdout.totalChars + stderr.totalChars > PREVIEW_CHARS) {
            beginFileCapture()
        } else if (wasCapturingToFile && writeFailure == null) {
            write(stream, bytes, length)
        }
    }

    private fun beginFileCapture() {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("无法创建终端输出目录：${directory.path}")
            }
            cleanupExpiredFiles()
            val now = System.currentTimeMillis()
            outputFile = File(directory, "$now-${UUID.randomUUID().toString().take(8)}.log")
            stderrFile = File.createTempFile("eta-terminal-", ".part", directory)
            stdoutSink = FileOutputStream(outputFile!!)
            stderrSink = FileOutputStream(stderrFile!!)
            stdoutSink!!.write("[stdout]\n".toByteArray())
            write(stdout, stdout.initial.toByteArray(), stdout.initial.size())
            write(stderr, stderr.initial.toByteArray(), stderr.initial.size())
            stdout.stopInitial()
            stderr.stopInitial()
        } catch (failure: IOException) {
            stdout.stopInitial()
            stderr.stopInitial()
            writeFailure = failure
            closeSinks()
        }
    }

    private fun cleanupExpiredFiles() {
        val now = System.currentTimeMillis()
        val files = directory.listFiles() ?: throw IOException("无法列出终端输出目录：${directory.path}")
        files.filter { it.isFile && it.lastModified() < now - TimeUnit.DAYS.toMillis(7) }.forEach {
            if (it.exists() && !it.delete()) warnings += "无法删除过期终端输出：${it.path}"
        }
    }

    private fun write(stream: CapturedStream, bytes: ByteArray, length: Int) {
        val sink = if (stream === stdout) stdoutSink else stderrSink
        if (sink == null) return
        val allowed = (OUTPUT_FILE_CONTENT_MAX_BYTES - fileBytes).coerceAtLeast(0)
        val count = length.coerceAtMost(allowed)
        try {
            if (count > 0) {
                sink.write(bytes, 0, count)
                fileBytes += count
            }
            if (count < length) fileTruncated = true
        } catch (failure: IOException) {
            writeFailure = failure
            closeSinks()
        }
    }

    @Synchronized
    fun finish(probeOutput: String): Snapshot {
        closeSinks()
        val observation = TerminalExecutionProbe.extract(probeOutput, marker, identity, environment)
        val metadataChars = probeOutput.length - observation.text.length
        val stdoutChars = (stdout.totalChars - metadataChars).coerceAtLeast(0)
        val totalChars = stdoutChars + stderr.totalChars
        val stdoutText = stdout.firstText(observation.metadataBytes, Int.MAX_VALUE)
        val stderrText = stderr.firstText(0, Int.MAX_VALUE)
        if (totalChars > PREVIEW_CHARS && outputFile != null && writeFailure == null) {
            try {
                appendStderr()
                removeProbePrefix(outputFile!!, observation.metadataBytes)
            } catch (failure: IOException) {
                writeFailure = failure
            }
        }

        val preview = if (totalChars <= PREVIEW_CHARS) null else
            longPreview(stdoutChars, totalChars, stdoutText, stderrText, observation.metadataBytes)

        if (totalChars <= PREVIEW_CHARS) {
            deleteTemporaryFile(outputFile)
            outputFile = null
            writeFailure = null
        }
        deleteTemporaryFile(stderrFile)
        stderrFile = null
        val file = if (writeFailure == null) outputFile else {
            deleteTemporaryFile(outputFile)
            null
        }
        return Snapshot(
            stdout = if (preview == null) stdoutText else preview,
            stderr = if (preview == null) stderrText else "",
            totalChars = totalChars,
            stderrChars = stderr.totalChars,
            outputFile = file,
            fileTruncated = fileTruncated,
            stdoutTruncated = stdoutChars > (if (stderr.totalChars == 0L) PREVIEW_CHARS else PREVIEW_HEAD_CHARS + STDOUT_TAIL_WITH_STDERR_CHARS) || fileTruncated,
            stderrTruncated = stderr.totalChars > STDERR_PREVIEW_CHARS || fileTruncated,
            warning = warnings.takeIf { it.isNotEmpty() }?.joinToString("; "),
            writeFailure = writeFailure,
            runtime = observation.runtime,
        )
    }

    private fun appendStderr() {
        val output = outputFile!!
        output.appendText("\n[stderr]\n")
        FileInputStream(stderrFile!!).use { input ->
            FileOutputStream(output, true).use { target -> input.copyTo(target) }
        }
    }

    private fun longPreview(
        stdoutChars: Long,
        totalChars: Long,
        stdoutText: String,
        stderrText: String,
        metadataBytes: Int,
    ): String {
        val stderrChars = stderr.totalChars
        val stdoutLimit = if (stderrChars == 0L) PREVIEW_TAIL_CHARS else STDOUT_TAIL_WITH_STDERR_CHARS
        val stdoutPart = if (stdoutChars <= PREVIEW_HEAD_CHARS + stdoutLimit) {
            stdoutText
        } else {
            stdout.firstText(metadataBytes, PREVIEW_HEAD_CHARS) + stdout.tailText(stdoutLimit, metadataBytes)
        }
        val stderrPart = if (stderrChars == 0L) "" else "\n[stderr]\n" +
            if (stderrChars <= STDERR_PREVIEW_CHARS) stderrText else stderr.tailText(STDERR_PREVIEW_CHARS)
        val shown = stdoutPart.length + stderrPart.length - if (stderrChars == 0L) 0 else "\n[stderr]\n".length
        val omitted = (totalChars - shown).coerceAtLeast(0)
        val path = if (writeFailure == null) outputFile?.path.orEmpty() else ""
        val truncation = if (fileTruncated) "（全文文件也已截断）" else ""
        val destination = if (path.isEmpty()) "全文保存失败" else "全文见 $path$truncation"
        val marker = "\n[省略 $omitted 字符，$destination]\n"
        return if (stderrChars == 0L) {
            if (stdoutChars <= PREVIEW_HEAD_CHARS + stdoutLimit) stdoutPart + marker
            else stdout.firstText(metadataBytes, PREVIEW_HEAD_CHARS) + marker + stdout.tailText(stdoutLimit, metadataBytes)
        } else {
            val head = if (stdoutChars <= PREVIEW_HEAD_CHARS + stdoutLimit) stdoutPart else stdout.firstText(metadataBytes, PREVIEW_HEAD_CHARS)
            val tail = if (stdoutChars <= PREVIEW_HEAD_CHARS + stdoutLimit) "" else stdout.tailText(stdoutLimit, metadataBytes)
            head + marker + tail + stderrPart
        }
    }

    private fun removeProbePrefix(file: File, metadataBytes: Int) {
        if (metadataBytes == 0) return
        val prefixBytes = "[stdout]\n".toByteArray().size
        RandomAccessFile(file, "rw").use { output ->
            val start = prefixBytes + metadataBytes
            val length = output.length()
            var readPosition = start.toLong()
            var writePosition = prefixBytes.toLong()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (readPosition < length) {
                output.seek(readPosition)
                val read = output.read(buffer, 0, minOf(buffer.size.toLong(), length - readPosition).toInt())
                if (read < 0) break
                output.seek(writePosition)
                output.write(buffer, 0, read)
                readPosition += read
                writePosition += read
            }
            output.setLength(length - metadataBytes)
        }
    }

    private fun deleteTemporaryFile(file: File?) {
        if (file != null && file.exists() && !file.delete()) warnings += "无法删除终端临时输出：${file.path}"
    }

    private fun closeSinks() {
        try {
            stdoutSink?.close()
        } catch (failure: IOException) {
            if (writeFailure == null) writeFailure = failure
        }
        try {
            stderrSink?.close()
        } catch (failure: IOException) {
            if (writeFailure == null) writeFailure = failure
        }
        stdoutSink = null
        stderrSink = null
    }

    private class CapturedStream {
        val initial = ByteArrayOutputStream()
        private val first = ByteArrayOutputStream()
        private val tail = ByteArrayOutputStream()
        private var keepInitial = true
        private var totalBytes = 0L
        var totalChars = 0L
            private set

        fun capture(bytes: ByteArray, length: Int) {
            totalBytes += length
            totalChars += countUtf16Chars(bytes, length)
            if (first.size() < WINDOW_BYTES) first.write(bytes, 0, minOf(length, WINDOW_BYTES - first.size()))
            if (keepInitial) initial.write(bytes, 0, length)
            tail.write(bytes, 0, length)
            if (tail.size() > WINDOW_BYTES) {
                val all = tail.toByteArray()
                tail.reset()
                tail.write(all, all.size - WINDOW_BYTES, WINDOW_BYTES)
            }
        }

        fun firstText(metadataBytes: Int, maxChars: Int): String =
            decodeWindow(first.toByteArray().drop(metadataBytes).toByteArray(), false, totalBytes > first.size())
                .take(maxChars)

        fun tailText(maxChars: Int, metadataBytes: Int = 0): String {
            val bytes = tail.toByteArray()
            val prefix = if (totalBytes <= bytes.size) metadataBytes.coerceAtMost(bytes.size) else 0
            return decodeWindow(bytes.drop(prefix).toByteArray(), totalBytes > bytes.size, false).takeLast(maxChars)
        }

        fun stopInitial() {
            keepInitial = false
            initial.reset()
        }

        companion object {
            private fun decodeWindow(bytes: ByteArray, trimStart: Boolean, trimEnd: Boolean): String {
                var start = 0
                var end = bytes.size
                if (trimStart) {
                    while (start < end && bytes[start].toInt() and 0xc0 == 0x80) start++
                }
                if (trimEnd && start < end) {
                    var lead = end - 1
                    while (lead > start && bytes[lead].toInt() and 0xc0 == 0x80) lead--
                    val value = bytes[lead].toInt() and 0xff
                    val requiredBytes = when {
                        value and 0xf8 == 0xf0 -> 4
                        value and 0xf0 == 0xe0 -> 3
                        value and 0xe0 == 0xc0 -> 2
                        else -> 1
                    }
                    if (end - lead < requiredBytes) end = lead
                }
                return bytes.copyOfRange(start, end).decodeToString()
            }

            private fun countUtf16Chars(bytes: ByteArray, length: Int): Long {
                var count = 0L
                for (index in 0 until length) {
                    val byte = bytes[index].toInt() and 0xff
                    if (byte and 0xc0 != 0x80) count += if (byte and 0xf8 == 0xf0) 2 else 1
                }
                return count
            }
        }
    }

    data class Snapshot(
        val stdout: String,
        val stderr: String,
        val totalChars: Long,
        /** 收到的 stderr 字符数；长输出时 stderr 并入 [stdout] 的预览，[stderr] 为空。 */
        val stderrChars: Long,
        val outputFile: File?,
        val fileTruncated: Boolean,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean,
        val warning: String?,
        val writeFailure: IOException?,
        val runtime: JSONObject?,
    )
}
