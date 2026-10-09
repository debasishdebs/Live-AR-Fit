import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseConfigTest {
    private val all = mapOf("LIVEAR_KEYSTORE" to "/k/upload.jks", "LIVEAR_KEY_ALIAS" to "upload", "LIVEAR_STORE_PASSWORD" to "s", "LIVEAR_KEY_PASSWORD" to "k")

    @Test fun propertiesOrEnvGiveARelease() {
        assertEquals(SigningResolution.Release(SigningInput("/k/upload.jks", "upload", "s", "k")), ReleaseSigning.resolve(all, emptyMap()))
        assertEquals(SigningResolution.Release(SigningInput("/k/upload.jks", "upload", "s", "k")), ReleaseSigning.resolve(emptyMap(), all))
    }

    @Test fun envWinsOverTheFile() {
        val r = ReleaseSigning.resolve(all, mapOf("LIVEAR_KEY_ALIAS" to "ci"))
        assertEquals("ci", (r as SigningResolution.Release).input.keyAlias)
    }

    @Test fun nothingConfiguredIsADiagnosticBuild() {
        assertEquals(SigningResolution.Diagnostic, ReleaseSigning.resolve(emptyMap(), emptyMap()))
        assertNull(ReleaseSigning.problem(SigningResolution.Diagnostic, requireSigning = false))
        assertNotNull(ReleaseSigning.problem(SigningResolution.Diagnostic, requireSigning = true), "the tag job never ships a diagnostic build")
    }

    /** Review focus 1: a CI secret that expands to "" (or a half-filled keystore.properties) fails loudly. */
    @Test fun partialOrBlankCredentialsAreAnErrorNotADiagnosticBuild() {
        assertEquals(SigningResolution.Misconfigured(listOf("LIVEAR_KEY_PASSWORD")), ReleaseSigning.resolve(all - "LIVEAR_KEY_PASSWORD", emptyMap()))
        val blank = ReleaseSigning.resolve(mapOf("LIVEAR_KEYSTORE" to "/k", "LIVEAR_KEY_ALIAS" to "a"), mapOf("LIVEAR_STORE_PASSWORD" to " "))
        assertEquals(SigningResolution.Misconfigured(listOf("LIVEAR_STORE_PASSWORD", "LIVEAR_KEY_PASSWORD")), blank)
        val p = assertNotNull(ReleaseSigning.problem(blank, requireSigning = false), "fails even without -Plivefit.requireSigning")
        assertTrue("LIVEAR_STORE_PASSWORD" in p)
    }

    @Test fun releaseTasksAreRecognisedPerProject() {
        assertTrue(ReleaseGate.requested(listOf(":phone:bundleRelease"), ":phone"))
        assertTrue(ReleaseGate.requested(listOf("assembleRelease"), ":watch"), "unqualified = every project")
        assertTrue(ReleaseGate.requested(listOf("assemble"), ":phone"))
        assertTrue(ReleaseGate.requested(listOf(":check16kb"), ":watch"))
        assertTrue(ReleaseGate.requested(listOf("check16kb"), ":phone"))
        assertFalse(ReleaseGate.requested(listOf("test", ":phone:assembleDebug", ":glasses:installDebug"), ":phone"))
    }

    /** Review focus 2: `:glasses:assembleRelease` configures the phone script too; it must not ask for the phone's tile key. */
    @Test fun anotherProjectsReleaseTaskDoesNotCount() {
        assertFalse(ReleaseGate.requested(listOf(":glasses:assembleRelease"), ":phone"))
        assertTrue(ReleaseGate.requested(listOf(":glasses:assembleRelease"), ":glasses"))
    }

    @Test fun tilesKeyFromEnvOrLocalProperties() {
        assertEquals("abcDEF123_-", TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to " abcDEF123_- ")))
        assertEquals("fromFile", TilesKey.resolve(mapOf("LIVEAR_TILES_KEY" to "fromFile"), emptyMap()))
        assertEquals("env", TilesKey.resolve(mapOf("LIVEAR_TILES_KEY" to "fromFile"), mapOf("LIVEAR_TILES_KEY" to "env")))
    }

    /** Review focus 2: an unset CI secret expands to an empty string; that is "missing", so a release build fails. */
    @Test fun blankTilesKeyIsMissing() {
        assertNull(TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to "")))
        assertNull(TilesKey.resolve(mapOf("LIVEAR_TILES_KEY" to "   "), emptyMap()))
        assertNull(TilesKey.resolve(emptyMap(), emptyMap()))
    }

    @Test fun aKeyThatWouldBreakBuildConfigIsRejected() {
        assertFailsWith<IllegalArgumentException> { TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to "ab\"c")) }
        assertFailsWith<IllegalArgumentException> { TilesKey.resolve(emptyMap(), mapOf("LIVEAR_TILES_KEY" to "a b")) }
    }
}
