package com.muz.flowpacer.proxy

import com.muz.flowpacer.engine.TokenBucket
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

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

        thread(name = "FlowPacer-HttpProxy", isDaemon = true) {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("127.0.0.1"))
                while (isRunning) {
                    val clientSocket = serverSocket?.accept() ?: break
                    thread(name = "FlowPacer-Worker", isDaemon = true) {
                        handleHttpClient(clientSocket)
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

    private fun handleHttpClient(clientSocket: Socket) {
        var upstreamSocket: Socket? = null
        try {
            clientSocket.tcpNoDelay = true
            val clientIn = clientSocket.getInputStream()
            val clientOut = clientSocket.getOutputStream()

            val reader = BufferedReader(InputStreamReader(clientIn))
            val requestLine = reader.readLine() ?: return

            // Baca permintaan HTTP CONNECT: "CONNECT host:port HTTP/1.1"
            val tokens = requestLine.split(" ")
            if (tokens.size < 2 || !tokens[0].equals("CONNECT", ignoreCase = true)) {
                clientSocket.close()
                return
            }

            // Langkau semua baris header HTTP lain sampai jumpa baris kosong
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }

            val hostPort = tokens[1].split(":")
            val targetHost = hostPort[0]
            val targetPort = if (hostPort.size > 1) hostPort[1].toInt() else 443

            // Buka sambungan keluar ke pelayan sasaran
            upstreamSocket = Socket()
            upstreamSocket.tcpNoDelay = true
            onProtectSocket(upstreamSocket)
            upstreamSocket.connect(InetSocketAddress(targetHost, targetPort), 10000)

            // Balas kembali ke aplikasi bahawa terowong sudah terhubung
            val response = "HTTP/1.1 200 Connection Established\r\n\r\n"
            clientOut.write(response.toByteArray(Charsets.US_ASCII))
            clientOut.flush()

            val upstreamIn = upstreamSocket.getInputStream()
            val upstreamOut = upstreamSocket.getOutputStream()

            // Alirkan data dua hala: muat naik dikawal TokenBucket, muat turun terus laju
            val uploadThread = thread(name = "Upload-Limiter", isDaemon = true) {
                pipeWithRateLimit(clientIn, upstreamOut)
            }

            pipeDirect(upstreamIn, clientOut)
            uploadThread.join()

        } catch (_: Exception) {
        } finally {
            try { clientSocket.close() } catch (_: Exception) {}
            try { upstreamSocket?.close() } catch (_: Exception) {}
        }
    }

    private fun pipeWithRateLimit(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (isRunning) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) break

                val delayMs = tokenBucket.acquire(bytesRead)
                if (delayMs > 0) {
                    Thread.sleep(delayMs)
                }

                output.write(buffer, 0, bytesRead)
                output.flush()
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
            }
        } catch (_: Exception) {}
    }
}
