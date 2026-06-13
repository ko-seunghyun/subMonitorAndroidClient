package com.tabmonitor.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * ImageView 기반 렌더러
 * - setImageBitmap() 으로 vsync 제한 없이 즉시 갱신
 * - 디코딩 전용 스레드풀 2개로 병렬 처리
 * - 비트맵 재사용으로 GC 최소화
 */
class DisplayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    var frameReceiver: FrameReceiver? = null
    var remoteWidth:  Int = 1920
    var remoteHeight: Int = 1080

    // FPS
    private var frameCount = 0
    private var lastFpsTime = System.currentTimeMillis()
    var onFpsUpdate: ((Int) -> Unit)? = null

    // 최신 JPEG만 보관
    private val pendingJpeg = AtomicReference<ByteArray?>(null)

    // 디코딩 스레드풀
    private val decodeExecutor = Executors.newFixedThreadPool(2)

    // 비트맵 재사용 풀 (2개 교대 사용)
    private val bitmapPool = ArrayDeque<Bitmap>()

    init {
        scaleType = ScaleType.FIT_CENTER
        keepScreenOn = true
    }

    // ─────────────────────────────────────────────────
    // 외부 호출: 새 프레임 push (IO 스레드)
    // ─────────────────────────────────────────────────

    fun pushFrame(jpeg: ByteArray) {
        pendingJpeg.set(jpeg)
        decodeExecutor.submit { decodeAndRender() }
    }

    private fun decodeAndRender() {
        val jpeg = pendingJpeg.getAndSet(null) ?: return  // 이미 더 새 프레임으로 교체됨

        // 재사용 비트맵 꺼내기
        val reuse = synchronized(bitmapPool) { bitmapPool.removeFirstOrNull() }

        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.RGB_565
            inBitmap = reuse  // 재사용 시도
        }

        val bitmap = try {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        } catch (e: Exception) {
            opts.inBitmap = null
            try { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) }
            catch (e2: Exception) { return }
        } ?: return

        // UI 스레드에서 즉시 표시
        post {
            // 이전 비트맵 풀에 반환
            val prev = drawable
            if (prev is android.graphics.drawable.BitmapDrawable) {
                val old = prev.bitmap
                if (old != null && old != bitmap && !old.isRecycled) {
                    synchronized(bitmapPool) {
                        if (bitmapPool.size < 3) bitmapPool.addLast(old)
                    }
                }
            }

            setImageBitmap(bitmap)
            updateFps()
        }
    }

    // ─────────────────────────────────────────────────
    // FPS
    // ─────────────────────────────────────────────────

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - lastFpsTime >= 1_000L) {
            onFpsUpdate?.invoke(frameCount)
            frameCount = 0
            lastFpsTime = now
        }
    }

    // ─────────────────────────────────────────────────
    // 터치
    // ─────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val receiver = frameReceiver ?: return true

        val vw = width.toFloat()
        val vh = height.toFloat()
        val scale   = minOf(vw / remoteWidth, vh / remoteHeight)
        val offsetX = (vw - remoteWidth  * scale) / 2f
        val offsetY = (vh - remoteHeight * scale) / 2f

        val pointerIndex = event.actionIndex
        val rawX = event.getX(pointerIndex)
        val rawY = event.getY(pointerIndex)

        if (rawX < offsetX || rawX > offsetX + remoteWidth  * scale ||
            rawY < offsetY || rawY > offsetY + remoteHeight * scale) return true

        val pcX = ((rawX - offsetX) / scale).coerceIn(0f, remoteWidth.toFloat())
        val pcY = ((rawY - offsetY) / scale).coerceIn(0f, remoteHeight.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> receiver.sendTouch(FrameReceiver.TOUCH_DOWN, pcX, pcY)
            MotionEvent.ACTION_MOVE         -> receiver.sendTouch(FrameReceiver.TOUCH_MOVE, pcX, pcY)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL       -> receiver.sendTouch(FrameReceiver.TOUCH_UP, pcX, pcY)
        }
        return true
    }

    // ─────────────────────────────────────────────────
    // 정리
    // ─────────────────────────────────────────────────

    fun release() {
        decodeExecutor.shutdown()
        synchronized(bitmapPool) {
            bitmapPool.forEach { if (!it.isRecycled) it.recycle() }
            bitmapPool.clear()
        }
    }
}