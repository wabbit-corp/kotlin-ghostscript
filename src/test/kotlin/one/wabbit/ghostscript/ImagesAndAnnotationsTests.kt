package one.wabbit.ghostscript

import kotlin.test.*
import java.nio.file.Files

class ImagesAndAnnotationsTests {

    @BeforeTest
    fun checkGs() = GsTestUtil.requireGhostscript()

    @Test
    fun testImagesToPdfAndSplit() {
        val dir = GsTestUtil.tmpDir()
        val img1 = GsTestUtil.makeJpeg(dir, "i1.jpg", 80, 60)
        val img2 = GsTestUtil.makeJpeg(dir, "i2.jpg", 60, 80)
        val out = dir.resolve("imgs.pdf")
        Gs.imagesToPdf(listOf(img1, img2), out, dpi = 144, fitMode = Gs.Fit.FitToImage)
        assertEquals(2, Gs.countPages(out))

        val splitDirPattern = dir.resolve("page-%04d.pdf").toString()
        Gs.splitPages(out, splitDirPattern)
        assertTrue(Files.exists(dir.resolve("page-0001.pdf")))
        assertTrue(Files.exists(dir.resolve("page-0002.pdf")))
    }

    @Test
    fun testShiftContentAndAutoCrop() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(2, dir)
        val shifted = dir.resolve("shift.pdf")
        Gs.shiftContent(inPdf, shifted, dxPt = 20, dyPt = -10)
        GsTestUtil.assertNonEmpty(shifted)

        val cropped = dir.resolve("crop-auto.pdf")
        Gs.autoCropToContent(shifted, cropped, marginPt = 5)
        GsTestUtil.assertNonEmpty(cropped)
    }

    @Test
    fun testAnnotationPolicy() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(1, dir)

        val flat = dir.resolve("annots-flat.pdf")
        Gs.normalizeAnnotations(inPdf, flat, Gs.AnnotationPolicy.FlattenAll)
        GsTestUtil.assertNonEmpty(flat)

        val keep = dir.resolve("annots-keep.pdf")
        Gs.normalizeAnnotations(inPdf, keep, Gs.AnnotationPolicy.PreserveAll)
        GsTestUtil.assertNonEmpty(keep)
    }
}
