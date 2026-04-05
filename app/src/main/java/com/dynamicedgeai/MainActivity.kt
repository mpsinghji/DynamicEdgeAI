package com.dynamicedgeai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.cloud.CloudModelRunner
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.Strategy
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.local.LocalModel
import com.dynamicedgeai.monitor.*
import com.dynamicedgeai.router.MessageRouter
import com.dynamicedgeai.util.ModelStatus
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    
    private var lastKnownState: DeviceState? = null

    // Guard: prevents double-send when the IME "Send" key fires both
    // the EditorAction callback AND a sendButton click simultaneously.
    private var isSending = false

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

        // Default Visible Insights
        insightsContainer.visibility = View.VISIBLE
        insightsChevron.rotation = 0f

        insightsToggle.setOnClickListener { toggleInsights() }
        sendButton.setOnClickListener { trySendMessage() }
        menuButton.setOnClickListener { showBottomSheetMenu() }

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
        // Block re-entry: do nothing if we are already waiting for a response.
        if (isSending) return

        val text = messageInput.text.toString()
        if (text.isNotBlank()) {
            // Guard: If privacy mode is ON and no local model is available, block
            if (privacyModeToggle.isChecked && !localModelRunner.hasAnyModelAvailable()) {
                Toast.makeText(
                    this,
                    "No local model available. Download a model from Models menu.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            // Guard: If privacy mode is ON, ensure current model is valid
            if (privacyModeToggle.isChecked && !localModelRunner.isModelAvailable(localModelRunner.currentModel)) {
                val available = localModelRunner.getFirstAvailableModel()
                if (available != null) {
                    localModelRunner.switchModel(available)
                    Toast.makeText(this, "Auto-switched to ${available.displayName}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this,
                        "Current model is corrupted. Download or repair from Models menu.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            isSending = true
            sendButton.isEnabled = false   // visual feedback: greyed-out while thinking
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
            // Unlock send now that the response has been delivered
            isSending = false
            sendButton.isEnabled = true
        }
    }

    /**
     * Shows the bottom sheet menu.
     * - Top icons: Models (open manager) | RAM (adjust)
     * - Below: Every DOWNLOADED model as a tappable row with active indicator.
     *          Tapping a non-active model switches to it immediately.
     */
    private fun showBottomSheetMenu() {
        val dialog = BottomSheetDialog(this)
        val sheetView = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_menu, null)
        dialog.setContentView(sheetView)

        // ── Top icon buttons ─────────────────────────────────────────────────
        sheetView.findViewById<View>(R.id.menuModelManager).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }
        sheetView.findViewById<View>(R.id.menuAdjustRam).setOnClickListener {
            dialog.dismiss()
            showCustomRamDialog()
        }

        // ── Downloaded model list ────────────────────────────────────────────
        val container = sheetView.findViewById<LinearLayout>(R.id.modelListContainer)
        container.removeAllViews()

        val downloadedModels = LocalModel.values().filter { localModelRunner.isModelAvailable(it) }

        if (downloadedModels.isEmpty()) {
            val hint = TextView(this).apply {
                text = "No models downloaded yet.\nTap  Models  above to download one."
                textSize = 13f
                setTextColor(0xFF999999.toInt())
                gravity = android.view.Gravity.CENTER
                setPadding(32, 24, 32, 16)
            }
            container.addView(hint)
        } else {
            downloadedModels.forEach { model ->
                val isActive = (localModelRunner.currentModel == model)

                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(48, 22, 48, 22)
                    isClickable = true
                    isFocusable = true
                    // Resolve the theme attribute to an actual drawable resource ID
                    val tv = android.util.TypedValue()
                    if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
                        setBackgroundResource(tv.resourceId)
                    }
                }

                // Checkmark (visible only for active model)
                val icon = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(40, 40)
                    visibility = if (isActive) View.VISIBLE else View.INVISIBLE
                    if (isActive) setImageResource(R.drawable.ic_check_green)
                }
                row.addView(icon)

                // Model name
                val nameView = TextView(this).apply {
                    val lp = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    ).also { it.marginStart = 16 }
                    layoutParams = lp
                    text = model.displayName
                    textSize = 14f
                    setTextColor(if (isActive) 0xFF1B8C5E.toInt() else 0xFF333333.toInt())
                    if (isActive) setTypeface(null, android.graphics.Typeface.BOLD)
                }
                row.addView(nameView)

                // "ACTIVE" badge
                if (isActive) {
                    val badge = TextView(this).apply {
                        text = "ACTIVE"
                        textSize = 10f
                        setTextColor(0xFF1B8C5E.toInt())
                        setPadding(14, 4, 14, 4)
                        try {
                            background = ContextCompat.getDrawable(
                                this@MainActivity, R.drawable.badge_recommended
                            )
                        } catch (_: Exception) {}
                    }
                    row.addView(badge)
                }

                row.setOnClickListener {
                    if (!isActive) {
                        localModelRunner.switchModel(model)
                        Toast.makeText(
                            this, "Switched to ${model.displayName}", Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }

                container.addView(row)
            }
        }

        dialog.show()
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
