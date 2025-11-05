package one.wabbit.ghostscript

import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import javax.imageio.ImageIO

/** ---------- Core ADT for arguments ---------- **/
sealed interface GsArg {
    /** Return CLI parts (each element is one argv token, no spaces or quoting required). */
    fun toCli(): List<String>

    data class PdfVersion(val value: String) {
        companion object {
            val V1_3 = PdfVersion("1.3")
            val V1_4 = PdfVersion("1.4")
            val V1_5 = PdfVersion("1.5")
            val V1_6 = PdfVersion("1.6")
            val V1_7 = PdfVersion("1.7")
            val V2_0 = PdfVersion("2.0")
        }
    }

    /** Generic -d param; value=null emits just -dName (true). */
    data class D(val name: String, val value: String? = null) : GsArg {
        override fun toCli() = listOf("-d${name}" + (value?.let { "=$it" } ?: ""))
    }

    /** Generic -s param. */
    data class S(val name: String, val value: String) : GsArg {
        override fun toCli() = listOf("-s${name}=$value")
    }

    /** A bare switch (like -q) */
    data class Switch(val flag: String) : GsArg {
        init { require(flag.startsWith("-")) { "Switch must start with '-': $flag" } }
        override fun toCli() = listOf(flag)
    }

    /** PostScript fragment evaluated via -c ... */
    data class PostScript(val code: String) : GsArg {
        override fun toCli() = listOf("-c", code)
    }

    /** Read additional args from a response file: @file */
    data class ArgFile(val file: Path) : GsArg {
        override fun toCli() = listOf("@${file.toString()}")
    }

    /** ---------- Devices & output ---------- **/
    sealed interface Device : GsArg {
        val name: String
        override fun toCli() = listOf("-sDEVICE=$name")

        data object PdfWrite : Device { override val name = "pdfwrite" }
        data object Ps2Write : Device { override val name = "ps2write" }
        data object Eps2Write : Device { override val name = "eps2write" }
        data object Png16m : Device { override val name = "png16m" }
        data object PngGray : Device { override val name = "pnggray" }
        data object PngAlpha : Device { override val name = "pngalpha" }
        data object Jpeg : Device { override val name = "jpeg" }
        data object JpegCmyk : Device { override val name = "jpegcmyk" }
        data object Tiff24nc : Device { override val name = "tiff24nc" }
        data object TiffGray : Device { override val name = "tiffgray" }
        data object TxtWrite : Device { override val name = "txtwrite" }
        data object XpsWrite : Device { override val name = "xpswrite" }
        data object DocxWrite : Device { override val name = "docxwrite" }
        data class Custom(override val name: String) : Device
    }

    /** Output destination: -sOutputFile=... (preferred explicit form). */
    data class OutputFile(val spec: String) : GsArg {
        override fun toCli() = listOf("-sOutputFile=$spec")
    }

    /**
     * -o shorthand: emits -o spec *and* auto-adds -dBATCH/-dNOPAUSE at assembly time.
     * We keep it distinct to preserve your intent; the builder turns it into concrete flags.
     */
    data class OutputShorthand(val spec: String) : GsArg {
        override fun toCli() = listOf("-o", spec)
    }

    /** Resolution: -r300 or -r300x300 */
    data class Resolution(val x: Int, val y: Int? = null) : GsArg {
        init {
            require(x > 0) { "Resolution must be > 0" }
            require(y == null || y > 0) { "Resolution (Y) must be > 0 if specified" }
        }
        override fun toCli() = listOf("-r" + if (y == null) "$x" else "${x}x${y}")
    }

    /** Pixel size for display devices: -gWIDTHxHEIGHT */
    data class PixelSize(val width: Int, val height: Int) : GsArg {
        init { require(width > 0 && height > 0) { "Pixel dimensions must be > 0" } }
        override fun toCli() = listOf("-g${width}x${height}")
    }

    /** Page size via points: -dDEVICEWIDTHPOINTS=... -dDEVICEHEIGHTPOINTS=... */
    data class PageSizePoints(val widthPt: Int, val heightPt: Int) : GsArg {
        init { require(widthPt > 0 && heightPt > 0) { "Page size in points must be > 0" } }
        override fun toCli() = listOf("-dDEVICEWIDTHPOINTS=$widthPt", "-dDEVICEHEIGHTPOINTS=$heightPt")
    }

    /** Predefined paper: -sPAPERSIZE=a4|letter|... */
    data class Paper(val name: String) : GsArg {
        override fun toCli() = listOf("-sPAPERSIZE=$name")
    }

    /** ---------- General switches ---------- **/
    data object Batch : GsArg { override fun toCli() = listOf("-dBATCH") }
    data object NoPause : GsArg { override fun toCli() = listOf("-dNOPAUSE") }
    data object Quiet : GsArg { override fun toCli() = listOf("-q") /* implies -dQUIET */ }
    data object Safer : GsArg { override fun toCli() = listOf("-dSAFER") }
    data object NoSafer : GsArg { override fun toCli() = listOf("-dNOSAFER") }
    data object NoPrompt : GsArg { override fun toCli() = listOf("-dNOPROMPT") }
    data object NoDisplay : GsArg { override fun toCli() = listOf("-dNODISPLAY") }

    data class IncludePath(val path: String) : GsArg { override fun toCli() = listOf("-I", path) }

    /** Permit Ghostscript’s SAFER sandbox to read a specific path (file or directory). */
    data class PermitFileRead(val path: Path) : GsArg {
        override fun toCli() = listOf("--permit-file-read=${path.toAbsolutePath()}")
    }

    /** Enable PS/PDF transparency operators (needed for flattened watermarks at PDF 1.4+). */
    data object AllowPsTransparency : GsArg { override fun toCli() = listOf("-dALLOWPSTRANSPARENCY") }

    /** Alpha bit settings for text/graphics (rasterization quality). */
    data class TextAlphaBits(val value: Int) : GsArg {
        init { require(value in 1..4) }
        override fun toCli() = listOf("-dTextAlphaBits=$value")
    }
    data class GraphicsAlphaBits(val value: Int) : GsArg {
        init { require(value in 1..4) }
        override fun toCli() = listOf("-dGraphicsAlphaBits=$value")
    }

    /** ---------- PDF-centric args ---------- **/
    data class PdfPassword(val password: String) : GsArg { // for reading encrypted PDFs
        override fun toCli() = listOf("-sPDFPassword=$password")
    }
    data class FirstPage(val page: Int) : GsArg {
        init { require(page >= 1) { "FirstPage must be >= 1" } }
        override fun toCli() = listOf("-dFirstPage=$page")
    }
    data class LastPage(val page: Int) : GsArg {
        init { require(page >= 1) { "LastPage must be >= 1" } }
        override fun toCli() = listOf("-dLastPage=$page")
    }
    data class PageList(val spec: String) : GsArg { override fun toCli() = listOf("-sPageList=$spec") }
    data object PdfFitPage : GsArg { override fun toCli() = listOf("-dPDFFitPage") }
    data class UseBox(val which: Box) : GsArg {
        enum class Box { Crop, Trim, Art, Bleed }
        override fun toCli() = listOf("-dUse${which.name}Box")
    }

    /** PDF write quality presets: -dPDFSETTINGS= */
    sealed class PdfSettings private constructor(val nameArg: String) : GsArg {
        override fun toCli() = listOf("-dPDFSETTINGS=/$nameArg")
        data object Default : PdfSettings("default")
        data object Prepress : PdfSettings("prepress")
        data object Printer : PdfSettings("printer")
        data object Ebook : PdfSettings("ebook")
        data object Screen : PdfSettings("screen")
    }

    /** PDF version/feature cap: -dCompatibilityLevel=1.4, 1.7, ... */
    data class CompatibilityLevel(val version: String) : GsArg {
        override fun toCli() = listOf("-dCompatibilityLevel=$version")
    }

    /** Common pdfwrite toggles (documented, stable) */
    data class CompressFonts(val value: Boolean) : GsArg {
        override fun toCli() = listOf("-dCompressFonts=${value}")
    }
    data class CompressStreams(val value: Boolean) : GsArg {
        override fun toCli() = listOf("-dCompressStreams=${value}")
    }
    data object NoOutputFonts : GsArg { override fun toCli() = listOf("-dNoOutputFonts") }
    data object UnrollForms : GsArg { override fun toCli() = listOf("-dUNROLLFORMS") }

    /** Distiller parameter helpers (typed where sensible) */
    enum class DownsampleType { Subsample, Average, Bicubic }
    data class DownsampleColorImages(val enable: Boolean) : GsArg {
        override fun toCli() = listOf("-dDownsampleColorImages=$enable")
    }
    data class ColorImageResolution(val dpi: Int) : GsArg {
        override fun toCli() = listOf("-dColorImageResolution=$dpi")
    }
    data class ColorImageDownsampleType(val type: DownsampleType) : GsArg {
        override fun toCli() = listOf("-dColorImageDownsampleType=/$type")
    }
    data class DownsampleGrayImages(val enable: Boolean) : GsArg {
        override fun toCli() = listOf("-dDownsampleGrayImages=$enable")
    }
    data class GrayImageResolution(val dpi: Int) : GsArg {
        override fun toCli() = listOf("-dGrayImageResolution=$dpi")
    }
    data class GrayImageDownsampleType(val type: DownsampleType) : GsArg {
        override fun toCli() = listOf("-dGrayImageDownsampleType=/$type")
    }
    data class DownsampleMonoImages(val enable: Boolean) : GsArg {
        override fun toCli() = listOf("-dDownsampleMonoImages=$enable")
    }
    data class MonoImageResolution(val dpi: Int) : GsArg {
        override fun toCli() = listOf("-dMonoImageResolution=$dpi")
    }
    data class AutoRotatePages(val mode: Mode) : GsArg {
        enum class Mode { None, All, PageByPage }
        override fun toCli() = listOf("-dAutoRotatePages=/$mode")
    }
    /** Generic distiller param escape hatches: */
    data class DistillerBoolean(val name: String, val value: Boolean) : GsArg {
        override fun toCli() = listOf("-d${name}=$value")
    }
    data class DistillerNumber(val name: String, val value: Number) : GsArg {
        override fun toCli() = listOf("-d${name}=$value")
    }
    data class DistillerName(val name: String, val value: String) : GsArg {
        override fun toCli() = listOf("-d${name}=/$value")
    }
    data class DistillerString(val name: String, val value: String) : GsArg {
        override fun toCli() = listOf("-s${name}=$value")
    }

    /** Encryption / permissions (pdfwrite) */
    data class OwnerPassword(val value: String) : GsArg { override fun toCli() = listOf("-sOwnerPassword=$value") }
    data class UserPassword(val value: String) : GsArg { override fun toCli() = listOf("-sUserPassword=$value") }
    /** Permissions bitmask; semantics depend on PDF version/rev. */
    data class Permissions(val value: Int) : GsArg { override fun toCli() = listOf("-dPermissions=$value") }
    /** Historic, still seen in some workflows: */
    data class EncryptionR(val rev: Int) : GsArg { override fun toCli() = listOf("-dEncryptionR=$rev") }
    data class KeyLength(val bits: Int) : GsArg { override fun toCli() = listOf("-dKeyLength=$bits") }

    /** Typed text extraction format for txtwrite device. */
    enum class TextFormatMode(val code: Int) {
        XmlRaw(0),               // developer XML, no grouping
        XmlWithGrouping(1),      // XML with block grouping
        Ucs2Layout(2),           // UCS-2 + BOM, layout-ish
        Utf8Layout(3),           // UTF-8, layout-ish (default)
        InternalDebug(4)         // internal diagnostics
    }
    data class TextFormat(val mode: TextFormatMode) : GsArg {
        override fun toCli() = listOf("-dTextFormat=${mode.code}")
    }

    /** ---------- Inputs ---------- **/
    sealed interface Input { fun toCli(): List<String> }
    data class FileInput(val path: Path) : Input { override fun toCli() = listOf(path.toString()) }
    data object StdIn : Input { override fun toCli() = listOf("-") }
}

/** ---------- Command builder ---------- **/
class GsCommand private constructor(
    private val args: MutableList<GsArg>,
    private val inputs: MutableList<GsArg.Input>
) {
    constructor() : this(mutableListOf(), mutableListOf())

    fun add(vararg a: GsArg) = apply { args.addAll(a) }
    fun input(vararg inps: GsArg.Input) = apply { inputs.addAll(inps) }

    /** Produce final argv with stable ordering guarantees. */
    fun assemble(): List<String> {
        val deviceArgs = args.filterIsInstance<GsArg.Device>()
        require(deviceArgs.size <= 1) { "Specify at most one Device (-sDEVICE=...)" }

        val explicitOut = args.filterIsInstance<GsArg.OutputFile>()
        val shorthands  = args.filterIsInstance<GsArg.OutputShorthand>()
        require(explicitOut.size + shorthands.size <= 1) {
            "Specify at most one of OutputFile or -o shorthand"
        }

        val postScripts = args.filterIsInstance<GsArg.PostScript>()
        val nonOutputNonPS = args.filterNot {
            it is GsArg.Device || it is GsArg.OutputFile || it is GsArg.OutputShorthand || it is GsArg.PostScript
        }

        val outSpec: GsArg.OutputFile? = when {
            explicitOut.isNotEmpty() -> explicitOut.single()
            shorthands.isNotEmpty() -> GsArg.OutputFile(shorthands.single().spec)
            else -> null
        }

        // If user used -o shorthand, mimic gs by auto-adding -dBATCH/-dNOPAUSE unless already present.
        val needBatch = outSpec != null && args.none { it is GsArg.Batch }
        val needNoPause = outSpec != null && args.none { it is GsArg.NoPause }

        val hasPostScript = postScripts.isNotEmpty()
        val hasInputs = inputs.isNotEmpty()

        return buildList {
            // 1) Device first (if any)
            deviceArgs.forEach { addAll(it.toCli()) }

            // 2) Output BEFORE any executable content
            outSpec?.let { addAll(it.toCli()) }

            // 3) Other flags/switches
            nonOutputNonPS.forEach { addAll(it.toCli()) }

            // 3a) If we expanded -o shorthand, add implied flags now
            if (needBatch)   addAll(GsArg.Batch.toCli())
            if (needNoPause) addAll(GsArg.NoPause.toCli())

            // 4) PostScript blocks (-c ...)
            postScripts.forEach { addAll(it.toCli()) }

            // 5) If we injected any -c and we DO have inputs, delimit with -f
            if (hasPostScript && hasInputs) add("-f")

            // 6) Finally the file inputs
            inputs.forEach { addAll(it.toCli()) }
        }
    }
}

/** ---------- Runner ---------- **/
data class ExecResult(
    val command: List<String>,             // full argv (contains secrets)
    val redactedCommand: List<String>,     // safe for logs
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val durationMs: Long,                  // wall-clock duration
    val timedOut: Boolean,                 // true if we killed the process for timeout
    val workingDirectory: Path?            // where we ran it (null => inherited)
) {
    fun requireSuccess(): ExecResult {
        if (exitCode == 0) return this
        throw IllegalStateException(
            buildString {
                append("Ghostscript failed")
                if (timedOut) append(" (timed out after ").append(durationMs).append(" ms)")
                else append(" (exit ").append(exitCode).append(" after ").append(durationMs).append(" ms)")
                append('\n')
                append("Working dir: ").append(workingDirectory?.toString() ?: "<inherit>").append('\n')
                append("Command: ").append(redactedCommand.joinToString(" ")).append('\n')
                if (stderr.isNotBlank()) {
                    append("stderr").append(if (stderrTruncated) " (truncated)" else "").append(":\n")
                    append(stderr)
                }
                if (stdout.isNotBlank()) {
                    append("\nstdout").append(if (stdoutTruncated) " (truncated)" else "").append(":\n")
                    append(stdout)
                }
            }
        )
    }
}

class Ghostscript(
    private val execPath: Path = discoverExecutable(),
    private val config: RunnerConfig = RunnerConfig()
) {
    /** Runtime knobs for launching Ghostscript. */
    data class RunnerConfig(
        /** Process kill timeout. */
        val timeoutMs: Long = 300_000L,          // 5 minutes
        /** Max bytes captured per stream before truncation. */
        val maxOutputBytes: Int = 1_048_576      // 1 MiB
    )

    companion object {
        private val SECRET_PREFIXES = listOf("-sOwnerPassword=", "-sUserPassword=", "-sPDFPassword=")

        private fun redactArgv(argv: List<String>): List<String> =
            argv.map { a -> SECRET_PREFIXES.firstOrNull { a.startsWith(it) }?.let { it + "******" } ?: a }

        private data class ReadResult(val text: String, val truncated: Boolean)

        private fun readStreamLimited(input: java.io.InputStream, limit: Int): ReadResult {
            val buf = ByteArray(8192)
            val baos = java.io.ByteArrayOutputStream()
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val toWrite = if (total + n <= limit) n else (limit - total)
                if (toWrite > 0) {
                    baos.write(buf, 0, toWrite)
                    total += toWrite
                }
                if (total >= limit) {
                    // drain to unblock reader, ignore rest
                    while (input.read(buf) >= 0) {}
                    return ReadResult(baos.toString(Charsets.UTF_8.name()), true)
                }
            }
            return ReadResult(baos.toString(Charsets.UTF_8.name()), false)
        }

        fun discoverExecutable(): Path {
            System.getProperty("ghostscript.exec")?.let { return Paths.get(it) }

            val env = System.getenv()
            // Honour GS / GSC where present (GSC is Windows-only per docs)
            listOfNotNull(env["GS"], env["GSC"]).firstOrNull()?.let { return Paths.get(it) }

            val isWindows = System.getProperty("os.name").lowercase(Locale.ROOT).contains("win")
            val candidates = if (isWindows) listOf(
                "gswin64c.exe", "gswin32c.exe", "gswin64.exe", "gswin32.exe", "gs.exe", "gs"
            ) else listOf("gs")

            val pathDirs = (env["PATH"] ?: "").split(File.pathSeparator).filter { it.isNotBlank() }
            val pathext = if (isWindows) (env["PATHEXT"] ?: ".EXE;.BAT;.CMD").split(";") else listOf("")

            // Track what we actually tried for better diagnostics.
            val attempted = mutableListOf<Path>()

            for (dir in pathDirs) {
                for (base in candidates) {
                    val tryNames = if (isWindows && !base.contains('.')) pathext.map { base + it } else listOf(base)
                    for (name in tryNames) {
                        val p = Paths.get(dir).resolve(name)
                        attempted.add(p)
                        if (Files.isRegularFile(p) && Files.isExecutable(p)) return p
                    }
                }
            }

            val attemptedPreview = attempted.take(20).joinToString("\n  ") { it.toString() }
            val attemptedCount = attempted.size

            error(
                buildString {
                    appendLine("Ghostscript executable not found.")
                    appendLine("OS: ${System.getProperty("os.name")}  Arch: ${System.getProperty("os.arch")}")
                    appendLine("Env GS=${env["GS"] ?: "<unset>"}  GSC=${env["GSC"] ?: "<unset>"}")
                    appendLine("Searched PATH (${pathDirs.size} dirs). Candidates: ${candidates.joinToString(", ")}")
                    appendLine("Example fixes:")
                    appendLine("  • Set system property: -Dghostscript.exec=/absolute/path/to/gs")
                    appendLine("  • Or export GS=/absolute/path/to/gs (or GSC on Windows)")
                    appendLine("  • On macOS (Homebrew): /opt/homebrew/bin/gs or /usr/local/bin/gs")
                    appendLine("  • On Windows: C:\\Program Files\\gs\\<version>\\bin\\gswin64c.exe")
                    appendLine("Attempted (${attemptedCount} paths), first 20:")
                    append("  ").append(attemptedPreview.ifBlank { "<none>" })
                }
            )
        }
    }

    /** Blocking API */
    fun executeBlocking(cmd: GsCommand, workingDir: Path? = null, stdinBytes: ByteArray? = null): ExecResult =
        runBlocking { execute(cmd, workingDir, stdinBytes) }

    /** Suspend API */
    suspend fun execute(
        cmd: GsCommand,
        workingDir: Path? = null,
        stdinBytes: ByteArray? = null,
        timeoutMs: Long = config.timeoutMs
    ): ExecResult = withContext(Dispatchers.IO) {
        val argv = mutableListOf(execPath.toString()) + cmd.assemble()
        val redacted = redactArgv(argv)

        val pb = ProcessBuilder(argv)
        if (workingDir != null) pb.directory(workingDir.toFile())
        pb.redirectErrorStream(false)

        val startNanos = System.nanoTime()
        val proc = try {
            pb.start()
        } catch (e: java.io.IOException) {
            val durMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            throw IllegalStateException(
                "Failed to start Ghostscript at '$execPath'. ${e.message ?: e::class.java.name}\n" +
                        "Command: ${redacted.joinToString(" ")}",
                e
            )
        }

        val outDeferred = async { readStreamLimited(proc.inputStream, config.maxOutputBytes) }
        val errDeferred = async { readStreamLimited(proc.errorStream, config.maxOutputBytes) }

        if (stdinBytes != null) {
            proc.outputStream.use { it.write(stdinBytes) }
        } else {
            proc.outputStream.close()
        }

        val exit: Int
        val outRes: ReadResult
        val errRes: ReadResult

        try {
            exit = withTimeout(timeoutMs) { proc.waitFor() }
            outRes = outDeferred.await()
            errRes = errDeferred.await()
            val durMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            return@withContext ExecResult(
                command = argv,
                redactedCommand = redacted,
                exitCode = exit,
                stdout = outRes.text,
                stderr = errRes.text,
                stdoutTruncated = outRes.truncated,
                stderrTruncated = errRes.truncated,
                durationMs = durMs,
                timedOut = false,
                workingDirectory = workingDir?.toAbsolutePath()
            )
        } catch (e: TimeoutCancellationException) {
            // Hard kill; then make sure readers don't hang on streams after destroy.
            proc.destroyForcibly()
            outDeferred.cancel()
            errDeferred.cancel()
            runCatching { proc.inputStream.close() }
            runCatching { proc.errorStream.close() }

            val durMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
            return@withContext ExecResult(
                command = argv,
                redactedCommand = redacted,
                exitCode = 124, // conventional timeout
                stdout = "",
                stderr = "[ghostscript runner] timed out after ${timeoutMs}ms",
                stdoutTruncated = false,
                stderrTruncated = false,
                durationMs = durMs,
                timedOut = true,
                workingDirectory = workingDir?.toAbsolutePath()
            )
        }
    }


    /** Convenience: query available devices (`gs -h` lists them). */
    fun availableDevicesBlocking(): List<String> = runBlocking { availableDevices() }

    suspend fun availableDevices(): List<String> = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(execPath.toString(), "-h")
        pb.redirectErrorStream(true)
        val proc = try {
            pb.start()
        } catch (e: java.io.IOException) {
            throw IllegalStateException(
                "Failed to start Ghostscript at '$execPath' to query devices (-h). ${e.message ?: e::class.java.name}",
                e
            )
        }
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val code = proc.waitFor()
        if (code != 0) {
            throw IllegalStateException(
                "Ghostscript '-h' exited with $code. Output (tail):\n${output.takeLast(2000)}"
            )
        }

        val idx = output.indexOf("Available devices:")
        if (idx < 0) {
            throw IllegalStateException(
                "Could not locate 'Available devices:' section in 'gs -h' output. Full tail:\n${output.takeLast(4000)}"
            )
        }

        val section = output.substring(idx).substringAfter('\n')
        val devices = mutableListOf<String>()
        for (line in section.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) break
            if (t.startsWith("Search path:") || t.startsWith("For more information")) break
            t.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach(devices::add)
        }
        if (devices.isEmpty()) {
            throw IllegalStateException("Parsed 'gs -h' but found no devices. Output tail:\n${output.takeLast(2000)}")
        }
        devices
    }
}

// -------------------------------------
// High-level helpers (the good stuff)
// -------------------------------------
object Gs {
    // ---------- small utilities ----------
    private fun newCmd(vararg args: GsArg) = GsCommand().add(*args)
    private fun exec(cmd: GsCommand, stdin: ByteArray? = null): ExecResult =
        Ghostscript().executeBlocking(cmd, stdinBytes = stdin)
    private fun requireReadableFile(path: Path, label: String = "file") {
        val abs = path.toAbsolutePath()
        require(Files.isRegularFile(abs)) { "$label not found: $abs" }
        require(Files.isReadable(abs)) { "$label not readable: $abs" }
    }
    private fun requireReadableFiles(paths: List<Path>, label: String = "input file") {
        val problems = paths.map { it.toAbsolutePath() }.mapNotNull { p ->
            when {
                !Files.exists(p) -> "$label missing: $p"
                !Files.isRegularFile(p) -> "$label is not a regular file: $p"
                !Files.isReadable(p) -> "$label not readable: $p"
                else -> null
            }
        }
        require(problems.isEmpty()) { problems.joinToString("\n") }
    }
    private fun requireWritableTarget(output: Path, label: String = "output file") {
        val parent = output.toAbsolutePath().parent ?: Paths.get(".").toAbsolutePath()
        require(Files.exists(parent)) { "Parent directory for $label does not exist: $parent" }
        require(Files.isDirectory(parent)) { "Parent path for $label is not a directory: $parent" }
        require(Files.isWritable(parent)) { "Parent directory for $label is not writable: $parent" }
    }

    // ---------- queries ----------
    /** List available devices (gs -h parse). */
    fun availableDevices(): List<String> = Ghostscript().availableDevicesBlocking()

    /** Count pages using the PDF interpreter without rendering. */
    fun countPages(pdf: Path): Int {
        requireReadableFile(pdf, "input PDF")
        val abs = pdf.toAbsolutePath()
        val escaped = escapePsString(abs.toString())
        val cmd = GsCommand().add(
            GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt,
            GsArg.NoDisplay,
            GsArg.PermitFileRead(abs),
            GsArg.PostScript("($escaped) (r) file runpdfbegin pdfpagecount = quit")
        )
        val r = exec(cmd).requireSuccess()
        val match = Regex("""(?m)^\s*(\d+)\s*$""").findAll(r.stdout).lastOrNull()
        val count = match?.groupValues?.get(1)?.toIntOrNull()
        return count ?: error(
            buildString {
                appendLine("Failed to parse page count from Ghostscript output.")
                appendLine("Command: ${r.redactedCommand.joinToString(" ")}")
                if (r.stderr.isNotBlank()) appendLine("stderr tail:\n${r.stderr.takeLast(400)}")
                append("stdout tail:\n${r.stdout.takeLast(400)}")
            }
        )
    }

    // ---------- core PDF->PDF transforms ----------
    /** Merge PDFs into one. Order is preserved. */
    fun mergePdfs(inputs: List<Path>, output: Path): ExecResult {
        require(inputs.isNotEmpty()) { "inputs must not be empty" }
        requireReadableFiles(inputs, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).apply {
            input(*inputs.map { GsArg.FileInput(it) }.toTypedArray())
        }
        return exec(cmd).requireSuccess()
    }

    /** Extract a page range using First/LastPage (inclusive). */
    fun extractRange(input: Path, output: Path, first: Int, last: Int): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")
        require(first >= 1) { "first page must be >= 1 (got $first)" }
        require(last >= first) { "last page ($last) must be >= first page ($first)" }

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.FirstPage(first), GsArg.LastPage(last),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** Extract arbitrary pages using -sPageList ("1,3,5-7,odd,even"). */
    fun extractPages(input: Path, output: Path, pageList: String): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")
        require(pageList.isNotBlank()) { "pageList must not be blank (e.g. \"1,3,5-7,odd,even\")" }

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.PageList(pageList),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    data class OptimizeOptions(
        val preset: GsArg.PdfSettings? = null,           // /screen|/ebook|/printer|/prepress|/default
        val compatibility: String? = null,               // e.g. "1.4" -> -dCompatibilityLevel
        val downsampleColorDpi: Int? = null,
        val downsampleGrayDpi: Int? = null,
        val downsampleMonoDpi: Int? = null,
        val detectDuplicateImages: Boolean? = true,
        val compressFonts: Boolean? = null,
        val compressStreams: Boolean? = null,
        val autoRotate: GsArg.AutoRotatePages.Mode? = null,
        val preserveAnnots: Boolean? = null,             // -dPreserveAnnots
        val forceLosslessColorImages: Boolean? = null,   // -> AutoFilterColorImages=false, EncodeColorImages=true, ColorImageFilter=/FlateEncode
        val jpegQFactor: Double? = null                  // 0.0..1.0 via /ColorACSImageDict << /QFactor X >> setdistillerparams
    )

    /** Optimize/shrink a PDF with sane toggles and optional preset. */
    fun optimizePdf(input: Path, output: Path, opt: OptimizeOptions = OptimizeOptions()): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).apply {
            opt.preset?.let { add(it) }
            opt.compatibility?.let { add(GsArg.CompatibilityLevel(it)) }

            opt.downsampleColorDpi?.let {
                add(GsArg.DownsampleColorImages(true), GsArg.ColorImageResolution(it),
                    GsArg.ColorImageDownsampleType(GsArg.DownsampleType.Bicubic))
            }
            opt.downsampleGrayDpi?.let {
                add(GsArg.DownsampleGrayImages(true), GsArg.GrayImageResolution(it),
                    GsArg.GrayImageDownsampleType(GsArg.DownsampleType.Bicubic))
            }
            opt.downsampleMonoDpi?.let { add(GsArg.DownsampleMonoImages(true), GsArg.MonoImageResolution(it)) }

            opt.detectDuplicateImages?.let { add(GsArg.DistillerBoolean("DetectDuplicateImages", it)) }
            opt.compressFonts?.let { add(GsArg.CompressFonts(it)) }
            opt.compressStreams?.let { add(GsArg.CompressStreams(it)) }
            opt.autoRotate?.let { add(GsArg.AutoRotatePages(it)) }
            opt.preserveAnnots?.let { add(GsArg.DistillerBoolean("PreserveAnnots", it)) }

            // Image filter policy:
            if (opt.forceLosslessColorImages == true) {
                add(
                    GsArg.DistillerBoolean("AutoFilterColorImages", false),
                    GsArg.DistillerBoolean("EncodeColorImages", true),
                    GsArg.DistillerName("ColorImageFilter", "FlateEncode")
                )
            }

            opt.jpegQFactor?.let { q ->
                require(q in 0.0..1.0) { "jpegQFactor must be in [0.0, 1.0] (got $q)" }
                // Ensure JPEG encode is selected for color images
                add(
                    GsArg.DistillerBoolean("AutoFilterColorImages", false),
                    GsArg.DistillerBoolean("EncodeColorImages", true),
                    GsArg.DistillerName("ColorImageFilter", "DCTEncode")
                )
                add(GsArg.PostScript("<< /ColorACSImageDict << /QFactor $q >> >> setdistillerparams"))
            }

            input(GsArg.FileInput(input))
        }
        return exec(cmd).requireSuccess()
    }

    /** Convert all color to grayscale without rasterizing (where possible). */
    fun toGrayscale(input: Path, output: Path): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.DistillerString("ProcessColorModel", "DeviceGray"),
            GsArg.DistillerString("ColorConversionStrategy", "Gray"),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** Convert to CMYK (vector-preserving where possible). */
    fun toCmyk(input: Path, output: Path): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.DistillerString("ProcessColorModel", "DeviceCMYK"),
            GsArg.DistillerString("ColorConversionStrategy", "CMYK"),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    enum class ColorPolicy { LeaveUnchanged, ToGray, ToRGB, ToCMYK }

    /** Convert/leave colors with an optional compatibility cap (e.g., "1.3" to force transparency flatten). */
    fun convertColors(
        input: Path,
        output: Path,
        policy: ColorPolicy,
        compatibility: String? = null
    ): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).apply {
            when (policy) {
                ColorPolicy.LeaveUnchanged -> {
                    add(GsArg.DistillerName("ColorConversionStrategy", "LeaveColorUnchanged"))
                }
                ColorPolicy.ToGray -> {
                    add(GsArg.DistillerString("ProcessColorModel", "DeviceGray"))
                    add(GsArg.DistillerName("ColorConversionStrategy", "Gray"))
                }
                ColorPolicy.ToRGB -> {
                    add(GsArg.DistillerString("ProcessColorModel", "DeviceRGB"))
                    add(GsArg.DistillerName("ColorConversionStrategy", "RGB"))
                }
                ColorPolicy.ToCMYK -> {
                    add(GsArg.DistillerString("ProcessColorModel", "DeviceCMYK"))
                    add(GsArg.DistillerName("ColorConversionStrategy", "CMYK"))
                }
            }
            compatibility?.let { add(GsArg.CompatibilityLevel(it)) }
            input(GsArg.FileInput(input))
        }
        return exec(cmd).requireSuccess()
    }

    /** Make a linearized (Fast Web View) PDF. Not compatible with ObjStms. */
    fun linearize(input: Path, output: Path): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.DistillerBoolean("FastWebView", true),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** Fit pages to a named paper size (e.g., a4, letter) preserving content. */
    fun fitToPaper(input: Path, output: Path, paper: String): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.Paper(paper),
            GsArg.D("FIXEDMEDIA"),
            GsArg.PdfFitPage,
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** Set CropBox for ALL pages using pdfmark (units: points). */
    fun cropAllPages(input: Path, output: Path, llx: Int, lly: Int, urx: Int, ury: Int): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val crop = "mark /CropBox [$llx $lly $urx $ury] /PAGES pdfmark"
        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.PostScript(crop),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    // ---------- PDF/A ----------
    /**
     * Create PDF/A (1|2|3). You must supply a PDFA_def.ps path that points to a valid OutputIntent (ICC).
     * NOTE: We do NOT force -dSAFER here; PDFA_def.ps typically references an ICC from disk.
     *       If you want SAFER, pass appropriate --permit-file-read=... via `extra`.
     */
    fun toPdfA(
        input: Path,
        output: Path,
        pdfaLevel: Int,
        pdfaDefPs: Path,
        colorStrategy: String = "UseDeviceIndependentColor"
    ): ExecResult {
        require(pdfaLevel in 1..3) { "pdfaLevel must be 1, 2, or 3 (got $pdfaLevel)" }
        requireReadableFile(input, "input PDF")
        requireReadableFile(pdfaDefPs, "PDFA_def.ps")
        requireWritableTarget(output, "output PDF/A")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.DistillerNumber("PDFA", pdfaLevel),
            GsArg.DistillerString("ColorConversionStrategy", colorStrategy),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(pdfaDefPs), GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** Create PDF/X using a valid PDFX_def.ps (must reference a correct OutputIntent). */
    fun toPdfX(input: Path, output: Path, pdfxDefPs: Path): ExecResult {
        requireReadableFile(input, "input PDF")
        requireReadableFile(pdfxDefPs, "PDFX_def.ps")
        requireWritableTarget(output, "output PDF/X")

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.DistillerBoolean("PDFX", true),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(pdfxDefPs), GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    // ---------- encryption / decryption ----------

    /** Encryption revision supported by Ghostscript's pdfwrite (RC4 only). */
    enum class EncryptionRevision(val value: Int) { R2(2), R3(3) }

    /** Allowed key lengths for these revisions. */
    enum class KeyLength(val bits: Int) { Bits40(40), Bits128(128) }

    /** User-controllable permissions. */
    enum class PdfPermission {
        Print, Modify, Copy, Annotate, FillForms, ExtractForAccessibility, Assemble, PrintHigh
    }

    /** Compute the Ghostscript -dPermissions bit field for the given allowed set. */
    private fun buildPermissionsMask(allowed: Set<PdfPermission>, rev: EncryptionRevision): Int {
        fun bit(n: Int) = 1 shl (n - 1)
        var mask = 0

        // Reserved bits that must be 1 per PDF spec: 7–8 and 13–32
        mask = mask or bit(7) or bit(8)
        for (b in 13..32) mask = mask or bit(b)

        // Allowed capabilities
        if (PdfPermission.Print in allowed) mask = mask or bit(3)
        if (PdfPermission.Modify in allowed) mask = mask or bit(4)
        if (PdfPermission.Copy in allowed) mask = mask or bit(5)
        if (PdfPermission.Annotate in allowed) mask = mask or bit(6)

        if (rev == EncryptionRevision.R3) {
            if (PdfPermission.FillForms in allowed) mask = mask or bit(9)
            if (PdfPermission.ExtractForAccessibility in allowed) mask = mask or bit(10)
            if (PdfPermission.Assemble in allowed) mask = mask or bit(11)
            if (PdfPermission.PrintHigh in allowed) mask = mask or bit(12)
        } else {
            // R2 has no bits 9–12; silently ignore if present.
        }
        return mask
    }

    /** Encryption options with typed fields (no raw bitmask). */
    data class EncryptOptions(
        val ownerPassword: String,
        val userPassword: String? = null,
        val revision: EncryptionRevision = EncryptionRevision.R3,
        val keyLength: KeyLength = KeyLength.Bits128,
        val allowedPermissions: Set<PdfPermission> = setOf(
            PdfPermission.Print, PdfPermission.PrintHigh,
            PdfPermission.Copy, PdfPermission.Modify,
            PdfPermission.Annotate, PdfPermission.FillForms,
            PdfPermission.ExtractForAccessibility, PdfPermission.Assemble
        )
    )

    /** Encrypt a PDF. Note: pdfwrite supports only the Standard RC4 method (no AES). */
    fun encryptPdf(input: Path, output: Path, opt: EncryptOptions): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")
        require(opt.ownerPassword.isNotEmpty()) {
            "Owner password is required for permissions to be enforced."
        }
        require(!(opt.revision == EncryptionRevision.R2 && opt.keyLength == KeyLength.Bits128)) {
            "Encryption R2 does not support 128-bit keys."
        }

        val mask = buildPermissionsMask(opt.allowedPermissions, opt.revision)

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.OwnerPassword(opt.ownerPassword),
            GsArg.Permissions(mask),
            GsArg.EncryptionR(opt.revision.value),
            GsArg.KeyLength(opt.keyLength.bits),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).apply {
            opt.userPassword?.let { add(GsArg.UserPassword(it)) }
            input(GsArg.FileInput(input))
        }
        return exec(cmd).requireSuccess()
    }

    /** Remove encryption when you know a valid password. */
    fun decryptPdf(input: Path, output: Path, password: String): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")
        require(password.isNotEmpty()) { "password must not be empty" }

        val cmd = newCmd(
            GsArg.Device.PdfWrite,
            GsArg.OutputFile(output.toString()),
            GsArg.PdfPassword(password),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.Switch("-dNOPROMPT")
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    // ---------- rasterization (PDF -> images) ----------
    data class RasterOptions(
        val device: GsArg.Device = GsArg.Device.Png16m,  // png16m, pnggray, pngalpha, jpeg, tiff*
        val dpi: Int = 150,
        val firstPage: Int? = null,
        val lastPage: Int? = null,
        val textAlphaBits: Int? = 4,
        val graphicsAlphaBits: Int? = 4
    )
    /**
     * Render pages to image files, e.g. outPattern="/tmp/page-%03d.png".
     * Use a printf-like %d in outPattern for multi-page output.
     */
    fun renderToImages(input: Path, outPattern: String, opt: RasterOptions = RasterOptions()): ExecResult {
        requireReadableFile(input, "input PDF")
        // Guard common footgun: multi-page render without %d placeholder.
        val mayBeMultiPage = when {
            opt.firstPage != null && opt.lastPage != null -> opt.firstPage != opt.lastPage
            else -> true // unknown page range; treat as potentially multi-page
        }
        if (mayBeMultiPage && !outPattern.contains("%")) {
            // If they didn't specify a single page, double-check count; costs one cheap call.
            val pages = countPages(input)
            require(!(pages > 1)) {
                "outPattern '$outPattern' lacks a %d placeholder, but the PDF has $pages pages. " +
                        "Use something like '/tmp/page-%03d.png'."
            }
        }
        // No directory validation here because pattern may contain printf parts; still check parent if static.
        if (!outPattern.contains("%")) {
            val outPath = Paths.get(outPattern)
            if (outPath.parent != null) requireWritableTarget(outPath, "output image")
        }

        val cmd = newCmd(
            opt.device,
            GsArg.OutputFile(outPattern),
            GsArg.Resolution(opt.dpi),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).apply {
            opt.firstPage?.let { add(GsArg.FirstPage(it)) }
            opt.lastPage?.let { add(GsArg.LastPage(it)) }
            opt.textAlphaBits?.let { add(GsArg.TextAlphaBits(it)) }
            opt.graphicsAlphaBits?.let { add(GsArg.GraphicsAlphaBits(it)) }
            input(GsArg.FileInput(input))
        }
        return exec(cmd).requireSuccess()
    }

    // ---------- extraction / format conversions ----------
    /** Extract Unicode text. */
    fun extractText(input: Path, outputTxt: Path, mode: GsArg.TextFormatMode = GsArg.TextFormatMode.Utf8Layout): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(outputTxt, "output text file")

        val cmd = newCmd(
            GsArg.Device.TxtWrite,
            GsArg.OutputFile(outputTxt.toString()),
            GsArg.TextFormat(mode),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.NoPrompt
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** Extract to DOCX (text-focused). */
    fun extractDocx(input: Path, outputDocx: Path): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(outputDocx, "output DOCX")

        val cmd = newCmd(
            GsArg.Device.DocxWrite,
            GsArg.OutputFile(outputDocx.toString()),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.Switch("-dNOPROMPT")
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** PDF -> PostScript/EPS/XPS */
    fun pdfToPs(input: Path, outputPs: Path): ExecResult = convertVector(input, outputPs, GsArg.Device.Ps2Write)
    fun pdfToEps(input: Path, outputEps: Path): ExecResult = convertVector(input, outputEps, GsArg.Device.Eps2Write)
    fun pdfToXps(input: Path, outputXps: Path): ExecResult = convertVector(input, outputXps, GsArg.Device.XpsWrite)

    private fun convertVector(input: Path, output: Path, device: GsArg.Device): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output file")

        val cmd = newCmd(
            device,
            GsArg.OutputFile(output.toString()),
            GsArg.Batch, GsArg.NoPause, GsArg.Quiet,
            GsArg.Safer,
            GsArg.Switch("-dNOPROMPT")
        ).input(GsArg.FileInput(input))
        return exec(cmd).requireSuccess()
    }

    /** Page selection DSL for helpers. */
    sealed interface PageSelector {
        data object All : PageSelector
        data object Odd : PageSelector
        data object Even : PageSelector
        data class Range(val first: Int, val last: Int) : PageSelector {
            init { require(first >= 1 && last >= first) }
        }
        data class Set(val pages: kotlin.collections.Set<Int>) : PageSelector
    }

    /** Positioning for watermarks. */
    sealed interface Anchor {
        /** Page center. */
        data object Center : Anchor
        /** Absolute user-space coords in points (72/in). */
        data class Absolute(val xPt: Double, val yPt: Double) : Anchor
        /** Offset from page center, in points. */
        data class OffsetFromCenter(val dxPt: Double, val dyPt: Double) : Anchor
    }

    /** Simple color model for watermark drawing. */
    sealed interface PsColor {
        data class Gray(val g: Double) : PsColor { init { require(g in 0.0..1.0) } }
        data class Rgb(val r: Double, val g: Double, val b: Double) : PsColor {
            init { require(r in 0.0..1.0 && g in 0.0..1.0 && b in 0.0..1.0) }
        }
    }

    /** Transparency model (Ghostscript’s operators vary by version; we polyfill). */
    sealed interface AlphaMode {
        /** Constant fill/stroke alpha in [0,1]. */
        data class Constant(val alpha: Double) : AlphaMode { init { require(alpha in 0.0..1.0) } }
        /** No transparency control (solid). */
        data object None : AlphaMode
    }

    /** Color depth for image-only pipeline. */
    enum class PdfImageDepth { Gray8, Rgb24, Cmyk32 }

    /** Compression for pdfimage* devices. */
    enum class PdfImageCompression { None, Flate, LZW, RLE, JPEG }

    /** Background treatment when rasterizing to image-only PDF. */
    sealed interface RasterBackground {
        data object White : RasterBackground
        data class Rgb(val r: Int, val g: Int, val b: Int) : RasterBackground {
            init { require(r in 0..255 && g in 0..255 && b in 0..255) }
        }
    }

    /**
     * Flattened TEXT watermark via EndPage hook (no annotations).
     *
     * The text is drawn as vector text (embedded font subset as needed) on each selected page.
     * Transparency uses Ghostscript’s PDF 1.4 operators when available; otherwise falls back to solid.
     */
    fun watermarkTextFlattened(
        inPdf: Path,
        outPdf: Path,
        text: String,
        font: String = "Helvetica-Bold",
        fontSizePt: Int = 72,
        rotationDeg: Double = 45.0,
        color: PsColor = PsColor.Gray(0.75),
        // IMPORTANT: no transparency by default to preserve extractable text
        alpha: AlphaMode = AlphaMode.None,
        anchor: Anchor = Anchor.Center,
        pages: PageSelector = PageSelector.All,
        compatibilityPdfVersion: GsArg.PdfVersion = GsArg.PdfVersion.V1_4
    ): ExecResult {
        require(text.isNotEmpty()) { "watermark text must not be empty" }
        // (These guards exist elsewhere in your file; keep them if you already have them.)
        // requireReadableFile(inPdf, "input PDF")
        // requireWritableTarget(outPdf, "output PDF")

        val ps = buildTextWatermarkPS(text, font, fontSizePt, rotationDeg, color, alpha, anchor, pages)

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(outPdf.toString()))
            .add(GsArg.CompatibilityLevel(compatibilityPdfVersion.value))
            // Only enable transparency ops when caller actually asked for alpha
            .apply {
                if (alpha is AlphaMode.Constant && alpha.alpha < 1.0) {
                    add(GsArg.AllowPsTransparency)
                }
            }
            .add(GsArg.Safer)
            .add(GsArg.NoPrompt)
            .add(GsArg.Quiet, GsArg.Batch, GsArg.NoPause)
            .add(GsArg.PostScript(ps))
            .input(GsArg.FileInput(inPdf))

        val r = Ghostscript().executeBlocking(cmd).requireSuccess()

        // Defensive: if someone flips EndPage logic again, catch it loudly.
        runCatching { Gs.countPages(outPdf) }.onSuccess { pagesOut ->
            check(pagesOut > 0) {
                "watermarkTextFlattened produced 0 pages; EndPage hook likely suppressed output. " +
                        "Command: ${r.redactedCommand.joinToString(" ")}"
            }
        }

        return r
    }

    /**
     * Flattened IMAGE watermark (e.g., a small logo) via EndPage hook.
     *
     * Currently supports JPEG input reliably (embedded with DCTDecode), scaled to a target box in points.
     * If you pass PNG, convert externally or supply a JPEG for predictable results.
     */
    fun watermarkImageFlattened(
        inPdf: Path,
        outPdf: Path,
        imageFile: Path,
        drawWidthPt: Double,
        drawHeightPt: Double,
        rotationDeg: Double = 0.0,
        anchor: Anchor = Anchor.Absolute(36.0, 36.0),
        alpha: AlphaMode = AlphaMode.Constant(1.0),
        pages: PageSelector = PageSelector.All,
        compatibilityPdfVersion: GsArg.PdfVersion = GsArg.PdfVersion.V1_4
    ): ExecResult {
        require(drawWidthPt > 0 && drawHeightPt > 0) {
            "drawWidthPt and drawHeightPt must be > 0 (got $drawWidthPt x $drawHeightPt)"
        }
        requireReadableFile(inPdf, "input PDF")
        requireReadableFile(imageFile, "watermark image")
        requireWritableTarget(outPdf, "output PDF")

        val info = readJpegInfo(imageFile)
        val ps = buildImageWatermarkPS(
            imagePath = imageFile.toAbsolutePath().toString(),
            drawWidthPt = drawWidthPt, drawHeightPt = drawHeightPt,
            rotationDeg = rotationDeg, anchor = anchor, alpha = alpha, pages = pages,
            imgWidthPx = info.widthPx, imgHeightPx = info.heightPx, colorSpace = info.colorSpace
        )
        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(outPdf.toString()))
            .add(GsArg.CompatibilityLevel(compatibilityPdfVersion.value))
            .apply {
                if (alpha is AlphaMode.Constant && alpha.alpha < 1.0) add(GsArg.AllowPsTransparency)
            }
            .add(GsArg.Safer)
            .add(GsArg.PermitFileRead(imageFile.toAbsolutePath()))
            .add(GsArg.NoPrompt)
            .add(GsArg.Quiet, GsArg.Batch, GsArg.NoPause)
            .add(GsArg.PostScript(ps))
            .input(GsArg.FileInput(inPdf))
        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    /**
     * Rotate pages by updating the /Rotate key (pdfmark/EndPage).
     * Works on a selected subset or globally, and is non-destructive to page content.
     */
    fun rotatePages(
        inPdf: Path,
        outPdf: Path,
        degrees: Int, // {0, 90, 180, 270}
        pages: PageSelector = PageSelector.All
    ): ExecResult {
        require(degrees in setOf(0, 90, 180, 270)) { "degrees must be one of 0, 90, 180, 270 (got $degrees)" }
        requireReadableFile(inPdf, "input PDF")
        requireWritableTarget(outPdf, "output PDF")

        val ps = buildRotatePagesPS(degrees, pages)
        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(outPdf.toString()))
            .add(GsArg.Safer)
            .add(GsArg.NoPrompt)
            .add(GsArg.Quiet, GsArg.Batch, GsArg.NoPause)
            .add(GsArg.PostScript(ps))
            .input(GsArg.FileInput(inPdf))
        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    /**
     * Image‑only PDF pipeline: render -> wrap each page as a single bitmap image in PDF.
     *
     * Uses pdfimage* devices (raster-to-PDF, single image per page). That guarantees no selectable text,
     * no vectors, just pixels. If you want OCR text layer, switch device names to pdfocr8/24/32.
     */
    fun imageOnlyPdf(
        inPdf: Path,
        outPdf: Path,
        dpi: Int = 300,
        depth: PdfImageDepth = PdfImageDepth.Rgb24,
        compression: PdfImageCompression = PdfImageCompression.Flate,
        downscaleFactor: Int? = null,
        background: RasterBackground = RasterBackground.White,
        autoRotatePages: GsArg.AutoRotatePages.Mode = GsArg.AutoRotatePages.Mode.PageByPage
    ): ExecResult {
        require(dpi > 0) { "dpi must be > 0 (got $dpi)" }
        requireReadableFile(inPdf, "input PDF")
        requireWritableTarget(outPdf, "output PDF")

        val device = when (depth) {
            PdfImageDepth.Gray8 -> GsArg.Device.Custom("pdfimage8")
            PdfImageDepth.Rgb24 -> GsArg.Device.Custom("pdfimage24")
            PdfImageDepth.Cmyk32 -> GsArg.Device.Custom("pdfimage32")
        }

        // Preflight: pdfimage* devices may be absent in some builds. Throw something human-readable.
        runCatching { Ghostscript().availableDevicesBlocking() }.onSuccess { devs ->
            require(devs.contains(device.name)) {
                "Ghostscript device '${device.name}' is not available in this build. " +
                        "Available devices include: ${devs.take(20).joinToString(", ")}${if (devs.size > 20) ", …" else ""}"
            }
        }.onFailure {
            // If we can't query, proceed; the underlying exec will still report a precise error.
        }

        val cmd = GsCommand()
            .add(device)
            .add(GsArg.OutputFile(outPdf.toString()))
            .add(GsArg.Resolution(dpi))
            .add(GsArg.AutoRotatePages(autoRotatePages))
            .add(GsArg.Safer)
            .add(GsArg.NoPrompt)
            .add(GsArg.Quiet, GsArg.Batch, GsArg.NoPause)
            .apply {
                when (compression) {
                    PdfImageCompression.None -> add(GsArg.S("Compression", "None"))
                    PdfImageCompression.Flate -> add(GsArg.S("Compression", "Flate"))
                    PdfImageCompression.LZW -> add(GsArg.S("Compression", "LZW"))
                    PdfImageCompression.RLE -> add(GsArg.S("Compression", "RLE"))
                    PdfImageCompression.JPEG -> add(GsArg.S("Compression", "jpeg"))
                }
                downscaleFactor?.let { require(it in 2..8) { "DownScaleFactor must be in 2..8 (got $it)" }; add(GsArg.D("DownScaleFactor", it.toString())) }
                when (background) {
                    is RasterBackground.White -> { /* default */ }
                    is RasterBackground.Rgb -> {
                        val rgb = (background.r shl 16) or (background.g shl 8) or background.b
                        add(GsArg.D("BackgroundColor", String.format(Locale.ROOT, "16#%06X", rgb)))
                    }
                }
                input(GsArg.FileInput(inPdf))
            }
        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }


    // -----------------------------
    // PS assembly helpers
    // -----------------------------

    private fun buildRotatePagesPS(deg: Int, selSpec: PageSelector): String {
        return when (selSpec) {
            PageSelector.All ->
                "mark /Rotate $deg /PAGES pdfmark"
            else -> {
                val sel = pagePredicate(selSpec)
                """
            ${sel.prolog}
            << /EndPage {
                dup 2 eq {
                    pop
                    /_wmPage _wmPage 1 add def
                    ${sel.predicate} { mark /Rotate $deg /PAGE pdfmark } if
                    false
                }{
                    pop true
                } ifelse
            } bind >> setpagedevice
            """.trimIndent()
            }
        }
    }

    private fun buildTextWatermarkPS(
        text: String,
        font: String,
        fontSizePt: Int,
        rotationDeg: Double,
        color: PsColor,
        alpha: AlphaMode,
        anchor: Anchor,
        pages: PageSelector
    ): String {
        val s = escapePsString(text)
        val colorCode = when (color) {
            is PsColor.Gray -> "${color.g} setgray"
            is PsColor.Rgb  -> "${color.r} ${color.g} ${color.b} setrgbcolor"
        }
        val alphaCode = when (alpha) {
            is AlphaMode.Constant -> """
            ${alpha.alpha}
            systemdict /.setfillconstantalpha known { dup .setfillconstantalpha } {
                systemdict /.setopacityalpha known { .setopacityalpha } { pop } ifelse
            } ifelse
            ${alpha.alpha} systemdict /.setstrokeconstantalpha known { .setstrokeconstantalpha } if
        """.trimIndent()
            AlphaMode.None -> "% no alpha"
        }
        val placeCode = when (anchor) {
            Anchor.Center ->
                "currentpagedevice /PageSize get aload pop 2 div exch 2 div exch translate"
            is Anchor.Absolute ->
                "${anchor.xPt} ${anchor.yPt} translate"
            is Anchor.OffsetFromCenter ->
                "currentpagedevice /PageSize get aload pop 2 div ${anchor.dxPt} add exch 2 div ${anchor.dyPt} add exch translate"
        }

        val sel = pagePredicate(pages)

        return """
        ${sel.prolog}
        /WmDraw {
            gsave
              $placeCode
              $rotationDeg rotate
              /$font findfont $fontSizePt scalefont setfont
              $colorCode
              $alphaCode
              ($s) dup stringwidth pop 2 div neg 0 moveto show
            grestore
        } bind def

        << /EndPage {
            % Stack: pageCount reason
            exch pop        % keep 'reason' only
            dup 2 eq {
                % reason == 2 : setpagedevice deactivation - DO NOT output another page
                pop false
            }{
                % real end-of-page: draw and output
                /_wmPage _wmPage 1 add def
                ${sel.predicate} { WmDraw } if
                true
            } ifelse
        } bind >> setpagedevice
    """.trimIndent()
    }

    private data class JpegInfo(val widthPx: Int, val heightPx: Int, val colorSpace: String)

    private fun readJpegInfo(imageFile: Path): JpegInfo {
        val iis = ImageIO.createImageInputStream(imageFile.toFile())
            ?: error("Unsupported or unreadable image for watermark: $imageFile (no ImageInputStream)")
        iis.use {
            val readers = ImageIO.getImageReaders(iis)
            require(readers.hasNext()) { "Unsupported or unreadable image for watermark: $imageFile (no reader)" }
            val r = readers.next()
            try {
                r.input = iis
                val fmt = r.formatName.lowercase(Locale.ROOT)
                require("jpeg" in fmt) { "watermarkImageFlattened requires JPEG; got $fmt for $imageFile" }
                val w = r.getWidth(0)
                val h = r.getHeight(0)

                // Try to infer color space; default to RGB if unsure (JRE often can’t expose CMYK).
                val raw = r.getRawImageType(0)
                val imageType = raw ?: run {
                    val it = r.getImageTypes(0)
                    if (it.hasNext()) it.next() else null
                }
                val colorSpace = when (val cm = imageType?.colorModel) {
                    null -> "DeviceRGB"
                    else -> when (cm.numColorComponents) {
                        1 -> "DeviceGray"
                        4 -> "DeviceCMYK"
                        else -> "DeviceRGB"
                    }
                }
                return JpegInfo(w, h, colorSpace)
            } finally {
                r.dispose()
            }
        }
    }

    private fun buildImageWatermarkPS(
        imagePath: String,
        drawWidthPt: Double,
        drawHeightPt: Double,
        rotationDeg: Double,
        anchor: Anchor,
        alpha: AlphaMode,
        pages: PageSelector,
        imgWidthPx: Int,
        imgHeightPx: Int,
        colorSpace: String
    ): String {
        val alphaCode = when (alpha) {
            is AlphaMode.Constant -> """
            ${alpha.alpha}
            systemdict /.setfillconstantalpha known { dup .setfillconstantalpha } {
                systemdict /.setopacityalpha known { .setopacityalpha } { pop } ifelse
            } ifelse
            ${alpha.alpha} systemdict /.setstrokeconstantalpha known { .setstrokeconstantalpha } if
        """.trimIndent()
            AlphaMode.None -> "% no alpha"
        }

        val placeCode = when (anchor) {
            Anchor.Center -> """
            currentpagedevice /PageSize get aload pop      % w h
            2 div ${drawWidthPt / 2.0} sub                 % w/2 - drawW/2
            exch 2 div ${drawHeightPt / 2.0} sub           % h/2 - drawH/2
            exch translate
        """.trimIndent()
            is Anchor.Absolute ->
                "${anchor.xPt} ${anchor.yPt} translate"
            is Anchor.OffsetFromCenter -> """
            currentpagedevice /PageSize get aload pop      % w h
            2 div ${anchor.dxPt} add ${drawWidthPt / 2.0} sub
            exch 2 div ${anchor.dyPt} add ${drawHeightPt / 2.0} sub
            exch translate
        """.trimIndent()
        }

        val escapedPath = escapePsLiteralString(imagePath)
        val sel = pagePredicate(pages)

        val decode = when (colorSpace) {
            "DeviceGray" -> "[0 1]"
            "DeviceCMYK" -> "[0 1 0 1 0 1 0 1]"
            else -> "[0 1 0 1 0 1]" // DeviceRGB default
        }
        val csName = when (colorSpace) {
            "DeviceGray" -> "/DeviceGray"
            "DeviceCMYK" -> "/DeviceCMYK"
            else -> "/DeviceRGB"
        }

        return """
        ${sel.prolog}
        /WmImg {
          gsave
            $placeCode
            $rotationDeg rotate
            $alphaCode
            ($escapedPath) (rb) file /DCTDecode filter dup
            << /ImageType 1
               /Width $imgWidthPx
               /Height $imgHeightPx
               /BitsPerComponent 8
               /ColorSpace $csName
               /Decode $decode
               /ImageMatrix [ $drawWidthPt 0 0 -$drawHeightPt 0 $drawHeightPt ]
               /DataSource exch
            >> image
            closefile
          grestore
        } bind def

        << /EndPage {
            % stack: page# reason  (reason is on top)
            exch pop              % keep 'reason'
            2 lt {                % reason 0/1 => real page end
                /_wmPage _wmPage 1 add def
                ${sel.predicate} { WmImg } if
                true              % EMIT the page
            }{
                false             % reason==2 => device transition, DO NOT emit
            } ifelse
        } bind >> setpagedevice
    """.trimIndent()
    }

    // -----------------------------
    // Tiny utilities
    // -----------------------------

    /** Escape () and \n etc for a PostScript ( ... ) string literal. */
    private fun escapePsString(s: String): String =
        buildString(s.length + 8) {
            s.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '('  -> append("\\(")
                    ')'  -> append("\\)")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

    /** Minimal escaping for a literal filename token. */
    private fun escapePsLiteralString(path: String): String =
        path.replace("\\", "\\\\")  // Windows backslashes
            .replace("(", "\\(").replace(")", "\\)")

    // Prolog + predicate for page selection; predicates use the _wmPage counter.
    private data class SelectionPS(val prolog: String, val predicate: String)

    private fun pagePredicate(sel: Gs.PageSelector): SelectionPS = when (sel) {
        Gs.PageSelector.All  -> SelectionPS("/_wmPage 0 def", "true")
        Gs.PageSelector.Odd  -> SelectionPS("/_wmPage 0 def", "_wmPage 2 mod 1 eq")
        Gs.PageSelector.Even -> SelectionPS("/_wmPage 0 def", "_wmPage 2 mod 0 eq")
        is Gs.PageSelector.Range -> {
            require(sel.first >= 1 && sel.last >= sel.first)
            SelectionPS("/_wmPage 0 def", "_wmPage ${sel.first} ge _wmPage ${sel.last} le and")
        }
        is Gs.PageSelector.Set -> {
            if (sel.pages.isEmpty()) {
                SelectionPS("/_wmPage 0 def", "false")
            } else {
                val body = sel.pages.sorted().joinToString("\n") { "/p$it true def" }
                SelectionPS(
                    prolog = "/_wmPage 0 def\n/_wmSel ${sel.pages.size + 8} dict dup begin\n$body\nend def",
                    // _wmSel /p<page> known
                    predicate = """
                    _wmSel
                    _wmPage 20 string cvs
                    dup length 1 add string dup 0 (p) putinterval
                    exch 1 exch putinterval
                    cvn
                    known
                """.trimIndent()
                )
            }
        }
    }

    /** Like File.stem: filename without its last extension. */
    private fun Path.stem(): String {
        val name = fileName.toString()
        val dot = name.lastIndexOf('.')
        return if (dot >= 0) name.substring(0, dot) else name
    }

    /** PS string literal suited for pdfmark: ASCII → (..), otherwise UTF‑16BE hex with BOM. */
    private fun pdfmarkString(s: String): String {
        val ascii = s.all { it.code in 0x20..0x7E && it != '(' && it != ')' && it != '\\' }
        return if (ascii) "(${escapePsString(s)})"
        else {
            val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)
            val hex = bytes.joinToString("") { "%02X".format(it) }
            "<$hex>"
        }
    }

    /** Paper size map (portrait, points). Extend as needed. */
    private fun paperSizePoints(name: String): Pair<Double, Double>? {
        return when (name.lowercase()) {
            "letter"   -> 612.0 to 792.0
            "legal"    -> 612.0 to 1008.0
            "tabloid"  -> 792.0 to 1224.0
            "a3"       -> 842.0 to 1191.0
            "a4"       -> 595.0 to 842.0
            "a5"       -> 420.0 to 595.0
            else -> null
        }
    }

    /**
     * Split into one PDF per page using pdfwrite's %d pattern (no loops).
     * Example outPattern: "/tmp/invoice-%04d.pdf"
     */
    fun splitPages(input: Path, outPattern: String = input.resolveSibling("${input.stem()}-%04d.pdf").toString()): ExecResult {
        requireReadableFile(input, "input PDF")
        require(outPattern.contains("%")) {
            "outPattern must contain a %d placeholder (e.g., ${input.resolveSibling("${input.stem()}-%04d.pdf")})"
        }
        // If pattern does not refer to cwd, validate its parent dir.
        runCatching { Paths.get(outPattern) }.onSuccess { p ->
            p.parent?.let { requireWritableTarget(it.resolve("dummy.tmp"), "output pattern parent") }
        }

        // Guard: don't let single-page PDFs silently create "-0001.pdf" unless caller wants it.
        val pages = countPages(input)
        require(pages >= 1)
        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(outPattern))
            .add(GsArg.Batch, GsArg.NoPause, GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt)
            .input(GsArg.FileInput(input))
        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    /** Shift page content by dx/dy points without rasterizing (pdfwrite preserves vectors). */
    fun shiftContent(input: Path, output: Path, dxPt: Int, dyPt: Int): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")
        val ps = "<< /PageOffset [$dxPt $dyPt] >> setpagedevice"
        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(output.toString()))
            .add(GsArg.PostScript(ps))
            .add(GsArg.Batch, GsArg.NoPause, GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt)
            .input(GsArg.FileInput(input))
        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    /**
     * Auto-crop to content (BBox) with optional uniform margin in points.
     * Implementation: run bbox once, parse per-page HiResBoundingBox, then apply per page via EndPage hook.
     */
    fun autoCropToContent(input: Path, output: Path, marginPt: Int = 0): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val measureCmd = GsCommand()
            .add(GsArg.Device.Custom("bbox"))
            .add(GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt, GsArg.Batch, GsArg.NoPause)
            .input(GsArg.FileInput(input))
        val measure = Ghostscript(config = Ghostscript.RunnerConfig(maxOutputBytes = 16 * 1024 * 1024))
            .executeBlocking(measureCmd)
            .requireSuccess()

        val text = buildString {
            append(measure.stdout)
            if (measure.stderr.isNotBlank()) append('\n').append(measure.stderr)
        }
        val re = Regex("""%%HiResBoundingBox:\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)\s+([-\d.]+)""")
        val boxes = re.findAll(text).map { m ->
            val llx = m.groupValues[1].toDouble() - marginPt
            val lly = m.groupValues[2].toDouble() - marginPt
            val urx = m.groupValues[3].toDouble() + marginPt
            val ury = m.groupValues[4].toDouble() + marginPt
            listOf(llx, lly, urx, ury)
        }.toList()

        require(boxes.isNotEmpty()) {
            "bbox did not yield any HiResBoundingBox lines. Check input integrity. Tail:\n${text.takeLast(1200)}"
        }

        val cases = boxes.mapIndexed { i, b ->
            val (llx, lly, urx, ury) = b
            "_wmPage ${i + 1} eq { mark /CropBox [ $llx $lly $urx $ury ] /PAGE pdfmark } if"
        }.joinToString("\n  ")

        val ps = """
        /_wmPage 0 def
        << /EndPage {
            dup 2 eq {
                pop
                /_wmPage _wmPage 1 add def
                $cases
                false
            }{
                pop true
            } ifelse
        } bind >> setpagedevice
    """.trimIndent()

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(output.toString()))
            .add(GsArg.PostScript(ps))
            .add(GsArg.Batch, GsArg.NoPause, GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt)
            .input(GsArg.FileInput(input))

        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    data class DocInfo(
        val title: String? = null,
        val author: String? = null,
        val subject: String? = null,
        val keywords: String? = null
    )

    data class Bookmark(val title: String, val page: Int, val kids: List<Bookmark> = emptyList())

    /** Set DOCINFO and docview (bookmarks open, page layout). */
    fun setDocInfo(
        input: Path,
        output: Path,
        info: DocInfo,
        openWithOutlines: Boolean = false,
        pageLayout: String? = null
    ): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val docInfoParts = buildList {
            info.title?.let { add("/Title ${pdfmarkString(it)}") }
            info.author?.let { add("/Author ${pdfmarkString(it)}") }
            info.subject?.let { add("/Subject ${pdfmarkString(it)}") }
            info.keywords?.let { add("/Keywords ${pdfmarkString(it)}") }
        }
        val docInfo = if (docInfoParts.isNotEmpty())
            "mark ${docInfoParts.joinToString(" ")} /DOCINFO pdfmark"
        else
            "% no DOCINFO"

        val docViewParts = buildList {
            if (openWithOutlines) add("/PageMode /UseOutlines")
            pageLayout?.let { add("/PageLayout /$it") }
            add("/Page 1")
            add("/View [ /Fit ]")
        }
        val docView = if (docViewParts.isNotEmpty())
            "mark ${docViewParts.joinToString(" ")} /DOCVIEW pdfmark"
        else
            "% no DOCVIEW"

        val ps = listOf(docInfo, docView).joinToString("\n")

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(output.toString()))
            .add(GsArg.PostScript(ps))
            .add(GsArg.Batch, GsArg.NoPause, GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt)
            .input(GsArg.FileInput(input))
        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    /** Merge PDFs and add top-level bookmarks at each file's start page. */
    fun mergeWithBookmarks(
        inputs: List<Path>,
        output: Path,
        titles: List<String> = inputs.map { it.fileName.toString() }
    ): ExecResult {
        require(inputs.isNotEmpty()) { "inputs must not be empty" }
        require(titles.size == inputs.size) { "titles size (${titles.size}) must match inputs size (${inputs.size})" }
        requireReadableFiles(inputs, "input PDF")
        requireWritableTarget(output, "output PDF")

        val pageCounts = inputs.map { countPages(it) }
        var cursor = 1
        val marks = buildString {
            inputs.indices.forEach { i ->
                val title = pdfmarkString(titles[i])
                append("mark /Title ").append(title)
                    .append(" /Page ").append(cursor)
                    .append(" /OUT pdfmark\n")
                cursor += pageCounts[i]
            }
        }

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(output.toString()))
            .add(GsArg.PostScript(marks))
            .add(GsArg.Batch, GsArg.NoPause, GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt)
            .apply { input(*inputs.map { GsArg.FileInput(it) }.toTypedArray()) }

        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    enum class PageNumberPos { TopLeft, TopRight, BottomLeft, BottomRight, CenterBottom, Custom }

    /**
     * Add flattened page numbers via EndPage hook. We precompute all labels (so your formatter can be arbitrary).
     * dxPt/dyPt are margins; for Custom they are absolute (from bottom-left).
     */
    fun addPageNumbers(
        input: Path,
        output: Path,
        font: String = "Helvetica",
        fontSizePt: Int = 10,
        pos: PageNumberPos = PageNumberPos.BottomRight,
        dxPt: Double = 18.0,
        dyPt: Double = 18.0,
        format: (page: Int, total: Int) -> String = { p, _ -> p.toString() }
    ): ExecResult {
        // basic guards
        require(Files.isRegularFile(input)) { "input PDF not found: $input" }
        require(fontSizePt > 0) { "fontSizePt must be > 0" }

        val total = countPages(input)

        // Pre-render the labels so PS uses a simple array of strings.
        // pdfmarkString(...) should already exist in your helpers; if not, escape like your other PS strings.
        val labels = (1..total).joinToString(" ") { pdfmarkString(format(it, total)) }

        val posCode = when (pos) {
            PageNumberPos.BottomLeft   -> "dx dy moveto"
            PageNumberPos.BottomRight  -> "w dx sub tw sub dy moveto"
            PageNumberPos.TopLeft      -> "dx h dy sub fs sub moveto"
            PageNumberPos.TopRight     -> "w dx sub tw sub h dy sub fs sub moveto"
            PageNumberPos.CenterBottom -> "w tw sub 2 div dy moveto"
            PageNumberPos.Custom       -> "dx dy moveto" // interpret dx/dy as absolute
        }

        // Robust EndPage: consume BOTH operands, test reason, return exactly one boolean.
        val ps = """
        /_wmPage 0 def
        /_labels [ $labels ] def
        /fs $fontSizePt def
        /dx $dxPt def /dy $dyPt def
        /PNdraw {
           gsave
             /$font findfont fs scalefont setfont
             _labels _wmPage 1 sub get /str exch def
             currentpagedevice /PageSize get aload pop /h exch def /w exch def
             str stringwidth pop /tw exch def
             $posCode
             0 setgray
             str show
           grestore
        } bind def

        << /EndPage {
            % stack: count reason  (reason is on top)
            /reason exch def    % reason -> name; stack now: count
            pop                 % drop count
            reason 2 eq {
                % device deactivation / param-change: don't emit a page
                false
            }{
                /_wmPage _wmPage 1 add def
                PNdraw
                true
            } ifelse
        } bind >> setpagedevice
    """.trimIndent()

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(output.toString()))
            .add(GsArg.PostScript(ps))
            .add(GsArg.Batch, GsArg.NoPause, GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt)
            .input(GsArg.FileInput(input))

        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    sealed interface Fit {
        data object FitToImage : Fit
        data class InsidePaper(
            val paper: String,              // e.g. "a4", "letter"
            val marginPt: Double = 36.0,    // points on each side
            val rotateToFit: Boolean = true
        ) : Fit
    }

    /**
     * Wrap JPEG images into a PDF (vector page, single JPEG per page).
     *
     * Implementation detail: we DO NOT call PostScript `file` at all. We stream each JPEG
     * as ASCII85 to stdin and read it with `currentfile /ASCII85Decode /DCTDecode ...`.
     * This avoids SAFER/permit headaches and works on macOS (/var vs /private/var) cleanly.
     */
    fun imagesToPdf(
        images: List<Path>,
        output: Path,
        dpi: Int = 300,
        fitMode: Fit = Fit.FitToImage,
        backgroundWhite: Boolean = true
    ): ExecResult {
        require(images.isNotEmpty()) { "images must not be empty" }
        require(dpi > 0) { "dpi must be > 0 (got $dpi)" }
        requireWritableTarget(output, "output PDF")
        requireReadableFiles(images, "input image")

        data class Img(val path: Path, val wPx: Int, val hPx: Int, val cs: String, val bytes: ByteArray)

        val infos = images.map { p ->
            val info = readJpegInfo(p) // validates JPEG + infers DeviceGray/RGB/CMYK
            Img(p, info.widthPx, info.heightPx, info.colorSpace, Files.readAllBytes(p))
        }

        val ps = buildString(infos.size * 256) {
            appendLine("%!PS-Adobe-3.0")
            for (img in infos) {
                val imgWpt = img.wPx * 72.0 / dpi
                val imgHpt = img.hPx * 72.0 / dpi

                data class Layout(
                    val pageW: Double, val pageH: Double,
                    val drawW: Double, val drawH: Double,
                    val tx: Double, val ty: Double, val rotate: Boolean
                )
                val lay: Layout = when (fitMode) {
                    Fit.FitToImage -> Layout(imgWpt, imgHpt, imgWpt, imgHpt, 0.0, 0.0, false)
                    is Fit.InsidePaper -> {
                        val (pw, ph) = paperSizePoints(fitMode.paper)
                            ?: error("Unknown paper '${fitMode.paper}'. Try: letter, legal, tabloid, a3, a4, a5")
                        val availW = (pw - 2 * fitMode.marginPt).coerceAtLeast(1.0)
                        val availH = (ph - 2 * fitMode.marginPt).coerceAtLeast(1.0)
                        val sNoRot = minOf(availW / imgWpt, availH / imgHpt)
                        val sRot   = minOf(availW / imgHpt, availH / imgWpt)
                        val useRot = fitMode.rotateToFit && (sRot > sNoRot)
                        val s = if (useRot) sRot else sNoRot
                        val drawW = if (useRot) imgHpt * s else imgWpt * s
                        val drawH = if (useRot) imgWpt * s else imgHpt * s
                        val tx = (pw - drawW) / 2.0
                        val ty = (ph - drawH) / 2.0
                        Layout(pw, ph, drawW, drawH, tx, ty, useRot)
                    }
                }

                // PostScript image uses the *current* colorspace (not a dict key)
                val (csName, decodeArray) = when (img.cs) {
                    "DeviceGray" -> "/DeviceGray" to "[0 1]"
                    "DeviceCMYK" -> "/DeviceCMYK" to "[0 1 0 1 0 1 0 1]"
                    else         -> "/DeviceRGB"  to "[0 1 0 1 0 1]"
                }

                appendLine("<< /PageSize [ ${lay.pageW} ${lay.pageH} ] >> setpagedevice")
                if (backgroundWhite) {
                    appendLine("gsave 1 setgray 0 0 moveto ${lay.pageW} 0 rlineto 0 ${lay.pageH} rlineto -${lay.pageW} 0 rlineto closepath fill grestore")
                }

                appendLine("gsave")
                appendLine("$csName setcolorspace")  // <-- critical fix per PLRM
                appendLine("${lay.tx} ${lay.ty} translate")
                if (lay.rotate) appendLine("90 rotate 0 -${lay.drawW} translate")

                // Valid ImageType 1 dict: no /ColorSpace key here
                appendLine(
                    "<< /ImageType 1 /Width ${img.wPx} /Height ${img.hPx} /BitsPerComponent 8 " +
                            "/Decode $decodeArray /ImageMatrix [ ${lay.drawW} 0 0 -${lay.drawH} 0 ${lay.drawH} ] " +
                            "/DataSource currentfile /ASCII85Decode filter /DCTDecode filter >> image"
                )

                // ASCII85 payload (terminated by ~>)
                ascii85Encode(img.bytes).forEach { appendLine(it) }

                appendLine("grestore")
                appendLine("showpage")
            }
        }

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(output.toString()))
            .add(GsArg.Quiet, GsArg.Batch, GsArg.NoPause, GsArg.NoPrompt)
            .add(GsArg.Safer)      // fine: no filesystem reads from PS; all via stdin
            .input(GsArg.StdIn)

        return Ghostscript().executeBlocking(cmd, stdinBytes = ps.toByteArray(Charsets.US_ASCII)).requireSuccess()
    }

    enum class AnnotationPolicy { FlattenAll, PreserveAll, PreserveLinksOnly }

    /**
     * Control annotation preservation. Note: PreserveLinksOnly uses -dPrinted=false as a heuristic;
     * behavior varies across GS versions and annotation types.
     */
    fun normalizeAnnotations(input: Path, output: Path, policy: AnnotationPolicy): ExecResult {
        requireReadableFile(input, "input PDF")
        requireWritableTarget(output, "output PDF")

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(output.toString()))
            .add(when (policy) {
                AnnotationPolicy.FlattenAll -> GsArg.DistillerBoolean("PreserveAnnots", false)
                AnnotationPolicy.PreserveAll -> GsArg.DistillerBoolean("PreserveAnnots", true)
                AnnotationPolicy.PreserveLinksOnly -> {
                    // Keep annots overall; ask GS not to print non-printed annots.
                    // Low–medium confidence: behavior is version-dependent.
                    // We still include the toggle while surfacing that it's a best-effort knob.
                    GsArg.D("Printed", "false")
                }
            })
            .add(GsArg.Batch, GsArg.NoPause, GsArg.Quiet, GsArg.Safer, GsArg.NoPrompt)
            .input(GsArg.FileInput(input))

        return Ghostscript().executeBlocking(cmd).requireSuccess()
    }

    /** ASCII85 encode into ~80‑char lines, *without* starting delimiter; appends `~>` at the end. */
    private fun ascii85Encode(data: ByteArray): List<String> {
        val out = StringBuilder((data.size * 5 + 3) / 4 + 32)
        var col = 0

        fun emit(ch: Char) {
            out.append(ch); col++
            if (col >= 80) { out.append('\n'); col = 0 }
        }

        var i = 0
        while (i < data.size) {
            val remain = minOf(4, data.size - i)
            var tuple = 0L
            for (j in 0 until remain) {
                tuple = (tuple shl 8) or ((data[i + j].toInt() and 0xFF).toLong())
            }
            if (remain < 4) tuple = tuple shl (8 * (4 - remain))

            if (remain == 4 && tuple == 0L) {
                emit('z')
            } else {
                val chars = CharArray(5)
                var v = tuple
                for (k in 4 downTo 0) { chars[k] = ((v % 85) + 33).toInt().toChar(); v /= 85 }
                // For partial groups, emit only (remain + 1) chars
                val n = remain + 1
                for (k in 0 until n) emit(chars[k])
            }
            i += remain
        }
        out.append("~>\n")
        return out.toString().lines()
    }
}
