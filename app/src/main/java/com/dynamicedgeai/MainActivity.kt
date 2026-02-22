package com.dynamicedgeai

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.ExecutionStrategy
import com.dynamicedgeai.monitor.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var cpuMonitor: CpuMonitor
    private lateinit var ramMonitor: RamMonitor
    private val decisionEngine = DecisionEngine()

    private lateinit var ramLabel: TextView
    private lateinit var cpuLabel: TextView
    private lateinit var networkLabel: TextView
    private lateinit var batteryLabel: TextView
    
    private lateinit var ramIndicator: ImageView
    private lateinit var cpuIndicator: ImageView
    
    private lateinit var insightModeText: TextView
    private lateinit var execInfoTitle: TextView
    private lateinit var strategyModel: TextView
    private lateinit var strategyReason: TextView
    private lateinit var strategyLatency: TextView
    private lateinit var strategyNetworkUsed: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ramLabel = findViewById(R.id.ramLabel)
        cpuLabel = findViewById(R.id.cpuLabel)
        networkLabel = findViewById(R.id.networkLabel)
        batteryLabel = findViewById(R.id.batteryLabel)
        
        ramIndicator = findViewById(R.id.ramIndicator)
        cpuIndicator = findViewById(R.id.cpuIndicator)

        insightModeText = findViewById(R.id.insightModeText)
        execInfoTitle = findViewById(R.id.execInfoTitle)
        strategyModel = findViewById(R.id.strategyModel)
        strategyReason = findViewById(R.id.strategyReason)
        strategyLatency = findViewById(R.id.strategyLatency)
        strategyNetworkUsed = findViewById(R.id.strategyNetworkUsed)

        batteryMonitor = BatteryMonitor(this)
        thermalMonitor = ThermalMonitor(this)
        networkMonitor = NetworkMonitor(this)
        cpuMonitor = CpuMonitor()
        ramMonitor = RamMonitor(this)

        startResourceMonitoring()
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
                    ramAvailable = ram
                )
            }.collect { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: DeviceState) {
        // RAM Free formatting
        val ramFormatted = if (state.ramAvailable > 1024) String.format(Locale.US, "%.1f GB", state.ramAvailable / 1024f) else "${state.ramAvailable} MB"
        ramLabel.text = "RAM Free: $ramFormatted"
        
        // Dynamic Indicator Logic
        // RAM: Green if > 800MB, Orange if > 400MB, Red otherwise
        ramIndicator.setImageResource(when {
            state.ramAvailable > 800 -> R.drawable.ic_check_green
            state.ramAvailable > 400 -> R.drawable.dot_orange
            else -> R.drawable.dot_red
        })

        // CPU: Green if < 40%, Orange if < 80%, Red otherwise
        cpuLabel.text = "CPU Usage: ${state.cpuUsage}%"
        cpuIndicator.setImageResource(when {
            state.cpuUsage < 40 -> R.drawable.dot_green
            state.cpuUsage < 80 -> R.drawable.dot_orange
            else -> R.drawable.dot_red
        })
        
        // Network status display
        val networkIcon = if (state.networkQuality != NetworkQuality.POOR && state.networkQuality != NetworkQuality.UNKNOWN) "🔄" else "⚠️"
        val networkStr = when(state.networkQuality) {
            NetworkQuality.EXCELLENT -> "EXCELLENT (85 Mbps)"
            NetworkQuality.GOOD -> "STRONG (25 Mbps)"
            NetworkQuality.MODERATE -> "MODERATE (10 Mbps)"
            NetworkQuality.POOR -> "WEAK (3 Mbps)"
            NetworkQuality.UNKNOWN -> "UNKNOWN"
        }
        networkLabel.text = "Network: $networkIcon $networkStr"
        
        batteryLabel.text = "Battery Level: ${state.batteryLevel.toInt()}%"

        // Decision Engine Strategy
        val detail = decisionEngine.determineStrategyDetail(state)
        
        // System Insights Section update
        insightModeText.text = detail.mode.name
        val modeColor = when(detail.mode) {
            ExecutionStrategy.LOCAL_LIGHTWEIGHT -> R.color.mode_local
            ExecutionStrategy.HYBRID -> R.color.accent_blue
            ExecutionStrategy.CLOUD_HEAVY -> R.color.mode_cloud
        }
        insightModeText.setTextColor(ContextCompat.getColor(this, modeColor))

        // Execution Info Section update
        execInfoTitle.text = "Execution Info: ${detail.mode.name}"
        strategyModel.text = "Models Used: ${detail.modelName}"
        strategyReason.text = "Decision Reason: ${detail.reason}"
        strategyLatency.text = "Latency: ${detail.latency}"
        strategyNetworkUsed.text = "Network Used: ${detail.networkUsed}"
    }
}
