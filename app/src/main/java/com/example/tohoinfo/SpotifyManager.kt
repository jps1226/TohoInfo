package com.example.tohoinfo

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL

object SpotifyManager {
    private const val CLIENT_ID = "7cd273258816450dad5e8fa8a7ddddea"
    private const val REDIRECT_URI = "touhoutrackinfo://callback"
    private const val REQUEST_CODE = 1337

    private var accessToken: String? = null
    var currentSpotifyAlbumName: String? = null
    private var currentSpotifyAlbumYear: Int? = null


    fun login(activity: Activity) {
        val request = AuthorizationRequest.Builder(
            CLIENT_ID,
            AuthorizationResponse.Type.TOKEN,
            REDIRECT_URI
        ).setScopes(
            arrayOf(
                "user-read-playback-state",
                "user-modify-playback-state"
            )
        ).build()

        AuthorizationClient.openLoginActivity(activity, REQUEST_CODE, request)
    }

    fun handleAuthResult(activity: MainActivity, requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode != this.REQUEST_CODE) return
        val response = AuthorizationClient.getResponse(resultCode, intent)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                accessToken = response.accessToken
                WebhookLogger.send("✅ Auth success! Token received.")
                fetchCurrentlyPlaying(activity)
            }
            AuthorizationResponse.Type.ERROR -> {
                WebhookLogger.send("❌ Auth error: ${response.error}", isError = true)
            }
            else -> {
                WebhookLogger.send("⚠️ Auth response: ${response.type}", isError = true)
            }
        }
    }

    fun fetchCurrentlyPlaying(activity: MainActivity) {
        Thread {
            try {
                val token = accessToken ?: return@Thread
                val request = Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/currently-playing")
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                OkHttpClient().newCall(request).execute().use { response ->
                    if (response.code == 204) {
                        UIUpdater.updateTrackText(activity, "Nothing is playing right now.")
                        activity.runOnUiThread {
                            activity.findViewById<TextView>(R.id.touhouInfo).text = ""
                            activity.findViewById<ImageView>(R.id.albumArt).setImageResource(R.mipmap.ic_launcher)
                        }
                        return@use
                    }

                    val body = response.body?.string()?.trim()
                    if (body.isNullOrBlank() || !body.startsWith("{")) {
                        WebhookLogger.send("⚠️ Unexpected Spotify response: $body", isError = true)
                        return@use
                    }

                    val json = JSONObject(body)
                    val item = json.getJSONObject("item")
                    val name = item.getString("name")
                    val album = item.getJSONObject("album")
                    val images = album.getJSONArray("images")
                    currentSpotifyAlbumName = album.getString("name")
                    val releaseDate = album.optString("release_date")  // format: "2017-12-30"
                    currentSpotifyAlbumYear = releaseDate.take(4).toIntOrNull()


                    val imageUrl = if (images.length() > 0) images.getJSONObject(0).getString("url") else null

                    val artistArray = item.getJSONArray("artists")
                    val artistNames = mutableListOf<String>()
                    for (i in 0 until artistArray.length()) {
                        artistNames.add(artistArray.getJSONObject(i).getString("name"))
                    }
                    val primarySpotifyArtist = artistNames.firstOrNull()?.trim() ?: ""


                    val isOriginalZUN = artistNames.any { it == "ZUN" || it.contains("上海アリス幻樂団") }

                    val displayName = UIUpdater.formatTildeTitle(name)
                    val fullText = if (isOriginalZUN) {
                        "🎧 Now playing (original):\n$displayName\nby ${artistNames.joinToString(", ")}"
                    } else {
                        "🎧 Now playing:\n$displayName\nby ${artistNames.firstOrNull().orEmpty()}"
                    }

                    UIUpdater.updateTrackText(activity, fullText)

                    activity.runOnUiThread {
                        val albumArt = activity.findViewById<ImageView>(R.id.albumArt)
                        if (imageUrl != null) {
                            Thread {
                                try {
                                    val stream = URL(imageUrl).openStream()
                                    val bitmap = BitmapFactory.decodeStream(stream)
                                    activity.runOnUiThread {
                                        albumArt.setImageBitmap(bitmap)
                                    }
                                } catch (e: Exception) {
                                    albumArt.setImageResource(R.mipmap.ic_launcher)
                                }
                            }.start()
                        } else {
                            albumArt.setImageResource(R.mipmap.ic_launcher)
                        }
                    }

                    if (isOriginalZUN) {
                        UIUpdater.hideCharacterSection(activity)
                        TouhouDBManager.scrape(activity, name, currentSpotifyAlbumYear, primarySpotifyArtist, artistNames)

                    } else {
                        UIUpdater.hideCharacterSection(activity)
                        TouhouDBManager.scrape(activity, name, currentSpotifyAlbumYear, primarySpotifyArtist, artistNames)
                    }
                }
            } catch (e: Exception) {
                WebhookLogger.send("🔥 Error in fetchCurrentlyPlaying: ${e.message}", isError = true)
                activity.runOnUiThread {
                    activity.findViewById<TextView>(R.id.touhouInfo).text = activity.getString(R.string.spotify_fetch_failed)
                }
            }
        }.start()
    }

    fun skipToNextTrack(context: Context, onComplete: (() -> Unit)? = null) {
        Log.d("SpotifySkip", "Attempting to skip track")

        Thread {
            try {
                val token = accessToken ?: return@Thread
                val request = Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/next")
                    .post("".toRequestBody(null)) // ✅ modern, clean, empty body
                    .addHeader("Authorization", "Bearer $token")
                    .build()


                val response = OkHttpClient().newCall(request).execute()
                Log.d("SpotifySkip", "Response code: ${response.code}")

                if (response.isSuccessful) {
                    WebhookLogger.send("⏭️ Skipped to next track")
                    // slight delay before fetching the next track info
                    Thread.sleep(750)
                    fetchCurrentlyPlaying(context as MainActivity)
                    onComplete?.invoke()
                } else {
                    WebhookLogger.send("⚠️ Failed to skip track: ${response.code}", isError = true)
                }
            } catch (e: Exception) {
                WebhookLogger.send("🔥 Error skipping track: ${e.message}", isError = true)
            }
        }.start()
    }

}
