package not.exist.hopway.tunnel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import not.exist.hopway.data.AuthType
import not.exist.hopway.data.Credential
import not.exist.hopway.data.OutboundType
import not.exist.hopway.data.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineConfigTest {
    private fun json(p: Profile) = Json.parseToJsonElement(EngineConfig.json(p, null, null)).jsonObject

    @Test
    fun sshConfig() {
        val o = json(Profile(host = "h", port = 2222, username = "u", authType = AuthType.PASSWORD, password = "pw", connections = 3))
        assertEquals("ssh", o["type"]!!.jsonPrimitive.content)
        assertEquals("u", o["user"]!!.jsonPrimitive.content)
        assertEquals("3", o["connections"]!!.jsonPrimitive.content)
        assertFalse(o.containsKey("privateKey"))
    }

    @Test
    fun socksConfigHasNoSshFields() {
        val anon = json(Profile(type = OutboundType.SOCKS5, host = "proxy", port = 1080, connections = 3, udpgwEnabled = true))
        assertEquals("socks5", anon["type"]!!.jsonPrimitive.content)
        for (k in listOf("user", "password", "privateKey", "connections", "udpgw")) assertFalse(k, anon.containsKey(k))
        val auth = json(Profile(type = OutboundType.SOCKS5, host = "proxy", port = 1080, username = "alice", password = "s"))
        assertEquals("alice", auth["user"]!!.jsonPrimitive.content)
        assertEquals("s", auth["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun socksProfileDisplay() {
        val p = Profile(type = OutboundType.SOCKS5, host = "proxy.example", port = 1080)
        assertEquals("socks5://proxy.example:1080", p.endpoint)
        assertEquals("proxy.example:1080", p.displayName)
        assertEquals("socks5://alice@proxy.example:1080", p.copy(username = "alice").endpoint)
        // SOCKS5 帳密選填,不會被標成缺帳密
        assertTrue(p.missingCredentials.isEmpty())
        assertEquals(setOf(Credential.PASSWORD), p.copy(type = OutboundType.SSH).missingCredentials)
    }
}
