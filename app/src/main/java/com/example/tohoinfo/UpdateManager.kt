package com.example.tohoinfo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AlertDialog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.regex.Pattern

object UpdateManager {

    // ❗️ IMPORTANT: Replace these with your GitHub username and repository name
    private const val GITHUB_OWNER = "jps1226"
    private const val GITHUB_REPO = "TohoInfo"

    private const val API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    fun checkForUpdates(context: Context) {
        Thread {
            try {
                val currentVersionCode = getCurrentVersionCode(context)
                if (currentVersionCode == -1L) return@Thread

                val client = OkHttpClient()
                val request = Request.Builder().url(API_URL).build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("UpdateManager", "Failed to fetch releases: ${response.code}")
                        return@Thread
                    }

                    val responseBody = response.body?.string() ?: return@Thread
                    val json = JSONObject(responseBody)

                    val tagName = json.getString("tag_name")
                    val releaseNotes = json.getString("body")

                    // Extract the build number from the tag name (e.g., "Build 13" -> 13)
                    val latestVersionCode = extractNumberFromTag(tagName)

                    if (latestVersionCode == null) {
                        Log.e("UpdateManager", "Could not find a version number in tag: $tagName")
                        return@Thread
                    }

                    Log.d("UpdateManager", "Current Version Code: $currentVersionCode | Latest Version Code: $latestVersionCode")


                    // Find the APK download URL from the assets
                    var apkUrl: String? = null
                    val assets = json.getJSONArray("assets")
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.getString("name").endsWith(".apk")) {
                            apkUrl = asset.getString("browser_download_url")
                            break
                        }
                    }

                    // Compare numbers instead of strings
                    if (latestVersionCode > currentVersionCode && apkUrl != null) {
                        (context as? Activity)?.runOnUiThread {
                            showUpdateDialog(context, tagName, releaseNotes, apkUrl)
                        }
                    } else {
                        Log.d("UpdateManager", "App is up to date.")
                    }
                }
            } catch (e: Exception) {
                Log.e("UpdateManager", "Update check failed", e)
            }
        }.start()
    }

    /**
     * Gets the app's versionCode.
     */
    private fun getCurrentVersionCode(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        } catch (e: Exception) {
            Log.e("UpdateManager", "Could not get current app version code", e)
            -1L
        }
    }

    /**
     * Finds the first sequence of digits in a string.
     * For example, "TohoInfo Build 13" becomes 13.
     */
    private fun extractNumberFromTag(tag: String): Long? {
        val matcher = Pattern.compile("\\d+").matcher(tag)
        return if (matcher.find()) {
            matcher.group(0).toLongOrNull()
        } else {
            null
        }
    }

    private fun showUpdateDialog(context: Context, version: String, notes: String, url: String) {
        AlertDialog.Builder(context)
            .setTitle("✨ Update Available")
            .setMessage("A new version ($version) is ready!\n\nRelease Notes:\n$notes")
            .setPositiveButton("Update") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                context.startActivity(intent)
            }
            .setNegativeButton("Later", null)
            .show()
    }
}