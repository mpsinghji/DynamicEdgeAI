package com.dynamicedgeai

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

    private val cardViews = mutableMapOf<LocalModel, View>()

    // Handler for periodic UI refresh while downloads are active
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshDownloadingCards()
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

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtAppVersionModelManager)?.text = "Dynamic Edge AI • ${AppVersion.shortLabel}"

        buildModelList()
        updateStorageInfo()
    }

    override fun onResume() {
        super.onResume()
        refreshAllCards()
        updateStorageInfo()
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
        val cardBg = if (isNight) {
            when (model) {
                LocalModel.GEMMA_4_E4B -> android.graphics.Color.parseColor("#0E3D46") // Deep Teal (like Morphe)
                LocalModel.Qwen -> android.graphics.Color.parseColor("#1D3E54")        // Slate Blue (like Paresh)
                LocalModel.GEMMA_2B -> android.graphics.Color.parseColor("#1F3D17")    // Forest Green (like Heval)
                LocalModel.TINY_LLAMA -> android.graphics.Color.parseColor("#4A152E")  // Plum Burgundy (like HushGram)
            }
        } else {
            when (model) {
                LocalModel.GEMMA_4_E4B -> android.graphics.Color.parseColor("#E0F2F1") // Soft Teal
                LocalModel.Qwen -> android.graphics.Color.parseColor("#E1F0FA")        // Soft Slate Blue
                LocalModel.GEMMA_2B -> android.graphics.Color.parseColor("#E8F5E9")    // Soft Mint
                LocalModel.TINY_LLAMA -> android.graphics.Color.parseColor("#FCE4EC")  // Soft Plum
            }
        }
        cardView?.setCardBackgroundColor(cardBg)

        card.findViewById<TextView>(R.id.txtModelName).text = model.displayName
        card.findViewById<TextView>(R.id.txtModelDesc).text = model.description
        card.findViewById<TextView>(R.id.txtModelSize).text = "Size: ${model.fileSizeLabel}"

        // Recommended badge
        val badgeRecommended = card.findViewById<TextView>(R.id.badgeRecommended)
        badgeRecommended.visibility = if (model.isRecommended) View.VISIBLE else View.GONE

        // Active badge
        val badgeActive = card.findViewById<TextView>(R.id.badgeActive)
        badgeActive.visibility = if (localModelRunner.currentModel == model &&
            localModelRunner.isModelAvailable(model)) View.VISIBLE else View.GONE

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

        // Reset all visibility
        progressContainer.visibility = View.GONE
        btnDownload.visibility = View.GONE
        btnPause.visibility = View.GONE
        btnCancel.visibility = View.GONE
        btnRepair.visibility = View.GONE
        btnUninstall.visibility = View.GONE
        btnUseModel.visibility = View.GONE

        when (status) {
            ModelStatus.NOT_DOWNLOADED -> {
                txtStatus.text = "↓ Not Downloaded"
                txtStatus.setTextColor(0xFF999999.toInt())
                btnDownload.visibility = View.VISIBLE
                btnDownload.setOnClickListener { startDownload(model, false) }
            }
            ModelStatus.DOWNLOADING -> {
                val activeDl = DownloadManagerSingleton.getActiveDownload(model)
                txtStatus.text = "⟳ Downloading..."
                txtStatus.setTextColor(0xFF1E88E5.toInt())
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
                txtStatus.text = "⏸ Paused"
                txtStatus.setTextColor(0xFFFF9800.toInt())
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
                txtStatus.text = "✓ Downloaded"
                txtStatus.setTextColor(0xFF4CAF50.toInt())
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
                txtStatus.text = "⚠ Corrupted (Tap for details)"
                txtStatus.setTextColor(0xFFF44336.toInt())
                txtStatus.setOnClickListener { showModelDiagnosticDialog(model) }
                btnRepair.visibility = View.VISIBLE
                btnUninstall.visibility = View.VISIBLE
                btnRepair.setOnClickListener { startDownload(model, false) }
                btnUninstall.setOnClickListener { confirmUninstall(model) }
            }
        }
    }

    /**
     * Refreshes only the cards that have active downloads — called by the periodic handler.
     * This makes buttons and progress bars dynamic without flickering non-downloading cards.
     */
    private fun refreshDownloadingCards() {
        LocalModel.values().forEach { model ->
            if (DownloadManagerSingleton.isDownloading(model) || 
                DownloadManagerSingleton.getActiveDownload(model) != null) {
                val card = cardViews[model] ?: return@forEach
                updateCardStatus(model, card)
            }
        }
        // Also refresh cards that just finished (status changed from downloading to downloaded)
        LocalModel.values().forEach { model ->
            val activeDl = DownloadManagerSingleton.getActiveDownload(model)
            if (activeDl == null) {
                val card = cardViews[model] ?: return@forEach
                val currentStatusText = card.findViewById<TextView>(R.id.txtModelStatus).text
                if (currentStatusText.contains("Downloading") || currentStatusText.contains("Paused")) {
                    updateCardStatus(model, card)
                    updateStorageInfo()
                }
            }
        }
    }

    private fun startDownload(model: LocalModel, resume: Boolean) {
        val destination = localModelRunner.getModelPath(model)
        val card = cardViews[model] ?: return

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
                    Toast.makeText(this, "Download failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        )

        // Immediately show download UI
        updateCardStatus(model, card)
        // Start periodic refresh
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
                Toast.makeText(this, "${model.displayName} uninstalled", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
        }

    private fun useModel(model: LocalModel) {
        localModelRunner.switchModel(model)
        Toast.makeText(this, "Switched to ${model.displayName}", Toast.LENGTH_SHORT).show()
        refreshAllCards()
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
