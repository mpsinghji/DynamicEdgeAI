package com.dynamicedgeai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.cloud.CloudModelRunner
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.Strategy
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.local.LocalModel
import com.dynamicedgeai.monitor.*
import com.dynamicedgeai.router.MessageRouter
import com.dynamicedgeai.util.ParallelDownloader
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var cpuMonitor: CpuMonitor
    private lateinit var ramMonitor: RamMonitor
    
    private val decisionEngine = DecisionEngine()
    private lateinit var localModelRunner: LocalModelRunner
    private val cloudModelRunner = CloudModelRunner()
    private lateinit var messageRouter: MessageRouter
    private val downloader = ParallelDownloader()

    private lateinit var ramLabel: TextView
    private lateinit var cpuLabel: TextView
    private lateinit var networkLabel: TextView
    private lateinit var batteryLabel: TextView
    private lateinit var ramIndicator: ImageView
    private lateinit var cpuIndicator: ImageView
    private lateinit var insightModeText: TextView
    
    private lateinit var insightsToggle: View
    private lateinit var insightsContainer: View
    private lateinit var insightsChevron: ImageView
    private var isInsightsVisible = true

    private lateinit var privacyModeToggle: android.widget.Switch
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var thinkingIndicator: LinearLayout
    private lateinit var thinkingText: TextView
    
    // Download UI
    private lateinit var downloadOverlay: CardView
    private lateinit var downloadTitleText: TextView
    private lateinit var downloadStatusText: TextView
    private lateinit var downloadProgressBar: ProgressBar
    private lateinit var downloadPercentText: TextView
    private lateinit var btnCancelDownload: ImageButton
    private lateinit var btnRetryDownload: ImageButton
    private lateinit var btnPauseResume: ImageButton
    
    private var lastKnownState: DeviceState? = null
    private var currentDownloadingModel: LocalModel? = null
    private var isPaused = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localModelRunner = LocalModelRunner(this)
        messageRouter = MessageRouter(decisionEngine, localModelRunner, cloudModelRunner)

        // Initialize UI
        ramLabel = findViewById(R.id.ramLabel)
        cpuLabel = findViewById(R.id.cpuLabel)
        networkLabel = findViewById(R.id.networkLabel)
        batteryLabel = findViewById(R.id.batteryLabel)
        ramIndicator = findViewById(R.id.ramIndicator)
        cpuIndicator = findViewById(R.id.cpuIndicator)
        insightModeText = findViewById(R.id.insightModeText)
        insightsToggle = findViewById(R.id.insightsToggle)
        insightsContainer = findViewById(R.id.insightsContainer)
        insightsChevron = findViewById(R.id.insightsInfoIcon) 
        privacyModeToggle = findViewById(R.id.privacyModeToggle)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        menuButton = findViewById(R.id.menuButton)
        chatContainer = findViewById(R.id.chatContainer)
        chatScrollView = findViewById(R.id.chatScrollView)
        thinkingIndicator = findViewById(R.id.thinkingIndicator)
        thinkingText = findViewById(R.id.thinkingText)
        
        downloadOverlay = findViewById(R.id.downloadOverlay)
        downloadTitleText = findViewById(R.id.downloadTitleText)
        downloadStatusText = findViewById(R.id.downloadStatusText)
        downloadProgressBar = findViewById(R.id.downloadProgressBar)
        downloadPercentText = findViewById(R.id.downloadPercentText)
        btnCancelDownload = findViewById(R.id.btnCancelDownload)
        btnRetryDownload = findViewById(R.id.btnRetryDownload)
        btnPauseResume = findViewById(R.id.btnPauseResume)

        // Default Visible Insights
        insightsContainer.visibility = View.VISIBLE
        insightsChevron.rotation = 0f

        insightsToggle.setOnClickListener { toggleInsights() }
        sendButton.setOnClickListener { trySendMessage() }
        menuButton.setOnClickListener { showRamMenu(it) }
        
        btnCancelDownload.setOnClickListener { 
            downloader.cancel()
            val file = currentDownloadingModel?.let { localModelRunner.getModelPath(it) }
            if (file?.exists() == true) file.delete() 
            downloadOverlay.visibility = View.GONE
            Toast.makeText(this, "Download Reset", Toast.LENGTH_SHORT).show()
        }

        btnPauseResume.setOnClickListener {
            if (isPaused) {
                downloader.resume()
            } else {
                downloader.pause()
            }
        }

        btnRetryDownload.setOnClickListener {
            currentDownloadingModel?.let { startParallelDownload(it, true) }
        }

        messageInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                trySendMessage()
                true
            } else { false }
        }

        batteryMonitor = BatteryMonitor(this)
        thermalMonitor = ThermalMonitor(this)
        networkMonitor = NetworkMonitor(this)
        cpuMonitor = CpuMonitor()
        ramMonitor = RamMonitor(this)

        startResourceMonitoring()
    }

    private fun toggleInsights() {
        isInsightsVisible = !isInsightsVisible
        insightsContainer.visibility = if (isInsightsVisible) View.VISIBLE else View.GONE
        insightsChevron.animate().rotation(if (isInsightsVisible) 0f else 180f).setDuration(300).start()
    }

    private fun trySendMessage() {
        val text = messageInput.text.toString()
        if (text.isNotBlank()) {
            messageInput.text.clear()
            handleUserMessage(text)
        }
    }

    private fun handleUserMessage(text: String) {
        addUserBubble(text)
        scrollToBottom()
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            showThinking()
            val state = lastKnownState ?: DeviceState()
            val result = messageRouter.routeMessage(text, privacyModeToggle.isChecked, state)
            val latency = "${(System.currentTimeMillis() - startTime) / 1000.0}s"
            hideThinking()
            addAIResponse(result.response, result.detail, latency)
            scrollToBottom()
        }
    }

    private fun showRamMenu(view: View) {
        val popup = PopupMenu(this, view)
        popup.menu.add(0, 101, 0, "--- RESOURCE TOOLS ---")
        popup.menu.add(0, 1, 1, "Adjust RAM Level (Target)")
        popup.menu.add(0, 102, 2, "--- LOCAL MODELS ---")
        
        LocalModel.values().forEach { model ->
            val isReady = localModelRunner.isModelAvailable(model)
            val status = if (isReady) "Ready" else "Download"
            val selected = if (localModelRunner.currentModel == model) " ✓" else ""
            popup.menu.add(0, model.ordinal + 10, 3, "${model.displayName} ($status)$selected")
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { showCustomRamDialog(); true }
                in 10..20 -> {
                    val selectedModel = LocalModel.values()[item.itemId - 10]
                    if (localModelRunner.isModelAvailable(selectedModel)) {
                        localModelRunner.switchModel(selectedModel)
                        Toast.makeText(this, "Switched to ${selectedModel.displayName}", Toast.LENGTH_SHORT).show()
                    } else {
                        startParallelDownload(selectedModel, false)
                    }
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun startParallelDownload(model: LocalModel, resume: Boolean) {
        currentDownloadingModel = model
        val destination = localModelRunner.getModelPath(model)
        
        if (!resume) {
            destination.parentFile?.mkdirs()
            if (destination.exists()) destination.delete()
        }

        downloadTitleText.text = "Downloading ${model.displayName}..."
        downloadOverlay.visibility = View.VISIBLE
        btnRetryDownload.visibility = View.GONE
        btnPauseResume.visibility = View.VISIBLE
        btnPauseResume.setImageResource(R.drawable.ic_pause)
        isPaused = false
        if (!resume) downloadProgressBar.progress = 0

        downloader.download(model.downloadUrl, destination, object : ParallelDownloader.DownloadListener {
            override fun onStart(totalSize: Long) {
                Handler(Looper.getMainLooper()).post {
                    downloadStatusText.text = "Status: Connecting..."
                }
            }

            override fun onProgress(progress: Int, speed: String) {
                Handler(Looper.getMainLooper()).post {
                    downloadProgressBar.progress = progress
                    downloadPercentText.text = "$progress%"
                    downloadStatusText.text = "Status: Segmented Download Active"
                }
            }

            override fun onPaused() {
                isPaused = true
                Handler(Looper.getMainLooper()).post {
                    downloadStatusText.text = "Status: Paused"
                    btnPauseResume.setImageResource(android.R.drawable.ic_media_play)
                }
            }

            override fun onResumed() {
                isPaused = false
                Handler(Looper.getMainLooper()).post {
                    downloadStatusText.text = "Status: Resuming..."
                    btnPauseResume.setImageResource(R.drawable.ic_pause)
                }
            }

            override fun onComplete(file: File) {
                Handler(Looper.getMainLooper()).post {
                    downloadOverlay.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Download Success! Model Ready.", Toast.LENGTH_LONG).show()
                }
            }

            override fun onError(error: String) {
                Handler(Looper.getMainLooper()).post {
                    downloadStatusText.text = "Error: $error"
                    btnRetryDownload.visibility = View.VISIBLE
                    btnPauseResume.visibility = View.GONE
                }
            }
        }, resume)
    }

    private fun showCustomRamDialog() {
        val currentFree = lastKnownState?.ramAvailable ?: 0
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val infoText = TextView(this).apply {
            text = "Current Free RAM: $currentFree MB"
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
            setPadding(0, 0, 0, 30)
        }
        layout.addView(infoText)
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Target Free RAM (MB)"
            setText(currentFree.toString())
            setSelection(text.length)
        }
        layout.addView(input)
        AlertDialog.Builder(this)
            .setTitle("Adjust RAM Level")
            .setView(layout)
            .setPositiveButton("Set Target") { _, _ ->
                val target = input.text.toString().toLongOrNull() ?: currentFree
                ramMonitor.setTargetAvailableRam(target)
            }
            .setNeutralButton("Reset") { _, _ -> ramMonitor.freeRam() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addUserBubble(text: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_user, chatContainer, false)
        view.findViewById<TextView>(R.id.messageText).text = text
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        view.findViewById<TextView>(R.id.timestamp).text = timeStr
        val index = chatContainer.indexOfChild(thinkingIndicator)
        chatContainer.addView(view, if (index != -1) index else chatContainer.childCount)
    }

    private fun addAIResponse(response: String, detail: com.dynamicedgeai.engine.StrategyDetail, latency: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_ai, chatContainer, false)
        view.findViewById<TextView>(R.id.messageText).text = response
        view.findViewById<TextView>(R.id.modeTitle).text = "Execution Info: ${detail.strategy.name}"
        val modelName = if (detail.strategy == Strategy.LOCAL) "${localModelRunner.currentModel.displayName} (On-Device)" else "Gemini 1.5 Flash (Cloud)"
        view.findViewById<TextView>(R.id.modelUsedText).text = "Model: $modelName"
        view.findViewById<TextView>(R.id.reasonText).text = "Reason: ${detail.reason}"
        view.findViewById<TextView>(R.id.latencyText).text = "Latency: $latency"
        view.findViewById<TextView>(R.id.networkUsedText).text = "Network Used: ${if (detail.strategy == Strategy.CLOUD) "Yes" else "No"}"
        val index = chatContainer.indexOfChild(thinkingIndicator)
        chatContainer.addView(view, if (index != -1) index else chatContainer.childCount)
    }

    private fun showThinking() {
        thinkingIndicator.visibility = View.VISIBLE
        val anim = AlphaAnimation(0.3f, 1.0f).apply { duration = 600; repeatMode = Animation.REVERSE; repeatCount = Animation.INFINITE }
        thinkingText.startAnimation(anim)
        scrollToBottom()
    }

    private fun hideThinking() {
        thinkingText.clearAnimation()
        thinkingIndicator.visibility = View.GONE
    }

    private fun scrollToBottom() { chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) } }

    private fun startResourceMonitoring() {
        lifecycleScope.launch {
            combine(
                batteryMonitor.observeBatteryState(),
                thermalMonitor.observeThermalState(),
                networkMonitor.observeNetworkQuality(),
                cpuMonitor.observeCpuUsage(),
                ramMonitor.observeRamUsage()
            ) { battery: Pair<Float, Boolean>, thermal: ThermalState, network: NetworkQuality, cpu: Int, ram: Pair<Long, Long> ->
                DeviceState(battery.first, battery.second, network, thermal, cpu, ram.first, ram.second)
            }.collect { state: DeviceState -> 
                lastKnownState = state
                updateUI(state) 
            }
        }
    }

    private fun updateUI(state: DeviceState) {
        val ramFormatted = if (state.ramAvailable > 1024) String.format(Locale.US, "%.1f GB", state.ramAvailable / 1024f) else "${state.ramAvailable} MB"
        ramLabel.text = "RAM Free: $ramFormatted"
        val ramRatio = if (state.totalRam > 0) state.ramAvailable.toDouble() / state.totalRam else 1.0
        ramIndicator.setImageResource(when { ramRatio > 0.30 -> R.drawable.ic_check_green; ramRatio > 0.20 -> R.drawable.dot_orange; else -> R.drawable.dot_red })
        cpuLabel.text = "CPU Usage: ${state.cpuUsage}%"
        cpuIndicator.setImageResource(when { state.cpuUsage < 40 -> R.drawable.dot_green; state.cpuUsage < 80 -> R.drawable.dot_orange; else -> R.drawable.dot_red })
        val networkIcon = if (state.networkQuality != NetworkQuality.POOR && state.networkQuality != NetworkQuality.UNKNOWN) "🔄" else "⚠️"
        val networkStr = when(state.networkQuality) {
            NetworkQuality.EXCELLENT -> "STRONG (85 Mbps)"
            NetworkQuality.GOOD -> "STRONG (25 Mbps)"
            NetworkQuality.MODERATE -> "MODERATE (10 Mbps)"
            NetworkQuality.POOR -> "WEAK (3 Mbps)"
            else -> "UNKNOWN"
        }
        networkLabel.text = "Network: $networkIcon $networkStr"
        batteryLabel.text = "Battery Level: ${state.batteryLevel.toInt()}%"
        val detail = decisionEngine.determineStrategy(state)
        val displayStrategy = if (privacyModeToggle.isChecked) Strategy.LOCAL else detail.strategy
        insightModeText.text = displayStrategy.name
        val modeColor = when(displayStrategy) { Strategy.LOCAL -> R.color.mode_local; Strategy.CLOUD -> R.color.accent_blue }
        insightModeText.setTextColor(ContextCompat.getColor(this, modeColor))
    }
}
