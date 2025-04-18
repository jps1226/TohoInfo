package com.example.tohoinfo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
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
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URL
import androidx.core.graphics.toColorInt

object TouhouDBManager {

    @SuppressLint("CutPasteId")
    fun scrape(context: Context, query: String) {
        val ui = (context as MainActivity)

        val touhouText = ui.findViewById<TextView>(R.id.touhouInfo)
        ui.runOnUiThread {
            touhouText.text = ui.getString(R.string.searching_touhoudb)
            touhouText.visibility = View.VISIBLE
        }

            Thread {
                try {
                    val client = OkHttpClient()
                    val fallbackQueries = mutableListOf(query)

                    // Add trimmed variations
                    val noBrackets = query.replace("""[(\[（【].*?[])）】]""".toRegex(), "").trim()
                    if (noBrackets != query) fallbackQueries.add(noBrackets)

                    val featRegex = """(?i)\s*(feat\.|with)\s.+$""".toRegex()
                    val noFeat = query.replace(featRegex, "").trim()
                    if (noFeat != query) fallbackQueries.add(noFeat)

// Also run feat-removal on already cleaned variants
                    val noBracketsFeat = noBrackets.replace(featRegex, "").trim()
                    if (noBracketsFeat != noBrackets && !fallbackQueries.contains(noBracketsFeat)) fallbackQueries.add(noBracketsFeat)


                    val noVersionSuffix = noFeat.replace("""[-–]\s*(Short|Full)?\s*Ver\.?""".toRegex(RegexOption.IGNORE_CASE), "").trim()
                    if (noVersionSuffix != noFeat) fallbackQueries.add(noVersionSuffix)

                    val noTilde = noVersionSuffix.substringBefore("~").trim()
                    if (noTilde != noVersionSuffix && noTilde.isNotBlank()) fallbackQueries.add(noTilde)


                    var originalId = -1
                    var lastSearchUrl = ""

                    for (variant in fallbackQueries) {
                        val encodedQuery = Uri.encode(variant)
                        Log.d("TouhouDB", "Trying query variant: $variant")

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
                        Log.d("TouhouDB", "❌ No matching arrangement found. Query: $query")

                        // Send log to Discord
                        try {
                            val escapedQuery = query.replace("`", "'").replace("\"", "\\\"")
                            val logJson = JSONObject().apply {
                                put("username", "TohoInfo App")
                                put("content", "⚠️ **No matching arrangement found**\n**Query:** `$escapedQuery`\n**Search URL:** $lastSearchUrl")
                            }

                            val requestBody = logJson.toString()
                                .toRequestBody("application/json".toMediaTypeOrNull())

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

                        (context).runOnUiThread {
                            touhouText.text = ui.getString(R.string.no_arrangement_found)
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
                                characterName = artistObj.optString("additionalNames").split(",").firstOrNull {
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


                    val touhouTextView = ui.findViewById<TextView>(R.id.touhouInfo)

                    for (i in 0 until names.length()) {
                        val obj = names.getJSONObject(i)
                        val rawValue = obj.getString("value")

                        when (obj.getString("language")) {
                            "Japanese" -> jp = UIUpdater.smartBreakOnTilde(rawValue, touhouTextView)
                            "Romaji" -> romaji = UIUpdater.smartBreakOnTilde(rawValue, touhouTextView)
                            "English" -> en = UIUpdater.smartBreakOnTilde(rawValue, touhouTextView)
                        }
                    }



                    val spotifyLink = "https://open.spotify.com/search/" + Uri.encode(jp)
                    val full = SpannableStringBuilder()

// JP title
                    val jpSpan = SpannableString("$jp\n")
                    jpSpan.setSpan(ForegroundColorSpan("#A5C9FF".toColorInt()), 0, jp.length, 0)
                    jpSpan.setSpan(StyleSpan(Typeface.BOLD), 0, jp.length, 0)
                    jpSpan.setSpan(RelativeSizeSpan(1.2f), 0, jp.length, 0)
                    full.append(jpSpan)

// Romaji
                    val romajiSpan = SpannableString("$romaji\n")
                    romajiSpan.setSpan(StyleSpan(Typeface.ITALIC), 0, romaji.length, 0)
                    romajiSpan.setSpan(RelativeSizeSpan(0.85f), 0, romaji.length, 0)
                    romajiSpan.setSpan(ForegroundColorSpan("#CCCCCC".toColorInt()), 0, romaji.length, 0)
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
                    linkSpan.setSpan(ForegroundColorSpan("#88C0D0".toColorInt()), 0, linkText.length, 0)
                    full.append(linkSpan)

// 🎮 Game
                    if (gameTitle.isNotBlank()) {
                        val fromSpan = SpannableString("🎮 From: $gameTitle\n\n")
                        fromSpan.setSpan(StyleSpan(Typeface.BOLD), 0, fromSpan.length, 0)
                        fromSpan.setSpan(ForegroundColorSpan("#AAAAAA".toColorInt()), 0, fromSpan.length, 0)
                        fromSpan.setSpan(RelativeSizeSpan(0.9f), 0, fromSpan.length, 0)
                        full.append(fromSpan)
                    }

// 🎭 Character name will come after this as usual


                    val characterLayout = ui.findViewById<LinearLayout>(R.id.characterThemeSection)
                    val charNameView = ui.findViewById<TextView>(R.id.characterNameText)
                    val charImageView = ui.findViewById<ImageView>(R.id.characterImage)

                    if (characterName.isNotBlank()) {
                        ui.runOnUiThread {
                            charNameView.text = ui.getString(R.string.character_theme_label, characterName)
                            characterLayout.visibility = View.VISIBLE
                        }

                        if (characterThumbUrl.isNotBlank()) {
                            Thread {
                                try {
                                    val imgStream = URL(characterThumbUrl).openStream()
                                    val bmp = BitmapFactory.decodeStream(imgStream)
                                    ui.runOnUiThread {
                                        charImageView.setImageBitmap(bmp)
                                    }
                                } catch (e: Exception) {
                                    Log.e("CharacterImage", "Failed to load character thumbnail", e)
                                }
                            }.start()
                        } else {
                            ui.runOnUiThread {
                                charImageView.setImageDrawable(null) // or placeholder
                            }
                        }
                    } else {
                        ui.runOnUiThread {
                            characterLayout.visibility = View.GONE
                        }
                    }







                    ui.runOnUiThread {
                        touhouText.text = full
                        touhouText.movementMethod = LinkMovementMethod.getInstance()
                    }

                } catch (e: Exception) {
                    Log.e("TouhouDB", "🔥 Error fetching data", e)
                    ui.runOnUiThread {
                        ui.findViewById<TextView>(R.id.touhouInfo).text = ui.getString(R.string.touhou_info_fetch_failed)
                    }
                }
            }.start()
        }
        // Replace `this@MainActivity` with `context`
    }



