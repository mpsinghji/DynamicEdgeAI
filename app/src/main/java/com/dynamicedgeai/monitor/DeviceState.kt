package com.dynamicedgeai.monitor

data class DeviceState(
    val batteryLevel: Float = 100.0f,
    val isCharging: Boolean = false,
    val networkQuality: NetworkQuality = NetworkQuality.UNKNOWN,
    val thermalState: ThermalState = ThermalState.NORMAL,
    val cpuUsage: Int = 0,
    val ramAvailable: Long = 0, // in MB
    val totalRam: Long = 0      // in MB
)

enum class NetworkQuality { POOR, MODERATE, GOOD, EXCELLENT, UNKNOWN }
enum class ThermalState { NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY }
