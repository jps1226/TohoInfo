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
