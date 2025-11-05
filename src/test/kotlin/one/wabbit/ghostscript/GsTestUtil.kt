package one.wabbit.ghostscript

import kotlin.test.fail
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.name
import kotlin.math.min

/**
 * Test utilities for Ghostscript integration tests.
 *
 * These tests expect Ghostscript to be installed and on PATH (or GS/GSC env set),
 * and will bail with an explicit failure if not found.
 *
 * Some features (docxwrite, pdfimage*, xpswrite, PDF/A/X defs) are optional.
 * For those, tests return early with a clear message instead of failing.
 */
object GsTestUtil {

    fun tmpDir(prefix: String = "gs-tests-"): Path =
        Files.createTempDirectory(prefix).apply { toFile().deleteOnExit() }

    fun requireGhostscript() {
        try {
            Ghostscript.discoverExecutable()
        } catch (e: Throwable) {
            fail("Ghostscript not found for tests: ${e.message}")
        }
    }

    fun availableDevices(): Set<String> = try {
        Ghostscript().availableDevicesBlocking().toSet()
    } catch (e: Throwable) {
        emptySet()
    }

    fun hasDevice(name: String): Boolean = availableDevices().contains(name)

    fun envPath(name: String): Path? =
        System.getenv(name)?.let { Paths.get(it) }?.takeIf { Files.isReadable(it) }

    /** Make a simple N-page PDF with known text on each page using pdfwrite. */
    fun makePdf(pages: Int, rootDir: Path, name: String = "sample.pdf"): Path {
        require(pages >= 1)
        val out = rootDir.resolve(name)
        val ps = buildString {
            repeat(pages) { idx ->
                val n = idx + 1
                appendLine("<< /PageSize [595 842] >> setpagedevice")
                appendLine("/Helvetica findfont 18 scalefont setfont")
                appendLine("72 770 moveto (Hello Page $n) show")
                appendLine("0.0 setgray 100 100 moveto 300 0 rlineto 0 200 rlineto -300 0 rlineto closepath stroke")
                appendLine("showpage")
            }
        }

        val cmd = GsCommand()
            .add(GsArg.Device.PdfWrite)
            .add(GsArg.OutputFile(out.toString()))
            .add(GsArg.Quiet, GsArg.Batch, GsArg.NoPause, GsArg.NoPrompt, GsArg.Safer)
            .add(GsArg.PostScript(ps))

        Ghostscript().executeBlocking(cmd).requireSuccess()
        return out
    }

    /** Write simple UTF-8 text to a file. */
    fun writeText(path: Path, text: String) {
        Files.write(path, text.toByteArray(StandardCharsets.UTF_8))
    }

    /** Read small file to string (UTF-8). */
    fun readText(path: Path): String = Files.readAllBytes(path).toString(StandardCharsets.UTF_8)

    /** Produce a small RGB JPEG on disk. */
    fun makeJpeg(root: Path, name: String = "img.jpg", w: Int = 64, h: Int = 48, color: Color = Color(0x66CCFF)): Path {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color; g.fillRect(0, 0, w, h)
        g.color = Color.BLACK; g.drawString("GS", 5, h / 2)
        g.dispose()
        val out = root.resolve(name)
        ImageIO.write(img, "jpeg", out.toFile())
        out.toFile().deleteOnExit()
        return out
    }

    /** Quick file size > 0 assertion. */
    fun assertNonEmpty(path: Path) {
        val s = Files.size(path)
        if (s <= 0) fail("Expected non-empty file: $path")
    }

    /** One-line GS banner to confirm which build we’re hitting. */
    fun gsHeader(): String {
        return try {
            val p = ProcessBuilder(Ghostscript.discoverExecutable().toString(), "-h").start()
            val txt = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            txt.lineSequence().firstOrNull()?.trim().orEmpty()
        } catch (e: Throwable) {
            "Ghostscript header: <unavailable: ${e.message}>"
        }
    }

    /** Pretty log of a Ghostscript ExecResult (redacted command). */
    fun logExecResult(tag: String, r: ExecResult) {
        println(
            buildString {
                appendLine("==[ $tag ]====================================")
                appendLine("exitCode=${r.exitCode}")
                appendLine("redactedCommand: ${r.redactedCommand.joinToString(" ")}")
                if (r.stdout.isNotBlank()) {
                    appendLine("-- stdout${if (r.stdoutTruncated) " (truncated)" else ""} --")
                    appendLine(r.stdout.take(1200))
                } else appendLine("-- stdout: <empty>")
                if (r.stderr.isNotBlank()) {
                    appendLine("-- stderr${if (r.stderrTruncated) " (truncated)" else ""} --")
                    appendLine(r.stderr.take(1200))
                } else appendLine("-- stderr: <empty>")
                appendLine("==============================================")
            }
        )
    }

    /** Tiny hexdump for quick content sniffing. */
    fun hexDump(path: Path, maxBytes: Int = 512): String {
        val bytes = Files.readAllBytes(path)
        val n = min(bytes.size, maxBytes)
        val sb = StringBuilder()
        for (i in 0 until n) {
            if (i % 16 == 0) sb.append(String.format("%04X: ", i))
            sb.append(String.format("%02X ", bytes[i].toInt() and 0xFF))
            if (i % 16 == 15 || i == n - 1) sb.append('\n')
        }
        return sb.toString()
    }

    /** Quick PDF report: size, page count, header sniff + hex. */
    fun debugReport(pdf: Path, tag: String, outDir: Path) {
        println("== PDF DEBUG [$tag] ===========================")
        if (!pdf.exists()) {
            println("File missing: $pdf")
            return
        }
        val size = pdf.fileSize()
        println("File: ${pdf} (${size} bytes)")
        val pages = runCatching { Gs.countPages(pdf) }.getOrElse { -1 }
        println("Page count (pdfpagecount): $pages")

        val head = Files.readAllBytes(pdf).take(2048).toByteArray()
        val headStr = head.toString(Charsets.ISO_8859_1)
        println("-- Header sniff (2KB, ISO-8859-1) --")
        println(headStr)

        println("-- Hex dump (512B) --")
        println(hexDump(pdf, 512))
        println("==============================================")
    }

    /** Extract using multiple txtwrite modes; write files and return a map. */
    fun extractAllTextModes(pdf: Path, outDir: Path): Map<GsArg.TextFormatMode, String> {
        val out = linkedMapOf<GsArg.TextFormatMode, String>()
        for (mode in listOf(
            GsArg.TextFormatMode.Utf8Layout,
            GsArg.TextFormatMode.XmlRaw,
            GsArg.TextFormatMode.Ucs2Layout,
            GsArg.TextFormatMode.InternalDebug
        )) {
            val p = outDir.resolve("${pdf.name}.${mode.name}.txt")
            try {
                Gs.extractText(pdf, p, mode)
                val s = Files.readAllBytes(p).toString(
                    if (mode == GsArg.TextFormatMode.Ucs2Layout) Charsets.UTF_16 else Charsets.UTF_8
                )
                out[mode] = s
            } catch (e: Throwable) {
                out[mode] = "<ERROR: ${e.message}>"
            }
        }
        return out
    }

    /** Render a low-DPI PNG per page to prove pages exist visually. */
    fun rasterSmoke(pdf: Path, outDir: Path, dpi: Int = 96): List<Path> {
        val pattern = outDir.resolve("dbg-page-%03d.png").toString()
        val r = runCatching {
            Gs.renderToImages(
                input = pdf,
                outPattern = pattern,
                opt = Gs.RasterOptions(
                    device = GsArg.Device.Png16m,
                    dpi = dpi,
                    textAlphaBits = 4,
                    graphicsAlphaBits = 4
                )
            )
        }.onFailure { e ->
            println("rasterSmoke failed: ${e.message}")
        }
        if (r.isSuccess) {
            val pages = runCatching { Gs.countPages(pdf) }.getOrElse { 0 }
            return (1..pages).map { outDir.resolve(String.format("dbg-page-%03d.png", it)) }
                .filter { it.exists() }
        }
        return emptyList()
    }
}
