# 📱 Dynamic Edge AI

**Dynamic Edge AI** is an Android research application that intelligently decides where AI inference should run — **on the device (Edge)** or **in the Cloud** — based on real-time device conditions and user privacy preferences.

The system continuously monitors **RAM availability, device thermal state, and network quality**, then dynamically selects the most efficient AI execution strategy. This project explores **adaptive edge computing for mobile AI systems**.

---

# 🚀 Features

### 🔄 Dynamic AI Execution
The system automatically selects between **local inference** and **cloud inference** depending on device health and connectivity.

### 🔒 Privacy Mode
Users can enable **Privacy Mode**, which forces all AI processing to run locally so messages **never leave the device**.

### 🧠 Resource-Aware Decision Engine
A rule-based decision engine evaluates live telemetry to determine the best execution strategy:
* RAM availability (RAM ratio)
* Device thermal state
* Network quality
* Cloud latency

### 📊 Explainable Decisions
Each AI response includes a **reason string** explaining why a strategy was selected.
> **Example:** > **Strategy:** CLOUD  
> **Reason:** Device temperature high

### 💬 Modern Chat Interface
A clean messaging interface built with **Material Design** provides transparency about system state and execution mode.

---

# 🏗 System Architecture

The decision-making flow follows a structured pipeline:

**User Message** ↓  
**Privacy Mode Check** ↓  
**Decision Engine** (RAM + Thermal + Network)  
↓  
**Strategy Selection** ↓  
**LOCAL LLM** (TinyLlama) | **CLOUD LLM** (Gemini API)  
↓  
**AI Response**

---

# ⚙️ Execution Strategies

| Feature | Local Inference | Cloud Inference |
| :--- | :--- | :--- |
| **Model** | TinyLlama (Quantized) | Google Gemini API |
| **Used When** | Network unavailable, Privacy mode ON, Sufficient resources | Device overheating, Low RAM, Complex inference |
| **Benefits** | Offline use, Privacy, No network dependency | High model capability, Faster complex tasks |

---

# 🧠 Decision Engine Logic

The Decision Engine dynamically selects execution strategy based on device state.

### Priority Order:
1.  **Privacy Mode** (Highest)
2.  **Thermal Safety**
3.  **RAM Availability**
4.  **Network Quality** (Lowest)

### Example Rules:
* `Privacy Mode ON` → **LOCAL**
* `High temperature` → **CLOUD**
* `RAM ratio < 30%` → **CLOUD**
* `Network unavailable` → **LOCAL**
* `Otherwise` → **LOCAL**

### Hysteresis (Stability Logic):
To prevent rapid switching ("flapping") between states:
* Switch to **CLOUD** if RAM < 30%
* Return to **LOCAL** only if RAM > 40%

---

# 📊 Research Motivation

Mobile AI systems face a key challenge known as the **Execution Paradox**:
* **Edge execution** → privacy + low latency
* **Cloud execution** → stronger models

**Dynamic Edge AI** explores how mobile devices can **adaptively choose the optimal execution location** based on real-time device conditions.

---

# 🛠 Setup & Installation

### 1. Clone the Repository
```bash
git clone [https://github.com/mpsinghji/DynamicEdgeAI.git](https://github.com/mpsinghji/DynamicEdgeAI.git)