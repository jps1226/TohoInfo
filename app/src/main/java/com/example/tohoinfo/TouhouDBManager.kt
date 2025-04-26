package com.example.tohoinfo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

object TouhouDBManager {

    data class SongInfo(
        val jp: String,
        val romaji: String?,
        val en: String?,
        val gameTitle: String?,
        val characterName: String?,
        val characterThumbUrl: String?,
        val genres: List<String>
    )

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
    private fun findArrangementViaAlbumDisambiguation(
        albumName: String?,
        spotifyTrackTitle: String,
        spotifyYear: Int?,
        primaryArtist: String,
        allArtists: List<String>,
        client: OkHttpClient
    ): Pair<Int, Int> {
        if (albumName.isNullOrBlank()) return -1 to -1

        val variants = buildAlbumNameVariants(albumName)
        val albumMatches = mutableListOf<Pair<JSONObject, Int>>()

        for (variant in variants) {
            val encoded = Uri.encode(variant)
            val url = "https://touhoudb.com/api/albums?query=$encoded&start=0&maxResults=10&nameMatchMode=Auto&fields=Artists,Tracks"
            Log.d("TouhouDB", "📡 Querying album variant: \"$variant\" → $url")

            val resp = client.newCall(Request.Builder().url(url).build()).execute()
            val body = resp.body?.string() ?: continue
            val items = JSONObject(body).optJSONArray("items") ?: continue
            Log.d("TouhouDB", "🎯 Found ${items.length()} album(s) for variant \"$variant\"")

            for (i in 0 until items.length()) {
                val album = items.getJSONObject(i)
                val score = computeAlbumMatchScore(album, primaryArtist, allArtists, spotifyYear)
                albumMatches.add(album to score)
            }
        }
        Log.d("TouhouDB", "🧪 Evaluating albums for matching:")
        for ((album, score) in albumMatches) {
            val name = album.optString("name")
            val artistList = album.optJSONArray("artists")?.let {
                (0 until it.length()).joinToString(", ") { i ->
                    it.getJSONObject(i).optJSONObject("artist")?.optString("name") ?: "?"
                }
            } ?: "N/A"
            Log.d("TouhouDB", "   • Album: \"$name\" — Score: $score — Artists: $artistList")
        }

        val titleVariants = buildTitleVariants(spotifyTrackTitle)

        for ((album, _) in albumMatches.sortedByDescending { it.second }) {
            val tracks = album.optJSONArray("tracks") ?: continue
            for (i in 0 until tracks.length()) {
                val track = tracks.getJSONObject(i)
                val name = track.optString("name")
                if (titleVariants.any { variant ->
                        name.equals(variant, ignoreCase = true) ||
                                name.contains(variant, ignoreCase = true) ||
                                variant.contains(name, ignoreCase = true)
                    }) {
                    val song = track.optJSONObject("song") ?: continue
                    Log.d("TouhouDB", "✅ Matched track \"$name\" in album \"${album.optString("name")}\"")
                    return song.optInt("id", -1) to song.optInt("originalVersionId", -1)
                }
            }
        }

        Log.d("TouhouDB", "❌ No matching track found in any top-ranked albums.")
        return -1 to -1


        return -1 to -1
    }


    private fun computeAlbumMatchScore(
        album: JSONObject,
        primaryArtist: String,
        allArtists: List<String>,
        spotifyYear: Int?
    ): Int {
        var score = 0
        val artistArray = album.optJSONArray("artists") ?: return 0
        val albumYear = album.optJSONObject("releaseDate")?.optInt("year")

        for (i in 0 until artistArray.length()) {
            val artistObj = artistArray.getJSONObject(i).optJSONObject("artist") ?: continue
            val name = artistObj.optString("name")
            val aliases = artistObj.optString("additionalNames")

            if (name.equals(primaryArtist, ignoreCase = true) ||
                aliases.contains(primaryArtist, ignoreCase = true)) {
                score += 80
            } else if (allArtists.any { it.equals(name, ignoreCase = true) || aliases.contains(it, ignoreCase = true) }) {
                score += 40
            }
        }

        if (spotifyYear != null && albumYear != null && spotifyYear == albumYear) {
            score += 25
        }

        return score
    }

    private fun findArrangementByArtistQuery(
        trackTitle: String,
        primaryArtist: String,
        client: OkHttpClient
    ): Pair<Int, Int> {
        try {
            val encodedArtist = Uri.encode(primaryArtist)
            val artistSearchUrl = "https://touhoudb.com/api/artists?query=$encodedArtist&nameMatchMode=Auto&maxResults=10"
            Log.d("TouhouDB", "🔍 Looking up artist: \"$primaryArtist\" → $artistSearchUrl")

            val artistResp = client.newCall(Request.Builder().url(artistSearchUrl).build()).execute()
            val artistJson = JSONObject(artistResp.body?.string() ?: "")
            val artistItems = artistJson.optJSONArray("items") ?: return -1 to -1

            val artistId = artistItems.optJSONObject(0)?.optInt("id", -1) ?: -1
            if (artistId == -1) return -1 to -1

            val titleVariants = buildTitleVariants(trackTitle)
            Log.d("TouhouDB", "🎵 Searching songs by artist ID $artistId with title variants: $titleVariants")

            for (variant in titleVariants) {
                val encodedQuery = Uri.encode(variant)
                val songSearchUrl = "https://touhoudb.com/api/songs?query=$encodedQuery&artistId[]=$artistId&maxResults=10"
                Log.d("TouhouDB", "🎯 Querying: $songSearchUrl")

                val songResp = client.newCall(Request.Builder().url(songSearchUrl).build()).execute()
                val songJson = JSONObject(songResp.body?.string() ?: "")
                val songItems = songJson.optJSONArray("items") ?: continue

                for (i in 0 until songItems.length()) {
                    val song = songItems.getJSONObject(i)
                    if (song.optString("songType") == "Arrangement") {
                        val songName = song.optString("name")
                        Log.d("TouhouDB", "✅ Matched arrangement: \"$songName\"")
                        return song.optInt("id", -1) to song.optInt("originalVersionId", -1)
                    }
                }
            }

            Log.d("TouhouDB", "❌ No matching song found by artist.")
        } catch (e: Exception) {
            Log.e("TouhouDB", "🔥 Error in artist-based matching", e)
        }

        return -1 to -1
    }


    private fun buildAlbumNameVariants(original: String): List<String> {
        val base = original.trim()
        val variants = mutableSetOf<String>()

        // Add raw form
        variants.add(base)

        // Remove common suffixes like DISC02 CODE : XX
        val suffixRegex = Regex("""[\s\-–—ー]*(DISC\s?\d{1,2}|CODE\s?:?.*|DISC[0-9]{2}.*)$""", RegexOption.IGNORE_CASE)
        val suffixStripped = base.replace(suffixRegex, "").trim()
        variants.add(suffixStripped)

        // Also remove bracketed expressions like 【特別版】 etc.
        val bracketStripped = base.replace("""[(（【\[].*?[)）】\]]""".toRegex(), "").trim()
        variants.add(bracketStripped)

        // Combined: both suffix and brackets
        val fullyClean = bracketStripped.replace(suffixRegex, "").trim()
        variants.add(fullyClean)

        // Also remove anything after colon (some titles use it stylistically)
        val colonStripped = base.substringBefore(":").trim()
        variants.add(colonStripped)

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

    private fun findOriginalIdByFallbackQueries(client: OkHttpClient, queries: List<String>): Triple<Int, Int, String> {
        var arrangementId = -1
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
                    if (song.optString("songType") == "Arrangement") {
                        arrangementId = song.getInt("id")
                        originalId = song.optInt("originalVersionId", -1)
                        break
                    }
                }
            }

            if (originalId != -1) break // Found a match, stop iterating
        }

        return Triple(arrangementId, originalId, lastSearchUrl)
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

    private fun fetchSongInfo(arrangementId: Int, originalId: Int, client: OkHttpClient): SongInfo? {
        // Get names + album + character from the original
        val originalUrl = "https://touhoudb.com/api/songs/$originalId?fields=Names,Albums"
        val originalJson = JSONObject(client.newCall(Request.Builder().url(originalUrl).build()).execute().body?.string() ?: return null)

        val names = originalJson.getJSONArray("names")
        var jp = ""
        var romaji: String? = null
        var en: String? = null
        for (i in 0 until names.length()) {
            val obj = names.getJSONObject(i)
            val value = obj.getString("value")
            when (obj.getString("language")) {
                "Japanese" -> jp = value
                "Romaji" -> romaji = value
                "English" -> en = value
            }
        }

        val gameTitle = parseGameTitleFromAlbums(originalJson, client)
        val (characterName, characterThumbUrl) = parseCharacterThemeInfo(originalId, client)

        // Get genres from the arrangement
        val genreTags = mutableListOf<String>()
        val arrangementUrl = "https://touhoudb.com/api/songs/$arrangementId?fields=Tags"
        val arrangementJson = JSONObject(client.newCall(Request.Builder().url(arrangementUrl).build()).execute().body?.string() ?: "")
        val tags = arrangementJson.optJSONArray("tags")

        if (tags != null) {
            for (i in 0 until tags.length()) {
                val tagObj = tags.getJSONObject(i)
                val tag = tagObj.optJSONObject("tag")
                if (tag?.optString("categoryName") == "Genres") {
                    val genreName = tag.optString("name").ifBlank { tag.optString("additionalNames") }
                    if (genreName.isNotBlank()) genreTags.add(genreName)
                }
            }
        }

        return SongInfo(
            jp = jp,
            romaji = romaji,
            en = en,
            gameTitle = gameTitle,
            characterName = characterName,
            characterThumbUrl = characterThumbUrl,
            genres = genreTags // <-- not genreTags
        )
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
                val genres = mutableListOf<String>()
                val tags = tagJson.optJSONArray("tags")
                if (tags != null) {
                    Log.d("TouhouDB", "🔖 Tag count: ${tags.length()}")

                    for (i in 0 until tags.length()) {
                        val tagObj = tags.getJSONObject(i)
                        val tag = tagObj.optJSONObject("tag")

                        val cat = tag?.optString("categoryName")
                        val name = tag?.optString("name")
                        val alt = tag?.optString("additionalNames")

                        Log.d("TouhouDB", "🔍 Tag #$i - Category: $cat, Name: $name, Additional: $alt")

                        if (cat == "Genres") {
                            val genreName = name?.ifBlank { alt }
                            if (!genreName.isNullOrBlank()) {
                                genres.add(genreName)
                                Log.d("TouhouDB", "🎼 Genre added: $genreName")
                            }
                        }
                    }
                } else {
                    Log.d("TouhouDB", "⚠️ No tags array found in song JSON.")
                }

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

    fun scrape(
        context: Context,
        query: String,
        spotifyYear: Int? = null,
        primarySpotifyArtist: String,
        allSpotifyArtists: List<String>
    ){
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


                val albumName = SpotifyManager.currentSpotifyAlbumName
                var (arrangementId, originalId) = findArrangementByArtistQuery(query, primarySpotifyArtist, client)

                if (arrangementId == -1 || originalId == -1) {
                    Log.d("TouhouDB", "❌ Artist-based match failed. Trying album disambiguation.")
                    val result = findArrangementViaAlbumDisambiguation(
                        albumName, query, spotifyYear, primarySpotifyArtist, allSpotifyArtists, client
                    )
                    arrangementId = result.first
                    originalId = result.second
                }


                val lastSearchUrl = "https://touhoudb.com/api/albums?query=" + Uri.encode(albumName ?: "")

                if (originalId == -1 || arrangementId == -1) {
                    Log.d("TouhouDB", "❌ Album+artist disambiguation failed. Falling back to title-based search.")
                    val fallbackResult = findOriginalIdByFallbackQueries(client, fallbackQueries)
                    arrangementId = fallbackResult.first
                    originalId = fallbackResult.second
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
                val songInfo = fetchSongInfo(arrangementId, originalId, client) ?: return@Thread
                Log.d("TouhouDB", "✅ Using arrangement ID: $arrangementId")
                Log.d("TouhouDB", "✅ Using original song ID: $originalId")
                Log.d("TouhouDB", "🎯 Displayed title: ${songInfo.jp}")
                Log.d("TouhouDB", "🎼 Genres: ${songInfo.genres.joinToString(", ")}")
                // ✅ DEBUG: Fetch and log artists from final arrangement
                try {
                    val artistUrl = "https://touhoudb.com/api/songs/$arrangementId?fields=Artists"
                    val artistResp = client.newCall(Request.Builder().url(artistUrl).build()).execute()
                    val artistJson = JSONObject(artistResp.body?.string() ?: "")
                    val artistArray = artistJson.optJSONArray("artists")

                    Log.d("TouhouDB", "🎤 Artists for arrangement ID $arrangementId:")
                    for (i in 0 until (artistArray?.length() ?: 0)) {
                        val artistEntry = artistArray?.getJSONObject(i)
                        val name = artistEntry?.optString("name")
                        val roles = artistEntry?.optString("roles")
                        val category = artistEntry?.optString("categories")
                        val artistType = artistEntry?.optJSONObject("artist")?.optString("artistType")
                        val altNames = artistEntry?.optJSONObject("artist")?.optString("additionalNames")

                        Log.d("TouhouDB", "   • $name [$roles] ($category, $artistType) aka: $altNames")
                    }
                } catch (e: Exception) {
                    Log.e("TouhouDB", "❌ Failed to fetch artist info for arrangement", e)
                }


                val spotifyLink = "https://open.spotify.com/search/" + Uri.encode(songInfo.jp)

                ui.runOnUiThread {
                    // 🔗 Spotify link
                    Log.d("TouhouDB", "🧾 SongInfo contents: ${Gson().toJson(songInfo)}")
                    UIUpdater.showTouhouInfo(
                        context,
                        songInfo.jp,
                        songInfo.romaji,
                        songInfo.en,
                        songInfo.gameTitle,
                        spotifyLink,
                        songInfo.characterName,
                        songInfo.characterThumbUrl,
                        songInfo.genres
                    )

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



