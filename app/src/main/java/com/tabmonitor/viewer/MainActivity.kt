package com.tabmonitor.viewer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.tabmonitor.viewer.databinding.ActivityMainBinding
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    private var receiver: FrameReceiver? = null
    private val uiScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ─────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 전체 화면 + 화면 켜짐 유지
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("tab_monitor", Context.MODE_PRIVATE)

        // 마지막 접속 IP 복원
        binding.etIpAddress.setText(prefs.getString("last_ip", ""))
        binding.etPort.setText(prefs.getString("last_port", "7070"))

        setupButtons()
        setupDisplayView()
        showConnectPanel()
    }

    override fun onDestroy() {
        binding.displayView.release()
        super.onDestroy()
        uiScope.cancel()
        receiver?.stop()
    }

    // ─────────────────────────────────────────────────
    // UI 설정
    // ─────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnConnect.setOnClickListener { handleConnect() }
        binding.btnDisconnect.setOnClickListener { handleDisconnect() }

        // 연결된 상태에서 화면 탭 -> 상태바 토글
        binding.displayView.setOnClickListener {
            if (binding.statusBar.visibility == View.VISIBLE) {
                binding.statusBar.visibility = View.GONE
            } else {
                binding.statusBar.visibility = View.VISIBLE
                // 3초 후 자동 숨김
                uiScope.launch {
                    delay(3_000)
                    binding.statusBar.visibility = View.GONE
                }
            }
        }
    }

    private fun setupDisplayView() {
        binding.displayView.onFpsUpdate = { fps ->
            runOnUiThread {
                binding.tvFps.text = "FPS: $fps"
            }
        }
    }

    // ─────────────────────────────────────────────────
    // 연결 / 해제
    // ─────────────────────────────────────────────────

    private fun handleConnect() {
        val ip   = binding.etIpAddress.text.toString().trim()
        val port = binding.etPort.text.toString().trim().toIntOrNull() ?: 7070

        if (ip.isEmpty()) {
            binding.tvStatus.text = "IP 주소를 입력하세요."
            return
        }

        // 마지막 접속 정보 저장
        prefs.edit().putString("last_ip", ip).putString("last_port", port.toString()).apply()

        showLoadingPanel(ip, port)

        receiver?.stop()
        receiver = FrameReceiver(
            host = ip,
            port = port,
            onFrame = { jpeg ->
                // IO 스레드에서 호출됨 -> DisplayView에 직접 push (내부에서 lockCanvas)
                binding.displayView.pushFrame(jpeg)
            },
            onConnected = {
                runOnUiThread { showStreamingPanel(ip, port) }
            },
            onDisconnected = { reason ->
                runOnUiThread { showConnectPanel(errorMsg = reason) }
            }
        ).also { it.start() }

        // DisplayView에 receiver 연결 (터치 역전송용)
        binding.displayView.frameReceiver = receiver
    }

    private fun handleDisconnect() {
        receiver?.stop()
        receiver = null
        binding.displayView.frameReceiver = null
        showConnectPanel()
    }

    // ─────────────────────────────────────────────────
    // UI 상태 전환
    // ─────────────────────────────────────────────────

    /** 연결 입력 폼 표시 */
    private fun showConnectPanel(errorMsg: String = "") {
        binding.connectPanel.visibility  = View.VISIBLE
        binding.loadingPanel.visibility  = View.GONE
        binding.statusBar.visibility     = View.GONE
        binding.tvStatus.text            = errorMsg
    }

    /** 연결 시도 중 로딩 표시 */
    private fun showLoadingPanel(ip: String, port: Int) {
        binding.connectPanel.visibility  = View.GONE
        binding.loadingPanel.visibility  = View.VISIBLE
        binding.tvConnecting.text        = "$ip:$port 연결 중..."
        binding.statusBar.visibility     = View.GONE
    }

    /** 스트리밍 수신 중 - 패널 숨김 */
    private fun showStreamingPanel(ip: String, port: Int) {
        binding.connectPanel.visibility  = View.GONE
        binding.loadingPanel.visibility  = View.GONE
        binding.statusBar.visibility     = View.VISIBLE
        binding.tvConnectedIp.text       = "$ip:$port"
        binding.tvFps.text               = "FPS: --"
    }
}