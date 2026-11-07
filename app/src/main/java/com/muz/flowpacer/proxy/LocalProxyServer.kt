package com.muz.flowpacer.proxy

import com.muz.flowpacer.engine.TokenBucket
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LocalProxyServer(
    private val port: Int = 10808,
    private val uploadTokenBucket: TokenBucket,
    private val downloadTokenBucket: TokenBucket,
    private val onProtectSocket: (Socket) -> Unit
) {

    private var serverSocket: ServerSocket? = null
    private var threadPool: ExecutorService? = null

    @Volatile
    private var isRunning = false

    val totalBytesUploaded = AtomicLong(0L)
    val totalBytesDownloaded = AtomicLong(0L)

    fun start() {
        if (isRunning) return
        isRunning = true
        totalBytesUploaded.set(0L)
        totalBytesDownloaded.set(0L)

        // Gunakan pool benang terurus bagi mengelakkan isu Thread Leak
        threadPool = Executors.newCachedThreadPool()

        threadPool?.execute {
            try {
                serverSocket = ServerSocket(port, 100, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    threadPool?.execute {
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

        try {
            threadPool?.shutdownNow()
            threadPool?.awaitTermination(1, TimeUnit.SECONDS)
            threadPool = null
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
                return
            }

            val method = tokens[0]
            val target = tokens[1]

            val targetHost: String
            val targetPort: Int

            if (method.equals("CONNECT", ignoreCase = true)) {
                // Mod Terowong HTTPS
                while (true) {
                    val header = readLineByteByByte(clientIn)
                    if (header.isNullOrEmpty()) break
                }
                val hp = target.split(":")
                targetHost = hp[0]
                targetPort = if (hp.size > 1) hp[1].toIntOrNull() ?: 443 else 443

                upstreamSocket = Socket()
                upstreamSocket.tcpNoDelay = true
                onProtectSocket(upstreamSocket)
                upstreamSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

                val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
                clientOut.write(response.toByteArray(Charsets.US_ASCII))
                clientOut.flush()
            } else {
                // Mod HTTP Biasa
                val cleanUrl = if (target.startsWith("http://", ignoreCase = true)) target.substring(7) else target
                val slashIdx = cleanUrl.indexOf('/')
                val hostPart = if (slashIdx != -1) cleanUrl.substring(0, slashIdx) else cleanUrl
                val hp = hostPart.split(":")
                targetHost = hp[0]
                targetPort = if (hp.size > 1) hp[1].toIntOrNull() ?: 80 else 80

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

            // Selaras aliran dwiarah secara serentak tanpa pemotongan masa paksa
            val latch = CountDownLatch(2)

            threadPool?.execute {
                try {
                    pipeWithUploadRateLimit(clientIn, upstreamOut)
                    try { upstreamSocket?.shutdownOutput() } catch (_: Exception) {}
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }

            threadPool?.execute {
                try {
                    pipeWithDownloadRateLimit(upstreamIn, clientOut)
                    try { clientSocket.shutdownOutput() } catch (_: Exception) {}
                } catch (_: Exception) {
                } finally {
                    latch.countDown()
                }
            }

            try {
                latch.await()
            } catch (_: InterruptedException) {}

        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun readLineByteByByte(input: InputStream): String? {
        val sb = StringBuilder()
        var charCount = 0
        while (isRunning && charCount < 8192) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                sb.append(b.toChar())
                charCount++
            }
        }
        return sb.toString()
    }

    private fun pipeWithUploadRateLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                val delayMs = uploadTokenBucket.acquire(bytesRead)
                if (delayMs > 0) Thread.sleep(delayMs)

                output.write(buffer, 0, bytesRead)
                output.flush()
                totalBytesUploaded.addAndGet(bytesRead.toLong())
            }
        } catch (_: Exception) {}
    }

    private fun pipeWithDownloadRateLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                val delayMs = downloadTokenBucket.acquire(bytesRead)
                if (delayMs > 0) Thread.sleep(delayMs)

                output.write(buffer, 0, bytesRead)
                output.flush()
                totalBytesDownloaded.addAndGet(bytesRead.toLong())
            }
        } catch (_: Exception) {}
    }
}
