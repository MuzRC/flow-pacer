package com.muz.flowpacer.engine

import java.net.InetAddress

/**
 * Memeriksa dan menganalisis header paket mentah IPv4 dari antaramuka TUN (Layer 3).
 * Mengasingkan paket kependaman kritikal (Permua UDP, ICMP, DNS, TCP Handshake/ACK)
 * daripada limpahan trafik muat naik data berat.
 */
object PacketInspector {
    const val PROTOCOL_ICMP: Byte = 1
    const val PROTOCOL_TCP: Byte = 6
    const val PROTOCOL_UDP: Byte = 17

    /**
     * Menyemak sama ada sesuatu paket layak mendapat laluan pintas keutamaan tinggi (Fast-Path).
     */
    fun isHighPriority(packet: ByteArray, length: Int): Boolean {
        // 1. Saiz minimum header asas IPv4 mesti sekurang-kurangnya 20 bait
        if (length < 20) return false

        // 2. Sahkan versi IPv4 (4 bit pertama byte 0 mesti angka 4)
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return false

        // 3. Sahkan Internet Header Length (IHL) - minimum 5 perkataan 32-bit (20 bait)
        val ihl = packet[0].toInt() and 0x0F
        val ipHeaderLen = ihl * 4
        if (ihl < 5 || length < ipHeaderLen) return false

        val protocol = packet[9]

        // Keutamaan 1: Paket ICMP (Ping & Trace) sentiasa keutamaan tertinggi
        if (protocol == PROTOCOL_ICMP) return true

        // Keutamaan 2: Paket UDP (DNS, NTP & Paket Denyut Permua / Game State)
        if (protocol == PROTOCOL_UDP && length >= (ipHeaderLen + 8)) {
            val srcPort = getPort(packet, ipHeaderLen)
            val destPort = getPort(packet, ipHeaderLen + 2)

            // DNS (53), NTP Masa (123)
            if (destPort == 53 || srcPort == 53 || destPort == 123 || srcPort == 123) {
                return true
            }

            // Paket permua masa nyata biasanya padat dan ringan (<= 512 bait)
            // Luluskan terus tanpa delay supaya ping dalam permua kekal hijau
            if (length <= 512) {
                return true
            }
        }

        // Keutamaan 3: Paket Kawalan TCP (SYN, FIN, RST) & Pure TCP ACK
        if (protocol == PROTOCOL_TCP && length >= (ipHeaderLen + 20)) {
            val tcpDataOffset = (packet[ipHeaderLen + 12].toInt() ushr 4) and 0x0F
            val tcpHeaderLen = tcpDataOffset * 4
            val totalHeadersLen = ipHeaderLen + tcpHeaderLen

            if (tcpDataOffset >= 5 && length >= totalHeadersLen) {
                val flags = packet[ipHeaderLen + 13].toInt() and 0xFF
                val isSyn = (flags and 0x02) != 0
                val isRst = (flags and 0x04) != 0
                val isFin = (flags and 0x01) != 0
                val isAck = (flags and 0x10) != 0

                // Jika tiada payload data muat naik (hanya paket jabat tangan atau ACK pengesahan)
                if (length == totalHeadersLen) {
                    if (isAck || isSyn || isRst || isFin) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Membaca nombor port 16-bit (Big Endian) dari ofset tertentu.
     */
    fun getPort(packet: ByteArray, offset: Int): Int {
        if (offset + 1 >= packet.size) return 0
        return ((packet[offset].toInt() and 0xFF) shl 8) or (packet[offset + 1].toInt() and 0xFF)
    }

    /**
     * Membaca jenis protokol Layer 4 (TCP, UDP, ICMP).
     */
    fun getProtocol(packet: ByteArray, length: Int): Byte {
        if (length < 20) return 0
        return packet[9]
    }

    /**
     * Mengambil IP Destinasi dari paket IPv4.
     */
    fun getDestinationAddress(packet: ByteArray, length: Int): InetAddress? {
        if (length < 20) return null
        return try {
            val ipBytes = ByteArray(4)
            System.arraycopy(packet, 16, ipBytes, 0, 4)
            InetAddress.getByAddress(ipBytes)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Mengambil IP Sumber dari paket IPv4.
     */
    fun getSourceAddress(packet: ByteArray, length: Int): InetAddress? {
        if (length < 20) return null
        return try {
            val ipBytes = ByteArray(4)
            System.arraycopy(packet, 12, ipBytes, 0, 4)
            InetAddress.getByAddress(ipBytes)
        } catch (_: Exception) {
            null
        }
    }
}
