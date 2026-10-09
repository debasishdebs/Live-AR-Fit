import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LiveFitVersionTest {
    @Test fun schemeGivesEachFormFactorItsOwnCode() {
        assertEquals(1_000_000, LiveFitVersion.code("1.0.0", LiveFitVersion.FormFactor.Phone))
        assertEquals(1_000_001, LiveFitVersion.code("1.0.0", LiveFitVersion.FormFactor.Watch))
        assertEquals(1_000_002, LiveFitVersion.code("1.0.0", LiveFitVersion.FormFactor.Glasses))
        assertEquals(2_134_501, LiveFitVersion.code("2.13.45", LiveFitVersion.FormFactor.Watch))
    }

    @Test fun preReleaseSuffixDoesNotChangeTheCode() =
        assertEquals(20_000, LiveFitVersion.code("0.2.0-beta", LiveFitVersion.FormFactor.Phone))

    @Test fun everyLaterVersionHasAHigherCode() {
        val ordered = listOf("0.1.0", "0.2.0", "0.2.1", "0.10.0", "1.0.0", "1.0.1", "1.1.0", "2.0.0")
        val codes = ordered.map { LiveFitVersion.code(it, LiveFitVersion.FormFactor.Glasses) }
        assertEquals(codes.sorted(), codes)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun malformedOrOutOfRangeVersionsFailTheBuild() {
        for (bad in listOf("1.0", "v1.0.0", "1.0.0.0", "1.100.0", "1.0.100", "2100.0.0", " 1.0.0", ""))
            assertFailsWith<IllegalArgumentException>(bad) { LiveFitVersion.code(bad, LiveFitVersion.FormFactor.Phone) }
    }
}
