package one.wabbit.ghostscript

import kotlin.test.*
import java.nio.file.Files
import java.nio.file.Path

class ComplianceAndSecurityTests {

    @BeforeTest
    fun checkGs() = GsTestUtil.requireGhostscript()

    @Test
    fun testEncryptDecryptAndRedaction() {
        val dir = GsTestUtil.tmpDir()
        val inPdf = GsTestUtil.makePdf(1, dir)

        val enc = dir.resolve("enc.pdf")
        val encRes = Gs.encryptPdf(inPdf, enc, Gs.EncryptOptions(ownerPassword = "owner123", userPassword = "user123"))
        // Commands should be redacted:
        val red = encRes.redactedCommand.joinToString(" ")
        assertTrue("-sOwnerPassword=******" in red)
        assertTrue("-sUserPassword=******" in red)

        val dec = dir.resolve("dec.pdf")
        Gs.decryptPdf(enc, dec, password = "user123")
        assertEquals(1, Gs.countPages(dec))
    }

    @Test
    fun testPdfAIfDefProvided() {
        val dir = GsTestUtil.tmpDir()
        val def = GsTestUtil.envPath("GS_PDFA_DEF") ?: run {
            println("Skipping PDF/A: set env GS_PDFA_DEF to a readable PDFA_def.ps")
            return
        }
        val inPdf = GsTestUtil.makePdf(1, dir)
        val out = dir.resolve("a-1b.pdf")
        Gs.toPdfA(inPdf, out, pdfaLevel = 1, pdfaDefPs = def)
        GsTestUtil.assertNonEmpty(out)
    }

    @Test
    fun testPdfXIfDefProvided() {
        val dir = GsTestUtil.tmpDir()
        val def = GsTestUtil.envPath("GS_PDFX_DEF") ?: run {
            println("Skipping PDF/X: set env GS_PDFX_DEF to a readable PDFX_def.ps")
            return
        }
        val inPdf = GsTestUtil.makePdf(1, dir)
        val out = dir.resolve("x-1a.pdf")
        Gs.toPdfX(inPdf, out, pdfxDefPs = def)
        GsTestUtil.assertNonEmpty(out)
    }
}
