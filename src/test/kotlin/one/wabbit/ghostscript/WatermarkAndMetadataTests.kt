package one.wabbit.ghostscript

import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatermarkAndMetadataTests {
    @BeforeTest fun checkGs() = GsTestUtil.requireGhostscript()

    @Test
    fun testTextWatermarkAndExtraction() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(1, dir)
        val out = dir.resolve("wm.pdf")

        println("GS: ${GsTestUtil.gsHeader()}")
        println(
            "Devices (first 12): ${GsTestUtil.availableDevices().take(12).sorted().joinToString()}"
        )

        // Baseline extraction on input
        val inTxt = dir.resolve("in.txt")
        Gs.extractText(inPdf, inTxt)
        val s0 = GsTestUtil.readText(inTxt)
        println("Text BEFORE watermarking (Utf8Layout):\n${s0.take(400)}")

        val mark = "WMTEST-Δ"

        // Run watermark with current defaults; capture ExecResult for logging.
        val res = Gs.watermarkTextFlattened(inPdf, out, text = mark, fontSizePt = 24)
        GsTestUtil.logExecResult("watermarkTextFlattened", res)

        // Confirm we emitted pages
        val outPages = runCatching { Gs.countPages(out) }.getOrElse { -1 }
        println("Output page count: $outPages")
        GsTestUtil.debugReport(out, "after-watermark", dir)

        // Render at low DPI to confirm a visible page exists
        val thumbs = GsTestUtil.rasterSmoke(out, dir, dpi = 96)
        println("Raster thumbnails: ${thumbs.joinToString { it.fileName.toString() }}")

        // Try ALL text modes to diagnose encoding/ToUnicode issues
        val all = GsTestUtil.extractAllTextModes(out, dir)
        all.forEach { (mode, text) ->
            println("---- txtwrite mode=$mode ----")
            println(text.take(400))
            println("-----------------------------")
        }

        // Original assertion (kept). If this fails, the logs above will tell us why.
        val txt = dir.resolve("wm.txt")
        Gs.extractText(out, txt, mode = GsArg.TextFormatMode.Utf8Layout)
        val s = GsTestUtil.readText(txt)
        println("Text AFTER watermarking (Utf8Layout):\n${s.take(400)}")
        assertTrue(
            s.contains("WMTEST") || s.contains("Δ"),
            "Expected watermark text in extraction; got:\n${s.take(400)}",
        )
    }

    @Test
    fun testImageWatermark() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(1, dir)
        val logo = GsTestUtil.makeJpeg(dir, "logo.jpg", 64, 64)
        val out = dir.resolve("wmimg.pdf")
        Gs.watermarkImageFlattened(
            inPdf,
            out,
            logo,
            drawWidthPt = 36.0,
            drawHeightPt = 36.0,
            rotationDeg = 0.0,
            anchor = Gs.Anchor.Absolute(36.0, 36.0),
        )
        GsTestUtil.assertNonEmpty(out)
    }

    @Test
    fun testDocInfoAndMergeBookmarks() {
        val dir = GsTestUtil.tmpDir()
        val a = GsTestUtil.makePdf(1, dir, "a.pdf")
        val b = GsTestUtil.makePdf(1, dir, "b.pdf")

        val infoOut = dir.resolve("info.pdf")
        Gs.setDocInfo(
            a,
            infoOut,
            Gs.DocInfo(
                title = "My Fancy Title — ü",
                author = "Author Æ",
                subject = "Subject",
                keywords = "k1, k2",
            ),
            openWithOutlines = true,
            pageLayout = "SinglePage",
        )
        val bytes = Files.readAllBytes(infoOut).toString(Charsets.ISO_8859_1)
        assertTrue(
            bytes.contains("My Fancy Title") || bytes.contains("\uFEFF"),
            "DOCINFO should be present",
        )

        val merged = dir.resolve("merged-bm.pdf")
        Gs.mergeWithBookmarks(listOf(a, b), merged, titles = listOf("First", "Second"))
        assertEquals(2, Gs.countPages(merged))
    }

    @Test
    fun testPageNumbers() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(3, dir)
        val out = dir.resolve("numbered.pdf")
        Gs.addPageNumbers(inPdf, out, fontSizePt = 12, pos = Gs.PageNumberPos.BottomRight)
        val txt = dir.resolve("numbered.txt")
        Gs.extractText(out, txt)
        val s = GsTestUtil.readText(txt)
        assertTrue(s.contains("1") && s.contains("2"), "Expected page numbers in extracted text")
    }
}
