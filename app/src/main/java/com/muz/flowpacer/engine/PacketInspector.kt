package com.muz.flowpacer.engine

/**
 * Memeriksa header paket mentah IPv4 dari antaramuka TUN (Layer 3).
 * Mengasingkan paket kependaman kritikal (ICMP, DNS, Pure TCP ACK)
 * daripada paket muat naik data berat.
 */
object PacketInspector {
    const val PROTOCOL_ICMP: Byte = 1
    const val PROTOCOL_TCP: Byte = 6
    const val PROTOCOL_UDP: Byte = 17

    fun isHighPriority(packet: ByteArray, length: Int): Boolean {
        // Saiz minimum header IPv4 ialah 20 bait
        if (length < 20) return false

        // Sahkan versi paket IPv4 (4 bit pertama byte 0 mesti bernilai 4)
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return false

        val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
        if (length < ipHeaderLen) return false

        val protocol = packet[9]

        // 1. Paket ICMP (Ping) sentiasa keutamaan tertinggi
        if (protocol == PROTOCOL_ICMP) return true

        // 2. Paket UDP DNS (Port Destinasi 53)
        if (protocol == PROTOCOL_UDP && length >= (ipHeaderLen + 8)) {
            val destPort = ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                    (packet[ipHeaderLen + 3].toInt() and 0xFF)
            if (destPort == 53) return true
        }

        // 3. Paket TCP Pure ACK (Mengesahkan muat turun tanpa isi beban upload)
        if (protocol == PROTOCOL_TCP && length >= (ipHeaderLen + 20)) {
            val tcpHeaderLen = ((packet[ipHeaderLen + 12].toInt() ushr 4) and 0x0F) * 4
            val totalHeadersLen = ipHeaderLen + tcpHeaderLen

            // Jika saiz keseluruhan paket sama dengan saiz gabungan header (tiada payload data)
            if (length == totalHeadersLen) {
                val flags = packet[ipHeaderLen + 13].toInt() and 0xFF
                val isAck = (flags and 0x10) != 0 // Bit ke-4 mewakili flag ACK
                if (isAck) return true
            }
        }

        return false
    }
}
