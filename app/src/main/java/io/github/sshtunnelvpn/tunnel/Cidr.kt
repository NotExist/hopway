package io.github.sshtunnelvpn.tunnel

import java.math.BigInteger
import java.net.InetAddress

/** IPv4 / IPv6 網段;以 BigInteger 統一處理兩種位址族。 */
data class Cidr(val address: InetAddress, val prefix: Int) {
    val bits: Int get() = address.address.size * 8
    val start: BigInteger get() = BigInteger(1, address.address).and(mask(bits, prefix))
    val end: BigInteger get() = start + BigInteger.ONE.shiftLeft(bits - prefix) - BigInteger.ONE

    override fun toString() = "${address.hostAddress}/$prefix"

    companion object {
        private val V4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun parse(s: String): Cidr? {
            val t = s.trim()
            if (t.isEmpty()) return null
            val parts = t.split('/')
            if (parts.size > 2) return null
            // 只接受字面 IP,避免 InetAddress.getByName 觸發 DNS 查詢
            val host = parts[0]
            val isV4 = V4.matches(host)
            val isV6 = ':' in host && host.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
            if (!isV4 && !isV6) return null
            val addr = runCatching { InetAddress.getByName(parts[0]) }.getOrNull() ?: return null
            val bits = addr.address.size * 8
            val prefix = parts.getOrNull(1)?.toIntOrNull() ?: bits
            if (prefix !in 0..bits) return null
            return of(addr, prefix)
        }

        fun of(addr: InetAddress, prefix: Int): Cidr {
            val bits = addr.address.size * 8
            val net = BigInteger(1, addr.address).and(mask(bits, prefix))
            return Cidr(toInet(net, bits), prefix)
        }

        fun parseList(s: String): List<Cidr> = s.split(',', '\n', ' ', ';').mapNotNull(::parse)

        private fun mask(bits: Int, prefix: Int): BigInteger {
            val all = BigInteger.ONE.shiftLeft(bits) - BigInteger.ONE
            val host = BigInteger.ONE.shiftLeft(bits - prefix) - BigInteger.ONE
            return all.xor(host)
        }

        internal fun toInet(v: BigInteger, bits: Int): InetAddress {
            val raw = v.toByteArray()
            val out = ByteArray(bits / 8)
            val n = minOf(raw.size, out.size)
            System.arraycopy(raw, raw.size - n, out, out.size - n, n)
            return InetAddress.getByAddress(out)
        }

        /**
         * 回傳 [base] 扣掉 [excluded] 後的最小 CIDR 集合。
         * API 33 以前 VpnService.Builder 沒有 excludeRoute,只能自己算補集。
         */
        fun subtract(base: Cidr, excluded: List<Cidr>): List<Cidr> {
            val bits = base.bits
            val ex = excluded.filter { it.bits == bits }
                .map { it.start to it.end }
                .sortedBy { it.first }
            val out = mutableListOf<Cidr>()
            var cur = base.start
            val last = base.end
            for ((s, e) in ex) {
                if (e < cur || s > last) continue
                if (s > cur) out += rangeToCidrs(cur, s - BigInteger.ONE, bits)
                if (e + BigInteger.ONE > cur) cur = e + BigInteger.ONE
                if (cur > last) break
            }
            if (cur <= last) out += rangeToCidrs(cur, last, bits)
            return out
        }

        private fun rangeToCidrs(from: BigInteger, to: BigInteger, bits: Int): List<Cidr> {
            val out = mutableListOf<Cidr>()
            var start = from
            while (start <= to) {
                // 起點對齊允許的最大區塊
                var size = if (start.signum() == 0) bits else start.lowestSetBit
                size = minOf(size, bits)
                while (size > 0 && start + BigInteger.ONE.shiftLeft(size) - BigInteger.ONE > to) size--
                out += Cidr(toInet(start, bits), bits - size)
                start += BigInteger.ONE.shiftLeft(size)
            }
            return out
        }
    }
}

/** 要交給 VpnService.Builder 的路由:[include] 走 addRoute,[exclude] 走 excludeRoute(僅 API 33+)。 */
data class RoutePlan(val include: List<Cidr>, val exclude: List<Cidr>)

object Routes {
    // loopback 本來就不會進 VPN,不需要排除;而且 Builder 遇到 loopback 位址會丟 "Bad address"
    val LAN_V4 = listOf(
        "10.0.0.0/8", "100.64.0.0/10", "169.254.0.0/16", "172.16.0.0/12",
        "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
    ).mapNotNull(Cidr::parse)
    val LAN_V6 = listOf("fc00::/7", "fe80::/10", "ff00::/8").mapNotNull(Cidr::parse)
    val ALL_V4 = Cidr.parse("0.0.0.0/0")!!
    val ALL_V6 = Cidr.parse("::/0")!!
    private val LOOPBACK_V4 = Cidr.parse("127.0.0.0/8")!!

    /** 對應 VpnService.Builder 內部的 check():loopback 位址一律被拒(IllegalArgumentException "Bad address")。 */
    fun acceptedByBuilder(c: Cidr): Boolean = !c.address.isLoopbackAddress

    /**
     * 依 API 等級規劃路由。API 33+ 用 excludeRoute;更舊的版本只能 addRoute,自行計算補集
     * (補集計算時順便扣掉 loopback,避免產生以 127.x 為起點的路由)。
     */
    fun plan(sdkInt: Int, all: Cidr, exclude: List<Cidr>): RoutePlan {
        val ex = exclude.filter { it.bits == all.bits }
        return if (sdkInt >= 33) {
            RoutePlan(listOf(all), ex.filter(::acceptedByBuilder))
        } else {
            val withLoopback = if (all.bits == 32) ex + LOOPBACK_V4 else ex
            RoutePlan(Cidr.subtract(all, withLoopback).filter(::acceptedByBuilder), emptyList())
        }
    }
}
