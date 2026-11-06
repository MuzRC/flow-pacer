package com.muz.flowpacer.proxy

import com.muz.flowpacer.engine.TokenBucket
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class LocalProxyServer(
    private val port: Int = 10808,
    private val tokenBucket: TokenBucket,
    private val onProtectSocket: (Socket) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    // Ciri 4: Pengira bait masa-nyata
    val totalBytesUploaded = AtomicLong(0L)
    val totalBytesDownloaded = AtomicLong(0L)

    fun start() {
        if (isRunning) return
        isRunning = true
        totalBytesUploaded.set(0L)
        totalBytesDownloaded.set(0L)

        thread(name = "FlowPacer-HttpProxy", isDaemon = true) {
            try {
                serverSocket = ServerSocket(port, 100, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    thread(name = "FlowPacer-Worker", isDaemon = true) {
                        handleClient(clientSocket)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
        } catch (_: Exception) {}
    }

    private fun handleClient(clientSocket: Socket) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val requestLine = readLineByteByByte(clientIn) ?: return
            val tokens = requestLine.split(" ")
            if (tokens.size < 2) {
                clientSocket.close()
                return
            }

            val method = tokens[0]
            val target = tokens[1]

            val targetHost: String
            val targetPort: Int

            if (method.equals("CONNECT", ignoreCase = true)) {
                while (true) {
                    val header = readLineByteByByte(clientIn)
                    if (header.isNullOrEmpty()) break
                }
                val hp = target.split(":")
                targetHost = hp[0]
                targetPort = if (hp.size > 1) hp[1].toInt() else 443

                upstreamSocket = Socket()
                upstreamSocket.tcpNoDelay = true
                onProtectSocket(upstreamSocket)
                upstreamSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

                val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
                clientOut.write(response.toByteArray(Charsets.US_ASCII))
                clientOut.flush()
            } else {
                val cleanUrl = if (target.startsWith("http://", ignoreCase = true)) target.substring(7) else target
                val slashIdx = cleanUrl.indexOf('/')
                val hostPart = if (slashIdx != -1) cleanUrl.substring(0, slashIdx) else cleanUrl
                val hp = hostPart.split(":")
                targetHost = hp[0]
                targetPort = if (hp.size > 1) hp[1].toInt() else 80

                upstreamSocket = Socket()
                upstreamSocket.tcpNoDelay = true
                onProtectSocket(upstreamSocket)
                upstreamSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

                val upstreamOut = upstreamSocket.getOutputStream()
                val reqBytes = "$requestLine\r\n".toByteArray(Charsets.US_ASCII)
                upstreamOut.write(reqBytes)
                upstreamOut.flush()
                totalBytesUploaded.addAndGet(reqBytes.size.toLong())
            }

            val upstreamIn = upstreamSocket.getInputStream()
            val upstreamOut = upstreamSocket.getOutputStream()

            val uploadThread = thread(name = "Upload-Limiter", isDaemon = true) {
                try {
                    pipeWithRateLimit(clientIn, upstreamOut)
                } finally {
                    try { upstreamSocket?.close() } catch (_: Exception) {}
                }
            }

            try {
                pipeDirect(upstreamIn, clientOut)
            } finally {
                try { clientSocket.close() } catch (_: Exception) {}
            }

            uploadThread.join(1500)

        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun readLineByteByByte(input: InputStream): String? {
        val sb = StringBuilder()
        while (isRunning) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun pipeWithRateLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                val delayMs = tokenBucket.acquire(bytesRead)
                if (delayMs > 0) Thread.sleep(delayMs)

                output.write(buffer, 0, bytesRead)
                output.flush()
                totalBytesUploaded.addAndGet(bytesRead.toLong())
            }
        } catch (_: Exception) {}
    }

    private fun pipeDirect(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(16384)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break
                output.write(buffer, 0, bytesRead)
                output.flush()
                totalBytesDownloaded.addAndGet(bytesRead.toLong())
            }
        } catch (_: Exception) {}
    }
}
