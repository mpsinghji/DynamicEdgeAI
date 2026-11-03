package com.dynamicedgeai.updater

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dynamicedgeai.R
import com.dynamicedgeai.util.AppVersion
import kotlinx.coroutines.launch

/**
 * Binds the floating top update notification banner (inspired by modern app update pills)
 * to [AppUpdateManager.updateState]. Displays checking status on app launch,
 * shows "Up to date" and auto-hides after ~3.5 seconds, or alerts the user if an update is available.
 */
class UpdateBannerBinder(
    private val bannerView: View,
    private val lifecycleOwner: LifecycleOwner,
    private val onBannerClicked: (UpdateState) -> Unit
) {

    private val spinner: ProgressBar = bannerView.findViewById(R.id.popupProgressSpinner)
    private val statusIcon: ImageView = bannerView.findViewById(R.id.popupStatusIcon)
    private val txtTitle: TextView = bannerView.findViewById(R.id.txtPopupTitle)
    private val txtSubtitle: TextView = bannerView.findViewById(R.id.txtPopupSubtitle)
    private val txtBadge: TextView = bannerView.findViewById(R.id.txtPopupBadge)
    private val btnDismiss: ImageButton = bannerView.findViewById(R.id.btnPopupDismiss)

    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    private var currentState: UpdateState = UpdateState.Idle
    private var hasShownInitialCheck = false
    private var isDismissedOrHidden = false

    fun bind() {
        btnDismiss.setOnClickListener {
            dismissBanner()
        }

        bannerView.setOnClickListener {
            onBannerClicked(currentState)
        }

        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppUpdateManager.getInstance().updateState.collect { state ->
                    currentState = state
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: UpdateState) {
        cancelAutoHide()

        when (state) {
            is UpdateState.Idle -> {
                // Keep current visibility or remain hidden
            }

            is UpdateState.Checking -> {
                isDismissedOrHidden = false
                showBanner()
                spinner.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
                txtTitle.text = "Checking for updates..."
                txtSubtitle.text = "Connecting to GitHub..."
                txtBadge.text = AppVersion.shortLabel
                txtBadge.setTextColor(Color.parseColor("#82B1FF"))
            }

            is UpdateState.UpToDate -> {
                if (hasShownInitialCheck || isDismissedOrHidden) {
                    // Do not re-display when returning from other activities (e.g. ModelManager)
                    return
                }
                showBanner()
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_check_green)
                txtTitle.text = "Dynamic Edge AI is up to date"
                txtSubtitle.text = AppVersion.label
                txtBadge.text = "Latest"
                txtBadge.setTextColor(Color.parseColor("#81C784"))

                // Auto-hide after 3.5 seconds
                scheduleAutoHide(3500)
            }

            is UpdateState.UpdateAvailable -> {
                showBanner()
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_download)
                txtTitle.text = "Update Available: v${state.info.versionName}"
                txtSubtitle.text = "Tap to review & download (${state.info.formattedSize})"
                txtBadge.text = "Update"
                txtBadge.setTextColor(Color.parseColor("#FFB74D"))
            }

            is UpdateState.Downloading -> {
                showBanner()
                spinner.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
                val pct = state.progress
                val speed = state.speed
                txtTitle.text = "Downloading update... ($pct%)"
                txtSubtitle.text = if (speed.isNotEmpty()) "$speed • ${state.info.formattedSize}" else "Downloading APK..."
                txtBadge.text = "$pct%"
                txtBadge.setTextColor(Color.parseColor("#82B1FF"))
            }

            is UpdateState.DownloadCompleted -> {
                showBanner()
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_check_green)
                txtTitle.text = "Update ready to install"
                txtSubtitle.text = "Tap to restart and apply"
                txtBadge.text = "Install"
                txtBadge.setTextColor(Color.parseColor("#69F0AE"))
            }

            is UpdateState.Installing -> {
                showBanner()
                spinner.visibility = View.VISIBLE
                statusIcon.visibility = View.GONE
                txtTitle.text = "Launching installer..."
                txtSubtitle.text = "Confirm update on system prompt"
                txtBadge.text = "Install"
                txtBadge.setTextColor(Color.parseColor("#64B5F6"))
            }

            is UpdateState.Error -> {
                showBanner()
                spinner.visibility = View.GONE
                statusIcon.visibility = View.VISIBLE
                statusIcon.setImageResource(R.drawable.ic_close)
                txtTitle.text = "Update check failed"
                txtSubtitle.text = state.message
                txtBadge.text = "Dismiss"
                txtBadge.setTextColor(Color.parseColor("#FF8A80"))

                // Auto-hide error after 4.5 seconds
                scheduleAutoHide(4500)
            }
        }
    }

    private fun showBanner() {
        if (bannerView.visibility != View.VISIBLE) {
            bannerView.visibility = View.VISIBLE
            bannerView.alpha = 0f
            bannerView.translationY = -60f
            bannerView.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(300)
                .start()
        }
    }

    fun dismissBanner() {
        isDismissedOrHidden = true
        cancelAutoHide()
        bannerView.animate()
            .alpha(0f)
            .translationY(-60f)
            .setDuration(300)
            .withEndAction {
                bannerView.visibility = View.GONE
            }
            .start()
    }

    private fun scheduleAutoHide(delayMs: Long) {
        cancelAutoHide()
        hideRunnable = Runnable {
            hasShownInitialCheck = true
            isDismissedOrHidden = true
            dismissBanner()
        }
        handler.postDelayed(hideRunnable!!, delayMs)
    }

    private fun cancelAutoHide() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
    }
}
