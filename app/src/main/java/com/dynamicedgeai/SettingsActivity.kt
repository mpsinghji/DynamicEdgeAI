package com.dynamicedgeai

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.data.MemoryManager
import com.dynamicedgeai.util.ApiKeyManager
import com.dynamicedgeai.util.AppVersion
import com.dynamicedgeai.util.PlatformType
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var radioGroupProviders: RadioGroup
    private lateinit var platformCardsContainer: LinearLayout
    private lateinit var switchSettingsMemory: Switch
    private lateinit var memoryManager: MemoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        memoryManager = MemoryManager(this)

        findViewById<ImageButton>(R.id.btnSettingsBack).setOnClickListener {
            finish()
        }

        findViewById<TextView>(R.id.txtSettingsAppVersion).text = "DynamicEdgeAI • ${AppVersion.label}"

        radioGroupProviders = findViewById(R.id.radioGroupProviders)
        platformCardsContainer = findViewById(R.id.platformCardsContainer)
        switchSettingsMemory = findViewById(R.id.switchSettingsMemory)

        setupProviderRadioGroup()
        renderPlatformCards()
        setupMemoryControls()
    }

    private fun setupProviderRadioGroup() {
        val active = ApiKeyManager.getActivePlatform(this)
        when (active) {
            PlatformType.GEMINI -> findViewById<RadioButton>(R.id.radioGemini).isChecked = true
            PlatformType.OPENAI -> findViewById<RadioButton>(R.id.radioOpenAI).isChecked = true
            PlatformType.GROQ -> findViewById<RadioButton>(R.id.radioGroq).isChecked = true
            PlatformType.OPENROUTER -> findViewById<RadioButton>(R.id.radioOpenRouter).isChecked = true
            PlatformType.ANTHROPIC -> findViewById<RadioButton>(R.id.radioAnthropic).isChecked = true
        }

        radioGroupProviders.setOnCheckedChangeListener { _, checkedId ->
            val selected = when (checkedId) {
                R.id.radioGemini -> PlatformType.GEMINI
                R.id.radioOpenAI -> PlatformType.OPENAI
                R.id.radioGroq -> PlatformType.GROQ
                R.id.radioOpenRouter -> PlatformType.OPENROUTER
                R.id.radioAnthropic -> PlatformType.ANTHROPIC
                else -> PlatformType.GEMINI
            }
            ApiKeyManager.setActivePlatform(this, selected)
            Toast.makeText(this, "Active Provider: ${selected.displayName}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderPlatformCards() {
        platformCardsContainer.removeAllViews()

        PlatformType.values().forEach { platform ->
            val cardView = layoutInflater.inflate(R.layout.item_setting_platform, platformCardsContainer, false)

            val txtName = cardView.findViewById<TextView>(R.id.txtPlatformName)
            val txtModel = cardView.findViewById<TextView>(R.id.txtPlatformModel)
            val txtStatus = cardView.findViewById<TextView>(R.id.txtPlatformKeyStatus)
            val btnGetKey = cardView.findViewById<Button>(R.id.btnPlatformGetKey)
            val btnClear = cardView.findViewById<Button>(R.id.btnPlatformClear)
            val btnConfigure = cardView.findViewById<Button>(R.id.btnPlatformConfigure)

            txtName.text = platform.displayName
            txtModel.text = "Default Model: ${platform.defaultModel}"

            fun updateStatus() {
                val hasKey = ApiKeyManager.hasApiKey(this, platform)
                if (hasKey) {
                    val masked = ApiKeyManager.getMaskedApiKey(this, platform)
                    txtStatus.text = "● Key Configured: $masked"
                    txtStatus.setTextColor(0xFF2E7D32.toInt())
                    btnClear.visibility = View.VISIBLE
                    btnConfigure.text = "Edit Key"
                } else {
                    txtStatus.text = "○ Key not configured"
                    txtStatus.setTextColor(0xFF757575.toInt())
                    btnClear.visibility = View.GONE
                    btnConfigure.text = "Add Key"
                }
            }

            updateStatus()

            btnGetKey.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(platform.consoleUrl))
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Unable to open browser: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            btnClear.setOnClickListener {
                ApiKeyManager.clearApiKey(this, platform)
                updateStatus()
                Toast.makeText(this, "${platform.displayName} key cleared", Toast.LENGTH_SHORT).show()
            }

            btnConfigure.setOnClickListener {
                showEditKeyDialog(platform) {
                    updateStatus()
                }
            }

            platformCardsContainer.addView(cardView)
        }
    }

    private fun showEditKeyDialog(platform: PlatformType, onSaved: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_api_key, null)
        val txtHeader = dialogView.findViewById<TextView>(R.id.txtCurrentKeyStatus)
        val editKey = dialogView.findViewById<EditText>(R.id.editApiKeyInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelApiKey)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearApiKey)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveApiKey)

        txtHeader.text = "${platform.displayName} Key (${platform.keyHint})"
        if (ApiKeyManager.hasApiKey(this, platform)) {
            editKey.setText(ApiKeyManager.getApiKey(this, platform))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnClear.setOnClickListener {
            ApiKeyManager.clearApiKey(this, platform)
            onSaved()
            Toast.makeText(this, "Key cleared", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val input = editKey.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "Please enter a key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiKeyManager.setApiKey(this, platform, input)
            onSaved()
            Toast.makeText(this, "${platform.displayName} key saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupMemoryControls() {
        switchSettingsMemory.isChecked = memoryManager.isMemoryEnabled
        switchSettingsMemory.setOnCheckedChangeListener { _, isChecked ->
            memoryManager.isMemoryEnabled = isChecked
            Toast.makeText(this, if (isChecked) "Chat Memory Enabled" else "Chat Memory Disabled", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnManageAllMemories).setOnClickListener {
            showMemoriesBottomSheet()
        }
    }

    private fun showMemoriesBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_memories, null)
        val container = view.findViewById<LinearLayout>(R.id.memoryListContainer)
        val toggle = view.findViewById<Switch>(R.id.toggleEnableMemory)
        val txtEmpty = view.findViewById<TextView>(R.id.txtEmptyMemories)

        toggle.isChecked = memoryManager.isMemoryEnabled
        toggle.setOnCheckedChangeListener { _, isChecked ->
            memoryManager.isMemoryEnabled = isChecked
            switchSettingsMemory.isChecked = isChecked
        }

        lifecycleScope.launch {
            val memories = memoryManager.getAllMemories()
            if (memories.isEmpty()) {
                txtEmpty.visibility = View.VISIBLE
            } else {
                txtEmpty.visibility = View.GONE
                memories.forEach { mem ->
                    val row = layoutInflater.inflate(R.layout.item_memory_row, container, false)
                    row.findViewById<TextView>(R.id.txtMemoryFact).text = mem.keyFact
                    row.findViewById<TextView>(R.id.txtMemoryCategory).text = mem.category.uppercase()
                    row.findViewById<ImageButton>(R.id.btnDeleteMemory).setOnClickListener {
                        lifecycleScope.launch {
                            memoryManager.deleteMemory(mem.memoryId)
                            container.removeView(row)
                            if (container.childCount == 0) txtEmpty.visibility = View.VISIBLE
                        }
                    }
                    container.addView(row)
                }
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }
}
