package com.example.tohoinfo
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.URLSpan
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Color
import android.graphics.Typeface
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.text.HtmlCompat
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URL
import java.net.URLEncoder



class MainActivity : AppCompatActivity() {
    private var accessToken: String? = null
    private val errorWebhookUrl = "https://discord.com/api/webhooks/1362788746130620677/EG-nfizMV5DUS26fytrRS9RTSOL9WyPWCX1OssplU0snCl6sjV02q5qVK5bfILS6nZge"
    private val testingWebhookUrl = "https://discord.com/api/webhooks/1362617126850134116/9eyYw4FjU53cHtorsD1oTxpgztyzb1J85SB7-NCnm4GKSexl3he8bmTu5biXrTPaWkAr"

    companion object {
        private const val CLIENT_ID    = "7cd273258816450dad5e8fa8a7ddddea"
        private const val CLIENT_SECRET = "880f5fee3f3444d7b2a303ccdaf72bfe"  // move to backend in prod
        private const val REDIRECT_URI = "touhoutrackinfo://callback"
        private const val REQUEST_CODE = 1337
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        setupRefreshButton()
        setupNextButton()
        val albumImage = findViewById<ImageView>(R.id.albumArt)
        albumImage.setImageResource(R.mipmap.ic_launcher) // Or default album image

        Toast.makeText(this, "Starting Spotify login…", Toast.LENGTH_SHORT).show()

        // Launch Spotify Auth flow
        val builder = AuthorizationRequest.Builder(
            CLIENT_ID,
            AuthorizationResponse.Type.CODE,
            REDIRECT_URI
        )
        builder.setScopes(arrayOf(
            "streaming",
            "user-read-currently-playing",
            "user-read-playback-state"
        ))

        AuthorizationClient.openLoginActivity(this, REQUEST_CODE, builder.build())
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        super.onActivityResult(requestCode, resultCode, intent)
        if (requestCode == REQUEST_CODE) {
            val response = AuthorizationClient.getResponse(resultCode, intent)
            when (response.type) {
                AuthorizationResponse.Type.CODE -> {
                    val authCode = response.code
                    Log.d("SpotifyAuth", "Got code: $authCode")
                    sendLogToWebhook("Spotify auth response type: ${response.type}", testingWebhookUrl)

                    exchangeCodeForToken(authCode)
                }
                AuthorizationResponse.Type.ERROR -> {
                    Log.e("SpotifyAuth", "Auth error: ${response.error}")
                }
                else -> Log.w("SpotifyAuth", "Auth flow returned ${response.type}")
            }
        }
    }

    private fun sendLogToWebhook(message: String, webhookUrl: String = errorWebhookUrl) {
        Thread {
            try {
                val json = JSONObject()
                json.put("content", message)

                val requestBody = RequestBody.create(
                    "application/json; charset=utf-8".toMediaTypeOrNull(),
                    json.toString()
                )

                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(requestBody)
                    .build()

                OkHttpClient().newCall(request).execute().close()
            } catch (e: Exception) {
                Log.e("Webhook", "Failed to send log to Discord", e)
            }
        }.start()
    }



    private fun setupNextButton() {
        val nextButton = findViewById<Button>(R.id.nextSongButton)

        nextButton?.setOnClickListener {
            if (accessToken == null) {
                Toast.makeText(this, "No token available. Please log in again.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            Thread {
                try {
                    val client = OkHttpClient()
                    val request = Request.Builder()
                        .url("https://api.spotify.com/v1/me/player/next")
                        .post(FormBody.Builder().build()) // POST body required
                        .addHeader("Authorization", "Bearer $accessToken")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Log.d("NextTrack", "Skipped to next track.")
                            Thread.sleep(500) // wait 2 seconds for Spotify to update
                            fetchCurrentlyPlaying(accessToken!!)
                        } else {
                            Log.e("NextTrack", "Failed to skip track: ${response.code}")
                            runOnUiThread {
                                Toast.makeText(this, "Unable to skip track.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("NextTrack", "Error skipping track", e)
                }
            }.start()
        }
    }

    private fun scrapeTouhouDB(originalQuery: String) {
        val touhouText = findViewById<TextView>(R.id.touhouInfo)
        runOnUiThread {
            touhouText.text = "🔄 Searching TouhouDB..."
            touhouText.visibility = View.VISIBLE
        }

        Thread {
            try {
                val client = OkHttpClient()
                val fallbackQueries = mutableListOf(originalQuery)

                // Add trimmed variations
                val noBrackets = originalQuery.replace("""[\(\[\（\【].*?[\)\]\）\】]""".toRegex(), "").trim()
                if (noBrackets != originalQuery) fallbackQueries.add(noBrackets)

                val featRegex = """(?i)\s*(feat\.|with)\s.+$""".toRegex()
                val noFeat = originalQuery.replace(featRegex, "").trim()
                if (noFeat != originalQuery) fallbackQueries.add(noFeat)

// Also run feat-removal on already cleaned variants
                val noBracketsFeat = noBrackets.replace(featRegex, "").trim()
                if (noBracketsFeat != noBrackets && !fallbackQueries.contains(noBracketsFeat)) fallbackQueries.add(noBracketsFeat)


                val noVersionSuffix = noFeat.replace("""[-–]\s*(Short|Full)?\s*Ver\.?""".toRegex(RegexOption.IGNORE_CASE), "").trim()
                if (noVersionSuffix != noFeat) fallbackQueries.add(noVersionSuffix)

                val noTilde = noVersionSuffix.substringBefore("~").trim()
                if (noTilde != noVersionSuffix && noTilde.isNotBlank()) fallbackQueries.add(noTilde)


                var originalId = -1
                var lastSearchUrl = ""

                for (query in fallbackQueries) {
                    val encodedQuery = Uri.encode(query)
                    Log.d("TouhouDB", "Trying query variant: $query")

                    val searchUrl = "https://touhoudb.com/api/songs?query=$encodedQuery&start=0&maxResults=10&getTotalCount=true"
                    lastSearchUrl = searchUrl

                    val searchRequest = Request.Builder().url(searchUrl).build()
                    val searchResponse = client.newCall(searchRequest).execute()
                    val searchBody = searchResponse.body?.string() ?: ""

                    if (searchBody.isNotBlank()) {
                        val searchJson = JSONObject(searchBody)
                        val items = searchJson.getJSONArray("items")
                        for (i in 0 until items.length()) {
                            val song = items.getJSONObject(i)
                            val type = song.optString("songType")
                            if (type == "Arrangement") {
                                originalId = song.optInt("originalVersionId", -1)
                                break
                            }
                        }
                    }

                    if (originalId != -1) break // Found a match, stop iterating
                }

                if (originalId == -1) {
                    Log.d("TouhouDB", "❌ No matching arrangement found. Query: $originalQuery")

                    // Send log to Discord
                    try {
                        val escapedQuery = originalQuery.replace("`", "'").replace("\"", "\\\"")
                        val logJson = JSONObject().apply {
                            put("username", "TohoInfo App")
                            put("content", "⚠️ **No matching arrangement found**\n**Query:** `$escapedQuery`\n**Search URL:** $lastSearchUrl")
                        }

                        val requestBody = RequestBody.create(
                            "application/json".toMediaTypeOrNull(),
                            logJson.toString()
                        )

                        val webhookRequest = Request.Builder()
                            .url("https://discord.com/api/webhooks/1362617126850134116/9eyYw4FjU53cHtorsD1oTxpgztyzb1J85SB7-NCnm4GKSexl3he8bmTu5biXrTPaWkAr")
                            .post(requestBody)
                            .build()

                        val webhookResp = client.newCall(webhookRequest).execute()
                        Log.d("TouhouDB", "📤 Webhook sent, response code: ${webhookResp.code}")
                        webhookResp.close()
                    } catch (ex: Exception) {
                        Log.e("TouhouDB", "🚨 Failed to send webhook", ex)
                    }

                    runOnUiThread {
                        touhouText.text = "🔍 No matching Touhou arrangement found."
                    }
                    return@Thread
                }

                // Fetch the original song
                val originalUrl = "https://touhoudb.com/api/songs/$originalId?fields=Names,Albums"
                val originalResponse = client.newCall(Request.Builder().url(originalUrl).build()).execute()
                val originalJson = JSONObject(originalResponse.body?.string() ?: "")

                val names = originalJson.getJSONArray("names")
                var jp = ""
                var romaji = ""
                var en = ""
// Extract game title from Albums
                // Extract formatted game title from Albums
                var gameTitle = ""
                if (originalJson.has("albums")) {
                    val albums = originalJson.getJSONArray("albums")
                    for (i in 0 until albums.length()) {
                        val album = albums.getJSONObject(i)
                        val name = album.optString("name", "") // Japanese + English combined title
                        val albumId = album.optInt("id", -1)
                        val discType = album.optString("discType")

                        if (discType == "Game") {
                            // same Touhou-number logic as before
                            val altNames = album.optString("additionalNames", "")
                            val touhouNumber = when {
                                name.contains("東方永夜抄") -> "Touhou 8"
                                name.contains("東方紅魔郷") -> "Touhou 6"
                                name.contains("東方妖々夢") -> "Touhou 7"
                                name.contains("東方花映塚") -> "Touhou 9"
                                name.contains("東方風神録") -> "Touhou 10"
                                name.contains("東方地霊殿") -> "Touhou 11"
                                name.contains("東方星蓮船") -> "Touhou 12"
                                name.contains("東方神霊廟") -> "Touhou 13"
                                name.contains("東方輝針城") -> "Touhou 14"
                                name.contains("東方紺珠伝") -> "Touhou 15"
                                name.contains("東方天空璋") -> "Touhou 16"
                                name.contains("東方鬼形獣") -> "Touhou 17"
                                name.contains("東方虹龍洞") -> "Touhou 18"
                                else -> ""
                            }

                            val englishTitle = altNames.split(",")
                                .map { it.trim() }
                                .firstOrNull { it.matches(Regex(".*[a-zA-Z].*")) } ?: ""

                            val cleanEnglish = englishTitle.replace(Regex("Touhou.*?~\\s*"), "").trim()
                            gameTitle = "$touhouNumber～ $cleanEnglish"
                            break
                        }

                        // fallback: check if it's from ZUN's music collection
                        if (discType == "Fanmade" && albumId != -1) {
                            // Fetch album tags
                            val albumUrl = "https://touhoudb.com/api/albums/$albumId?fields=Tags"
                            val tagResponse = OkHttpClient().newCall(Request.Builder().url(albumUrl).build()).execute()
                            val tagJson = JSONObject(tagResponse.body?.string() ?: "")
                            val tags = tagJson.optJSONArray("tags")

                            for (j in 0 until (tags?.length() ?: 0)) {
                                val tag = tags?.getJSONObject(j)?.optJSONObject("tag")
                                if (tag?.optString("name") == "ZUN's Music Collection") {
                                    gameTitle = "ZUN's Music Collection ～ ${name.replace("　", " ").trim()}"
                                    break
                                }
                            }

                            if (gameTitle.isNotBlank()) break
                        }
                    }

                }
// Check if the original song is a character theme
                var characterName = ""
                var characterThumbUrl = ""

                try {
                    val artistUrl = "https://touhoudb.com/api/songs/$originalId?fields=Artists"
                    val artistResp = OkHttpClient().newCall(Request.Builder().url(artistUrl).build()).execute()
                    val artistJson = JSONObject(artistResp.body?.string() ?: "")
                    val artistArray = artistJson.optJSONArray("artists")

                    for (i in 0 until (artistArray?.length() ?: 0)) {
                        val artistEntry = artistArray?.getJSONObject(i)
                        val category = artistEntry?.optString("categories")
                        val artistObj = artistEntry?.optJSONObject("artist")
                        val type = artistObj?.optString("artistType")

                        if (category == "Subject" && type == "Character") {
                            characterName = artistObj?.optString("additionalNames")?.split(",")?.firstOrNull {
                                it.matches(Regex(".*[a-zA-Z].*"))
                            } ?: artistEntry.optString("name")
                            val charId = artistObj.optInt("id", -1)

                            // Get thumbnail
                            val thumbReq = Request.Builder().url("https://touhoudb.com/api/artists/$charId?fields=MainPicture").build()
                            val thumbResp = OkHttpClient().newCall(thumbReq).execute()
                            val thumbJson = JSONObject(thumbResp.body?.string() ?: "")
                            characterThumbUrl = thumbJson.optJSONObject("mainPicture")
                                ?.optString("urlSmallThumb", "") ?: ""

                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CharacterTheme", "Failed to fetch character info", e)
                }


                val touhouTextView = findViewById<TextView>(R.id.touhouInfo)

                for (i in 0 until names.length()) {
                    val obj = names.getJSONObject(i)
                    val rawValue = obj.getString("value")

                    when (obj.getString("language")) {
                        "Japanese" -> jp = smartBreakOnTilde(rawValue, touhouTextView)
                        "Romaji" -> romaji = smartBreakOnTilde(rawValue, touhouTextView)
                        "English" -> en = smartBreakOnTilde(rawValue, touhouTextView)
                    }
                }



                val spotifyLink = "https://open.spotify.com/search/" + Uri.encode(jp)
                val full = SpannableStringBuilder()

// JP title
                val jpSpan = SpannableString("$jp\n")
                jpSpan.setSpan(ForegroundColorSpan(Color.parseColor("#A5C9FF")), 0, jp.length, 0)
                jpSpan.setSpan(StyleSpan(Typeface.BOLD), 0, jp.length, 0)
                jpSpan.setSpan(RelativeSizeSpan(1.2f), 0, jp.length, 0)
                full.append(jpSpan)

// Romaji
                val romajiSpan = SpannableString("$romaji\n")
                romajiSpan.setSpan(StyleSpan(Typeface.ITALIC), 0, romaji.length, 0)
                romajiSpan.setSpan(RelativeSizeSpan(0.85f), 0, romaji.length, 0)
                romajiSpan.setSpan(ForegroundColorSpan(Color.parseColor("#CCCCCC")), 0, romaji.length, 0)
                full.append(romajiSpan)

// English title
                val enSpan = SpannableString("$en\n")
                enSpan.setSpan(StyleSpan(Typeface.NORMAL), 0, en.length, 0)
                enSpan.setSpan(RelativeSizeSpan(1.0f), 0, en.length, 0)
                full.append(enSpan)

// 🔗 Spotify link — right after song name
                val linkText = "🔗 Search on Spotify\n\n"
                val linkSpan = SpannableString(linkText)
                linkSpan.setSpan(URLSpan(spotifyLink), 0, linkText.length, 0)
                linkSpan.setSpan(ForegroundColorSpan(Color.parseColor("#88C0D0")), 0, linkText.length, 0)
                full.append(linkSpan)

// 🎮 Game
                if (gameTitle.isNotBlank()) {
                    val fromSpan = SpannableString("🎮 From: $gameTitle\n\n")
                    fromSpan.setSpan(StyleSpan(Typeface.BOLD), 0, fromSpan.length, 0)
                    fromSpan.setSpan(ForegroundColorSpan(Color.parseColor("#AAAAAA")), 0, fromSpan.length, 0)
                    fromSpan.setSpan(RelativeSizeSpan(0.9f), 0, fromSpan.length, 0)
                    full.append(fromSpan)
                }

// 🎭 Character name will come after this as usual


                val characterLayout = findViewById<LinearLayout>(R.id.characterThemeSection)
                val charNameView = findViewById<TextView>(R.id.characterNameText)
                val charImageView = findViewById<ImageView>(R.id.characterImage)

                if (characterName.isNotBlank()) {
                    runOnUiThread {
                        charNameView.text = "🎭 Character Theme: $characterName"
                        characterLayout.visibility = View.VISIBLE
                    }

                    if (characterThumbUrl.isNotBlank()) {
                        Thread {
                            try {
                                val imgStream = URL(characterThumbUrl).openStream()
                                val bmp = BitmapFactory.decodeStream(imgStream)
                                runOnUiThread {
                                    charImageView.setImageBitmap(bmp)
                                }
                            } catch (e: Exception) {
                                Log.e("CharacterImage", "Failed to load character thumbnail", e)
                            }
                        }.start()
                    } else {
                        runOnUiThread {
                            charImageView.setImageDrawable(null) // or placeholder
                        }
                    }
                } else {
                    runOnUiThread {
                        characterLayout.visibility = View.GONE
                    }
                }







                runOnUiThread {
                    touhouText.text = full
                    touhouText.movementMethod = LinkMovementMethod.getInstance()
                }

            } catch (e: Exception) {
                Log.e("TouhouDB", "🔥 Error fetching data", e)
                runOnUiThread {
                    findViewById<TextView>(R.id.touhouInfo).text = "❌ Failed to fetch Touhou info."
                }
            }
        }.start()
    }



    private fun exchangeCodeForToken(code: String) {
        Thread {
            try {
                val client = OkHttpClient()
                val formBody = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .build()

                val request = Request.Builder()
                    .url("https://accounts.spotify.com/api/token")
                    .post(formBody)
                    .build()

                client.newCall(request).execute().use { resp ->
                    val json = JSONObject(resp.body!!.string())
                    accessToken = json.getString("access_token")
                    Log.d("TokenExchange", "Access token: $accessToken")
                    sendLogToWebhook("Access token obtained: $accessToken", testingWebhookUrl)

                    fetchCurrentlyPlaying(accessToken!!)
                }
            } catch (e: Exception) {
                Log.e("TokenExchange", "Error exchanging code", e)
            }
        }.start()
    }

    private fun setupRefreshButton() {
        val refreshIcons = listOf(
            findViewById<ImageView>(R.id.refreshIcon),
        )

        for (icon in refreshIcons) {
            icon?.setOnClickListener {
                if (accessToken != null) {
                    Toast.makeText(this, "Refreshing track info…", Toast.LENGTH_SHORT).show()
                    fetchCurrentlyPlaying(accessToken!!)
                } else {
                    Toast.makeText(this, "No token available. Please log in again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun scrapeOriginalSong(title: String) {
        // Just do a simplified version of your scrapeTouhouDB that searches title exactly
        // You can even reuse most of scrapeTouhouDB but skip all fallback trimming
        scrapeTouhouDB(title)
    }

    private fun smartBreakOnTilde(text: String, textView: TextView): String {
        val paint = textView.paint
        val availableWidth = textView.width - textView.paddingLeft - textView.paddingRight
        val fullWidth = paint.measureText(text)

        return if (fullWidth > availableWidth && text.contains("～")) {
            text.replace(Regex("""\s*[～~]\s*"""), " ～\n")
        } else {
            text
        }
    }


    private fun fetchCurrentlyPlaying(token: String) {
        Thread {
            try {
                val client = OkHttpClient()
                val request = Request.Builder()
                    .url("https://api.spotify.com/v1/me/player/currently-playing")
                    .addHeader("Authorization", "Bearer $token")
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (resp.code == 204) {
                        updateUI("Nothing is playing right now.")
                        runOnUiThread {
                            findViewById<TextView>(R.id.touhouInfo).text = ""
                            findViewById<ImageView>(R.id.albumArt).setImageResource(R.mipmap.ic_launcher)
                        }
                        return@use
                    }

                    val body = resp.body?.string()
                    if (body.isNullOrBlank() || !body.trim().startsWith("{")) {
                        sendLogToWebhook("⚠️ Unexpected response from Spotify: $body", testingWebhookUrl)
                        updateUI("Spotify returned invalid data.")
                        return@use
                    }

                    val json = JSONObject(body)
                    val item = json.getJSONObject("item")

                    val name = item.getString("name")
                    val artistArray = item.getJSONArray("artists")

                    val artistNames = mutableListOf<String>()
                    for (i in 0 until artistArray.length()) {
                        artistNames.add(artistArray.getJSONObject(i).getString("name"))
                    }

                    val isOriginalZUN = artistNames.any { it == "ZUN" || it.contains("上海アリス幻樂団") }

                    val displayName = formatTildeTitle(name)
                    val fullText = if (isOriginalZUN) {
                        "🎧 Now playing (original):\n$displayName\nby ${artistNames.joinToString(", ")}"
                    } else {
                        "🎧 Now playing:\n$displayName\nby ${artistNames.firstOrNull().orEmpty()}"
                    }
                    sendLogToWebhook("Currently playing: $name by ${artistNames.joinToString()}", testingWebhookUrl)


                    updateUI(fullText)

                    val images = item.getJSONObject("album").getJSONArray("images")
                    val imageUrl = if (images.length() > 0) images.getJSONObject(0).getString("url") else null


                    // Load album art if available
                    runOnUiThread {
                        val albumArt = findViewById<ImageView>(R.id.albumArt)
                        if (imageUrl != null) {
                            Thread {
                                try {
                                    val imageStream = java.net.URL(imageUrl).openStream()
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(imageStream)
                                    runOnUiThread {
                                        albumArt.setImageBitmap(bitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e("AlbumArt", "Failed to load image", e)
                                    albumArt.setImageResource(R.mipmap.ic_launcher)
                                }
                            }.start()
                        } else {
                            albumArt.setImageResource(R.mipmap.ic_launcher)
                        }
                    }
// ✅ If it's already an original, go get info directly
                    if (isOriginalZUN) {
                        scrapeOriginalSong(name)
                    } else {
                        scrapeTouhouDB(name)
                    }
                    // Only send song name to TouhouDB
                    scrapeTouhouDB(name)
                }
            } catch (e: Exception) {
                Log.e("NowPlaying", "Error fetching track", e)
                sendLogToWebhook("⚠️ Spotify fetch error: ${e.localizedMessage}", testingWebhookUrl)

            }
        }.start()
    }

    private fun formatTildeTitle(text: String): String {
        return text.replace(Regex("""\s*[～~]\s*"""), " ～\n")
    }


    private fun updateUI(text: String) {
        runOnUiThread {
            findViewById<TextView>(R.id.trackInfo).text = text
            val trackInfo = findViewById<TextView>(R.id.trackInfo)
            trackInfo.setShadowLayer(6f, 0f, 0f, Color.BLACK)


        }
    }

}
