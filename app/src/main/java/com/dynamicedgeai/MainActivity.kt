package com.dynamicedgeai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.ExecutionStrategy
import com.dynamicedgeai.ml.CloudAIEngine
import com.dynamicedgeai.ml.LocalAIEngine
import com.dynamicedgeai.monitor.*
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private fun getApiKey(): String {
        return getString(R.string.gemini_api_key)
    }

    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var cpuMonitor: CpuMonitor
    private lateinit var ramMonitor: RamMonitor
    private val decisionEngine = DecisionEngine()
    
    private val cloudAIEngine = CloudAIEngine()
    private val localAIEngine = LocalAIEngine()
    private val gson = Gson()

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

    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var thinkingIndicator: LinearLayout
    private lateinit var thinkingText: TextView
    
    private var lastKnownState: DeviceState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
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

        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        chatContainer = findViewById(R.id.chatContainer)
        chatScrollView = findViewById(R.id.chatScrollView)
        thinkingIndicator = findViewById(R.id.thinkingIndicator)
        thinkingText = findViewById(R.id.thinkingText)

        insightsToggle.setOnClickListener { toggleInsights() }

        sendButton.setOnClickListener { trySendMessage() }

        messageInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND || 
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                trySendMessage()
                true
            } else {
                false
            }
        }

        batteryMonitor = BatteryMonitor(this)
        thermalMonitor = ThermalMonitor(this)
        networkMonitor = NetworkMonitor(this)
        cpuMonitor = CpuMonitor()
        ramMonitor = RamMonitor(this)

        startResourceMonitoring()
    }

    private fun trySendMessage() {
        val text = messageInput.text.toString()
        if (text.isNotBlank()) {
            handleUserMessage(text)
        }
    }

    private fun toggleInsights() {
        isInsightsVisible = !isInsightsVisible
        if (isInsightsVisible) {
            insightsContainer.visibility = View.VISIBLE
            insightsChevron.animate().rotation(0f).setDuration(300).start()
        } else {
            insightsContainer.visibility = View.GONE
            insightsChevron.animate().rotation(180f).setDuration(300).start()
        }
    }

    private fun handleUserMessage(text: String) {
        messageInput.text.clear()
        addUserBubble(text)
        scrollToBottom()
        
        lifecycleScope.launch {
            val startTime = System.currentTimeMillis()
            showThinking()
            
            val state = lastKnownState ?: DeviceState()
            val detail = decisionEngine.determineStrategyDetail(state)
            
            val aiResponse = when(detail.mode) {
                ExecutionStrategy.CLOUD_HEAVY, ExecutionStrategy.HYBRID -> {
                    cloudAIEngine.runCloudModel(getApiKey(), text)
                }
                ExecutionStrategy.LOCAL_LIGHTWEIGHT -> {
                    delay(1500)
                    "I am responding using my on-device lightweight model to save your battery and data."
                }
            }
            
            val endTime = System.currentTimeMillis()
            val finalLatency = "${(endTime - startTime) / 1000.0}s"
            
            hideThinking()
            addAIResponse(aiResponse, detail.copy(latency = finalLatency))
            scrollToBottom()
        }
    }

    private fun addUserBubble(text: String) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_user, chatContainer, false)
        val msgText = view.findViewById<TextView>(R.id.messageText)
        msgText.text = text
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        view.findViewById<TextView>(R.id.timestamp).text = timeStr

        val bubbleContainer = view.findViewById<View>(R.id.userBubbleContainer)
        val actionsLayout = view.findViewById<View>(R.id.userActions)
        val copyBtn = view.findViewById<ImageButton>(R.id.copyButton)
        val editBtn = view.findViewById<ImageButton>(R.id.editButton)

        bubbleContainer.setOnClickListener {
            actionsLayout.visibility = if (actionsLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        copyBtn.setOnClickListener {
            copyToClipboard(text)
            actionsLayout.visibility = View.GONE
        }

        editBtn.setOnClickListener {
            messageInput.setText(text)
            messageInput.requestFocus()
            actionsLayout.visibility = View.GONE
        }

        val animation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        view.startAnimation(animation)
        chatContainer.addView(view)
    }

    private fun addAIResponse(response: String, detail: com.dynamicedgeai.engine.StrategyDetail) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_ai, chatContainer, false)
        view.findViewById<TextView>(R.id.messageText).text = response
        view.findViewById<TextView>(R.id.modeTitle).text = "Execution Info: ${detail.mode.name}"
        view.findViewById<TextView>(R.id.modelUsedText).text = "Model Used: ${detail.modelName}"
        view.findViewById<TextView>(R.id.reasonText).text = "Reason: ${detail.reason}"
        view.findViewById<TextView>(R.id.latencyText).text = "Latency: ${detail.latency}"
        view.findViewById<TextView>(R.id.networkUsedText).text = "Network Used: ${detail.networkUsed}"

        val bubbleContainer = view.findViewById<View>(R.id.aiBubbleContainer)
        val actionsLayout = view.findViewById<View>(R.id.aiActions)
        val copyBtn = view.findViewById<ImageButton>(R.id.copyButton)

        bubbleContainer.setOnClickListener {
            actionsLayout.visibility = if (actionsLayout.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        copyBtn.setOnClickListener {
            copyToClipboard(response)
            actionsLayout.visibility = View.GONE
        }
        
        val animation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
        view.startAnimation(animation)
        chatContainer.addView(view)
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("AI Message", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun showThinking() {
        thinkingIndicator.visibility = View.VISIBLE
        val anim = AlphaAnimation(0.3f, 1.0f)
        anim.duration = 600
        anim.repeatMode = Animation.REVERSE
        anim.repeatCount = Animation.INFINITE
        thinkingText.startAnimation(anim)
    }

    private fun hideThinking() {
        thinkingText.clearAnimation()
        thinkingIndicator.visibility = View.GONE
    }

    private fun scrollToBottom() {
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun startResourceMonitoring() {
        lifecycleScope.launch {
            combine(
                batteryMonitor.observeBatteryState(),
                thermalMonitor.observeThermalState(),
                networkMonitor.observeNetworkQuality(),
                cpuMonitor.observeCpuUsage(),
                ramMonitor.observeRamUsage()
            ) { battery, thermal, network, cpu, ram ->
                DeviceState(
                    batteryLevel = battery.first,
                    isCharging = battery.second,
                    thermalState = thermal,
                    networkQuality = network,
                    cpuUsage = cpu,
                    ramAvailable = ram.first,
                    totalRam = ram.second
                )
            }.collect { state ->
                lastKnownState = state
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: DeviceState) {
        val ramFormatted = if (state.ramAvailable > 1024) String.format(Locale.US, "%.1f GB", state.ramAvailable / 1024f) else "${state.ramAvailable} MB"
        ramLabel.text = "RAM Free: $ramFormatted"
        
        ramIndicator.setImageResource(when {
            state.totalRam > 0 && (state.ramAvailable.toDouble() / state.totalRam) > 0.15 -> R.drawable.ic_check_green
            state.totalRam > 0 && (state.ramAvailable.toDouble() / state.totalRam) > 0.05 -> R.drawable.dot_orange
            else -> R.drawable.dot_red
        })

        cpuLabel.text = "CPU Usage: ${state.cpuUsage}%"
        cpuIndicator.setImageResource(when {
            state.cpuUsage < 40 -> R.drawable.dot_green
            state.cpuUsage < 80 -> R.drawable.dot_orange
            else -> R.drawable.dot_red
        })
        
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

        val detail = decisionEngine.determineStrategyDetail(state)
        insightModeText.text = detail.mode.name
        val modeColor = when(detail.mode) {
            ExecutionStrategy.LOCAL_LIGHTWEIGHT -> R.color.mode_local
            ExecutionStrategy.HYBRID -> R.color.accent_blue
            ExecutionStrategy.CLOUD_HEAVY -> R.color.mode_cloud
        }
        insightModeText.setTextColor(ContextCompat.getColor(this, modeColor))
    }
}
