package com.hotspot.accounting.root

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A long-lived root shell shared by the whole app.
 *
 * Rather than depending on an external library, this drives `su` directly over stdin/stdout and
 * uses an out-of-band completion marker so we always know exactly where a command's output ends.
 * That matters because we run *many* short `nft`/`ip` commands per second while polling counters,
 * and forking a new `su` for each one would be both slow and battery-hostile.
 *
 * Robustness notes:
 *  - All reads are bounded by [MAX_OUTPUT_BYTES]. A busy `nft list` can emit a lot of text; if the
 *    caller never sees the marker we must not grow the heap without limit.
 *  - [exec] is serialised by [lock], so concurrent coroutines can't interleave their markers.
 *  - Any transport failure tears the shell down and marks it dead, so the next call restarts cleanly.
 */
object RootShell {

    private const val TAG = "RootShell"

    /** Output cap per command. Counters are numeric, so anything beyond this is unexpected. */
    private const val MAX_OUTPUT_BYTES = 512 * 1024

    private const val MARKER_PREFIX = "__HSACC_DONE_"

    /** How long a single command may take before we consider the shell wedged. */
    private const val COMMAND_TIMEOUT_SEC = 20L

    /** How long to wait for the user to answer the root prompt on first start. */
    private const val STARTUP_TIMEOUT_SEC = 60L

    private val lock = Mutex()
    private val seq = AtomicInteger(0)

    @Volatile
    private var process: Process? = null

    @Volatile
    private var writer: BufferedWriter? = null

    @Volatile
    private var reader: BufferedReader? = null

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var rootAvailable: Boolean? = null

    /** Result of a single root command. */
    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    ) {
        val ok: Boolean get() = exitCode == 0
        /** stdout with trailing whitespace removed, for convenient numeric parsing. */
        val trimmed: String get() = stdout.trim()
    }

    /**
     * Runs [command] through the shared root shell.
     *
     * @throws IOException when root is unavailable or the shell died mid-command.
     */
    suspend fun exec(command: String): Result = withContext(Dispatchers.IO) {
        lock.withLock { execLocked(command) }
    }

    /** Convenience: run a command and return trimmed stdout, or null when it failed. */
    suspend fun execOrNull(command: String): String? =
        try {
            val r = exec(command)
            if (r.ok) r.trimmed.ifEmpty { null } else null
        } catch (t: Throwable) {
            Log.w(TAG, "execOrNull failed: ${t.message}")
            null
        }

    /** True when a working `su` is present and already granted. */
    suspend fun isAvailable(): Boolean {
        rootAvailable?.let { return it }
        val ok = try {
            val r = exec("id")
            r.ok && r.stdout.contains("uid=0")
        } catch (t: Throwable) {
            false
        }
        rootAvailable = ok
        return ok
    }

    /** Forces a re-check of root on the next [isAvailable] call. */
    fun invalidateAvailability() {
        rootAvailable = null
    }

    fun lastError(): String? = lastError

    /** Tears down the shell process; the next [exec] transparently starts a fresh one. */
    fun close() {
        try {
            writer?.let { runCatching { it.write("exit\n"); it.flush() } }
        } catch (_: Throwable) {
        }
        runCatching { writer?.close() }
        runCatching { reader?.close() }
        runCatching { process?.destroy() }
        process = null
        writer = null
        reader = null
        rootAvailable = null
    }

    // ---------------------------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------------------------

    private fun execLocked(command: String): Result {
        val p = ensureStarted()
        val w = writer ?: throw IOException("root shell writer unavailable")
        val r = reader ?: throw IOException("root shell reader unavailable")

        val marker = MARKER_PREFIX + seq.incrementAndGet() + "__"
        val errMarker = "E" + marker

        // `2>&1` folds stderr into stdout for the command itself, then we emit the marker on both
        // streams so a command that dies loudly still produces a decidable terminator.
        val script = buildString {
            append(command.trimEnd().removeSuffix(";"))
            append("; __rc=$?; ")
            // stderr marker is separate so a command's own benign stderr does not confuse us.
            append("printf '\\n%s\\n' \"\$__rc\" ")
            append("; printf '%s\\n' '").append(errMarker).append("' 1>&2")
            append("; printf '%s\\n' '").append(marker).append("'")
            append('\n')
        }

        try {
            w.write(script)
            w.flush()
        } catch (e: IOException) {
            killQuietly()
            throw IOException("failed to write to root shell", e)
        }

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val sawMarker = readUntilMarker(r, marker, errMarker, stdout, stderr, p)

        if (!sawMarker) {
            killQuietly()
            throw IOException(
                "root shell did not respond within ${COMMAND_TIMEOUT_SEC}s " +
                    "(command: ${command.take(120)})"
            )
        }

        // Last non-empty line of stdout is the exit code we appended.
        val lines = stdout.toString().trimEnd('\n')
        val lastNl = lines.lastIndexOf('\n')
        val rcLine = if (lastNl >= 0) lines.substring(lastNl + 1) else lines
        val exitCode = rcLine.trim().toIntOrNull()
        val body = if (exitCode != null && lastNl >= 0) lines.substring(0, lastNl) else lines

        return Result(
            exitCode = exitCode ?: -1,
            stdout = body,
            stderr = stderr.toString(),
        )
    }

    /**
     * Reads stdout until [marker] appears, and stderr until [errMarker] appears.
     * Both must be seen before we call the command finished.
     *
     * We read the two streams on the *same* thread by alternating on [BufferedReader.ready], which
     * avoids burning two threads per command while staying responsive.
     */
    private fun readUntilMarker(
        reader: BufferedReader,
        marker: String,
        errMarker: String,
        stdout: StringBuilder,
        stderr: StringBuilder,
        process: Process,
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SEC)
        var stdoutDone = false
        var stderrDone = false

        while (!(stdoutDone && stderrDone)) {
            if (System.nanoTime() > deadline) return false
            if (!process.isAlive) return false

            var progressed = false

            // Drain whatever stdout is ready.
            while (!stdoutDone && reader.ready()) {
                val line = reader.readLine() ?: return false
                progressed = true
                if (line == marker) {
                    stdoutDone = true
                    break
                }
                if (stdout.length < MAX_OUTPUT_BYTES) {
                    stdout.append(line).append('\n')
                }
            }

            // Drain whatever stderr is ready. The error stream is a separate reader only when we
            // could not merge it; in the merged case this is a no-op branch.
            if (!stderrDone) {
                val er = errReader
                if (er != null) {
                    while (!stderrDone && er.ready()) {
                        val line = er.readLine() ?: break
                        progressed = true
                        if (line == errMarker) {
                            stderrDone = true
                            break
                        }
                        if (stderr.length < MAX_OUTPUT_BYTES) {
                            stderr.append(line).append('\n')
                        }
                    }
                } else {
                    stderrDone = true
                }
            }

            if (!progressed) {
                // Nothing ready yet; yield a little rather than spinning the CPU.
                try {
                    Thread.sleep(2)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    @Volatile
    private var errReader: BufferedReader? = null

    /** Starts (or restarts) the persistent root shell. Must be called while holding [lock]. */
    private fun ensureStarted(): Process {
        process?.let { if (it.isAlive) return it }

        killQuietly()

        // Root managers differ in how they accept an interactive shell:
        //   Magisk      `su` (no args) opens a root shell
        //   KernelSU    `su` too, and additionally supports `su 0 <cmd>`
        //   APatch      same as KernelSU
        // `su -c sh` is the least portable of the options, so it is tried last. Each candidate is
        // validated with a round-trip probe, so a wrong guess costs one failed process, not a bug.
        val shells = listOf(
            listOf("su"),
            listOf("su", "0", "sh"),
            listOf("su", "-c", "sh"),
            listOf("su", "root", "sh"),
            listOf("/system/bin/su"),
            listOf("/system/xbin/su"),
            listOf("/debug_ramdisk/su"),
            listOf("/data/adb/ksu/bin/su"),
            listOf("/data/adb/ap/bin/su"),
        )

        var lastFailure: Throwable? = null
        for (args in shells) {
            try {
                val pb = ProcessBuilder(args)
                // Avoid inheriting a working directory the root shell may not be able to enter
                // (the app's private data dir is mode 0700 and owned by the app uid).
                pb.directory(java.io.File("/"))
                pb.redirectErrorStream(false)
                val proc = pb.start()

                val w = BufferedWriter(OutputStreamWriter(proc.outputStream, Charsets.UTF_8))
                val r = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                val er = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))

                process = proc
                writer = w
                reader = r
                errReader = er
                lastError = null

                // Warm up: confirm `sh` is accepting commands before handing the shell to callers.
                // This is also where a denied root prompt manifests, as a startup timeout.
                if (probe(w, r, er)) {
                    Log.i(TAG, "root shell started via: ${args.joinToString(" ")}")
                    return proc
                }

                killQuietly()
                lastFailure = IOException(
                    "`${args.joinToString(" ")}` started but never responded (root denied?)"
                )
            } catch (t: Throwable) {
                lastFailure = t
                killQuietly()
            }
        }

        val msg = "no working root shell: ${lastFailure?.message ?: "unknown"}"
        lastError = msg
        rootAvailable = false
        throw IOException(msg, lastFailure)
    }

    /** Sends a trivial command and waits for its marker, to validate the shell. */
    private fun probe(
        w: BufferedWriter,
        r: BufferedReader,
        er: BufferedReader,
        @Suppress("UNUSED_PARAMETER") unused: Any? = null,
    ): Boolean {
        val marker = MARKER_PREFIX + "probe__"
        return try {
            w.write("echo ready; printf '%s\\n' '$marker'\n")
            w.flush()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STARTUP_TIMEOUT_SEC)
            while (System.nanoTime() < deadline) {
                if (r.ready()) {
                    val line = r.readLine() ?: return false
                    if (line == marker) return true
                }
                if (er.ready()) {
                    // Consume and log anything the root manager prints on stderr.
                    val line = er.readLine()
                    if (line != null) Log.d(TAG, "su stderr: $line")
                }
                Thread.sleep(5)
            }
            false
        } catch (t: Throwable) {
            Log.w(TAG, "probe failed: ${t.message}")
            false
        }
    }

    private fun killQuietly() {
        runCatching { errReader?.close() }
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { process?.destroy() }
        errReader = null
        reader = null
        writer = null
        process = null
    }

    /** Best-effort check for a binary on the root shell's PATH. */
    suspend fun hasBinary(name: String): Boolean {
        val out = execOrNull("command -v $name 2>/dev/null || which $name 2>/dev/null")
        return !out.isNullOrBlank()
    }

    /** Reads a kernel-visible file through the root shell. */
    suspend fun readFile(path: String): String? =
        execOrNull("cat '$path' 2>/dev/null")

    /** True when the kernel exposes a writable file (used for hotspot interface discovery). */
    suspend fun fileExists(path: String): Boolean =
        execOrNull("test -e '$path' && echo yes") == "yes"

    /** Lists directory entries matching [glob] under the root shell. */
    suspend fun listGlob(glob: String): List<String> {
        val out = execOrNull("ls -d $glob 2>/dev/null") ?: return emptyList()
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.contains("No such file") }
    }

    /** Absolute path of a binary, or null. */
    suspend fun which(name: String): String? {
        val out = execOrNull("command -v $name 2>/dev/null || which $name 2>/dev/null")
        return out?.lines()?.firstOrNull { it.isNotBlank() }?.trim()?.takeIf { File(it).isAbsolute }
    }
}
