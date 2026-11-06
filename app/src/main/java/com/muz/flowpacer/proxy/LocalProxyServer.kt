package com.muz.flowpacer.proxy

import com.muz.flowpacer.engine.TokenBucket
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Pelayan Proksi SOCKS5 Tempatan dengan Kawalan Aliran (Rate Limiting).
 * Menyekat had laju muat naik menggunakan TokenBucket untuk mematikan isu Bufferbloat.
 */
class LocalProxyServer(
    private val port: Int = 10808,
    private val tokenBucket: TokenBucket,
    private val onProtectSocket: (Socket) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true

        thread(name = "FlowPacer-ProxyServer", isDaemon = true) {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    thread(name = "FlowPacer-Worker", isDaemon = true) {
                        handleSocks5Client(clientSocket)
                    }
                }
            } catch (e: Exception) {
                // Pelayan ditutup atau terhenti
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
    }

    private fun handleSocks5Client(clientSocket: Socket) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            // 1. Jabat Tangan SOCKS5 (Method Handshake)
            val version = clientIn.read()
            if (version != 5) {
                clientSocket.close()
                return
            }

            val nMethods = clientIn.read()
            val methods = ByteArray(nMethods)
            clientIn.readFully(methods)

            // Balas dengan Kod 0x00 (Tiada Pengesahan Diperlukan)
            clientOut.write(byteArrayOf(0x05, 0x00))
            clientOut.flush()

            // 2. Permintaan Sambungan SOCKS5 (CONNECT Command)
            val reqVersion = clientIn.read()
            val cmd = clientIn.read()
            clientIn.read() // Reserved byte (0x00)
            val atyp = clientIn.read()

            if (reqVersion != 5 || cmd != 1) { // 1 = CONNECT TCP
                clientOut.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                clientSocket.close()
                return
            }

            // Tentukan Destinasi Sasaran (IPv4 atau Nama Domain)
            val targetHost: String = when (atyp) {
                0x01 -> { // IPv4 (4 bytes)
                    val ipBytes = ByteArray(4)
                    clientIn.readFully(ipBytes)
                    InetAddress.getByAddress(ipBytes).hostAddress ?: return
                }
                0x03 -> { // Nama Domain
                    val domainLen = clientIn.read()
                    val domainBytes = ByteArray(domainLen)
                    clientIn.readFully(domainBytes)
                    String(domainBytes, Charsets.US_ASCII)
                }
                else -> {
                    clientSocket.close()
                    return
                }
            }

            // Baca Port Sasaran (2 bytes)
            val portBytes = ByteArray(2)
            clientIn.readFully(portBytes)
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            // 3. Bina Sambungan ke Internet Luar (Upstream Socket)
            upstreamSocket = Socket()
            upstreamSocket.tcpNoDelay = true
            // Lindungi soket daripada ditangkap semula oleh VpnService
            onProtectSocket(upstreamSocket)
            upstreamSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

            // Hantar maklum balas kejayaan (Success) ke peranti
            clientOut.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            clientOut.flush()

            val upstreamIn = upstreamSocket.getInputStream()
            val upstreamOut = upstreamSocket.getOutputStream()

            // 4. Salurkan Data Dua Hala:
            // A. Muat Naik (Client -> Upstream): Melalui Kawalan Had Laju TokenBucket
            val uploadThread = thread(name = "Upload-Shaper", isDaemon = true) {
                pipeWithRateLimit(clientIn, upstreamOut)
            }

            // B. Muat Turun (Upstream -> Client): Kelajuan Maksimum Tanpa Had
            pipeDirect(upstreamIn, clientOut)

            uploadThread.join()

        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Memindahkan aliran bait muat naik sambil menyekat kadar lonjakan
     * menggunakan algoritma TokenBucket bagi memelihara antrean repeater.
     */
    private fun pipeWithRateLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                // Dapatkan masa lengah jika melebihi kuota saat ini
                val delayMs = tokenBucket.acquire(bytesRead)
                if (delayMs > 0) {
                    Thread.sleep(delayMs)
                }

                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    /**
     * Memindahkan data muat turun terus pada kelajuan asal tanpa ditahan.
     */
    private fun pipeDirect(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
            }
        } catch (_: Exception) {}
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = this.read(buffer, offset, buffer.size - offset)
            if (read == -1) break
            offset += read
        }
    }
}
