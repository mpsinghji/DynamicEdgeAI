package com.dynamicedgeai.updater

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.dynamicedgeai.R
import com.dynamicedgeai.util.AppVersion
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Binds an Update Card View (from layout_update_card.xml) to AppUpdateManager state.
 */
class UpdateCardBinder(
    private val cardView: View,
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val updateManager = AppUpdateManager.getInstance()

    private val txtTitle: TextView = cardView.findViewById(R.id.txtUpdateTitle)
    private val badgeState: TextView = cardView.findViewById(R.id.badgeUpdateState)
    private val btnDismiss: ImageButton = cardView.findViewById(R.id.btnDismissUpdate)
    private val txtMessage: TextView = cardView.findViewById(R.id.txtUpdateMessage)
    private val txtDetails: TextView = cardView.findViewById(R.id.txtUpdateDetails)
    private val progressBar: ProgressBar = cardView.findViewById(R.id.updateProgressBar)
    private val progressStats: RelativeLayout = cardView.findViewById(R.id.updateProgressStats)
    private val txtPercent: TextView = cardView.findViewById(R.id.txtUpdatePercent)
    private val txtSpeed: TextView = cardView.findViewById(R.id.txtUpdateSpeed)
    private val btnPrimary: Button = cardView.findViewById(R.id.btnUpdateActionPrimary)
    private val btnSecondary: Button = cardView.findViewById(R.id.btnUpdateActionSecondary)
    private val imgIcon: ImageView = cardView.findViewById(R.id.imgUpdateIcon)

    fun bind() {
        lifecycleOwner.lifecycleScope.launch {
            updateManager.updateState.collectLatest { state ->
                renderState(state)
            }
        }
    }

    private fun renderState(state: UpdateState) {
        // Reset defaults
        progressBar.visibility = View.GONE
        progressStats.visibility = View.GONE
        badgeState.visibility = View.GONE
        txtDetails.visibility = View.GONE
        btnSecondary.visibility = View.GONE
        btnDismiss.visibility = View.GONE
        btnPrimary.visibility = View.VISIBLE
        btnPrimary.isEnabled = true

        when (state) {
            is UpdateState.Idle -> {
                imgIcon.setImageResource(R.drawable.ic_download)
                txtTitle.text = "Software Update"
                txtMessage.text = "Check for new releases on GitHub."
                txtDetails.visibility = View.VISIBLE
                txtDetails.text = "Current: ${AppVersion.fullLabel}"
                btnPrimary.text = "Check for Updates"
                btnPrimary.setOnClickListener {
                    lifecycleOwner.lifecycleScope.launch {
                        updateManager.checkForUpdate(context)
                    }
                }
            }

            is UpdateState.Checking -> {
                imgIcon.setImageResource(R.drawable.ic_download)
                txtTitle.text = "Checking for Updates..."
                txtMessage.text = "Connecting to GitHub Releases API..."
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                btnPrimary.isEnabled = false
                btnPrimary.text = "Checking..."
            }

            is UpdateState.UpdateAvailable -> {
                val info = state.info
                imgIcon.setImageResource(R.drawable.ic_download)
                txtTitle.text = "Update Available"
                badgeState.visibility = View.VISIBLE
                badgeState.text = "v${info.versionName}"
                txtMessage.text = info.releaseTitle
                txtDetails.visibility = View.VISIBLE
                txtDetails.text = "Version ${info.versionName} (Build ${info.versionCode}) • Size: ${info.formattedSize}\n${info.releaseNotes.take(120).trim()}"
                btnPrimary.text = "Download & Install"
                btnPrimary.setOnClickListener {
                    lifecycleOwner.lifecycleScope.launch {
                        updateManager.downloadUpdate(context, info)
                    }
                }
                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = "Later"
                btnSecondary.setOnClickListener {
                    updateManager.resetState()
                }
            }

            is UpdateState.Downloading -> {
                imgIcon.setImageResource(R.drawable.ic_download)
                txtTitle.text = "Downloading Update..."
                txtMessage.text = "v${state.info.versionName} (${state.progress}%)"
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = false
                progressBar.progress = state.progress
                progressStats.visibility = View.VISIBLE
                txtPercent.text = "${state.progress}%"
                txtSpeed.text = state.speed
                btnPrimary.visibility = View.GONE
                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = "Cancel"
                btnSecondary.setOnClickListener {
                    updateManager.cancelDownload()
                }
            }

            is UpdateState.DownloadCompleted -> {
                imgIcon.setImageResource(R.drawable.ic_check_green)
                txtTitle.text = "Download Completed"
                txtMessage.text = "Update package verified and ready to install."
                txtDetails.visibility = View.VISIBLE
                txtDetails.text = "File: ${state.apkFile.name}"
                btnPrimary.text = "Install Update"
                btnPrimary.setOnClickListener {
                    updateManager.installUpdate(context, state.apkFile)
                }
            }

            is UpdateState.Installing -> {
                imgIcon.setImageResource(R.drawable.ic_download)
                txtTitle.text = "Installing Update..."
                txtMessage.text = "Launching Android PackageInstaller..."
                progressBar.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                btnPrimary.visibility = View.GONE
            }

            is UpdateState.UpToDate -> {
                imgIcon.setImageResource(R.drawable.ic_check_green)
                txtTitle.text = "You're Up to Date"
                badgeState.visibility = View.VISIBLE
                badgeState.text = "Latest"
                txtMessage.text = "DynamicEdgeAI is running the latest version (${state.currentVersion})."
                txtDetails.visibility = View.VISIBLE
                txtDetails.text = "Checked GitHub Releases • ${AppVersion.fullLabel}"
                btnPrimary.text = "Check Again"
                btnPrimary.setOnClickListener {
                    lifecycleOwner.lifecycleScope.launch {
                        updateManager.checkForUpdate(context)
                    }
                }
            }

            is UpdateState.Error -> {
                imgIcon.setImageResource(R.drawable.ic_repair)
                txtTitle.text = "Update Failed"
                txtMessage.text = state.message
                btnPrimary.text = "Retry"
                btnPrimary.setOnClickListener {
                    lifecycleOwner.lifecycleScope.launch {
                        updateManager.checkForUpdate(context)
                    }
                }
                btnSecondary.visibility = View.VISIBLE
                btnSecondary.text = "Dismiss"
                btnSecondary.setOnClickListener {
                    updateManager.resetState()
                }
            }
        }
    }
}
