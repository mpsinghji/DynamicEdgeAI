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
        setContentView(R.layout.activity_model_manager)

        localModelRunner = LocalModelRunner(this)
        modelListContainer = findViewById(R.id.modelListContainer)
        txtStorageInfo = findViewById(R.id.txtStorageInfo)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

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
                val txtProgressStatus = card.findViewById<TextView>(R.id.txtProgressStatus)
                if (activeDl != null) {
                    progressBar.progress = activeDl.progress
                    txtPercent.text = "${activeDl.progress}%"
                    txtProgressStatus.text = "Downloading..."
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
                val txtProgressStatus = card.findViewById<TextView>(R.id.txtProgressStatus)
                if (activeDl != null) {
                    progressBar.progress = activeDl.progress
                    txtPercent.text = "${activeDl.progress}%"
                    txtProgressStatus.text = "Paused"
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
                txtStatus.text = "⚠ Corrupted"
                txtStatus.setTextColor(0xFFF44336.toInt())
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
            onProgress = { progress ->
                // Progress is updated by the periodic refresh handler
            },
            onPaused = {
                // Updated by periodic refresh handler
            },
            onResumed = {
                // Updated by periodic refresh handler
            },
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

    private fun runOnUi(block: () -> Unit) {
        Handler(Looper.getMainLooper()).post(block)
    }
}
