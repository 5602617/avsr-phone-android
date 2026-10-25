package io.github.lejw0925.virtualscreen.phone

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

internal class NativeShellResult(val stdout: String, val stderr: String, val exitCode: Int) {
    fun successfulOutput(): String {
        if (exitCode != 0) throw NativeShellCommandException(this)
        return stdout
    }
}

/** Output is retained for callers, never included in exception messages or diagnostics. */
internal class NativeShellCommandException(val result: NativeShellResult) : IOException("ADB_SHELL_EXIT_${result.exitCode}")
internal class NativeShellReadException(
    code: String, val stdout: String, val stderr: String, cause: IOException,
) : IOException(code, cause)

/** ADB shell-v2: one-byte stream id, four-byte little-endian size, then payload.
 * libadb 3.1.1 can throw on normal CLSE. Only an explicit exit packet completes a command. */
internal object NativeAdbShell {
    fun read(input: InputStream): NativeShellResult {
        val source = DataInputStream(input)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val header = ByteArray(5)
        val chunk = ByteArray(8192)
        try {
            while (true) {
                source.readFully(header)
                val id = header[0].toInt() and 0xff
                var length = (1..4).fold(0L) { value, index ->
                    value or ((header[index].toLong() and 0xff) shl ((index - 1) * 8))
                }
                if (id == 3) {
                    if (length != 1L) throw IOException("ADB_SHELL_PROTOCOL_INVALID")
                    source.readFully(chunk, 0, 1)
                    return NativeShellResult(stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()), chunk[0].toInt() and 0xff)
                }
                val output = when (id) {
                    1 -> stdout
                    2 -> stderr
                    else -> throw IOException("ADB_SHELL_PROTOCOL_INVALID")
                }
                // Keep arbitrary packet fragmentation and UTF-8 byte boundaries intact.
                // Incremental reads also retain the prefix if transport fails inside a payload.
                while (length > 0) {
                    val count = source.read(chunk, 0, minOf(length, chunk.size.toLong()).toInt())
                    if (count < 0) throw java.io.EOFException()
                    if (count == 0) continue
                    output.write(chunk, 0, count)
                    length -= count
                }
            }
        } catch (failure: IOException) {
            val code = if (failure.message == "ADB_SHELL_PROTOCOL_INVALID") "ADB_SHELL_PROTOCOL_INVALID" else "ADB_SHELL_INCOMPLETE"
            throw NativeShellReadException(code, stdout.toString(Charsets.UTF_8.name()), stderr.toString(Charsets.UTF_8.name()), failure)
        }
    }
}
