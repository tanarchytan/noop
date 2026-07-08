package com.noop.net

import java.net.URI

/**
 * Single source of truth for the app's cleartext-HTTP policy.
 *
 * The network-security-config permits cleartext app-wide (Android XML can't scope a cleartext rule to
 * a CIDR), so THIS code is what actually keeps plain http:// off the public internet: https:// is
 * always fine; plain http:// is allowed ONLY to loopback / RFC1918 / link-local / *.local. Used by
 * both the AI coach's Custom (local LLM) provider and the noop-cloud link (pairing / firmware / coach),
 * so the rule lives in one place (#187).
 */
object NetGuard {

    /** Throw a precise, actionable error if [url] would send cleartext over the public internet. */
    fun requireLanOrHttps(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        val host = uri?.host
        val scheme = uri?.scheme?.lowercase()
        require(host != null && !scheme.isNullOrBlank()) {
            "That URL isn't valid. Use http://<host>:<port> for a local server, or https://… for a remote one."
        }
        if (scheme == "https") return
        require(scheme == "http") {
            "Unsupported URL scheme \"$scheme\". Use http:// for a local server or https:// for a remote one."
        }
        require(isPrivateLanOrLoopback(host)) {
            "Plain http:// is only allowed to a local-network server (localhost, 10.x, 172.16-31.x, " +
                "192.168.x, 169.254.x, or a .local name). Use https:// to reach \"$host\"."
        }
    }

    /**
     * True when [host] is on the device's own machine or its private LAN, so plain http:// to it never
     * crosses the public internet: loopback (localhost / 127.0.0.0/8 / ::1), RFC1918 (10/8, 172.16/12,
     * 192.168/16), link-local (169.254/16 / fe80::/10), the emulator alias 10.0.2.2, and *.local mDNS.
     */
    fun isPrivateLanOrLoopback(host: String): Boolean {
        val raw = host.trim()
        val h = raw.trim('[', ']').lowercase()  // strip IPv6 brackets if present
        if (h.isEmpty()) return false

        // IPv6 literal (bracketed, or contains a colon) vs a DNS host: only classify fc/fd/fe80 for a
        // real literal, else a public name like "fdn.example.com" would be wrongly allowed cleartext.
        val isIpv6Literal = raw.startsWith("[") || h.contains(':')
        if (isIpv6Literal) {
            if (h == "::1") return true
            if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return true
            return false
        }

        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h.endsWith(".local") && h.length > ".local".length) return true

        val parts = h.split(".")
        if (parts.size != 4) return false
        val octets = parts.map { it.toIntOrNull() ?: -1 }
        if (octets.any { it < 0 || it > 255 }) return false
        val a = octets[0]; val b = octets[1]
        return when {
            a == 127 -> true
            a == 10 -> true
            a == 172 && b in 16..31 -> true
            a == 192 && b == 168 -> true
            a == 169 && b == 254 -> true
            else -> false
        }
    }
}
