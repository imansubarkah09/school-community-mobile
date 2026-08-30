package space.schoolcommunity.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckerTest {

    private fun release(
        versionCode: Long,
        minSupported: Long = 0,
        force: Boolean = false,
    ) = Release("x", versionCode, minSupported, "https://x/a.apk", null, emptyList(), force)

    @Test fun noReleaseIsNone() {
        assertEquals(UpdateType.NONE, UpdateDecision.of(5, null).type)
    }

    @Test fun latestEqualOrOlderIsNone() {
        assertEquals(UpdateType.NONE, UpdateDecision.of(5, release(5)).type)
        assertEquals(UpdateType.NONE, UpdateDecision.of(5, release(4)).type)
    }

    @Test fun newerNotForcedAboveMinimumIsOptional() {
        assertEquals(UpdateType.OPTIONAL, UpdateDecision.of(5, release(6, minSupported = 3)).type)
    }

    @Test fun forceUpdateIsMandatory() {
        assertEquals(UpdateType.MANDATORY, UpdateDecision.of(5, release(6, force = true)).type)
    }

    @Test fun installedBelowMinimumSupportedIsMandatory() {
        assertEquals(UpdateType.MANDATORY, UpdateDecision.of(5, release(9, minSupported = 6)).type)
    }

    @Test fun parseRejectsInvalidPayloads() {
        assertNull(ReleaseApi.parse("""{"apkUrl":"https://x/a.apk"}"""))
        assertNull(ReleaseApi.parse("""{"versionCode":2,"apkUrl":"http://x/a.apk"}"""))
        assertNull(ReleaseApi.parse("not json"))
    }

    @Test fun parseReadsDocumentedContract() {
        val r = ReleaseApi.parse(
            """
            {"platform":"android","versionName":"1.2.0","versionCode":3,
             "minimumSupportedVersion":2,"apkUrl":"https://github.com/x/school-community-mobile/releases/download/v1.2.0/school-community-1.2.0.apk",
             "fileName":"school-community-1.2.0.apk","releaseNotes":["Perbaikan bug",""],
             "forceUpdate":true}
            """.trimIndent(),
        )!!
        assertEquals(3L, r.versionCode)
        assertEquals("1.2.0", r.versionName)
        assertEquals(2L, r.minimumSupportedVersion)
        assertEquals(listOf("Perbaikan bug"), r.releaseNotes)
        assertEquals(true, r.forceUpdate)
    }
}
