package com.marathoncoach

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket

/**
 * One-screen setup app. After the user grants Health Connect + Google Drive
 * permissions on first launch, this screen is rarely needed — the background
 * SyncWorker handles everything automatically.
 *
 * OAuth flow (Desktop app / localhost redirect):
 *   1. App starts a local HTTP server on port 8765.
 *   2. Opens browser → user signs in and authorizes.
 *   3. Google redirects to http://127.0.0.1:8765?code=...
 *   4. Local server catches the code → exchanges for tokens → server shuts down.
 */
class MainActivity : AppCompatActivity() {

    private val uploader by lazy { DriveUploader(this) }
    private var localServer: ServerSocket? = null

    // Health Connect permission launcher
    private val requestHealthPermissions =
        registerForActivityResult(
            HealthConnectClient.requestHealthPermissionsResultContract()
        ) { granted ->
            if (granted.containsAll(HealthConnectReader.PERMISSIONS)) {
                updateStatus("✅ Health Connect connected! You're all set — syncing every 6 hours.")
                SyncWorker.schedule(this)
            } else {
                updateStatus("⚠️ Some Health Connect permissions were denied.\nPlease grant Exercise, Distance, Heart Rate, and Speed.")
            }
            updateUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnConnectDrive).setOnClickListener {
            startOAuthFlow()
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

    override fun onDestroy() {
        super.onDestroy()
        localServer?.close()  // clean up if user leaves mid-auth
    }

    // -------------------------------------------------------------------------
    // OAuth flow
    // -------------------------------------------------------------------------

    private fun startOAuthFlow() {
        updateStatus("⏳ Starting authorization…")

        lifecycleScope.launch {
            // 1. Start the local server BEFORE opening the browser, so it's ready to catch the redirect
            val code = withContext(Dispatchers.IO) { startLocalServerAndWait() }

            if (code == null) {
                updateStatus("❌ Authorization timed out or was cancelled. Try again.")
                updateUi()
                return@launch
            }

            // 2. Exchange the code for tokens
            updateStatus("⏳ Connecting to Google Drive…")
            val ok = uploader.exchangeCodeForTokens(code)

            if (ok) {
                updateStatus("✅ Google Drive connected!\n\nNow tap 'Grant Health Connect Access'.")
            } else {
                updateStatus("❌ Failed to connect Google Drive. Check your Client ID/Secret and try again.")
            }
            updateUi()
        }

        // 3. Open browser slightly after launching the coroutine (server is starting on IO thread)
        //    Small delay is fine — server is ready well before the user finishes the browser flow
        android.os.Handler(mainLooper).postDelayed({
            val url = uploader.buildAuthUrl()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }, 300)
    }

    /**
     * Spins up a local HTTP server on port 8765, waits for Google's redirect,
     * extracts the auth code from the URL, sends a success page, and returns the code.
     *
     * Runs on a background thread (Dispatchers.IO). Times out after 5 minutes.
     */
    private fun startLocalServerAndWait(): String? {
        return try {
            val server = ServerSocket(DriveUploader.REDIRECT_PORT).also { localServer = it }
            server.soTimeout = 5 * 60 * 1000  // 5 minute timeout

            Log.d(TAG, "Local OAuth server listening on port ${DriveUploader.REDIRECT_PORT}")

            val socket = server.accept()  // blocks until browser hits localhost:8765
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return null

            // requestLine looks like: GET /?code=4/ABC...&scope=... HTTP/1.1
            val code = extractCode(requestLine)

            // Send a nice response page so the browser doesn't show an error
            val responseBody = if (code != null) {
                "<html><body><h2>✅ Marathon Coach connected!</h2><p>You can close this tab and return to the app.</p></body></html>"
            } else {
                "<html><body><h2>❌ Authorization failed.</h2><p>Return to the app and try again.</p></body></html>"
            }
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n$responseBody"
            PrintWriter(socket.getOutputStream()).use { it.print(response) }

            socket.close()
            server.close()
            localServer = null

            code
        } catch (e: Exception) {
            Log.e(TAG, "Local server error", e)
            null
        }
    }

    /** Parses `code=XYZ` from the GET request line */
    private fun extractCode(requestLine: String): String? {
        // e.g. "GET /?code=4%2FABCD&scope=... HTTP/1.1"
        val queryStart = requestLine.indexOf('?')
        val queryEnd = requestLine.lastIndexOf(' ')
        if (queryStart < 0 || queryEnd < 0) return null

        val query = requestLine.substring(queryStart + 1, queryEnd)
        return query.split('&')
            .map { it.split('=') }
            .firstOrNull { it.size == 2 && it[0] == "code" }
            ?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
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
        Log.d(TAG, msg)
        runOnUiThread {
            findViewById<TextView>(R.id.tvStatus).text = msg
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
