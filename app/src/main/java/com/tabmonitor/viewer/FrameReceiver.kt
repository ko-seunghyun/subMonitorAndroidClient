package com.tabmonitor.viewer

import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue

/**
 * TCP 소켓을 통해 PC 서버와 통신하는 클래스
 *
 * 수신 프로토콜: [4바이트 Big Endian: JPEG 크기] + [N바이트: JPEG 데이터]
 * 송신 프로토콜: [1바이트: 이벤트 타입] + [4바이트: X좌표] + [4바이트: Y좌표]
 *   이벤트 타입: 0x01=DOWN, 0x02=MOVE, 0x03=UP
 */
class FrameReceiver(
    private val host: String,
    private val port: Int,
    private val onFrame: (ByteArray) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (String) -> Unit
) {
    companion object {
        private const val TAG = "FrameReceiver"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_FRAME_BYTES    = 10 * 1024 * 1024
        private const val TOUCH_QUEUE_MAX    = 8  // 터치 큐 작게 유지

        const val TOUCH_DOWN: Byte = 0x01
        const val TOUCH_MOVE: Byte = 0x02
        const val TOUCH_UP:   Byte = 0x03
    }

    data class TouchEvent(val type: Byte, val x: Float, val y: Float)

    @Volatile private var running = false
    private var socket: Socket? = null

    private val scope = CoroutineScope(SupervisorJob())
    private val touchQueue = LinkedBlockingQueue<TouchEvent>(TOUCH_QUEUE_MAX)

    private var recvCount = 0
    private var lastRecvTime = System.currentTimeMillis()

    fun start() {
        running = true
        scope.launch(Dispatchers.IO) { runReceiveLoop() }
    }

    fun stop() {
        running = false
        scope.cancel()
        closeSocket()
    }

    fun sendTouch(type: Byte, x: Float, y: Float) {
        // MOVE는 큐가 차 있으면 버림 (최신 위치만 유지)
        if (type == TOUCH_MOVE) {
            touchQueue.clear()
        }
        touchQueue.offer(TouchEvent(type, x, y))
    }

    private suspend fun runReceiveLoop() {
        try {
            Log.d(TAG, "Connecting to $host:$port")
            val sock = Socket()
            sock.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            sock.soTimeout = 0          // 타임아웃 없음 (readFully 블로킹)
            sock.tcpNoDelay = true
            sock.receiveBufferSize = 1024 * 1024  // 1MB 수신 버퍼
            socket = sock

            val input  = DataInputStream(sock.getInputStream().buffered(1024 * 1024))
            val output = DataOutputStream(sock.getOutputStream())

            val touchJob = scope.launch(Dispatchers.IO) { runTouchSender(output) }

            onConnected()
            Log.d(TAG, "Connected!")

            while (running && !sock.isClosed) {
                val size = try { input.readInt() } catch (e: IOException) { break }
                if (size <= 0 || size > MAX_FRAME_BYTES) {
                    Log.w(TAG, "Invalid frame size: $size")
                    continue
                }
                val buf = ByteArray(size)
                input.readFully(buf)
                // 프레임 전달 (DisplayView에서 최신 것만 렌더링)
                onFrame(buf)
                recvCount++  // ← 추가
                val now = System.currentTimeMillis()
                if (now - lastRecvTime >= 1_000L) {
                    android.util.Log.d("FrameReceiver", "수신 FPS: $recvCount")
                    recvCount = 0
                    lastRecvTime = now
                }
            }

            touchJob.cancel()

        } catch (e: Exception) {
            if (running) Log.e(TAG, "Connection error: ${e.message}")
        } finally {
            closeSocket()
            if (running) onDisconnected("연결이 끊어졌습니다.")
            running = false
        }
    }

    private suspend fun runTouchSender(out: DataOutputStream) {
        while (running) {
            val event = withContext(Dispatchers.IO) {
                try { touchQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                catch (e: InterruptedException) { null }
            } ?: continue

            try {
                withContext(Dispatchers.IO) {
                    out.writeByte(event.type.toInt())
                    out.writeFloat(event.x)
                    out.writeFloat(event.y)
                    out.flush()
                }
            } catch (e: IOException) {
                Log.e(TAG, "Touch send error: ${e.message}")
                break
            }
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }
}
