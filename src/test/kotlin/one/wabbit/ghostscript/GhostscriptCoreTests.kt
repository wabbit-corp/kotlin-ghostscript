package one.wabbit.ghostscript

import kotlin.test.*

class GhostscriptCoreTests {

    @BeforeTest
    fun checkGs() = GsTestUtil.requireGhostscript()

    @Test
    fun testAvailableDevicesAndExecBasics() {
        val devs = GsTestUtil.availableDevices()
        assertTrue(devs.isNotEmpty(), "Expected some Ghostscript devices")

        // Run a tiny NoDisplay program and inspect ExecResult fields.
        val cmd = GsCommand().add(
            GsArg.Quiet, GsArg.NoPrompt, GsArg.NoDisplay, GsArg.Safer,
            GsArg.PostScript("(%stdout smoke) = quit")
        )
        val res = Ghostscript().executeBlocking(cmd)
        assertEquals(0, res.exitCode)
        assertFalse(res.timedOut)
        assertTrue(res.durationMs >= 0)
        assertNotNull(res.redactedCommand)
        assertTrue(res.stdout.contains("smoke"), "stdout should contain marker (got: ${res.stdout.take(200)})")
    }

    @Test
    fun testRequireSuccessFailureMessageAndRedaction() {
        val dir = GsTestUtil.tmpDir()
        val pdf = GsTestUtil.makePdf(1, dir)

        // Trigger a failure with a nonsense device to validate error message content.
        val bad = GsCommand().add(
            GsArg.Device.Custom("definitely-not-a-device"),
            GsArg.OutputFile(dir.resolve("out.pdf").toString()),
            GsArg.Safer, GsArg.NoPrompt, GsArg.Quiet, GsArg.Batch, GsArg.NoPause
        ).input(GsArg.FileInput(pdf))
        val ex = assertFailsWith<IllegalStateException> {
            Ghostscript().executeBlocking(bad).requireSuccess()
        }
        val msg = ex.message ?: ""
        assertTrue(msg.contains("Ghostscript failed"), "message should announce failure")
        assertTrue(msg.contains("Command:"), "message should include redacted command")
        assertTrue(msg.contains("Working dir"), "message should include working dir or <inherit>")

        // Exercise redaction for encryption switches.
        val encOut = dir.resolve("enc.pdf")
        val r = Gs.encryptPdf(pdf, encOut, Gs.EncryptOptions(ownerPassword = "owner", userPassword = "user"))
        val redacted = r.redactedCommand.joinToString(" ")
        assertTrue(redacted.contains("-sOwnerPassword=******"))
        assertTrue(redacted.contains("-sUserPassword=******"))
    }
}
