package com.example.tohoinfo

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.text.HtmlCompat
import java.net.URL

object UIUpdater {
    private var currentTitleIndex = 0
    private var titleCycle: List<String> = emptyList()

    fun showTouhouInfo(
        context: Context,
        jp: String,
        romaji: String?,
        en: String?,
        gameTitle: String?,
        spotifyLink: String,
        characterName: String?,
        characterThumbUrl: String?,
        genres: List<String>
    ) {
        val ui = context as MainActivity

        ui.runOnUiThread {
            val titleView = ui.findViewById<TextView>(R.id.touhouInfo)
            val spotifyView = ui.findViewById<TextView>(R.id.touhouSpotifyLink)
            val gameView = ui.findViewById<TextView>(R.id.touhouGameTitle)
            val charText = ui.findViewById<TextView>(R.id.characterNameText)
            val charImage = ui.findViewById<ImageView>(R.id.characterImage)
            val charSection = ui.findViewById<LinearLayout>(R.id.characterThemeSection)


            // 🎵 Title toggle
            setOriginalSongTitles(titleView, jp, romaji, en)

            // 🔗 Spotify (use hardcoded link if available)
            val zunInfo = ZunSpotifyLinks.originalSongLinks[jp]
            val finalSpotifyLink = zunInfo?.spotifyUrl ?: spotifyLink

            spotifyView.text = "🔗 Listen on Spotify"
            spotifyView.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalSpotifyLink))
                    intent.putExtra(Intent.EXTRA_REFERRER, Uri.parse("android-app://" + context.packageName))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("UIUpdater", "Failed to open Spotify link", e)
                }
            }


            spotifyView.visibility = View.VISIBLE


// 🎼 Genres
            val genreView = ui.findViewById<TextView>(R.id.touhouGenres)
            if (genres.isNotEmpty()) {
                genreView.text = "🎼 Genre: ${genres.joinToString(", ")}"
                genreView.visibility = View.VISIBLE
            } else {
                genreView.visibility = View.GONE
            }

            // 🎮 Game
            if (!gameTitle.isNullOrBlank()) {
                gameView.text = "🎮 From: $gameTitle"
                gameView.visibility = View.VISIBLE
            } else {
                gameView.visibility = View.GONE
            }

            // 🎭 Character
            if (!characterName.isNullOrBlank()) {
                charText.text = ui.getString(R.string.character_theme_label, characterName)
                charSection.visibility = View.VISIBLE
            } else {
                charSection.visibility = View.GONE
                charImage.setImageDrawable(null)
            }
        }

        // 🎭 Load image async
        if (!characterThumbUrl.isNullOrBlank()) {
            Thread {
                try {
                    val bmp = BitmapFactory.decodeStream(URL(characterThumbUrl).openStream())
                    ui.runOnUiThread {
                        val charImage = ui.findViewById<ImageView>(R.id.characterImage)
                        charImage.setImageBitmap(bmp)
                    }
                } catch (e: Exception) {
                    Log.e("CharacterImage", "Failed to load character thumbnail", e)
                }
            }.start()
        }
    }

    fun showNoArrangementFound(view: TextView) {
        titleCycle = listOf("❌ No matching Touhou arrangement found.")
        currentTitleIndex = 0

        view.text = titleCycle.first()
        view.setOnClickListener(null) // disable toggling
        view.visibility = View.VISIBLE
    }

    fun setOriginalSongTitles(view: TextView, jp: String?, romaji: String?, en: String?) {
        titleCycle = listOfNotNull(jp, romaji, en).distinct()
        currentTitleIndex = 0

        // Post the update to ensure the view is laid out
        view.post {
            // Even if titleCycle is empty, set a fallback text.
            view.text = smartBreakOnTilde(titleCycle.firstOrNull() ?: "Unknown Title", view)
            view.visibility = View.VISIBLE
            view.requestLayout()

            // Set up tap listener to cycle the titles
            if (titleCycle.isNotEmpty()) {
                view.setOnClickListener {
                    currentTitleIndex = (currentTitleIndex + 1) % titleCycle.size
                    view.text = smartBreakOnTilde(titleCycle[currentTitleIndex], view)
                }
            }
        }
    }

    fun updateTrackText(context: Context, text: String) {
        (context as MainActivity).runOnUiThread {
            val trackInfo = context.findViewById<TextView>(R.id.trackInfo)
            trackInfo.text = text
            trackInfo.setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
    }

    fun hideCharacterSection(context: Context) {
        val ui = context as MainActivity
        ui.runOnUiThread {
            val layout = ui.findViewById<LinearLayout>(R.id.characterThemeSection)
            val charText = ui.findViewById<TextView>(R.id.characterNameText)
            val charImage = ui.findViewById<ImageView>(R.id.characterImage)

            charText.text = ""              // clear old name
            charImage.setImageDrawable(null) // clear old image
            layout.visibility = View.GONE   // hide it
        }
    }

    fun formatTildeTitle(text: String): String {
        return text.replace(Regex("""\s*[～~]\s*"""), " ～\n")
    }

    fun smartBreakOnTilde(text: String, view: TextView): String {
        val paint = view.paint
        val availableWidth = view.width - view.paddingLeft - view.paddingRight
        val fullWidth = paint.measureText(text)

        return if (fullWidth > availableWidth && text.contains("～")) {
            text.replace(Regex("""\s*[～~]\s*"""), " ～\n")
        } else {
            text
        }
    }
}