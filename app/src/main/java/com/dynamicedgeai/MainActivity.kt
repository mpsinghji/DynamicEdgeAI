package com.dynamicedgeai

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Button
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.ExecutionStrategy
import com.dynamicedgeai.ml.CloudAIEngine
import com.dynamicedgeai.ml.LocalAIEngine
import com.dynamicedgeai.monitor.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var cpuMonitor: CpuMonitor
    private lateinit var ramMonitor: RamMonitor
    private val decisionEngine = DecisionEngine()
    
    private val localAIEngine = LocalAIEngine()
    private val cloudAIEngine = CloudAIEngine()

    // UI elements
    private lateinit var batteryText: TextView
    private lateinit var thermalText: TextView
    private lateinit var networkText: TextView
    private lateinit var cpuText: TextView
    private lateinit var ramText: TextView
    private lateinit var strategyText: TextView
    private lateinit var inferenceResultText: TextView
    private lateinit var runTaskButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI
        batteryText = findViewById(R.id.batteryText)
        thermalText = findViewById(R.id.thermalText)
        networkText = findViewById(R.id.networkText)
        cpuText = findViewById(R.id.cpuText)
        ramText = findViewById(R.id.ramText)
        strategyText = findViewById(R.id.strategyText)
        inferenceResultText = findViewById(R.id.inferenceResultText)
        runTaskButton = findViewById(R.id.runTaskButton)

        batteryMonitor = BatteryMonitor(this)
        thermalMonitor = ThermalMonitor(this)
        networkMonitor = NetworkMonitor(this)
        cpuMonitor = CpuMonitor()
        ramMonitor = RamMonitor(this)

        startResourceMonitoring()
        
        runTaskButton.setOnClickListener {
            Log.d("MainActivity", "Manual Run Task Triggered")
            // We can add logic to perform a specific action here if needed
        }
    }

    private fun startResourceMonitoring() {
        lifecycleScope.launch {
            // Combine all flows to get real-time DeviceState
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
                Log.d("MainActivity", "Device State Updated: $state")
                
                // Update UI
                updateUI(state)
                
                // Determine the best strategy
                val strategy = decisionEngine.determineStrategy(state)
                strategyText.text = "Current Mode: $strategy"
                
                // Execute sample task
                executeAITask(strategy, "Sample input data")
            }
        }
    }

    private fun updateUI(state: DeviceState) {
        batteryText.text = "Battery Level: ${state.batteryLevel.toInt()}%"
        thermalText.text = "Thermal State: ${state.thermalState}"
        networkText.text = "Network Quality: ${state.networkQuality}"
        cpuText.text = "CPU Usage: ${state.cpuUsage}%"
        ramText.text = "RAM Available: ${state.ramAvailable} MB"
    }

    private fun executeAITask(strategy: ExecutionStrategy, input: Any) {
        val result = when (strategy) {
            ExecutionStrategy.LOCAL_LIGHTWEIGHT -> localAIEngine.runLightweightModel(input)
            ExecutionStrategy.LOCAL_HEAVYWEIGHT -> localAIEngine.runHeavyweightModel(input)
            ExecutionStrategy.CLOUD_ONLY -> cloudAIEngine.runCloudModel(input)
            ExecutionStrategy.HYBRID -> {
                val local = localAIEngine.runLightweightModel(input)
                val cloud = cloudAIEngine.runCloudModel(input)
                "Hybrid: $local | $cloud"
            }
        }
        inferenceResultText.text = "Last Inference: $result"
    }
}
