package com.marathoncoach

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * One-screen setup app. After the user grants Health Connect + Google Drive
 * permissions on first launch, this screen is rarely needed — the background
 * SyncWorker handles everything.
 *
 * Screens:
 *   1. "Connect Google Drive" — opens browser OAuth flow.
 *   2. After OAuth redirect back: "Grant Health Connect access".
 *   3. "Sync Now" — manual trigger, shows last sync status.
 */
class MainActivity : AppCompatActivity() {

    private val uploader by lazy { DriveUploader(this) }

    // Health Connect permission launcher
    private val requestHealthPermissions =
        registerForActivityResult(
            HealthConnectClient.requestHealthPermissionsResultContract()
        ) { granted ->
            if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
                updateStatus("✅ Health Connect connected!")
                SyncWorker.schedule(this)
                updateUi()
            } else {
                updateStatus("⚠️ Some Health Connect permissions were denied. Please grant all run-related permissions.")
            }
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
            updateStatus("⏳ Syncing…")
            SyncWorker.runNow(this)
            updateStatus("⏳ Sync queued — check back in a minute.")
        }

        updateUi()
    }

    /** Called when the app is re-opened via the OAuth redirect URI */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthRedirect(intent)
    }

    // Also handle the case where the activity was cold-started by the redirect
    override fun onResume() {
        super.onResume()
        handleOAuthRedirect(intent)
    }

    private fun handleOAuthRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "com.marathoncoach" || uri.host != "oauth2callback") return

        val code = uri.getQueryParameter("code")
        if (code == null) {
            updateStatus("❌ OAuth error: ${uri.getQueryParameter("error")}")
            return
        }

        updateStatus("⏳ Exchanging authorization code…")
        lifecycleScope.launch {
            val ok = uploader.exchangeCodeForTokens(code)
            if (ok) {
                updateStatus("✅ Google Drive connected! Now grant Health Connect access.")
            } else {
                updateStatus("❌ Failed to connect Google Drive. Try again.")
            }
            updateUi()
        }

        // Clear the intent so we don't re-process on next resume
        this.intent = Intent()
    }

    private fun openBrowserForAuth() {
        val url = uploader.buildAuthUrl()
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

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
        Log.d("MainActivity", msg)
        runOnUiThread {
            findViewById<TextView>(R.id.tvStatus).text = msg
        }
    }
}
