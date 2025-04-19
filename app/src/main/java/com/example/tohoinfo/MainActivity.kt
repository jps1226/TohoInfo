package com.example.tohoinfo

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity


class MainActivity : AppCompatActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        supportActionBar?.hide()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SpotifyManager.login(this)
        findViewById<Button>(R.id.nextSongButton).setOnClickListener {
            val titleView = findViewById<TextView>(R.id.touhouInfo)
            val spotifyView = findViewById<TextView>(R.id.touhouSpotifyLink)
            val gameView = findViewById<TextView>(R.id.touhouGameTitle)
            val characterSection = findViewById<View>(R.id.characterThemeSection)
            val charImage = findViewById<ImageView>(R.id.characterImage)

            UIUpdater.setOriginalSongTitles(titleView, "", "", "")
            spotifyView.text = ""
            gameView.text = ""
            spotifyView.visibility = View.GONE
            gameView.visibility = View.GONE
            characterSection.visibility = View.GONE
            charImage.setImageDrawable(null)

            val characterLayout = (this).findViewById<LinearLayout>(R.id.characterThemeSection)
            characterLayout.visibility = View.GONE
            SpotifyManager.skipToNextTrack(this)
        }

        val refreshButton = findViewById<ImageView>(R.id.refreshIcon)
        refreshButton.setOnClickListener {
            val characterLayout = (this).findViewById<LinearLayout>(R.id.characterThemeSection)
            characterLayout.visibility = View.GONE
            SpotifyManager.fetchCurrentlyPlaying(this)
        }

    }

    @Deprecated("Used for Spotify Auth. Consider replacing with ActivityResultLauncher in future.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        SpotifyManager.handleAuthResult(this, requestCode, resultCode, data)
    }




}
