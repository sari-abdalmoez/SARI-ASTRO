package com.sari.astro

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** Compatibility entry point kept for older navigation paths. */
class ProjectsActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        startActivity(Intent(this, GalleryActivity::class.java))
        finish()
    }
}
