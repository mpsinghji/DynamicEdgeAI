package com.dynamicedgeai

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dynamicedgeai.local.LocalModel
import com.dynamicedgeai.local.LocalModelRunner
import com.dynamicedgeai.util.AppVersion
import com.dynamicedgeai.util.ThemeManager
import com.dynamicedgeai.util.DownloadManagerSingleton
import com.dynamicedgeai.util.ModelStatus
import java.io.File

class ModelManagerActivity : AppCompatActivity() {

    private lateinit var localModelRunner: LocalModelRunner
    private lateinit var modelListContainer: LinearLayout
    private lateinit var txtStorageInfo: TextView
    private lateinit var txtSummaryTitle: TextView
    private lateinit var txtSummarySubtitle: TextView
    private lateinit var summaryBadgePill: TextView

    private val cardViews = mutableMapOf<LocalModel, View>()
    private val expandedCards = mutableSetOf<LocalModel>()

    // Handler for periodic UI refresh while downloads are active
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDownloadingCards()
            updateSummaryCard()
            if (DownloadManagerSingleton.hasAnyActiveDownload()) {
                refreshHandler.postDelayed(this, 500) // refresh every 500ms
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.initTheme(this)
        setContentView(R.layout.activity_model_manager)

        localModelRunner = LocalModelRunner.getInstance(this)
        modelListContainer = findViewById(R.id.modelListContainer)
        txtStorageInfo = findViewById(R.id.txtStorageInfo)
        txtSummaryTitle = findViewById(R.id.txtSummaryTitle)
        txtSummarySubtitle = findViewById(R.id.txtSummarySubtitle)
        summaryBadgePill = findViewById(R.id.summaryBadgePill)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtAppVersionModelManager)?.text = "DynamicEdgeAI • ${AppVersion.shortLabel}"

        // Floating Bottom Navigation Bar listeners
        findViewById<View>(R.id.btnNavChat)?.setOnClickListener {
            finish()
        }
        findViewById<View>(R.id.btnNavModels)?.setOnClickListener {
            // Already here, smoothly scroll to top
            findViewById<ScrollView>(R.id.headerModelManager)?.smoothScrollTo(0, 0)
        }
        findViewById<View>(R.id.btnNavSettings)?.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
        }

        buildModelList()
        updateStorageInfo()
        updateSummaryCard()
    }

    override fun onResume() {
        super.onResume()
        refreshAllCards()
        updateStorageInfo()
        updateSummaryCard()
        // Start periodic refresh if downloads are active
        if (DownloadManagerSingleton.hasAnyActiveDownload()) {
            refreshHandler.post(refreshRunnable)
        }
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun updateStorageInfo() {
        val externalDir = getExternalFilesDir(null)
        if (externalDir != null) {
            val free = externalDir.freeSpace / (1024 * 1024)
            val freeLabel = if (free > 1024) String.format("%.1f GB", free / 1024.0) else "$free MB"
            txtStorageInfo.text = "Free: $freeLabel"
        }
    }

    private fun updateSummaryCard() {
        var activeDlCount = 0
        var activeDlModel: LocalModel? = null

        LocalModel.values().forEach { m ->
            if (DownloadManagerSingleton.isDownloading(m)) {
                activeDlCount++
                if (activeDlModel == null) activeDlModel = m
            }
        }

        if (activeDlModel != null) {
            val dl = DownloadManagerSingleton.getActiveDownload(activeDlModel!!)
            txtSummaryTitle.text = "Downloading ${activeDlModel!!.displayName}..."
            val progressText = if (dl != null) "${dl.progress}% • ${dl.speedLabel}" else "Downloading..."
            txtSummarySubtitle.text = progressText
            summaryBadgePill.text = "⟳ ${dl?.progress ?: 0}%"
            summaryBadgePill.setBackgroundResource(R.drawable.bg_status_badge_cloud)
            summaryBadgePill.setTextColor(android.graphics.Color.parseColor("#38BDF8"))
        } else {
            val current = localModelRunner.currentModel
            val isAvail = localModelRunner.isModelAvailable(current)
            txtSummaryTitle.text = "Active Engine: ${current.displayName}"
            txtSummarySubtitle.text = if (isAvail) "On-Device LiteRT • Ready for prompt inference" else "Engine selected • Model not downloaded"
            summaryBadgePill.text = if (isAvail) "● Active" else "● Pending"
            summaryBadgePill.setBackgroundResource(if (isAvail) R.drawable.bg_status_badge_local else R.drawable.bg_pill_badge)
            summaryBadgePill.setTextColor(if (isAvail) android.graphics.Color.parseColor("#16A34A") else android.graphics.Color.parseColor("#94A3B8"))
        }
    }

    private fun buildModelList() {
        modelListContainer.removeAllViews()
        LocalModel.values().forEach { model ->
            val cardView = LayoutInflater.from(this)
                .inflate(R.layout.item_model_card, modelListContainer, false)
            cardViews[model] = cardView
            setupCard(model, cardView)
            modelListContainer.addView(cardView)
        }
    }

    private fun setupCard(model: LocalModel, card: View) {
        val cardView = card.findViewById<androidx.cardview.widget.CardView>(R.id.modelCardView)
            ?: (card as? androidx.cardview.widget.CardView)
        val isNight = ThemeManager.isNightMode(this)

        // Morphe-Inspired Deep Tint Palette
        val cardBg = if (isNight) {
            when (model) {
                LocalModel.GEMMA_4_E4B -> android.graphics.Color.parseColor("#10353A") // Teal (Morphe Patches)
                LocalModel.Qwen -> android.graphics.Color.parseColor("#233B4D")        // Slate Blue (Paresh Patches)
                LocalModel.GEMMA_2B -> android.graphics.Color.parseColor("#233E18")    // Forest Olive (Heval's Patches)
                LocalModel.TINY_LLAMA -> android.graphics.Color.parseColor("#4E142B")  // Deep Burgundy (HushGram)
            }
        } else {
            when (model) {
                LocalModel.GEMMA_4_E4B -> android.graphics.Color.parseColor("#E0F2F1") // Light Teal
                LocalModel.Qwen -> android.graphics.Color.parseColor("#E1F0FA")        // Light Slate Blue
                LocalModel.GEMMA_2B -> android.graphics.Color.parseColor("#E8F5E9")    // Light Mint
                LocalModel.TINY_LLAMA -> android.graphics.Color.parseColor("#FCE4EC")  // Light Rose
            }
        }
        cardView?.setCardBackgroundColor(cardBg)

        // Model Title & Description
        card.findViewById<TextView>(R.id.txtModelName).text = model.displayName
        card.findViewById<TextView>(R.id.txtModelDesc).text = model.description

        // Model Specs chips
        val isAvailable = localModelRunner.isModelAvailable(model)
        val isActive = localModelRunner.currentModel == model && isAvailable

        val statusLabel = when {
            isActive -> "Active"
            isAvailable -> "Downloaded"
            else -> "Available"
        }
        card.findViewById<TextView>(R.id.txtModelSize).text = "${model.fileSizeLabel} • $statusLabel"

        // Status Badge (Verified shield for active/downloaded, Globe/Download for remote)
        val statusBadge = card.findViewById<ImageView>(R.id.modelStatusBadge)
        if (isAvailable) {
            statusBadge.setImageResource(R.drawable.ic_verified_badge)
            statusBadge.visibility = View.VISIBLE
        } else {
            statusBadge.setImageResource(R.drawable.ic_globe_badge)
            statusBadge.visibility = View.VISIBLE
        }

        // Recommended & Active badges
        val badgeRecommended = card.findViewById<TextView>(R.id.badgeRecommended)
        badgeRecommended.visibility = if (model.isRecommended) View.VISIBLE else View.GONE

        val badgeActive = card.findViewById<TextView>(R.id.badgeActive)
        badgeActive.visibility = if (isActive) View.VISIBLE else View.GONE

        // Expand/Collapse behavior
        val detailsContainer = card.findViewById<View>(R.id.cardDetailsContainer)
        val chevron = card.findViewById<ImageView>(R.id.btnExpandChevron)
        val headerArea = card.findViewById<View>(R.id.cardHeaderClickArea)

        val isExpanded = expandedCards.contains(model)
        detailsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
        chevron.rotation = if (isExpanded) 180f else 0f

        headerArea.setOnClickListener {
            if (expandedCards.contains(model)) {
                expandedCards.remove(model)
                detailsContainer.visibility = View.GONE
                chevron.animate().rotation(0f).setDuration(200).start()
            } else {
                expandedCards.add(model)
                detailsContainer.visibility = View.VISIBLE
                chevron.animate().rotation(180f).setDuration(200).start()
            }
        }

        updateCardStatus(model, card)
    }

    private fun getEffectiveStatus(model: LocalModel): ModelStatus {
        val activeDl = DownloadManagerSingleton.getActiveDownload(model)
        if (activeDl != null) return activeDl.status
        return localModelRunner.getModelStatus(model)
    }

    private fun updateCardStatus(model: LocalModel, card: View) {
        val status = getEffectiveStatus(model)
        val txtStatus = card.findViewById<TextView>(R.id.txtModelStatus)
        val progressContainer = card.findViewById<View>(R.id.progressContainer)
        val btnDownload = card.findViewById<View>(R.id.btnDownload)
        val btnPause = card.findViewById<ImageButton>(R.id.btnPause)
        val btnCancel = card.findViewById<View>(R.id.btnCancel)
        val btnRepair = card.findViewById<View>(R.id.btnRepair)
        val btnUninstall = card.findViewById<View>(R.id.btnUninstall)
        val btnUseModel = card.findViewById<View>(R.id.btnUseModel)
        val badgeActive = card.findViewById<TextView>(R.id.badgeActive)
        val statusBadge = card.findViewById<ImageView>(R.id.modelStatusBadge)

        // Reset all action visibility
        progressContainer.visibility = View.GONE
        btnDownload.visibility = View.GONE
        btnPause.visibility = View.GONE
        btnCancel.visibility = View.GONE
        btnRepair.visibility = View.GONE
        btnUninstall.visibility = View.GONE
        btnUseModel.visibility = View.GONE

        when (status) {
            ModelStatus.NOT_DOWNLOADED -> {
                txtStatus.text = "Not Downloaded"
                statusBadge.setImageResource(R.drawable.ic_globe_badge)
                btnDownload.visibility = View.VISIBLE
                btnDownload.setOnClickListener { startDownload(model, false) }
            }
            ModelStatus.DOWNLOADING -> {
                val activeDl = DownloadManagerSingleton.getActiveDownload(model)
                txtStatus.text = "Downloading"
                progressContainer.visibility = View.VISIBLE
                btnPause.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE

                val progressBar = card.findViewById<ProgressBar>(R.id.modelProgressBar)
                val txtPercent = card.findViewById<TextView>(R.id.txtProgressPercent)
                val txtSize = card.findViewById<TextView>(R.id.txtDownloadSize)
                val txtSpeed = card.findViewById<TextView>(R.id.txtDownloadSpeed)
                val txtEta = card.findViewById<TextView>(R.id.txtDownloadEta)
                val txtProgressStatus = card.findViewById<TextView>(R.id.txtProgressStatus)

                if (activeDl != null) {
                    progressBar.progress = activeDl.progress
                    txtPercent.text = "${activeDl.progress}%"
                    txtSize.text = activeDl.downloadSizeText.ifBlank { "Downloading..." }
                    txtSpeed.text = "Speed: ${activeDl.speedLabel}"
                    txtEta.text = "Remaining: ${activeDl.etaLabel}"
                    txtProgressStatus.text = activeDl.statusText
                }

                btnPause.setImageResource(R.drawable.ic_pause)
                btnPause.setOnClickListener { togglePause(model) }
                btnCancel.setOnClickListener { cancelDownload(model) }
            }
            ModelStatus.PAUSED -> {
                val activeDl = DownloadManagerSingleton.getActiveDownload(model)
                txtStatus.text = "Paused"
                progressContainer.visibility = View.VISIBLE
                btnPause.visibility = View.VISIBLE
                btnCancel.visibility = View.VISIBLE

                val progressBar = card.findViewById<ProgressBar>(R.id.modelProgressBar)
                val txtPercent = card.findViewById<TextView>(R.id.txtProgressPercent)
                val txtSize = card.findViewById<TextView>(R.id.txtDownloadSize)
                val txtSpeed = card.findViewById<TextView>(R.id.txtDownloadSpeed)
                val txtEta = card.findViewById<TextView>(R.id.txtDownloadEta)
                val txtProgressStatus = card.findViewById<TextView>(R.id.txtProgressStatus)

                if (activeDl != null) {
                    progressBar.progress = activeDl.progress
                    txtPercent.text = "${activeDl.progress}%"
                    txtSize.text = activeDl.downloadSizeText
                    txtSpeed.text = "Speed: Paused"
                    txtEta.text = "Remaining: --"
                    txtProgressStatus.text = "Paused (Tap Resume to continue)"
                }

                btnPause.setImageResource(android.R.drawable.ic_media_play)
                btnPause.setOnClickListener { togglePause(model) }
                btnCancel.setOnClickListener { cancelDownload(model) }
            }
            ModelStatus.DOWNLOADED -> {
                txtStatus.text = "Downloaded"
                statusBadge.setImageResource(R.drawable.ic_verified_badge)
                btnUninstall.visibility = View.VISIBLE
                btnUninstall.setOnClickListener { confirmUninstall(model) }

                val isActive = localModelRunner.currentModel == model
                badgeActive.visibility = if (isActive) View.VISIBLE else View.GONE
                if (!isActive) {
                    btnUseModel.visibility = View.VISIBLE
                    btnUseModel.setOnClickListener { useModel(model) }
                }
            }
            ModelStatus.CORRUPTED -> {
                txtStatus.text = "Corrupted"
                btnRepair.visibility = View.VISIBLE
                btnUninstall.visibility = View.VISIBLE
                btnRepair.setOnClickListener { startDownload(model, false) }
                btnUninstall.setOnClickListener { confirmUninstall(model) }
            }
        }
    }

    private fun refreshDownloadingCards() {
        LocalModel.values().forEach { model ->
            if (DownloadManagerSingleton.isDownloading(model) || 
                DownloadManagerSingleton.getActiveDownload(model) != null) {
                val card = cardViews[model] ?: return@forEach
                updateCardStatus(model, card)
            }
        }
        LocalModel.values().forEach { model ->
            val activeDl = DownloadManagerSingleton.getActiveDownload(model)
            if (activeDl == null) {
                val card = cardViews[model] ?: return@forEach
                val currentStatusText = card.findViewById<TextView>(R.id.txtModelStatus).text
                if (currentStatusText.contains("Downloading") || currentStatusText.contains("Paused")) {
                    updateCardStatus(model, card)
                    updateStorageInfo()
                    updateSummaryCard()
                }
            }
        }
    }

    private fun startDownload(model: LocalModel, resume: Boolean) {
        val destination = localModelRunner.getModelPath(model)
        val card = cardViews[model] ?: return

        // Auto-expand card when download begins so progress is immediately visible
        expandedCards.add(model)
        card.findViewById<View>(R.id.cardDetailsContainer)?.visibility = View.VISIBLE
        card.findViewById<ImageView>(R.id.btnExpandChevron)?.rotation = 180f

        DownloadManagerSingleton.startDownload(
            context = this,
            model = model,
            destination = destination,
            resume = resume,
            onProgress = { _ -> },
            onPaused = { },
            onResumed = { },
            onComplete = { _ ->
                runOnUi {
                    updateCardStatus(model, card)
                    updateStorageInfo()
                    updateSummaryCard()
                    val fileStatus = localModelRunner.getModelStatus(model)
                    val msg = if (fileStatus == ModelStatus.DOWNLOADED)
                        "${model.displayName} downloaded successfully!"
                    else
                        "${model.displayName} finished but integrity check failed. Try Repair."
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                }
            },
            onError = { error ->
                runOnUi {
                    updateCardStatus(model, card)
                    updateSummaryCard()
                    Toast.makeText(this, "Download failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        )

        updateCardStatus(model, card)
        updateSummaryCard()
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.post(refreshRunnable)
    }

    private fun togglePause(model: LocalModel) {
        val activeDl = DownloadManagerSingleton.getActiveDownload(model) ?: return
        if (activeDl.isPaused) {
            DownloadManagerSingleton.resumeDownload(model)
        } else {
            DownloadManagerSingleton.pauseDownload(model)
        }
        updateSummaryCard()
    }

    private fun cancelDownload(model: LocalModel) {
        DownloadManagerSingleton.cancelDownload(this, model)

        val file = localModelRunner.getModelPath(model)
        val tempFile = File(file.absolutePath + ".tmp")
        if (file.exists()) file.delete()
        if (tempFile.exists()) tempFile.delete()

        val card = cardViews[model] ?: return
        updateCardStatus(model, card)
        updateStorageInfo()
        updateSummaryCard()

        Toast.makeText(this, "${model.displayName} download cancelled", Toast.LENGTH_SHORT).show()
    }

    private fun confirmUninstall(model: LocalModel) {
        AlertDialog.Builder(this)
            .setTitle("Uninstall ${model.displayName}")
            .setMessage("This will delete the downloaded model file (${model.fileSizeLabel}). You can re-download it later.")
            .setPositiveButton("Uninstall") { _, _ ->
                localModelRunner.deleteModel(model)
                val card = cardViews[model] ?: return@setPositiveButton
                updateCardStatus(model, card)
                updateStorageInfo()
                updateSummaryCard()
                Toast.makeText(this, "${model.displayName} uninstalled", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun useModel(model: LocalModel) {
        localModelRunner.switchModel(model)
        Toast.makeText(this, "Switched to ${model.displayName}", Toast.LENGTH_SHORT).show()
        refreshAllCards()
        updateSummaryCard()
    }

    private fun refreshAllCards() {
        LocalModel.values().forEach { model ->
            val card = cardViews[model] ?: return@forEach
            setupCard(model, card)
        }
    }

    private fun showModelDiagnosticDialog(model: LocalModel) {
        val path = localModelRunner.getModelPath(model)
        val details = buildString {
            appendLine("Model: ${model.displayName}")
            appendLine("File: ${path.absolutePath}")
            appendLine("Expected Size: ${model.fileSizeLabel}")
            if (path.exists()) {
                val actualMb = path.length() / (1024.0 * 1024.0)
                appendLine("Actual Size: ${String.format(java.util.Locale.US, "%.1f MB", actualMb)}")
            } else {
                appendLine("File Status: Missing from disk")
            }
            appendLine()
            appendLine("Diagnostics:")
            appendLine(localModelRunner.diagnosticInfo)
        }

        AlertDialog.Builder(this)
            .setTitle("Model Diagnostic Details")
            .setMessage(details)
            .setPositiveButton("Repair / Redownload") { _, _ -> startDownload(model, false) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun runOnUi(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
