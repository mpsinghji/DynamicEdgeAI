package com.dynamicedgeai

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.cloud.CloudModelRunner
import com.dynamicedgeai.data.*
import com.dynamicedgeai.engine.DecisionEngine
import com.dynamicedgeai.engine.Strategy
import com.dynamicedgeai.local.LocalModel
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.local.ModelCapabilityRegistry
import com.dynamicedgeai.monitor.*
import com.dynamicedgeai.router.ChatMessage
import com.dynamicedgeai.router.MessageRouter
import com.dynamicedgeai.updater.AppUpdateManager
import com.dynamicedgeai.updater.UpdateBannerBinder
import com.dynamicedgeai.updater.UpdateCardBinder
import com.dynamicedgeai.updater.UpdateState
import com.dynamicedgeai.util.ApiKeyManager
import com.dynamicedgeai.util.AppVersion
import com.dynamicedgeai.util.ModelStatus
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val tag = "MainActivity"

    companion object {
        private var hasCheckedUpdatesOnLaunch = false
    }

    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var cpuMonitor: CpuMonitor
    private lateinit var ramMonitor: RamMonitor
    
    private val decisionEngine = DecisionEngine()
    private lateinit var localModelRunner: LocalModelRunner
    private val cloudModelRunner by lazy { CloudModelRunner(this) }
    private lateinit var messageRouter: MessageRouter

    // Persistence & Context Architecture
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var memoryManager: MemoryManager
    private lateinit var contextBuilder: ContextBuilder
    private var activeConversation: ConversationEntity? = null

    // UI Elements
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

    private lateinit var privacyModeToggle: Switch
    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var menuButton: ImageButton
    private lateinit var chatContainer: LinearLayout
    private lateinit var chatScrollView: ScrollView
    private lateinit var thinkingIndicator: LinearLayout
    private lateinit var thinkingText: TextView

    // Image Attachment UI
    private lateinit var btnAttachImage: ImageButton
    private lateinit var imagePreviewContainer: LinearLayout
    private lateinit var imgPreviewThumbnail: ImageView
    private lateinit var txtPreviewFilename: TextView
    private lateinit var btnRemoveImage: ImageButton
    private var selectedAttachmentPath: String? = null

    private var lastKnownState: DeviceState? = null
    private var isSending = false

    // Image Picker Result Contract
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImagePicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        localModelRunner = LocalModelRunner.getInstance(this)
        messageRouter = MessageRouter(decisionEngine, localModelRunner, cloudModelRunner)

        conversationRepo = ConversationRepository(this)
        memoryManager = MemoryManager(this)
        contextBuilder = ContextBuilder(memoryManager)

        // Initialize UI Elements
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

        // Image Attachment UI
        btnAttachImage = findViewById(R.id.btnAttachImage)
        imagePreviewContainer = findViewById(R.id.imagePreviewContainer)
        imgPreviewThumbnail = findViewById(R.id.imgPreviewThumbnail)
        txtPreviewFilename = findViewById(R.id.txtPreviewFilename)
        btnRemoveImage = findViewById(R.id.btnRemoveImage)

        // Header Quick Action Buttons
        findViewById<ImageButton>(R.id.btnHeaderNewChat)?.setOnClickListener { startNewConversation() }
        findViewById<ImageButton>(R.id.btnHeaderConversations)?.setOnClickListener { showConversationsDialog() }
        findViewById<ImageButton>(R.id.btnHeaderSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // In-App Version display from single source of truth
        findViewById<TextView>(R.id.appSubtitleText)?.text = "Edge & Cloud Intelligence • ${AppVersion.shortLabel}"

        // Wire Top Floating Update Banner & Auto-check ONCE on Startup
        val updateBannerView = findViewById<View>(R.id.includedUpdateBanner)
        if (updateBannerView != null) {
            val bannerBinder = UpdateBannerBinder(updateBannerView, this) { state ->
                when (state) {
                    is UpdateState.UpdateAvailable -> {
                        lifecycleScope.launch {
                            AppUpdateManager.getInstance().downloadUpdate(this@MainActivity, state.info)
                        }
                    }
                    is UpdateState.DownloadCompleted -> {
                        AppUpdateManager.getInstance().installUpdate(this, state.apkFile)
                    }
                    else -> {}
                }
            }
            bannerBinder.bind()

            // Automatically check for updates ONCE on app launch
            if (!hasCheckedUpdatesOnLaunch) {
                hasCheckedUpdatesOnLaunch = true
                lifecycleScope.launch {
                    AppUpdateManager.getInstance().checkForUpdate(this@MainActivity)
                }
            }
        }

        // Default Visible Insights
        insightsContainer.visibility = View.VISIBLE
        insightsChevron.rotation = 0f

        insightsToggle.setOnClickListener { toggleInsights() }
        sendButton.setOnClickListener { trySendMessage() }
        menuButton.setOnClickListener { showBottomSheetMenu() }

        btnAttachImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        btnRemoveImage.setOnClickListener {
            clearSelectedImage()
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
        loadOrCreateInitialConversation()
    }

    private fun handleImagePicked(uri: Uri) {
        try {
            val attachmentsDir = File(filesDir, "attachments").apply { mkdirs() }
            val destFile = File(attachmentsDir, "img_${System.currentTimeMillis()}.jpg")
            
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            if (destFile.exists() && destFile.length() > 0) {
                selectedAttachmentPath = destFile.absolutePath
                val bitmap = BitmapFactory.decodeFile(destFile.absolutePath)
                imgPreviewThumbnail.setImageBitmap(bitmap)
                txtPreviewFilename.text = destFile.name
                imagePreviewContainer.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to copy picked image", e)
            Toast.makeText(this, "Failed to load selected image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearSelectedImage() {
        selectedAttachmentPath = null
        imagePreviewContainer.visibility = View.GONE
        imgPreviewThumbnail.setImageDrawable(null)
    }

    // ── Multi-Conversation Architecture ──────────────────────────────────────

    private fun loadOrCreateInitialConversation() {
        lifecycleScope.launch {
            val conversations = conversationRepo.getAllConversations()
            val savedConvId = getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE)
                .getString("last_active_conv_id", null)

            val conv = conversations.firstOrNull { it.conversationId == savedConvId }
                ?: conversations.firstOrNull()
                ?: conversationRepo.createConversation("New Chat", localModelRunner.currentModel.name)

            setActiveConversation(conv)
        }
    }

    private fun setActiveConversation(conv: ConversationEntity) {
        activeConversation = conv
        getSharedPreferences("ChatPrefs", Context.MODE_PRIVATE).edit()
            .putString("last_active_conv_id", conv.conversationId)
            .apply()

        findViewById<TextView>(R.id.appTitleText)?.text = "DynamicEdgeAI"
        findViewById<TextView>(R.id.appSubtitleText)?.text = if (conv.title != "New Chat") {
            "${conv.title} • ${AppVersion.shortLabel}"
        } else {
            "Edge & Cloud Intelligence • ${AppVersion.shortLabel}"
        }
        loadMessagesForActiveConversation()
    }

    private fun loadMessagesForActiveConversation() {
        val convId = activeConversation?.conversationId ?: return
        lifecycleScope.launch {
            val messages = conversationRepo.getMessages(convId)
            chatContainer.removeAllViews()
            // Re-add thinking indicator
            chatContainer.addView(thinkingIndicator)

            messages.forEach { msg ->
                if (msg.role == "user") {
                    renderUserBubble(msg)
                } else {
                    renderAiBubble(msg)
                }
            }
            scrollToBottom()
        }
    }

    private fun startNewConversation() {
        lifecycleScope.launch {
            val newConv = conversationRepo.createConversation("New Chat", localModelRunner.currentModel.name)
            setActiveConversation(newConv)
            clearSelectedImage()
            messageInput.text.clear()
            Toast.makeText(this@MainActivity, "Started New Conversation", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showConversationsDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_conversations, null)
        val container = view.findViewById<LinearLayout>(R.id.conversationListContainer)
        val btnNew = view.findViewById<Button>(R.id.btnDialogNewChat)
        val txtEmpty = view.findViewById<TextView>(R.id.txtEmptyConversations)

        btnNew.setOnClickListener {
            dialog.dismiss()
            startNewConversation()
        }

        lifecycleScope.launch {
            val list = conversationRepo.getAllConversations()
            if (list.isEmpty()) {
                txtEmpty.visibility = View.VISIBLE
            } else {
                txtEmpty.visibility = View.GONE
                list.forEach { conv ->
                    val row = layoutInflater.inflate(R.layout.item_conversation_row, container, false)
                    row.findViewById<TextView>(R.id.txtConvTitle).text = conv.title
                    
                    val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                    row.findViewById<TextView>(R.id.txtConvTime).text = timeFormat.format(Date(conv.updatedAt))

                    val lastMsg = conversationRepo.getLastMessage(conv.conversationId)
                    row.findViewById<TextView>(R.id.txtConvPreview).text = lastMsg?.content ?: "No messages"

                    if (conv.conversationId == activeConversation?.conversationId) {
                        row.findViewById<ImageView>(R.id.imgConvIcon).setColorFilter(0xFF1E88E5.toInt())
                    }

                    row.setOnClickListener {
                        setActiveConversation(conv)
                        dialog.dismiss()
                    }

                    row.findViewById<ImageButton>(R.id.btnRenameConv).setOnClickListener {
                        showRenameConversationDialog(conv) {
                            dialog.dismiss()
                            showConversationsDialog()
                        }
                    }

                    row.findViewById<ImageButton>(R.id.btnDeleteConv).setOnClickListener {
                        showDeleteConversationDialog(conv) {
                            dialog.dismiss()
                            showConversationsDialog()
                        }
                    }

                    container.addView(row)
                }
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun showRenameConversationDialog(conv: ConversationEntity, onDone: () -> Unit) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_prompt, null)
        val txtTitle = dialogView.findViewById<TextView>(R.id.dialogTitle)
        val txtSubtitle = dialogView.findViewById<TextView>(R.id.dialogSubtitle)
        val editInput = dialogView.findViewById<EditText>(R.id.dialogEditInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnDialogConfirm)

        txtTitle.text = "Rename Conversation"
        txtSubtitle.text = "Enter a title for this conversation thread."
        editInput.setText(conv.title)
        editInput.setSelection(editInput.text.length)
        editInput.minLines = 1
        editInput.maxLines = 2
        btnConfirm.text = "Save"

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val newTitle = editInput.text.toString().trim()
            if (newTitle.isNotBlank()) {
                lifecycleScope.launch {
                    conversationRepo.updateConversationTitle(conv.conversationId, newTitle)
                    if (conv.conversationId == activeConversation?.conversationId) {
                        findViewById<TextView>(R.id.appSubtitleText)?.text = "$newTitle • ${AppVersion.shortLabel}"
                    }
                    dialog.dismiss()
                    onDone()
                }
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun showDeleteConversationDialog(conv: ConversationEntity, onDone: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Delete Conversation")
            .setMessage("Are you sure you want to delete '${conv.title}'? All messages will be permanently removed.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    conversationRepo.deleteConversation(conv.conversationId)
                    if (conv.conversationId == activeConversation?.conversationId) {
                        loadOrCreateInitialConversation()
                    }
                    onDone()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Long-Term Memory Inspection Dialog ────────────────────────────────────

    private fun showMemoriesDialog() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_memories, null)
        val container = view.findViewById<LinearLayout>(R.id.memoryListContainer)
        val toggle = view.findViewById<Switch>(R.id.toggleEnableMemory)
        val txtEmpty = view.findViewById<TextView>(R.id.txtEmptyMemories)

        toggle.isChecked = memoryManager.isMemoryEnabled
        toggle.setOnCheckedChangeListener { _, isChecked ->
            memoryManager.isMemoryEnabled = isChecked
            Toast.makeText(this, if (isChecked) "Memory Enabled" else "Memory Disabled", Toast.LENGTH_SHORT).show()
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
                    row.findViewById<TextView>(R.id.txtMemoryCategory).text = mem.category
                    
                    val timeFormat = SimpleDateFormat("MMM d", Locale.getDefault())
                    row.findViewById<TextView>(R.id.txtMemoryDate).text = timeFormat.format(Date(mem.timestamp))

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

    // ── Gemini API Key Configuration Dialog ───────────────────────────────────

    private fun showApiKeyDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_api_key, null)
        val txtStatus = dialogView.findViewById<TextView>(R.id.txtCurrentKeyStatus)
        val editKey = dialogView.findViewById<EditText>(R.id.editApiKeyInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelApiKey)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearApiKey)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveApiKey)

        txtStatus.text = "Current: ${ApiKeyManager.getMaskedApiKey(this)}"
        if (ApiKeyManager.hasGeminiApiKey(this)) {
            editKey.setText(ApiKeyManager.getGeminiApiKey(this))
        }

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnClear.setOnClickListener {
            ApiKeyManager.clearGeminiApiKey(this)
            Toast.makeText(this, "API Key cleared", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val input = editKey.text.toString().trim()
            if (input.isEmpty()) {
                Toast.makeText(this, "Please enter a valid API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            ApiKeyManager.setGeminiApiKey(this, input)
            Toast.makeText(this, "Gemini API Key saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Message Routing & Generation ──────────────────────────────────────────

    private fun toggleInsights() {
        isInsightsVisible = !isInsightsVisible
        insightsContainer.visibility = if (isInsightsVisible) View.VISIBLE else View.GONE
        insightsChevron.animate().rotation(if (isInsightsVisible) 0f else 180f).setDuration(300).start()
    }

    private fun trySendMessage() {
        if (isSending) return

        val text = messageInput.text.toString().trim()
        val imagePath = selectedAttachmentPath

        if (text.isNotBlank() || !imagePath.isNullOrBlank()) {
            val promptText = if (text.isNotBlank()) text else "Analyze this image."

            // Multimodal capability validation
            if (!imagePath.isNullOrBlank()) {
                val localSupportsImage = ModelCapabilityRegistry.supportsImage(localModelRunner.currentModel)
                if (privacyModeToggle.isChecked && !localSupportsImage) {
                    Toast.makeText(
                        this,
                        "${localModelRunner.currentModel.displayName} is text-only. Switch to Gemma 4 E4B-it or disable Privacy Mode to use Cloud vision.",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            }

            // Local model availability validation
            if (privacyModeToggle.isChecked && !localModelRunner.hasAnyModelAvailable()) {
                Toast.makeText(
                    this,
                    "No local model available. Download a model from Models menu.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            isSending = true
            sendButton.isEnabled = false
            messageInput.text.clear()
            clearSelectedImage()

            handleUserMessage(promptText, imagePath)
        }
    }

    private fun handleUserMessage(text: String, imagePath: String?) {
        val conv = activeConversation ?: return
        lifecycleScope.launch {
            try {
                // Save user message to database
                val userMsg = MessageEntity(
                    conversationId = conv.conversationId,
                    role = "user",
                    content = text,
                    attachmentPath = imagePath,
                    attachmentType = if (!imagePath.isNullOrBlank()) "image" else null
                )
                conversationRepo.addMessage(userMsg)

                // Auto-rename "New Chat" after first prompt
                if (conv.title == "New Chat") {
                    val autoTitle = text.take(28).trim().replace("\n", " ") + (if (text.length > 28) "…" else "")
                    conversationRepo.updateConversationTitle(conv.conversationId, autoTitle)
                    findViewById<TextView>(R.id.appSubtitleText)?.text = "$autoTitle • ${AppVersion.shortLabel}"
                }

                // Inspect message for long-term preferences
                memoryManager.inspectAndExtractMemories(text, conv.conversationId)

                // Render in UI
                renderUserBubble(userMsg)
                scrollToBottom()

                // Build context with history and long-term memories
                val recentMessages = conversationRepo.getMessages(conv.conversationId)
                val contextHistory = contextBuilder.buildInferenceHistory(recentMessages, conv.summary)

                val startTime = System.currentTimeMillis()
                showThinking()
                val state = lastKnownState ?: DeviceState()
                
                val result = messageRouter.routeMessage(
                    text = text,
                    isPrivacyModeOn = privacyModeToggle.isChecked,
                    state = state,
                    imagePath = imagePath,
                    providedHistory = contextHistory,
                    overrideMode = ApiKeyManager.getInferenceOverride(this@MainActivity)
                )

                val latency = "${(System.currentTimeMillis() - startTime) / 1000.0}s"
                hideThinking()

                // Save AI response to database
                val aiMsg = MessageEntity(
                    conversationId = conv.conversationId,
                    role = "assistant",
                    content = result.response
                )
                conversationRepo.addMessage(aiMsg)

                // Render in UI
                renderAiBubble(aiMsg, result.detail, latency)
                scrollToBottom()
            } catch (t: Throwable) {
                Log.e("MainActivity", "Failed to process message", t)
                hideThinking()
                val errorMsg = MessageEntity(
                    conversationId = conv.conversationId,
                    role = "assistant",
                    content = "⚠️ Error processing prompt: ${t.localizedMessage ?: t.javaClass.simpleName}\n\n" +
                            "If using a local model, verify the model file is complete in Model Manager, or configure Cloud AI in Settings."
                )
                conversationRepo.addMessage(errorMsg)
                renderAiBubble(errorMsg, null, "0s")
                scrollToBottom()
            } finally {
                isSending = false
                sendButton.isEnabled = true
            }
        }
    }

    // ── Message Actions (Copy & Edit) ─────────────────────────────────────────

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("DynamicEdgeAI Message", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }

    private fun showEditUserMessageDialog(message: MessageEntity) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_prompt, null)
        val editInput = dialogView.findViewById<EditText>(R.id.dialogEditInput)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnDialogCancel)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnDialogConfirm)

        editInput.setText(message.content)
        editInput.setSelection(editInput.text.length)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val newContent = editInput.text.toString().trim()
            if (newContent.isNotBlank() && newContent != message.content) {
                dialog.dismiss()
                executeMessageEdit(message, newContent)
            } else {
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun executeMessageEdit(oldMessage: MessageEntity, newContent: String) {
        val conv = activeConversation ?: return
        lifecycleScope.launch {
            try {
                // 1. Update message content in database
                conversationRepo.updateMessageContent(oldMessage.messageId, newContent)

                // 2. Remove all subsequent messages that depended on the old message
                conversationRepo.deleteMessagesAfter(conv.conversationId, oldMessage.timestamp)

                // 3. Reload UI bubbles up to the edited message
                loadMessagesForActiveConversation()

                // 4. Automatically trigger inference for the updated message
                isSending = true
                sendButton.isEnabled = false
                showThinking()

                val recentMessages = conversationRepo.getMessages(conv.conversationId)
                val contextHistory = contextBuilder.buildInferenceHistory(recentMessages, conv.summary)
                val state = lastKnownState ?: DeviceState()

                val result = messageRouter.routeMessage(
                    text = newContent,
                    isPrivacyModeOn = privacyModeToggle.isChecked,
                    state = state,
                    imagePath = oldMessage.attachmentPath,
                    providedHistory = contextHistory,
                    overrideMode = ApiKeyManager.getInferenceOverride(this@MainActivity)
                )

                hideThinking()

                val aiMsg = MessageEntity(
                    conversationId = conv.conversationId,
                    role = "assistant",
                    content = result.response
                )
                conversationRepo.addMessage(aiMsg)
                renderAiBubble(aiMsg, result.detail, "Regenerated")
                scrollToBottom()
            } catch (t: Throwable) {
                Log.e("MainActivity", "Failed to regenerate edited message", t)
                hideThinking()
                Toast.makeText(this@MainActivity, "Inference error: ${t.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                isSending = false
                sendButton.isEnabled = true
            }
        }
    }

    // ── Bubble Rendering ──────────────────────────────────────────────────────

    private fun renderUserBubble(message: MessageEntity) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_user, chatContainer, false)
        view.findViewById<TextView>(R.id.messageText).text = message.content
        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp))
        view.findViewById<TextView>(R.id.timestamp).text = timeStr

        // Attached image preview in chat
        val imgView = view.findViewById<ImageView>(R.id.userAttachedImage)
        if (!message.attachmentPath.isNullOrBlank()) {
            val file = File(message.attachmentPath)
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                imgView.setImageBitmap(bitmap)
                imgView.visibility = View.VISIBLE
            }
        }

        // Copy button
        view.findViewById<ImageButton>(R.id.copyButton).setOnClickListener {
            copyToClipboard(message.content)
        }

        // Edit button
        view.findViewById<ImageButton>(R.id.editButton).setOnClickListener {
            showEditUserMessageDialog(message)
        }

        val index = chatContainer.indexOfChild(thinkingIndicator)
        chatContainer.addView(view, if (index != -1) index else chatContainer.childCount)
    }

    private fun renderAiBubble(
        message: MessageEntity,
        detail: com.dynamicedgeai.engine.StrategyDetail? = null,
        latency: String = ""
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_chat_ai, chatContainer, false)
        view.findViewById<TextView>(R.id.messageText).text = message.content

        val executionLayout = view.findViewById<View>(R.id.executionInfo)
        if (detail != null) {
            view.findViewById<TextView>(R.id.modeTitle).text = "Execution Info: ${detail.strategy.name}"
            val modelName = if (detail.strategy == Strategy.LOCAL) "${localModelRunner.currentModel.displayName} (On-Device)" else "Gemini 1.5 Flash (Cloud)"
            view.findViewById<TextView>(R.id.modelUsedText).text = "Model: $modelName"
            view.findViewById<TextView>(R.id.reasonText).text = "Reason: ${detail.reason}"
            view.findViewById<TextView>(R.id.latencyText).text = "Latency: $latency"
            view.findViewById<TextView>(R.id.networkUsedText).text = "Network Used: ${if (detail.strategy == Strategy.CLOUD) "Yes" else "No"}"
            executionLayout.visibility = View.VISIBLE
        } else {
            executionLayout.visibility = View.GONE
        }

        // Copy plain response text only
        view.findViewById<ImageButton>(R.id.copyButton).setOnClickListener {
            copyToClipboard(message.content)
        }

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

    private fun scrollToBottom() { 
        chatScrollView.post { chatScrollView.fullScroll(View.FOCUS_DOWN) } 
    }

    // ── Bottom Sheet Menu ─────────────────────────────────────────────────────

    private fun showBottomSheetMenu() {
        val dialog = BottomSheetDialog(this)
        val view = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_menu, null)

        view.findViewById<View>(R.id.menuModelManager).setOnClickListener {
            startActivity(Intent(this, ModelManagerActivity::class.java))
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.menuAdjustRam).setOnClickListener {
            showCustomRamDialog()
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.menuMemories)?.setOnClickListener {
            dialog.dismiss()
            showMemoriesDialog()
        }

        view.findViewById<TextView>(R.id.txtMenuVersion)?.text = "DynamicEdgeAI • ${AppVersion.label}"

        view.findViewById<View>(R.id.menuSettings)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        view.findViewById<View>(R.id.btnManageModelsLink)?.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, ModelManagerActivity::class.java))
        }

        // List downloaded local models
        val container = view.findViewById<LinearLayout>(R.id.menuModelsContainer)
        val downloaded = LocalModel.values().filter { localModelRunner.isModelAvailable(it) }

        if (downloaded.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No models downloaded yet. Tap Models above."
                setTextColor(0xFF888888.toInt())
                textSize = 12f
                setPadding(20, 16, 20, 16)
            }
            container.addView(empty)
        } else {
            downloaded.forEach { model ->
                val isActive = localModelRunner.currentModel == model
                val row = TextView(this).apply {
                    text = "${if (isActive) "● " else "  "}${model.displayName} (${model.fileSizeLabel})"
                    setTextColor(if (isActive) 0xFF1E88E5.toInt() else 0xFF333333.toInt())
                    textSize = 13f
                    setPadding(20, 12, 20, 12)
                    setBackgroundResource(R.drawable.card_outline)
                }

                row.setOnClickListener {
                    if (!isActive) {
                        localModelRunner.switchModel(model)
                        Toast.makeText(this, "Switched to ${model.displayName}", Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }

                container.addView(row)
            }
        }

        dialog.setContentView(view)
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

    // ── Resource Telemetry Monitoring ─────────────────────────────────────────

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
        ramLabel.text = "RAM: $ramFormatted"
        val ramRatio = if (state.totalRam > 0) state.ramAvailable.toDouble() / state.totalRam else 1.0
        ramIndicator.setImageResource(when { ramRatio > 0.30 -> R.drawable.ic_check_green; ramRatio > 0.20 -> R.drawable.dot_orange; else -> R.drawable.dot_red })
        cpuLabel.text = "CPU: ${state.cpuUsage}%"
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
        batteryLabel.text = "Battery: ${state.batteryLevel.toInt()}%"
        val detail = decisionEngine.determineStrategy(state)
        val overrideMode = ApiKeyManager.getInferenceOverride(this)
        val displayStrategy = when {
            overrideMode == com.dynamicedgeai.util.InferenceOverrideMode.LOCAL -> Strategy.LOCAL
            overrideMode == com.dynamicedgeai.util.InferenceOverrideMode.CLOUD -> Strategy.CLOUD
            privacyModeToggle.isChecked -> Strategy.LOCAL
            else -> detail.strategy
        }
        insightModeText.text = displayStrategy.name
        val modeColor = when(displayStrategy) { Strategy.LOCAL -> R.color.mode_local; Strategy.CLOUD -> R.color.accent_blue }
        insightModeText.setTextColor(ContextCompat.getColor(this, modeColor))
    }
}
