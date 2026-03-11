# Dynamic Edge AI

**Dynamic Edge AI** is an advanced Android application designed to optimize artificial intelligence inference by intelligently adapting to real-time device resource constraints. The system continuously monitors system health—including CPU usage, RAM availability, battery levels, thermal states, and network quality—to dynamically select the most efficient execution strategy (Local, Hybrid, or Cloud).

## 🚀 Key Features

- **Real-time Resource Monitoring:** High-frequency tracking of device metrics using Android System APIs and Coroutine Flows.
- **Intelligent Decision Engine:** An adaptive logic layer that evaluates environment constraints to choose optimal AI models.
- **Adaptive Execution Modes:**
  - **Local Lightweight:** Minimal resource consumption for low-power or offline scenarios.
  - **Hybrid:** Balanced performance utilizing both edge processing and cloud verification.
  - **Cloud Heavy:** High-performance inference offloaded to powerful remote models when resources and network allow.
- **Modern Chat Interface:** A professional, conversational UI providing transparent "System Insights" and "Execution Info" for every AI interaction.

## 🛠 Project Architecture

The project is organized into several core modules:
- **`monitor`:** Contains specialized monitors for Battery, CPU, RAM, Thermal, and Network states.
- **`engine`:** The "Brain" of the app, containing the `DecisionEngine` and strategy definitions.
- **`ml`:** Infrastructure for local (TFLite) and cloud-based AI inference.
- **`ui`:** Material 3 based activities and layouts following the latest Android design patterns.

## 📱 System Status Indicators

The app provides visual feedback on system health:
- ✅ **Green:** Optimal (e.g., CPU < 40%, RAM > 800MB)
- 🟠 **Orange:** Moderate Load (e.g., CPU < 80%, RAM > 400MB)
- 🔴 **Red:** Critical/Throttled (High load or extremely low resources)

## 🛠 Setup & Installation

1. Clone the repository:
   ```bash
   git clone https://github.com/mpsinghji/DynamicEdgeAI.git
   ```
2. Open the project in **Android Studio (Iguana or newer)**.
3. Ensure you have the **Android SDK 34** installed.
4. Sync Gradle and run the `:app` module on an emulator or physical device.

## 📄 License
This project is part of a research initiative for adaptive edge computing.
