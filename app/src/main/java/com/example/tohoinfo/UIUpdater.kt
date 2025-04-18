package com.example.tohoinfo

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object UIUpdater {
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
