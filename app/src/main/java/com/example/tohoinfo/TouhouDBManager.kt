package com.example.tohoinfo

import android.annotation.SuppressLint
import android.app.Activity
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
import androidx.core.text.HtmlCompat

object TouhouDBManager {

    @SuppressLint("CutPasteId")
    private fun tryAlbumDisambiguation(albumName: String?, spotifyTitle: String, spotifyYear: Int?): Int {
        if (albumName.isNullOrBlank()) return -1
        return try {
            val client = OkHttpClient()
            val albumNameVariants = buildAlbumNameVariants(albumName)
            val allAlbumItems = mutableListOf<JSONObject>()

            for (variant in albumNameVariants) {
                val encoded = Uri.encode(variant)
                val searchUrl = "https://touhoudb.com/api/albums?query=$encoded&start=0&maxResults=10&deleted=false&nameMatchMode=Auto&fields=Tracks"
                Log.d("TouhouDB", "🔎 Searching album variant: \"$variant\" → $searchUrl")

                val resp = client.newCall(Request.Builder().url(searchUrl).build()).execute()
                val body = resp.body?.string() ?: continue
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: continue

                Log.d("TouhouDB", "🔍 Found ${items.length()} album(s) for variant \"$variant\":")

                for (i in 0 until items.length()) {
                    val album = items.getJSONObject(i)
                    val name = album.optString("name")
                    val year = album.optJSONObject("releaseDate")?.optInt("year", -1)
                    Log.d("TouhouDB", "  • \"$name\" — Year: $year")
                    allAlbumItems.add(album)
                }
            }



            if (allAlbumItems.isEmpty()) {
                Log.d("TouhouDB", "❌ No albums returned after variant search.")
                return -1
            }

            Log.d("TouhouDB", "🧾 Spotify Album: \"$albumName\" — Release Year: $spotifyYear")

            val scoredAlbums = allAlbumItems.map { album ->
                val name = album.optString("name")
                val year = album.optJSONObject("releaseDate")?.optInt("year", -1)
                val nameScore = nameSimilarity(name, albumName)
                val yearScore = if (spotifyYear != null && year == spotifyYear) 100 else 0
                val totalScore = nameScore + yearScore

                Log.d("TouhouDB", "🗂️ Album \"$name\" — Year: $year — Score: $totalScore")
                album to totalScore
            }.sortedByDescending { it.second }

            val topAlbum = scoredAlbums.firstOrNull()?.first ?: run {
                Log.d("TouhouDB", "❌ No suitable album match found")
                return -1
            }

            val albumId = topAlbum.optInt("id", -1)
            if (albumId == -1) return -1

            val tracks = topAlbum.optJSONArray("tracks") ?: return -1


            val fallbackVariants = buildTitleVariants(spotifyTitle)
            Log.d("TouhouDB", "🎯 Track matching against ${fallbackVariants.size} title variants:")
            for (v in fallbackVariants) {
                Log.d("TouhouDB", "    • \"$v\"")
            }

            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                val trackName = track.optString("name").trim()
                Log.d("TouhouDB", "🔎 Checking track: \"$trackName\"")


                for (variant in fallbackVariants) {
                    if (trackName == variant) {
                        Log.d("TouhouDB", "🎵 Track matched in album: \"$trackName\" (matched variant: \"$variant\")")

                        return track.getJSONObject("song").optInt("originalVersionId", -1)
                    }

                }
            }

            Log.d("TouhouDB", "❌ No matching track found in album using variants.")
            -1
        } catch (e: Exception) {
            Log.e("TouhouDB", "Album disambiguation failed", e)
            -1
        }
    }

    private fun buildAlbumNameVariants(original: String): List<String> {
        val base = original.trim()
        val variants = mutableSetOf(base)

        // Remove dashes and suffixes like -KURENAI-, -Re, etc
        val dashTrimmed = base.replace(Regex("[-ー−‐―].*?$"), "").trim()
        variants.add(dashTrimmed)

        // Remove bracketed suffixes
        val bracketTrimmed = base.replace("""[(（【\[].*?[)）】\]]""".toRegex(), "").trim()
        variants.add(bracketTrimmed)

        // Generate smart alternates (e.g., add " Re", replace KURENAI with Re, etc.)
        if (base.contains("KURENAI", ignoreCase = true)) {
            variants.add(base.replace(Regex("KURENAI.*", RegexOption.IGNORE_CASE), "Re"))
            variants.add(base.replace(Regex("[-ー−‐―]KURENAI.*", RegexOption.IGNORE_CASE), " Re"))
            variants.add(base.replace("KURENAI", "Re", ignoreCase = true))
        }

        if (dashTrimmed.isNotEmpty()) {
            variants.add("$dashTrimmed Re")
            variants.add("$dashTrimmed -Re")
        }

        return variants.filter { it.isNotBlank() }.distinct()
    }

    private fun nameSimilarity(a: String, b: String): Int {
        val tokensA = a.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").split(" ").filter { it.isNotBlank() }
        val tokensB = b.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), " ").split(" ").filter { it.isNotBlank() }
        return tokensA.count { tokensB.contains(it) }
    }

    private fun buildTitleVariants(original: String): List<String> {
        val variants = mutableSetOf<String>()

        val trimmed = original.trim()
        variants.add(trimmed)

        // Normalize full-width tilde to ASCII tilde
        val normalizedTilde = trimmed.replace("～", "~")

        // Strip everything after tilde
        val noTilde = normalizedTilde.substringBefore("~").trim()
        if (noTilde != trimmed) variants.add(noTilde)

        // Normalize dash types
        val dashNormalized = normalizedTilde
            .replace("－", "-")
            .replace("ー", "-")
            .replace("−", "-")
            .replace("―", "-")
            .replace("‐", "-")

// Add variant that strips everything after the first dash
        val noDash = dashNormalized.substringBefore("-").trim()
        if (noDash.isNotBlank()) variants.add(noDash)


        // Remove bracketed phrases like （東方紅魔郷）
        val noBrackets = trimmed.replace("""[(\[（【].*?[)\]）】]""".toRegex(), "").trim()
        variants.add(noBrackets)

        // Combine: bracket-removed + tilde-stripped
        val bracketTildeNormalized = noBrackets.replace("～", "~")
        val combined = bracketTildeNormalized.substringBefore("~").trim()
        if (combined.isNotBlank()) variants.add(combined)

        // Remove remix-style suffixes
        val styleStripped = combined.replace(
            Regex("(?i)(violin rock|piano jazz|hardcore|vocal|arrange|ver\\.?|mix|remix).*"),
            ""
        ).trim()
        if (styleStripped.isNotBlank()) variants.add(styleStripped)

        // Remove feat/with suffixes
        val noFeat = styleStripped.replace("""(?i)\s*(feat\.|with)\s.+$""".toRegex(), "").trim()
        if (noFeat != styleStripped) variants.add(noFeat)

        return variants.filter { it.isNotBlank() }.distinct()
    }

    private fun buildFallbackQueries(original: String): List<String> {
        val variants = mutableListOf(original)

        // Remove bracketed expressions
        val noBrackets = original.replace("""[(\[（【].*?[)\]）】]""".toRegex(), "").trim()
        if (noBrackets != original) variants.add(noBrackets)

        // Remove feat./with suffixes
        val featRegex = """(?i)\s*(feat\.|with)\s.+$""".toRegex()
        val noFeat = original.replace(featRegex, "").trim()
        if (noFeat != original) variants.add(noFeat)

        // Apply feat-removal on the bracket-cleaned version too
        val noBracketsFeat = noBrackets.replace(featRegex, "").trim()
        if (noBracketsFeat != noBrackets && noBracketsFeat !in variants) variants.add(noBracketsFeat)

        // Remove remix/short/full ver. suffixes
        val noVersionSuffix = noFeat.replace("""[-–]\s*(Short|Full)?\s*Ver\.?""".toRegex(RegexOption.IGNORE_CASE), "").trim()
        if (noVersionSuffix != noFeat) variants.add(noVersionSuffix)

        // Remove anything after a tilde
        val noTilde = noVersionSuffix.substringBefore("~").trim()
        if (noTilde != noVersionSuffix && noTilde.isNotBlank()) variants.add(noTilde)

        return variants.distinct()
    }

    private fun findOriginalIdByFallbackQueries(client: OkHttpClient, queries: List<String>): Pair<Int, String> {
        var originalId = -1
        var lastSearchUrl = ""

        for (variant in queries) {
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

        return originalId to lastSearchUrl
    }

    private fun sendNoResultWebhook(query: String, lastSearchUrl: String, client: OkHttpClient) {
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
    }

    private fun parseGameTitleFromAlbums(originalJson: JSONObject, client: OkHttpClient): String {
        if (!originalJson.has("albums")) return ""

        val albums = originalJson.getJSONArray("albums")
        for (i in 0 until albums.length()) {
            val album = albums.getJSONObject(i)
            val name = album.optString("name", "")
            val albumId = album.optInt("id", -1)
            val discType = album.optString("discType")

            if (discType == "Game") {
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
                return "$touhouNumber～ $cleanEnglish"
            }

            if (discType == "Fanmade" && albumId != -1) {
                val albumUrl = "https://touhoudb.com/api/albums/$albumId?fields=Tags"
                val tagResponse = client.newCall(Request.Builder().url(albumUrl).build()).execute()
                val tagJson = JSONObject(tagResponse.body?.string() ?: "")
                val tags = tagJson.optJSONArray("tags")

                for (j in 0 until (tags?.length() ?: 0)) {
                    val tag = tags?.getJSONObject(j)?.optJSONObject("tag")
                    if (tag?.optString("name") == "ZUN's Music Collection") {
                        return "ZUN's Music Collection ～ ${name.replace("　", " ").trim()}"
                    }
                }
            }
        }

        return ""
    }

    private fun parseCharacterThemeInfo(originalId: Int, client: OkHttpClient): Pair<String, String> {
        var characterName = ""
        var characterThumbUrl = ""

        try {
            val artistUrl = "https://touhoudb.com/api/songs/$originalId?fields=Artists"
            val artistResp = client.newCall(Request.Builder().url(artistUrl).build()).execute()
            val artistJson = JSONObject(artistResp.body?.string() ?: "")
            val artistArray = artistJson.optJSONArray("artists")

            for (i in 0 until (artistArray?.length() ?: 0)) {
                val artistEntry = artistArray?.getJSONObject(i)
                val category = artistEntry?.optString("categories")
                val artistObj = artistEntry?.optJSONObject("artist")
                val type = artistObj?.optString("artistType")

                if (category == "Subject" && type == "Character") {
                    characterName = artistObj.optString("additionalNames")
                        .split(",")
                        .firstOrNull { it.matches(Regex(".*[a-zA-Z].*")) }
                        ?: artistEntry.optString("name")

                    val charId = artistObj.optInt("id", -1)

                    // Thumbnail
                    val thumbUrl = "https://touhoudb.com/api/artists/$charId?fields=MainPicture"
                    val thumbResp = client.newCall(Request.Builder().url(thumbUrl).build()).execute()
                    val thumbJson = JSONObject(thumbResp.body?.string() ?: "")
                    characterThumbUrl = thumbJson.optJSONObject("mainPicture")
                        ?.optString("urlSmallThumb", "") ?: ""

                    break
                }
            }
        } catch (e: Exception) {
            Log.e("CharacterTheme", "Failed to fetch character info", e)
        }

        return characterName to characterThumbUrl
    }

    fun scrape(context: Context, query: String, spotifyYear: Int? = null)
    {
        val ui = (context as MainActivity)
        val spotifyYear = try {
            SpotifyManager.currentSpotifyAlbumName?.let { albumName ->
                // This assumes you parse year from a reliable spot
                val field = SpotifyManager::class.java.getDeclaredField("currentSpotifyAlbumYear")
                field.isAccessible = true
                field.get(SpotifyManager)?.toString()?.toIntOrNull()
            }
        } catch (e: Exception) {
            null
        }

        val touhouText = ui.findViewById<TextView>(R.id.touhouInfo)
        ui.runOnUiThread {
            UIUpdater.setOriginalSongTitles(touhouText, "", "", "")
            ui.findViewById<TextView>(R.id.touhouSpotifyLink).visibility = View.GONE
            ui.findViewById<TextView>(R.id.touhouGameTitle).visibility = View.GONE
            ui.findViewById<View>(R.id.characterThemeSection).visibility = View.GONE
            ui.findViewById<ImageView>(R.id.characterImage).setImageDrawable(null)
            touhouText.visibility = View.VISIBLE
        }

        Thread {
            try {
                val client = OkHttpClient()
                val fallbackQueries = buildFallbackQueries(query)


                val (originalIdInitial, lastSearchUrl) = findOriginalIdByFallbackQueries(client, fallbackQueries)
                var originalId = originalIdInitial


                if (originalId == -1) {
                    Log.d("TouhouDB", "🔍 No match found in fallback queries. Attempting album disambiguation...")
                    val albumName = SpotifyManager.currentSpotifyAlbumName
                    Log.d("TouhouDB", "🎼 Album from Spotify: $albumName")

                    val albumFallbackId = tryAlbumDisambiguation(albumName, query, spotifyYear)
                    if (albumFallbackId != -1) {
                        Log.d("TouhouDB", "✅ Match found via album disambiguation. Song ID: $albumFallbackId")
                        originalId = albumFallbackId
                    } else {
                        Log.d("TouhouDB", "❌ Album disambiguation failed to find a matching track.")
                    }
                }


                if (originalId == -1) {
                    Log.d("TouhouDB", "❌ No matching arrangement found. Query: $query")
                    (context as Activity).runOnUiThread {
                        UIUpdater.showNoArrangementFound(touhouText)
                    }
                    // Send log to Discord
                    sendNoResultWebhook(query, lastSearchUrl, client)

                    (context as Activity).runOnUiThread {
                        UIUpdater.showNoArrangementFound(touhouText)

                        val spotify = ui.findViewById<TextView>(R.id.touhouSpotifyLink)
                        val game = ui.findViewById<TextView>(R.id.touhouGameTitle)
                        val charSection = ui.findViewById<View>(R.id.characterThemeSection)
                        val charImage = ui.findViewById<ImageView>(R.id.characterImage)

                        spotify.visibility = View.GONE
                        game.visibility = View.GONE
                        charSection.visibility = View.GONE
                        charImage.setImageDrawable(null)
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
                val gameTitle = parseGameTitleFromAlbums(originalJson, client)

// Check if the original song is a character theme
                val (characterName, characterThumbUrl) = parseCharacterThemeInfo(originalId, client)

                val touhouTextView = ui.findViewById<TextView>(R.id.touhouInfo)

                for (i in 0 until names.length()) {
                    val obj = names.getJSONObject(i)
                    val rawValue = obj.getString("value")

                    when (obj.getString("language")) {
                        "Japanese" -> jp = rawValue
                        "Romaji" -> romaji = rawValue
                        "English" -> en = rawValue
                    }
                }




                val spotifyLink = "https://open.spotify.com/search/" + Uri.encode(jp)
                (context as Activity).runOnUiThread {
                    UIUpdater.setOriginalSongTitles(touhouTextView, jp, romaji, en)
                }

                ui.runOnUiThread {
                    // 🔗 Spotify link
                    val spotifyLink = "https://open.spotify.com/search/" + Uri.encode(jp)
                    UIUpdater.showTouhouInfo(context, jp, romaji, en, gameTitle, spotifyLink, characterName, characterThumbUrl)

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
}



