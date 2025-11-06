package one.wabbit.ghostscript

import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RasterExtractTests {
    @BeforeTest fun checkGs() = GsTestUtil.requireGhostscript()

    @Test
    fun testRenderToImagesAndGuard() {
        val dir = GsTestUtil.tmpDir()
        val two = GsTestUtil.makePdf(2, dir)

        // Guard: multi-page render must include %d
        val err =
            assertFailsWith<IllegalArgumentException> {
                Gs.renderToImages(two, dir.resolve("page.png").toString())
            }
        assertTrue(err.message!!.contains("%d"), "should complain about %d placeholder")

        val ok = Gs.renderToImages(two, dir.resolve("page-%03d.png").toString())
        val img1 = dir.resolve("page-001.png")
        val img2 = dir.resolve("page-002.png")
        assertTrue(Files.exists(img1) && Files.exists(img2))
    }

    @Test
    fun testExtractTextAndDocxIfAvailable() {
        val dir = GsTestUtil.tmpDir()
        val pdf = GsTestUtil.makePdf(2, dir)

        val txt = dir.resolve("out.txt")
        Gs.extractText(pdf, txt)
        val s = GsTestUtil.readText(txt)
        assertTrue(s.contains("Hello Page 1") || s.contains("Hello Page 2"))

        if (GsTestUtil.hasDevice("docxwrite")) {
            val docx = dir.resolve("out.docx")
            Gs.extractDocx(pdf, docx)
            GsTestUtil.assertNonEmpty(docx)
        }
    }

    @Test
    fun testImageOnlyPdfIfDeviceExists() {
        val devs = GsTestUtil.availableDevices()
        if (!devs.contains("pdfimage24")) {
            println("Skipping imageOnlyPdf: 'pdfimage24' not available in this Ghostscript build.")
            return
        }
        val dir = GsTestUtil.tmpDir()
        val pdf = GsTestUtil.makePdf(2, dir)
        val imgPdf = dir.resolve("imageonly.pdf")
        Gs.imageOnlyPdf(pdf, imgPdf, dpi = 72, depth = Gs.PdfImageDepth.Rgb24)
        assertEquals(Gs.countPages(pdf), Gs.countPages(imgPdf))
    }
}
