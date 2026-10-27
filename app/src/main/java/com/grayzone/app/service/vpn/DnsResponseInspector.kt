package com.grayzone.app.service.vpn

/**
 * Reads the CNAME targets out of a raw DNS response payload (no IP/UDP headers), so the VPN can
 * refuse an allowed name that is only an alias for a known threat host.
 *
 * Pure and defensive: malformed, truncated or hostile packets (compression loops, labels running
 * past the end) yield an empty or partial list, never an exception.
 */
object DnsResponseInspector {

    private const val TYPE_CNAME = 5
    private const val MAX_POINTER_JUMPS = 32
    private const val MAX_NAME_LENGTH = 255
    private const val MAX_RECORDS = 64

    fun cnameTargets(payload: ByteArray): List<String> {
        if (payload.size < 12) return emptyList()
        val qdCount = u16(payload, 4)
        val anCount = u16(payload, 6).coerceAtMost(MAX_RECORDS)
        var pos = 12
        repeat(qdCount.coerceAtMost(MAX_RECORDS)) {
            pos = skipName(payload, pos) ?: return emptyList()
            pos += 4
            if (pos > payload.size) return emptyList()
        }
        val targets = ArrayList<String>(2)
        repeat(anCount) {
            pos = skipName(payload, pos) ?: return targets
            if (pos + 10 > payload.size) return targets
            val type = u16(payload, pos)
            val rdLength = u16(payload, pos + 8)
            val rdStart = pos + 10
            if (rdStart + rdLength > payload.size) return targets
            if (type == TYPE_CNAME) {
                readName(payload, rdStart)?.let { targets += it }
            }
            pos = rdStart + rdLength
        }
        return targets
    }

    /** Position just past the (possibly compressed) name at [start], or null if malformed. */
    private fun skipName(data: ByteArray, start: Int): Int? {
        var pos = start
        while (true) {
            if (pos >= data.size) return null
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                len and 0xC0 == 0xC0 -> return if (pos + 2 <= data.size) pos + 2 else null
                len and 0xC0 != 0 -> return null
                else -> pos += 1 + len
            }
        }
    }

    /** Decodes the name at [start] following compression pointers, lowercased without the root dot. */
    private fun readName(data: ByteArray, start: Int): String? {
        val sb = StringBuilder()
        var pos = start
        var jumps = 0
        while (true) {
            if (pos >= data.size) return null
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> return sb.toString().lowercase().takeIf { it.isNotEmpty() }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= data.size || ++jumps > MAX_POINTER_JUMPS) return null
                    pos = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > data.size) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in pos + 1..pos + len) sb.append((data[i].toInt() and 0xFF).toChar())
                    if (sb.length > MAX_NAME_LENGTH) return null
                    pos += 1 + len
                }
            }
        }
    }

    private fun u16(data: ByteArray, pos: Int): Int =
        ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
}
