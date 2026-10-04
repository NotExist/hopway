package not.exist.hopway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpInfoTest {
    @Test
    fun parsePublic() {
        val i = IpInfoRepository.parse(
            """{"ip":"203.0.113.10","city":"Tokyo","region":"Tokyo","country":"JP","loc":"35.6,139.6","org":"AS2516 KDDI","timezone":"Asia/Tokyo","readme":"https://ipinfo.io/missingauth"}""",
        )!!
        assertEquals("203.0.113.10", i.ip)
        assertEquals("JP", i.country)
        assertEquals("Tokyo", i.city)
        assertEquals("AS2516 KDDI", i.org)
    }

    @Test
    fun parseBogon() {
        val i = IpInfoRepository.parse("""{"ip":"192.168.1.2","bogon":true}""")!!
        assertTrue(i.bogon)
        assertNull(i.country)
    }

    @Test
    fun parseGarbage() {
        assertNull(IpInfoRepository.parse("<html>rate limited</html>"))
        assertNull(IpInfoRepository.parse("""{"error":{"title":"Wrong ip"}}"""))
    }

    @Test
    fun flags() {
        assertEquals("🇯🇵", flagEmoji("jp"))
        assertEquals("🇹🇼", flagEmoji("TW"))
        assertEquals("", flagEmoji(null))
        assertEquals("", flagEmoji("XYZ"))
    }
}
