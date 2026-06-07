package com.marathoncoach

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * One-screen setup app. After permissions are granted this screen is rarely needed —
 * SyncWorker handles everything in the background.
 *
 * OAuth flow (GitHub Pages redirect):
 *   1. App opens browser → user signs in and approves.
 *   2. Google redirects to the GitHub Pages callback page.
 *   3. That page shows the auth code prominently with a Copy button.
 *   4. User copies the code, switches back to app, pastes it, taps Connect.
 *   5. App exchanges the code for tokens and stores the refresh token.
 *
 * Why not localhost? Chrome on Android blocks HTTP redirects from HTTPS pages.
 * Why not custom URI scheme? Requires SHA-1 + Android credential type in Google Cloud.
 * GitHub Pages redirect is reliable, requires no infrastructure, works every time.
 */
class MainActivity : AppCompatActivity() {

    private val uploader by lazy { DriveUploader(this) }

    private val requestHealthPermissions =
        registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
                updateStatus("✅ All set! Syncing every 6 hours automatically.")
                SyncWorker.schedule(this)
            } else {
                updateStatus("⚠️ Some Health Connect permissions were denied.\nPlease grant all permissions for full data sync.")
            }
            updateUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnConnectDrive).setOnClickListener {
            openBrowserForAuth()
        }

        findViewById<Button>(R.id.btnGrantHealth).setOnClickListener {
            requestHealthPermissions.launch(HealthConnectReader.PERMISSIONS)
        }

        findViewById<Button>(R.id.btnSyncNow).setOnClickListener {
            updateStatus("⏳ Sync queued — check back in a minute.")
            SyncWorker.runNow(this)
        }

        updateUi()
    }

    // -------------------------------------------------------------------------
    // OAuth flow
    // -------------------------------------------------------------------------

    private fun openBrowserForAuth() {
        updateStatus("Opening Google authorization…")

        // Open the consent page. After approval, Google redirects to the GitHub Pages
        // callback URL which shows the auth code for the user to copy.
        val url = uploader.buildAuthUrl()
        CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
            .launchUrl(this, Uri.parse(url))

        // After user comes back, show the paste dialog
        showCodePasteDialog()
    }

    private fun showCodePasteDialog() {
        val input = EditText(this).apply {
            hint = "Paste authorization code here"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("Paste Authorization Code")
            .setMessage(
                "After approving in the browser:\n\n" +
                "1. The page will show an authorization code\n" +
                "2. Tap 'Copy Code' on that page\n" +
                "3. Come back here and paste it below"
            )
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val code = input.text.toString().trim()
                if (code.isNotEmpty()) {
                    exchangeCode(code)
                } else {
                    updateStatus("❌ No code entered. Tap Connect Drive to try again.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exchangeCode(code: String) {
        updateStatus("⏳ Connecting to Google Drive…")
        lifecycleScope.launch {
            val ok = uploader.exchangeCodeForTokens(code)
            if (ok) {
                updateStatus("✅ Google Drive connected!\n\nNow tap 'Grant Health Connect Access'.")
            } else {
                updateStatus("❌ Failed — the code may have expired (they last ~10 min). Tap Connect Drive to try again.")
            }
            updateUi()
        }
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private fun updateUi() {
        val driveOk = uploader.isAuthorized
        findViewById<Button>(R.id.btnConnectDrive).apply {
            text = if (driveOk) "✅ Google Drive Connected" else "1. Connect Google Drive"
            isEnabled = !driveOk
        }
        findViewById<Button>(R.id.btnGrantHealth).isEnabled = driveOk
        findViewById<Button>(R.id.btnSyncNow).isEnabled = driveOk
    }

    private fun updateStatus(msg: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.tvStatus).text = msg
        }
    }
}
