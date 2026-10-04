package not.exist.hopway.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class CidrTest {
    private fun size(c: Cidr) = BigInteger.ONE.shiftLeft(c.bits - c.prefix)

    @Test
    fun parse() {
        assertEquals("10.0.0.0/8", Cidr.parse("10.1.2.3/8").toString())
        assertEquals("1.2.3.4/32", Cidr.parse("1.2.3.4").toString())
        assertEquals(64, Cidr.parse("2001:db8::/64")!!.prefix)
        assertNull(Cidr.parse("example.com/24"))
        assertNull(Cidr.parse("10.0.0.0/33"))
        assertNull(Cidr.parse(""))
    }

    @Test
    fun subtractCoversExactlyTheComplement() {
        val all = Routes.ALL_V4
        val out = Cidr.subtract(all, Routes.LAN_V4)
        // 不重疊、遞增、且總大小 = 全部 - 排除
        val excluded = Routes.LAN_V4.fold(BigInteger.ZERO) { a, c -> a + size(c) }
        val covered = out.fold(BigInteger.ZERO) { a, c -> a + size(c) }
        assertEquals(size(all) - excluded, covered)
        out.zipWithNext().forEach { (a, b) -> assertTrue(a.end < b.start) }
        for (ex in Routes.LAN_V4) for (r in out) assertTrue(r.end < ex.start || r.start > ex.end)
        // 最小 CIDR 集合大小(以 Python ipaddress.collapse_addresses 交叉驗證)
        assertEquals(74, out.size)
    }

    /** 回歸測試:Android 13+ 的 excludeRoute(127.0.0.0/8) 曾讓 Builder 丟 "Bad address"。 */
    @Test
    fun planNeverHandsLoopbackToBuilder() {
        val custom = Cidr.parseList("127.0.0.1/32, 203.0.113.0/24")
        for (sdk in listOf(26, 32, 33, 36)) {
            for ((all, lan) in listOf(Routes.ALL_V4 to Routes.LAN_V4, Routes.ALL_V6 to Routes.LAN_V6)) {
                val plan = Routes.plan(sdk, all, lan + custom)
                (plan.include + plan.exclude).forEach {
                    assertTrue("sdk $sdk: $it rejected by Builder", Routes.acceptedByBuilder(it))
                }
                if (sdk < 33) assertTrue(plan.exclude.isEmpty())
            }
        }
        // API < 33:補集同時扣掉 loopback,最小集合 = 77(LAN + 127/8)
        assertEquals(77, Routes.plan(32, Routes.ALL_V4, Routes.LAN_V4).include.size)
        // API 33+:只有一條 0.0.0.0/0,排除清單不含 loopback
        val p33 = Routes.plan(33, Routes.ALL_V4, Routes.LAN_V4 + custom)
        assertEquals(listOf(Routes.ALL_V4), p33.include)
        assertTrue(p33.exclude.none { it.address.isLoopbackAddress })
        assertTrue(p33.exclude.any { it.toString() == "203.0.113.0/24" })
    }

    @Test
    fun subtractSingleHost() {
        val out = Cidr.subtract(Routes.ALL_V4, listOf(Cidr.parse("1.1.1.1")!!))
        assertEquals(32, out.size)
        assertEquals("0.0.0.0/8", out.first().toString())
        assertEquals("128.0.0.0/1", out.last().toString())
    }

    @Test
    fun subtractIpv6IgnoresV4AndOverlaps() {
        val ex = Routes.LAN_V6 + Cidr.parse("fd00::/8")!! + Cidr.parse("10.0.0.0/8")!!
        val out = Cidr.subtract(Routes.ALL_V6, ex)
        val covered = out.fold(BigInteger.ZERO) { a, c -> a + size(c) }
        val removed = Routes.LAN_V6.fold(BigInteger.ZERO) { a, c -> a + size(c) }
        assertEquals(size(Routes.ALL_V6) - removed, covered)
    }
}
