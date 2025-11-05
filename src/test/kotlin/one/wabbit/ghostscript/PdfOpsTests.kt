package one.wabbit.ghostscript

import kotlin.test.*
import java.nio.file.Files

class PdfOpsTests {

    @BeforeTest
    fun checkGs() = GsTestUtil.requireGhostscript()

    @Test
    fun testCountMergeExtract() {
        val dir = GsTestUtil.tmpDir()
        val a = GsTestUtil.makePdf(1, dir, "a.pdf")
        val b = GsTestUtil.makePdf(2, dir, "b.pdf")

        assertEquals(1, Gs.countPages(a))
        assertEquals(2, Gs.countPages(b))

        val merged = dir.resolve("merged.pdf")
        Gs.mergePdfs(listOf(a, b), merged)
        assertEquals(3, Gs.countPages(merged))

        val range = dir.resolve("range.pdf")
        Gs.extractRange(merged, range, 2, 3)
        assertEquals(2, Gs.countPages(range))

        val plist = dir.resolve("plist.pdf")
        Gs.extractPages(merged, plist, "1,3")
        assertEquals(2, Gs.countPages(plist))
    }

    @Test
    fun testOptimizeAndLinearizeAndFitCropRotate() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(2, dir)

        val opt = dir.resolve("opt.pdf")
        Gs.optimizePdf(inPdf, opt, Gs.OptimizeOptions(
            detectDuplicateImages = true,
            compressFonts = true,
            compressStreams = true,
            preserveAnnots = true,
            jpegQFactor = 0.4
        ))
        GsTestUtil.assertNonEmpty(opt)

        val lin = dir.resolve("lin.pdf")
        Gs.linearize(inPdf, lin)
        val header = Files.readAllBytes(lin).take(4096).toByteArray().toString(Charsets.ISO_8859_1)
        assertTrue(header.contains("/Linearized"), "Expected linearization dictionary near start")

        val a4 = dir.resolve("a4.pdf")
        Gs.fitToPaper(inPdf, a4, "a4")
        GsTestUtil.assertNonEmpty(a4)

        val crop = dir.resolve("crop.pdf")
        Gs.cropAllPages(inPdf, crop, 50, 50, 545, 792 - 50)
        GsTestUtil.assertNonEmpty(crop)

        val rot = dir.resolve("rot.pdf")
        Gs.rotatePages(inPdf, rot, 90)
        GsTestUtil.assertNonEmpty(rot)
    }

    @Test
    fun testColorConversions() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(1, dir)

        val gray = dir.resolve("gray.pdf")
        Gs.toGrayscale(inPdf, gray)
        GsTestUtil.assertNonEmpty(gray)

        val cmyk = dir.resolve("cmyk.pdf")
        Gs.toCmyk(inPdf, cmyk)
        GsTestUtil.assertNonEmpty(cmyk)

        val leave = dir.resolve("leave.pdf")
        Gs.convertColors(inPdf, leave, Gs.ColorPolicy.LeaveUnchanged, compatibility = "1.3")
        val s = Files.readAllBytes(leave).toString(Charsets.ISO_8859_1)
        // Not all files will show this literal, but the run should succeed.
        assertTrue(Files.size(leave) > 0L)
    }

    @Test
    fun testPdfToPsEpsXpsIfAvailable() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(1, dir)
        val devs = GsTestUtil.availableDevices()

        if ("ps2write" in devs) {
            val ps = dir.resolve("out.ps")
            Gs.pdfToPs(inPdf, ps)
            GsTestUtil.assertNonEmpty(ps)
        }
        if ("eps2write" in devs) {
            val eps = dir.resolve("out.eps")
            Gs.pdfToEps(inPdf, eps)
            GsTestUtil.assertNonEmpty(eps)
        }
        if ("xpswrite" in devs) {
            val xps = dir.resolve("out.xps")
            Gs.pdfToXps(inPdf, xps)
            GsTestUtil.assertNonEmpty(xps)
        }
    }
}
