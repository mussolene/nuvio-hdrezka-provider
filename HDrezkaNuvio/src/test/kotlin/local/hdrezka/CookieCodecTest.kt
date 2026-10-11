package local.hdrezka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieCodecTest {
    @Test
    fun roundTripPreservesSessionCookies() {
        val cookies = mapOf(
            "dle_user_id" to "42",
            "dle_password" to "hash=value",
            "PHPSESSID" to "session"
        )

        assertEquals(cookies, CookieCodec.decode(CookieCodec.encode(cookies)))
    }

    @Test
    fun malformedEntriesAreIgnored() {
        assertEquals(
            mapOf("dle_user_id" to "42"),
            CookieCodec.decode("broken; =empty; dle_user_id=42")
        )
    }

    @Test
    fun valuesMayContainEqualsCharacters() {
        assertEquals(
            mapOf("token" to "part=part=="),
            CookieCodec.decode("token=part=part==")
        )
    }

    @Test
    fun anubisProofHasRequestedPrefix() {
        val solution = AnubisProofOfWork.solve("nuvio-test", 3)

        assertTrue(solution.hash.startsWith("000"))
        assertTrue(solution.nonce >= 0)
    }
}
