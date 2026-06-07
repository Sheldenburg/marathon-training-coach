package com.marathoncoach

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Handles OAuth2 token management and uploading JSON files to Google Drive.
 *
 * Uses the "Desktop app" OAuth2 flow (localhost redirect):
 *   1. On first run, MainActivity starts a local HTTP server on port 8765.
 *   2. Opens browser → user authorizes → Google redirects to http://127.0.0.1:8765?code=...
 *   3. Local server catches the code, passes it here to exchange for tokens.
 *   4. All subsequent calls use the stored refresh token to get fresh access tokens.
 *
 * Why "Desktop app" credentials? Google's "Web application" type rejects custom URI schemes
 * (com.example://), but "Desktop app" type implicitly allows localhost redirects.
 */
class DriveUploader(private val context: Context) {

    private val http = OkHttpClient()
    private val prefs: SharedPreferences =
        context.getSharedPreferences("coach_prefs", Context.MODE_PRIVATE)

    // -------------------------------------------------------------------------
    // Auth state
    // -------------------------------------------------------------------------

    val isAuthorized: Boolean
        get() = prefs.getString(KEY_REFRESH_TOKEN, null) != null

    /** Build the URL the app opens in a browser so the user can authorize. */
    fun buildAuthUrl(): String {
        return "https://accounts.google.com/o/oauth2/v2/auth" +
            "?client_id=${BuildConfig.GOOGLE_CLIENT_ID}" +
            "&redirect_uri=$REDIRECT_URI" +
            "&response_type=code" +
            "&scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file" +
            "&access_type=offline" +
            "&prompt=consent"
    }

    /**
     * Called from MainActivity after the OAuth redirect returns a code.
     * Exchanges the code for tokens and persists the refresh token.
     */
    suspend fun exchangeCodeForTokens(code: String): Boolean {
        val body = FormBody.Builder()
            .add("code", code)
            .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_CLIENT_SECRET)
            .add("redirect_uri", REDIRECT_URI)
            .add("grant_type", "authorization_code")
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(body)
            .build()

        return try {
            val response = http.newCall(request).execute()
            val json = JSONObject(response.body!!.string())
            prefs.edit()
                .putString(KEY_REFRESH_TOKEN, json.getString("refresh_token"))
                .putString(KEY_ACCESS_TOKEN, json.getString("access_token"))
                .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + json.getLong("expires_in") * 1000)
                .apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Uploading
    // -------------------------------------------------------------------------

    /**
     * Uploads [json] as a file named [filename] into the RunningCoach folder on Drive.
     * Creates the folder if it doesn't exist yet.
     * Returns true on success.
     */
    suspend fun uploadRun(filename: String, json: JSONObject): Boolean {
        val token = getValidAccessToken() ?: return false
        val folderId = getOrCreateFolder(token) ?: return false

        // Check if a file with this name already exists (avoid duplicates on re-sync)
        if (fileExists(token, folderId, filename)) {
            Log.d(TAG, "File $filename already exists in Drive — skipping")
            return true
        }

        // Multipart upload: metadata part + JSON body part
        val boundary = "coach_boundary_${System.currentTimeMillis()}"
        val metadata = JSONObject().apply {
            put("name", filename)
            put("parents", org.json.JSONArray().put(folderId))
        }

        val multipartBody = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json\r\n\r\n")
            append(json.toString(2))  // pretty-printed so humans can read it
            append("\r\n--$boundary--")
        }

        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .addHeader("Authorization", "Bearer $token")
            .post(multipartBody.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()

        return try {
            val response = http.newCall(request).execute()
            val ok = response.isSuccessful
            if (ok) Log.d(TAG, "Uploaded $filename to Drive/$FOLDER_NAME")
            else Log.e(TAG, "Upload failed: ${response.code} ${response.body?.string()}")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception", e)
            false
        }
    }

    // -------------------------------------------------------------------------
    // Token management
    // -------------------------------------------------------------------------

    private suspend fun getValidAccessToken(): String? {
        val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0)
        if (System.currentTimeMillis() < expiry - 60_000) {
            // Current token still valid
            return prefs.getString(KEY_ACCESS_TOKEN, null)
        }
        // Refresh it
        return refreshAccessToken()
    }

    private suspend fun refreshAccessToken(): String? {
        val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return null

        val body = FormBody.Builder()
            .add("refresh_token", refreshToken)
            .add("client_id", BuildConfig.GOOGLE_CLIENT_ID)
            .add("client_secret", BuildConfig.GOOGLE_CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .build()

        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(body)
            .build()

        return try {
            val response = http.newCall(request).execute()
            val json = JSONObject(response.body!!.string())
            val newToken = json.getString("access_token")
            prefs.edit()
                .putString(KEY_ACCESS_TOKEN, newToken)
                .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + json.getLong("expires_in") * 1000)
                .apply()
            newToken
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh failed", e)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Drive folder helpers
    // -------------------------------------------------------------------------

    private suspend fun getOrCreateFolder(token: String): String? {
        // Check if folder already exists
        val cached = prefs.getString(KEY_FOLDER_ID, null)
        if (cached != null) return cached

        val query = "name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false"
        val listRequest = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name)")
            .addHeader("Authorization", "Bearer $token")
            .build()

        return try {
            val listResponse = http.newCall(listRequest).execute()
            val listJson = JSONObject(listResponse.body!!.string())
            val files = listJson.getJSONArray("files")

            val folderId = if (files.length() > 0) {
                files.getJSONObject(0).getString("id")
            } else {
                createFolder(token)
            }

            folderId?.also {
                prefs.edit().putString(KEY_FOLDER_ID, it).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Folder lookup failed", e)
            null
        }
    }

    private suspend fun createFolder(token: String): String? {
        val metadata = JSONObject().apply {
            put("name", FOLDER_NAME)
            put("mimeType", "application/vnd.google-apps.folder")
        }
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?fields=id")
            .addHeader("Authorization", "Bearer $token")
            .post(metadata.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = http.newCall(request).execute()
            JSONObject(response.body!!.string()).getString("id")
        } catch (e: Exception) {
            Log.e(TAG, "Folder creation failed", e)
            null
        }
    }

    private suspend fun fileExists(token: String, folderId: String, filename: String): Boolean {
        val query = "name='$filename' and '$folderId' in parents and trashed=false"
        val request = Request.Builder()
            .url("https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id)")
            .addHeader("Authorization", "Bearer $token")
            .build()
        return try {
            val response = http.newCall(request).execute()
            JSONObject(response.body!!.string()).getJSONArray("files").length() > 0
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "DriveUploader"
        private const val FOLDER_NAME = BuildConfig.DRIVE_FOLDER_NAME
        // Desktop app OAuth uses localhost — no custom scheme needed, no console config needed
        const val REDIRECT_PORT = 8765
        private const val REDIRECT_URI = "http://127.0.0.1:$REDIRECT_PORT"

        // SharedPreferences keys
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_TOKEN_EXPIRY = "token_expiry"
        private const val KEY_FOLDER_ID = "drive_folder_id"
    }
}
