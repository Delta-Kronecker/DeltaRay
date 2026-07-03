package xyz.zarazaex.olc.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import xyz.zarazaex.olc.AppConfig
import xyz.zarazaex.olc.BuildConfig
import xyz.zarazaex.olc.R
import xyz.zarazaex.olc.databinding.ActivityCheckUpdateBinding
import xyz.zarazaex.olc.dto.CheckUpdateResult
import xyz.zarazaex.olc.extension.toast
import xyz.zarazaex.olc.extension.toastError
import xyz.zarazaex.olc.extension.toastSuccess
import xyz.zarazaex.olc.handler.UpdateCheckerManager
import xyz.zarazaex.olc.handler.V2RayNativeManager
import xyz.zarazaex.olc.util.MarkdownUtil
import xyz.zarazaex.olc.util.Utils
import kotlinx.coroutines.launch

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        // Hide the pre-release toggle - we always check releases
        binding.checkPreRelease.visibility = android.view.View.GONE

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates()
    }

    private fun checkForUpdates() {
        toast(R.string.update_checking_for_update)
        showLoading()

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(false)
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            }
            finally {
                hideLoading()
            }
        }
    }

    private fun downloadAndInstall(downloadUrl: String) {
        if (!Utils.canInstallApk(this)) {
            toast(R.string.update_install_permission_required)
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri()
                )
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to open install-sources settings: ${e.message}")
            }
            return
        }

        showLoading()
        binding.layoutCheckUpdate.isEnabled = false
        binding.tvVersion.text = "Downloading..."

        lifecycleScope.launch {
            try {
                val apk = UpdateCheckerManager.downloadApk(this@CheckUpdateActivity, downloadUrl)
                if (apk != null && Utils.installApk(this@CheckUpdateActivity, apk)) {
                    return@launch
                }
                toastError(R.string.update_download_failed)
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to download/install update: ${e.message}")
                toastError(e.message ?: getString(R.string.update_download_failed))
            } finally {
                hideLoading()
                binding.layoutCheckUpdate.isEnabled = true
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        val message = result.releaseNotes?.let { MarkdownUtil.parseBasic(it) } ?: ""
        val titleStr = getString(R.string.update_new_version_found, result.latestVersion)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleStr)
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ ->
                result.downloadUrl?.let { downloadAndInstall(it) }
            }
            .create()
        dialog.show()
        val titleView = layoutInflater.inflate(R.layout.dialog_title_with_close, null)
        titleView.findViewById<TextView>(R.id.dialog_title_text).text = titleStr
        titleView.findViewById<android.widget.ImageButton>(R.id.dialog_close_btn).setOnClickListener { dialog.dismiss() }
        dialog.setCustomTitle(titleView)
    }
}
